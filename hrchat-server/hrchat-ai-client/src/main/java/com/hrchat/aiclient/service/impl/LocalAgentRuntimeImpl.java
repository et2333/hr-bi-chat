package com.hrchat.aiclient.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.mcp.HeadcountAsOf;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.aiclient.service.llm.LlmCallException;
import com.hrchat.aiclient.service.llm.LlmChatClient;
import com.hrchat.aiclient.service.llm.LlmChatClientProvider;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.api.chat.ContextOverride;
import com.hrchat.api.chat.TimeRange;
import com.hrchat.api.sse.SseEvent;
import com.hrchat.api.sse.SseEvents;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.model.AuthorizedQuery;
import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.api.PageResult;
import com.hrchat.knowledge.model.ScoredDoc;
import com.hrchat.knowledge.model.SearchFilter;
import com.hrchat.knowledge.search.HybridRetriever;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.dto.MetricSummary;
import com.hrchat.semantic.entity.BizSynonym;
import com.hrchat.semantic.mapper.BizSynonymMapper;
import com.hrchat.semantic.service.SemanticMetaService;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 本地确定性问数编排引擎（AgentRuntimeClient 的 local 形态，无 LLM 依赖）。
 *
 * <p>主链路（架构红线：取数唯一入口=权限改写后执行）：</p>
 * <ol>
 *   <li>意图分类：问候词 → CHITCHAT；否则 QUERY</li>
 *   <li>语义检索（Schema Linking）：同义词最长匹配 + 多向量混合检索（RRF）</li>
 *   <li>SQL 模板拼装：基础指标公式注入组织/时间谓词；派生指标（A/B）递归展开为标量子查询</li>
 *   <li>权限改写：{@code {authz_org_filter}} 占位符替换为行级过滤片段（经 SqlRewriteService 单一入口）</li>
 *   <li>只读执行（QueryExecService 二次校验 + 超时）→ 渲染三段式 ANSWER_DONE</li>
 * </ol>
 *
 * <p>歧义问句（≥2 个指标候选）发 INTERRUPT(CLARIFY)，由澄清应答后重跑（BR-07）。</p>
 */
@Slf4j
public class LocalAgentRuntimeImpl implements AgentRuntimeClient {

    /** 演示数据最新同步时间（BR-09 数据时效标注，对齐 itg_sync_task 种子）。 */
    private static final String DATA_UPDATED_AT = "2026-09-28T06:00:00+08:00";

    private static final DateTimeFormatter SQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 问候词（意图 CHITCHAT）。 */
    private static final Set<String> GREETINGS = Set.of(
            "你好", "您好", "hello", "hi", "嗨", "谢谢", "感谢", "再见", "拜拜",
            "你是谁", "介绍一下", "你能做什么",
            "还能问什么", "可以问什么", "能问什么", "支持哪些", "有哪些指标", "你会什么");

    /** 每个指标的单位。 */
    private static final Map<String, String> METRIC_UNIT = Map.of(
            "headcount", "人", "hire_count", "人", "leave_count", "人",
            "payroll_total", "元", "avg_salary", "元");
    private static final Map<String, Integer> PERCENT_METRICS = Map.of(
            "turnover_rate", 1, "attendance_rate", 1);

    /** 缺期间澄清选项；续跑时作为 TimeRange.preset，不能当作指标 code。 */
    private static final Set<String> PERIOD_PRESETS = Set.of(
            "THIS_MONTH", "LAST_MONTH", "LAST_30D", "LAST_7D");

    private static final Pattern DIGIT_MONTH = Pattern.compile("(?<!\\d)(1[0-2]|0?[1-9])\\s*月份?");

    /** 长月名优先，避免「十一月」命中「一月」。 */
    private static final String[] CN_MONTHS = {
            "十一月", "十二月", "十月", "一月", "二月", "三月", "四月", "五月",
            "六月", "七月", "八月", "九月"
    };
    private static final int[] CN_MONTH_NUMBERS = {11, 12, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    private final HybridRetriever hybridRetriever;
    private final SemanticMetaService semanticMetaService;
    private final SqlRewriteService sqlRewriteService;
    private final QueryExecService queryExecService;
    private final BizSynonymMapper synonymMapper;
    private final ObjectMapper objectMapper;
    private final LocalDate demoNow;
    /** LLM 自然语言增强（答案润色/闲聊）；无有效模型配置时为 NOOP，全程降级模板。 */
    private final LlmChatClientProvider llmProvider;

    public LocalAgentRuntimeImpl(HybridRetriever hybridRetriever,
                                 SemanticMetaService semanticMetaService,
                                 SqlRewriteService sqlRewriteService,
                                 QueryExecService queryExecService,
                                 BizSynonymMapper synonymMapper,
                                 ObjectMapper objectMapper,
                                 LocalDate demoNow) {
        this(hybridRetriever, semanticMetaService, sqlRewriteService, queryExecService,
                synonymMapper, objectMapper, demoNow, LlmChatClientProvider.NOOP);
    }

    public LocalAgentRuntimeImpl(HybridRetriever hybridRetriever,
                                 SemanticMetaService semanticMetaService,
                                 SqlRewriteService sqlRewriteService,
                                 QueryExecService queryExecService,
                                 BizSynonymMapper synonymMapper,
                                 ObjectMapper objectMapper,
                                 LocalDate demoNow,
                                 LlmChatClientProvider llmProvider) {
        this.hybridRetriever = hybridRetriever;
        this.semanticMetaService = semanticMetaService;
        this.sqlRewriteService = sqlRewriteService;
        this.queryExecService = queryExecService;
        this.synonymMapper = synonymMapper;
        this.objectMapper = objectMapper;
        this.demoNow = demoNow;
        this.llmProvider = llmProvider == null ? LlmChatClientProvider.NOOP : llmProvider;
    }

    /** 当前租户的 LLM 客户端（无配置时为 noop，enabled()=false）。 */
    private LlmChatClient llm() {
        return llmProvider.forTenant(TenantContextHolder.get());
    }

    @Override
    public AgentResult ask(AskRequest request, UserContext ctx) {
        long start = System.currentTimeMillis();
        validate(request);
        String question = request.question().trim();
        String askId = "ask_" + UUID.randomUUID().toString().substring(0, 8);

        if (isChitchat(question)) {
            return chitchat(askId, question, start);
        }
        return runQuery(askId, question, ctx, null, request.contextOverride(), start);
    }

    @Override
    public AgentResult clarify(String askId, String question, ClarifyAnswerRequest.Answer answers, UserContext ctx) {
        if (answers == null || answers.optionIds() == null || answers.optionIds().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "answers[].option_ids");
        }
        long start = System.currentTimeMillis();
        String option = answers.optionIds().get(0);
        // 期间澄清选项带 TimeRange preset；指标澄清选项仍作为 forcedMetricCode
        if (PERIOD_PRESETS.contains(option)) {
            ContextOverride period = new ContextOverride(new TimeRange(option, null, null, null),
                    null, null, null);
            return runQuery(askId, question.trim(), ctx, null, period, start);
        }
        return runQuery(askId, question.trim(), ctx, option, null, start);
    }

    // =================================================================
    // 主编排链路
    // =================================================================

