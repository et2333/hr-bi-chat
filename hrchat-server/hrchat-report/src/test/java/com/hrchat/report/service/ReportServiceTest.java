package com.hrchat.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.tenant.Tenant;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.dto.ReportViews;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptSubscription;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.report.mapper.RptSnapshotMapper;
import com.hrchat.report.mapper.RptSubReceiverMapper;
import com.hrchat.report.mapper.RptSubscriptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表服务单测（S5）：创建/列表范围/订阅幂等/越权删除/详情访问隔离。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    @Mock
    private RptReportMapper reportMapper;
    @Mock
    private RptComponentMapper componentMapper;
    @Mock
    private RptSubscriptionMapper subscriptionMapper;
    @Mock
    private RptSubReceiverMapper receiverMapper;
    @Mock
    private RptSnapshotMapper snapshotMapper;
    @Mock
    private SecUserMapper secUserMapper;
    @Mock
    private AuthzService authzService;
    @Mock
    private TenantMapper tenantMapper;

    private ReportService service;
    private ReportService tenantService;
    private UserContext hr01;

    @BeforeEach
    void setUp() {
        service = new ReportService(reportMapper, componentMapper, subscriptionMapper,
                receiverMapper, snapshotMapper, secUserMapper, authzService, new ObjectMapper(), null);
        tenantService = new ReportService(reportMapper, componentMapper, subscriptionMapper,
                receiverMapper, snapshotMapper, secUserMapper, authzService, new ObjectMapper(), tenantMapper);
        TenantContextHolder.clear();
        hr01 = UserContext.builder()
                .userId(1L).empNo("hr01").displayName("张雨晴").roles(List.of("HRBP"))
                .dataLevel(1)
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(35L).orgCode("rd").orgName("研发中心")
                        .orgPath("/1/35/").scope(3).subtreeOrgKeys(List.of(35L)).build()))
                .build();
    }

    private RptReport report(Long id, Long ownerId, String name) {
        RptReport r = new RptReport();
        r.setId(id);
        r.setOwnerId(ownerId);
        r.setReportName(name);
        r.setSourceType(3);
        r.setStatus(1);
        r.setIsDeleted(0);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        return r;
    }

    @Test
    void createCustom_storesReportAndComponents() {
        when(reportMapper.insert(any())).thenAnswer(inv -> {
            RptReport r = inv.getArgument(0);
            r.setId(10L);
            return 1;
        });
        when(componentMapper.insert(any())).thenReturn(1);

        Long id = service.create(hr01, new ReportDtos.ReportCreateRequest("CUSTOM", null,
                "人员结构报表", null, null,
                List.of(new ReportDtos.ComponentSpec("CHART", "bar", Map.of("metrics", List.of("headcount")))),
                Map.of("org", "2")), null);

        assertEquals(10L, id);
        verify(reportMapper).insert(argThat(r -> "人员结构报表".equals(r.getReportName())
                && r.getSourceType() == 3 && r.getOwnerId().equals(1L)));
        verify(componentMapper, times(1)).insert(any());
    }

    @Test
    void create_blankNameThrows() {
        assertThrows(BizException.class, () -> service.create(hr01,
                new ReportDtos.ReportCreateRequest("CUSTOM", null, "  ", null, null,
                        List.of(new ReportDtos.ComponentSpec("CHART", "bar", Map.of())), Map.of()),
                null));
    }

    @Test
    void listMine_onlyReturnsOwnerReports() {
        when(reportMapper.selectList(any())).thenReturn(List.of(report(1L, 1L, "我的报表"), report(2L, 9L, "他人报表")));
        SecUser owner = new SecUser();
        owner.setDisplayName("张雨晴");
        when(secUserMapper.selectById(1L)).thenReturn(owner);

        PageResult<ReportViews.ReportSummary> result = service.list(hr01, "mine", null, 1, 20);

        assertEquals(1, result.getTotal());
        assertEquals("我的报表", result.getRecords().get(0).name());
    }

    @Test
    void delete_nonOwnerForbidden() {
        when(reportMapper.selectById(9L)).thenReturn(report(9L, 99L, "他人报表"));

        BizException e = assertThrows(BizException.class, () -> service.delete(hr01, 9L));
        assertEquals(ErrorCode.FUNC_FORBIDDEN, e.getErrorCode());
        verify(reportMapper, never()).deleteById(anyLong());
    }

    @Test
    void delete_owner_softDeletesViaTableLogicAndCancelsSubs() {
        when(reportMapper.selectById(5L)).thenReturn(report(5L, 1L, "人力月报"));
        RptSubscription sub = new RptSubscription();
        sub.setId(20L);
        sub.setReportId(5L);
        sub.setStatus(1);
        sub.setIsDeleted(0);
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(sub));
        when(reportMapper.updateById(any())).thenReturn(1);
        when(subscriptionMapper.updateById(any())).thenReturn(1);
        when(reportMapper.deleteById(5L)).thenReturn(1);

        service.delete(hr01, 5L);

        verify(reportMapper).updateById(argThat(r -> r.getStatus() != null && r.getStatus() == 2));
        verify(subscriptionMapper).updateById(argThat(s ->
                Integer.valueOf(0).equals(s.getStatus()) && Integer.valueOf(1).equals(s.getIsDeleted())));
        verify(reportMapper).deleteById(5L);
    }

    @Test
    void subscribe_computesNextRunAndReplaysIdempotent() {
        when(reportMapper.selectById(5L)).thenReturn(report(5L, 1L, "人力月报"));
        SecUser hr02 = new SecUser();
        hr02.setId(2L);
        hr02.setEmpNo("hr02");
        hr02.setDisplayName("李思思");
        when(secUserMapper.selectOne(any())).thenReturn(hr02);
        when(subscriptionMapper.insert(any())).thenAnswer(inv -> {
            RptSubscription s = inv.getArgument(0);
            s.setId(20L);
            return 1;
        });
        when(subscriptionMapper.selectById(20L)).thenAnswer(inv -> {
            RptSubscription s = new RptSubscription();
            s.setId(20L);
            s.setReportId(5L);
            s.setFreq(1);
            s.setChannel(1);
            s.setStatus(1);
            s.setNextRunAt(LocalDateTime.now().plusDays(1));
            s.setIsDeleted(0);
            return s;
        });
        when(receiverMapper.selectCount(any())).thenReturn(1L);
        when(receiverMapper.insert(any())).thenReturn(1);

        ReportDtos.SubscribeRequest req = new ReportDtos.SubscribeRequest("DAILY", "EMAIL",
                List.of(new ReportDtos.Receiver("USER", "hr02")), "09:00", null);

        ReportViews.SubscriptionView first = service.subscribe(hr01, 5L, req, "key-1");
        ReportViews.SubscriptionView replay = service.subscribe(hr01, 5L, req, "key-1");

        assertEquals(first.id(), replay.id());
        assertNotNull(first.nextRunAt());
        verify(subscriptionMapper, times(1)).insert(any());
        verify(receiverMapper, times(1)).insert(any());
    }

    @Test
    void subscribe_unknownReceiverThrows() {
        when(reportMapper.selectById(5L)).thenReturn(report(5L, 1L, "人力月报"));
        when(secUserMapper.selectOne(any())).thenReturn(null);

        ReportDtos.SubscribeRequest req = new ReportDtos.SubscribeRequest("DAILY", "EMAIL",
                List.of(new ReportDtos.Receiver("USER", "hr99")), "09:00", null);

        assertThrows(BizException.class, () -> service.subscribe(hr01, 5L, req, null));
    }

    @Test
    void getDetail_strangerDenied() {
        when(reportMapper.selectById(7L)).thenReturn(report(7L, 99L, "保密报表"));
        when(receiverMapper.selectCount(any())).thenReturn(0L);

        BizException e = assertThrows(BizException.class, () -> service.getDetail(hr01, 7L));
        assertEquals(ErrorCode.FUNC_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void getDetail_ownerSeesComponentsAndSnapshot() {
        when(reportMapper.selectById(7L)).thenReturn(report(7L, 1L, "我的报表"));
        RptComponent comp = new RptComponent();
        comp.setId(30L);
        comp.setReportId(7L);
        comp.setCompType(1);
        comp.setChartType("bar");
        comp.setDefJson("{\"metrics\":[\"headcount\"]}");
        comp.setSortNo(0);
        when(componentMapper.selectList(any())).thenReturn(List.of(comp));
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        when(snapshotMapper.selectList(any())).thenReturn(List.of());

        ReportViews.ReportDetail detail = service.getDetail(hr01, 7L);

        assertEquals("我的报表", detail.name());
        assertEquals(1, detail.components().size());
        assertEquals("CHART", detail.components().get(0).compType());
    }

    // ---------------- 配额校验（仅显式租户请求时生效） ----------------

    private Tenant tenant(int reportQuota, int subscriptionQuota) {
        Tenant t = new Tenant();
        t.setTenantCode("t01");
        t.setStatus(1);
        t.setReportQuota(reportQuota);
        t.setSubscriptionQuota(subscriptionQuota);
        return t;
    }

    @Test
    void create_reportQuotaExceeded_throwsQuotaExceeded() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1, 50));
        when(reportMapper.selectCount(any())).thenReturn(1L);
        TenantContextHolder.set("t01");
        try {
            BizException e = assertThrows(BizException.class, () -> tenantService.create(hr01,
                    new ReportDtos.ReportCreateRequest("CUSTOM", null, "超配报表", null, null,
                            List.of(new ReportDtos.ComponentSpec("CHART", "bar", Map.of())), Map.of()),
                    null));
            assertEquals(ErrorCode.QUOTA_EXCEEDED, e.getErrorCode());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void create_reportQuotaWithinLimit_insertsWithTenantId() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(200, 50));
        when(reportMapper.selectCount(any())).thenReturn(1L);
        when(reportMapper.insert(any())).thenAnswer(inv -> {
            RptReport r = inv.getArgument(0);
            r.setId(10L);
            return 1;
        });
        when(componentMapper.insert(any())).thenReturn(1);
        TenantContextHolder.set("t01");
        try {
            Long id = tenantService.create(hr01,
                    new ReportDtos.ReportCreateRequest("CUSTOM", null, "报表A", null, null,
                            List.of(new ReportDtos.ComponentSpec("CHART", "bar", Map.of())), Map.of()),
                    null);
            assertEquals(10L, id);
            verify(reportMapper).insert(argThat(r -> "t01".equals(r.getTenantId())));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void create_noTenantContext_skipsQuotaCheck() {
        when(reportMapper.insert(any())).thenAnswer(inv -> {
            RptReport r = inv.getArgument(0);
            r.setId(10L);
            return 1;
        });
        when(componentMapper.insert(any())).thenReturn(1);
        // 无租户头 → 即使配额为 1 且已用满也不拦截（单租户兼容）
        Long id = service.create(hr01,
                new ReportDtos.ReportCreateRequest("CUSTOM", null, "报表B", null, null,
                        List.of(new ReportDtos.ComponentSpec("CHART", "bar", Map.of())), Map.of()),
                null);
        assertEquals(10L, id);
    }

    @Test
    void create_tenantWithoutQuotaRecord_skipsQuotaCheck() {
        when(tenantMapper.selectOne(any())).thenReturn(null);
        when(reportMapper.insert(any())).thenAnswer(inv -> {
            RptReport r = inv.getArgument(0);
            r.setId(10L);
            return 1;
        });
        when(componentMapper.insert(any())).thenReturn(1);
        TenantContextHolder.set("t99");
        try {
            Long id = tenantService.create(hr01,
                    new ReportDtos.ReportCreateRequest("CUSTOM", null, "报表C", null, null,
                            List.of(new ReportDtos.ComponentSpec("CHART", "bar", Map.of())), Map.of()),
                    null);
            assertEquals(10L, id);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void subscribe_subscriptionQuotaExceeded_throwsQuotaExceeded() {
        when(reportMapper.selectById(5L)).thenReturn(report(5L, 1L, "人力月报"));
        when(tenantMapper.selectOne(any())).thenReturn(tenant(200, 1));
        when(subscriptionMapper.selectCount(any())).thenReturn(1L);
        TenantContextHolder.set("t01");
        try {
            ReportDtos.SubscribeRequest req = new ReportDtos.SubscribeRequest("DAILY", "EMAIL",
                    List.of(new ReportDtos.Receiver("USER", "hr02")), "09:00", null);
            BizException e = assertThrows(BizException.class,
                    () -> tenantService.subscribe(hr01, 5L, req, null));
            assertEquals(ErrorCode.QUOTA_EXCEEDED, e.getErrorCode());
        } finally {
            TenantContextHolder.clear();
        }
    }
}
