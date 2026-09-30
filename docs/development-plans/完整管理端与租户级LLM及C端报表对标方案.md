# 完整管理端 + 租户级 LLM + C 端报表对标 QuickBI 方案

## Context（背景）

用户反馈链条：目前只有 C 端 → 要**增加完整管理端**；LLM 配置**按租户**（租户配 key/模型，无配置回退默认）；**对标 QuickBI 完善**。已确认决策：
- 管理端形态：**完整管理端**（独立管理侧边栏导航 + 管理首页仪表盘 + 管理员专用入口，与 C 端分离）。
- 范围：租户管理、用户管理、角色与权限配置、LLM 配置（租户级）、审计日志、管理仪表盘、系统设置，并对标 QuickBI 完善。
- 管理员层级：**超管 + 租户管理员**（租户管理员只管本租户用户与 LLM 配置）。
- C 端报表本轮实现：TABLE 数据表格（筛选/排序/分页）、图表类型一键切换、AI 洞察解读、图表下钻。

已核实现状（可复用）：
- 后端已具备：RoleService 角色 CRUD（admin:authz:manage）、UserAdminController（admin:user:manage）、TenantAdminController（admin:tenant:manage，含 usage）、LlmAdminController、审计 Tab（admin/index.vue）。功能授权已统一为 `sec_role_permission`，默认角色授权由 Flyway V3 初始化。
- 前端：路由 /admin、/admin/llm|users|tenants 已存在；App.vue 顶部导航聚合管理入口（非独立侧边栏）；auth store 无超管/租户管理员区分。
- 租户机制：TenantContextHolder（显式 X-Tenant-No 才注入）+ 业务查询 tenant_id 谓词；前端 http.ts 自动带 X-Tenant-No。
- LLM 配置当前全局单例（llm_model_config 无 tenant_id；Python _runtime 全局）；Python 仅 config/chat 端点。
- C 端：ReportChartService def/聚合/改写链可复用；executeReadonly 纯文本无参数化、硬上限 200 行（分页须自建 COUNT）；dim_org 3 层种子（集团→研发中心→研发一部/二部，叶子无下级）。

---

## P1 管理端基础（独立布局 + 仪表盘 + 角色页 + 审计 + 系统设置）

### 1. 完整管理端布局（前端）
- 新增管理端外壳：`layouts/AdminLayout.vue`（左侧管理导航 + 顶栏租户/管理员标识），路由 `/admin` 下所有页面挂到 AdminLayout。
- 管理导航菜单：概览、租户管理、用户管理、角色权限、LLM 配置、审计日志、系统设置；入口与 C 端顶部导航分离（App.vue 只留"C 端"入口 + 管理端跳转）。
- `/admin` 首页改为**管理仪表盘**（原 admin/index.vue 的 Tab 式审计/评测/权限迁移到对应菜单页）。

### 2. 管理仪表盘（后端 + 前端）
- 新增 `DashboardController`（hrchat-admin）：`GET /admin/dashboard` → 租户数/用户总数/配额使用率（user/report/subscription/api）/LLM 活跃与健康/近 7 日审计条数。复用 TenantService usage、LlmMonitor、Audit 查询。
- 前端 `views/admin/dashboard/index.vue`：指标卡 + 配额使用率进度 + 模型健康列表 + 审计趋势。

### 3. 角色与权限配置页（前端，后端已备）
- 新增 `views/admin/roles/index.vue`：角色列表/新建/编辑（roleCode/roleName/dataLevel/功能权限码多选）、数据范围与字段策略入口（复用 AuthzAdminController：roles CRUD、set-data-scope、field-policy）。
- 前端 `api/admin.ts` 补角色 CRUD 方法（若缺）。

### 4. 审计日志页
- 从 admin/index.vue Tab 迁移为独立菜单页 `views/admin/audit/index.vue`（保留筛选/分页，补导出 CSV 可选）。

### 5. 系统设置
- 新增 `t_sys_setting`（key/value/描述）表（schema-h2 + deploy 同步）；`SysSettingController` CRUD；`SysSettingService`。
- 默认项：`llm.default.profile`（系统回退模型档位，供 P2 默认回退读取）、`deploy.mock`（模拟部署开关，读现有 hrchat.ai.deploy-mock 语义）。
- 前端 `views/admin/settings/index.vue`：键值列表编辑 + "系统默认 LLM 配置"区块（指向 tenant_id=NULL 的模型记录，见 P2）。

---

## P2 租户级 LLM + 超管/租户管理员两级

### 1. 租户级 LLM 配置
- **DDL**：`llm_model_config` 加 `tenant_id VARCHAR(16) NULL`（NULL=系统默认）；`llm_deploy_state` 经 config 归属。相关结构和种子现已固化在 Flyway H2/MySQL baseline 与 V2 demo seed 中。
- **Java**：LlmModelConfig 加 tenantId；LlmConfigService create/patch 写租户（TenantContextHolder.get()，null→'t01'）、list 过滤 `tenant_id=当前 OR IS NULL`；LlmDeployService ACTIVE 按 config 归属过滤；AgentRuntimeFactory 单例→`Map<String,AgentRuntimeClient>` 按租户懒加载 + default（保留 build）；RemoteAgentRuntimeClient 构造加 tenantNo、请求头带 X-Tenant-No；调用方用 TenantContextHolder.get()（null→default）。
- **Python**：`_runtime`→`_runtimes: dict[str,runtime]` + `_default`；`POST /v1/config` body 加 tenant_no；`GET /v1/config/current?tenant_no=`；asks 端点读 X-Tenant-No，无记录回退 `_default`；新增 `POST /v1/insight`（见 P3-C）。
- **前端** llm 页：顶部提示"配置按当前租户生效（{tenant}）"；列表/部署已自动带 X-Tenant-No。

