# HR 问数评测基线（S1）

## I4：AI 洞察全链路验收与三组对照（2026-10-11）

阶段 I4 先冻结本轮代码、Schema、提示与数据版本，再交付固定评测集、三组对照、判定规则、真实模型小样本及运行归档。所有报告写入被 Git 忽略的 `docs/evaluation-runs/`，保留全部成功与失败，不挑最佳重跑。

### 固定评测集与判定（I4-1、I4-4）

`datasets/insight-scenarios-v1/`：5 个固定模拟业务场景（dev 3、frozen 2）加 3 组上下文消融。每例预写正确事实、禁止结论与可执行动作；`evals/insight_scenarios.py` 加载时用 `build_fact_bundle` 复核手写预期，出现漂移即报错。

| 场景 | 指标/语义 | 关键预期 | 推荐动作 |
|---|---|---|---|
| IS-01 研发两期持平抵消 (dev) | leave_count/flow | 净变化 0、部门 -1/+1 抵消；禁止净贡献率 | 部门贡献，可执行 |
| IS-02 小基数变化 (dev) | headcount/stock | 1→2 优先绝对变化；存量不跨期求和 | 仅人工核查，不可执行 |
| IS-03 缺月序列 (dev) | leave_count/flow | 缺 2026-02，禁止持续趋势 | 部门贡献，可执行 |
| IS-04 比率口径不足 (frozen) | leave_rate/ratio | 缺分子/分母，禁止百分点变化 | 仅人工核查，不可执行 |
| IS-05 上下文不完整 (frozen) | leave_count/flow | 无基期，仅并列极值可算 | 部门贡献，缺参不可执行 |

消融：AB-01 移除基期、AB-02 移除部门分项、AB-03 移除比率分子/分母。判定规则（`score_insight`/`score_ablation`）固定记录重点覆盖分子/分母、事实漂移、事实引用错误、禁止结论与动作匹配；消融另查是否引用了被移除的证据。相关单测 6 项见 `tests/test_insight_scenarios.py`。

### 三组对照（I4-3）

- **组 A 模板 vs LLM**：同一事实包，比较解读而非算数。
- **组 B 缺上下文 vs 完整上下文**：逐项移除一个上下文变量，检查模型是否降级而非断言被移除的证据。
- **组 C 单 vs 双 Agent**：复用 `evals.s6_real`，见 [S6 部门贡献文档](S6-department-contribution.md)。

在 `hrchat-ai` 执行（离线脚本模型、零付费）：

```powershell
.\.venv\Scripts\python.exe -m evals.insight_compare --group all --offline
```

真实模型（须显式确认，整轮 ≤60 次调用）：

```powershell
.\.venv\Scripts\python.exe -m evals.insight_compare --group all --real --model qwen3.6-plus --confirm-real-calls
```

离线冒烟：组 A 模板 5/5、脚本 LLM 5/5 全覆盖，组 B 3/3。脚本臂只证明链路，不代表模型质量。

### 真实模型小样本（I4-5）

模型取 `.env.local` 配置的 `qwen3.6-plus`（该 Key 免费额度不接受 `qwen-plus`）；输出上限沿用 `limits`（每例最多 2 次调用、60 秒）。

| 运行 | 报告 ID | 真实调用 | 结果 |
|---|---|---:|---|
| 组 A/B `qwen-plus`（保留失败） | `insight-compare-20261011T135722362276Z` | 11 | 全部 HTTP 403（AllocationQuota），usage 未知 |
| 组 A/B `qwen3.6-plus` | `insight-compare-20261011T135759254435Z` | 22 | LLM 5 臂全部 `draft_validation_failed`；模板 5/5、组 B 3/3 |
| 组 C 单/双 Agent | `s6-real-20261011T140302039924Z` | 10 | 6/6 通过（详见 S6 文档） |
| 跨服务/浏览器（脚本模型） | `s6-smoke-20261011T140446260286Z` | 0 | 10 项检查全通过 |

`qwen3.6-plus` 组 A/B 共 22 次调用（每臂 1 次 + 1 次修复）：组 A 输入 8,315/输出 3,012 tokens，组 B 输入 10,269/输出 3,671，合计 25,267 tokens；任务耗时组 A 55.7 秒、组 B 67.7 秒。组 C 输入 18,801/输出 1,346，合计 20,147 tokens，任务耗时约 31.5 秒。未配置价格快照，金额保持未知。报告内 `summary.calls` 依赖适配器 `.calls` 属性，真实适配器未暴露，故为 0；真实调用数以 `generation.call_count` 累计为准（22）。

