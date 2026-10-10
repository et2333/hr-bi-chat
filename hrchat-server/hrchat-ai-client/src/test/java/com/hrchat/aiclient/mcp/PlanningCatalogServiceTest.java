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

    @Test
    @SuppressWarnings("unchecked")
    void ambiguousVisibleHomonymsReturnDistinguishableCandidates() {
        var semantic = mock(SemanticMetaService.class);
        var orgs = mock(SecOrgNodeMapper.class);
        when(semantic.listSynonyms(null, 1, 200)).thenReturn(PageResult.of(List.of(), 0, 1, 200));
        SecOrgNode rd1 = new SecOrgNode(); rd1.setId(3L); rd1.setOrgName("研发一部"); rd1.setOrgCode("RD1");
        rd1.setParentId(2L); rd1.setOrgPath("/1/2/3/");
        SecOrgNode rd2 = new SecOrgNode(); rd2.setId(4L); rd2.setOrgName("研发二部"); rd2.setOrgCode("RD2");
        rd2.setParentId(2L); rd2.setOrgPath("/1/2/4/");
        SecOrgNode a = new SecOrgNode(); a.setId(9L); a.setOrgName("研发部"); a.setOrgCode("RD1-DEV");
        a.setParentId(3L); a.setOrgPath("/1/2/3/9/");
        SecOrgNode b = new SecOrgNode(); b.setId(10L); b.setOrgName("研发部"); b.setOrgCode("RD2-DEV");
        b.setParentId(4L); b.setOrgPath("/1/2/4/10/");
        when(orgs.selectList(any())).thenReturn(List.of(rd1, rd2, a, b));
        UserContext user = UserContext.builder().tenantId("t01").grantedOrgs(List.of(
                UserContext.GrantedOrg.builder().subtreeOrgKeys(List.of(3L, 4L, 9L, 10L)).build())).build();
        var service = new PlanningCatalogService(semantic, LocalDate.of(2026, 9, 28), orgs, "authorized");
        var result = service.resolveOrganization(user, "研发部").publicResult();
        assertEquals("AMBIGUOUS", result.get("status"));
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(c -> "9".equals(c.get("org_id"))
                && String.valueOf(c.get("label")).contains("研发一部")));
        assertTrue(candidates.stream().anyMatch(c -> "10".equals(c.get("org_id"))
                && String.valueOf(c.get("label")).contains("研发二部")));
    }

    @Test
    void labelsHideUnauthorizedParentsAndTerminateOnCyclicData() {
        SecOrgNode child = new SecOrgNode(); child.setId(9L); child.setOrgName("研发部"); child.setParentId(3L);
        SecOrgNode hidden = new SecOrgNode(); hidden.setId(3L); hidden.setOrgName("秘密事业群"); hidden.setParentId(9L);
        String label = PlanningCatalogService.visiblePathLabel(child, Map.of(9L, child, 3L, hidden), java.util.Set.of(9L));
        assertFalse(label.contains("秘密事业群"));
        assertTrue(label.contains("研发部"));
    }
}
