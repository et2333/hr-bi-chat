# HR 问数评测基线（S1）

## 本地 RAG与对话体验验收（J3/J4/J5）

2026-10-10：实现本地 `BAAI/bge-small-zh-v1.5`（CPU、512 维）+ BM25/RRF，动态检索当前 Java 可见业务口径及 QueryDraft 开发示例。不是历史 SQL 检索，也不生成自由 SQL；组织/指标权限与最终执行仍由 Java 校验。当前保留完整目录约束，检索只补充相关口径与示例，不宣称减少输入 Token。

**R4 复核修正版**：外置语料 `adapters/retrieval_corpus/`（`retrieval-corpus-v2.1`）包含 36 条编译契约校验示例、60 条模拟干扰口径。按用户选择，默认问数只检索 Java 可见目录与有效示例，`evals.retrieval_scale` 才显式启用干扰项。可见但不支持执行的目录项不再标为 executable；检索指纹包含口径版本及内容，语料故障可降级到完整权威目录。

复核原 `f32fe01` 语料发现 31/36 示例存在原文摘录不匹配、擅自补期间等编译错误；现已修正并实际通过 `compile_contextual` 校验。`reviewed=true` 原由生成器自动写入，不能作为人工抽检证据；当前明确记录“程序校验、人工复核未记录”。`--validate` 不再修改 manifest。审计明细：本地 `docs/evaluation-runs/r4-review.json`。

规模题集：dev 42（含 28 条与 few-shot 重合）、**holdout 20**（零重合）、**hard 12**（故意咬合干扰词、零重合）。校验：`python -m evals.build_retrieval_corpus --validate`；对照：`python -m evals.retrieval_scale [dev|holdout|hard]`。

含 **MRR** 的检索对照（99 候选 = 3 可执行 + 60 干扰 + 36 示例）：

| 子集 | 模式 | Hit@1 | Hit@2 | MRR | Top-1 误选率 | 报告 |
|---|---|---:|---:|---:|---:|---|
| 非重合 14（dev） | lexical | 0.14 | 0.29 | 0.21 | — | `…T143402…` |
| 非重合 14（dev） | hybrid | **0.29** | **0.43** | **0.36** | — | 同上 |
| 全量 42（dev） | lexical | 0.40 | 0.64 | 0.52 | 0.57 | 同上 |
| 全量 42（dev） | hybrid | **0.52** | 0.64 | **0.58** | **0.45** | 同上 |
| **holdout 20** | lexical | **0.45** | **0.65** | **0.55** | **0.55** | `…T144734…`（加权前基线） |
| **holdout 20** | hybrid | 0.40 | 0.50 | 0.45 | 0.60 | 同上 |
| **hard 12** | lexical | 0.00 | 0.33 | 0.17 | 1.00 | `…T145048…`（加权前基线） |
| **hard 12** | hybrid | 0.00 | 0.08 | 0.04 | 1.00 | 同上 |

**v2.2 可执行加权 + 过滤池**（`prefer_executable`；另报 `exec_pool_*` = 丢掉干扰项后再算 Hit@K/MRR）：

| 子集 | 模式 | Hit@1（加权后） | 误选率 | exec_pool Hit@1 | exec_pool MRR |
|---|---|---:|---:|---:|---:|
| holdout 20 | lexical | 0.45 | 0.55 | **0.90** | **0.95** |
| holdout 20 | hybrid | **0.95** | **0.00** | **0.95** | **0.975** |
| hard 12 | lexical | 0.00 | 1.00 | **1.00** | **1.00** |
| hard 12 | hybrid | **1.00** | **0.00** | **1.00** | **1.00** |

解读：未加权时 holdout/hard 上 hybrid 可劣于 lexical；加可执行先验后 hybrid 表面 Hit@1 明显改善。`exec_pool_*` 回答「若只在可执行三条里排序，金标是否靠前」——hard 上多为 1.0，说明金标仍在可执行集合内被排到前面，原先崩盘主要是干扰项抢 Top-1。简历需写清指标定义，勿把加权后 Hit@1 与加权前混比。公开语料说明见 [corpus README](../adapters/retrieval_corpus/README.md)。

