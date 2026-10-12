package com.hrchat.report.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.ReportContext;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptDataVersionMapper;
import com.hrchat.report.service.ReportDataVersionService;
import com.hrchat.report.service.ReportService;
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
import static org.mockito.Mockito.when;

/**
 * REPORT 来源读取单测（I3/R3）：统一访问裁决、数据版本归属/就绪、
 * 冻结上下文优先、旧版本同修订号回退与不可恢复边界。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportSourceReaderImplTest {

    private static final Long REPORT_ID = 7L;
    private static final Long VERSION_ID = 88L;

    @Mock
    private ReportService reportService;
    @Mock
    private RptDataVersionMapper dataVersionMapper;
    @Mock
    private UserContextService userContextService;

    private ObjectMapper objectMapper;
    private ReportSourceReaderImpl reader;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        reader = new ReportSourceReaderImpl(reportService, dataVersionMapper, userContextService, objectMapper);
        when(userContextService.resolve(anyString(), anyString(), any()))
                .thenReturn(UserContext.builder().userId(1L).empNo("hr01").tenantId("t01").build());
        when(reportService.requireAccessibleReport(any(), anyLong())).thenReturn(accessibleReport());
    }

    private AskSourceAccess access() {
        return new AskSourceAccess("t01", 1L, "hr01");
    }

    private RptReport accessibleReport() {
        RptReport r = new RptReport();
        r.setId(REPORT_ID);
        r.setOwnerId(1L);
        r.setTenantId("t01");
        r.setIsDeleted(0);
        r.setDefinitionRevision(1);
        return r;
    }

    private RptDataVersion readyVersion(Long reportId) {
        RptDataVersion v = new RptDataVersion();
        v.setId(VERSION_ID);
        v.setReportId(reportId);
        v.setStatus(ReportDataVersionService.STATUS_READY);
        v.setDefinitionRevision(1);
        return v;
    }

    private String contextJson() {
        return """
                {"schemaVersion":"1","sourceType":"ASK","sourceAskId":"ask-1","sourceTurnId":11,
                 "questionSummary":"8月离职人数","metricCode":"leave_count","metricVersion":1,
                 "metricName":"离职人数","unit":"人","queryMode":"breakdown","dimensions":["org"],
                 "period":{"kind":"FIXED","start":"2026-08-01","endExclusive":"2026-09-01",
                   "rollingRule":null,"rollingParam":null,"resolvedStart":"2026-08-01",
                   "resolvedEndExclusive":"2026-09-01","displayLabel":"2026-08-01/2026-09-01"},
                 "organizationLabel":"研发中心","effectiveOrgIds":[2,3],"filters":{},
                 "contextCompleteness":"complete","snapshotReusable":true,"caliberDefinition":"COUNT"}
                """;
    }

    @Test
    void missingAccess_forbidden() {
        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(null, REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    @Test
    void resolvedUserMismatch_forbidden() {
        when(userContextService.resolve(anyString(), anyString(), any()))
                .thenReturn(UserContext.builder().userId(999L).empNo("hr01").tenantId("t01").build());

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    @Test
    void notAccessible_forbidden() {
        when(reportService.requireAccessibleReport(any(), anyLong()))
                .thenThrow(new BizException(ErrorCode.FUNC_FORBIDDEN));

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    @Test
    void versionBelongsToOtherReport_paramInvalid() {
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(readyVersion(999L));

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void versionNotReady_paramInvalid() {
        RptDataVersion running = readyVersion(REPORT_ID);
        running.setStatus(ReportDataVersionService.STATUS_RUNNING);
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(running);

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void frozenContextSurvivesDefinitionEdit() throws Exception {
        String json = contextJson();
        RptReport report = accessibleReport();
        report.setDefinitionRevision(2);          // 报表已编辑，当前修订号不同
        report.setContextJson("{\"metricCode\":\"other\"}");
        when(reportService.requireAccessibleReport(any(), anyLong())).thenReturn(report);
        RptDataVersion version = readyVersion(REPORT_ID);
        version.setDefinitionRevision(1);
        version.setContextJson(json);             // 版本自身的冻结上下文
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(version);

        ReportContext actual = reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID);

        assertThat(actual).isEqualTo(objectMapper.readValue(json, ReportContext.class));
        assertThat(actual.metricCode()).isEqualTo("leave_count");
        assertThat(actual.period().kind()).isEqualTo("FIXED");
        assertThat(actual.effectiveOrgIds()).containsExactly(2L, 3L);
        assertThat(actual.dimensions()).isEqualTo(List.of("org"));
        assertThat(actual.filters()).isEqualTo(Map.of());
    }

    @Test
    void legacyVersionWithoutFrozenContext_fallsBackWhenSameRevision() throws Exception {
        String json = contextJson();
        RptReport report = accessibleReport();
        report.setDefinitionRevision(1);
        report.setContextJson(json);
        when(reportService.requireAccessibleReport(any(), anyLong())).thenReturn(report);
        RptDataVersion version = readyVersion(REPORT_ID);
        version.setDefinitionRevision(1);
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(version);

        ReportContext actual = reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID);

        assertThat(actual).isEqualTo(objectMapper.readValue(json, ReportContext.class));
    }

    @Test
    void legacyVersionWithoutFrozenContext_changedRevision_paramInvalid() {
        RptReport report = accessibleReport();
        report.setDefinitionRevision(2);
        report.setContextJson(contextJson());
        when(reportService.requireAccessibleReport(any(), anyLong())).thenReturn(report);
        RptDataVersion version = readyVersion(REPORT_ID);
        version.setDefinitionRevision(1);
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(version);

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
