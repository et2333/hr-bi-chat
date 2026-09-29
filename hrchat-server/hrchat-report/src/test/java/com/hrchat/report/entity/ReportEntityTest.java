package com.hrchat.report.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** report 模块实体/视图 DTO round-trip。 */
class ReportEntityTest {

    @Test
    void rptSnapshotRoundTrip() {
        RptSnapshot s = new RptSnapshot();
        s.setId(1L);
        s.setReportId(7L);
        s.setSubId(3L);
        s.setFileKey("snap/2026-08.json");
        s.setGeneratedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        s.setExpireAt(LocalDateTime.of(2027, 8, 1, 10, 0));
        assertThat(s.getId()).isEqualTo(1L);
        assertThat(s.getReportId()).isEqualTo(7L);
        assertThat(s.getSubId()).isEqualTo(3L);
        assertThat(s.getFileKey()).isEqualTo("snap/2026-08.json");
        assertThat(s.getGeneratedAt()).isNotNull();
        assertThat(s.getExpireAt()).isNotNull();
    }

    @Test
    void rptSubReceiverRoundTrip() {
        RptSubReceiver r = new RptSubReceiver();
        r.setId(2L);
        r.setSubscriptionId(9L);
        r.setUserId(12L);
        r.setPushStatus(1);
        assertThat(r.getId()).isEqualTo(2L);
        assertThat(r.getSubscriptionId()).isEqualTo(9L);
        assertThat(r.getUserId()).isEqualTo(12L);
        assertThat(r.getPushStatus()).isEqualTo(1);
    }

    @Test
    void rptSubscriptionRoundTrip() {
        RptSubscription s = new RptSubscription();
        s.setId(5L);
        s.setReportId(7L);
        s.setFreq(2);
        s.setChannel(1);
        s.setNextRunAt(LocalDateTime.now());
        s.setStatus(1);
        s.setPermSnapshotJson("{}");
        s.setIsDeleted(0);
        s.setCreatedBy("hr01");
        s.setUpdatedBy("hr01");
        assertThat(s.getFreq()).isEqualTo(2);
        assertThat(s.getChannel()).isEqualTo(1);
        assertThat(s.getStatus()).isEqualTo(1);
        assertThat(s.getIsDeleted()).isZero();
        assertThat(s.getPermSnapshotJson()).isEqualTo("{}");
    }

    @Test
    void rptReportRoundTrip() {
        RptReport r = new RptReport();
        r.setId(11L);
        r.setReportName("月度人力月报");
        r.setOwnerId(1L);
        r.setSourceType(2);
        r.setTemplateId(99L);
        r.setParamsJson("{\"org_id\":1}");
        r.setStatus(1);
        r.setMetricVersions("v1");
        r.setIsDeleted(0);
        assertThat(r.getReportName()).isEqualTo("月度人力月报");
        assertThat(r.getOwnerId()).isEqualTo(1L);
        assertThat(r.getSourceType()).isEqualTo(2);
        assertThat(r.getTemplateId()).isEqualTo(99L);
        assertThat(r.getStatus()).isEqualTo(1);
        assertThat(r.getMetricVersions()).isEqualTo("v1");
    }

    @Test
    void rptComponentRoundTrip() {
        RptComponent c = new RptComponent();
        c.setId(3L);
        c.setReportId(7L);
        c.setCompType(1);
        c.setChartType("bar");
        c.setDefJson("{\"metrics\":[\"headcount\"]}");
        c.setSortNo(1);
        assertThat(c.getCompType()).isEqualTo(1);
        assertThat(c.getChartType()).isEqualTo("bar");
        assertThat(c.getDefJson()).contains("headcount");
        assertThat(c.getSortNo()).isEqualTo(1);
    }

    @Test
    void snapshotViewRecord() {
        com.hrchat.report.dto.ReportViews.SnapshotView v =
                new com.hrchat.report.dto.ReportViews.SnapshotView(1L, 7L, 3L,
                        "snap/x.json", "2026-08-01T10:00:00", "2027-08-01T10:00:00");
        assertThat(v.id()).isEqualTo(1L);
        assertThat(v.reportId()).isEqualTo(7L);
        assertThat(v.subId()).isEqualTo(3L);
        assertThat(v.fileKey()).contains("x.json");
        assertThat(v.generatedAt()).startsWith("2026");
        assertThat(v.expireAt()).isNotNull();
    }

    @Test
    void templateDetailRecord() {
        com.hrchat.report.dto.ReportViews.TemplateDetail d =
                new com.hrchat.report.dto.ReportViews.TemplateDetail("tpl-1", "x", "组织",
                        "desc", Map.of("org_id", "必填"));
        assertThat(d.id()).isEqualTo("tpl-1");
        assertThat(d.name()).isEqualTo("x");
        assertThat(d.category()).isEqualTo("组织");
        assertThat(d.description()).isEqualTo("desc");
        assertThat(d.paramsSchema()).containsKey("org_id");
    }

    @Test
    void exportCreateRequestRecord() {
        com.hrchat.report.dto.ReportDtos.ExportCreateRequest r =
                new com.hrchat.report.dto.ReportDtos.ExportCreateRequest("CSV",
                        Map.of("row_count", 10));
        assertThat(r.format()).isEqualTo("CSV");
        assertThat(r.filters()).containsEntry("row_count", 10);
    }

    @Test
    void refreshRecord() {
        com.hrchat.report.dto.ReportDtos.Refresh rf =
                new com.hrchat.report.dto.ReportDtos.Refresh("DAILY", "09:00");
        assertThat(rf.frequency()).isEqualTo("DAILY");
        assertThat(rf.time()).isEqualTo("09:00");
    }
}