首次在 `hrchat-ai` 安装 `.[dev,retrieval]` 并执行 `python -m adapters.prepare_embedding`；模型写入被 Git 忽略的 `.models/`。正常请求仅本地加载；缺失/故障在 evidence 中标记 `fallback_full_catalog`，不伪造向量。demo 入口默认 hybrid 且提前检查模型。模型来源：[BGE 官方模型卡](https://huggingface.co/BAAI/bge-small-zh-v1.5)，本轮 revision `7999e1d3359715c523056ef9478215996d62a620`。

在 `hrchat-ai` 执行下列单行命令：

```powershell
.\.venv\Scripts\python.exe -m evals.retrieval
.\.venv\Scripts\python.exe -m evals.retrieval_scale
.\.venv\Scripts\python.exe -m evals.conversation_smoke --browser
.\.venv\Scripts\python.exe -m evals.conversation_smoke --j2 --browser
```

第三条需先构建 Java JAR、安装前端依赖和 Playwright Chromium；使用隔离端口 18106/18107 和浏览器 5199，脚本 LLM + 真实 H2/Java/Python/本地 embedding，**零付费模型调用**。不用于证明自然语言理解准确率。

第四条启用 `local,j2` 可选演示种子，增加两个同名“研发部”。验证模型猜 ID 仍需确认、选组织后补期间、最终条件与刷新历史；默认 `local` 不加载该种子，旧评测组织范围不变。移动迁移文件后请执行一次 `mvn -pl hrchat-bootstrap -am clean package "-DskipTests"`，避免旧 target 中残留默认迁移。

R4/J2 复核验收：`conversation-20261010T111758107661Z/report.json` 的 7 项检查和 2 个浏览器场景通过；默认 profile 复验 `conversation-20261010T111932570355Z/report.json` 的 6 项检查通过，确认研发中心有效组织仍为 `{2,3,4}`、正常检索干扰项为 0。单元/集成测试：Python 498 通过、Java 643 通过。均为离线或脚本模型验证，未新增付费调用。

首轮小目录检索报告：`docs/evaluation-runs/retrieval-20261010T051258546280Z/report.json`。以下是 **v1 语料的历史结果**。扩充后的 v2.1 与这 18 题有 13 题重合，因此当前重跑只作开发诊断；不能沿用“独立问句”结论。R4 规模化报告写入 `docs/evaluation-runs/retrieval-scale-*`（gitignore）。

| 检索方案（小目录 18 题） | 指标首位命中 | 指标前两位命中 | 指标与模式匹配示例前两位命中 | 单题中位耗时 |
|---|---:|---:|---:|---:|
| BM25 | 14/18 | 17/18 | 15/18 | 0.18 ms |
| BGE + BM25/RRF | 16/18 | 17/18 | 16/18 | 7.90 ms |

向量检索改善了此小样本排序，但 Top-2 召回未改善。`off / lexical / hybrid` 端到端问数消融用独立控制器（默认 3 题 × 3 模式 = **最多 9 次** initial 调用，repair 关、干扰项关）：

```powershell
# 仅登记 plan（零付费）；确认 max_model_calls 后再 --execute
.\.venv\Scripts\python.exe -m evals.rag_ablation --model qwen-plus
.\.venv\Scripts\python.exe -m evals.rag_ablation --plan <plan.json路径> --execute
```

对照报告写入 `docs/evaluation-runs/j3-rag-*/comparison.json`（gitignore）。分层报告任务通过率、Token、延迟；**不可**转写成 Hit@K 或“RAG 提升问数准确率 23%”。需 `OPENAI_API_KEY`、已构建 JAR，以及 hybrid 臂的本地 embedding（`python -m adapters.prepare_embedding`）。已完成臂可用 `--compare-only` 重建对照（零调用）。

**首轮真实消融**（`j3-rag-20261010T132731363549Z`，qwen-plus，repair 关，modes 三题）：三臂任务均为 **3/3**；配对 delta 为 0。Token（已知 usage 合计）约 off **4407** / lexical **5270** / hybrid **5280**（检索上下文使 lexical/hybrid 输入更高，本子集未带来任务增益）。hybrid 延迟 p95 受冷启动影响偏高，勿写成生产 SLA。样本极小，不能外推。

**提示目录掺干扰的端到端压力**（评测专用 `HRCHAT_PROMPT_DISTRACTORS=1`，非默认演示；6 道咬合干扰词的硬题 × 3 模式）：

```powershell
.\.venv\Scripts\python.exe -m evals.rag_pressure --model qwen-plus
.\.venv\Scripts\python.exe -m evals.rag_pressure --plan <plan.json> --execute
```

报告 `rag-pressure-20261010T145144088510Z`：off / lexical / hybrid 均为 **3/6**，失败题相同（UNSUPPORTED/CLARIFYING）。在「提示里塞满干扰口径」时，本轮**未观察到检索纠正 off 错误**；可写取舍证据，不可写「RAG 提升端到端成功率」。

跨服务与浏览器报告：`docs/evaluation-runs/conversation-20261010T052148358166Z/report.json`，6 项检查通过：两轮澄清历史、实际本地检索与公共 SSE、分析只准备确认、来源会话隔离、薪酬不被替换、刷新后历史与确认界面。此前失败的 `conversation-20261010T051957033702Z` 保留：脚本将带明确别名的“查人数”误设为入/离职歧义，已改用独立歧义问句“人员变动情况”，未放宽生产校验。

`datasets/manual-feedback-v1/cases.json` 保存 09:58 试用的原句与回归预期；缺失的历史澄清选择明确标注未知。`tests/test_manual_feedback.py` 验证薪酬不静默改成人数、“各部门”不可忽略、期间追问保留原指标与组织。更多保护见 `test_query_retrieval.py`、`test_analysis_entry.py` 以及 Java/前端同名功能测试。

澄清记录保存在 `ChtTurn.inheritJson`，历史选项只读；文字续答关联后续问句，取消/失效/失败保留结果；实际采用条件展示在答案卡。H2 重启会丢失模拟会话，不承诺跨重启持久化。普通问数阶段来自实时节点事件；续答仍使用原同步续答接口。usage 复用已有估算器，缺价格/usage 为未知，非计费账单。

S2 的真实模型规划、调用证据、估算费用及跨服务验证见 [受约束模型查询](S2-query-planning.md)。S1 原始题集与基线报告不改写。

此目录评测**模拟 HR 数据上的用户任务**。首版从 Java 用户 HTTP API 创建真实会话、提交问句、消费 SSE 终态及读取有权限的 SQL 记录；没有调用待测 SQL 构造器生成答案。Java local 是规则基线，不是大模型效果。后台旧模拟评分已在 S0 停用，本 CLI 不调用它。

## 题集与判定

`datasets/hr-query-v1/` 共 60 个案例单元、69 轮：开发集 32/38，冻结集 28/31。完整多轮序列算一个案例，所有轮次都正确才通过。

| 场景 | 案例数 | 检查内容 |
|---|---:|---|
| 明确单轮 | 24 | 三个计数指标、研发中心/一部、自然月/近 7 天/近 30 天 |
| 缺参/澄清 | 8 | 原 S1 缺期间预期保留 FAILED/HRX-1001；S2/S3/S4 按阶段政策验收追问，缺指标仍澄清 |
| 多轮 | 9 | 继承指标和期间只换组织，或继承指标和组织只换期间 |
| 数据权限 | 6 | 三个模拟 HR 身份查询无权组织，不返回数字 |
| 会话隔离 | 3 | 非所有者向他人会话提交问句，被 HRC-2002 拒绝 |
| 日期边界 | 4 | CUSTOM 右边界排他，入职/离职日的历史在职人数 |
| 超范围 | 6 | 未知组织、额外分组、预测、原因分析、主动离职口径 |

`group_id/template_id` 整组分割，同一序列不跨集合。近义轻微改写留在同组；不是随机逐问句切分。此划分只验证指定表达族，不能推断广泛泛化能力。无真实模型训练/调参。

题集与 H2 SQL 种子的 manifest 使用 `sha256-lf-text-v1`：只对这些文本文件统一 CRLF/LF 后计算 SHA-256，保证 Windows 与 Linux 检出同一内容时结果一致。原 Windows 原始字节哈希保留为 `legacy_cases_sha256`，用于识别修正前已保存的基线报告；JAR、代码证据等二进制或原始文件仍按原始字节哈希。此次只修正校验方式，题目和预期值未改。

`reference.py` 只读取 V2 中 `dim_employee`、`fact_emp_change` 的字面记录，以 Python 集合和日期比较计算独立预期；不使用生产 SQL、查询结果或业务实现。`tests/test_evals.py` 用人工可核算锚点验证离职日前后 17→16、9 月 15 日入职边界 0→1。两张表分别有 33/38 行，额外事件不被擅自补入员工维。该预期是独立参考计算，未宣称经 HR 业务专家签字。

成功查询必须同时满足终态、指标 code、时间标签、组织 SQL 范围、标量形态、单位、数值（计数容差 0）。未知或缺失证据不判通过。组织判定检查实际 `org_key IN (...)` 的交集，防止不同组织同值误判。该逻辑**仅适用于当前 local 生成的 SQL 形式**；不作为通用 SQL 授权证明或 QueryPlan 正确率。S2 接入结构化执行计划后应新增版本化适配器。

未知组织等应澄清/说明不支持；仅报“缺时间”虽未输出错误数字，仍未完成该任务。拒绝场景同时检查必要错误码及没有结构化数据。对自然语言里夹带数字的全面事实核验、澄清选项语义质量、详细计划字段留待后续判定器；首版不能证明这些能力。

## 运行

需要本地 Java 17、Maven 和项目 Python 环境。以下 PowerShell 命令从仓库根目录执行；先构建当前代码，再让 helper 创建独立 H2 进程，结束后自动终止自己创建的进程。

```powershell
Set-Location hrchat-server
mvn -pl hrchat-bootstrap -am package -DskipTests -q
Set-Location ../hrchat-ai
.\.venv\Scripts\python.exe -m evals.launch_local --split dev
.\.venv\Scripts\python.exe -m evals.launch_local --split frozen
```

已计分集合的重跑标为 `regression`；不能作为新的独立首测。首版题集首次计分后保持不变，`build_dataset.py` 拒绝覆盖；若改题集，发布新目录和版本、重新分组并解释原因。

已有独立演示服务也可运行：

```powershell
.\.venv\Scripts\python.exe -m evals.run --base-url http://127.0.0.1:18085 --split dev --server-evidence evals/fixtures/local-server.json
```

`local-server.json` 是启动约定，不能证明任意现有服务器的实际配置。确认使用新 H2、2026-09-28 演示日、系统 mock 配置、无租户 ACTIVE 覆盖；`launch_local` 会保存启动参数、JAR hash、PID 与日志供核验。它只绑定回环地址，使用原有 local profile 的模拟身份机制。身份目录为 V2 种子的 `hr01/hr02/hr03/hr04`，不会接入真实用户。

时区固定 `Asia/Shanghai`，结果另记录实际 UTC 运行时间。Java 已有用户接口兼容 remote runtime；CLI 可显式记录 `--runtime remote` 和对应启动证据，但 S1 **未验收 Python remote + Java MCP**，不将 local 数值结果当作跨 backend 一致性证据。真实模型、模型用量和结构化计划的采集按 S2 实施。

## 报告与重放

报告输出 `docs/evaluation-runs/<run_id>/report.json`，由 `.gitignore` 忽略。包含题集/全部 H2 迁移 hash、commit、dirty、tracked diff hash、untracked 文件 hash、命令、机器环境、运行配置、逐题预期/实际、SSE 事件、SQL 取证、错误分类和耗时。无 API 密钥/工具 Token 配置；首版只存模拟标量数据。

`COMPLETED` 表示全部案例执行完，不代表全部通过。超时/断连/异常不中途缩小分母；未完整执行时标 `PARTIAL/FAILED`，成功率为 `null`。每完成一个案例原子替换报告；中断保留已有记录和完整计划数。模型 usage 不可得时为 `null`，不把字符估算当作真实 Token。

任务成功率的分母含应澄清、应拒绝、尚未实现的多轮案例。P50/P95 是单轮请求加 SQL 取证请求的时间，不是纯模型延迟，也不是前端首字延迟。小样本只报告通过数/总数。

复跑全部案例，再比较原开发/冻结报告：

```powershell
.\.venv\Scripts\python.exe -m evals.launch_local --split all
.\.venv\Scripts\python.exe -m evals.compare --original ../docs/evaluation-runs/<dev_run>/report.json ../docs/evaluation-runs/<frozen_run>/report.json --replay ../docs/evaluation-runs/<replay_run>/report.json
```

比较逐案例的预期、通过与错误类型、实际终态、错误码、表格值、结论卡、图表和口径；忽略 ID、时钟、延迟。生成 `replay-check.json`。旧原始报告不覆盖。首次计分历史根据本地报告目录判定；移动/删除报告会丢失该证据，应保留原始报告及基线文档。

## 测试和边界

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_evals.py -q
```

覆盖独立参考值、同值错组织、时间/指标/模式错误、空值/布尔/NaN、拒绝时带数据、重复/冲突终态、分组泄漏、超时、连接失败、格式异常与中断。故障注入是 **runner/判定器单元测试**，不进入 60 个用户任务分母，也不宣称已验证业务工具的故障恢复。

首版不含趋势/明细/比率数值判定、租户切换/权限变更、多轮澄清提交、远端工具故障注入；不以占位案例凑到建议的 80 题。后续对应能力到位时新增独立题集版本，保留 v1 作回归。

S3 的多轮状态、期间快捷澄清与独立生命周期检查见 [S3-task-memory.md](S3-task-memory.md)。运行 `evals.launch_remote --stage s3` 将原 v1 的 9 组多轮全部纳入阶段分母；`--memory-smoke` 另验连续任务和澄清提交，结果不混入 v1 的 60 组分母。

S4 的一次受约束修复、总调用预算及首次/最终结果统计见 [S4-bounded-repair.md](S4-bounded-repair.md)。使用 `--stage s4` 开启修复评测；原始 v1 题集保持不变，故障注入单独报告。

S5 的规则/完整方案对照、任务记忆消融、演示及匿名试用模板见 [S5-evidence-and-demo.md](S5-evidence-and-demo.md)。`evals.s5_experiment` 先登记实验，再显式执行；失败与中断保留，不自动挑选最好成绩。

已完成的单轮 Qwen Plus 对照与 Memory 消融结果见 [S5-results.md](S5-results.md)，不将模拟回归解释为生产准确率。

## J1：趋势、组织分组与明细结果核验

新增独立版本 `datasets/hr-query-modes-v1/`，18 个单轮任务：趋势 5、组织分组 5、明细 5、权限拒绝 2、标量对照 1。全部属于开发回归集，相关表达模板放在同一组；没有把同模板轻微改写当成未见冻结集。旧 `hr-query-v1` 的 60 组题、阶段政策和历史成绩保持不变，两套成绩不合并。

日期、组织和是否含下级由显式界面参数给定。本扩展主要回答“规划进入执行后，结果是否正确”，不证明模型能理解各种自然日期表达。`mode_reference.py` 直接读取原始模拟记录，按日历快照、直接组织归属及事件日期独立计算；不导入 SQL 构造器。`build_modes_dataset.py` 只用于首次出题，拒绝覆盖已发布版本。

判定同时检查执行计划、组织展开、指标版本、逐期间/部门计数、完整行集合（含重复次数）、允许字段和脱敏标记、表格与图表、结论中的末值/合计。部门分组是直属记录的互斥分项；父级不是再算一次子树总数。在职趋势逐月核对期末快照，不能跨月求和。事件趋势沿用稀疏结果：没有事件的月份不自动补 0；null 也不能补 0。

明细最多保存 50 行，结论明确为“本次返回”，表格 `total` 是**已保存行数**。分页接口分页的是这份结果，不是继续扫描数据库；本扩展核对第一页、第二页和空尾页。由于脱敏后不同员工可能显示相同内容，公共接口只能核对脱敏后的多重集合，不能据此宣称精确验证每个原始身份。独立的 `QueryModeContractIntegrationTest` 在专用 H2 中使用显式测试字段授权，核对离职日前后准确名单、超过 50 条的截断、同月事件排序，以及跨组织/租户隔离；不修改原种子或放宽产品权限。

### 本地运行与报告

以下命令均为单行。先在仓库根目录构建当前 Java JAR：

```powershell
mvn -f hrchat-server/pom.xml -pl hrchat-bootstrap -am package -DskipTests -q
```

然后 `cd hrchat-ai`，运行不收费的脚本模型链路：

```powershell
.\.venv\Scripts\python.exe -m evals.launch_remote --dataset hr-query-modes-v1 --fixture-cases --split all --report-pointer ..\docs\evaluation-runs\j1-latest-report.txt
```

`--fixture-cases` 只替换条件提取模型，后续 Java 用户 API → Python 编排 → Java MCP → H2 → 答案/分页接口均实际执行，不读取预期答案来伪造工具返回，也不发外部模型请求。报告记录 `model_kind=fixture`、`model_effectiveness=false`。J1 不使用 S2/S3/S4 的题目政策覆盖；任一严格判定不通过，runner 返回非零。`COMPLETED` 仅表示全部执行完。

2026-10-10 本地结果：

| 记录 | 结果 | 说明 |
|---|---|---|
| 首次完整报告 `20261009T223026961361Z` | 12/18 | 5 个趋势因网关将未选粒度补为 NONE 而失败；另 1 个身份缺诊断权限 |
| 修复后报告 `20261009T223243468818Z` | 17/18 | 趋势 5/5、分组 4/5、明细 5/5、权限拒绝 2/2、标量 1/1；属于回归 |

报告 ID 使用 UTC，以上记录对应北京时间 10 月 10 日。完整报告保存在 `docs/evaluation-runs/<run_id>/report.json`，不提交远程；更早一次报告保存回调因沿用旧阶段评分字段而中断，也保留在本地。该保存兼容性问题已修复并补回归。

唯一未通过案例 `hr-modes-v1-10`：`hr03` 查询职能部的空分组结果符合独立预期，但 `/evidence` 和 SQL 诊断入口受 `chat:viewSql` 权限限制，返回 403。因缺少执行范围证据，仍计不通过，不删题、不借其他身份取证、不放宽权限。该记录不能等同于“已查到错误数字”，也不能写成 18/18。错误按规划/终态、执行、展示、权限和证据分类，仅作排查线索，非自动根因认定。

本轮同时修正：只选日期时不再默认添加粒度约束；已授权聚合月份/部门名默认可读，显式字段隐藏/脱敏策略仍优先；工具行列不齐、布尔/字符串/非有限计数直接拒绝，null 保留；部分组织缺值时不输出完整合计；事件明细增加稳定排序；明细文案披露 50 行上限。

最终 Python 全量测试 469 项通过；Java 定向验证 26 项通过（语义工具 20、历史时点 2、J1 专用 H2 契约 4）。最终判定器对已记录响应重新计分仍为 17/18，重放不产生服务或模型调用。上述测试数不并入 18 个用户任务分母。

真实模型首次验收于 2026-10-10 获准执行最多 3 次，选择 `01/06/11`，关闭修复且不重跑。报告 `20261010T013252772226Z` 记录 3 次 initial 请求尝试，全部 `ConnectError`，没有 HTTP 响应或 usage，任务 0/3；这是执行环境连接失败，不能解读为模型答错。随后不带 Key 的连通性检查定位到本次沙箱的 `HTTP_PROXY/HTTPS_PROXY/ALL_PROXY=127.0.0.1:9` 拒绝连接；经批准在沙箱外访问服务根路径获得 HTTP 404，证明 HTTPS 可达，不代表模型认证或推理成功。该失败报告保留。

用户随后重新授权，在正常联网环境执行同三题，报告 `20261010T014303808696Z`：**3/3 通过**（趋势 `01`、部门分组 `06`、明细及保存结果分页 `11`）。Qwen Plus 共 3 次 initial 调用，均 HTTP 200，无修复、无自动重试；实际输入 3,550、输出 269，合计 **3,819 Token**。缺少适用价格快照，金额保持未知。本轮调用额度已用完，后续新增付费验收须另获授权。三题均为既有开发集抽样，日期/组织由显式参数提供，不作为自然语言泛化成绩；也不改变完整脚本回归 17/18 的独立结论。

复现命令（会发起模型请求，仅在授权后运行；代理问题应通过获准的正常联网执行环境处理）：

```powershell
.\.venv\Scripts\python.exe -m evals.launch_remote --dataset hr-query-modes-v1 --split dev --stage s3 --repair off --model qwen-plus --case-id hr-modes-v1-01 --case-id hr-modes-v1-06 --case-id hr-modes-v1-11
```

这只是模型接入后的三个任务验收，不能推导趋势/明细的生产准确率。J1 没有新增指标、同比、任意 SQL、数据库全量分页或浏览器渲染测试。
