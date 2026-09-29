# HR智能问数 全栈实施计划（Vue 前端 + Java 后端 + Python AI 运行时）

## 1. 背景（Context）

依据《产品需求文档 PRD》《接口设计文档》《数据库设计文档》《应用架构设计文档 ARCH-HRCHATBI-001/002》《测试用例文档》，从零开发 HR智能问数（HR ChatBI）完整全栈系统。当前仓库仅有设计文档，无任何代码。

**用户已确认的决策：**
| 项 | 决策 |
|---|---|
| 交付范围 | **全功能全栈**：前端 + Java 后端全部模块 + Python AI 运行时 + 知识库层 |
| 前端框架 | **Vue 3**（用户指定，偏离文档 React 选型，需记录 ADR） |
| 本地环境 | **轻量内嵌替代**：H2 替代 MySQL、内存向量检索替代 Milvus、Stub/Mock LLM 替代 vLLM，零外部依赖、本地可跑可测 |
| 编码规范 | 后端《阿里巴巴Java开发手册》+Checkstyle；前端 Vue 官方风格指南 + ESLint Vue 插件 |
| 性能指标 | 按 PRD 分层：问数链路 P95<3s（SSE 流式）；非 LLM REST API <200ms；并发 100 QPS |

**架构红线（不可违反）：** 权限不旁路（取数唯一入口=MCP→authz 改写）、语义层单一事实源、降级可达（FR-24）、数据不出域、Trace 全链路贯通。

## 2. 关键架构决策

| 编号 | 决策 | 理由 |
|---|---|---|
| ADR-108 | 前端栈 React 18→**Vue 3 + TS + Vite + Ant Design Vue 4 + Pinia + ECharts 5** | 用户指定 Vue；Ant Design Vue 与原型 AntD 设计语言 1:1 对应，满足「视觉与原型严格一致」；Zustand→Pinia，React Query→axios 组合式封装；图表 ECharts 与框架无关直接复用 |
| — | 后端采用**模块化单体**（非微服务） | 架构文档 ADR-002 明确 V1.0 模块化单体+DDD 分包+接口抽象预留拆分；用户要求"不得偏离设计要求"，可扩展性以模块边界+独立 profile 满足（独立部署能力以 Maven 子模块各自可启动保证） |
| — | 中间件全部抽象为**可插拔接口**（双 profile） | `local` profile 用 H2/内存实现（零依赖可测）；`prod` profile 用 MySQL/Redis/Kafka/Milvus 实现。对应文档"统一适配层"要求，保证可扩展性 |
| — | AI 运行时双形态 | Python（LangGraph+AgentScope+agent-gateway，MockLLM 适配器）为生产形态；Java 侧 AgentRuntimeClient 接口的 **LocalAgentRuntimeImpl** 为本地集成测试形态（确定性规则引擎，不依赖 Python 进程） |

## 3. 目录结构与技术栈

```
BIchat/
├── hrchat-server/                  # Java 17 + Spring Boot 3.2 模块化单体（Maven 多模块）
│   ├── hrchat-common/              # 错误码(HRX/HRC/HRS/HRA/HRD/HRK)、统一响应、工具
│   ├── hrchat-api/                 # DTO/OpenAPI 契约、SSE 事件 Schema、MCP 契约
│   ├── hrchat-authz/               # RBAC×行级×字段级、SQL 权限改写、MCP Server
│   ├── hrchat-semantic/            # 语义层元数据（指标/维度/同义词/版本审批）
│   ├── hrchat-knowledge/           # knowledge-svc：VectorStore 接口+内存实现、混合检索+重排(stub)
│   ├── hrchat-chat/                # 会话/问答编排、SSE、澄清、降级
│   ├── hrchat-ai-client/           # AgentRuntimeClient 接口 + LocalAgentRuntimeImpl
│   ├── hrchat-query-exec/          # 只读 SQL 白名单、Doris 执行（local 用 H2 执行）
│   ├── hrchat-report/              # 报表 CRUD/组件
│   ├── hrchat-subscription/        # 订阅、调度(@Scheduled)、推送(IM/邮件接口)
│   ├── hrchat-audit/               # 审计事件（≥3年）
│   ├── hrchat-admin/               # 管理后台 API
│   └── hrchat-bootstrap/           # 启动器、local/prod profile、schema 初始化
├── hrchat-web/                     # Vue 3 + Vite + TS + Ant Design Vue 4 + Pinia + ECharts 5
│   ├── src/router/                 # 站点地图路由(/chat /reports /admin /profile)
│   ├── src/views/chat|reports|admin|profile/
│   ├── src/components/             # AnswerCard/ClarifyCard/MetricCard/降级提示条/异常态
│   ├── src/stores/                 # Pinia：session/chat/report/auth
│   ├── src/api/                    # axios 客户端（按接口文档契约）
│   └── src/composables/            # useSSE 流式消费
├── hrchat-ai/                      # Python 3.11：LangGraph + AgentScope 2.0 + agent-gateway
│   ├── agent_gateway/              # FastAPI：会话路由/事件标准化/SSE
│   ├── langgraph_flows/            # 问数状态机（Router/澄清/检索/NL2SQL/执行/呈现）
│   ├── agentscope_teams/           # 归因 Team（Leader/DataWorker/AnalystWorker/Writer）
│   ├── adapters/                   # ModelAdapter(ToolRegistry/MemoryAdapter/TraceAdapter)
│   └── tests/                      # pytest + MockLLM
├── hrchat-deploy/                  # docker-compose(local 全中间件)、Helm 骨架、schema.sql
├── README.md                       # 快速启动/规范/架构说明
└── development-plans/HR智能问数全栈实施计划.md
```

