# 权限单一事实源与 Java/Python 问数链路收敛实施方案

## 1. 目的

本方案用于把远程 Python 问数链路收敛到现有 Java 数据访问安全边界，并修正模型配置生命周期中会阻碍跨栈联调的问题。

实施完成后：

- Java 是身份、租户、功能权限、组织范围、字段策略和查询执行的服务端事实源，并作为已发布语义元数据的统一服务入口。
- Python 负责意图理解、澄清、分析编排和结果表达，不持有业务库凭证，也不维护业务权限规则。
- Java 本地问数与 Python 远程问数最终共用同一套语义元数据、权限改写、只读执行和审计链路。
- `baseUrl`、`deployUrl`、配置发布、缓存刷新和模拟部署具有明确且一致的运行语义。

这里的“权限单一事实源”特指**问数数据访问权限**。角色功能权限已由 `sec_role_permission → UserContext.functionPerms` 统一，本方案不重复建设角色授权存储。

## 2. 依据与不可突破的约束

本方案沿用仓库已有结论：

| 依据 | 已确定的约束 |
|---|---|
| [《HR 智能问数技术选型文档》](../2-1_HR智能问数技术选型文档.md) | Agent 取数必须经过 Java 权限中心；Java 以 MCP 工具向 Python 暴露能力 |
| [《HR 智能问数技术架构优化设计方案》](../2-2_HR智能问数技术架构优化设计方案.md) D-2 | Python 禁止直连 Doris/MySQL，取数唯一入口为 Java MCP |
| [《HR 智能问数应用架构设计文档》](../3-2_HR智能问数应用架构设计文档.md) ADR-001/ADR-007 | Java 负责权限和事务，Python 负责编排；MCP 是跨栈取数入口 |
| [《HR 智能问数接口设计文档》](../3-4_HR智能问数接口设计文档.md) 2.9 | `POST /mcp`、JSON-RPC 2.0、`X-Service-Token`、四个工具及统一错误结构 |
| [《认证与数据访问安全加固实施方案》](认证与数据访问安全加固实施方案.md) | 身份和租户由 Java 建立可信上下文；SQL 必须经结构校验、权限改写和参数绑定 |
| 现有实现 | Java 本地链路已通过 `SqlRewriteService → QueryExecService` 执行授权后的只读查询 |

以下约束作为编码验收红线：

1. Python 不保存数据库账号，不直接执行 SQL，不根据工号、角色名或租户号自行放行数据。
2. Python 传回的用户、租户、角色、组织范围和字段策略均不能直接作为 Java 授权依据。
3. `semantic_query` 每次都重新执行 Java 授权；`permission_check` 只用于预检和交互提示，不能成为后续查询的授权凭证。
4. 远程链路故障不得静默切换到 `DemoQueryExecutor` 并返回看似真实的业务结果。
5. Java 本地运行时可作为本地 Demo 和明确配置的降级路径，但与 Python 远程运行时不能在单次请求中混用两套数据来源。

### 2.1 既有文档冲突处理

- 本方案以 `SIMULATED` 取代《三功能模块开发方案》中“mock 部署直接标记 ACTIVE”的旧规则；模拟功能仍保留，但不再表达真实远程服务可用。
- 《接口设计文档》中“用户 JWT 不透传应用层”与“Java → agent-gateway 透传 JWT”存在冲突。本期不转发浏览器 JWT，改用 Java 签发的短期工具令牌。
- MCP 属于 Java 应用能力，部署地址统一归入 Java 应用命名空间；旧文档中的 `hrchat-ai` MCP 地址在更新部署契约时修正。
- 机读契约按原设计应由 `hrchat-deploy` 维护，但当前仓库尚无 MCP Schema。本期必须补齐机读契约，不能只增加 Java DTO。

## 3. 当前实现与问题

### 3.1 已具备能力

- Java 本地链路已经从 `SemanticMetaService` 取得指标定义，生成带安全占位符的 SQL，经 `SqlRewriteService.authorize` 注入租户和组织条件，再由 `QueryExecService` 只读执行。
- `UserContextService` 已统一装配可信用户、租户、功能权限、组织授权、字段策略和权限指纹。
- Java 已有 `RemoteAgentRuntimeClient` 调用 Python 问数、澄清和洞察接口，并透传 Trace 之外的基本请求信息。
- Python 已有 LangGraph 问数流程和按租户切换 LLM 适配器的基础结构。
- 接口设计已经给出 `semantic_query`、`permission_check`、`get_semantic_meta`、`report_save` 四个 MCP 工具的目标契约。