**真实模型发现（冻结本轮提示，未改生产 prompt）**：真实草稿全部未过契约校验，两类可复现错误——(1) 对 `absolute_change` 事实误用 predicate `tied_extreme`；(2) 动作参数中的期间被序列化为字符串 `"2026-03-01~2026-04-01"`，而契约要求 `{start,end}` 对象。这是冻结提示词的健壮性问题，本轮如实记录，修复与复验见下节。

边界声明：组 C 本轮为脚本模型搭配冻结模拟工具，不是 Java/MCP/浏览器全栈真实模型验收（全栈由 `s6_smoke` 单独覆盖）；双 Agent 未观察到相对单 Agent 的净收益。单次小样本只做接通与行为验收，不声称泛化准确率。

### 契约修复与真实模型复验（2026-10-11）

针对 I4-5 发现的两类契约错误，完成三项修复并重测：

- **提示升级**（`insight/generation.py`）：`PROMPT_VERSION` 由 `insight-prompt-1` → `insight-prompt-2`，补入 predicate→事实类型/方向映射（无法对应时须填 `null`）、`next_actions` 能力与报告指标 `metric_code` 一致约束、期间对象形状（`{start,end}`）、缺失参数不得补造（须写入 `missing_params` 且 `executable=false`）。
- **校验放宽**（`insight/validator.py`）：仅 `fact`/`statistical_contribution` 强制 `claim.fact_ids`，`limitation`/`next_check` 允许无引用（真实模型对后者天然不引用事实）。
- **能力目录**（`insight/capabilities.py`）：向模型可见目录暴露不可执行的 `manual_review` 兜底能力，供非 `leave_count` 指标选择。

离线回归：insight 相关单测 **37 passed**；`insight_compare --group all --offline` 报告 `insight-compare-20261011T141936613100Z`（模板 5/5、脚本 LLM 5/5、组 B 3/3，11 调用）。

真实复验（`qwen3.6-plus`，组 A，`--confirm-real-calls`）：报告 `insight-compare-20261011T142507954661Z`，5 例 LLM 臂全部 `status=complete`、`ok=true`、`prompt_version=insight-prompt-2`，首调即通过（`call_count=1`，无修复），llm_ok 由修复前 **1/5 提升至 5/5**、覆盖率 5/5，模板 5/5。本轮真实调用 **5 次**（每例 1 次）。修复前对照：`insight-compare-20261011T135759254435Z`（LLM 5 臂全部 `draft_validation_failed`）。

### 全链路验收与需求→证据映射（I4-2）

跨栈复验（2026-10-11）：Python 全量 **660 passed**；Web vitest **136 passed** 且 `vue-tsc` 无错误；Java `mvn test` BUILD SUCCESS（report 137、admin 94、bootstrap 21、subscription 10）；`s6_smoke --browser` 10 项 API 检查 + 2 个浏览器流程全绿（报告 `s6-smoke-20261011T140446260286Z`）。

| 验收点 | 证据 |
|---|---|
| 保存→复用→刷新新版本 | `ReportDataVersionServiceTest`、`ReportVersionConsistencyIntegrationTest`、`ReportChartServiceTest` |
| 幂等/复用不新增调用 | `s6_smoke` `idempotent_start`；`InsightTaskServiceTest`；`useReportChartAnalysis.spec.ts` |
| 下钻/回溯 | `useReportChartAnalysis.spec.ts`（切下钻/回溯清空并更新版本、丢弃过期轮询） |
| 进一步分析 | `s6_smoke` `browser_report_analysis_flow`；`ReportAnalysisPanel.spec.ts` |
| 超时 | `s6_run` 注入 `deadline_exhausted` |
| 格式错误 | `test_insight_generation.py`「malformed output is repaired once」；`s6_run` `invalid_model_json` |
| 取消 | `s6_smoke` `cancel_does_not_become_success`；`s6_run` `cancel_during_tool`；`ReportAnalysisPanel.spec.ts` |
| 进程重启 | `AnalysisTaskPersistenceTest`（运行中任务恢复为中断、成功结果可读） |
| 权限变化 | `s6_smoke` `ownership_and_tenant_denied`；`AttributionServiceTest.runningTask_terminatesWhenPermissionChangesMidFlight`（运行中撤权传导）；Java `FunctionPermissionIntegrationTest` |
| 迟到响应 | `useReportChartAnalysis.spec.ts`（离开页面/切换下钻丢弃过期请求） |

