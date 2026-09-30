# 租户级 LLM 配置 + C 端报表对标 QuickBI 增强方案

## Context（背景）

用户反馈三点，据此重设计（取代被拒的上一版方案）：
1. **当前只有 C 端，无独立管理端** → LLM 配置入口保留 `/admin/llm` 菜单但**按租户生效**（每个租户配置自己的模型），弱化"全局管理台"。
2. **大模型按租户配置**：租户可配 API key/模型，不同租户调不同模型；**无配置回退默认**（yml `hrchat.ai.*`）。确认采用**完整多租户运行时**（Python 租户→配置字典，按 X-Tenant-No 路由）。
3. **对标 QuickBI**（调研结论）：我们已有报表 CRUD/图表/订阅/导出/语义层权限；差距在**交互式数据表格、图表交互（切换/下钻）、AI 洞察解读、监控告警**。用户确认本轮实现：**表格数据（必做）+ 图表一键切换 + AI 洞察解读 + 图表下钻**；阈值告警本轮不做。

已核实契约：
- `executeReadonly(String)` 纯文本 SQL 无参数化、JdbcQueryExecutor 硬上限 200 行、total=rows.size → 分页须自建 COUNT。
- `ReportChartService` def 解析/聚合/改写链可复用（含模板 `metrics/dimensions`、time→org）。
- `TenantContextHolder.get()` 请求线程内可拿租户（TenantFilter 显式头才注入）；前端 http.ts 自动带 X-Tenant-No。
- Python 当前仅 config/chat 端点、`_runtime` 全局单例；dim_org 种子 3 层（集团→研发中心→研发一部/二部），研发一部/二部为叶子。

---

## 任务 A：租户级 LLM 配置（完整多租户运行时）

### DDL 与 Java
- **Flyway H2/MySQL V1 baseline**：`llm_model_config` 含 `tenant_id VARCHAR(16) NULL`（NULL=系统默认，非 NULL=租户专属）。`llm_deploy_state` 不加列，经 config 归属。
- **LlmModelConfig.java** 加 `tenantId`；**LlmConfigService** create/patch 从 `TenantContextHolder.get()` 写租户（null 回退 't01'），`list` 过滤 `tenant_id=当前 OR IS NULL`。
- **LlmDeployService** ACTIVE 查询按 config 归属过滤。
- **AgentRuntimeFactory**：单例 → `Map<String, AgentRuntimeClient>` 按租户懒加载缓存 + default 条目（保留 build 逻辑）；`getForTenant(String tenant)` 替代 getDelegate，调用方用 `TenantContextHolder.get()`（null 用 default）；`RemoteAgentRuntimeClient` 构造加 tenantNo、请求头带 X-Tenant-No；`ApplicationReadyEvent` 预热 default。
- **Flyway V2 demo seed**：t01 ACTIVE 模型、t02 ACTIVE 模型、一条 tenant_id NULL 默认 ACTIVE（演示回退）。

### Python（hrchat-ai/agent_gateway）
- `_runtime` → `_runtimes: dict[str, runtime]` + `_default`（yml LLM_PROFILE 兜底）。
- `POST /v1/config` body 加 `tenant_no`（缺省 'default'）；`GET /v1/config/current?tenant_no=`；asks 端点加 `X-Tenant-No` 头读取，`get_current_adapter(tenant_no)` 无记录回退 `_default`。
- 新端点 `POST /v1/insight`（AI 解读，见任务 B3）。

### 前端
- **views/admin/llm/index.vue**：页面顶部提示"配置按当前租户生效（{tenant}）"；列表/表单/部署请求已自动带 X-Tenant-No，仅文案微调。

### 测试
- Java：LlmConfigService 租户写入/过滤、AgentRuntimeFactory 租户缓存与 default 回退。
- Python test_config.py：两租户隔离、default 回退、current 查询。
- 前端 llm/index.spec.ts：租户提示文案。

---

## 任务 B：C 端报表（对标 QuickBI）