### 3.2 主要差距

| 问题 | 当前表现 | 风险或结果 |
|---|---|---|
| Python 独立取数 | 全局 `DemoQueryExecutor` 直接返回演示值 | Java 与 Python 结果可能不同，无法证明真实权限生效 |
| Python 独立权限判断 | `GRANTED_ORGS` 按工号静态判断，未知用户默认放行 | 形成第二套权限事实源，且存在默认放行 |
| Python 独立语义定义 | Python 使用静态 `METRICS`、组织和演示值 | 指标发布后远程链路不能同步更新 |
| 跨栈身份不可信 | Java 当前仅向 Python 发送 `X-User-No/X-Tenant-No`，Python 后续也可原样自报给 Java | MCP 侧若信任这些字段会产生身份和租户伪造风险 |
| MCP 工具未落地 | 文档有契约，Java 无 `/mcp` 实现，Python 无工具客户端 | 远程编排无法复用 Java 的权限和执行能力 |
| 地址用途混用 | `AgentRuntimeFactory` 用模型 `baseUrl` 构造 Python 客户端；部署时 `deployUrl` 为空会回退 `baseUrl` | Java 可能把问数或配置请求发给模型供应商接口 |
| 运行时缓存不刷新 | `AgentRuntimeFactory.clients` 使用 `computeIfAbsent`，发布、回滚或删除后无对应失效入口 | 后续请求继续使用旧网关、旧模型或旧 API Key |
| 模拟部署冒充真实可用 | `deploy-mock=true` 时直接标记 `ACTIVE/UP` | Java 可能将后续问数路由到未启动的 Python 服务 |
| Java/Python 响应不一致 | Python SYNC 返回终态本体，Java 期待 `answer_payload` 外层；澄清一侧返回 SSE、另一侧按 JSON 解析 | remote 问数和澄清可能在权限链路接入前已经解析失败 |
| 上下文 DTO 不一致 | Java 发送扁平 camelCase `ContextOverride`，Python 接收 snake_case 且组织字段嵌套 | 时间、组织和指标覆盖可能 422 或被忽略；组织覆盖未进入 Python 演示权限判断 |
| 远程会话键错误 | Java 使用 `java-{empNo}` 作为 Python sessionId | 同用户多会话及多租户同工号会共享 Python 事件序列和缓冲 |
| Python 默认配置继承缓存 | 租户槽位首次复制默认 adapter 后独立缓存 | 更新默认配置不能刷新已建立的继承槽位；多 worker 状态可能不一致 |
| 模型租户边界不完整 | 部署、回滚、健康检查和版本查询未全部执行租户归属校验 | 租户管理员可能访问或操作非本租户配置 |
| 版本快照含敏感值 | `configJson` 当前包含 API Key，版本接口返回原始快照 | 历史版本和接口可能泄露密钥 |

## 4. 目标职责与调用链

### 4.1 职责边界

| 组件 | 负责 | 不负责 |
|---|---|---|
| Java chat/model | 建立可信请求身份、选择 local/remote 运行时、签发短期工具令牌、汇总 SSE/终态 | 不在远程模式中代替 Python 做意图编排 |
| Python agent-gateway | 意图路由、澄清、调用 LLM、组织工具调用、结果表达 | 不保存业务库凭证，不裁决数据权限，不返回本地演示数据冒充真实查询 |
| Java MCP/authz | 校验服务身份和工具令牌、重新装配 `UserContext`、执行功能/行级/字段级裁决 | 不信任 Python 自报权限 |
| Java semantic/query-exec | 解析指标和维度、构造安全查询、只读执行、返回口径与脱敏结果 | 不接受 Python 提交的任意可执行 SQL |
| Java audit | 记录工具、用户、租户、权限指纹、SQL 摘要、行数、耗时和结果状态 | 不记录 API Key、服务令牌和敏感明文值 |

### 4.2 目标链路

