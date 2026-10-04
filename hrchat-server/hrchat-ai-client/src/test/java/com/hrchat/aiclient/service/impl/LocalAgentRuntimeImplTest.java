package com.hrchat.aiclient.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.aiclient.service.llm.LlmCallException;
import com.hrchat.aiclient.service.llm.LlmChatClient;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.api.sse.SseEvent;
import com.hrchat.api.sse.SseEvents;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.model.AuthorizedQuery;
import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.knowledge.search.HybridRetriever;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.entity.BizSynonym;
import com.hrchat.semantic.mapper.BizSynonymMapper;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * S4 问数主链路核心用例：CHITCHAT / 组织过滤与权限改写 / 时间窗口 / 澄清 / 越权 HRC-2003。
 */
@ExtendWith(MockitoExtension.class)
class LocalAgentRuntimeImplTest {

    @Mock
    private HybridRetriever hybridRetriever;
    @Mock
    private SemanticMetaService semanticMetaService;
    @Mock
    private QueryExecService queryExecService;
    @Mock
    private BizSynonymMapper synonymMapper;

    private LocalAgentRuntimeImpl runtime;

    /** 真实无状态改写服务（占位符替换 + 只读校验）。 */
    private final SqlRewriteService sqlRewriteService = new SqlRewriteService();

    private UserContext hr01;

