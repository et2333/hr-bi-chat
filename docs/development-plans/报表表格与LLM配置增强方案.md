# 报表数据表格 + LLM 配置增强 实现方案

## Context（背景）

三功能模块（LLM 配置/用户多租户/C端报表）已交付并验证。本次为两项完善任务：

1. **C端报表**：TABLE 组件目前只展示定义 JSON，不直观；用户确认需要真实数据表格——列=维度+指标，支持表头排序、维度值筛选、分页；图表展示保持现状。
2. **LLM 配置页**：用户确认新增 4 个配置项（top_p / 超时秒数 / 描述 / 系统提示词），并做易用性优化（表单分组、厂商 Base URL 联动预设、参数帮助 tooltip、高级参数折叠、健康状态 30s 自动轮询）。

已核实的契约约束：
- `QueryExecService.executeReadonly(String)` 纯文本 SQL、无参数化；`JdbcQueryExecutor` 硬上限 200 行、`total=rows.size()` → 真分页必须自建 COUNT 子查询。
- `ReportChartService` 已具备 def 解析（含模板 `metrics/dimensions`、time→org 兼容）、`parseAggregation`/`dimSpec`/`buildDirectSql|buildRatioSql`、`rewriteSql` 行级改写链路，全部可复用。
- Python `agent_gateway/app.py` `apply_llm_config` 已支持热应用，可扩展透传字段。

---

## 任务 A：C端报表 TABLE 数据表格（筛选/排序/分页）

### 后端

**新增 `ReportTableService.java`**（hrchat-report/service）：
- 入口 `TableView data(Long reportId, Long compId, UserContext ctx, int page, int size, String sortField, String sortOrder, List<String> dimValues)`：权限校验 `report:view` + `requireViewAccess`（与 ChartService 一致）。
- 复用 ReportChartService 的 def 解析与指标口径：
  - 解析 compType=2（TABLE）组件 defJson；支持 `{metric,dim}` 与模板 `{metrics:[],dimensions:[]}`（time→org）。
  - 每个指标独立调 `parseAggregation`+`buildDirectSql|buildRatioSql` 得到聚合 SQL（含 `{authz_org_filter}`），对 `executeReadonly` 结果按 `dim_value` hash 合并多指标列。
  - 分页：`SELECT COUNT(*) FROM (aggSql) t` 取 total；数据查询包装 `SELECT * FROM (aggSql) t WHERE t.dim_value IN ('...',...) ORDER BY <白名单列> <asc|desc> LIMIT n OFFSET m`；单引号转义防注入；比率列 NULLS LAST。
  - 排序白名单：`dim_value` 或指标名映射列（如 `metric_<code>`，仅数字指标列可排）；筛选取值来自 `dim-values` 接口（白名单维度值）。
- 新增 `GET /api/v1/reports/{reportId}/components/{compId}/data`（ChartController，参数如上）；`GET .../{compId}/dim-values` 返回该组件维度可选值（复用聚合 SQL 去重）。
- **ChartViews.java** 新增 `TableView/TableColumn` record（columns:{key,title,type,sortable}，rows:{dim_value, metrics:{code:value}}，total/page/size）。

### 前端

- **api/reports.ts**：`getTableData(reportId, compId, params)` + `getDimValues(reportId, compId)`。
- **detail.vue**：`v-else-if c.compType === 'TABLE'` 分支渲染 `a-table`（columns/rows 服务端数据）：
  - 表头排序：`onChange(pagination, filters, sorter)` → 带 `sortField/sortOrder` 重查。
  - 维度值筛选：顶部 `a-select` 多选（数据源 dim-values）+ 防抖重查。
  - 分页：`pagination.total` 服务端分页。
  - loading/失败降级沿用 `chartStates` 同款状态管理；ChartRenderer 不动。
- 导出保持现状（chartDataForExport 取图表数据）。

### 测试

- `ReportTableServiceTest`（仿 ReportChartServiceTest mock 模式：Mock RptComponentMapper/SemanticMetaService/AuthzService/QueryExecService，rewriteSql 原样返回）：多指标列构造、ORDER BY 方向、dimValue 过滤注入、LIMIT/OFFSET、total 独立 COUNT、模板 def 兼容。
- 前端 `views/reports/detail.spec.ts` 或 `reports.spec.ts` 补 TABLE 表格渲染断言（仿现有 mock 方式）。

