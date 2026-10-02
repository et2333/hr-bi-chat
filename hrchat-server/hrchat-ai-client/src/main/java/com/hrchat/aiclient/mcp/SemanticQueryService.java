package com.hrchat.aiclient.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.AuthorizedQuery;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP semantic_query 门面：语义对象 → SQL 模板 → 权限改写 → 只读执行 → 字段脱敏。
 */
@Slf4j
@Service
public class SemanticQueryService {

    public static final int MAX_ROWS = 200;

    private final SemanticMetaService semanticMetaService;
    private final SqlRewriteService sqlRewriteService;
    private final QueryExecService queryExecService;
    private final AuthzService authzService;
    private final MetricSqlComposer sqlComposer;
    private final ObjectMapper objectMapper;
    private final LocalDate demoNow;
    private final AuditCollector auditCollector;

    public SemanticQueryService(SemanticMetaService semanticMetaService,
                                SqlRewriteService sqlRewriteService,
                                QueryExecService queryExecService,
                                AuthzService authzService,
                                ObjectMapper objectMapper,
                                @Value("${hrchat.demo.now:2026-09-28}") LocalDate demoNow,
                                @Autowired(required = false) AuditCollector auditCollector) {
        this.semanticMetaService = semanticMetaService;
        this.sqlRewriteService = sqlRewriteService;
        this.queryExecService = queryExecService;
        this.authzService = authzService;
        this.sqlComposer = new MetricSqlComposer(semanticMetaService);
        this.objectMapper = objectMapper;
        this.demoNow = demoNow;
        this.auditCollector = auditCollector;
    }

