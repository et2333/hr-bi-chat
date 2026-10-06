package com.hrchat.aiclient.mcp;

import com.hrchat.api.mcp.McpEnvelope;
import com.hrchat.authz.mcp.McpToolHandler;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.DimensionSummary;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.dto.MetricSummary;
import com.hrchat.semantic.dto.SynonymItem;
import com.hrchat.semantic.service.SemanticMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP get_semantic_meta：返回已发布语义对象，不含物理库凭证。
 */
@Component
@RequiredArgsConstructor
public class GetSemanticMetaToolHandler implements McpToolHandler {

    private final SemanticMetaService semanticMetaService;
    private final AuthzService authzService;
    private final PlanningCatalogService planningCatalogService;
    @Autowired(required = false)
    private AuditCollector auditCollector;

    @Override
    public String toolName() {
        return McpEnvelope.TOOL_GET_SEMANTIC_META;
    }

    @Override
    public Object call(UserContext user, Map<String, Object> arguments, Map<String, Object> context) {
        authzService.checkFunc(user, "chat:ask");
        String type = arguments == null ? null : stringVal(arguments.get("type"));
        if (type == null || type.isBlank()) {
            type = "METRIC";
        }
        if ("PLANNING".equalsIgnoreCase(type.trim())) {
            if (arguments.get("requested_org_id") != null) {
                planningCatalogService.checkRequestedOrg(user, String.valueOf(arguments.get("requested_org_id")));
            }
            return planningCatalogService.metrics(user);
        }
        if ("RESOLVE_ORG".equalsIgnoreCase(type.trim())) {
            var resolution = planningCatalogService.resolveOrganization(user, stringVal(arguments.get("name")));
            if (auditCollector != null) {
                try {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("tool", "resolve_organization");
                    detail.put("resolution", resolution.auditReason());
                    detail.put("invocation_id", context.get("invocation_id"));
                    detail.put("tool_call_id", context.get("tool_call_id"));
                    auditCollector.record(AuditEvent.of(AuditEvents.MCP_TOOL_CALL, user.getEmpNo(),
                            "mcp_tool", stringVal(context.get("tool_call_id")),
                            new ObjectMapper().writeValueAsString(detail), false));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new BizException(ErrorCode.SYSTEM_BUSY);
                }
            }
            return resolution.publicResult();
        }
        List<String> names = arguments != null && arguments.get("names") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();

        List<Map<String, Object>> objects = switch (type.trim().toUpperCase()) {
            case "METRIC" -> loadMetrics(user, names);
            case "DIMENSION" -> loadDimensions(names);
            case "SYNONYM" -> loadSynonyms(names);
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "type");
        };
        return Map.of("objects", objects);
    }

    private List<Map<String, Object>> loadMetrics(UserContext user, List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            List<MetricSummary> all = PlanningCatalogService.allPages(
                    p -> semanticMetaService.listMetrics(null, 1, null, p, 200));
            for (MetricSummary m : all) {
                MetricDetail detail = semanticMetaService.getMetricByCode(m.code());
                if (PlanningCapabilities.visible(user, detail)) out.add(metricView(detail));
            }
            return out;
        }
        for (String name : names) {
            MetricDetail detail = semanticMetaService.getMetricByCode(name);
            PlanningCapabilities.requireVisible(user, detail);
            out.add(metricView(detail));
        }
        return out;
    }

    private List<Map<String, Object>> loadDimensions(List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            PageResult<DimensionSummary> page = semanticMetaService.listDimensions(null, 1, 200);
            for (DimensionSummary d : page.getRecords()) {
                out.add(dimensionView(semanticMetaService.getDimensionByCode(d.code())));
            }
            return out;
        }
        for (String name : names) {
            out.add(dimensionView(semanticMetaService.getDimensionByCode(name)));
        }
        return out;
    }

    private List<Map<String, Object>> loadSynonyms(List<String> names) {
        PageResult<SynonymItem> page = semanticMetaService.listSynonyms(null, 1, 200);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SynonymItem item : page.getRecords()) {
            if (names != null && !names.isEmpty()
                    && !names.contains(item.termGroup())
                    && !names.contains(String.valueOf(item.targetId()))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", item.termGroup());
            row.put("target_type", item.targetType());
            row.put("target_id", item.targetId());
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> metricView(MetricDetail detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", detail.code());
        row.put("name", detail.name());
        row.put("definition", detail.calcScope());
        row.put("domain", detail.domain());
        row.put("available_dimensions", detail.availableDimensions());
        row.put("version", detail.effectiveVersion());
        row.putAll(PlanningCapabilities.describe(detail));
        return row;
    }

    /** 维度仅返回业务可读字段，不暴露 ref_table / 物理列。 */
    private static Map<String, Object> dimensionView(DimensionDetail detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", detail.code());
        row.put("name", detail.name());
        row.put("dim_type", detail.dimType());
        if (detail.values() != null) {
            row.put("values", detail.values().stream()
                    .map(v -> Map.of(
                            "code", v.valueCode() == null ? "" : v.valueCode(),
                            "name", v.valueLabel() == null ? "" : v.valueLabel()))
                    .toList());
        }
        return row;
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
