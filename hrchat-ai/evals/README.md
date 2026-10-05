# HR 问数评测基线（S1）

此目录评测**模拟 HR 数据上的用户任务**。首版从 Java 用户 HTTP API 创建真实会话、提交问句、消费 SSE 终态及读取有权限的 SQL 记录；没有调用待测 SQL 构造器生成答案。Java local 是规则基线，不是大模型效果。后台旧模拟评分已在 S0 停用，本 CLI 不调用它。

## 题集与判定

`datasets/hr-query-v1/` 共 60 个案例单元、69 轮：开发集 32/38，冻结集 28/31。完整多轮序列算一个案例，所有轮次都正确才通过。

| 场景 | 案例数 | 检查内容 |
|---|---:|---|
| 明确单轮 | 24 | 三个计数指标、研发中心/一部、自然月/近 7 天/近 30 天 |
| 缺参/澄清 | 8 | 缺指标触发澄清；缺统计期间按 S0 停止并返回 HRX-1001 |
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
