package com.hrchat.aiclient.mcp;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.dto.MetricSummary;
import com.hrchat.semantic.dto.SynonymItem;
import com.hrchat.semantic.service.SemanticMetaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.LocalDate;
import java.util.*;
import java.util.function.IntFunction;

/** Read a complete small published catalog on every request; no shared permission cache. */
@Service
public class PlanningCatalogService {
    private final SemanticMetaService semantic;
    private final LocalDate asOf;
    private final SecOrgNodeMapper organizations;
    private final String orgScope;

    public PlanningCatalogService(SemanticMetaService semantic,
                                  @Value("${hrchat.demo.now:2026-09-28}") LocalDate asOf,
                                  SecOrgNodeMapper organizations,
                                  @Value("${hrchat.ai.planning.org-catalog-scope:authorized}") String orgScope) {
        this.semantic = semantic;
        this.asOf = asOf;
        this.organizations = organizations;
        this.orgScope = orgScope;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> metrics(UserContext user) {
        // A+: only authorized names may enter the model context.
        if (!"authorized".equals(orgScope)) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "组织目录可见范围配置无效");
        }
        List<SynonymItem> synonyms = allPages(p -> semantic.listSynonyms(null, p, 200));
        List<MetricSummary> summaries = allPages(p -> semantic.listMetrics(null, 1, null, p, 200));
        List<Map<String, Object>> metrics = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (MetricSummary summary : summaries) {
            MetricDetail m = semantic.getMetricByCode(summary.code());
            if (!codes.add(m.code())) throw incomplete();
            if (!PlanningCapabilities.visible(user, m)) continue;
            Map<String, Object> row = new LinkedHashMap<>(PlanningCapabilities.describe(m));
            row.put("code", m.code());
            row.put("name", m.name());
            row.put("definition", m.calcScope() == null ? "" : m.calcScope());
            row.put("version", m.effectiveVersion());
            row.put("aliases", synonyms.stream().filter(s -> Integer.valueOf(1).equals(s.targetType())
                    && Objects.equals(m.id(), s.targetId())).map(SynonymItem::termGroup).toList());
            metrics.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1");
        out.put("complete", true);
        out.put("as_of_date", asOf.toString());
        out.put("timezone", "Asia/Shanghai");
        out.put("metrics", metrics);
        out.put("metric_count", metrics.size());
        out.put("catalog_scope", "published_visible");
        out.put("capability_version", "s0-counts-v1");
        List<SecOrgNode> nodes = organizations.selectList(new LambdaQueryWrapper<SecOrgNode>()
                .eq(SecOrgNode::getTenantId, user.getTenantId()).eq(SecOrgNode::getStatus, 1)
                .orderByAsc(SecOrgNode::getId));
        Set<Long> authorized = new HashSet<>();
        if (user.getGrantedOrgs() != null) {
            user.getGrantedOrgs().forEach(g -> authorized.addAll(g.getSubtreeOrgKeys()));
        }
        Map<Long, SecOrgNode> byId = new HashMap<>();
        for (SecOrgNode node : nodes) {
            byId.put(node.getId(), node);
        }
        List<Map<String, Object>> orgs = new ArrayList<>();
        for (SecOrgNode node : nodes) {
            if (!authorized.contains(node.getId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_id", String.valueOf(node.getId()));
            row.put("name", node.getOrgName());
            row.put("org_code", node.getOrgCode() == null ? "" : node.getOrgCode());
            row.put("label", visiblePathLabel(node, byId, authorized));
            row.put("aliases", synonyms.stream().filter(s -> Integer.valueOf(2).equals(s.targetType())
                    && Objects.equals(node.getId(), s.targetId())).map(SynonymItem::termGroup).toList());
            orgs.add(row);
        }
        out.put("organizations", orgs);
        out.put("org_count", orgs.size());
        out.put("org_catalog_scope", orgScope);
        return out;
    }

    public void checkRequestedOrg(UserContext user, String orgId) {
        boolean allowed = user.getGrantedOrgs() != null && user.getGrantedOrgs().stream()
                .flatMap(g -> g.getSubtreeOrgKeys().stream()).anyMatch(id -> String.valueOf(id).equals(orgId));
        if (!allowed) throw new BizException(ErrorCode.DATA_RANGE_FORBIDDEN, "请求组织");
    }

    /** Internal reason is audit-only; callers cannot enumerate hidden organizations. */
    public record OrganizationResolution(Map<String, Object> publicResult, String auditReason) { }

    /**
     * Build a user-visible disambiguation label using only authorized ancestors (A+).
     * Example: {@code 研发部（研发一部 / RD1-DEV）}.
     */
    static String visiblePathLabel(SecOrgNode node, Map<Long, SecOrgNode> byId, Set<Long> allowed) {
        List<String> parents = new ArrayList<>();
        Long parentId = node.getParentId();
        Set<Long> visited = new HashSet<>();
        visited.add(node.getId());
        while (parentId != null && parentId != 0L && visited.add(parentId)) {
            SecOrgNode parent = byId.get(parentId);
            if (parent == null) {
                break;
            }
            if (allowed.contains(parent.getId())) {
                parents.add(0, parent.getOrgName());
            }
            parentId = parent.getParentId();
        }
        String code = node.getOrgCode() == null || node.getOrgCode().isBlank() ? String.valueOf(node.getId()) : node.getOrgCode();
        if (parents.isEmpty()) {
            return node.getOrgName() + "（" + code + "）";
        }
        return node.getOrgName() + "（" + String.join(" / ", parents) + " / " + code + "）";
    }

    private Map<String, Object> candidateView(SecOrgNode node, Map<Long, SecOrgNode> byId, Set<Long> allowed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("org_id", String.valueOf(node.getId()));
        row.put("name", node.getOrgName());
        row.put("org_code", node.getOrgCode() == null ? "" : node.getOrgCode());
        row.put("label", visiblePathLabel(node, byId, allowed));
        return row;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrganizationResolution resolveOrganization(UserContext user, String requestedName) {
        if (requestedName == null || requestedName.isBlank() || requestedName.length() > 100) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组织名称");
        }
        List<SynonymItem> synonyms = allPages(p -> semantic.listSynonyms(null, p, 200));
        Set<Long> aliases = new HashSet<>();
        synonyms.stream().filter(s -> Integer.valueOf(2).equals(s.targetType())
                && requestedName.equals(s.termGroup())).forEach(s -> aliases.add(s.targetId()));
        List<SecOrgNode> nodes = organizations.selectList(new LambdaQueryWrapper<SecOrgNode>()
                .eq(SecOrgNode::getTenantId, user.getTenantId()).eq(SecOrgNode::getStatus, 1));
        Map<Long, SecOrgNode> byId = new HashMap<>();
        for (SecOrgNode node : nodes) {
            byId.put(node.getId(), node);
        }
        List<SecOrgNode> matches = nodes.stream().filter(n -> requestedName.equals(n.getOrgName())
                || aliases.contains(n.getId())).toList();
        Set<Long> allowed = new HashSet<>();
        if (user.getGrantedOrgs() != null) user.getGrantedOrgs().forEach(g -> allowed.addAll(g.getSubtreeOrgKeys()));
        Map<String, Object> unavailable = Map.of("status", "UNAVAILABLE",
                "message", "组织无法识别或不在当前可用范围，请使用可用组织的完整名称。");
        if (matches.isEmpty()) return new OrganizationResolution(unavailable, "ORG_NOT_FOUND");
        // A+: any hidden hit collapses to the same public UNAVAILABLE (do not leak existence).
        if (matches.stream().anyMatch(n -> !allowed.contains(n.getId()))) {
            return new OrganizationResolution(unavailable, "ORG_NOT_VISIBLE");
        }
        if (matches.size() > 1) {
            List<Map<String, Object>> candidates = matches.stream()
                    .map(n -> candidateView(n, byId, allowed)).toList();
            Map<String, Object> ambiguous = new LinkedHashMap<>();
            ambiguous.put("status", "AMBIGUOUS");
            ambiguous.put("candidates", candidates);
            return new OrganizationResolution(ambiguous, "ORG_AMBIGUOUS");
        }
        SecOrgNode resolved = matches.get(0);
        Map<String, Object> organization = new LinkedHashMap<>();
        organization.put("org_id", String.valueOf(resolved.getId()));
        organization.put("name", resolved.getOrgName());
        organization.put("org_code", resolved.getOrgCode() == null ? "" : resolved.getOrgCode());
        organization.put("label", visiblePathLabel(resolved, byId, allowed));
        organization.put("aliases", List.of(requestedName));
        return new OrganizationResolution(Map.of("status", "RESOLVED", "organization", organization), "ORG_RESOLVED");
    }

    static <T> List<T> allPages(IntFunction<PageResult<T>> fetch) {
        List<T> out = new ArrayList<>();
        Long total = null;
        for (int page = 1; page <= 100; page++) {
            PageResult<T> result = fetch.apply(page);
            if (result == null || result.getRecords() == null || result.getPage() != page
                    || result.getTotal() < 0 || (total != null && total != result.getTotal())) throw incomplete();
            total = result.getTotal();
            out.addAll(result.getRecords());
            if (out.size() == total) return out;
            if (out.size() > total || result.getRecords().isEmpty()) throw incomplete();
        }
        throw incomplete();
    }

    private static BizException incomplete() {
        return new BizException(ErrorCode.SERVICE_UNAVAILABLE, "语义目录不完整，请重试");
    }
}