    private AgentResult runQuery(String askId, String question, UserContext ctx,
                                 String forcedMetricCode, ContextOverride contextOverride, long start) {
        List<SseEvent> events = new ArrayList<>();
        events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", "正在解析您的问句…", "phase", "PARSING")));
        List<BizSynonym> synonyms = synonymMapper.selectList(null);
        String refusal = LocalQueryScopeGuard.refusal(question, synonyms);
        if (refusal != null) {
            events.add(errorEvent(ErrorCode.QUERY_UNSUPPORTED.getCode(), refusal, false));
            return new AgentResult(askId, events, null, List.of(), null,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }
        events.add(toolStart("semantic_search", "正在检索指标语义…"));

        long searchStart = System.currentTimeMillis();
        Resolved resolved = resolve(question, ctx, synonyms);
        long searchMs = System.currentTimeMillis() - searchStart;
        events.add(toolEnd("semantic_search", searchMs, resolved.metricCodes().size()));

        String metricCode;
        MetricDetail aiPicked = null;
        if (forcedMetricCode != null) {
            metricCode = forcedMetricCode;
        } else if (resolved.metricCodes().isEmpty()) {
            // 规则/向量均未命中 → LLM 封闭指标清单兜底（只选已有 code，不生成 SQL；失败仍 HRA-4001）
            events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", "正在用 AI 理解您的说法…", "phase", "PARSING")));
            aiPicked = resolveMetricByLlm(question);
            if (aiPicked == null) {
                // 规则与 LLM 均未识别出指标 → 引导式澄清（缺指标槽位），而非直接 HRA-4001
                List<MetricDetail> candidates = topMetrics(5);
                if (candidates.isEmpty()) {
                    events.add(errorEvent(ErrorCode.INTENT_NOT_UNDERSTOOD.getCode(),
                            ErrorCode.INTENT_NOT_UNDERSTOOD.getMessageTemplate(), true));
                    return new AgentResult(askId, events, null, List.of(), null,
                            SseEvents.INTENT_QUERY, false, elapsed(start));
                }
                List<ClarifyQuestion> questions = List.of(new ClarifyQuestion(
                        askId + "-q1", "您想查询哪个指标？",
                        candidates.stream().map(d -> new ClarifyQuestion.Option(d.code(), d.name())).toList(),
                        false));
                events.add(new SseEvent(SseEvents.INTERRUPT,
                        metricClarifyPayload(askId, "您想查询哪个指标？", candidates)));
                return new AgentResult(askId, events, null, questions, null,
                        SseEvents.INTENT_QUERY, false, elapsed(start));
            }
            log.info("LLM 意图识别兜底命中: metric={}, question={}", aiPicked.code(), question);
            metricCode = aiPicked.code();
        } else if (resolved.metricCodes().size() > 1) {
            // 歧义澄清（BR-07）
            List<ClarifyQuestion> questions = List.of(new ClarifyQuestion(
                    askId + "-q1", "您指的是哪个指标？",
                    resolved.metricCodes().stream()
                            .map(c -> new ClarifyQuestion.Option(c, resolved.details().get(c).name()))
                            .toList(), false));
            events.add(new SseEvent(SseEvents.INTERRUPT, interruptPayload(askId, resolved)));
            return new AgentResult(askId, events, null, questions, null,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        } else {
            metricCode = resolved.metricCodes().iterator().next();
        }

        // 澄清续跑（forcedMetricCode）时原问句可能未命中同义词，details 内无该指标，需按 code 回查语义层
        MetricDetail metric = aiPicked;
        if (metric == null) {
            metric = resolved.details().get(metricCode);
        }
        if (metric == null) {
            metric = semanticMetaService.getMetricByCode(metricCode);
        }
        if (metric == null || metric.formulaExpr() == null || metric.formulaExpr().isBlank()) {
            events.add(errorEvent(ErrorCode.PARSE_FAILED.getCode(),
                    ErrorCode.PARSE_FAILED.format("指标无口径定义"), true));
            return new AgentResult(askId, events, null, List.of(), null,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }

        // 时间范围（问句关键词 > 显式覆盖）
        TimeWindow window = resolveWindow(question, contextOverride);
        if (window != null && (window.start().isAfter(demoNow)
                || window.end().isAfter(demoNow.plusDays(1))
                || !window.start().isBefore(window.end()))) {
            events.add(errorEvent(ErrorCode.PARAM_INVALID.getCode(),
                    "查询期间必须在演示时点内且起始日早于结束日", false));
            return new AgentResult(askId, events, null, List.of(), null,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }
        if (("headcount".equals(metricCode) || "turnover_rate".equals(metricCode)
                || "avg_salary".equals(metricCode)) && window != null
                && (window.end().minusDays(1).isBefore(HeadcountAsOf.MIN_DEMO_DATE)
                || (isTrendQuestion(question) && window.start().isBefore(HeadcountAsOf.MIN_DEMO_DATE)))) {
            events.add(errorEvent(ErrorCode.PARAM_INVALID.getCode(),
                    "演示在职历史仅覆盖 2026-01-01 起", false));
            return new AgentResult(askId, events, null, List.of(), null,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }
        if (window == null && ("hire_count".equals(metricCode) || "leave_count".equals(metricCode))) {
            String prompt = "请明确入职或离职人数的统计期间";
            List<ClarifyQuestion.Option> periodOptions = List.of(
                    new ClarifyQuestion.Option("THIS_MONTH", "本月"),
                    new ClarifyQuestion.Option("LAST_MONTH", "上月"),
                    new ClarifyQuestion.Option("LAST_30D", "近30天"));
            List<ClarifyQuestion> questions = List.of(new ClarifyQuestion(
                    askId + "-q1", prompt, periodOptions, false));
            events.add(new SseEvent(SseEvents.INTERRUPT, periodClarifyPayload(askId, prompt, periodOptions)));
            return new AgentResult(askId, events, null, questions, null,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }

        // 明细 / 按组织对比 / 趋势（演示追问 chips）
        if (isDetailQuestion(question)) {
            AgentResult detailResult = runDetail(askId, question, metric, metricCode,
                    window, ctx, resolved, events, start);
            if (detailResult != null) {
                return detailResult;
            }
        }
        if (isOrgCompareQuestion(question)) {
            AgentResult orgResult = runOrgCompare(askId, question, metric, metricCode,
                    window, ctx, resolved, events, start);
            if (orgResult != null) {
                return orgResult;
            }
        }

        // 趋势查询不能静默回退单值，否则回答的不是用户提出的问题。
        if (isTrendQuestion(question)) {
            AgentResult trendResult = runTrend(askId, question, metric, metricCode,
                    window, ctx, resolved, events, start);
            if (trendResult != null) {
                return trendResult;
            }
            events.add(errorEvent(ErrorCode.PARAM_INVALID.getCode(),
                    "该指标当前不支持月趋势，请改问单期数值", false));
            return new AgentResult(askId, events, null, List.of(), null,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }

        events.add(toolStart("sql_exec", "正在查询「" + metric.name() + "」…"));
        String sql = null;
        try {
            // 组织过滤片段：null=用用户全授权子树（占位符由改写服务注入）；越权组织抛 HRC-2003
            String orgFragment = orgFilterFragment(ctx, resolved.orgKeys(), resolved.orgTerm());
            long sqlStart = System.currentTimeMillis();
            sql = buildQuerySql(metric, window, orgFragment);
            // 取数唯一入口：权限改写（占位符替换 + 只读白名单校验）
            AuthorizedQuery authorizedQuery = sqlRewriteService.authorize(sql, ctx);
            sql = authorizedQuery.sql();
            QueryResult result = queryExecService.executeReadonly(authorizedQuery);
            long sqlMs = System.currentTimeMillis() - sqlStart;
            events.add(toolEnd("sql_exec", sqlMs, result.rows().size()));

            Number current = extractValue(result, metricCode);
            String prevPeriod = null;
            Number compare = null;
            if (window != null && current != null) {
                TimeWindow prev = window.prev();
                if (prev != null) {
                    // 取数唯一入口：环比查询同样必须经权限改写后执行（BR-02）
                    QueryResult prevResult = queryExecService.executeReadonly(
                            sqlRewriteService.authorize(buildQuerySql(metric, prev, orgFragment), ctx));
                    compare = extractValue(prevResult, metricCode);
                    prevPeriod = prev.label();
                }
            }

            // 结论摘要（SUMMARIZING delta）：LLM 可用时做一句式润色，失败/无配置走确定性模板
            String unit = unit(metric.code());
            String sentence = sentence(metric, current, compare, prevPeriod, unit);
            if (llm().enabled()) {
                events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", "正在生成智能解读…", "phase", "SUMMARIZING")));
                sentence = enhanceSentence(question, metric, current, compare, prevPeriod, unit, sentence);
            }
            events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", sentence, "phase", "SUMMARIZING")));

            AnswerPayload payload = buildPayload(askId, metric, current, compare, prevPeriod, window, result, elapsed(start));
            events.add(new SseEvent(SseEvents.ANSWER_DONE, objectMapper.convertValue(payload, Map.class)));
            return new AgentResult(askId, events, payload, List.of(), sql,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        } catch (BizException e) {
            // BR-02：越权组织记审计日志；数据软错误（200）可重试，权限错误（403）不可重试
            boolean recoverable = e.getErrorCode().getHttpStatus() == 200;
            log.warn("问数链路业务错误: code={}, msg={}", e.getErrorCode().getCode(), e.getMessageText());
            events.add(errorEvent(e.getErrorCode().getCode(), e.getMessageText(), recoverable));
            return new AgentResult(askId, events, null, List.of(), sql,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }
    }

    /** INTERRUPT(CLARIFY) 载荷：≤5 选项。 */
    private Map<String, Object> interruptPayload(String askId, Resolved resolved) {
        return metricClarifyPayload(askId, "您指的是哪个指标？",
                resolved.metricCodes().stream().map(resolved.details()::get).toList());
    }

    /** 指标澄清载荷：候选项为语义对象（code → 名称），用户选择后以 code 作为 forcedMetricCode 续跑。 */
    private Map<String, Object> metricClarifyPayload(String askId, String prompt, List<MetricDetail> metrics) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (MetricDetail d : metrics) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("option_id", d.code());
            o.put("label", d.name());
            options.add(o);
        }
        return clarifyPayload(askId, prompt, options);
    }

    /** 期间澄清载荷：option_id 为 TimeRange preset。 */
    private Map<String, Object> periodClarifyPayload(String askId, String prompt,
                                                     List<ClarifyQuestion.Option> periodOptions) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (ClarifyQuestion.Option opt : periodOptions) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("option_id", opt.optionId());
            o.put("label", opt.label());
            options.add(o);
        }
        return clarifyPayload(askId, prompt, options);
    }

    private Map<String, Object> clarifyPayload(String askId, String prompt, List<Map<String, Object>> options) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("question_id", askId + "-q1");
        question.put("question", prompt);
        question.put("multiple", false);
        question.put("options", options);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interrupt_type", "CLARIFY");
        payload.put("ask_id", askId);
        payload.put("questions", List.of(question));
        return payload;
    }

    // =================================================================
    // 意图 / 语义检索
    // =================================================================

    /** 语义解析结果。 */
    private record Resolved(Set<String> metricCodes, Map<String, MetricDetail> details,
                            Set<Long> orgKeys, String orgTerm) {
    }

    private boolean isChitchat(String question) {
        String lower = question.toLowerCase();
        return GREETINGS.stream().anyMatch(lower::contains);
    }

    /** 趋势/时序分析意图：要求按月折线呈现（无显式时间范围时默认近三月）。 */
    private boolean isTrendQuestion(String question) {
        return question != null && (question.contains("趋势") || question.contains("走势")
                || question.contains("按月") || question.contains("每月") || question.contains("月度"));
    }

    private boolean isDetailQuestion(String question) {
        return question != null && question.contains("明细");
    }

    private boolean isOrgCompareQuestion(String question) {
        return question != null && (question.contains("按组织对比") || question.contains("按部门对比")
                || (question.contains("组织") && question.contains("对比")));
    }

    /**
     * 解析问句：同义词最长匹配（指标/组织）+ 多向量混合检索兜底。
     */
    private Resolved resolve(String question, UserContext ctx, List<BizSynonym> synonyms) {
        // 最长匹配（指标/组织各自独立）：仅保留长度最大的命中同义词组，避免「离职率」同时命中「离职」，
        // 且避免指标词「在职人数」压过组织词「销售部」导致组织过滤失效
        int metricMaxLen = -1;
        int orgMaxLen = -1;
        Set<Long> metricIds = new LinkedHashSet<>();
        Set<Long> orgKeys = new LinkedHashSet<>();
        String orgTerm = null;
        for (BizSynonym s : synonyms) {
            if (s.getStatus() == null || s.getStatus() != 1 || s.getTermGroup() == null) {
                continue;
            }
            if (!question.contains(s.getTermGroup())) {
                continue;
            }
            int len = s.getTermGroup().length();
            if (s.getTargetType() != null && s.getTargetType() == 1 && s.getTargetId() != null) {
                if (len > metricMaxLen) {
                    metricMaxLen = len;
                    metricIds.clear();
                }
                if (len == metricMaxLen) {
                    metricIds.add(s.getTargetId());
                }
            } else if (s.getTargetType() != null && s.getTargetType() == 2 && s.getTargetId() != null) {
                if (len > orgMaxLen) {
                    orgMaxLen = len;
                    orgKeys.clear();
                    orgTerm = null;
                }
                if (len == orgMaxLen) {
                    orgKeys.add(s.getTargetId());
                    if (orgTerm == null) {
                        orgTerm = s.getTermGroup();
                    }
                }
            }
        }

        // 同义词命中的指标 id → code
        Map<String, MetricDetail> details = new LinkedHashMap<>();
        for (Long id : metricIds) {
            MetricDetail d = semanticMetaService.getMetric(id);
            if (d != null && d.code() != null) {
                details.put(d.code(), d);
            }
        }

        // 混合检索兜底（无同义词命中时补召回）
        if (details.size() <= 1) {
            List<ScoredDoc> docs = hybridRetriever.hybridSearch(question,
                    SearchFilter.of(null, "metric", "published", null), 20, 20, 5);
            for (ScoredDoc sd : docs) {
                String code = sd.doc().code();
                if (code != null && !details.containsKey(code)) {
                    MetricDetail d = semanticMetaService.getMetricByCode(code);
                    if (d != null) {
                        details.put(code, d);
                    }
                }
            }
        }
        return new Resolved(new LinkedHashSet<>(details.keySet()), details, orgKeys, orgTerm);
    }

    /** 当前全部「已发布且有口径」的指标（顺序同语义层列表），供 LLM 选择与澄清候选使用。 */
    private List<MetricDetail> listEffectiveMetrics() {
        PageResult<MetricSummary> page = semanticMetaService.listMetrics(null, 1, null, 1, 200);
        if (page == null || page.getRecords() == null) {
            return List.of();
        }
        List<MetricDetail> metrics = new ArrayList<>();
        for (MetricSummary s : page.getRecords()) {
            MetricDetail d = semanticMetaService.getMetricByCode(s.code());
            if (d != null && d.formulaExpr() != null && !d.formulaExpr().isBlank()) {
                metrics.add(d);
            }
        }
        return metrics;
    }

    /** 缺指标槽位时的引导澄清候选：取前 n 个已发布指标。 */
    private List<MetricDetail> topMetrics(int n) {
        List<MetricDetail> all = listEffectiveMetrics();
        return all.size() <= n ? all : all.subList(0, n);
    }

    /**
     * LLM 意图识别兜底：规则与向量均未命中时，让模型在「生效指标封闭清单」中选择一个指标 code。
     * 安全约束：模型只能选清单内已有 code（不生成 SQL、不自由发挥），任何异常或越界输出返回 null（降级 HRA-4001）。
     */
    private MetricDetail resolveMetricByLlm(String question) {
        LlmChatClient client = llm();
        if (!client.enabled()) {
            return null;
        }
        List<MetricDetail> metrics = listEffectiveMetrics();
        if (metrics.isEmpty()) {
            return null;
        }
        Map<String, MetricDetail> valid = new LinkedHashMap<>();
        StringBuilder catalog = new StringBuilder();
        for (MetricDetail d : metrics) {
            valid.put(d.code(), d);
            catalog.append("- ").append(d.code()).append("：").append(d.name());
            if (d.domain() != null && !d.domain().isBlank()) {
                catalog.append("（领域：").append(d.domain()).append("）");
            }
            catalog.append('\n');
        }
        try {
            String ai = client.chat(
                    "你是企业 HR 问数系统的意图识别器。用户的问题必须映射到下面清单中的某一个指标。"
                            + "你只能输出该指标的 code（英文标识符），禁止输出任何解释、标点或其他文字；"
                            + "若清单中确实没有相关指标，输出 UNKNOWN。\n可用指标清单：\n" + catalog,
                    "用户问题：" + question);
            String code = ai == null ? "" : ai.trim().replaceAll("[`*\"'\\s]", "");
            MetricDetail picked = valid.get(code);
            if (picked == null && ai != null) {
                // 容错：模型夹带多余文字时，匹配清单中出现的 code 子串
                for (Map.Entry<String, MetricDetail> e : valid.entrySet()) {
                    if (ai.contains(e.getKey())) {
                        picked = e.getValue();
                        break;
                    }
                }
            }
            return picked;
        } catch (LlmCallException e) {
            log.warn("LLM 意图识别兜底失败，降级 HRA-4001: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 组织行级过滤：无组织提及 → null（改写服务注入全授权子树）；
     * 有组织提及 → 校验在授权范围内后返回片段；越权抛 HRC-2003。
     */
    private String orgFilterFragment(UserContext ctx, Set<Long> requestedOrgKeys, String orgTerm) {
        if (requestedOrgKeys.isEmpty()) {
            return null;
        }
        boolean rootGrant = ctx.getGrantedOrgs().stream()
                .anyMatch(g -> g.getSubtreeOrgKeys().contains(1L));
        if (rootGrant) {
            return "1=1";
        }
        Set<Long> covering = new LinkedHashSet<>();
        for (UserContext.GrantedOrg g : ctx.getGrantedOrgs()) {
            if (requestedOrgKeys.contains(g.getOrgNodeId())) {
                covering.addAll(g.getSubtreeOrgKeys());
            } else {
                for (Long req : requestedOrgKeys) {
                    if (g.getSubtreeOrgKeys().contains(req)) {
                        covering.add(req);
                    }
                }
            }
        }
        if (covering.isEmpty()) {
            throw new BizException(ErrorCode.DATA_RANGE_FORBIDDEN, orgTerm == null ? "该组织" : orgTerm);
        }
        return "org_key IN (" + covering.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")";
    }

    // =================================================================
    // SQL 模板拼装 / 权限改写 / 执行
    // =================================================================

    /**
     * 构建可执行 SQL：统一 {@code SELECT (<表达式>) AS "<code>"} 标量子查询形态，
     * 组织谓词以 {@code {authz_org_filter}} 占位（或 orgFragment 直代），随后经改写服务统一校验替换。
     */
    String buildQuerySql(MetricDetail metric, TimeWindow window, String orgFragment) {
        String expr = metric.formulaExpr().trim();
        Set<String> visited = new HashSet<>();
        visited.add(metric.code());
        String expression = expr.toUpperCase().startsWith("SELECT")
                ? injectFilters(expr, window, orgFragment, metric.code())
                : buildExpression(expr, window, visited, orgFragment);
        return "SELECT (" + expression + ") AS \"" + metric.code() + "\"";
    }

    /** 派生指标表达式递归展开：{@code leave_count / headcount} → {@code (sub) / (sub)}。 */
    private String buildExpression(String expr, TimeWindow window, Set<String> visited, String orgFragment) {
        List<String> tokens = java.util.Arrays.stream(expr.split("\\s+"))
                .filter(t -> !t.isBlank())
                .toList();
        // 比率指标 a / b：转 DECIMAL 浮点除法 + NULLIF 防除零，避免 H2 整数除法 1/18=0
        if (tokens.size() == 3 && "/".equals(tokens.get(1))) {
            String numerator = expandMetricToken(tokens.get(0), window, visited, orgFragment);
            String denominator = expandMetricToken(tokens.get(2), window, visited, orgFragment);
            return "(CAST((" + numerator + ") AS DECIMAL(18,6)) / NULLIF((" + denominator + "), 0))";
        }
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (isOperator(token)) {
                sb.append(' ').append(token).append(' ');
                continue;
            }
            sb.append('(').append(expandMetricToken(token, window, visited, orgFragment)).append(')');
        }
        return sb.toString();
    }

    /** 展开单个指标引用：基础指标注入过滤条件；派生指标递归展开。 */
    private String expandMetricToken(String token, TimeWindow window, Set<String> visited, String orgFragment) {
        if (!visited.add(token)) {
            throw new BizException(ErrorCode.PARSE_FAILED, "指标循环引用：" + token);
        }
        MetricDetail dep = semanticMetaService.getMetricByCode(token);
        if (dep == null || dep.formulaExpr() == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "未知指标引用：" + token);
        }
        String sub = dep.formulaExpr().trim();
        return sub.toUpperCase().startsWith("SELECT")
                ? injectFilters(sub, window, orgFragment, token)
                : buildExpression(sub, window, visited, orgFragment);
    }

    /** 向基础 SQL 注入组织/时间谓词（追加于 WHERE 之后；无 WHERE 时新起 WHERE）。 */
    private String injectFilters(String baseSql, TimeWindow window, String orgFragment, String metricCode) {
        List<String> predicates = new ArrayList<>();
        predicates.add(authzPredicate(orgFragment));
        if ("headcount".equals(metricCode)) {
            predicates.add(HeadcountAsOf.predicate(HeadcountAsOf.date(
                    window == null ? null : window.end(), demoNow)));
        }
        if (window != null && baseSql.toUpperCase().contains("FACT_")) {
            predicates.add(window.sqlPredicate(baseSql.toLowerCase(java.util.Locale.ROOT)
                    .contains("fact_emp_change") ? "change_date" : "dt"));
        }
        String suffix = String.join(" AND ", predicates);
        int whereIdx = baseSql.toUpperCase().indexOf("WHERE");
        return whereIdx >= 0 ? baseSql + " AND " + suffix : baseSql + " WHERE " + suffix;
    }

    private static boolean isOperator(String token) {
        return "/".equals(token) || "*".equals(token) || "+".equals(token) || "-".equals(token);
    }

    // -----------------------------------------------------------------
    // 趋势（按月时序）SQL：把基础 SELECT 公式改写为 GROUP BY month
    // -----------------------------------------------------------------

    /** 基础 SELECT 公式的结构化片段（语义层公式受控，形如 {@code SELECT agg FROM tbl [WHERE cond]}）。 */
    private record BaseParts(String aggExpr, String table, String where) {
        boolean isFact() {
            return table.toLowerCase(java.util.Locale.ROOT).startsWith("fact_");
        }
    }

    private static final java.util.regex.Pattern BASE_SELECT_PATTERN = java.util.regex.Pattern.compile(
            "^\\s*SELECT\\s+(.+?)\\s+FROM\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:WHERE\\s+(.+?))?\\s*;?\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    private BaseParts parseBase(String formula) {
        if (formula == null) {
            return null;
        }
        java.util.regex.Matcher m = BASE_SELECT_PATTERN.matcher(formula.trim());
        if (!m.matches()) {
            return null;
        }
        return new BaseParts(m.group(1).trim(), m.group(2).trim(),
                m.group(3) == null ? null : m.group(3).trim());
    }

    /** 指标公式为基础 SELECT 时返回其片段，派生公式返回 null。 */
    private BaseParts basePartsOf(MetricDetail m) {
        String f = m.formulaExpr();
        if (f == null || !f.trim().toUpperCase().startsWith("SELECT")) {
            return null;
        }
        return parseBase(f);
    }

    private String authzPredicate(String orgFragment) {
        return orgFragment != null
                ? "(" + orgFragment + ") AND " + SqlRewriteService.TENANT_FILTER_PLACEHOLDER
                : SqlRewriteService.ORG_FILTER_PLACEHOLDER;
    }

    /** 事实表按月聚合子查询：输出 {@code period(yyyy-MM), v}，组织/时间谓词在组内注入。 */
    private String monthlyFactSub(BaseParts p, TimeWindow window, String orgFragment) {
        List<String> cond = new ArrayList<>();
        if (p.where() != null && !p.where().isBlank()) {
            cond.add("(" + p.where() + ")");
        }
        cond.add(authzPredicate(orgFragment));
        String dateColumn = "fact_emp_change".equalsIgnoreCase(p.table()) ? "change_date" : "dt";
        cond.add(window.sqlPredicate(dateColumn));
        String periodExpr = "FORMATDATETIME(" + dateColumn + ", 'yyyy-MM')";
        return "SELECT " + periodExpr + " AS period, " + p.aggExpr() + " AS v "
                + "FROM " + p.table() + " WHERE " + String.join(" AND ", cond)
                + " GROUP BY " + periodExpr;
    }

    /** 快照/整体标量子查询（注入组织、不注入时间），用于比率指标分母无月度历史时取恒定值。 */
    private String scalarSub(BaseParts p, String orgFragment) {
        List<String> cond = new ArrayList<>();
        if (p.where() != null && !p.where().isBlank()) {
            cond.add("(" + p.where() + ")");
        }
        cond.add(authzPredicate(orgFragment));
        return "(SELECT " + p.aggExpr() + " FROM " + p.table()
                + " WHERE " + String.join(" AND ", cond) + ")";
    }

    /**
     * 构建按月趋势 SQL（输出 {@code period, "<code>"}，按 period 升序）；指标无法按月聚合时返回 null。
     * 支持：事实表基础指标；派生比率 {@code a/b}（分子按事实表月份；分母为事实表则按月相关匹配，
     * 为快照表则取恒定分母标量）。返回值为原始量纲——百分比为小数，由展示层统一 ×100。
     */
    String buildTrendSql(MetricDetail metric, TimeWindow window, String orgFragment) {
        if (window.start().isAfter(demoNow)) {
            return null;
        }
        String expr = metric.formulaExpr().trim();
        if (expr.toUpperCase().startsWith("SELECT")) {
            BaseParts p = parseBase(expr);
            if (p == null) {
                return null;
            }
            if (p.isFact()) {
                return wrapTrend(metric, monthlyFactSub(p, window, orgFragment));
            }
            // 快照在职：按入职/离职日还原各月末时点人数，支撑「近三月趋势」折线
            if ("dim_employee".equalsIgnoreCase(p.table()) && "headcount".equals(metric.code())) {
                return buildHeadcountSnapshotTrendSql(metric, window, orgFragment);
            }
            return null;
        }
        // 派生比率 a / b
        List<String> tokens = java.util.Arrays.stream(expr.split("\\s+"))
                .filter(t -> !t.isBlank()).toList();
        if (tokens.size() != 3 || !"/".equals(tokens.get(1))) {
            return null;
        }
        MetricDetail numMetric = semanticMetaService.getMetricByCode(tokens.get(0));
        MetricDetail denMetric = semanticMetaService.getMetricByCode(tokens.get(2));
        BaseParts numParts = numMetric == null ? null : basePartsOf(numMetric);
        BaseParts denParts = denMetric == null ? null : basePartsOf(denMetric);
        if (numParts == null || !numParts.isFact()) {
            return null; // 分子必须可按月聚合
        }
        String denomExpr;
        if (denParts != null && denParts.isFact()) {
            denomExpr = "(SELECT d.v FROM (" + monthlyFactSub(denParts, window, orgFragment)
                    + ") d WHERE d.period = n.period)";
        } else if (denParts != null && "headcount".equals(tokens.get(2))) {
            denomExpr = "(SELECT d.v FROM (" + headcountMonthlySub(window, orgFragment)
                    + ") d WHERE d.period = n.period)";
        } else if (denParts != null) {
            denomExpr = scalarSub(denParts, orgFragment);
        } else {
            return null; // 分母为多层派生，暂不支持趋势
        }
        String inner = "SELECT n.period AS period, (CAST(n.v AS DECIMAL(18,6)) / NULLIF(" + denomExpr
                + ", 0)) AS v FROM (" + monthlyFactSub(numParts, window, orgFragment) + ") n";
        return wrapTrend(metric, inner);
    }

    /** 时序统一外包裹：输出 {@code period, "<code>"}（量纲不转换，按 period 升序）。 */
    private String wrapTrend(MetricDetail metric, String inner) {
        return "SELECT period, v AS \"" + metric.code() + "\" FROM (" + inner
                + ") t ORDER BY period";
    }

    /**
     * 在职人数月末时点趋势：用 hire_date / leave_date 还原历史，不依赖 emp_status 快照。
     */
    private String buildHeadcountSnapshotTrendSql(MetricDetail metric, TimeWindow window, String orgFragment) {
        return wrapTrend(metric, headcountMonthlySub(window, orgFragment));
    }

    private String headcountMonthlySub(TimeWindow window, String orgFragment) {
        List<String> unions = new ArrayList<>();
        LocalDate cursor = window.start().withDayOfMonth(1);
        LocalDate last = HeadcountAsOf.date(window.end(), demoNow);
        while (!cursor.isAfter(last)) {
            LocalDate monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth());
            String period = monthEnd.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            String asOf = HeadcountAsOf.date(
                    monthEnd.isBefore(window.end()) ? monthEnd.plusDays(1) : window.end(), demoNow)
                    .format(SQL_DATE);
            unions.add("SELECT '" + period + "' AS period, COUNT(DISTINCT emp_key) AS v FROM dim_employee WHERE "
                    + "hire_date <= DATE '" + asOf + "' "
                    + "AND (leave_date IS NULL OR leave_date > DATE '" + asOf + "') "
                    + "AND " + authzPredicate(orgFragment));
            cursor = cursor.plusMonths(1);
        }
        return String.join(" UNION ALL ", unions);
    }

    private static Number extractValue(QueryResult result, String code) {
        if (result.rows() == null || result.rows().isEmpty()) {
            return null;
        }
        Object v = result.rows().get(0).get(code);
        return v instanceof Number n ? n : null;
    }

    // =================================================================
    // 时间范围
    // =================================================================

    /** 时间窗口：左闭右开 [start, end)。 */
    private record TimeWindow(LocalDate start, LocalDate end) {

        String sqlPredicate() {
            return sqlPredicate("dt");
        }

        String sqlPredicate(String column) {
            return column + " >= '" + start.format(SQL_DATE) + "' AND " + column + " < '" + end.format(SQL_DATE) + "'";
        }

        String label() {
            return start.format(SQL_DATE) + "/" + end.minusDays(1).format(SQL_DATE);
        }

        TimeWindow prev() {
            long days = ChronoUnit.DAYS.between(start, end);
            return new TimeWindow(start.minusDays(days), end.minusDays(days));
        }
    }

    private TimeWindow resolveWindow(String question, ContextOverride co) {
        if (co != null && co.timeRange() != null && co.timeRange().preset() != null) {
            return mapPreset(co.timeRange().preset(), co.timeRange());
        }
        // 「上个月」不含连续子串「上月」，须先匹配
        if (question.contains("上个月") || question.contains("上一个月")) {
            return mapPreset("LAST_MONTH", null);
        }
        if (question.contains("上月")) {
            return mapPreset("LAST_MONTH", null);
        }
        if (question.contains("本月") || question.contains("这个月")) {
            return mapPreset("THIS_MONTH", null);
        }
        if (question.contains("上季度")) {
            return mapPreset("LAST_QUARTER", null);
        }
        if (question.contains("今年")) {
            return mapPreset("THIS_YEAR", null);
        }
        if (question.contains("去年")) {
            return mapPreset("LAST_YEAR", null);
        }
        if (question.contains("近7天") || question.contains("最近7天")) {
            return mapPreset("LAST_7D", null);
        }
        if (question.contains("近30天") || question.contains("最近30天")) {
            return mapPreset("LAST_30D", null);
        }
        LocalDate now = demoNow;
        if (question.contains("近三月") || question.contains("近3个月") || question.contains("近三个月")
                || question.contains("最近三月") || question.contains("最近3个月") || question.contains("最近三个月")) {
            return new TimeWindow(now.withDayOfMonth(1).minusMonths(2), now.plusDays(1));
        }
        if (question.contains("近半年") || question.contains("近6个月") || question.contains("近六个月")
                || question.contains("最近半年") || question.contains("最近6个月")) {
            return new TimeWindow(now.withDayOfMonth(1).minusMonths(5), now.plusDays(1));
        }
        if (question.contains("近一年") || question.contains("近12个月")
                || question.contains("最近一年") || question.contains("最近12个月")) {
            return new TimeWindow(now.withDayOfMonth(1).minusMonths(11), now.plusDays(1));
        }
        TimeWindow calendarMonth = resolveCalendarMonth(question);
        if (calendarMonth != null) {
            return calendarMonth;
        }
        return null;
    }

    /** 演示年内的日历月：7月 / 07月份 / 七月（相对 demoNow 的年份）。 */
    private TimeWindow resolveCalendarMonth(String question) {
        Matcher digit = DIGIT_MONTH.matcher(question);
        if (digit.find()) {
            int month = Integer.parseInt(digit.group(1));
            LocalDate start = LocalDate.of(demoNow.getYear(), month, 1);
            return new TimeWindow(start, start.plusMonths(1));
        }
        for (int i = 0; i < CN_MONTHS.length; i++) {
            String name = CN_MONTHS[i];
            int idx = question.indexOf(name);
            if (idx < 0) {
                continue;
            }
            // 跳过「近一月」「本七月」等相对说法中的假阳性
            if (idx > 0) {
                char prev = question.charAt(idx - 1);
                if ("近去今本上下".indexOf(prev) >= 0) {
                    continue;
                }
            }
            if (question.regionMatches(idx, name + "份", 0, name.length() + 1)
                    || question.regionMatches(idx, name, 0, name.length())) {
                LocalDate start = LocalDate.of(demoNow.getYear(), CN_MONTH_NUMBERS[i], 1);
                return new TimeWindow(start, start.plusMonths(1));
            }
        }
        return null;
    }

    private TimeWindow mapPreset(String preset, TimeRange custom) {
        LocalDate now = demoNow;
        return switch (preset) {
            case "LAST_7D" -> new TimeWindow(now.minusDays(6), now.plusDays(1));
            case "LAST_30D" -> new TimeWindow(now.minusDays(29), now.plusDays(1));
            case "THIS_MONTH" -> new TimeWindow(now.withDayOfMonth(1), now.plusDays(1));
            case "LAST_MONTH" -> new TimeWindow(now.withDayOfMonth(1).minusMonths(1), now.withDayOfMonth(1));
            case "THIS_QUARTER" -> new TimeWindow(
                    now.withMonth(now.getMonth().firstMonthOfQuarter().getValue()).withDayOfMonth(1),
                    now.plusDays(1));
            case "LAST_QUARTER" -> new TimeWindow(
                    now.withMonth(now.getMonth().firstMonthOfQuarter().getValue()).withDayOfMonth(1).minusMonths(3),
                    now.withMonth(now.getMonth().firstMonthOfQuarter().getValue()).withDayOfMonth(1));
            case "THIS_YEAR" -> new TimeWindow(now.withDayOfYear(1), now.plusDays(1));
            case "LAST_YEAR" -> new TimeWindow(now.withDayOfYear(1).minusYears(1), now.withDayOfYear(1));
            case "CUSTOM" -> custom != null && custom.start() != null && custom.end() != null
                    ? new TimeWindow(LocalDate.parse(custom.start().substring(0, 10)),
                    LocalDate.parse(custom.end().substring(0, 10)))
                    : null;
            default -> null;
        };
    }

    // =================================================================
    // 呈现（三段式 ANSWER_DONE）
    // =================================================================

    private AgentResult chitchat(String askId, String question, long start) {
        List<SseEvent> events = new ArrayList<>();
        events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", "正在生成回复…", "phase", "SUMMARIZING")));
        String reply = "您好，我是 HR 智能问数助手，可以帮您查询在职人数、入职/离职、离职率、人力成本、平均薪酬等数据。请问您想了解什么？";
        LlmChatClient client = llm();
        if (client.enabled()) {
            try {
                String ai = client.chat(
                        "你是企业内部的 HR 智能问数助手，用简洁友好的中文回答（不超过 60 字）。"
                                + "只能介绍你能查询的 HR 数据（在职人数、入职/离职、离职率、人力成本、平均薪酬等），"
                                + "不要编造数据，不要回答与 HR 数据查询无关的问题。",
                        "用户说：" + question);
                if (validNl(ai, 80)) {
                    reply = ai;
                }
            } catch (LlmCallException e) {
                log.warn("LLM 闲聊增强失败，降级固定文案: {}", e.getMessage());
            }
        }
        AnswerPayload payload = new AnswerPayload(askId, "ans_" + UUID.randomUUID().toString().substring(0, 8),
                SseEvents.ASK_COMPLETED, SseEvents.INTENT_CHITCHAT, false, null,
                new AnswerPayload.Conclusion("TEXT", reply, null, null),
                new AnswerPayload.TableData(List.of(), List.of(), 0, 1, 1), null, null,
                List.of("研发中心在职人数", "上月入职了多少人？", "离职率是多少？"), elapsed(start));
        events.add(new SseEvent(SseEvents.ANSWER_DONE, objectMapper.convertValue(payload, Map.class)));
        return new AgentResult(askId, events, payload, List.of(), null,
                SseEvents.INTENT_CHITCHAT, false, elapsed(start));
    }

    /** 结论摘要句子（SUMMARIZING delta 文案）。 */
    private String sentence(MetricDetail metric, Number current, Number compare, String prevPeriod, String unit) {
        if (current == null) {
            return "暂未查询到相关数据，建议调整组织范围或时间后重试。";
        }
        boolean percent = PERCENT_METRICS.containsKey(metric.code());
        int scale = percent ? 2 : 0;
        BigDecimal value = toDecimal(current);
        String display = display(value, percent, scale) + (percent ? "%" : unit.isEmpty() ? "" : " " + unit);
        String text = switch (metric.code()) {
            case "headcount" -> "在职人数为 " + display;
            case "hire_count" -> "期内入职 " + display;
            case "leave_count" -> "期内离职 " + display;
            default -> metric.name() + "为 " + display;
        };
        if (compare != null && prevPeriod != null) {
            BigDecimal prev = toDecimal(compare);
            int cmp = value.compareTo(prev);
            String trendWord = cmp > 0 ? "上升" : cmp < 0 ? "下降" : "持平";
            text += "，环比" + prevPeriod + "（" + display(prev, percent, scale)
                    + (percent ? "%" : unit.isEmpty() ? "" : " " + unit) + "）" + trendWord;
        }
        return text;
    }

    /**
     * LLM 答案润色：基于已查出的确定性数据生成一句中文解读；任何异常/不可用/不合规输出均返回模板原文。
     */
    private String enhanceSentence(String question, MetricDetail metric, Number current,
                                   Number compare, String prevPeriod, String unit, String fallback) {
        LlmChatClient client = llm();
        if (!client.enabled() || current == null) {
            return fallback;
        }
        boolean percent = PERCENT_METRICS.containsKey(metric.code());
        int scale = percent ? 2 : 0;
        BigDecimal value = toDecimal(current);
        String display = display(value, percent, scale) + (percent ? "%" : unit.isEmpty() ? "" : " " + unit);
        StringBuilder data = new StringBuilder()
                .append("指标：").append(metric.name()).append('\n')
                .append("当前值：").append(display).append('\n');
        if (compare != null && prevPeriod != null) {
            data.append("对比周期：").append(prevPeriod).append("，对比值：")
                    .append(display(toDecimal(compare), percent, scale))
                    .append(percent ? "%" : unit.isEmpty() ? "" : " " + unit).append('\n');
        }
        try {
            String ai = client.chat(
                    "你是企业 HR 数据分析助手。用户的数据已由系统查出，你只能用给定数据把结论改写成一句自然、专业、简洁的中文"
                            + "（不超过 60 字，不要分点，不要使用 markdown）。严禁编造或推算任何未提供的数字；"
                            + "若提供了对比周期，用'环比上升/下降/持平'表述。直接输出这句话本身。",
                    "用户问题：" + question + "\n" + data);
            return validNl(ai, 80) ? ai : fallback;
        } catch (LlmCallException e) {
            log.warn("LLM 答案润色失败，降级模板文案: {}", e.getMessage());
            return fallback;
        }
    }

    /** LLM 输出合规性：非空、单行、长度受限，避免幻觉长文/markdown 污染结论。 */
    private boolean validNl(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        if (t.length() > maxLen || t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) {
            return false;
        }
        return !t.contains("```");
    }

    private AnswerPayload buildPayload(String askId, MetricDetail metric, Number current,
                                       Number compare, String prevPeriod, TimeWindow window,
                                       QueryResult result, long elapsedMs) {
        boolean percent = PERCENT_METRICS.containsKey(metric.code());
        int scale = percent ? 2 : 0;
        String unit = unit(metric.code());

        String direction = "FLAT";
        if (compare != null && current != null) {
            int cmp = toDecimal(current).compareTo(toDecimal(compare));
            direction = cmp > 0 ? "UP" : cmp < 0 ? "DOWN" : "FLAT";
        }

        AnswerPayload.Conclusion conclusion = new AnswerPayload.Conclusion(
                current == null ? "TEXT" : "NUMBER_CARD",
                current == null ? "暂无相关数据" : display(toDecimal(current), percent, scale),
                percent ? "%" : unit,
                compare != null && prevPeriod != null
                        ? new AnswerPayload.Compare(prevPeriod, display(toDecimal(compare), percent, scale), direction)
                        : null);

        // 明细与结论同量纲：比率指标用百分数，避免表里出现 0.0555… 而卡上是 5.56%
        List<Map<String, Object>> rows = current == null ? List.of()
                : List.of(Map.of(metric.code(), display(toDecimal(current), percent, scale)));
        AnswerPayload.TableData table = new AnswerPayload.TableData(
                List.of(new AnswerPayload.Column(metric.code(), metric.name(), "number", false)),
                rows, rows.size(), 1, 1);

        Map<String, Object> chartConfig = new HashMap<>();
        if (current != null) {
            chartConfig.put("value", display(toDecimal(current), percent, scale));
            chartConfig.put("unit", percent ? "%" : unit);
            if (compare != null) {
                chartConfig.put("delta", direction);
            }
        }
        AnswerPayload.Chart chart = current == null ? null
                : new AnswerPayload.Chart("NUMBER_CARD", true, chartConfig);

        AnswerPayload.Caliber caliber = toCaliber(metric, window == null ? null : window.label());

        return new AnswerPayload(askId, "ans_" + UUID.randomUUID().toString().substring(0, 8),
                SseEvents.ASK_COMPLETED, SseEvents.INTENT_QUERY, false, null,
                conclusion, table, chart, caliber,
                List.of("查看" + metric.name() + "明细",
                        "按组织对比" + metric.name(),
                        "查看" + metric.name() + "近三月趋势"), elapsedMs);
    }

    /** 口径：展示名 + 语义 code（存报表 def.metric 必须用 code）。 */
    private AnswerPayload.Caliber toCaliber(MetricDetail metric, String timeRange) {
        String effectiveRange = timeRange == null && "headcount".equals(metric.code())
                ? "截至 " + demoNow : timeRange;
        return new AnswerPayload.Caliber(metric.name(), metric.calcScope(), effectiveRange,
                DATA_UPDATED_AT, metric.code());
    }

    /**
     * 明细：人员花名册或事实表近期行（演示用，上限 50）。
     */
    private AgentResult runDetail(String askId, String question, MetricDetail metric, String metricCode,
                                  TimeWindow window, UserContext ctx, Resolved resolved,
                                  List<SseEvent> events, long start) {
        String orgFragment = orgFilterFragment(ctx, resolved.orgKeys(), resolved.orgTerm());
        String detailSql = buildDetailSql(metric, window, orgFragment);
        if (detailSql == null) {
            return null;
        }
        events.add(toolStart("sql_exec", "正在查询「" + metric.name() + "」明细…"));
        String sql = null;
        try {
            long sqlStart = System.currentTimeMillis();
            AuthorizedQuery authorized = sqlRewriteService.authorize(detailSql, ctx);
            sql = authorized.sql();
            QueryResult result = queryExecService.executeReadonly(authorized);
            events.add(toolEnd("sql_exec", System.currentTimeMillis() - sqlStart, result.rows().size()));

            List<AnswerPayload.Column> columns = new ArrayList<>();
            for (QueryResult.ColumnMeta c : result.columns()) {
                columns.add(new AnswerPayload.Column(c.key(), c.name() == null ? c.key() : c.name(),
                        c.type() == null ? "string" : c.type(), false));
            }
            List<Map<String, Object>> rows = new ArrayList<>(result.rows());
            String tip = rows.isEmpty()
                    ? "「" + metric.name() + "」暂无明细行。"
                    : "「" + metric.name() + "」明细共 " + rows.size() + " 行（最多展示 50 行）。";
            events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", tip, "phase", "SUMMARIZING")));

            AnswerPayload payload = new AnswerPayload(
                    askId, "ans_" + UUID.randomUUID().toString().substring(0, 8),
                    SseEvents.ASK_COMPLETED, SseEvents.INTENT_QUERY, false, null,
                    new AnswerPayload.Conclusion("TEXT", tip, "", null),
                    new AnswerPayload.TableData(columns, rows, rows.size(), 1, 50), null,
                    toCaliber(metric, window == null ? null : window.label()),
                    List.of("按组织对比" + metric.name(), "查看" + metric.name() + "近三月趋势",
                            "查看" + metric.name() + "明细"), elapsed(start));
            events.add(new SseEvent(SseEvents.ANSWER_DONE, objectMapper.convertValue(payload, Map.class)));
            return new AgentResult(askId, events, payload, List.of(), sql,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        } catch (BizException e) {
            boolean recoverable = e.getErrorCode().getHttpStatus() == 200;
            events.add(errorEvent(e.getErrorCode().getCode(), e.getMessageText(), recoverable));
            return new AgentResult(askId, events, null, List.of(), sql,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }
    }

    /** 按组织对比：GROUP BY org → BAR + 表格。 */
    private AgentResult runOrgCompare(String askId, String question, MetricDetail metric, String metricCode,
                                      TimeWindow window, UserContext ctx, Resolved resolved,
                                      List<SseEvent> events, long start) {
        String orgFragment = orgFilterFragment(ctx, resolved.orgKeys(), resolved.orgTerm());
        String orgSql = buildOrgCompareSql(metric, window, orgFragment);
        if (orgSql == null) {
            return null;
        }
        events.add(toolStart("sql_exec", "正在按组织对比「" + metric.name() + "」…"));
        String sql = null;
        try {
            long sqlStart = System.currentTimeMillis();
            AuthorizedQuery authorized = sqlRewriteService.authorize(orgSql, ctx);
            sql = authorized.sql();
            QueryResult result = queryExecService.executeReadonly(authorized);
            events.add(toolEnd("sql_exec", System.currentTimeMillis() - sqlStart, result.rows().size()));

            boolean percent = PERCENT_METRICS.containsKey(metricCode);
            int scale = percent ? 2 : 0;
            String unit = unit(metricCode);
            List<String> categories = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : result.rows()) {
                Object name = row.get("org_name");
                Object v = row.get(metricCode);
                if (name == null || !(v instanceof Number n)) {
                    continue;
                }
                BigDecimal raw = toDecimal(n);
                BigDecimal shown = percent
                        ? raw.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                        : raw.setScale(0, RoundingMode.HALF_UP);
                categories.add(String.valueOf(name));
                values.add(shown);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("org_name", name);
                r.put(metricCode, shown);
                rows.add(r);
            }
            // 对比问句用「谁高/谁低 + 合计」句子作第一行，避免与标量查数同一套大号数字
            String tip = buildOrgCompareSentence(metric.name(), categories, values, percent, scale, unit);
            events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", tip, "phase", "SUMMARIZING")));

            Map<String, Object> barSeries = new LinkedHashMap<>();
            barSeries.put("name", metric.name());
            barSeries.put("type", "bar");
            barSeries.put("data", values);
            barSeries.put("itemStyle", Map.of("color", "#1677ff"));
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("tooltip", Map.of("trigger", "axis"));
            config.put("grid", Map.of("left", 48, "right", 24, "top", 28, "bottom", 48));
            config.put("xAxis", Map.of("type", "category", "data", categories,
                    "axisLabel", Map.of("interval", 0, "rotate", categories.size() > 5 ? 30 : 0)));
            config.put("yAxis", percent
                    ? Map.of("type", "value", "axisLabel", Map.of("formatter", "{value}%"))
                    : Map.of("type", "value"));
            config.put("series", List.of(barSeries));

            AnswerPayload payload = new AnswerPayload(
                    askId, "ans_" + UUID.randomUUID().toString().substring(0, 8),
                    SseEvents.ASK_COMPLETED, SseEvents.INTENT_QUERY, false, null,
                    new AnswerPayload.Conclusion("TEXT", tip, "", null),
                    new AnswerPayload.TableData(
                            List.of(new AnswerPayload.Column("org_name", "组织", "string", false),
                                    new AnswerPayload.Column(metricCode, metric.name(), "number", false)),
                            rows, rows.size(), 1, 50),
                    rows.isEmpty() ? null : new AnswerPayload.Chart("BAR", true, config),
                    toCaliber(metric, window == null ? null : window.label()),
                    List.of("查看" + metric.name() + "明细", "查看" + metric.name() + "近三月趋势",
                            "按组织对比" + metric.name()), elapsed(start));
            events.add(new SseEvent(SseEvents.ANSWER_DONE, objectMapper.convertValue(payload, Map.class)));
            return new AgentResult(askId, events, payload, List.of(), sql,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        } catch (BizException e) {
            boolean recoverable = e.getErrorCode().getHttpStatus() == 200;
            events.add(errorEvent(e.getErrorCode().getCode(), e.getMessageText(), recoverable));
            return new AgentResult(askId, events, null, List.of(), sql,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }
    }

    private String buildDetailSql(MetricDetail metric, TimeWindow window, String orgFragment) {
        BaseParts p = basePartsOf(metric);
        if (p == null) {
            return null;
        }
        String authz = authzPredicate(orgFragment);
        if ("dim_employee".equalsIgnoreCase(p.table())) {
            List<String> cond = new ArrayList<>();
            if (p.where() != null && !p.where().isBlank()) {
                cond.add("(" + p.where() + ")");
            }
            if ("headcount".equals(metric.code())) {
                cond.add(HeadcountAsOf.predicate(HeadcountAsOf.date(
                        window == null ? null : window.end(), demoNow)));
            }
            cond.add(authz);
            return "SELECT emp_no, emp_name, org_key, job_level FROM dim_employee WHERE "
                    + String.join(" AND ", cond) + " ORDER BY emp_no LIMIT 50";
        }
        if (p.isFact()) {
            List<String> cond = new ArrayList<>();
            if (p.where() != null && !p.where().isBlank()) {
                cond.add("(" + p.where() + ")");
            }
            cond.add(authz);
            if (window != null) {
                cond.add(window.sqlPredicate("fact_emp_change".equalsIgnoreCase(p.table())
                        ? "change_date" : "dt"));
            }
            return "SELECT dt, emp_key, org_key FROM " + p.table()
                    + " WHERE " + String.join(" AND ", cond)
                    + " ORDER BY dt DESC LIMIT 50";
        }
        return null;
    }

    private String buildOrgCompareSql(MetricDetail metric, TimeWindow window, String orgFragment) {
        BaseParts p = basePartsOf(metric);
        if (p == null) {
            return null;
        }
        String authz = authzPredicate(orgFragment);
        List<String> cond = new ArrayList<>();
        if (p.where() != null && !p.where().isBlank()) {
            cond.add("(" + p.where() + ")");
        }
        if ("headcount".equals(metric.code())) {
            cond.add(HeadcountAsOf.predicate(HeadcountAsOf.date(
                    window == null ? null : window.end(), demoNow)));
        }
        cond.add(authz);
        if (window != null && p.isFact()) {
            cond.add(window.sqlPredicate("fact_emp_change".equalsIgnoreCase(p.table())
                    ? "change_date" : "dt"));
        }
        // 内层按 org_key 聚合（占位符无表别名）；外层 JOIN dim_org 取可读组织名
        String inner = "SELECT org_key, " + p.aggExpr() + " AS metric_value "
                + "FROM " + p.table() + " WHERE " + String.join(" AND ", cond)
                + " GROUP BY org_key";
        return "SELECT COALESCE(o.org_name, CAST(g.org_key AS VARCHAR)) AS org_name, "
                + "g.metric_value AS \"" + metric.code() + "\" "
                + "FROM (" + inner + ") g "
                + "LEFT JOIN dim_org o ON o.org_key = g.org_key AND o.is_current = 1 "
                + "ORDER BY g.metric_value DESC";
    }

    /**
     * 趋势分析主流程：按月聚合时序 SQL，产出折线图（LINE）。
     *
     * @return 时序结果；指标无法按月聚合时返回 null，由调用方明确告知不支持。
     */
    private AgentResult runTrend(String askId, String question, MetricDetail metric, String metricCode,
                                 TimeWindow window, UserContext ctx, Resolved resolved,
                                 List<SseEvent> events, long start) {
        // 无显式时间范围时趋势默认近三月
        TimeWindow w = window != null ? window
                : new TimeWindow(demoNow.withDayOfMonth(1).minusMonths(2), demoNow.plusDays(1));
        String orgFragment = orgFilterFragment(ctx, resolved.orgKeys(), resolved.orgTerm());
        String trendSql = buildTrendSql(metric, w, orgFragment);
        if (trendSql == null) {
            return null;
        }
        events.add(toolStart("sql_exec", "正在按月统计「" + metric.name() + "」趋势…"));
        String sql = null;
        try {
            long sqlStart = System.currentTimeMillis();
            AuthorizedQuery authorizedQuery = sqlRewriteService.authorize(trendSql, ctx);
            sql = authorizedQuery.sql();
            QueryResult result = queryExecService.executeReadonly(authorizedQuery);
            events.add(toolEnd("sql_exec", System.currentTimeMillis() - sqlStart, result.rows().size()));

            List<String> periods = new ArrayList<>();
            List<BigDecimal> rawValues = new ArrayList<>();
            for (Map<String, Object> row : result.rows()) {
                Object p = row.get("period");
                Object v = row.get(metricCode);
                if (p != null && v instanceof Number n) {
                    periods.add(String.valueOf(p));
                    rawValues.add(toDecimal(n));
                }
            }

            boolean percent = PERCENT_METRICS.containsKey(metricCode);
            int scale = percent ? 2 : 0;
            String unit = unit(metricCode);
            String suffix = percent ? "%" : unit.isEmpty() ? "" : " " + unit;

            // 空时序：给出可操作的文字结论，不渲染图表
            if (periods.isEmpty()) {
                String tip = "该时段内「" + metric.name() + "」暂无月度数据，建议扩大时间范围后重试。";
                events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", tip, "phase", "SUMMARIZING")));
                AnswerPayload empty = new AnswerPayload(
                        askId, "ans_" + UUID.randomUUID().toString().substring(0, 8),
                        SseEvents.ASK_COMPLETED, SseEvents.INTENT_QUERY, false, null,
                        new AnswerPayload.Conclusion("TEXT", tip, "", null),
                        new AnswerPayload.TableData(List.of(), List.of(), 0, 1, 20), null,
                        toCaliber(metric, w.label()),
                        List.of("查看" + metric.name() + "明细", "按组织对比" + metric.name(),
                                "查看" + metric.name() + "近一年趋势"), elapsed(start));
                events.add(new SseEvent(SseEvents.ANSWER_DONE, objectMapper.convertValue(empty, Map.class)));
                return new AgentResult(askId, events, empty, List.of(), sql,
                        SseEvents.INTENT_QUERY, false, elapsed(start));
            }

            // 展示值（百分比 *100）；原始小数留给 sentence/enhance 统一量纲
            List<BigDecimal> chartValues = rawValues.stream()
                    .map(v -> percent ? v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                            : v.setScale(0, RoundingMode.HALF_UP))
                    .toList();
            Number current = rawValues.get(rawValues.size() - 1);
            Number compare = rawValues.get(0);
            String firstPeriod = periods.get(0);
            String lastPeriod = periods.get(periods.size() - 1);

            int cmp = toDecimal(current).compareTo(toDecimal(compare));
            String word = cmp > 0 ? "上升" : cmp < 0 ? "下降" : "持平";
            String fallback = metric.name() + "：" + firstPeriod + " 为 "
                    + display(toDecimal(compare), percent, scale) + suffix + "，" + lastPeriod + " 为 "
                    + display(toDecimal(current), percent, scale) + suffix + "，期间整体" + word + "。";

            String sentence = fallback;
            if (llm().enabled()) {
                events.add(delta(SseEvents.MESSAGE_DELTA,
                        Map.of("delta", "正在生成智能解读…", "phase", "SUMMARIZING")));
                sentence = enhanceSentence(question, metric, current, compare, firstPeriod, unit, fallback);
            }
            events.add(delta(SseEvents.MESSAGE_DELTA, Map.of("delta", sentence, "phase", "SUMMARIZING")));

            AnswerPayload payload = buildTrendPayload(askId, metric, periods, chartValues,
                    sentence, w, elapsed(start));
            events.add(new SseEvent(SseEvents.ANSWER_DONE, objectMapper.convertValue(payload, Map.class)));
            return new AgentResult(askId, events, payload, List.of(), sql,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        } catch (BizException e) {
            boolean recoverable = e.getErrorCode().getHttpStatus() == 200;
            log.warn("趋势查询业务错误: code={}, msg={}", e.getErrorCode().getCode(), e.getMessageText());
            events.add(errorEvent(e.getErrorCode().getCode(), e.getMessageText(), recoverable));
            return new AgentResult(askId, events, null, List.of(), sql,
                    SseEvents.INTENT_QUERY, false, elapsed(start));
        }
    }

    /** 趋势三段式 payload：结论文案（首末点对比句）+ 月度明细表 + LINE 折线图。 */
    private AnswerPayload buildTrendPayload(String askId, MetricDetail metric, List<String> periods,
                                            List<BigDecimal> values, String sentence,
                                            TimeWindow window, long elapsedMs) {
        boolean percent = PERCENT_METRICS.containsKey(metric.code());

        AnswerPayload.Conclusion conclusion = new AnswerPayload.Conclusion(
                "TEXT", sentence, "", null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("period", periods.get(i));
            r.put(metric.code(), values.get(i));
            rows.add(r);
        }
        AnswerPayload.TableData table = new AnswerPayload.TableData(
                List.of(new AnswerPayload.Column("period", "月份", "string", false),
                        new AnswerPayload.Column(metric.code(), metric.name(), "number", false)),
                rows, rows.size(), 1, 20);

        Map<String, Object> lineSeries = new LinkedHashMap<>();
        lineSeries.put("name", metric.name());
        lineSeries.put("type", "line");
        lineSeries.put("smooth", true);
        lineSeries.put("symbolSize", 7);
        lineSeries.put("lineStyle", Map.of("width", 2));
        lineSeries.put("itemStyle", Map.of("color", "#1677ff"));
        lineSeries.put("areaStyle", Map.of("color", "rgba(22, 119, 255, 0.12)"));
        if (values.size() <= 6) {
            lineSeries.put("label", Map.of("show", true, "position", "top",
                    "formatter", percent ? "{c}%" : "{c}"));
        }
        lineSeries.put("data", values);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("color", List.of("#1677ff"));
        Map<String, Object> tooltip = new LinkedHashMap<>();
        tooltip.put("trigger", "axis");
        if (percent) {
            tooltip.put("valueFormatter", "{value}%");
        }
        config.put("tooltip", tooltip);
        config.put("grid", Map.of("left", 48, "right", 24, "top", 28, "bottom", 32));
        config.put("xAxis", Map.of("type", "category", "boundaryGap", false, "data", periods,
                "axisLabel", Map.of("interval", 0)));
        config.put("yAxis", percent
                ? Map.of("type", "value", "axisLabel", Map.of("formatter", "{value}%"))
                : Map.of("type", "value"));
        config.put("series", List.of(lineSeries));
        AnswerPayload.Chart chart = new AnswerPayload.Chart("LINE", true, config);

        AnswerPayload.Caliber caliber = toCaliber(metric, window.label());

        return new AnswerPayload(askId, "ans_" + UUID.randomUUID().toString().substring(0, 8),
                SseEvents.ASK_COMPLETED, SseEvents.INTENT_QUERY, false, null,
                conclusion, table, chart, caliber,
                List.of("查看" + metric.name() + "明细",
                        "按组织对比" + metric.name(),
                        "查看" + metric.name() + "近一年趋势"), elapsedMs);
    }

    /** 组织对比第一行：点名高低 +（可加总时）合计，避免只回一个总数。 */
    static String buildOrgCompareSentence(String metricName, List<String> categories,
                                          List<BigDecimal> values, boolean percent, int scale, String unit) {
        if (categories == null || categories.isEmpty() || values == null || values.isEmpty()) {
            return "「" + metricName + "」暂无组织分布数据。";
        }
        String suffix = percent ? "%" : (unit == null || unit.isBlank() ? "" : unit);
        int maxIdx = 0;
        int minIdx = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i).compareTo(values.get(maxIdx)) > 0) {
                maxIdx = i;
            }
            if (values.get(i).compareTo(values.get(minIdx)) < 0) {
                minIdx = i;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(categories.get(maxIdx)).append(' ')
                .append(display(values.get(maxIdx), false, scale)).append(suffix).append("最高");
        if (categories.size() > 1 && maxIdx != minIdx) {
            sb.append('，').append(categories.get(minIdx)).append(' ')
                    .append(display(values.get(minIdx), false, scale)).append(suffix).append("最低");
        }
        if (!percent) {
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            sb.append('；').append("合计 ").append(display(sum, false, scale)).append(suffix);
        } else {
            sb.append('；').append("共 ").append(categories.size()).append(" 个组织");
        }
        sb.append('。');
        return sb.toString();
    }

    private static String unit(String metricCode) {
        return METRIC_UNIT.getOrDefault(metricCode, "");
    }

    private static BigDecimal toDecimal(Number n) {
        return n instanceof BigDecimal bd ? bd : BigDecimal.valueOf(n.doubleValue());
    }

    /**
     * 展示值：整数走 long（避免 BigDecimal.stripTrailingZeros 把 10 变成 1E+1）；
     * 小数用 toPlainString 再解析，保证 JSON/文案都是常规十进制。
     */
    private static Object display(BigDecimal v, boolean percent, int scale) {
        BigDecimal r = percent ? v.multiply(BigDecimal.valueOf(100)) : v;
        BigDecimal scaled = r.setScale(scale, RoundingMode.HALF_UP);
        if (scale <= 0) {
            return scaled.longValue();
        }
        return new BigDecimal(scaled.stripTrailingZeros().toPlainString());
    }

    // =================================================================
    // 事件构造 / 校验 / 工具
    // =================================================================

    private static SseEvent delta(String event, Map<String, Object> payload) {
        return new SseEvent(event, payload);
    }

    private static SseEvent toolStart(String tool, String summary) {
        return new SseEvent(SseEvents.TOOL_CALL_START, Map.of("tool", tool, "summary", summary));
    }

    private static SseEvent toolEnd(String tool, long ms, int rows) {
        return new SseEvent(SseEvents.TOOL_CALL_END, Map.of("tool", tool, "ms", ms, "rows", rows));
    }

    private static SseEvent errorEvent(String code, String message, boolean recoverable) {
        return new SseEvent(SseEvents.ERROR,
                Map.of("code", code, "message", message, "recoverable", recoverable));
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private void validate(AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "question");
        }
        if (request.question().length() > 500) {
            throw new BizException(ErrorCode.QUESTION_TOO_LONG);
        }
    }
}