**如实标注**：任务运行中的**动态权限变化**已在服务层补齐运行中撤权传导验证（`AttributionServiceTest.runningTask_terminatesWhenPermissionChangesMidFlight`：RUNNING 期间撤权→下一个流式事件/在途工具调用被拒→任务 FAILED 并清空结果、调用 `runtime.cancel` 传导至 Python、后续读取与调用持续被拒）；发起/读取时的权限重检与拒绝越权由 `s6_smoke` 所有权/租户检查覆盖。跨服务 smoke 无权限变更注入面，故该传导不在浏览器链路重复验证。

### 演示与已知边界（I4-6）

演示步骤：问数生成答案 → 保存为报表（继承来源口径）→ 打开复用（无新增模型调用）→ 手动刷新数据生成新版本及配套洞察 → 图表下钻/回溯（同版、幂等）→ 侧边「进一步分析」确认两期并查看部门贡献与证据。任一结论均可回溯到数据版本与来源。

已知边界：首轮进一步分析仅支持 `leave_count` 两期部门贡献；Java/Python 均为单进程执行，重启后未完成任务标为中断、允许显式重试但不自动恢复；离线脚本模型与真实模型结果分开报告。

### R6：评分器版本化、负向控制与最终可见结果验收（2026-10-12）

评分规则与独立预期均已版本化：`evals/insight_scenarios.py` 的 `SCORING_VERSION="insight-scoring-4"`；独立 oracle `evals/insight_oracle.py` 的 `ORACLE_VERSION="insight-oracle-1"`（纯算术，不导入 `fact_builder`/`validator`，数值容差 `TOLERANCE=1e-6`）。加载时以 `verify_oracle` 做三重一致性核验（手写预期、oracle 计算、样例事实包），任一漂移即报错，避免“预期跟着实现漂移”。

打分范围由“事实类型/computable”扩展到**数值（含容差）、单位、范围、期间、版本、引用、必需覆盖、状态、局限与动作参数**。`ok` 的新语义是“**最终可见结果通过**”：空 claims、数值错（如全改 999999）、单位/期间/范围错、引用无关事实、非法动作参数，以及“把降级（程序模板）当完整成功”，均不得 `ok=true`。C19 逐项覆盖上述负例（`tests/test_insight_scenarios.py`），先确认负例按预期失败，再调生成器。C20 针对**经持久化与 API 序列化后的可见结果**评测（而非仅 Python 内存结构），并核对隐藏结构与 UI 证据映射，防止“Python claims 正确、页面仍显示模板”的假通过。

**scoring-3：可见正文从 segments/facts 独立重建（A4）**。旧版只比对 `summary` 与 `claim.text` 一致，可在保留全部正确事实/引用的同时，把 `claims.text`、`summary`、`points.label` 一起改成虚构因果句而不被发现。现在评分器独立复制槽位渲染规则（`_slot_text`：`unit→fact.unit`、`period_label→fact.period_ref`、`scope_label→fact.scope_ref`、`category→fact.category`、`value→` 按 `unit∈{ratio,share}` 用 `+x.x%`/`x.x%`、signed 类型 `+g`、否则 `g`），从 `claim.segments + facts` **重建**可见文本并逐字段比对：`_visible_errors` 校验 `claim.text` 必须等于重建文本（`claim_text_not_from_segments`）、`narrative.summary` 必须来自证据型 claim（`visible_summary_not_from_narrative`）、`points.label` 必须落在重建文本集合内（`point_not_from_claim`）。自由文本另查禁数字与因果词。C21 三个负向控制（含 999999 虚构正文、文本段夹带数字、事实槽位未绑定）覆盖该类绕过。

