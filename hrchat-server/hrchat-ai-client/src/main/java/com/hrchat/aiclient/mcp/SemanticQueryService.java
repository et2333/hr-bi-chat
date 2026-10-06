package com.hrchat.aiclient.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.hrchat.api.mcp.QueryPlan;
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
import java.util.Set;
import java.util.stream.Collectors;

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
        this.sqlComposer = new MetricSqlComposer(semanticMetaService, demoNow);
        this.objectMapper = objectMapper;
        this.demoNow = demoNow;
        this.auditCollector = auditCollector;
    }

    public Map<String, Object> execute(UserContext user, Map<String, Object> arguments, Map<String, Object> context) {
        authzService.checkFunc(user, "chat:ask");
        Map<String, Object> planEvidence = null;
        if (arguments.containsKey("query_plan")) {
            planEvidence = normalizePlan(user, arguments);
            arguments = planEvidence;
        }
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
        if (metrics.size() != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前仅支持单指标查询");
        }
        if (filters != null && !filters.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "filters 本期暂不支持");
        }
        String queryMode = arguments.get("query_mode") == null ? null
                : String.valueOf(arguments.get("query_mode")).trim().toLowerCase();
        if (queryMode != null && queryMode.isBlank()) {
            queryMode = null;
        }
        if (queryMode != null && !queryMode.isBlank()
                && !Set.of("scalar", "org", "detail", "trend").contains(queryMode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的 query_mode: " + queryMode);
        }
        if (dimensions != null && !dimensions.isEmpty()) {
            if (dimensions.size() == 1 && "org".equalsIgnoreCase(dimensions.get(0))) {
                if (queryMode != null && !queryMode.isBlank() && !"org".equals(queryMode)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "dimensions=org 与 query_mode 不匹配");
                }
                queryMode = "org";
            } else {
                throw new BizException(ErrorCode.PARAM_INVALID, "dimensions 仅支持单一 org");
            }
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
        if (window != null && window.start().isAfter(demoNow)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "查询期间晚于演示时点");
        }
        if (queryMode == null && timeRange != null && "MONTH".equalsIgnoreCase(stringVal(timeRange.get("grain")))) {
            queryMode = "trend";
        }
        if (queryMode == null || queryMode.isBlank()) {
            queryMode = "scalar";
        }
        // SQL 始终保留 {authz_org_filter}；org_context 越权在窄化上下文时拒绝（1A）
        UserContext effectiveUser = narrowForOrgContext(user, orgContext);
        if ("detail".equals(queryMode) && !effectiveUser.canViewDetail()) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }

        List<MetricDetail> details = new ArrayList<>();
        for (String code : metrics) {
            details.add(semanticMetaService.getMetricByCode(code));
        }
        MetricDetail primary = details.get(0);
        PlanningCapabilities.requireVisible(user, primary);
        if (window == null && ("hire_count".equals(primary.code())
                || "leave_count".equals(primary.code()))) {
            throw new BizException(ErrorCode.PARAM_MISSING, "人事变动人数需要明确统计期间");
        }

        String sql;
        if ("org".equals(queryMode)) {
            sql = sqlComposer.buildOrgCompareQuery(primary, window, null);
        } else if ("detail".equals(queryMode)) {
            sql = sqlComposer.buildDetailQuery(primary, window, null);
        } else if ("trend".equals(queryMode)) {
            if (window == null) {
                window = new MetricSqlComposer.TimeWindow(
                        demoNow.withDayOfMonth(1).minusMonths(2), demoNow.plusDays(1));
            }
            sql = sqlComposer.buildMonthlyTrendQuery(primary, window, null);
            if (sql == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "该指标不支持月趋势");
            }
        } else {
            sql = sqlComposer.buildScalarQuery(details, window, null);
            queryMode = "scalar";
        }
        AuthorizedQuery authorized = sqlRewriteService.authorize(sql, effectiveUser);
        QueryResult raw = queryExecService.executeReadonly(authorized);
        Map<String, Object> result = toMaskedResult(user, details, raw, limit);
        result.put("query_mode", queryMode);
        if (planEvidence != null) {
            result.put("query_plan", planEvidence.get("validated_plan"));
            result.put("metric_version", primary.effectiveVersion());
            result.put("effective_org_ids", effectiveUser.getGrantedOrgs().stream()
                    .flatMap(g -> g.getSubtreeOrgKeys().stream()).distinct().sorted().toList());
        }
        if ("headcount".equals(primary.code())) {
            result.put("as_of_date", HeadcountAsOf.date(window == null ? null : window.end(), demoNow).toString());
        }

        audit(user, context, authorized, (Integer) result.get("row_count"));
        return result;
    }

    /** Validate a planner request again at the trusted execution boundary. */
    private Map<String, Object> normalizePlan(UserContext user, Map<String, Object> request) {
        if (!Set.of("query_plan", "metric_version").containsAll(request.keySet())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "查询计划含未知参数");
        }
        QueryPlan plan;
        try {
            plan = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .convertValue(request.get("query_plan"), QueryPlan.class);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, "查询计划格式错误");
        }
        if (plan == null || !"1".equals(plan.schemaVersion()) || !"execute".equals(plan.decision())
                || !"ready".equals(plan.reason()) || plan.metricCodes() == null || plan.metricCodes().size() != 1
                || (plan.missingSlots() != null && !plan.missingSlots().isEmpty())
                || (plan.clarificationOptions() != null && !plan.clarificationOptions().isEmpty())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "查询计划尚不可执行");
        }
        MetricDetail metric = semanticMetaService.getMetricByCode(plan.metricCodes().get(0));
        PlanningCapabilities.requireVisible(user, metric);
        if (!(request.get("metric_version") instanceof Number version)
                || version.intValue() != metric.effectiveVersion()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标口径版本已变化，请重新规划");
        }
        Map<String, Object> capability = PlanningCapabilities.describe(metric);
        if (!((List<?>) capability.get("allowed_modes")).contains(plan.queryMode())) {
            throw new BizException(ErrorCode.QUERY_UNSUPPORTED, "指标不支持这种统计方式");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metrics", plan.metricCodes());
        out.put("query_mode", plan.queryMode());
        if (plan.orgScope() != null) {
            if (plan.orgScope().orgId() == null || plan.orgScope().orgId().isBlank()
                    || plan.orgScope().requestedName() != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "组织不能为空");
            }
            out.put("org_context", Map.of("org_id", plan.orgScope().orgId(),
                    "include_children", !Boolean.FALSE.equals(plan.orgScope().includeChildren())));
        }
        if (plan.timeRange() != null) {
            QueryPlan.TimeRange t = plan.timeRange();
            try {
                LocalDate start = LocalDate.parse(t.start()), end = LocalDate.parse(t.end());
                if (!start.isBefore(end) || end.isAfter(demoNow.plusDays(1))
                        || !"Asia/Shanghai".equals(t.timezone())
                        || !capability.get("time_type").equals(t.timeType())
                        || !("trend".equals(plan.queryMode()) ? "MONTH" : "NONE").equals(t.grain())) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "计划时间范围不合法");
            }
            out.put("time_range", Map.of("preset", "CUSTOM", "start", t.start(), "end", t.end(), "grain", t.grain()));
        } else if (Boolean.TRUE.equals(capability.get("requires_period")) || "trend".equals(plan.queryMode())) {
            throw new BizException(ErrorCode.PARAM_MISSING, "统计期间");
        }
        out.put("validated_plan", request.get("query_plan"));
        return out;
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
        Set<String> metricCodes = metrics.stream().map(MetricDetail::code).collect(Collectors.toSet());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (QueryResult.ColumnMeta col : raw.columns()) {
            // 指标聚合列是 KPI 结果，不是 PII 字段；未知 fieldCode 默认脱敏会导致 Python 无法数值化
            int policy = fieldPolicyForColumn(user, col.key(), metricCodes);
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
                int policy = fieldPolicyForColumn(user, key, metricCodes);
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
        caliber.put("definition", primary.calcScope());
        caliber.put("version", primary.effectiveVersion());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("caliber_meta", caliber);
        out.put("permission_rewrite_applied", true);
        out.put("row_count", rows.size());
        return out;
    }

    private int fieldPolicyForColumn(UserContext user, String columnKey, Set<String> metricCodes) {
        if (columnKey != null && metricCodes.contains(columnKey)) {
            return 4;
        }
        return authzService.decideFieldPolicy(user, columnKey);
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
