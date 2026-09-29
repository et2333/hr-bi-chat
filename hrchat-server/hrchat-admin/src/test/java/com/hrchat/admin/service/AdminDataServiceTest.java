package com.hrchat.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.entity.ItgSyncTask;
import com.hrchat.admin.mapper.ItgDatasourceMapper;
import com.hrchat.admin.mapper.ItgSyncTaskMapper;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据源与同步单测（S5）：任务重试、数据质量摘要、同步任务过滤。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDataServiceTest {

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

    @Test
    void retryJob_setsRetryingAndAudits() {
        ItgSyncTask task = new ItgSyncTask();
        task.setId(3L);
        task.setDsId(1L);
        task.setExecState(3);
        task.setFailReason("connection refused");
        when(syncTaskMapper.selectById(3L)).thenReturn(task);

        service.retryJob(3L, UserContext.builder().empNo("hr99").build());

        verify(syncTaskMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                t -> t.getExecState() == 4 && t.getFailReason() == null));
        ArgumentCaptor<com.hrchat.audit.model.AuditEvent> captor =
                ArgumentCaptor.forClass(com.hrchat.audit.model.AuditEvent.class);
        verify(auditCollector).record(captor.capture());
        assertEquals(AuditEvents.SYNC_RETRY, captor.getValue().eventType());
        assertEquals("hr99", captor.getValue().userNo());
    }

    @Test
    void qualitySummary_computesCompleteness() {
        ItgSyncTask s1 = new ItgSyncTask();
        s1.setBizDate(LocalDate.of(2026, 9, 27));
        s1.setExecState(2);
        ItgSyncTask s2 = new ItgSyncTask();
        s2.setBizDate(LocalDate.of(2026, 9, 27));
        s2.setExecState(2);
        ItgSyncTask s3 = new ItgSyncTask();
        s3.setBizDate(LocalDate.of(2026, 9, 27));
        s3.setExecState(3);
        ItgSyncTask s4 = new ItgSyncTask();
        s4.setBizDate(LocalDate.of(2026, 9, 27));
        s4.setExecState(0);
        when(syncTaskMapper.selectList(any())).thenReturn(List.of(s1, s2, s3, s4));

        AdminViews.QualitySummary summary = service.qualitySummary(LocalDate.of(2026, 9, 27));

        assertEquals(4, summary.totalJobs());
        assertEquals(2, summary.success());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.delayed());
        assertEquals(66.67, summary.completeness());
    }
}