## 4. 实施步骤

### S1 工程骨架与文档一致性
- 记录 ADR-108 至 `HR智能问数技术架构优化设计方案.md`（前端栈变更），更新前端技术栈表
- 初始化 `hrchat-server` Maven 多模块（pom 继承、依赖管理）、`hrchat-web` Vite 工程、`hrchat-ai` uv 工程
- 编写 `schema.sql`（H2/MySQL 双兼容）与实体表（依据《数据库设计文档》）

### S2 Java 数据层与权限模块（先地基）
- 建表：`sec_user/sec_org_node/sec_org_grant/sec_field_policy`、`biz_metric/biz_metric_version/biz_dimension/biz_dim_value/biz_metric_dim/biz_synonym`、`cht_session/cht_turn/cht_answer`、`rpt_report/rpt_component/rpt_subscription/rpt_sub_receiver/rpt_snapshot`、审计表（字段/索引对齐数据库文档）
- `authz-svc`：三层权限裁决、SQL 改写（注入 `org_path IN (...)`）、字段脱敏策略、LiteFlow 规则（可简化为策略表+规则引擎）、权限变更≤5min 生效（本地用缓存失效广播）
- 单测：权限改写 100 条越权用例（测试用例文档 L424-L488 映射）

### S3 语义层与知识库
- `semantic-svc`：指标/维度/同义词 CRUD、版本与审批流、热更新发布
- `knowledge-svc`：`VectorStore` 接口（`InMemoryVectorStore` local 实现；`MilvusVectorStore` prod 实现——依赖 pymilvus 风格客户端，本地不运行）、混合检索（稠密余弦 TopK + 稀疏关键词 TopK + RRF 融合 + 重排 stub）
- 语义层双写：MySQL(version/status) + VectorStore(向量)，查询按 version 隔离

### S4 问数主链路（chat + query-exec + ai-client）
- `query-exec`：只读 SQL 白名单校验（关键词/`SELECT` 前缀）、H2 执行、超时控制
- `chat-svc`：会话管理、`POST /api/v1/chat/sessions/{id}/asks` SSE 流式（按接口文档 L277-L317）、`mode=SYNC` 降级、`context_override`
- `LocalAgentRuntimeImpl`：确定性问数编排（意图规则→语义检索→SQL 模板拼装→权限改写→执行→呈现），无 LLM 依赖
- SSE 事件流（接口文档 L390-L434）：`MESSAGE_DELTA/INTERRUPT/ANSWER_DONE/ERROR/HEARTBEAT`，`seq/ts` 规则、`Last-Event-ID` 断线恢复
- 核心用例（测试用例文档 L65-L133）：简单问数/复杂条件/闲聊兜底/操作类问句/反馈/SQL 查看/超时异步

### S5 报表/订阅/审计/管理
- `report-svc`：报表 CRUD、组件定义、`POST /api/v1/reports/{id}:subscribe`、快照
- `subscription-svc`：`@Scheduled` 定时触发、推送失败重试3次、收件人逐个权限校验（BR-13）
- `audit-svc`：全链路审计事件收集（问句/SQL摘要/行数）、敏感操作标记
- `admin-svc`：语义层配置、同步状态、评测查询

