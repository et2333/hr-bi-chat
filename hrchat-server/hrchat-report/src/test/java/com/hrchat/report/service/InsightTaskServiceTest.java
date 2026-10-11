package com.hrchat.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartSeries;
import com.hrchat.report.chart.ChartViews.InsightAction;
import com.hrchat.report.chart.ChartViews.InsightPoint;
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
import static org.mockito.Mockito.never;
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

    @Test
    void execute_claimsOnce_thenPersistsCompleteResult() throws Exception {
        when(insightMapper.selectById(50L))
                .thenReturn(task(RptInsightTask.PENDING, 0), task(RptInsightTask.RUNNING, 1));
        when(insightMapper.claim(50L)).thenReturn(1);
        when(reportService.loadReport(1L)).thenReturn(report());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(chartService.buildInsightRequest(any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new InsightRequest("人力编制报表", "在职人数", Map.of()));
        when(agentRuntime.generateInsight(any())).thenReturn(Map.of(
                "status", "complete", "summary", "均值 10",
                "points", List.of(Map.of("type", "value", "label", "在职人数 10 人"))));
        when(insightMapper.finish(anyLong(), anyInt(), anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        service.execute(50L);

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(insightMapper).finish(eq(50L), eq(1), eq(RptInsightTask.COMPLETE),
                resultJson.capture(), anyString(), any(), any(), any());
        assertThat(resultJson.getValue()).contains("均值 10");
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
                .thenReturn(task(RptInsightTask.PENDING, 0), task(RptInsightTask.RUNNING, 1));
        when(insightMapper.claim(50L)).thenReturn(1);
        when(reportService.loadReport(1L)).thenReturn(report());
        when(dataVersionMapper.selectById(7L)).thenReturn(readyVersion());
        when(viewSnapshotMapper.selectOne(any())).thenReturn(snapshot());
        when(chartService.buildInsightRequest(any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new InsightRequest("r", "m", Map.of()));
        when(agentRuntime.generateInsight(any())).thenReturn(Map.of("status", "complete", "summary", "s"));
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

        InsightTaskView view = service.ensureForView(ctx, 1L, 10L, null);

        assertThat(view.taskId()).isEqualTo(99L);
        assertThat(view.status()).isEqualTo(RptInsightTask.COMPLETE);
        verify(insightMapper, never()).insert(any());
        verifyNoInteractions(agentRuntime);
    }

    @Test
    void retry_rejectsRunningTask() {
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report());
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        when(insightMapper.selectById(50L)).thenReturn(task(RptInsightTask.RUNNING, 1));

        assertThatThrownBy(() -> service.retry(ctx, 1L, 10L, 50L))
                .isInstanceOf(BizException.class);
        verify(insightMapper, never()).update(any(), any());
    }

    @Test
    void retry_rejectsCompletedTask() {
        when(reportService.requireAccessibleReport(any(), eq(1L))).thenReturn(report());
        when(componentMapper.selectOne(any())).thenReturn(chartComponent());
        when(insightMapper.selectById(50L)).thenReturn(task(RptInsightTask.COMPLETE, 1));

        assertThatThrownBy(() -> service.retry(ctx, 1L, 10L, 50L))
                .isInstanceOf(BizException.class);
    }

    @Test
    void readForView_roundTripsRichInsightViewJson() throws Exception {
        InsightView rich = new InsightView("人力编制报表", "在职人数", "总体平稳",
                List.of(new InsightPoint("value", "在职人数 10 人")),
                "complete", null, "7", ViewKeySupport.ROOT,
                List.of("样本期为单月"),
                List.of(new InsightAction("a1", "attribution", "分析净变化来自哪个部门", true)));
        RptInsightTask stored = task(RptInsightTask.COMPLETE, 1);
        stored.setResultJson(objectMapper.writeValueAsString(rich));
        stored.setGenerationJson("{\"usage_source\":\"unknown\"}");
        when(insightMapper.selectOne(any())).thenReturn(stored);

        InsightTaskView view = service.readForView(report(), readyVersion(), 10L, ViewKeySupport.ROOT);

        assertThat(view.insight()).isNotNull();
        assertThat(view.insight().limitations()).containsExactly("样本期为单月");
        assertThat(view.insight().actions()).hasSize(1);
        assertThat(view.insight().actions().get(0).capabilityId()).isEqualTo("attribution");
        assertThat(view.generation()).containsEntry("usage_source", "unknown");
    }
}
