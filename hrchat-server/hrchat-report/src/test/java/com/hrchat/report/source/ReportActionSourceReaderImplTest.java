package com.hrchat.report.source;

import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.ReportActionSource;
import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.chart.ChartViews.InsightAction;
import com.hrchat.report.chart.ChartViews.InsightPoint;
import com.hrchat.report.chart.ChartViews.InsightTaskView;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptDataVersionMapper;
import com.hrchat.report.service.InsightTaskService;
import com.hrchat.report.service.ReportChartService;
import com.hrchat.report.service.ReportChartService.ViewScope;
import com.hrchat.report.service.ReportDataVersionService;
import com.hrchat.report.service.ReportService;
import com.hrchat.report.service.ViewKeySupport;
import com.hrchat.report.service.ViewOrgScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 报表动作来源读取单测（A2）：动作必须属于该视图持久化洞察；期间/组织由服务端恢复；
 * 组织下钻按授权组织名收窄且绝不扩大到报表授权范围之外。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportActionSourceReaderImplTest {

    private static final Long REPORT_ID = 7L;
    private static final Long VERSION_ID = 88L;
    private static final Long COMPONENT_ID = 9L;
    private static final String ACTION_ID = "act-1";

    @Mock private ReportService reportService;
    @Mock private RptDataVersionMapper dataVersionMapper;
    @Mock private UserContextService userContextService;
    @Mock private InsightTaskService insightTaskService;
    @Mock private ReportChartService chartService;
    @Mock private SecOrgNodeMapper orgNodeMapper;

    private ReportActionSourceReaderImpl reader;

    @BeforeEach
    void setUp() {
        reader = new ReportActionSourceReaderImpl(reportService, dataVersionMapper, userContextService,
                insightTaskService, chartService, new ViewOrgScopeResolver(orgNodeMapper));
        when(userContextService.resolve(anyString(), anyString(), any())).thenReturn(user("研发中心", List.of(2L, 3L, 4L)));
        when(reportService.requireAccessibleReport(any(), anyLong())).thenReturn(new RptReport());
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(readyVersion());
        // B1 权威组织层级：研发中心 /1/2/ 下含研发二部 /1/2/3/ 与研发一部 /1/2/4/。
        when(orgNodeMapper.selectList(any())).thenReturn(List.of(
                orgNode(2L, "研发中心", "/1/2/"), orgNode(3L, "研发二部", "/1/2/3/"), orgNode(4L, "研发一部", "/1/2/4/")));
    }

    private static SecOrgNode orgNode(Long id, String name, String path) {
        SecOrgNode node = new SecOrgNode();
        node.setId(id);
        node.setOrgName(name);
        node.setOrgPath(path);
        node.setTenantId("t01");
        return node;
    }

    private AskSourceAccess access() {
        return new AskSourceAccess("t01", 1L, "hr01");
    }

    private RptDataVersion readyVersion() {
        RptDataVersion v = new RptDataVersion();
        v.setId(VERSION_ID);
        v.setReportId(REPORT_ID);
        v.setStatus(ReportDataVersionService.STATUS_READY);
        return v;
    }

    private void givenViewInsight(String viewKey, String actionId) {
        InsightView insight = new InsightView("报表", "离职人数", "摘要",
                List.of(new InsightPoint("finding", "要点")), "COMPLETE", null, "7", viewKey, List.of(),
                List.of(new InsightAction(actionId, "leave_count_department_contribution", "查看部门贡献", true)));
        InsightTaskView task = new InsightTaskView(1L, 1, "COMPLETE", "7", viewKey, "scope", "rev",
                null, null, null, insight, Map.of());
        when(insightTaskService.readForView(any(), any(), eq(COMPONENT_ID), anyString(), any())).thenReturn(task);
    }

    private void givenScope(List<Long> orgs) {
        when(chartService.resolveViewScope(any(), any(), eq(COMPONENT_ID), anyString()))
                .thenReturn(new ViewScope("leave_count", "离职人数", "人", 1, orgs,
                        new InsightRequest.Period("2026-09-01", "2026-10-01"),
                        new InsightRequest.Period("2026-08-01", "2026-09-01"), "研发中心"));
    }

    @Test
    void missingReference_paramMissing() {
        BizException e = assertThrows(BizException.class, () -> reader.readAuthorizedAction(
                access(), REPORT_ID, VERSION_ID, COMPONENT_ID, ViewKeySupport.ROOT, null));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_MISSING);
    }

    @Test
    void actionNotInViewInsight_paramInvalid() {
        givenViewInsight(ViewKeySupport.ROOT, "other-action");

        BizException e = assertThrows(BizException.class, () -> reader.readAuthorizedAction(
                access(), REPORT_ID, VERSION_ID, COMPONENT_ID, ViewKeySupport.ROOT, ACTION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void rootView_restoresServerDerivedPeriodAndOrgs() {
        givenViewInsight(ViewKeySupport.ROOT, ACTION_ID);
        givenScope(List.of(2L, 3L, 4L));

        ReportActionSource src = reader.readAuthorizedAction(
                access(), REPORT_ID, VERSION_ID, COMPONENT_ID, ViewKeySupport.ROOT, ACTION_ID);

        assertThat(src.reportId()).isEqualTo(REPORT_ID);
        assertThat(src.dataVersionId()).isEqualTo(VERSION_ID);
        assertThat(src.componentId()).isEqualTo(COMPONENT_ID);
        assertThat(src.viewKey()).isEqualTo(ViewKeySupport.ROOT);
        assertThat(src.actionId()).isEqualTo(ACTION_ID);
        assertThat(src.metricCode()).isEqualTo("leave_count");
        assertThat(src.effectiveOrgIds()).containsExactly(2L, 3L, 4L);
        assertThat(src.currentPeriod().start()).isEqualTo("2026-09-01");
        assertThat(src.baselinePeriod().end()).isEqualTo("2026-09-01");
    }

    @Test
    void drillView_narrowsOrgsToGrantedSubtree() {
        String viewKey = ViewKeySupport.forOrgDrill("研发中心");
        givenViewInsight(viewKey, ACTION_ID);
        givenScope(List.of(2L, 3L, 4L, 5L));

        ReportActionSource src = reader.readAuthorizedAction(
                access(), REPORT_ID, VERSION_ID, COMPONENT_ID, viewKey, ACTION_ID);

        assertThat(src.viewKey()).isEqualTo(viewKey);
        assertThat(src.effectiveOrgIds()).containsExactly(2L, 3L, 4L);
    }

    @Test
    void orgsOutsideGrant_forbidden() {
        givenViewInsight(ViewKeySupport.ROOT, ACTION_ID);
        givenScope(List.of(2L, 99L));

        BizException e = assertThrows(BizException.class, () -> reader.readAuthorizedAction(
                access(), REPORT_ID, VERSION_ID, COMPONENT_ID, ViewKeySupport.ROOT, ACTION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    /** B1：只授权父节点「研发中心」、点击子节点「研发一部」时，不得静默回退父范围 [2,3,4]。 */
    @Test
    void childDrillMustNotSilentlyReturnParentScope() {
        String viewKey = ViewKeySupport.forOrgDrill("研发一部");
        givenViewInsight(viewKey, ACTION_ID);
        givenScope(List.of(2L, 3L, 4L));

        ReportActionSource src = reader.readAuthorizedAction(
                access(), REPORT_ID, VERSION_ID, COMPONENT_ID, viewKey, ACTION_ID);

        assertThat(src.effectiveOrgIds()).containsExactly(4L);
    }

    /** B1：无法按权威层级解析的下钻视图必须明确拒绝，不能退回父范围。 */
    @Test
    void unresolvableDrillView_isRejected() {
        String viewKey = ViewKeySupport.forOrgDrill("幽灵部门");
        givenViewInsight(viewKey, ACTION_ID);
        givenScope(List.of(2L, 3L, 4L));

        BizException e = assertThrows(BizException.class, () -> reader.readAuthorizedAction(
                access(), REPORT_ID, VERSION_ID, COMPONENT_ID, viewKey, ACTION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    private static UserContext user(String orgName, List<Long> subtree) {
        return UserContext.builder()
                .userId(1L).empNo("hr01").tenantId("t01").dataLevel(1)
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(2L).orgCode("RD").orgName(orgName).orgPath("/1/2/")
                        .scope(2).subtreeOrgKeys(subtree).build()))
                .permissionFingerprint("fp-hr01").build();
    }
}