**scoring-4：顶层 limitations 同受约束（B3）**。旧版把 `draft.limitations` 当作自由字符串直接展示，可在保留全部正确事实/claims/narrative/动作的同时追加一句虚构因果（如 `999999 people because management failed`）而不被任何评分发现。现在生产校验（`validator.free_text_issues`，正文文本段与 limitations 共用）拒绝含数字/日期或未获支持因果的局限，草稿因此修复一次后仍不合法即降级；评分器对 `limitations` 也做同一检查（`limitation_typed_number` / `limitation_unsupported_causality`），场景评分与 transport 评分同时生效。C24 三例覆盖（合法 `fact_type:reason` 局限仍通过、虚构数字局限失败、无数字因果局限失败）。生成提示同步升级为 `insight-prompt-5`，明确 limitations 不得出现数字/日期/单位与未获支持的因果。

**跨服务输出评分（A4 第二部分）**。新增 `normalize_java_insight`：把 Java `InsightView` 的 camelCase（`next_actions`/`capability_id`/`data_version`/`source.snapshot_id`/`schema_version`）规范到评分器形状，`claims/facts/narrative` 原样透传（Java 以 `Map` 保存 Python 响应，内层键仍为 snake_case）；`score_cross_service` 在规范化后调用 `score_insight` 并标注 `transport="java_persisted_get"`。C22 校验该映射，C23 校验 `score_transport` 能重建可见正文并捕获虚构 summary 与悬空 fact 引用。真正 Java POST→持久化→GET 的端到端评分由 `evals/insight_cross_service.py` 执行（见下）。

对已归档报告做**离线重评分**（零模型调用，旧报告保留、另产 `rescore-<scoring>-<run>.json`），本轮不新增付费真实模型实验（DP-2 选项 A）：

```powershell
.\.venv\Scripts\python.exe -m evals.insight_compare --rescore ..\docs\evaluation-runs\<run_id>\report.json
```

跨服务/浏览器验收（fresh H2 + 脚本模型，零付费）：`s6_smoke --browser` 的 10 项 API 检查与 2 个浏览器流程（`analysis.spec.ts`、`report-analysis.spec.ts`）全绿，报告 `docs/evaluation-runs/s6-smoke-20261011T203429676537Z/report.json`。

**在线跨服务可见结果评分（A4 最终验收）**：`evals/insight_cross_service.py` 启动 Python fixture gateway + Java bootstrap（fresh H2），走真实 HTTP：建会话 → 提问 → 存报表 → `data-versions/ensure` 至 READY → `chart-analysis` 取来源 → `insight-tasks` → 轮询 `GET insight`，对**经 Java POST→持久化→GET 的结果**做 `normalize_java_insight + score_transport` 评分，并注入虚构 summary 负例断言必须失败。运行（`hrchat-ai` 目录，零付费脚本模型）：

```powershell
.\.venv\Scripts\python.exe -m evals.insight_cross_service
```

通过报告 `docs/evaluation-runs/insight-cross-service-20261011T224845914142Z/report.json`：`passed=true`、`real_model_calls=0`，4 项检查全 true（`cross_service_visible_body_reconstructed`、`cross_service_source_identity`、`cross_service_references_resolve`、`fabricated_body_rejected`）；正例 `score.ok=true`（claims 2、facts 3、无 visible/reference 错误），篡改 summary 后 `ok=false` 且错误为 `visible_summary_not_from_narrative`。**如实区分**：本轮证明的是“Python 内部评分 + 跨服务输出评分（脚本模型）”；真实模型质量仍未验证（待授权）。

本轮同时修复两处后端缺陷（见决策记录 DP-13/DP-14）：分析上下文契约漂移（Java `trusted()` 曾向 `extra="forbid"` 的 Python `AnalysisContext` 多发两个键 → 网关 422）；数据版本 `scopeRef` 写入口径与读取口径不一致（生成时按未收窄范围取指纹、读取时按冻结上下文收窄后取指纹），导致报表详情页组件端点恒 403、AI 洞察卡片不渲染。

全量回归（2026-10-12，三套件均通过，零付费调用）：Java `mvn -o test` **BUILD SUCCESS**（15 模块，735 用例，0 失败；含 `QueryModeContractIntegrationTest` 4、`ReportVersionConsistencyIntegrationTest` 4、`ReportDataVersionServiceTest` 4）；Python `pytest -q` **723 passed**（1 项 Starlette 弃用警告）；Web `pnpm test` 28 文件 157 用例通过、`pnpm type-check`（vue-tsc --noEmit）通过。真实模型质量见下：prompt-7/8 已达 `ok=2/5`，prompt-9 待复验；组 C 待授权。