    public Map<String, Object> execute(UserContext user, Map<String, Object> arguments, Map<String, Object> context) {
        authzService.checkFunc(user, "chat:ask");
        rejectPhysicalOverrides(arguments);

        @SuppressWarnings("unchecked")
        List<String> metrics = castStringList(arguments.get("metrics"));
        @SuppressWarnings("unchecked")
        List<String> dimensions = castStringList(arguments.get("dimensions"));
        @SuppressWarnings("unchecked")
        List<?> filters = arguments.get("filters") instanceof List<?> list ? list : List.of();
        if (metrics == null || metrics.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "metrics");
        }
        if (dimensions != null && !dimensions.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "dimensions 本期暂不支持");
        }
        if (filters != null && !filters.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "filters 本期暂不支持");
        }

        Integer requestedLimit = arguments.get("limit") instanceof Number n ? n.intValue() : null;
        int limit = requestedLimit == null ? MAX_ROWS : Math.min(Math.max(requestedLimit, 1), MAX_ROWS);

        @SuppressWarnings("unchecked")
        Map<String, Object> timeRange = arguments.get("time_range") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> orgContext = arguments.get("org_context") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;

        MetricSqlComposer.TimeWindow window = MetricSqlComposer.resolveTimeRange(timeRange, demoNow);
        // SQL 始终保留 {authz_org_filter}；org_context 越权在窄化上下文时拒绝（1A）
        UserContext effectiveUser = narrowForOrgContext(user, orgContext);

        List<MetricDetail> details = new ArrayList<>();
        for (String code : metrics) {
            details.add(semanticMetaService.getMetricByCode(code));
        }

        String sql = sqlComposer.buildScalarQuery(details, window, null);
        AuthorizedQuery authorized = sqlRewriteService.authorize(sql, effectiveUser);
        QueryResult raw = queryExecService.executeReadonly(authorized);
        Map<String, Object> result = toMaskedResult(user, details, raw, limit);

        audit(user, context, authorized, (Integer) result.get("row_count"));
        return result;
    }

    private void audit(UserContext user, Map<String, Object> context, AuthorizedQuery authorized, int rowCount) {
        if (auditCollector == null) {
            return;
        }
        try {
            String toolCallId = context == null ? null : stringVal(context.get("tool_call_id"));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("tool", "semantic_query");
            detail.put("tool_call_id", toolCallId);
            detail.put("invocation_id", context == null ? null : stringVal(context.get("invocation_id")));
            detail.put("permission_fingerprint", authorized.permissionFingerprint());
            detail.put("sql_digest", digest(authorized.sql()));
            detail.put("rows", rowCount);
            auditCollector.record(AuditEvent.of(AuditEvents.MCP_TOOL_CALL, user.getEmpNo(),
                    "mcp_tool", toolCallId == null ? "semantic_query" : toolCallId,
                    objectMapper.writeValueAsString(detail), false));
        } catch (Exception e) {
            log.warn("MCP 审计记录失败: {}", e.getMessage());
        }
    }

    private static String digest(String sql) {
        if (sql == null) {
            return "";
        }
        String compact = sql.replaceAll("\\s+", " ").trim();
        return compact.length() <= 200 ? compact : compact.substring(0, 200);
    }

    private Map<String, Object> toMaskedResult(UserContext user, List<MetricDetail> metrics,
                                               QueryResult raw, int limit) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (QueryResult.ColumnMeta col : raw.columns()) {
            int policy = authzService.decideFieldPolicy(user, col.key());
            boolean masked = policy != 4;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("key", col.key());
            c.put("name", col.name() == null ? col.key() : col.name());
            c.put("type", col.type() == null ? "STRING" : col.type());
            c.put("masked", masked);
            columns.add(c);
        }
        List<List<Object>> rows = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> row : raw.rows()) {
            if (count >= limit) {
                break;
            }
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> col : columns) {
                String key = String.valueOf(col.get("key"));
                Object value = row.get(key);
                int policy = authzService.decideFieldPolicy(user, key);
                if (policy == 1) {
                    values.add(null);
                } else if (policy == 4 || value == null) {
                    values.add(value);
                } else {
                    values.add(authzService.mask(String.valueOf(value), key, policy));
                }
            }
            rows.add(values);
            count++;
        }
        MetricDetail primary = metrics.get(0);
        Map<String, Object> caliber = new LinkedHashMap<>();
        caliber.put("metric", primary.code());
        caliber.put("definition", primary.formulaExpr());
        caliber.put("version", primary.effectiveVersion());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("caliber_meta", caliber);
        out.put("permission_rewrite_applied", true);
        out.put("row_count", rows.size());
        return out;
    }

    /**
     * org_context 越权直接拒绝（决策 1A）；在范围内则窄化授权子树供改写注入。
     * 未指定 org_context 时沿用用户全量授权。
     */
    UserContext narrowForOrgContext(UserContext ctx, Map<String, Object> orgContext) {
        if (orgContext == null || orgContext.get("org_id") == null
                || String.valueOf(orgContext.get("org_id")).isBlank()) {
            return ctx;
        }
        long orgId;
        try {
            orgId = Long.parseLong(String.valueOf(orgContext.get("org_id")).trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "org_context.org_id");
        }
        boolean includeChildren = orgContext.get("include_children") == null
                || Boolean.parseBoolean(String.valueOf(orgContext.get("include_children")));

        List<UserContext.GrantedOrg> narrowed = new ArrayList<>();
        for (UserContext.GrantedOrg g : ctx.getGrantedOrgs()) {
            if (g.getSubtreeOrgKeys() == null || !g.getSubtreeOrgKeys().contains(orgId)) {
                continue;
            }
            // 命中授权根：可按 include_children 使用整棵授权子树；命中子节点：仅该 org_key（无完整树不便展开）
            boolean hitGrantRoot = Long.valueOf(orgId).equals(g.getOrgNodeId());
            List<Long> keys = hitGrantRoot && includeChildren
                    ? List.copyOf(g.getSubtreeOrgKeys())
                    : List.of(orgId);
            narrowed.add(UserContext.GrantedOrg.builder()
                    .orgNodeId(orgId)
                    .orgCode(g.getOrgCode())
                    .orgName(hitGrantRoot ? g.getOrgName() : "org:" + orgId)
                    .orgPath(g.getOrgPath())
                    .scope(g.getScope())
                    .subtreeOrgKeys(keys)
                    .build());
            break;
        }
        if (narrowed.isEmpty()) {
            throw new BizException(ErrorCode.DATA_RANGE_FORBIDDEN, "org:" + orgId);
        }
        return UserContext.builder()
                .userId(ctx.getUserId())
                .empNo(ctx.getEmpNo())
                .displayName(ctx.getDisplayName())
                .tenantId(ctx.getTenantId())
                .roles(ctx.getRoles())
                .functionPerms(ctx.getFunctionPerms())
                .dataLevel(ctx.getDataLevel())
                .grantedOrgs(narrowed)
                .fieldPolicyByField(ctx.getFieldPolicyByField())
                .permissionFingerprint(ctx.getPermissionFingerprint())
                .build();
    }

    private static void rejectPhysicalOverrides(Map<String, Object> arguments) {
        if (arguments == null) {
            return;
        }
        for (String forbidden : List.of("sql", "jdbc_url", "datasource", "datasource_code", "connection")) {
            if (arguments.containsKey(forbidden) && arguments.get(forbidden) != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "禁止指定物理连接或 SQL: " + forbidden);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
