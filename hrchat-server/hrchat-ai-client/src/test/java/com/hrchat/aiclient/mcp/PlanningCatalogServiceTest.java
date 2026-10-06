package com.hrchat.aiclient.mcp;

import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.dto.MetricSummary;
import com.hrchat.semantic.dto.SynonymItem;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlanningCatalogServiceTest {
    @Test
    void readsAllPagesAndRejectsPartialOrChangingCatalog() {
        assertEquals(List.of("a", "b"), PlanningCatalogService.allPages(p ->
                PageResult.of(List.of(p == 1 ? "a" : "b"), 2, p, 1)));
        assertThrows(BizException.class, () -> PlanningCatalogService.allPages(p ->
                PageResult.of(List.of(), 1, p, 200)));
        assertThrows(BizException.class, () -> PlanningCatalogService.allPages(p ->
                PageResult.of(List.of("a"), p == 1 ? 2 : 3, p, 1)));
        assertThrows(BizException.class, () -> PlanningCatalogService.allPages(p -> null));
    }

    @Test
    void visibilityPolicyMustBeConfigured() {
        var service = new PlanningCatalogService(mock(SemanticMetaService.class), LocalDate.of(2026, 9, 28),
                mock(SecOrgNodeMapper.class), "unconfigured");
        assertThrows(BizException.class, () -> service.metrics(UserContext.builder().build()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedMetadataIsScopedAndHasCapabilitiesWithoutPhysicalSql() {
        var semantic = mock(SemanticMetaService.class);
        var orgs = mock(SecOrgNodeMapper.class);
        var summary = mock(MetricSummary.class);
        when(summary.code()).thenReturn("headcount");
        when(semantic.listMetrics(null, 1, null, 1, 200)).thenReturn(PageResult.of(List.of(summary), 1, 1, 200));
        when(semantic.listSynonyms(null, 1, 200)).thenReturn(PageResult.of(
                List.of(new SynonymItem(1L, "研发", 2, 2L, 0)), 1, 1, 200));
        when(semantic.getMetricByCode("headcount")).thenReturn(new MetricDetail(1L, "headcount", "在职人数", "STAFF",
                "SELECT secret_physical_sql", "截至指定日期在职", "MONTH", 1, 1, 2, 3, 1, List.of("org"), "sys", null));
        SecOrgNode a = new SecOrgNode(); a.setId(2L); a.setOrgName("研发中心");
        SecOrgNode b = new SecOrgNode(); b.setId(5L); b.setOrgName("销售部");
        when(orgs.selectList(any())).thenReturn(List.of(a, b));
        UserContext user = UserContext.builder().tenantId("t01").dataLevel(1).grantedOrgs(List.of(
                UserContext.GrantedOrg.builder().subtreeOrgKeys(List.of(2L)).build())).build();
        var service = new PlanningCatalogService(semantic, LocalDate.of(2026, 9, 28), orgs, "authorized");
        var result = service.metrics(user);
        assertEquals(1, result.get("org_count"));
        var metrics = (List<Map<String, Object>>) result.get("metrics");
        assertEquals("人", metrics.get(0).get("unit"));
        assertEquals(2, metrics.get(0).get("version"));
        assertFalse(result.toString().contains("secret_physical_sql"));
        assertFalse(result.toString().contains("销售部"));
        assertThrows(BizException.class, () -> new PlanningCatalogService(semantic, LocalDate.of(2026, 9, 28), orgs,
                "tenant_directory").metrics(user));
        // A new request must re-read, not reuse authorization/catalog state.
        service.metrics(user);
        verify(semantic, times(2)).listMetrics(null, 1, null, 1, 200);
    }

    @Test
    void unpublishedAndHigherSensitivityDefinitionsAreNotVisible() {
        UserContext user = UserContext.builder().dataLevel(1).build();
        var hidden = new MetricDetail(1L, "secret", "secret", "STAFF", "", "", "MONTH",
                1, 1, 1, 0, 3, List.of(), "sys", null);
        assertFalse(PlanningCapabilities.visible(user, hidden));
        var draft = new MetricDetail(1L, "draft", "draft", "STAFF", "", "", "MONTH",
                1, 1, 0, 1, 1, List.of(), "sys", null);
        assertFalse(PlanningCapabilities.visible(user, draft));
        assertEquals(List.of(), PlanningCapabilities.describe(hidden).get("allowed_modes"));
    }

    @Test
    void onDemandResolutionDistinguishesOnlyInProtectedAudit() {
        var semantic = mock(SemanticMetaService.class);
        var orgs = mock(SecOrgNodeMapper.class);
        when(semantic.listSynonyms(null, 1, 200)).thenReturn(PageResult.of(List.of(), 0, 1, 200));
        SecOrgNode visible = new SecOrgNode(); visible.setId(2L); visible.setOrgName("研发中心");
        SecOrgNode hidden = new SecOrgNode(); hidden.setId(8L); hidden.setOrgName("并购筹备组");
        when(orgs.selectList(any())).thenReturn(List.of(visible, hidden));
        UserContext user = UserContext.builder().tenantId("t01").grantedOrgs(List.of(
                UserContext.GrantedOrg.builder().subtreeOrgKeys(List.of(2L)).build())).build();
        var service = new PlanningCatalogService(semantic, LocalDate.of(2026, 9, 28), orgs, "authorized");
        var missing = service.resolveOrganization(user, "不存在部门");
        var denied = service.resolveOrganization(user, "并购筹备组");
        assertEquals(missing.publicResult(), denied.publicResult());
        assertEquals("ORG_NOT_FOUND", missing.auditReason());
        assertEquals("ORG_NOT_VISIBLE", denied.auditReason());
        assertEquals("RESOLVED", service.resolveOrganization(user, "研发中心").publicResult().get("status"));
        assertThrows(BizException.class, () -> service.checkRequestedOrg(user, "8"));
        service.checkRequestedOrg(user, "2");
    }
}