```text
Web → Java chat-service
       │  Java 验证身份并构建 UserContext
       │  生成 invocation_id 并签发短期 tool_context_token
       ▼
Python agent-gateway / LangGraph
       │  get_semantic_meta：解析可用指标/维度
       │  permission_check：需要澄清或提前提示时预检
       │  semantic_query：提交语义查询对象
       ▼
Java /mcp
       │  校验 X-Service-Token + tool_context_token
       │  根据 token 中的用户标识重新查询 UserContext
       │  重新校验租户、功能权限、组织范围和字段策略
       │  语义模板 → SQL 权限改写 → 只读执行 → 审计
       ▼
Python Presenter → Java → Web
```

## 5. 跨栈身份与服务认证

### 5.1 两层认证

MCP 调用同时校验两类凭证：

1. `X-Service-Token`：证明调用方是允许访问 MCP 的 Python 服务。local/test 从环境变量注入；生产由 Secret 管理并配合内网 NetworkPolicy，后续可升级 mTLS。
2. `tool_context_token`：Java 在接收用户问答后签发的短期令牌，证明本次工具调用绑定到哪个可信用户、租户和问答任务。Java → Python 请求体携带该字段；Python 调用 MCP 时只将它原样放入 `params.context.tool_context_token`。

### 5.2 工具令牌最小载荷

建议使用 Java 签名的短期 JWT 或等价签名令牌，载荷只包含：

- `iss=hrchat-server`、`aud=hrchat-mcp`
- `sub=empNo`（与现有 `UserContext` / mock 身份一致；权限按工号重建）、`tenant_id`
- `invocation_id`、`jti`
- `iat`、`exp`，有效期建议 5 分钟；长任务由 Java 明确续签

`AgentRuntimeClient` 增加显式调用上下文，携带 `tenantId/sessionId/javaAskId/invocationId/traceId`。`invocation_id` 由 Java 在进入运行时前生成，并贯穿 Java → Python → MCP。它解决首次工具调用绑定问题；远程 sessionId 使用真实 Java 会话标识，禁止再由工号拼接。

令牌不携带角色、组织范围、字段策略或功能权限。MCP 校验签名和请求绑定后，必须根据 `sub` 从数据库重新装配当前 `UserContext`，因此授权变更能按 Java 缓存失效规则生效。

local 的 `X-User-No` 仅用于浏览器到 Java 的 mock 身份切换。它不能成为 Python 调用 MCP 时的用户认证凭据。

## 6. MCP 工具范围与契约收敛

### 6.1 本期工具

| 工具 | 本期用途 | 强制行为 |
|---|---|---|
| `get_semantic_meta` | 代替 Python 静态 `METRICS`，返回已发布指标、维度和口径 | 按当前租户可见语义对象返回，禁止返回密钥或物理库凭证 |
| `permission_check` | 在生成答案前预检查询/导出是否可能执行 | 每次根据最新 `UserContext` 计算；结果不可复用为查询授权 |
| `semantic_query` | 唯一取数入口 | 只接收指标、维度、筛选、时间和组织等语义对象；服务端生成并改写 SQL |

`report_save` 沿用既有接口设计，但不阻塞本期问数链路切换；待 Python 编排需要“由回答保存报表”时单独接入。

### 6.2 协议与错误

- 端点：`POST /mcp`，JSON-RPC 2.0；至少支持 `tools/list` 和 `tools/call`。
- 契约 DTO 放在 `hrchat-api`；同时在 `hrchat-deploy` 补 MCP 机读 Schema，Java Controller 与 Python 客户端通过契约测试共同校验。
- Python 不向 `semantic_query` 传原始 SQL。需要保留调试 SQL 时，由 Java 在响应中按 `chat:view_sql` 权限决定是否返回。
- `org_context` 只表示用户希望查询的组织范围。Java 必须与最新授权范围求交；无交集或请求越权时拒绝，不能将它视为已授权范围。
- 数据源由 Java 根据指标绑定关系解析 `datasourceCode` 和方言。Python 不能提交 JDBC 地址、凭据或任意数据源编码；跨数据源规则沿用《动态数据源接入实施方案》。
- 业务错误保留 HRC/HRD/HRA 错误码，外层映射为 JSON-RPC `-32000`；权限拒绝不可重试，超时和短暂不可用按既有降级规则处理。
- 请求和响应携带 `trace_id`；日志禁止输出服务令牌、工具令牌、API Key 和字段明文。
- 每次工具调用携带唯一 `tool_call_id`。MCP 客户端超时为 8 秒，可对只读查询重试 1 次；服务端以 `tool_call_id` 关联审计，避免把同一次重试统计成两次独立业务操作。未来 `report_save` 必须另带幂等键。
- `semantic_query` 需要行数上限、执行超时和响应体大小上限；Java 查询执行仍使用当前 5 秒默认值，MCP 外层 8 秒包含网络和序列化时间。
- Python 本期不缓存业务查询结果。未来如增加缓存，缓存键必须包含 Java 返回的 `permissionFingerprint`、租户、语义版本和查询参数，撤权后不得命中旧结果。

