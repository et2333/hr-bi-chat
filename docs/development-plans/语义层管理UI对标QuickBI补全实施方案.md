# 语义层管理 UI 对标 Quick BI 补全实施方案

> 版本：v1.0　日期：2026-09-28　范围：hrchat-server + hrchat-web
> 上游依据：《Quick BI 语义层对标缺口分析》（指标/维度/同义词三条线）
> 本文仅为实施方案，评审通过后再进入编码。

---

## 1. 背景与目标

语义层管理 UI 已交付指标/维度/同义词三个面板与版本审批流，但对标 Quick BI（智能小Q数据准备 + 知识库业务逻辑）后，管理闭环仍有缺口：**不能删、不能停用、审批人无待办入口、版本不能对比、删除无引用评估**。

本方案目标：用最小、直白、可落地的改动补齐 **P0 治理闭环**，不引入新中间件、不改变现有审批协议、不做超前抽象。

设计原则：

1. 复用现有基础设施（`biz_metric.is_deleted/status`、版本表、权限点），能加字段不加表。
2. 软删优先，删除前强制引用检查。
3. 主干功能在本文详设；P1/P2 仅列规划，不混入本期。

---

## 2. 范围划分

| 批次 | 内容 | 说明 |
|---|---|---|
| **P0（本期）** | ① 指标删除/启停　② 维度删除　③ 审批待办中心　④ 版本对比（纯前端）　⑤ 引用血缘 | 治理闭环 |
| P1（后续） | 变更说明 changeNote、默认聚合方式、指标负责人/单位/格式、维度层级编辑、日期维度、同义词批量导入与启停 | 建模效率 |
| P2（远期） | 业务知识库、维值学习加速、字段质量评估、AI 推荐同义词、生效范围/强制改写、分类树与收藏 | AI 增强/独立模块 |

---

## 3. P0 主干（一页式）

```
指标行： 详情 | 编辑 | 停用/启用 |删除
维度行： 编辑 | 删除
删除前： 查引用 → 有引用则拒绝并返回引用清单（前端 Popconfirm 二次确认）
删除：   统一软删（is_deleted=1），列表/详情查询自动过滤
审批人： 语义层页出现第 4 个 Tab「审批待办」（仅 admin:semantic:approve 可见）
         待办 = 版本状态 0 且已提交（submitted_at 非空）
         Tab 内直接 通过(Popconfirm) / 驳回(填原因)，无需进入指标详情
版本对比：详情 Drawer 内「版本对比」→ 任选两版本，口径/公式行级 diff
血缘：   详情 Drawer 展示「引用关系」；删除拦截信息同源
```

**两条数据库字段变更（P0 全部 DDL）：**

- `biz_dimension` 增加 `is_deleted TINYINT(1) NOT NULL DEFAULT 0`
- `biz_metric_version` 增加 `submitted_at DATETIME(3) DEFAULT NULL`（同时区分"草稿/已提交"与展示提交时间）

---

## 4. P0 后端详细设计

### 4.1 指标删除与启停

**删除：软删，强制引用检查**

- 新接口：`DELETE /api/v1/admin/semantic/metrics/{metricId}`
- 流程：
  1. 取指标（未删除）→ 血缘检查（见 4.4），存在引用则抛 `BizException(PARAM_INVALID)`，message 附前若干个引用报表名，如「该指标被 3 个报表组件引用，无法删除：研发在职月报、…」。
  2. 无引用 → `is_deleted=1`（`updateById`），版本与维度关联数据保留（可审计）。
  3. 同步清理：同义词 `biz_synonym` 中指向该指标的条目（删除指标的同义词已无意义）——同一事务内按 `target_type=1,target_id` 删除。
- 所有指标查询入口补 `is_deleted=0`：列表、详情、`getMetricByCode`（问数链路）、版本提交前校验。

**启停：独立端点，语义不与口径 PATCH 混淆**

- 新接口：`PATCH /api/v1/admin/semantic/metrics/{metricId}/status`
- Body：`{"status": 1}`（1 启用 / 0 停用）；新增 DTO `MetricStatusRequest(Integer status)`，仅接受 0/1。
- 停用不影响已发布版本；停用指标在问数链路不可被选中（`getMetricByCode` 检查 status，停用报业务异常）。
- 不停用时不做引用拦截（停用可恢复）。

### 4.2 维度删除

`biz_dimension` 现无软删字段，**补字段后软删**（与指标一致的处理范式）：