**F8 尺度对照回归（2026-10-12 追加）**：`tests/test_insight_fact_builder.py` 由 9 增至 16 项，覆盖 `ratio_scale` 的 fraction（0.005→0.008 得 **+0.3pp**，不是按数值大小猜出的 +30pp）、percent（0.5→0.8 得 +0.3pp）、尺度缺失（`ratio_scale_missing` 不可计算，负向控制）、单位 `%` 推导默认、显式尺度优先、端点缺失（`missing_value`）与非比率指标不产出。**边界**：Java `ReportChartService.buildRichRequest` 恒传分子/分母为 null，故生产路径当前恒为 `ratio_components_missing`，两个尺度分支仅在单测可达；测试只锁契约，未改变该产品口径决策。

**真实模型复验（2026-10-12，`insight-prompt-5` + `insight-scoring-4`）**：用户仅授权组 A/B，报告 `insight-compare-20261012T003000739110Z`（`real_model_insight_compare`）。**结果未达标**：组 A LLM 臂 **`ok=0/5`**（IS-01/03/04/05 `degraded: draft_validation_failed`，IS-02 `complete` 但覆盖不全）、组 A 模板臂 5/5 `degraded`（设计如此）、组 B `ok=3/3`；真实调用 **22 次**（11 个 LLM 臂各 `call_count=2`，即**首稿 11/11 校验失败**）。逐次调用记录 provider/model/request_id/usage(actual)/耗时。与 `insight-prompt-2` 的 5/5 **不可直接比较**（评分由 `insight-scoring-2` 升为 `-4`，且新增 limitations 与全 claim 文本规则）。组 C 未授权、未执行。

**失败定因归档（2026-10-12 追加，零付费）**：上一轮只记 `degradation_reason`，无法回溯定因。现补齐两层证据：
- 生产 `insight.generation` 的 `generation` 增记 **`validation_issues`**（每次被拒草稿的 `call_index`/`stage`/`outcome`/`issue_total` 与 `kind`/`message`/`fixable`/`claim_id`，各阶段最多 10 条；修复成功的首稿原因同样保留）。**不含模型原文**——该字段经任务 API 暴露，只放我们自己的校验消息与标识。
- 评测 harness `evals/insight_compare.py` 用 `RecordingInsightAdapter` 包装每一臂，把模型响应原文（截断 4000 字符 + `text_sha256` + `text_length` + `truncated`）记入报告 **`model_io`**（组 B 分 `full`/`partial`）；`calls` 改为按臂计数，`summary.calls` 不再恒为 0（离线复跑实测 11）。
- 回归：`tests/test_insight_generation.py` 新增 2 例，Python 全量 715 → **717 passed**；离线对照复跑 llm 5/5、组 B 3/3。
- 上述证据**只对下一次运行生效**：本次已发生的失败无法补记原文，定因需再次授权重跑（候选原因：自由文本规则与真实模型「引用月份」习惯冲突，或覆盖/主结论更严规则）。

**第二次授权运行与定因（2026-10-12）**：报告 `insight-compare-20261012T004447952532Z`（`calls=20`，组 A LLM `ok=0/5`、组 B `ok=3/3`，与首轮一致）。`model_io` + `validation_issues` 给出确切断因：
- **主因 A（我方载荷缺陷）**：系统提示允许 `field=period_label/category`，但 `build_prompt` 的事实 JSON 未含 `period_ref`/`category`，模型无从判断 → 给 `tied_extreme:*` 绑 `period_label` → `bad_segment: fact has no period to render`，整条 claim 被拒（IS-03/04/05）。IS-03 原始草稿其余部分完全合规。
- **次因 B（规则过宽）**：`limitation` 文本「由于时间序列存在缺失或不连续，无法判定…」被因果词规则拒绝——该句是局限说明而非因果断言（IS-03、IS-04 各 1 处）。
- **级联 C**：`narrative references unknown claim` 全是 A/B 拒绝后的噪声。
- 修复轮与首稿错误完全相同 → 载荷缺口未解时修复无效；IS-01 首轮失败次轮 `complete` → 存在运行间非确定性。
- **此前怀疑的 limitations 数字规则冲突未成立**：B3 规则本轮仅触发 1 次且为因果词。定因详情见 [总体验收报告 §7.3](../docs/development-plans/1012-AI洞察补强总体验收报告.md)。