## 7. 模型配置生命周期前置修正

这部分先于 MCP 联调实施，避免把地址或旧缓存问题误判为工具链问题。

### 7.1 字段语义

| 字段/配置 | 唯一用途 |
|---|---|
| `llm_model_config.base_url` | 模型供应商的 OpenAI 兼容 API 地址，仅供 Java/Python 的模型客户端使用，不得作为 agent-gateway 地址 |
| `llm_model_config.deploy_url` | Python agent-gateway 地址，供 Java 下发配置、健康检查和构造远程运行时客户端 |
| `hrchat.ai.remote-base-url` | 未选择数据库 ACTIVE 配置时的 Python 网关兜底地址 |

约束：`deployUrl` 为空时，部署操作返回参数错误，不再回退 `baseUrl`；`AgentRuntimeFactory.toRemote` 只使用 `deployUrl` 构造 `RemoteAgentRuntimeClient`。创建和编辑时分别校验两个 URL 的协议、主机及末尾斜杠规范。

### 7.2 发布、回滚和缓存

- 配置发布成功后，只允许同租户一个实际 ACTIVE 配置；原 ACTIVE 配置转为非活动状态。
- 发布、回滚、删除和停用事务提交后，调用 `AgentRuntimeFactory.evict(tenantId)`；下一次请求重新读取数据库构造客户端。
- 缓存签名至少包含 `deployUrl + baseUrl + model + apiKey 摘要 + 配置版本`，即使漏掉主动失效也不会长期复用不同连接。
- Python `/v1/config` 返回应用后的配置摘要；Java 校验租户、模型和配置版本一致后才置为 ACTIVE。
- 回滚必须重新下发旧版本快照、执行健康检查并刷新缓存，不能只修改数据库状态。
- Python 默认配置更新时，失效所有“继承默认”的租户槽位；本期明确按单进程运行，进入多 worker/多副本部署前改为共享配置或配置事件广播。
- Java 启动、Python 重启或连接恢复后执行配置 reconciliation：Java 将数据库中实际 ACTIVE 的版本重新下发，并核对 Python 返回的租户、模型和配置版本，避免 Python 内存丢失后数据库仍显示 ACTIVE。
- 健康检查按目标租户和配置版本返回状态，不能只检查默认槽位进程存活。
- Java 本地 LLM 增强也只读取已发布且 ACTIVE 的配置；普通 PATCH 只形成待发布版本，不能绕过部署流程立即改变本地调用。
- 部署、回滚、健康检查、版本查询、删除和编辑统一执行租户归属校验。租户管理员不能编辑系统默认配置。
- 版本快照不保存或返回明文 API Key。本期回滚只恢复非敏感配置并沿用当前凭据；生产阶段改为可版本化的 `secretRef`。
- 对旧的混用 URL 数据不进行猜测迁移：升级后统一转为 PENDING，由管理员补齐 `baseUrl/deployUrl` 并重新发布。Demo 种子同步改为语义正确的两个地址。

### 7.3 模拟部署

- `deploy-mock=true` 只用于 local/test，部署状态记录为 `SIMULATED`，界面显示“模拟生效”。
- `AgentRuntimeFactory` 只把经过真实下发和健康检查的 `ACTIVE` 配置用于远程路由；`SIMULATED` 不改变问数运行时。
- 非 local/test profile 禁止启用 `deploy-mock`，启动时校验失败并拒绝启动。

## 8. 分阶段实施

### 阶段 A：模型配置生命周期修正

