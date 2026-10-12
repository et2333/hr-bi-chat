package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartSeries;
import com.hrchat.report.chart.ChartViews.InsightAction;
import com.hrchat.report.chart.ChartViews.InsightPoint;
import com.hrchat.report.chart.ChartViews.InsightSource;
import com.hrchat.report.chart.ChartViews.InsightTaskView;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptInsightTask;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptViewSnapshot;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptDataVersionMapper;
import com.hrchat.report.mapper.RptInsightTaskMapper;
import com.hrchat.report.mapper.RptViewSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * InsightTaskService 单测（I2）：attempt 状态机、幂等取得、迟到结果丢弃、
 * 重试拒绝并发重复尝试，以及富 InsightView 的 JSON 往返（多构造器）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InsightTaskServiceTest {

    @Mock
    private RptInsightTaskMapper insightMapper;
    @Mock
    private RptComponentMapper componentMapper;
    @Mock
    private RptDataVersionMapper dataVersionMapper;
    @Mock
    private RptViewSnapshotMapper viewSnapshotMapper;
    @Mock
    private ReportService reportService;
    @Mock
    private AgentRuntimeClient agentRuntime;
    @Mock
    private ReportChartService chartService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InsightTaskService service;
    private final UserContext ctx = UserContext.builder().userId(1L).empNo("hr01").tenantId("t01").build();

    @BeforeEach
    void setUp() {
        // 纯单测环境下初始化 TableInfo 缓存，使 LambdaQueryWrapper/LambdaUpdateWrapper 可渲染 SQL 片段
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
                RptInsightTask.class);
        service = new InsightTaskService(insightMapper, componentMapper, dataVersionMapper,
                viewSnapshotMapper, reportService, agentRuntime, objectMapper);
        service.setChartService(chartService);
    }

    private RptInsightTask task(String status, int attempt) {
        RptInsightTask task = new RptInsightTask();
        task.setId(50L);
        task.setReportId(1L);
        task.setComponentId(10L);
        task.setDataVersionId(7L);
        task.setViewKey(ViewKeySupport.ROOT);
        task.setInsightRevision(InsightTaskService.INSIGHT_CONFIG_REVISION);
        task.setStatus(status);
        task.setAttempt(attempt);
        return task;
    }

    private RptReport report() {
        RptReport report = new RptReport();
        report.setId(1L);
        report.setTenantId("t01");
        report.setReportName("人力编制报表");
        report.setDefinitionRevision(1);
        report.setCurrentDataVersionId(7L);
        return report;
    }

    private RptDataVersion readyVersion() {
        RptDataVersion version = new RptDataVersion();
        version.setId(7L);
        version.setReportId(1L);
        version.setStatus(ReportDataVersionService.STATUS_READY);
        version.setDefinitionRevision(1);
        version.setScopeRef(ReportDataScope.fingerprint(ctx));
        version.setCreatedBy("hr01");
        return version;
    }

    private RptComponent chartComponent() {
        RptComponent component = new RptComponent();
        component.setId(10L);
        component.setReportId(1L);
        component.setCompType(1);
        component.setChartType("BAR");
        return component;
    }

    private RptViewSnapshot snapshot() throws Exception {
        ChartDataView chart = new ChartDataView("BAR", List.of("研发一部"),
                List.of(new ChartSeries("在职人数", List.of(10))), List.of());
        RptViewSnapshot snap = new RptViewSnapshot();
        snap.setId(555L);
        snap.setChartJson(objectMapper.writeValueAsString(chart));
        return snap;
    }

    /** ensure/retry 请求携带的预期来源（与 readyVersion/snapshot 同源）。 */
    private InsightSource source() {
        return new InsightSource(1L, 10L, "7", ViewKeySupport.ROOT, "555",
                ReportDataScope.fingerprint(ctx));
    }

    @Test
    void execute_claimsOnce_thenPersistsCompleteResult() throws Exception {
        when(insightMapper.selectById(50L))
                .thenReturn(task(RptInsightTask.PENDING, 1), task(RptInsightTask.RUNNING, 1));
        when(insightMapper.claim(50L)).thenReturn(1);
        when(reportService.loadReport(1L)).thenReturn(report());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(chartService.buildInsightRequest(any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new InsightRequest("人力编制报表", "在职人数", Map.of()));
        when(agentRuntime.generateInsight(any(), any())).thenReturn(Map.of(
                "status", "complete", "summary", "在职人数出现明显变化",
                "data_version", "7", "view_key", ViewKeySupport.ROOT, "snapshot_id", "555",
                "points", List.of(Map.of("type", "finding", "label", "在职人数出现明显变化")),
                "schema_version", "2",
                "claims", List.of(Map.of("claim_id", "c1", "kind", "fact",
                        "text", "在职人数出现明显变化+2人",
                        "fact_ids", List.of("absolute_change:overall"))),
                "narrative", Map.of("summary_claim_id", "c1", "finding_claim_ids", List.of("c1")),
                "facts", List.of(Map.of("fact_id", "absolute_change:overall", "fact_type", "absolute_change",
                        "value", 2, "unit", "人", "snapshot_id", "555"))));
        when(insightMapper.finish(anyLong(), anyInt(), anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        service.execute(50L);

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(insightMapper).finish(eq(50L), eq(1), eq(RptInsightTask.COMPLETE),
                resultJson.capture(), anyString(), any(), any(), any());
        // R5 / C16: the model narrative and its evidence survive persistence, not just the summary.
        assertThat(resultJson.getValue()).contains("在职人数出现明显变化");
        assertThat(resultJson.getValue()).contains("schemaVersion");
        assertThat(resultJson.getValue()).contains("claims");
        assertThat(resultJson.getValue()).contains("absolute_change:overall");
        assertThat(resultJson.getValue()).contains("narrative");
    }

    @Test
    void execute_skipsModelWhenClaimLost() {
        when(insightMapper.selectById(50L)).thenReturn(task(RptInsightTask.PENDING, 0));
        when(insightMapper.claim(50L)).thenReturn(0);

        service.execute(50L);

        verifyNoInteractions(agentRuntime);
        verify(insightMapper, never()).finish(anyLong(), anyInt(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void execute_doesNotThrowWhenLateResultIsDiscarded() throws Exception {
        when(insightMapper.selectById(50L))
                .thenReturn(task(RptInsightTask.PENDING, 1), task(RptInsightTask.RUNNING, 1));
        when(insightMapper.claim(50L)).thenReturn(1);
        when(reportService.loadReport(1L)).thenReturn(report());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(chartService.buildInsightRequest(any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new InsightRequest("r", "m", Map.of()));
        when(agentRuntime.generateInsight(any(), any())).thenReturn(Map.of(
                "status", "complete", "summary", "s",
                "data_version", "7", "view_key", ViewKeySupport.ROOT, "snapshot_id", "555"));
        // 被取代的 attempt：条件更新影响 0 行
        when(insightMapper.finish(anyLong(), anyInt(), anyString(), any(), any(), any(), any(), any())).thenReturn(0);

        service.execute(50L);

        verify(insightMapper).finish(eq(50L), eq(1), eq(RptInsightTask.COMPLETE),
                argThat(Objects::nonNull), anyString(), any(), any(), any());
    }

    @Test
    void ensureForView_isIdempotentWhenTaskAlreadyExists() throws Exception {
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report());
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        RptInsightTask existing = task(RptInsightTask.COMPLETE, 1);
        existing.setId(99L);
        when(insightMapper.selectOne(any())).thenReturn(existing);
        when(insightMapper.selectById(99L)).thenReturn(existing);

        InsightTaskView view = service.ensureForView(ctx, 1L, 10L, source());

        assertThat(view.taskId()).isEqualTo(99L);
        assertThat(view.status()).isEqualTo(RptInsightTask.COMPLETE);
        verify(insightMapper, never()).insert(any());
        verifyNoInteractions(agentRuntime);
    }

    @Test
    void retry_rejectsRunningTask() throws Exception {
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report());
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(insightMapper.selectById(50L)).thenReturn(task(RptInsightTask.RUNNING, 1));

        assertThatThrownBy(() -> service.retry(ctx, 1L, 10L, 50L, source(), 1))
                .isInstanceOf(BizException.class);
        verify(insightMapper, never()).update(any(), any());
    }

    @Test
    void retry_rejectsCompletedTask() throws Exception {
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report());
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(insightMapper.selectById(50L)).thenReturn(task(RptInsightTask.COMPLETE, 1));

        assertThatThrownBy(() -> service.retry(ctx, 1L, 10L, 50L, source(), 1))
                .isInstanceOf(BizException.class);
    }

    @Test
    void readForView_roundTripsRichInsightViewJson() throws Exception {
        InsightView rich = new InsightView("人力编制报表", "在职人数", "总体平稳",
                List.of(new InsightPoint("value", "在职人数 10 人")),
                "complete", null, "7", ViewKeySupport.ROOT,
                List.of("样本期为单月"),
                List.of(new InsightAction("a1", "attribution", "分析净变化来自哪个部门", true)),
                null, null, false, false, "2",
                List.of(Map.of("claim_id", "c1", "kind", "fact", "text", "总体平稳")),
                Map.of("summary_claim_id", "c1", "finding_claim_ids", List.of()),
                List.of(Map.of("fact_id", "absolute_change:overall", "fact_type", "absolute_change", "unit", "人")));
        RptInsightTask stored = task(RptInsightTask.COMPLETE, 1);
        stored.setResultJson(objectMapper.writeValueAsString(rich));
        stored.setGenerationJson("{\"usage_source\":\"unknown\"}");
        when(insightMapper.selectOne(any())).thenReturn(stored);
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());

        InsightTaskView view = service.readForView(report(), readyVersion(), 10L, ViewKeySupport.ROOT);

        assertThat(view.insight()).isNotNull();
        assertThat(view.insight().limitations()).containsExactly("样本期为单月");
        assertThat(view.insight().actions()).hasSize(1);
        assertThat(view.insight().actions().get(0).capabilityId()).isEqualTo("attribution");
        // R5: claims/narrative/facts round-trip through persistence for the page evidence view.
        assertThat(view.insight().schemaVersion()).isEqualTo("2");
        assertThat(view.insight().claims()).hasSize(1);
        assertThat(view.insight().claims().get(0)).containsEntry("claim_id", "c1");
        assertThat(view.insight().narrative()).containsEntry("summary_claim_id", "c1");
        assertThat(view.insight().facts()).hasSize(1);
        assertThat(view.generation()).containsEntry("usage_source", "unknown");
    }

    @Test
    void ensureForView_registersAfterCommitSchedulingWhenTransactionActive() throws Exception {
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report());
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        RptInsightTask pending = task(RptInsightTask.PENDING, 0);
        pending.setId(60L);
        when(insightMapper.selectOne(any())).thenReturn(pending);
        when(insightMapper.selectById(60L)).thenReturn(pending);

        TransactionSynchronizationManager.initSynchronization();
        try {
            InsightTaskView view = service.ensureForView(ctx, 1L, 10L, source());

            assertThat(view.taskId()).isEqualTo(60L);
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            // 事务提交回调触发入队（真实提交前不提交执行器）
            syncs.get(0).afterCommit();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void retry_requeuesFailedTaskWithinSameVersion() throws Exception {
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report());
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        RptInsightTask failed = task(RptInsightTask.FAILED, 1);
        RptInsightTask reset = task(RptInsightTask.PENDING, 1);
        when(insightMapper.selectById(50L)).thenReturn(failed, reset);
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(insightMapper.update(any(), any())).thenReturn(1);

        InsightTaskView view = service.retry(ctx, 1L, 10L, 50L, source(), 1);

        assertThat(view.status()).isEqualTo(RptInsightTask.PENDING);
        verify(insightMapper).update(isNull(), any());
    }

    @Test
    void ensureForView_allowsFrozenContextAcrossDefinitionEdit() throws Exception {
        RptReport report = report();
        report.setDefinitionRevision(2);                 // 报表已编辑，当前修订号与版本不同
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report);
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        RptDataVersion version = readyVersion();
        version.setDefinitionRevision(1);
        version.setContextJson("{\"metricCode\":\"leave_count\"}");   // 版本冻结上下文
        when(dataVersionMapper.selectById(7L)).thenReturn(version);
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(chartService.parseMap(anyString())).thenReturn(Map.of());
        RptInsightTask existing = task(RptInsightTask.COMPLETE, 1);
        existing.setId(99L);
        when(insightMapper.selectOne(any())).thenReturn(existing);
        when(insightMapper.selectById(99L)).thenReturn(existing);

        InsightTaskView view = service.ensureForView(ctx, 1L, 10L, source());

        assertThat(view.taskId()).isEqualTo(99L);
        verifyNoInteractions(agentRuntime);
    }

    @Test
    void ensureForView_isolatesFullAndReportVisibilityDomains() throws Exception {
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report());
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        RptDataVersion version = readyVersion();
        version.setContextJson("{\"sourceAskId\":\"ask-1\",\"questionSummary\":\"研发中心近三月离职为何上升\"}");
        when(dataVersionMapper.selectById(7L)).thenReturn(version);
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(chartService.parseMap(anyString())).thenReturn(Map.of());

        // 来源用户仍被授权：完整上下文可见域（携带原问题摘要）。
        service.setAskSourceReader((access, askId) -> null);
        service.ensureForView(ctx, 1L, 10L, source());

        // 共享用户只有报表权限：撤权后落到仅报表上下文可见域，不得复用完整上下文任务。
        service.setAskSourceReader((access, askId) -> {
            throw new RuntimeException("forbidden");
        });
        service.ensureForView(ctx, 1L, 10L, source());

        ArgumentCaptor<RptInsightTask> inserted = ArgumentCaptor.forClass(RptInsightTask.class);
        verify(insightMapper, times(2)).insert(inserted.capture());
        RptInsightTask full = inserted.getAllValues().get(0);
        RptInsightTask report = inserted.getAllValues().get(1);
        assertThat(full.getContextVisibilityRef()).isNotNull();
        assertThat(report.getContextVisibilityRef()).isNotNull();
        assertThat(report.getContextVisibilityRef()).isNotEqualTo(full.getContextVisibilityRef());
        assertThat(full.getGenerationKey()).isNotEqualTo(report.getGenerationKey());
    }

    @Test
    void ensureForView_legacyVersionWithoutFrozenContext_rejectsChangedRevision() throws Exception {
        RptReport report = report();
        report.setDefinitionRevision(2);
        report.setContextJson("{\"metricCode\":\"leave_count\"}");
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report);
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        RptDataVersion version = readyVersion();          // 旧版本：无冻结上下文
        version.setDefinitionRevision(1);
        when(dataVersionMapper.selectById(7L)).thenReturn(version);
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());

        assertThatThrownBy(() -> service.ensureForView(ctx, 1L, 10L, source()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("CONTEXT_UNAVAILABLE");
        verifyNoInteractions(agentRuntime);
    }

    @Test
    void execute_persistsEnrichedActionsWithParams() throws Exception {
        when(insightMapper.selectById(50L))
                .thenReturn(task(RptInsightTask.PENDING, 1), task(RptInsightTask.RUNNING, 1));
        when(insightMapper.claim(50L)).thenReturn(1);
        when(reportService.loadReport(1L)).thenReturn(report());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(chartService.buildInsightRequest(any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new InsightRequest("r", "m", Map.of()));
        when(agentRuntime.generateInsight(any(), any())).thenReturn(Map.of(
                "status", "complete", "summary", "s",
                "data_version", "7", "view_key", ViewKeySupport.ROOT, "snapshot_id", "555",
                "next_actions", List.of(Map.of(
                        "action_id", "act-1", "capability_id", "leave_count_department_contribution",
                        "question", "下钻部门", "executable", false,
                        "params", Map.of("currentPeriod", Map.of("start", "2026-08-01")),
                        "missing_params", List.of("baseline_period"),
                        "execution_conditions", List.of("需要两个可比期间")))));
        when(insightMapper.finish(anyLong(), anyInt(), anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        service.execute(50L);

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(insightMapper).finish(eq(50L), eq(1), eq(RptInsightTask.COMPLETE),
                resultJson.capture(), anyString(), any(), any(), any());
        assertThat(resultJson.getValue()).contains("needs_confirmation")
                .contains("baseline_period").contains("act-1").contains("currentPeriod");
    }

    @Test
    void interruptOnStartup_marksUnfinishedTasksInterrupted() {
        when(insightMapper.update(isNull(), any())).thenReturn(2);

        service.interruptOnStartup();

        verify(insightMapper).update(isNull(), any());
    }

    @Test
    void shutdownExecutor_doesNotThrow() {
        service.shutdownExecutor();
    }
}