**按定因修复（2026-10-12，零付费，`insight-prompt-6`）**：
- **载荷补齐**：`build_prompt` 的事实 JSON 增补 `period_ref`/`category`/`tied_categories`，提示注明「只有事实确实带该字段才可绑定对应槽」——直接消除主因（模型给 `tied_extreme` 绑 `period_label` 被整条拒）。
- **因果词规则收窄**：`validator.free_text_issues` 增加 `hedged_causality_ok`，`limitation`/`next_check` 文本与顶层 `limitations` 含限定标记（`无法/缺失/缺少/不连续/建议…`）时豁免因果词；数字/日期禁令与断言式因果不变。评分器 `insight_scenarios._causal_hit` 同口径。**已知边界**：关键词判定，限定标记在句内即豁免同句因果词，非语义保证。
- 回归：新增 2 例（prompt 载荷含槽位元数据；限定表述放行而断言式因果仍拒），Python 717 → **719 passed**；离线对照复跑 llm 5/5、组 B 3/3、`calls=11`；B3 的 C24 反例仍按预期失败。
- **prompt-5 的真实证据已被取代**，prompt-6 的真实通过率需再授权一次小样本（仅组 A/B）验证。

**prompt-6 复验（2026-10-12，第三次授权运行）**：报告 `insight-compare-20261012T005631062010Z`（`calls=17`）。**修复主因生效**：组 A LLM `degraded` 4/5 → **1/5**、`coverage_incomplete` 3/5 → **1/5**、事实覆盖分子 1~2 → **4/5**；4/5 用例已 `complete` 且数值/引用/可见正文/动作参数**全部无错**，`fact has no period to render` 不再出现。`ok` 仍 0/5，但阻塞点换成更窄的一条：`limitation_errors: undisclosed_incomputable`（4/5 例）——评分器要求不可计算事实的 **`fact_type` 标识**字面出现在 limitations 中，而模型改用自然语言局限（语义披露但无标识），属**格式契约不匹配**；另 IS-02 首稿与修复稿均 `draft_parse_failed`（模型侧非法 JSON）。下一步：提示要求局限以受控格式 `fact_type:补充说明` 引用不可计算事实。

**prompt-7：局限披露改为受控格式（2026-10-12，零付费）**：系统提示的 `limitations` 改为必须为「`fact_type:补充说明`」，`fact_type` 取自 `computable=false` 的事实类型且须覆盖全部；用户提示新增由 `build_fact_bundle` 确定性生成的「不可计算的事实类型（每条都必须在 limitations 中以「fact_type:补充说明」披露）：…」一行，模型无需自行推断。评分器 `_limitation_errors` 本就做该披露检查，故 validator/评分器口径未改。回归：`test_prompt_lists_incomputable_fact_types_for_disclosure`、`test_structured_limitation_is_required_for_disclosure`，Python 719 → **721 passed**；离线对照复跑 llm 5/5、组 B 3/3、`calls=11`。

**prompt-7 复验（第四次授权运行）**：报告 `insight-compare-20261012T010444822599Z`（`calls=17`），组 A LLM **`ok=2/5`（首次非 0）**、组 B `3/3`；`limitations` 已为受控格式并满足披露检查。剩余 3 例失败均为**模型侧契约违规**：事实 claim 的 text 片段夹带数字/日期（IS-05 写了 `{"type":"text","text":"2026-03"}` 与内联 `org:3`）、fact claim 文本含因果词（IS-01 c3）、`unit_mismatch`（IS-04 c1）；三例修复轮重复同样错误故降级。

**prompt-8 复验（第五次授权运行）**：报告 `insight-compare-20261012T011223783601Z`（`calls=17`），组 A LLM **`ok=2/5`**（IS-03、IS-04）、组 B `3/3`。**空 text 片段问题已消除**（无 `draft_parse_failed`）。仍失败 3 例且修复轮重复同样错误：IS-01 事实 claim 的 text 夹带数字/日期、IS-02 `unit_mismatch`（均模型侧违规），IS-05 **`fact has no category to render: tied_extreme:max`**——**我方契约陷阱**：`tied_extreme` 的类目在 `tied_categories`，而槽位渲染只读 `category`（与 prompt-6 修掉的 `period_label` 同源）。下一步：`category` 槽对 `tied_extreme` 回退渲染 `tied_categories`（validator 与评分器同口径）。运行间非确定性明显：prompt-7 与 prompt-8 通过的是不同用例，聚合同为 2/5。