1. 明确并校验 `baseUrl/deployUrl`，修正部署、健康检查和运行时构造逻辑。
2. 为发布、回滚、删除和停用增加事务提交后的运行时缓存失效。
3. 引入 `SIMULATED` 状态，隔离 mock 部署与真实 ACTIVE 路由。
4. 补租户唯一 ACTIVE、回滚实际下发、配置摘要核对及异常状态测试。
5. 统一 Java ↔ Python 的问答契约：SYNC 使用固定终态信封，澄清明确统一为 JSON 终态或 SSE 流，不再由两端各自推断。
6. 补全部署、回滚、健康、版本和系统默认配置的租户边界，并移除版本快照中的明文 API Key。
7. Java 启动和 Python 恢复连接时重放 ACTIVE 配置；本地 LLM Provider 同样只消费 ACTIVE 配置。

完成标准：修改配置并发布后，下一次请求使用新配置；Python 未启动时不能显示真实 `ACTIVE/UP`，也不会被远程路由选中。

### 阶段 B：契约和可信上下文

1. 在 `hrchat-api` 定义 MCP 信封、三个本期工具 DTO 和错误 DTO，并在 `hrchat-deploy` 增加对应机读 Schema。
2. 统一 Java/Python 的 `ContextOverride` 字段命名与嵌套结构，用契约测试覆盖时间、组织和指标覆盖。
3. 扩展 `AgentRuntimeClient` 调用上下文，传递真实 tenant/session/ask/trace；Java 先生成 `invocation_id`，`RemoteAgentRuntimeClient` 将工具令牌和 invocation ID 随问答任务发给 Python。
4. Java MCP 入口校验 `X-Service-Token` 与工具令牌，根据用户 ID 重新装配 `UserContext`。
5. 配置 local/test 服务令牌和签名密钥占位；密钥只从环境变量读取。

完成标准：伪造工号、租户、过期令牌、错 audience、错服务令牌和跨 invocation 复用均被拒绝。

### 阶段 C：Java MCP 工具

1. 实现 `tools/list`。
2. 实现 `get_semantic_meta`，复用 Java 已发布语义元数据。
3. 实现 `permission_check`，复用 `AuthzService/UserContextService`。
4. 实现 `semantic_query`，复用语义模板、`SqlRewriteService`、`QueryExecService` 和审计。
5. 禁止该入口接受任意 SQL；查询结果应用字段策略和行数上限。
6. 根据指标元数据选择已注册数据源，禁止 Python 指定物理连接信息。

完成标准：同一用户通过 Java 本地调用与 MCP 调用时，权限指纹、组织过滤、字段策略和核心指标结果一致。

### 阶段 D：Python 工具客户端与流程切换

1. 新增异步 MCP 客户端，统一超时、错误映射、Trace 和令牌转交。
2. 将 Python 节点依赖从具体 `DemoQueryExecutor` 改为 `SemanticToolClient` 抽象。
3. `retrieve/nl2sql` 从 `get_semantic_meta` 获取语义对象；`execute` 调用 `semantic_query`。
4. 删除远程正式链路中的 `GRANTED_ORGS/check_org_permission` 和演示 SQL 权限注释。
5. `DemoQueryExecutor` 只保留在显式 `demo` profile 的 Python 单元测试或独立演示入口；remote/prod 若选择 demo executor，启动直接失败。

完成标准：Python 进程没有数据库凭证和业务授权表；未知用户默认拒绝；Java 拒绝时 Python 原样返回统一权限错误，不生成演示答案。

### 阶段 E：切换、回归与文档

1. local 默认仍可使用 Java 本地运行时；显式选择 remote 时必须完成 Python → Java MCP 闭环。
2. 更新 README、接口设计、部署环境变量和手动验收步骤。
3. 补 Java 集成测试、Python 契约测试和端到端权限矩阵。
4. 联调通过后移除生产包中对 Python 演示数据和演示权限模块的引用。

## 9. 测试与验收矩阵

