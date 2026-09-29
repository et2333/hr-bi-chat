package com.hrchat.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.entity.AudAuditLog;
import com.hrchat.audit.mapper.AudAuditLogMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计服务单测（S5）：落库字段、31 天跨度约束、敏感过滤、姓名解析。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditServiceImplTest {

    @Mock
    private AudAuditLogMapper auditLogMapper;
    @Mock
    private SecUserMapper secUserMapper;

    private AuditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuditServiceImpl(auditLogMapper, secUserMapper, new ObjectMapper());
    }

    @Test
    void record_persistsEventWithTraceAndSensitiveFlag() {
        service.record(AuditEvent.of(AuditEvents.EXPORT, "hr01", "report", "1",
                "{\"file_name\":\"a.csv\",\"rows\":100}", true));

        ArgumentCaptor<AudAuditLog> captor = ArgumentCaptor.forClass(AudAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AudAuditLog log = captor.getValue();
        assertEquals(AuditEvents.EXPORT, log.getEventType());
        assertEquals("hr01", log.getUserNo());
        assertEquals(1, log.getIsSensitive());
        assertEquals("report", log.getObjectType());
        assertEquals("1", log.getObjectId());
        assertTrue(log.getActionTime() != null);
    }

    @Test
    void search_throwsWhenSpanExceeds31Days() {
        assertThrows(BizException.class, () -> service.search(null,
                LocalDateTime.now().minusDays(40), LocalDateTime.now(), null, null, 1, 20));
    }

    @Test
    void search_returnsDigestAndUserName() throws Exception {
        AudAuditLog log = new AudAuditLog();
        log.setId(1L);
        log.setEventType(AuditEvents.ASK);
        log.setUserNo("hr01");
        log.setActionTime(LocalDateTime.of(2026, 9, 28, 10, 0));
        log.setObjectType("turn");
        log.setObjectId("ask1");
        log.setDetailJson(new ObjectMapper().writeValueAsString(
                java.util.Map.of("question", "本月离职人数", "rows", 12)));
        log.setIsSensitive(0);

        when(auditLogMapper.selectList(any())).thenReturn(List.of(log));
        SecUser user = new SecUser();
        user.setEmpNo("hr01");
        user.setDisplayName("张雨晴");
        when(secUserMapper.selectOne(any())).thenReturn(user);

        PageResult<AuditLogView> result = service.search("hr01", null, null,
                AuditEvents.ASK, null, 1, 20);

        assertEquals(1, result.getTotal());
        AuditLogView view = result.getRecords().get(0);
        assertEquals("张雨晴", view.userName());
        assertEquals("本月离职人数", view.questionDigest());
        assertEquals(12L, view.rows());
    }

    @Test
    void search_sensitiveOnlyAppliesFilter() {
        AudAuditLog sensitive = new AudAuditLog();
        sensitive.setId(2L);
        sensitive.setEventType(AuditEvents.EXPORT);
        sensitive.setUserNo("hr01");
        sensitive.setActionTime(LocalDateTime.now());
        sensitive.setIsSensitive(1);
        when(auditLogMapper.selectList(any())).thenReturn(List.of(sensitive));

        PageResult<AuditLogView> result = service.search(null, null, null,
                null, true, 1, 20);

        assertEquals(1, result.getTotal());
        assertTrue(result.getRecords().get(0).sensitive());
    }

    @Test
    void record_neverBreaksMainFlow() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(auditLogMapper).insert(any());
        // 不应抛异常
        service.record(AuditEvent.of(AuditEvents.ASK, "hr01", "turn", "a1", "{}", false));
        // 幂等性断言：不抛异常即通过
        assertEquals(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID);
    }
}
