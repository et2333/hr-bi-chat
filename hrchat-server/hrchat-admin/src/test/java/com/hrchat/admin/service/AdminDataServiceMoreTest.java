package com.hrchat.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.entity.ItgDatasource;
import com.hrchat.admin.entity.ItgSyncTask;
import com.hrchat.admin.mapper.ItgDatasourceMapper;
import com.hrchat.admin.mapper.ItgSyncTaskMapper;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AdminDataService 补测（S8c）：数据源视图/同步任务过滤/质量摘要缺省日期/重试异常。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDataServiceMoreTest {

    @Mock
    private ItgDatasourceMapper datasourceMapper;
    @Mock
    private ItgSyncTaskMapper syncTaskMapper;
    @Mock
    private AuditCollector auditCollector;

    private AdminDataService service;

    @BeforeEach
    void setUp() {
        service = new AdminDataService(datasourceMapper, syncTaskMapper, auditCollector,
                new ObjectMapper());
    }

    private ItgDatasource ds(long id, String code, Integer syncMode, Integer status) {
        ItgDatasource d = new ItgDatasource();
        d.setId(id);
        d.setDsCode(code);
        d.setDsName(code);
        d.setSyncMode(syncMode);
        d.setStatus(status);
        return d;
    }

    private ItgSyncTask task(long id, long dsId, LocalDate bizDate, Integer execState, Integer taskType) {
        ItgSyncTask t = new ItgSyncTask();
        t.setId(id);
        t.setDsId(dsId);
        t.setBizDate(bizDate);
        t.setExecState(execState);
        t.setTaskType(taskType);
        return t;
    }

    @Test
    void listDatasources_withLatestTask() {
        when(datasourceMapper.selectList(null)).thenReturn(List.of(ds(1L, "HR_EMP", 1, 1)));
        ItgSyncTask t = task(5L, 1L, LocalDate.of(2026, 9, 27), 2, 1);
        when(syncTaskMapper.selectList(any())).thenReturn(List.of(t));

        List<AdminViews.DatasourceView> views = service.listDatasources();
        assertThat(views).hasSize(1);
        assertThat(views.get(0).syncMode()).isEqualTo("FULL");
        assertThat(views.get(0).lastSyncState()).isEqualTo("SUCCESS");
        assertThat(views.get(0).latestBizDate()).isEqualTo("2026-09-27");
    }

    @Test
    void listDatasources_nullTask() {
        when(datasourceMapper.selectList(null)).thenReturn(List.of(ds(2L, "HR_ATT", 3, 0)));
        when(syncTaskMapper.selectList(any())).thenReturn(List.of());
        List<AdminViews.DatasourceView> views = service.listDatasources();
        assertThat(views.get(0).syncMode()).isEqualTo("CDC");
        assertThat(views.get(0).lastSyncAt()).isNull();
    }

    @Test
    void listDatasources_unknownSyncMode() {
        when(datasourceMapper.selectList(null)).thenReturn(List.of(ds(3L, "X", 9, 1)));
        when(syncTaskMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listDatasources().get(0).syncMode()).isEqualTo("UNKNOWN");
    }

    @Test
    void listSyncJobs_withFilters() {
        ItgSyncTask t = task(3L, 1L, LocalDate.of(2026, 9, 27), 3, 2);
        when(syncTaskMapper.selectList(any())).thenReturn(List.of(t));
        when(datasourceMapper.selectById(1L)).thenReturn(ds(1L, "HR_EMP", 1, 1));

        var page = service.listSyncJobs(LocalDate.of(2026, 9, 27), 3, 1, 20);
        assertThat(page.getRecords()).hasSize(1);
        AdminViews.SyncJobView v = page.getRecords().get(0);
        assertThat(v.taskType()).isEqualTo("INCREMENT");
        assertThat(v.execState()).isEqualTo("FAILED");
        assertThat(v.dsCode()).isEqualTo("HR_EMP");
    }

    @Test
    void listSyncJobs_dsNotFound_fallsBackToId() {
        ItgSyncTask t = task(4L, 9L, LocalDate.of(2026, 9, 27), 1, 3);
        when(syncTaskMapper.selectList(any())).thenReturn(List.of(t));
        when(datasourceMapper.selectById(9L)).thenReturn(null);

        var page = service.listSyncJobs(null, null, 1, 20);
        AdminViews.SyncJobView v = page.getRecords().get(0);
        assertThat(v.dsCode()).isEqualTo("9");
        assertThat(v.dsName()).isEmpty();
        assertThat(v.taskType()).isEqualTo("DIMENSION");
        assertThat(v.execState()).isEqualTo("RUNNING");
        assertThat(v.startedAt()).isNull();
    }

    @Test
    void retryJob_unknownJob_invalid() {
        when(syncTaskMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.retryJob(9L, UserContext.builder().empNo("hr99").build()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void qualitySummary_noDate_usesLatest() {
        ItgSyncTask t = task(1L, 1L, LocalDate.of(2026, 9, 26), 2, 1);
        when(syncTaskMapper.selectList(any())).thenReturn(List.of(t));
        AdminViews.QualitySummary q = service.qualitySummary(null);
        assertThat(q.bizDate()).isEqualTo("2026-09-26");
    }

    @Test
    void qualitySummary_noTasksAtAll() {
        when(syncTaskMapper.selectList(any())).thenReturn(List.of());
        AdminViews.QualitySummary q = service.qualitySummary(null);
        assertThat(q.totalJobs()).isZero();
        assertThat(q.completeness()).isZero();
    }

    @Test
    void qualitySummary_zeroCompleted_completenessZero() {
        ItgSyncTask t = task(1L, 1L, LocalDate.of(2026, 9, 26), 0, 1);
        when(syncTaskMapper.selectList(any())).thenReturn(List.of(t));
        AdminViews.QualitySummary q = service.qualitySummary(LocalDate.of(2026, 9, 26));
        assertThat(q.delayed()).isEqualTo(1);
        assertThat(q.completeness()).isZero();
    }

    @Test
    void retryJob_recordsAudit() {
        ItgSyncTask t = task(3L, 1L, LocalDate.of(2026, 9, 27), 3, 1);
        when(syncTaskMapper.selectById(3L)).thenReturn(t);
        service.retryJob(3L, UserContext.builder().empNo("hr99").build());
        verify(syncTaskMapper).updateById(any());
        verify(auditCollector).record(any());
    }
}