### S6 前端 Vue（对话工作台优先）
- 按原型文档站点地图（L120-L149）建路由：`/chat /reports /reports/:id /reports/editor /admin /profile`
- 对话工作台（原型 L244-L303）：会话侧栏/空状态欢迎区/输入框/问答卡片流/答案详情侧栏
- 核心组件（原型 L423-L493）：`AnswerCard`（Skeleton→Clarifying/Completed/NoPermission/Failed/AsyncTimeout）、`ClarifyCard`、`MetricCard`、降级提示条、统一空/异常态
- `useSSE` 组合式函数：事件流消费、乱序缓冲重排、断线重连
- ECharts 图表渲染（原型图表区要求）

### S7 Python AI 运行时
- `agent-gateway`（FastAPI）+ LangGraph 问数状态机 + AgentScope 归因 Team（Leader-Worker、预算熔断）
- `ModelAdapter`：`OpenAIClientImpl`（真实）+ `MockLLMImpl`（规则/确定性，供 pytest 与本地演示）
- pytest：问数链路用例 + 归因 Team 收敛性用例

### S8 集成测试与质量门禁
- Java：JUnit5 + Mockito，JaCoCo 覆盖率门禁 **>80%**；集成测试以 `local` profile 启动全应用（H2+内存中间件），覆盖核心业务流程（问数/权限/报表/订阅/审计）
- 前端：Vitest + Vue Test Utils（组合式函数/组件）、Playwright E2E（问数、无权限、报表三主流程）
- Python：pytest 全绿
- 全量回归：测试用例文档核心用例逐条映射执行

### S9 收尾
- README（快速启动/规范/架构）、本地端到端手动验证（dev server + backend 联调）、最终质量报告

## 5. 关键实现契约（必须严格对齐文档）

- **通用 API**：`Base URL=/api/v1`、请求头 `Authorization/X-Idempotency-Key/Last-Event-ID`、统一错误模型 `{"code","message","trace_id","details"}`（接口文档 L101-L125）
- **MCP 工具**：`semantic_query/permission_check/get_semantic_meta/report_save`（JSON-RPC 2.0），取数唯一入口，authz 强制改写（接口文档 L658-L689）
- **业务规则**：BR-01 只读、BR-02 行级权限前置、BR-03 脱敏默认开启、BR-04 口径唯一、BR-05 结果权限隔离、BR-06 导出管控、BR-12 权限≤5min 生效、BR-13 订阅链接实时鉴权
- **非功能**：问数 P95<3s（流式）、非 LLM REST <200ms、100 QPS、可用性 99.9%（降级梯度 L0-L3）

## 6. 验证方案（端到端）

1. **后端**：`cd hrchat-server && mvn verify`（JaCoCo>80% 门禁 + 全部单测/集成测试绿）
2. **前端**：`cd hrchat-web && pnpm test && pnpm build`；`pnpm dev` + 后端联调
3. **AI**：`cd hrchat-ai && pytest`（MockLLM）
4. **本地完整集成测试**：启动后端（local profile，H2+内存中间件）→ 前端 dev server 联调 → 按测试用例文档执行核心用例（问数流式/澄清/无权限拦截/报表订阅/审计留痕）→ 全部通过
5. **性能抽查**：非 LLM REST 接口响应 <200ms 抽查（locust 轻量或 JMeter 可选）

## 7. 风险与注意

| 风险 | 应对 |
|---|---|
| 全功能全栈范围大、易超出单轮 | 按 S1→S9 分步提交，每步可独立验证；关键路径 S4（问数主链路）优先保证闭环 |
| H2 与 MySQL/Doris 语法差异 | schema 用 H2 MySQL 兼容模式；query-exec 预留方言适配；Doris 执行在 prod profile 通过 JDBC 实现 |
| AntD Vue 与原型视觉偏差 | 严格按原型文档组件规范实现；视觉核对清单交付 |
| Python 环境依赖安装耗时 | LangGraph/AgentScope 仅 S7 引入；Java 侧 LocalAgentRuntimeImpl 保证集成测试不依赖 Python |
| 覆盖率门禁拉高成本 | 优先保证核心模块（authz/semantic/chat/query-exec）>80%，非核心模块 >70% |

> 注：本计划以模块化单体满足架构文档 ADR-002（用户要求严格遵循文档）；前端 Vue 为唯一文档偏离，已以 ADR-108 记录。