**prompt-9：category 槽对 tied_extreme 回退渲染（2026-10-12，零付费）**：`validator.category_slot_text` 统一「渲染 + 槽位校验」口径——`category` 槽渲染 `fact.category`，为空时回退 `"、".join(tied_categories)`；评分器 `_slot_text` 同步。提示改为「tied_extreme 等并列事实的类目请用 category 槽（渲染其 tied_categories）」，取代此前「用 text 片段表达」（后者会把事实值诱导进 text，与 text 不得承载数值/事实的口径冲突）。回归：`test_tied_extreme_category_slot_renders_tied_categories`，Python 722 → **723 passed**；离线对照复跑 llm 5/5、组 B 3/3、`calls=11`。**待授权复验**。

## R5：检索分层评测（2026-10-11）

新增 [题集与指标说明](datasets/retrieval-quality-v1/README.md)：60 条合成题、20 个表达家族，dev/frozen 各 30 条。人工业务复核待完成；与旧题集/语料无规范化完全重合，不等于已证明语义独立。四臂共用 63 条口径及 36 个示例，直接调用生产排序函数；纯 BGE 仅用于离线消融，日常问数模式与权限边界不变。

在 `hrchat-ai` 单行执行 ` .\.venv\Scripts\python.exe -m evals.retrieval_quality --split dev ` 或 ` .\.venv\Scripts\python.exe -m evals.retrieval_quality --split frozen `；`--validate` 只校验数据。均不调用 LLM，模型只从本地加载。报告区分完整 MRR/MRR@K、命中/召回、不可执行首项与真正口径误选；多轮无指标问句的检索分母显式排除。旧 runner 的 `metric_mrr` 保留为 `metric_mrr_at_2` 的兼容别名，历史 JSON 不改写。

首次冻结集结果：30 题中 27 题有口径相关性标签（含 3 题模糊指标候选覆盖），18 题有同指标同模式示例标签。下表仅为合成目录上的检索，不是问数/澄清成功率。

| 方案 | 口径 Hit@1（/27） | 完整 MRR | MRR@2 | 示例 Hit@2（/18） | 不支持题被可执行项占首位（/6） |
|---|---:|---:|---:|---:|---:|
| BM25 | 15/27 | 0.707 | 0.667 | 10/18 | 0/6 |
| BGE | 9/27 | 0.503 | 0.389 | 11/18 | 0/6 |
| BGE+BM25/RRF | 15/27 | 0.713 | 0.648 | 12/18 | 0/6 |
| 混合+可执行先验 | 21/27 | 0.833 | 0.778 | 12/18 | 6/6 |

结论：混合检索没有提高冻结集口径 Hit@1，示例命中有小样本改善；不能写“RAG 显著提高问数准确率”。可执行先验的总体高分掩盖了 6 条不支持题的错误替换风险，**不宜作为默认语义选择策略**；当前默认日常目录本就不启用此先验，本轮未更改。混合相对 BM25 的口径 Hit@1 配对差值为 0，按 9 个表达家族 bootstrap 的探索性 95% 区间为 [-11.1,+11.1] 个百分点。

开发集对应 Hit@1 为 BM25 16/27、BGE 9/27、混合 17/27、混合+先验 21/27；先验同样使 6/6 不支持题被可执行项抢占首位。未根据 dev/frozen 结果修改权重、Prompt 或题集。后续使用本题集调参时，只能称回归，需另建测试集验证泛化。

本地证据：`r5-retrieval-20261010T165004439256Z`（dev）、`r5-retrieval-20261010T165103662090Z`（frozen），位于被忽略的 `docs/evaluation-runs/`。包含逐题排名、分层分母、配对结果、数据/模型/代码指纹。Python 离线回归 **551 passed**，新增付费调用 **0**。真实模型任务对照及紧凑目录 Token 效率实验尚未实施；需另行授权 API 用量。

## R5：结构洞察与 Reviewer（2026-10-11）