### 2. 超管/租户管理员两级
- `TENANT_ADMIN` 默认授权已在 Flyway V3 落表：`chat:ask、report:view、admin:view、admin:user:manage、admin:llm:view、admin:llm:manage、admin:audit:read`；不含平台租户管理、角色授权管理和系统管理权限。
- 超管 `ADMIN` 保持 `admin:*`。租户管理员新建用户/LLM 配置自动归属本租户（已有 tenant_id 谓词 + 新写入逻辑）。
- Flyway V2 demo seed：t02 新增租户管理员用户（如 `t02adm01`/TENANT_ADMIN/org 归属 t02）。
- 管理端前端：顶栏展示当前管理员角色；租户管理员不显示租户管理/角色权限/系统设置菜单（按权限码过滤菜单）。

---

## P3 C 端报表对标 QuickBI

### A. TABLE 数据表格（筛选/排序/分页）
- 新增 `ReportTableService`（hrchat-report）：`TableView data(reportId,compId,ctx,page,size,sortField,sortOrder,dimValues)`，复用 ChartService def 解析/聚合（parseAggregation/buildDirectSql|buildRatioSql）+ 多指标按 dim_value 合并；自建 COUNT 分页；排序白名单；单引号转义；比率 NULLS LAST。
- ChartViews 加 TableView/TableColumn；ChartController 加 `GET /reports/{id}/components/{cid}/data` 与 `.../dim-values`。
- 前端：detail.vue TABLE 分支渲染 a-table（服务端排序/分页、a-select 维度筛选防抖）；api/reports.ts 补方法。

### B. 图表类型一键切换
- detail.vue 图表卡片加 BAR/LINE/PIE 按钮组，仅改本地 chartTypeOf 状态，ChartRenderer 重算 option，不重拉数据。纯前端。

### C. AI 洞察解读
- Python `POST /v1/insight`：入参 reportName/metricName/summary{categories,series}，mock adapter 返回模板化解读（均值/趋势/极值）。
- Java：ChartController 加 `GET /reports/{id}/components/{cid}/insight`（内部 chartData+组装 payload）；RemoteAgentRuntimeClient 加 `generateInsight()`；失败降级错误码。
- 前端：图表下方解读卡片（loading 骨架/失败重试）。

### D. 图表下钻
- ChartService chart-data 加参数 `dimValue`（父 org_name）：dim=org 且非空时过滤 `parent_org_key=(SELECT org_key FROM dim_org WHERE org_name=?)`。
- 前端：EChart.vue 加 onEvents prop（柱 click），ChartRenderer emit barClick；detail.vue drillPath 栈 + 面包屑返回；叶子无下级提示。
- 演示边界：种子中研发一部/二部为叶子，下钻一层后提示无下级（机制可验证，不改种子避免破坏现有断言）。

---

## 验证（每阶段独立 + 全量回归）

1. Java `mvn clean verify`（jacoco：report/model/authz/admin 核心 LINE≥80%）；Python `pytest`；前端 `vitest run` + `pnpm build`。
2. 新增单测要点：
   - P1：DashboardController/统计、SysSetting CRUD；前端 admin layout 菜单按权限过滤、roles 页、settings 页。
   - P2：LlmConfigService 租户写入/过滤、AgentRuntimeFactory 租户缓存与 default 回退、TENANT_ADMIN 权限码、租户管理员建用户归属；Python config 多租户隔离/回退/current；insight mock。
   - P3：ReportTableService（多指标/排序/过滤/COUNT）、下钻 SQL、insight 聚合；前端表格 mock、切换、解读卡片、下钻面包屑。
3. 冒烟（重启 8080/5199）：管理端登录 adm01 看全菜单/仪表盘/角色页/设置；t02adm01 只见本租户菜单与数据；t01/t02 各自 LLM 配置生效、默认回退；报表表格排序筛选分页、图表切换、解读、下钻 1 层→叶子提示。
4. Playwright E2E 回归（ask/permission/report）+ 新增管理端 smoke spec（可选）。

## 涉及关键文件

- 前端新增：`layouts/AdminLayout.vue`、`views/admin/dashboard/index.vue`、`views/admin/roles/index.vue`、`views/admin/audit/index.vue`（迁移）、`views/admin/settings/index.vue`
- 前端修改：`router/index.ts`（/admin 挂 AdminLayout）、`App.vue`、`stores/auth.ts`（管理员角色标识）、`views/reports/detail.vue`、`views/admin/llm/index.vue`、`api/reports.ts`、`api/admin.ts`、`api/llm.ts`、`EChart.vue`、`ChartRenderer.vue`
- 后端新增：`DashboardController`、`SysSettingController/SysSettingService`、`ReportTableService`
- 后端修改：Flyway H2/MySQL baseline 与 demo seed、`LlmModelConfig.java`、`LlmConfigService.java`、`LlmDeployService.java`、`AgentRuntimeFactory.java`、`RemoteAgentRuntimeClient.java`、`AuthzService.java`（TENANT_ADMIN）、`ChartController.java`、`ChartViews.java`、`ReportChartService.java`
- Python：`app.py`（_runtimes + insight）、`mock_llm.py`、`openai_client.py`
- 测试：对应各模块新增/补充（DashboardTest、SysSettingTest、RoleService TENANT_ADMIN、LlmConfig 租户、ReportTableServiceTest、test_config 多租户、test_insight、各前端 spec）