    @BeforeEach
    void setUp() {
        runtime = new LocalAgentRuntimeImpl(hybridRetriever, semanticMetaService, sqlRewriteService,
                queryExecService, synonymMapper, new ObjectMapper(), LocalDate.of(2026, 9, 28));
        hr01 = UserContext.builder()
                .userId(1L).empNo("hr01").displayName("张雨晴")
                .tenantId("t01")
                .roles(List.of("HRBP")).dataLevel(1)
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(2L).orgCode("RD").orgName("研发中心").orgPath("/1/2/")
                        .scope(2).subtreeOrgKeys(List.of(2L, 3L, 4L)).build()))
                .fieldPolicyByField(Map.of())
                .permissionFingerprint("fp-hr01")
                .build();
    }

    private void stubSynonyms() {
        when(synonymMapper.selectList(null)).thenReturn(List.of(
                syn("在职人数", 1, 1L), syn("在职", 1, 1L), syn("人数", 1, 1L),
                syn("入职人数", 1, 2L), syn("入职", 1, 2L), syn("新入职", 1, 2L),
                syn("离职人数", 1, 3L), syn("离职", 1, 3L),
                syn("离职率", 1, 4L),
                syn("研发中心", 2, 2L), syn("研发一部", 2, 3L),
                syn("销售部", 2, 5L), syn("人力部", 2, 7L)));
    }

    private void stubMetrics() {
        // lenient：不同用例使用指标子集不同，避免未使用 stub 触发 UnnecessaryStubbing
        lenient().when(semanticMetaService.getMetric(1L)).thenReturn(metric(1L, "headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        lenient().when(semanticMetaService.getMetric(2L)).thenReturn(metric(2L, "hire_count", "入职人数", "SELECT COUNT(*) FROM fact_emp_change WHERE change_type = 1"));
        lenient().when(semanticMetaService.getMetric(3L)).thenReturn(metric(3L, "leave_count", "离职人数", "SELECT COUNT(*) FROM fact_emp_change WHERE change_type IN (5, 6)"));
        lenient().when(semanticMetaService.getMetric(4L)).thenReturn(metric(4L, "turnover_rate", "离职率", "leave_count / headcount"));
        lenient().when(semanticMetaService.getMetricByCode("headcount")).thenReturn(metric(1L, "headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        lenient().when(semanticMetaService.getMetricByCode("hire_count")).thenReturn(metric(2L, "hire_count", "入职人数", "SELECT COUNT(*) FROM fact_emp_change WHERE change_type = 1"));
        lenient().when(semanticMetaService.getMetricByCode("leave_count")).thenReturn(metric(3L, "leave_count", "离职人数", "SELECT COUNT(*) FROM fact_emp_change WHERE change_type IN (5, 6)"));
        lenient().when(semanticMetaService.getMetricByCode("turnover_rate")).thenReturn(metric(4L, "turnover_rate", "离职率", "leave_count / headcount"));
    }

    private void stubMetricCatalog() {
        lenient().when(semanticMetaService.listMetrics(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(com.hrchat.common.api.PageResult.of(List.of(
                        new com.hrchat.semantic.dto.MetricSummary(1L, "headcount", "在职人数", "staff", 1, 1, 0, null),
                        new com.hrchat.semantic.dto.MetricSummary(2L, "hire_count", "入职人数", "staff", 1, 1, 0, null),
                        new com.hrchat.semantic.dto.MetricSummary(3L, "leave_count", "离职人数", "staff", 1, 1, 0, null),
                        new com.hrchat.semantic.dto.MetricSummary(4L, "turnover_rate", "离职率", "staff", 1, 1, 0, null)),
                        4, 1, 200));
    }

    private static BizSynonym syn(String term, int type, Long targetId) {
        BizSynonym s = new BizSynonym();
        s.setTermGroup(term);
        s.setTargetType(type);
        s.setTargetId(targetId);
        s.setStatus(1);
        return s;
    }

    private static MetricDetail metric(Long id, String code, String name, String formula) {
        return new MetricDetail(id, code, name, "staff", formula,
                "口径：" + name, "MONTH", 1, 1, 1, 0, 1, List.of("org"), "dat01", null);
    }

    // ---------------- 用例 1：CHITCHAT ----------------

    @Test
    void chitchat_returnsTextAnswerWithoutQuery() {
        AgentResult result = runtime.ask(new AskRequest("你好", "STREAM", null), hr01);
        assertEquals(SseEvents.INTENT_CHITCHAT, result.intent());
        assertFalse(result.isClarifying());
        assertNull(result.sql());
        assertEquals(SseEvents.ASK_COMPLETED, result.payload().status());
        assertEquals("TEXT", result.payload().conclusion().type());
        assertTrue(result.events().stream().anyMatch(e -> SseEvents.ANSWER_DONE.equals(e.event())));
    }

    // ---------------- 用例 2：组织过滤 + 权限改写 + 数值 ------------

    @Test
    void query_withOrgSynonym_injectsRowFilterAndReturnsValue() {
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(
                new QueryResult(List.of(new QueryResult.ColumnMeta("headcount", "headcount", "int", false)),
                        List.of(Map.of("headcount", 18L)), 1));

        AgentResult result = runtime.ask(new AskRequest("研发中心在职人数", "STREAM", null), hr01);

        ArgumentCaptor<AuthorizedQuery> sqlCaptor = ArgumentCaptor.forClass(AuthorizedQuery.class);
        org.mockito.Mockito.verify(queryExecService).executeReadonly(sqlCaptor.capture());
        String sql = sqlCaptor.getValue().sql();
        // 组织行级注入：研发中心（节点2）授权子树 2,3,4
        assertTrue(sql.contains("org_key IN (2, 3, 4)"), "SQL 应注入授权子树过滤: " + sql);
        assertTrue(sql.contains("emp_status = 1"), "应保留指标自身口径谓词: " + sql);

        assertEquals(SseEvents.INTENT_QUERY, result.intent());
        assertEquals(SseEvents.ASK_COMPLETED, result.payload().status());
        assertEquals(18, ((Number) result.payload().conclusion().value()).intValue());
        assertEquals("headcount", result.payload().table().columns().get(0).key());
        assertTrue(result.sql().contains("org_key IN (2, 3, 4)"));
        // 事件序列：PARSING → semantic_search START/END → sql_exec START/END → SUMMARIZING → ANSWER_DONE
        assertEquals(SseEvents.TOOL_CALL_START, result.events().get(1).event());
        assertEquals(SseEvents.ANSWER_DONE, result.events().get(result.events().size() - 1).event());
    }

    // ---------------- 用例 3：时间窗口（上月 → 2026-08） + 环比 ----------------

    @Test
    void query_withLastMonth_appliesTimeWindowAndCompare() {
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class)))
                .thenAnswer(inv -> {
                    String sql = ((AuthorizedQuery) inv.getArgument(0)).sql();
                    // 主查询（2026-08）与环比上期（2026-07）区分：环比 SQL 含 "dt < '2026-08-01'"，
                    // 故用 "dt >= '2026-08-01'" 精确定位主查询
                    long value = sql.contains("dt >= '2026-08-01'") ? 3L : 0L;
                    return new QueryResult(List.of(new QueryResult.ColumnMeta("hire_count", "hire_count", "int", false)),
                            List.of(Map.of("hire_count", value)), 1);
                });

        AgentResult result = runtime.ask(new AskRequest("上月入职了多少人？", "STREAM", null), hr01);

        ArgumentCaptor<AuthorizedQuery> sqlCaptor = ArgumentCaptor.forClass(AuthorizedQuery.class);
        org.mockito.Mockito.verify(queryExecService, org.mockito.Mockito.atLeast(1)).executeReadonly(sqlCaptor.capture());
        List<String> sqls = sqlCaptor.getAllValues().stream().map(AuthorizedQuery::sql).toList();
        assertTrue(sqls.stream().anyMatch(s -> s.contains("dt >= '2026-08-01' AND dt < '2026-09-01'")),
                "上月窗口应映射 2026-08: " + sqls);
        assertTrue(sqls.stream().anyMatch(s -> s.contains("change_type = 1")));
        // 环比：当期 3 vs 上期 0 → UP
        assertEquals(3, ((Number) result.payload().conclusion().value()).intValue());
        assertEquals("UP", result.payload().conclusion().compare().direction());
        assertEquals("2026-07-01/2026-07-31", result.payload().conclusion().compare().period());
    }

    // ---------------- 用例 4：歧义澄清（BR-07） + 澄清续答 ----------------

    @Test
    void ambiguousQuestion_emitsInterruptAndClarifyResumes() {
        stubSynonyms();
        stubMetrics();
        // 双指标命中时 details.size()>1 直接澄清，不触发混合检索兜底，故 lenient
        lenient().when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        AgentResult first = runtime.ask(new AskRequest("上月离职人数和入职人数", "STREAM", null), hr01);
        assertTrue(first.isClarifying());
        assertEquals(1, first.clarifyQuestions().size());
        ClarifyQuestion q = first.clarifyQuestions().get(0);
        assertEquals(2, q.options().size());
        assertTrue(q.options().stream().anyMatch(o -> "hire_count".equals(o.optionId())));
        assertTrue(q.options().stream().anyMatch(o -> "leave_count".equals(o.optionId())));
        assertTrue(first.events().stream().anyMatch(e -> SseEvents.INTERRUPT.equals(e.event())));
        assertNull(first.payload());

        // 澄清选择「入职人数」续跑
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(
                new QueryResult(List.of(new QueryResult.ColumnMeta("hire_count", "hire_count", "int", false)),
                        List.of(Map.of("hire_count", 3L)), 1));
        AgentResult second = runtime.clarify(first.askId(), "上月离职人数和入职人数",
                new ClarifyAnswerRequest.Answer(q.questionId(), List.of("hire_count")), hr01);

        assertFalse(second.isClarifying());
        assertEquals(SseEvents.ASK_COMPLETED, second.payload().status());
        assertEquals(3, ((Number) second.payload().conclusion().value()).intValue());
        assertTrue(second.sql().contains("hire_count"));
    }

    // ---------------- 用例 4b：缺指标槽位（"查看近三月趋势"）→ 引导澄清 + 续跑 ----------------

    @Test
    void missingMetric_emitsClarifyWithCandidates_insteadOfHra4001() {
        stubSynonyms();
        stubMetrics();
        stubMetricCatalog();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        AgentResult result = runtime.ask(new AskRequest("查看近三月趋势", "STREAM", null), hr01);

        assertTrue(result.isClarifying(), "缺指标时应进入澄清而非直接报错");
        assertTrue(result.events().stream().anyMatch(e -> SseEvents.INTERRUPT.equals(e.event())));
        assertFalse(result.events().stream().anyMatch(e -> SseEvents.ERROR.equals(e.event())));
        ClarifyQuestion q = result.clarifyQuestions().get(0);
        assertEquals(4, q.options().size(), "候选应为全部已发布指标（≤5）");
        assertTrue(q.options().stream().anyMatch(o -> "headcount".equals(o.optionId())));
        assertNull(result.payload());
    }

    @Test
    void clarify_missingMetric_resumesWithThreeMonthWindow() {
        stubSynonyms();
        stubMetrics();
        stubMetricCatalog();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        AgentResult first = runtime.ask(new AskRequest("查看近三月趋势", "STREAM", null), hr01);
        ClarifyQuestion q = first.clarifyQuestions().get(0);

        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(monthlyHireResult());
        AgentResult second = runtime.clarify(first.askId(), "查看近三月趋势",
                new ClarifyAnswerRequest.Answer(q.questionId(), List.of("hire_count")), hr01);

        assertFalse(second.isClarifying());
        assertEquals(SseEvents.ASK_COMPLETED, second.payload().status());
        // demoNow=2026-09-28 → 近三月窗口 [2026-06-28, 2026-09-29)，仅事实表注入时间谓词
        assertTrue(second.sql().contains("dt >= '2026-06-28' AND dt < '2026-09-29'"),
                "澄清续跑应带上原问句的近三月时间窗: " + second.sql());
        // 趋势问句澄清后续跑应按月聚合产出折线图
        assertTrue(second.sql().contains("GROUP BY FORMATDATETIME(dt, 'yyyy-MM')"),
                "趋势应按月分组: " + second.sql());
        assertEquals("LINE", second.payload().chart().type());
        // 推荐气泡带当前指标，保证可点可答
        assertEquals("查看入职人数近一年趋势", second.payload().followups().get(2));
    }

    // ---------------- 用例 4c：趋势折线图（事实表按月聚合） ----------------

    @Test
    @SuppressWarnings("unchecked")
    void trendQuestion_factMetric_returnsMonthlyLineChart() {
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(monthlyHireResult());

        AgentResult result = runtime.ask(new AskRequest("入职人数近三月趋势", "STREAM", null), hr01);

        ArgumentCaptor<AuthorizedQuery> sqlCaptor = ArgumentCaptor.forClass(AuthorizedQuery.class);
        org.mockito.Mockito.verify(queryExecService).executeReadonly(sqlCaptor.capture());
        String sql = sqlCaptor.getValue().sql();
        assertTrue(sql.contains("GROUP BY FORMATDATETIME(dt, 'yyyy-MM')"), "应按月分组: " + sql);
        assertTrue(sql.contains("dt >= '2026-06-28' AND dt < '2026-09-29'"), "应注入近三月窗口: " + sql);
        assertTrue(sql.contains("change_type = 1"), "应保留指标自身口径: " + sql);

        assertEquals("LINE", result.payload().chart().type());
        Map<String, Object> cfg = result.payload().chart().config();
        List<String> xData = (List<String>) ((Map<String, Object>) cfg.get("xAxis")).get("data");
        assertEquals(List.of("2026-07", "2026-08", "2026-09"), xData);
        List<Map<String, Object>> series = (List<Map<String, Object>>) cfg.get("series");
        assertEquals("line", series.get(0).get("type"));
        List<?> yData = (List<?>) series.get(0).get("data");
        assertEquals(5, ((Number) yData.get(2)).intValue());
        // 趋势第一行用首末点对比句（TEXT），不再用大号末点数字
        assertEquals("TEXT", result.payload().conclusion().type());
        String tip = String.valueOf(result.payload().conclusion().value());
        assertTrue(tip.contains("2026-07") && tip.contains("2026-09") && tip.contains("上升"), tip);
        assertEquals("period", result.payload().table().columns().get(0).key());
        assertEquals(3, result.payload().table().rows().size());
    }

    @Test
    void trendQuestion_snapshotHeadcount_buildsLineFromHireLeaveDates() {
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(new QueryResult(
                List.of(new QueryResult.ColumnMeta("period", "period", "string", false),
                        new QueryResult.ColumnMeta("headcount", "headcount", "int", false)),
                List.of(
                        Map.of("period", "2026-07", "headcount", 15L),
                        Map.of("period", "2026-08", "headcount", 17L),
                        Map.of("period", "2026-09", "headcount", 18L)),
                3));

        // headcount：按入职/离职日还原月末时点，产出 LINE
        AgentResult result = runtime.ask(new AskRequest("在职人数近三月趋势", "STREAM", null), hr01);

        assertEquals(SseEvents.INTENT_QUERY, result.intent());
        assertEquals("LINE", result.payload().chart().type());
        assertEquals("TEXT", result.payload().conclusion().type());
        String tip = String.valueOf(result.payload().conclusion().value());
        assertTrue(tip.contains("15") && tip.contains("18") && tip.contains("上升"), tip);
        assertTrue(result.sql().contains("hire_date"), "应使用入职日还原时点: " + result.sql());
        assertTrue(result.sql().contains("UNION ALL"), "应按月 UNION: " + result.sql());
    }

    @Test
    @SuppressWarnings("unchecked")
    void trendQuestion_derivedRatio_joinsMonthlyNumeratorWithScalarDenominator() {
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(new QueryResult(
                List.of(new QueryResult.ColumnMeta("period", "period", "string", false),
                        new QueryResult.ColumnMeta("turnover_rate", "turnover_rate", "decimal", false)),
                List.of(
                        Map.of("period", "2026-07", "turnover_rate", new java.math.BigDecimal("0.0500")),
                        Map.of("period", "2026-08", "turnover_rate", new java.math.BigDecimal("0.0556")),
                        Map.of("period", "2026-09", "turnover_rate", new java.math.BigDecimal("0.0588"))),
                3));

        AgentResult result = runtime.ask(new AskRequest("离职率近三月趋势", "STREAM", null), hr01);

        String sql = result.sql();
        assertTrue(sql.contains("fact_emp_change"), "分子应取离职事实表: " + sql);
        assertTrue(sql.contains("dim_employee"), "分母为在职快照标量: " + sql);
        assertTrue(sql.contains("GROUP BY FORMATDATETIME(dt, 'yyyy-MM')"), "分子应按月分组: " + sql);
        assertTrue(sql.contains("NULLIF("), "应防除零: " + sql);

        assertEquals("LINE", result.payload().chart().type());
        // 结论文案含首末点百分比；图数据末点 0.0588 → 5.88
        assertEquals("TEXT", result.payload().conclusion().type());
        String tip = String.valueOf(result.payload().conclusion().value());
        assertTrue(tip.contains("5") && tip.contains("5.88") && tip.contains("上升"), tip);
        Map<String, Object> cfg = result.payload().chart().config();
        assertEquals("{value}%",
                ((Map<String, Object>) ((Map<String, Object>) cfg.get("yAxis")).get("axisLabel")).get("formatter"));
        List<Map<String, Object>> series = (List<Map<String, Object>>) cfg.get("series");
        List<?> yData = (List<?>) series.get(0).get("data");
        assertEquals(0, new java.math.BigDecimal("5.88")
                .compareTo((java.math.BigDecimal) ((Number) yData.get(2))));
    }

    private static QueryResult monthlyHireResult() {
        return new QueryResult(
                List.of(new QueryResult.ColumnMeta("period", "period", "string", false),
                        new QueryResult.ColumnMeta("hire_count", "hire_count", "int", false)),
                List.of(
                        Map.of("period", "2026-07", "hire_count", 2L),
                        Map.of("period", "2026-08", "hire_count", 3L),
                        Map.of("period", "2026-09", "hire_count", 5L)),
                3);
    }

    // ---------------- 用例 5：越权组织 → HRC-2003 ERROR 事件 ----------------

    @Test
    void outOfScopeOrg_emitsDataRangeForbiddenError() {
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        AgentResult result = runtime.ask(new AskRequest("销售部在职人数", "STREAM", null), hr01);

        assertNull(result.payload());
        assertNull(result.sql());
        SseEvent error = result.events().stream().filter(e -> SseEvents.ERROR.equals(e.event())).findFirst().orElseThrow();
        assertEquals(ErrorCode.DATA_RANGE_FORBIDDEN.getCode(), error.payload().get("code"));
        assertEquals(Boolean.FALSE, error.payload().get("recoverable"));
    }

    // ---------------- 用例 6：参数校验 ----------------

    @Test
    void blankQuestion_throwsParamMissing() {
        org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> runtime.ask(new AskRequest("  ", "STREAM", null), hr01));
    }

    @Test
    void longQuestion_throwsQuestionTooLong() {
        String longQ = "问".repeat(501);
        org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> runtime.ask(new AskRequest(longQ, "STREAM", null), hr01));
    }

    // ---------------- 用例 7：比率指标浮点除法（H2 整数除法 1/18=0 回归） ----------------

    @Test
    void ratioMetric_usesDecimalDivisionAndNullif_notIntegerZero() {
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(
                new QueryResult(List.of(new QueryResult.ColumnMeta("turnover_rate", "turnover_rate", "decimal", false)),
                        List.of(Map.of("turnover_rate", new java.math.BigDecimal("0.055600"))), 1));

        AgentResult result = runtime.ask(new AskRequest("研发中心离职率是多少", "STREAM", null), hr01);

        ArgumentCaptor<AuthorizedQuery> sqlCaptor = ArgumentCaptor.forClass(AuthorizedQuery.class);
        org.mockito.Mockito.verify(queryExecService).executeReadonly(sqlCaptor.capture());
        String sql = sqlCaptor.getValue().sql();
        assertTrue(sql.contains("CAST(") && sql.contains("AS DECIMAL(18,6)"),
                "比率指标应转 DECIMAL 浮点除法: " + sql);
        assertTrue(sql.contains("NULLIF("), "应防除零: " + sql);
        // 0.0556 ×100 展示为 5.56%（回归整数除法返回 0% 的 bug）
        assertEquals(0, new java.math.BigDecimal("5.56").compareTo((java.math.BigDecimal) result.payload().conclusion().value()));
        assertEquals("%", result.payload().conclusion().unit());
    }

    // ---------------- 用例 8：LLM 答案润色（成功替换 / 失败降级） ----------------

    @Test
    void llmEnabled_summaryDeltaUsesAiWording_payloadNumberUnchanged() {
        FakeChatClient fake = new FakeChatClient();
        fake.reply = "研发中心当前在职 18 人，规模保持稳定。";
        runtime = new LocalAgentRuntimeImpl(hybridRetriever, semanticMetaService, sqlRewriteService,
                queryExecService, synonymMapper, new ObjectMapper(), LocalDate.of(2026, 9, 28),
                tenantNo -> fake);
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(
                new QueryResult(List.of(new QueryResult.ColumnMeta("headcount", "headcount", "int", false)),
                        List.of(Map.of("headcount", 18L)), 1));

        AgentResult result = runtime.ask(new AskRequest("研发中心在职人数", "STREAM", null), hr01);

        assertEquals(fake.reply, lastSummarizingDelta(result));
        // 结构化卡片数值仍取确定性取数结果，不依赖模型文本
        assertEquals(18, ((Number) result.payload().conclusion().value()).intValue());
        assertTrue(fake.lastUserPrompt.contains("在职人数") && fake.lastUserPrompt.contains("18"));
    }

    @Test
    void llmFails_summaryFallsBackToTemplateSentence() {
        FakeChatClient fake = new FakeChatClient();
        fake.error = new LlmCallException("connect timeout");
        runtime = new LocalAgentRuntimeImpl(hybridRetriever, semanticMetaService, sqlRewriteService,
                queryExecService, synonymMapper, new ObjectMapper(), LocalDate.of(2026, 9, 28),
                tenantNo -> fake);
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(
                new QueryResult(List.of(new QueryResult.ColumnMeta("headcount", "headcount", "int", false)),
                        List.of(Map.of("headcount", 18L)), 1));

        AgentResult result = runtime.ask(new AskRequest("研发中心在职人数", "STREAM", null), hr01);

        assertEquals("在职人数为 18 人", lastSummarizingDelta(result));
        assertEquals(18, ((Number) result.payload().conclusion().value()).intValue());
    }

    @Test
    void llmReturnsInvalidText_summaryFallsBackToTemplateSentence() {
        FakeChatClient fake = new FakeChatClient();
        fake.reply = "第一行\n第二行编造内容";
        runtime = new LocalAgentRuntimeImpl(hybridRetriever, semanticMetaService, sqlRewriteService,
                queryExecService, synonymMapper, new ObjectMapper(), LocalDate.of(2026, 9, 28),
                tenantNo -> fake);
        stubSynonyms();
        stubMetrics();
        when(hybridRetriever.hybridSearch(anyString(), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryExecService.executeReadonly(any(AuthorizedQuery.class))).thenReturn(
                new QueryResult(List.of(new QueryResult.ColumnMeta("headcount", "headcount", "int", false)),
                        List.of(Map.of("headcount", 18L)), 1));

        AgentResult result = runtime.ask(new AskRequest("研发中心在职人数", "STREAM", null), hr01);

        assertEquals("在职人数为 18 人", lastSummarizingDelta(result));
    }

    private static String lastSummarizingDelta(AgentResult result) {
        return result.events().stream()
                .filter(e -> SseEvents.MESSAGE_DELTA.equals(e.event()))
                .filter(e -> "SUMMARIZING".equals(e.payload().get("phase")))
                .map(e -> (String) e.payload().get("delta"))
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    @Test
    void orgCompareSentence_avoidsScientificNotationForTen() {
        String tip = LocalAgentRuntimeImpl.buildOrgCompareSentence(
                "在职人数",
                List.of("研发一部", "研发二部"),
                List.of(new java.math.BigDecimal("10"), new java.math.BigDecimal("8")),
                false, 0, "人");
        assertTrue(tip.contains("10人") && !tip.toUpperCase().contains("E+"), tip);
        assertTrue(tip.contains("合计 18人"), tip);
    }

    /** 内存 LLM 假客户端：固定回复或抛异常。 */
    private static final class FakeChatClient implements LlmChatClient {
        private String reply = "好的";
        private LlmCallException error;
        private String lastUserPrompt;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String model() {
            return "fake-model";
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) throws LlmCallException {
            this.lastUserPrompt = userPrompt;
            if (error != null) {
                throw error;
            }
            return reply;
        }
    }
}