---

## 任务 B：LLM 配置增强

### DDL 与 Java

- **schema-h2.sql** `llm_model_config` 加 4 列：`top_p DECIMAL(4,2) DEFAULT NULL`、`timeout_seconds INT DEFAULT 30`、`description VARCHAR(255) DEFAULT NULL`、`system_prompt TEXT`；**hrchat-deploy/sql/schema.sql** 生产版同步。
- **LlmModelConfig.java**：加 `topP/top_p`(BigDecimal)、`timeoutSeconds`(Integer)、`description`(String)、`systemPrompt`(String)。
- **LlmViews.java** CreateRequest/DetailView 加 4 字段；**LlmConfigService** create/patch：新字段落库、校验（topP∈[0,1]、timeoutSeconds≥1 越界抛 PARAM_INVALID）、patch 保持"空字段不覆盖"语义；**LlmDeployService** 部署/回滚 payload 透传 topP/timeoutSeconds/systemPrompt（description 仅存库不发运行时）。

### Python（hrchat-ai）

- **app.py** `POST /v1/config` 与 `GET /v1/config/current`：透传 `top_p`/`timeout_seconds`/`system_prompt`。
- **mock_llm.py** `get_llm_adapter` 签名加可选参数并挂同名字段（不破坏仅传 profile 的用例）。
- **openai_client.py**：top_p 入请求体、timeout_seconds 覆盖 httpx 超时、system_prompt 非空时 messages 前置 system 消息。

### 前端

- **llm.ts** `LlmModelCreateRequest` 加 4 字段。
- **index.vue** 表单重构：
  - 分组：基础信息（编码/名称/厂商/模型/描述）、部署（Base URL/API Key/部署地址）、高级参数（`a-collapse` 折叠：温度/最大 Tokens/top_p/超时）。
  - 厂商联动预设 baseUrl（openai→https://api.openai.com/v1、deepseek→https://api.deepseek.com、qwen→https://dashscope.aliyuncs.com/compatible-mode/v1、ollama→http://localhost:11434），仅当用户未手改 URL 时覆盖。
  - 参数帮助：a-form-item `tooltip`。
  - 校验：top_p 0-1、timeoutSeconds≥1（前端 min/max + rules）。
  - 健康监控：进入页面 `setInterval` 30s 调 `getLlmMonitor`+`checkLlmHealth` 自动刷新（复用现有 checkHealth），保留手动按钮，卸载 clearInterval。

### 测试

- `LlmConfigServiceTest`：新字段落库、topP 越界 PARAM_INVALID、patch 空字段不覆盖。
- `llm/index.spec.ts`：新增表单项文案、厂商切换预设 URL、轮询 timer（vi.useFakeTimers）。
- Python `test_config.py`：新字段透传与 current 回显。

---

## 验证（端到端）

1. Java：`mvn clean verify`（全模块，含 jacoco 门禁：report/model 核心 LINE≥80%）。
2. Python：`pytest`（hrchat-ai）。
3. 前端：`vitest run` + `pnpm build`。
4. 冒烟（重启后端 8080 + 前端 5199）：
   - 建 CUSTOM 报表含 TABLE 组件 → `table-data` 返回 columns/rows/total；带 sortField/sortOrder/dimValue/page 验证排序筛选分页；dim-values 返回下拉值。
   - 创建 LLM 配置带新字段 → 详情回显 → 部署 payload 含 topP/timeoutSeconds/systemPrompt → 健康自动轮询生效。
5. Playwright E2E 回归（ask/permission/report 3 spec）。

## 涉及关键文件

- 复用：`ReportChartService.java`（def 解析/聚合/改写链）、`ChartViews.java`、`QueryExecService`/`JdbcQueryExecutor`
- 新增：`ReportTableService.java`、`ReportTableServiceTest.java`
- 修改：`ChartController.java`、`detail.vue`、`api/reports.ts`、`schema-h2.sql`、`hrchat-deploy/sql/schema.sql`、`LlmModelConfig.java`、`LlmViews.java`、`LlmConfigService.java`、`LlmDeployService.java`、`app.py`、`mock_llm.py`、`openai_client.py`、`llm.ts`、`views/admin/llm/index.vue` 及对应测试