- DDL：`ALTER TABLE biz_dimension ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0`（新增 H2/MySQL Flyway 版本迁移；实体 `BizDimension` 加 `isDeleted`）。
- 新接口：`DELETE /api/v1/admin/semantic/dimensions/{dimensionId}`
- 引用检查（任一命中即拒绝，返回命中类型与数量）：
  1. `biz_metric_dim`：被指标配置为可用维度；
  2. `biz_synonym`：存在 `target_type=2,target_id=该维度` 的同义词。
- 检查通过 → `is_deleted=1`；其 `biz_dim_value` 枚举值保留（随维度复活可复用）。
- 补过滤条件：维度列表、指标详情中维度码解析、`bindDimensions` 中按 code 解析维度的查询，一律加 `is_deleted=0`。

### 4.3 审批待办中心

**问题**：当前版本表 `status=0` 同时包含"改完口径未提交"和"已提交待审批"，且无提交时间。审批人无法获得干净待办。

**方案**：版本表加 `submitted_at`：

- DDL：`ALTER TABLE biz_metric_version ADD COLUMN submitted_at DATETIME(3) DEFAULT NULL`（实体加字段）。
- `submitApproval`（现有接口，保持幂等）：除返回 approvalId 外，当 `submitted_at IS NULL` 时写入当前时间；重复提交不改变原值。
- 修改口径生成新版本时 `submitted_at` 保持 NULL（草稿态）。
- 新接口：`GET /api/v1/admin/semantic/approvals/todo`
  - 权限点：`admin:semantic:approve`（待办仅审批人需要）。
  - 查询：`status=0 AND submitted_at IS NOT NULL`，按 `submitted_at` 倒序。
  - 返回项（新增 DTO `ApprovalTodoItem`）：

    | 字段 | 来源 |
    |---|---|
    | approvalId | 版本 id（approve/reject 沿用，协议不变） |
    | metricId / metricCode / metricName | join `biz_metric`（过滤 is_deleted） |
    | versionNo | 版本号 |
    | submittedBy / submittedAt | 版本表 |
    | calcScope / formulaExpr | 待审批内容摘要（列表可预览） |

- 审批通过/驳回仍走现有 `/approvals/{id}:approve | :reject`，**协议零变更**；操作后该条目自然移出待办。

### 4.4 引用血缘

**模块约束（已核实）**：`hrchat-report` 已依赖 `hrchat-semantic`，在 semantic 侧反向引用报表 Mapper 会形成循环依赖。

**方案（直白、不引入 SPI 间接层）**：血缘服务与 Controller 放在 **hrchat-report 模块**，URL 沿用 semantic 前缀，Spring MVC 不要求同一 Controller 类：

- 新增 `SemanticLineageController`（hrchat-report）
  - `GET /api/v1/admin/semantic/metrics/{metricId}/lineage`
  - `GET /api/v1/admin/semantic/dimensions/{dimensionId}/lineage`
  - 权限点复用 `admin:semantic`（`authzService.checkFunc`）。
- 新增 `SemanticLineageService`（hrchat-report）：
  - 数据源：`rpt_component.def_json`（结构 `{metric, dim, filter}`）+ `rpt_report`（名称/删除状态）。
  - 指标提取规则（复用 `ReportChartService` 已有的 def 解析约定）：`metric` 为字符串→单 code；为对象→取 `code`；表格组件可能为数组→逐个取；`dim` 取维度 code。
  - 过滤 `rpt_report.is_deleted=0`，返回 `[{reportId, reportName, componentId, compType, chartType}]`。
  - 实现方式：Java 侧 JSON 解析精确匹配 code；**不使用 SQL LIKE**（避免 `headcount` 误匹配 `headcount_yoy`）。
- 删除拦截（4.1/4.2）直接调用该 Service；语义层详情 Drawer 也调用同一接口展示，保证两处信息同源。

### 4.5 版本对比（无后端改动）

版本内容在现有 `GET /metrics/{id}/versions` 中已完整返回，diff 计算放前端（见 5.3）。

---

## 5. P0 前端详细设计

技术栈不变：Vue 3 + Ant Design Vue + 现有 antd 组件；视觉沿用当前语义层页面风格。

### 5.1 API 层（`src/api/semantic.ts` 增补）