### B1. TABLE 数据表格（筛选/排序/分页）
- **新增 ReportTableService**（hrchat-report/service）：`TableView data(reportId, compId, ctx, page, size, sortField, sortOrder, dimValues)`，权限 `report:view`+requireViewAccess。
  - 复用 ChartService def 解析（含模板兼容）；每指标独立聚合 SQL（复用 parseAggregation/buildRatioSql|buildDirectSql），对 executeReadonly 结果按 dim_value hash 合并多指标列。
  - 分页：自建 `SELECT COUNT(*) FROM (aggSql) t`；数据包装 `SELECT * FROM (...) t WHERE dim_value IN (...) ORDER BY <白名单列> <asc|desc> LIMIT n OFFSET m`；单引号转义；比率列 NULLS LAST。
  - 排序白名单：dim_value/org_name/org_key/指标列；dimValues 取自 dim-values 接口。
- **ChartViews.java** 加 `TableView/TableColumn` record。
- **ChartController** 加 `GET /reports/{reportId}/components/{compId}/data` 与 `.../dim-values`。
- **前端 api/reports.ts** + **detail.vue**：TABLE 分支渲染 `a-table`（服务端排序/分页、顶部 a-select 维度筛选防抖重查，loading/降级沿用 chartStates）。

### B2. 图表类型一键切换
- **detail.vue**：组件卡片加 BAR/LINE/PIE 按钮组，切换仅改本地 chartTypeOf 状态，ChartRenderer 按 chartType 重算 option，不重拉数据。纯前端。

### B3. AI 洞察解读
- **Python** `POST /v1/insight`：入参 reportName/metricName/summary{categories,series}，mock adapter 返回模板化解读（均值/趋势/极值对比）。
- **Java**：ChartController 加 `GET /reports/{reportId}/components/{compId}/insight`（内部 chartData + 组装 payload）；`RemoteAgentRuntimeClient` 加 `generateInsight()`（复用 client/头），返回 `{text,generatedAt}`，失败降级错误码。
- **前端**：图表下方解读卡片（loading 骨架、失败重试按钮）。

### B4. 图表下钻
- **ChartService** chart-data 加 query 参数 `dimValue`（父 org_name）：dim=org 且 dimValue 非空时，dimSpec 过滤 `parent_org_key=(SELECT org_key FROM dim_org WHERE org_name=?)`。
- **前端**：`EChart.vue` 加 `onEvents` prop（柱状图 click），ChartRenderer emit `barClick(category)`；detail.vue 维护 drillPath 栈 + 面包屑返回；叶子无下级 → 前端提示"无下级组织"。行权语义：rewriteSql 过滤下钻出"有权限子集"，属正确行为。
- 演示边界：种子中研发一部/二部为叶子，下钻一层后提示无下级（机制可验证）；不改种子避免破坏现有 headcount 断言。

### 测试
- Java：ReportTableServiceTest（多指标列/排序方向/dimValue 过滤/COUNT 分页）、insight、下钻（mock 语义层+queryExec）。
- Python test_insight：模板解读返回。
- 前端 Vitest：表格分页排序 mock、图表切换、解读卡片 loading/重试、下钻面包屑。
- Playwright E2E 回归。

---

## 验证（端到端）
1. Java `mvn clean verify`（jacoco：report/model 核心 LINE≥80%）。
2. Python `pytest`（hrchat-ai）。
3. 前端 `vitest run` + `pnpm build`。
4. 冒烟（重启 8080/5199）：
   - 租户 LLM：t01 配模型 A、t02 配模型 B、默认回退；`GET /v1/config/current?tenant_no=` 分别验证；`/admin/llm` 带 X-Tenant-No 看列表隔离。
   - 报表：建含 TABLE 组件报表 → table-data 排序/筛选/分页；图表切换 BAR/LINE/PIE；insight 返回解读；柱状图点击下钻→面包屑返回→叶子提示。
   - 导出/推送回归。
5. Playwright E2E（ask/permission/report）。

## 涉及关键文件
- 新增：`ReportTableService.java`、`ReportTableServiceTest.java`、Python `insight` 端点
- 复用：`ReportChartService`（def/聚合/改写）、`ChartViews`、`QueryExecService`、`TenantContextHolder`
- 修改：Flyway H2/MySQL baseline 与 demo seed、`LlmModelConfig.java`、`LlmConfigService.java`、`LlmDeployService.java`、`AgentRuntimeFactory.java`、`RemoteAgentRuntimeClient.java`、`ChartController.java`、`app.py`、`mock_llm.py`、`openai_client.py`、`detail.vue`、`api/reports.ts`、`views/admin/llm/index.vue`、`llm.ts`、`EChart.vue`、`ChartRenderer.vue` 及对应测试
