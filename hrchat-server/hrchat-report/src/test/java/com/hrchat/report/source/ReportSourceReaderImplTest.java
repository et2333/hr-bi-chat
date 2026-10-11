package com.hrchat.report.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.ReportContext;
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
import static org.mockito.Mockito.when;

/**
 * REPORT 来源读取单测（I3）：归属越权、数据版本归属/就绪、上下文为空与成功恢复。
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

    private ObjectMapper objectMapper;
    private ReportSourceReaderImpl reader;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        reader = new ReportSourceReaderImpl(reportService, dataVersionMapper, objectMapper);
    }

    private AskSourceAccess access() {
        return new AskSourceAccess("t01", 1L, "hr01");
    }

    private RptReport ownedReport() {
        RptReport r = new RptReport();
        r.setId(REPORT_ID);
        r.setOwnerId(1L);
        r.setTenantId("t01");
        r.setIsDeleted(0);
        return r;
    }

    private RptDataVersion readyVersion(Long reportId) {
        RptDataVersion v = new RptDataVersion();
        v.setId(VERSION_ID);
        v.setReportId(reportId);
        v.setStatus(ReportDataVersionService.STATUS_READY);
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
    void missingReport_forbidden() {
        when(reportService.loadReport(REPORT_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    @Test
    void notOwner_forbidden() {
        RptReport other = ownedReport();
        other.setOwnerId(99L);
        when(reportService.loadReport(REPORT_ID)).thenReturn(other);

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    @Test
    void tenantMismatch_forbidden() {
        RptReport other = ownedReport();
        other.setTenantId("t99");
        when(reportService.loadReport(REPORT_ID)).thenReturn(other);

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    @Test
    void versionBelongsToOtherReport_paramInvalid() {
        when(reportService.loadReport(REPORT_ID)).thenReturn(ownedReport());
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(readyVersion(999L));

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void versionNotReady_paramInvalid() {
        when(reportService.loadReport(REPORT_ID)).thenReturn(ownedReport());
        RptDataVersion running = readyVersion(REPORT_ID);
        running.setStatus(ReportDataVersionService.STATUS_RUNNING);
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(running);

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void missingContextJson_paramInvalid() {
        RptReport report = ownedReport();
        report.setContextJson("   ");
        when(reportService.loadReport(REPORT_ID)).thenReturn(report);
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(readyVersion(REPORT_ID));

        BizException e = assertThrows(BizException.class,
                () -> reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void success_returnsPersistedContext() throws Exception {
        RptReport report = ownedReport();
        String json = contextJson();
        report.setContextJson(json);
        when(reportService.loadReport(REPORT_ID)).thenReturn(report);
        when(dataVersionMapper.selectById(VERSION_ID)).thenReturn(readyVersion(REPORT_ID));

        ReportContext actual = reader.readAuthorizedReport(access(), REPORT_ID, VERSION_ID);
        ReportContext expected = objectMapper.readValue(json, ReportContext.class);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.metricCode()).isEqualTo("leave_count");
        assertThat(actual.metricVersion()).isEqualTo(1);
        assertThat(actual.effectiveOrgIds()).containsExactly(2L, 3L);
        assertThat(actual.period().kind()).isEqualTo("FIXED");
        assertThat(actual.dimensions()).isEqualTo(List.of("org"));
        assertThat(actual.filters()).isEqualTo(Map.of());
        assertThat(actual.snapshotReusable()).isTrue();
    }
}