| 方法 | 接口 |
|---|---|
| `deleteMetric(id)` | `DELETE /admin/semantic/metrics/{id}` |
| `setMetricStatus(id, status)` | `PATCH /admin/semantic/metrics/{id}/status` |
| `deleteDimension(id)` | `DELETE /admin/semantic/dimensions/{id}` |
| `listApprovalTodos()` | `GET /admin/semantic/approvals/todo` |
| `getMetricLineage(id)` / `getDimensionLineage(id)` | `GET .../{type}/{id}/lineage` |

同步补充 TS 类型：`ApprovalTodoItem`、`LineageItem`、`MetricStatusRequest`。

### 5.2 MetricPanel 改动

- **操作列**：在「编辑」后增加「停用/启用」（a-switch 或文字按钮，调 setMetricStatus，成功后刷新本行）与「删除」（a-popconfirm 标题"删除后不可直接恢复，确认删除？"，确认后调 deleteMetric；后端返回引用拦截时，用 `modal.error` 展示引用清单）。
- **详情 Drawer**：
  - 版本历史区块上方加「版本对比」按钮（版本数 ≥ 2 时可用）。
  - Drawer 末尾新增「引用关系」区块：调 getMetricLineage，以 a-list 展示报表名 + 组件类型 tag；无引用显示"暂无报表引用"。
- 列表状态列展示"停用"标签（已具备列结构）。

### 5.3 版本对比 Modal（新增组件 `VersionDiffModal.vue`）

- 两个版本选择器（a-select，默认选中最近两版），下方左右两栏。
- 口径说明、计算公式分块，按行 diff：行级 LCS 比对，新增行绿色底、删除行红色底、未变行默认色。
- 纯前端计算，不新增接口；diff 算法用通用 LCS（约 40 行），不引第三方依赖。

### 5.4 审批待办 Tab

- `index.vue` 在现有三个 Tab 后增加「审批待办」，**仅 `canApprove`（hasPerm 'admin:semantic:approve'）时渲染**；Tab 标题带待办数 Badge（进入页面时若有审批权则拉取 todo 计数）。
- 新增 `ApprovalTodoPanel.vue`：
  - a-table 列：指标（编码/名称）、版本、提交人、提交时间、待审批口径（ellipsis）、操作。
  - 操作列「通过」（a-popconfirm 确认 → 现有 approve 接口）、「驳回」（复用现有驳回原因 Modal → reject 接口）。
  - 操作成功后刷新待办列表；行点击可侧开该指标详情（复用现有 Drawer 入口，可选）。
- DATA_ADMIN（无 approve 权限）看不到该 Tab，行为与现状一致。

### 5.5 DimensionPanel 改动

- 操作列加「删除」（a-popconfirm → deleteDimension）；引用被拦截时 `modal.error` 展示后端返回的引用类型与数量。
- 其余不变。

---

## 6. P0 新增/变更接口清单

| # | 方法 | 路径 | 权限点 | 备注 |
|---|---|---|---|---|
| 1 | DELETE | `/admin/semantic/metrics/{id}` | admin:semantic | 软删，引用拦截 |
| 2 | PATCH | `/admin/semantic/metrics/{id}/status` | admin:semantic | body `{status:0/1}` |
| 3 | DELETE | `/admin/semantic/dimensions/{id}` | admin:semantic | 软删（先加字段），引用拦截 |
| 4 | GET | `/admin/semantic/approvals/todo` | admin:semantic:approve | 待办列表 |
| 5 | GET | `/admin/semantic/metrics/{id}/lineage` | admin:semantic | Controller 位于 report 模块 |
| 6 | GET | `/admin/semantic/dimensions/{id}/lineage` | admin:semantic | 同上 |
| — | POST | `/metrics/{id}:submit-approval` | admin:semantic | **现有接口**：补写 submitted_at |

所有响应继续包裹 `ApiResponse`，错误码体系不变（参数/业务冲突走 HRX-1003 等，未预期异常 HRS-3001）。

---

## 7. 数据库变更

| 表 | 变更 | 原因 |
|---|---|---|
| biz_dimension | 加 `is_deleted TINYINT(1) NOT NULL DEFAULT 0` | 维度软删 |
| biz_metric_version | 加 `submitted_at DATETIME(3) DEFAULT NULL` | 草稿/待审批区分 + 提交时间 |

落点：H2/MySQL 新增 Flyway 版本迁移、对应实体（`BizDimension`、`BizMetricVersion`）、受影响查询补条件。现有种子数据无需修改（新字段走默认值）。