已补齐部门最大增减、增减抵消和绝对变化份额；Reviewer 可选择已核验的事实引用补充遗漏，仍遵守 4 次模型/6 次工具/一次补查上限。页面展示补查依据、证据和未解决项。新增 8 类×4 臂的脚本故障注入评测，区分程序拦截和 Reviewer 行为，不把离线脚本计作真实模型提升。说明与单行运行命令见 [R5 Reviewer 评测](R5-reviewer-quality.md)。本轮 Python 564 项、前端相关 16 项、类型检查及真实浏览器脚本联调通过，付费调用 0。

## 本地 RAG与对话体验验收（J3/J4/J5）

2026-10-10：实现本地 `BAAI/bge-small-zh-v1.5`（CPU、512 维）+ BM25/RRF，动态检索当前 Java 可见业务口径及 QueryDraft 开发示例。不是历史 SQL 检索，也不生成自由 SQL；组织/指标权限与最终执行仍由 Java 校验。当前保留完整目录约束，检索只补充相关口径与示例，不宣称减少输入 Token。

**R4 复核修正版**：外置语料 `adapters/retrieval_corpus/`（`retrieval-corpus-v2.1`）包含 36 条编译契约校验示例、60 条模拟干扰口径。按用户选择，默认问数只检索 Java 可见目录与有效示例，`evals.retrieval_scale` 才显式启用干扰项。可见但不支持执行的目录项不再标为 executable；检索指纹包含口径版本及内容，语料故障可降级到完整权威目录。

复核原 `f32fe01` 语料发现 31/36 示例存在原文摘录不匹配、擅自补期间等编译错误；现已修正并实际通过 `compile_contextual` 校验。`reviewed=true` 原由生成器自动写入，不能作为人工抽检证据；当前明确记录“程序校验、人工复核未记录”。`--validate` 不再修改 manifest。审计明细：本地 `docs/evaluation-runs/r4-review.json`。

规模题集：dev 42（含 28 条与 few-shot 重合）、**holdout 20**（零重合）、**hard 12**（故意咬合干扰词、零重合）。校验：`python -m evals.build_retrieval_corpus --validate`；对照：`python -m evals.retrieval_scale [dev|holdout|hard]`。

含 **MRR@2** 的历史检索对照（99 候选 = 3 可执行 + 60 干扰 + 36 示例）：

| 子集 | 模式 | Hit@1 | Hit@2 | MRR@2 | Top-1 非可执行项占比 | 报告 |
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

| 子集 | 模式 | Hit@1（加权后） | Top-1 非可执行项占比 | exec_pool Hit@1 | exec_pool MRR |
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

**2026-10-11 压测 v2 修正**：上述 3/6 为历史 v1 记录，不能将三道失败都归为模型或检索错误。`rp-02` 的“不是主动离职专项”被关键词护栏误拦；现仅在明确请求总离职并以独立否定子句对比口径时放行，保留原问句供模型理解，主动离职查询、排除筛选和含混表达仍不取数。单轮和多轮提示词同步区分查询目标与否定口径。

`rp-03/rp-05` 原脚本同时传入“本月”和 CUSTOM `[2026-09-01,2026-09-28)`，与模拟日期 9 月 28 日下应包含当天的 `[2026-09-01,2026-09-29)` 冲突。正确脚本草稿也能离线复现 `context_conflict`；v2 改用 `THIS_MONTH`，系统原有冲突校验保持有效。旧报告缺少草稿和澄清载荷，因此不声称完全还原了当时模型输出。

`rag-pressure-v2` 记录各题固定输入与独立日期预期，判定升级为：完成状态、单值模式、指标、组织/实际授权范围、期间均正确且执行证据可读。报告新增结构化草稿、停止原因、校验阶段、澄清问题、执行计划和逐项评分错误。它验证条件执行一致性，不替代 J1 对结果数值的独立核验。旧计划与旧报告不改写，v1 与 v2 通过率不能直接归因为模型提升。v2 真实模型复验仍待授权，最多 18 次 Qwen Plus，修复关闭且不自动重跑。

本次离线验证：Python 全量 **544 passed**（1 项既有 Starlette 弃用警告），含明确否定口径、真实排除筛选、多轮日期冲突、错误范围/期间不得判成功，以及失败诊断保留的回归。新增付费模型调用为 0；这些脚本模型测试不作为真实模型修复成功率。

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