| 场景 | 预期 |
|---|---|
| `hr01` 查询研发中心 | local 与 remote 都成功，租户/组织过滤和结果一致 |
| `hr02` 查询研发中心 | local 与 remote 都返回 HRC-2003，Python 不返回 Demo 值 |
| `test09` 无数据授权查询 | Java MCP 拒绝，Python 不执行或缓存结果 |
| 修改用户组织授权后再次查询 | 后续工具调用使用新权限指纹和新范围 |
| Python 修改请求中的角色/组织/tenant | Java 忽略自报授权并按工具令牌重建上下文 |
| Python 请求超出授权的 `org_context` | Java 求交后拒绝或只返回授权交集，绝不扩大范围 |
| 工具令牌过期、错 audience、跨 invocation 复用 | Java 拒绝，日志不泄露令牌内容 |
| `X-Service-Token` 缺失或错误 | `/mcp` 拒绝 |
| Python 或 MCP 超时 | 返回可识别的服务不可用错误，不回退演示数据 |
| 修改并发布 LLM 配置 | 下一次请求使用新配置；旧客户端不再命中 |
| `deploy-mock=true` 且 Python 未启动 | 仅显示 SIMULATED，不生成 ACTIVE/UP，不切远程路由 |
| `baseUrl` 是模型 API、`deployUrl` 是 Python 网关 | 模型调用与配置/问数调用分别到达正确端点 |
| 默认模型配置更新 | 已建立的继承租户槽位失效并使用新默认配置 |
| 多角色用户撤销其中一个角色 | remote 后续查询不命中旧结果，并使用新的权限指纹 |
| 同一只读工具因超时重试 | 业务结果一致，审计可由同一 `tool_call_id` 关联 |

自动化检查至少包括：

- Java：MCP Controller/Service 单测、工具令牌安全用例、H2 全链路集成、运行时缓存失效和模型状态机测试。
- Python：MCP 客户端契约、错误映射、无演示回退、租户并发隔离和 LangGraph 流程测试。
- 跨栈：Java/Python 各启动一个进程，覆盖允许、越权、撤权、过期令牌、超时和配置热切换。
- 回归：现有 Java 问数、报表、订阅、审计、前端和构建测试继续通过。

## 10. 配置项

| 配置 | 示例 | 说明 |
|---|---|---|
| Java `hrchat.ai.runtime` | `local` / `remote` | 明确选择问数编排运行时 |
| Java `hrchat.ai.remote-base-url` | `http://127.0.0.1:8000` | Python 网关兜底地址 |
| Java `HRCHAT_MCP_SERVICE_TOKEN` | 环境变量 | MCP 调用方服务令牌，禁止提交真实值 |
| Java `HRCHAT_TOOL_TOKEN_SECRET` | 环境变量 | local/test 签名密钥；生产接入密钥管理 |
| Python `JAVA_MCP_BASE_URL` | `http://127.0.0.1:8080/mcp` | Java MCP 地址 |
| Python `HRCHAT_MCP_SERVICE_TOKEN` | 与 Java 配对 | Python 调用 MCP 时使用 |
| Python `QUERY_BACKEND` | `java_mcp` / `demo` | remote/prod 只允许 `java_mcp` |

配置日志只输出地址、profile、租户槽位和配置版本，不输出 API Key、服务令牌或签名密钥。

## 11. 代码落点建议

| 模块 | 主要改动 |
|---|---|
| `hrchat-api` | MCP 信封、工具 arguments/result、跨栈错误契约 |
| `hrchat-authz` | 工具令牌验证后的 `UserContext` 重建、权限预检、语义查询授权 |
| `hrchat-query-exec` | MCP 语义查询执行门面、限制与审计字段 |
| `hrchat-semantic` | 已发布指标/维度查询及安全 SQL 模板生成 |
| `hrchat-model` | URL 语义、部署状态机、缓存失效、远程运行时选择 |
| `hrchat-ai-client` | Java → Python 请求携带工具令牌与 Trace |
| `hrchat-bootstrap` | `/mcp` 入口、配置装配、profile 启动校验 |
| `hrchat-ai` | MCP 客户端、工具抽象、LangGraph 节点替换、Demo 隔离 |
| `hrchat-deploy` | 本期补 MCP 机读契约；部署阶段再补 Secret 和 NetworkPolicy |

## 12. 提交建议

按可独立验证的边界提交：

1. `fix: separate model and agent gateway urls`
2. `fix: refresh agent runtime after config changes`
3. `fix: isolate simulated model deployments`
4. `feat: add trusted MCP tool context`
5. `feat: expose authorized semantic query tools`
6. `feat: route Python queries through Java MCP`
7. `test: verify cross-runtime permission consistency`
8. `docs: document converged query authorization flow`

## 13. 延后项