---

## 8. 测试计划

**后端**

- `SemanticControllerTest`：指标删除成功/引用拦截、status 切换、维度删除成功/两类引用拦截、todo 列表（仅 submitted_at 非空）、submit-approval 写入 submitted_at。
- hrchat-report：新增 `SemanticLineageServiceTest`（字符串/对象/数组 metric 提取、子串 code 精确匹配、已删除报表过滤）。
- `SemanticMetaServiceFlowTest`：补软删后查询不可见、问数链路停用指标报错。
- 覆盖率门禁维持 semantic ≥80%，新增代码同步补测。

**前端（vitest）**

- `index.spec.ts` 增补：操作列删除/停用按钮存在性、引用拦截文案渲染、审批待办 Tab（ADMIN 可见/DATA_ADMIN 不可见）、版本对比 Modal 打开并渲染差异行。

**E2E（Playwright，临时脚本验收后清理）**

- adm01：停用指标 → 删除（无引用成功/造引用被拦截）→ 待办 Tab 通过一个审批 → 版本对比打开。
- dat01：无待办 Tab；删除被引用维度被拦截。

---

## 9. 任务拆分与顺序

| 序号 | 任务 | 模块 | 依赖 |
|---|---|---|---|
| 1 | DDL + 实体字段（is_deleted / submitted_at） | server | — |
| 2 | 血缘 Service + Controller | report | — |
| 3 | 指标删除/启停、维度删除（含查询过滤） | semantic | 1、2 |
| 4 | submit-approval 写 submitted_at + 待办接口 | semantic | 1 |
| 5 | 后端测试补齐并全量回归 | server | 2–4 |
| 6 | 前端 API 类型与封装 | web | 3、4 |
| 7 | MetricPanel 删除/启停、Drawer 血缘区块 | web | 6 |
| 8 | VersionDiffModal 版本对比 | web | 6 |
| 9 | ApprovalTodoPanel + Tab 接入 | web | 6 |
| 10 | DimensionPanel 删除 | web | 6 |
| 11 | 前端单测/类型检查/构建 + Playwright 全流程 | web | 7–10 |

建议验收：第 5 步后端可独立验收；第 11 步逐页对标 Quick BI 视觉与交互复核。

---

## 10. P1/P2 后续规划（本期不实施）

**P1**

- 提交审批时可填**变更说明** changeNote（字段已存在，MetricPatchRequest 透传 + 编辑 Modal 输入框）。
- 指标**默认聚合方式**结构化（SUM/AVG/COUNT/COUNTD/MAX/MIN 等，枚举字段 + SQL 生成）。
- 指标**负责人、单位、数值格式**（百分比/小数位）。
- 维度**层级编辑**（parentCode 树形 UI，支撑下钻）、**日期维度**类型。
- 同义词**批量导入**（前端解析 xlsx/csv + 模板下载，复用现有新增接口）、条目**启停**（status 已存在）。

**P2**

- 独立**业务知识库**（业务定义 + 数据解释，可挂非指标对象；文档 AI 提炼）。
- **维值学习加速、字段质量评估、维值匹配模式**（对标小Q数据准备）。
- AI 推荐同义词、生效范围（Agent/资产级）与强制改写。
- 自定义主题域分类树、指标收藏/置顶、批量操作与列表导出。

---

## 11. 附录：关键设计取舍

1. **血缘为何放 report 模块而非 semantic？**
   report 已依赖 semantic，反向依赖成环。放 report 是单向依赖下最直接的位置；URL/权限点保持 semantic 语义。不采用"semantic 定义 SPI 端口 + report 实现"的反转方案——对当前规模是过度设计。

2. **为什么一律软删？**
   版本历史与审计要求数据可追溯；`biz_metric` 已有 `is_deleted`，维度补同名字段形成统一范式。枚举值/版本随主对象保留，撤销删除仅需回置标记。

3. **删除为什么被引用即拒绝，不提供 force？**
   force 删除会留下指向空对象的报表组件，故障延后到报表打开时才暴露。强制先处理引用，把问题挡在删除动作之前。

4. **待办为什么用 submitted_at 而不是新增"提交状态"字段？**
   一个字段同时满足"草稿/待审批区分"和"提交时间展示"两个需求，避免冗余状态列。

5. **本期明确不做的事**：不改审批协议、不引入新表/中间件、不做血缘的 SQL LIKE 方案、不实现 P1/P2 任何条目。
