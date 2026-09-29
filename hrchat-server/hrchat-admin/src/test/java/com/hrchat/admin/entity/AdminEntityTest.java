package com.hrchat.admin.entity;

import com.hrchat.admin.dto.AdminViews;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** admin 模块实体/视图 DTO round-trip。 */
class AdminEntityTest {

    @Test
    void itgDatasourceRoundTrip() {
        ItgDatasource d = new ItgDatasource();
        d.setId(1L);
        d.setDsCode("HR_EMP");
        d.setDsName("员工主数据");
        d.setSyncMode(1);
        d.setCredRef("kms:key1");
        d.setScheduleCron("0 0 2 * * ?");
        d.setStatus(1);
        assertThat(d.getDsCode()).isEqualTo("HR_EMP");
        assertThat(d.getSyncMode()).isEqualTo(1);
        assertThat(d.getCredRef()).isEqualTo("kms:key1");
        assertThat(d.getScheduleCron()).contains("2");
        assertThat(d.getStatus()).isEqualTo(1);
    }

    @Test
    void itgSyncTaskRoundTrip() {
        ItgSyncTask t = new ItgSyncTask();
        t.setId(3L);
        t.setDsId(1L);
        t.setTaskType(2);
        t.setBizDate(LocalDate.of(2026, 9, 27));
        t.setExecState(3);
        t.setRowsRead(1000L);
        t.setRowsWritten(990L);
        t.setFailReason("connection refused");
        t.setStartedAt(LocalDateTime.of(2026, 9, 27, 2, 0));
        t.setFinishedAt(LocalDateTime.of(2026, 9, 27, 2, 5));
        assertThat(t.getBizDate()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(t.getExecState()).isEqualTo(3);
        assertThat(t.getRowsRead()).isEqualTo(1000L);
        assertThat(t.getRowsWritten()).isEqualTo(990L);
        assertThat(t.getFailReason()).contains("refused");
        assertThat(t.getStartedAt()).isNotNull();
        assertThat(t.getFinishedAt()).isNotNull();
    }

    @Test
    void evlEvalQuestionRoundTrip() {
        EvlEvalQuestion q = new EvlEvalQuestion();
        q.setId(100L);
        q.setQuestion("本月离职人数");
        q.setSceneTag("simple");
        q.setExpectJson("{}");
        q.setLastResult(1);
        q.setSourceType(1);
        assertThat(q.getQuestion()).isEqualTo("本月离职人数");
        assertThat(q.getSceneTag()).isEqualTo("simple");
        assertThat(q.getExpectJson()).isEqualTo("{}");
        assertThat(q.getLastResult()).isEqualTo(1);
        assertThat(q.getSourceType()).isEqualTo(1);
    }

    @Test
    void datasourceViewRecord() {
        AdminViews.DatasourceView v = new AdminViews.DatasourceView("HR_EMP", "员工", "FULL", 1,
                "2026-09-27T00:00:00", "SUCCESS", "2026-09-27");
        assertThat(v.dsCode()).isEqualTo("HR_EMP");
        assertThat(v.syncMode()).isEqualTo("FULL");
        assertThat(v.lastSyncState()).isEqualTo("SUCCESS");
        assertThat(v.latestBizDate()).isEqualTo("2026-09-27");
    }

    @Test
    void syncJobViewRecord() {
        AdminViews.SyncJobView v = new AdminViews.SyncJobView(3L, "HR_EMP", "员工", "INCREMENT",
                "2026-09-27", "FAILED", 1000L, 990L, "err", "s", "f");
        assertThat(v.jobId()).isEqualTo(3L);
        assertThat(v.execState()).isEqualTo("FAILED");
        assertThat(v.failReason()).isEqualTo("err");
    }

    @Test
    void qualitySummaryRecord() {
        AdminViews.QualitySummary q = new AdminViews.QualitySummary("2026-09-27", 4, 2, 1, 1, 0, 66.67);
        assertThat(q.totalJobs()).isEqualTo(4);
        assertThat(q.success()).isEqualTo(2);
        assertThat(q.completeness()).isEqualTo(66.67);
    }

    @Test
    void questionSetCreateRequestRecord() {
        AdminViews.QuestionSetCreateRequest.QuestionSpec spec =
                new AdminViews.QuestionSetCreateRequest.QuestionSpec("本月离职人数", "simple", "{}");
        AdminViews.QuestionSetCreateRequest req =
                new AdminViews.QuestionSetCreateRequest("集", List.of(spec));
        assertThat(req.name()).isEqualTo("集");
        assertThat(req.questions().get(0).question()).isEqualTo("本月离职人数");
        assertThat(req.questions().get(0).sceneTag()).isEqualTo("simple");
    }

    @Test
    void fieldPolicyRequestRecord() {
        AdminViews.FieldPolicyRequest.FieldPolicy p =
                new AdminViews.FieldPolicyRequest.FieldPolicy("salary.gross_pay", "salary", 2, 5, true);
        AdminViews.FieldPolicyRequest req = new AdminViews.FieldPolicyRequest(List.of(p));
        assertThat(req.policies().get(0).fieldCode()).isEqualTo("salary.gross_pay");
        assertThat(req.policies().get(0).policyType()).isEqualTo(2);
        assertThat(req.policies().get(0).approvalRequired()).isTrue();
    }

    @Test
    void dataScopeRequestRecord() {
        AdminViews.DataScopeRequest req = new AdminViews.DataScopeRequest(List.of(35L, 41L), 2);
        assertThat(req.orgIds()).containsExactly(35L, 41L);
        assertThat(req.grantScope()).isEqualTo(2);
    }

    @Test
    void runResultViewRecord() {
        AdminViews.RunResultView r = new AdminViews.RunResultView("run0001", "COMPLETED", 90.0, 88.0,
                9, 10, List.of("q9"));
        assertThat(r.accuracy()).isEqualTo(90.0);
        assertThat(r.passed()).isEqualTo(9);
        assertThat(r.failedQuestions()).containsExactly("q9");
    }

    @Test
    void effectivePermissionsViewRecord() {
        AdminViews.EffectivePermissionsView.DataScope ds =
                new AdminViews.EffectivePermissionsView.DataScope(35L, "研发中心", 3, 2);
        AdminViews.EffectivePermissionsView v = new AdminViews.EffectivePermissionsView(
                "7", List.of("HRBP"), List.of(ds), List.of("salary:MASKED"),
                List.of("chat:ask"), "2026-09-28T00:00:00");
        assertThat(v.userId()).isEqualTo("7");
        assertThat(v.dataScopes().get(0).orgName()).isEqualTo("研发中心");
        assertThat(v.functionPerms()).containsExactly("chat:ask");
    }
}