- `report_save` 的 Python 工具接入。
- APISIX 对 MCP 的网关策略、mTLS、生产 Secret 轮换和多实例分布式令牌撤销。
- Kafka 权限变更广播、Redis 多实例缓存失效和生产压测。
- Python 归因团队的多工具并行预算控制；其取数仍必须复用本方案的 MCP 客户端。
- 将 local Java 运行时也改造成 MCP 自调用。当前 Java 内部直接复用相同 Service 已能保证权限和执行逻辑一致，无需为形式统一增加网络跳转。

## 14. 决策结论

依据现有架构文档，本方案直接采用以下结论，不再保留双方案：

- 跨栈工具协议采用 MCP JSON-RPC over HTTP，不另建长期并行的私有 REST 取数接口。
- Python 只提交语义查询对象，不提交任意 SQL。
- 工具令牌只定位可信身份，实际权限由 Java 每次重建。
- 模型 `baseUrl` 与 Python `deployUrl` 严格分离。
- mock 部署使用 `SIMULATED` 状态，不参与远程路由。
- 本期接入三个问数必需工具；`report_save` 后续按需接入。
- Java 生成 `invocation_id` 并签发工具令牌，Python 只负责转交。
- 历史配置的混用 URL 统一转 PENDING 后人工补齐，不自动猜测字段含义。
- 版本快照不保留 API Key 明文，回滚沿用当前凭据。

阶段 B 实施决策（2026-10-02）：

- 工具令牌采用 HS256 JWT（`HRCHAT_TOOL_TOKEN_SECRET`），与浏览器登录 JWT（未来 SSO/RS256）密钥与用途完全隔离。
- `sub=empNo`；`ContextOverride` 跨栈统一嵌套 snake_case（Java Web 仍可用扁平 camelCase，在 Remote 边界映射）。
- 阶段 B 暴露 `POST /mcp` 鉴权骨架 + `tools/list`；`tools/call` 鉴权通过后对业务工具返回明确未实现，执行留给阶段 C。

阶段 C 实施决策（2026-10-02）：

- `org_context` 越权直接拒绝 `HRC-2003`（与本地 ask 一致）。
- `semantic_query` 本期最小闭环：标量指标 + 时间 + 组织；`dimensions`/`filters` 暂不支持并明确报错。
- `McpController` 留 authz 鉴权分发；业务 handler 与 `SemanticQueryService` 放 `hrchat-ai-client`。
- 行数默认封顶 200；请求 `limit` 不可超过；结果强制字段策略脱敏；禁止 Python 指定 SQL/数据源。

阶段 D 实施决策（2026-10-02）：

- 默认 `QUERY_BACKEND=java_mcp`；缺 `JAVA_MCP_BASE_URL` / `HRCHAT_MCP_SERVICE_TOKEN` 时 Python 启动失败。
- 正式链路：`retrieve` ← `get_semantic_meta`；`execute` ← `semantic_query`；去掉远程路径上的 `GRANTED_ORGS`。
- `QUERY_BACKEND=demo` 仅单测/独立演示（假数据）；Java `runtime=local` 不受影响。
- MCP 超时 8s，传输/5xx 重试 1 次（同一 `tool_call_id`）；业务 `-32000`（如 HRC-2003）不重试、不回退 Demo。
- 归因（AttributionTeam）本期不动。
- remote 问数：`ChatController` ask/clarify 以 MVC `Callable` 异步执行，释放 Tomcat 请求线程，避免 Python 回调 `/mcp` 同进程回环 502。

阶段 E 实施决策（2026-10-04）：

- 范围取 **1A**：文档对齐 + 既有关键单测确认 + 手验清单；不做全量 §9 跨进程 CI。
- 演示边界取 **2A**：`java_mcp` 正式链路不依赖 `GRANTED_ORGS`；`demo_data` 仅 `QUERY_BACKEND=demo` 单测/演示与归因暂用；生产/`remote` 禁止 demo 后端。
- 接口文档取 **3B**：`docs/3-4` §2.9/3.1 改为服务令牌 + 工具令牌；原「浏览器 JWT / UserContext 透传」精简为历史决策备注。
- 追问 chips 取 **4A**：本期不做多轮上下文；手验文档标明占位（HRA-4001 不判失败）。
- 手验入口：`docs/remote-java-mcp问数本地验收.md`；部署手册环境变量与 README 交叉引用。

当前没有需要产品侧补充的业务决策。若进入生产部署阶段，需要再确认企业内部服务认证设施（统一服务 JWT 或 mTLS）以及密钥管理平台；这不阻塞当前 local/demo 编码。
