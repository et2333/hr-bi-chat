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
        List<String> names = arguments != null && arguments.get("names") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();

        List<Map<String, Object>> objects = switch (type.trim().toUpperCase()) {
            case "METRIC" -> loadMetrics(names);
            case "DIMENSION" -> loadDimensions(names);
            case "SYNONYM" -> loadSynonyms(names);
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "type");
        };
        return Map.of("objects", objects);
    }

    private List<Map<String, Object>> loadMetrics(List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            PageResult<MetricSummary> page = semanticMetaService.listMetrics(null, 1, null, 1, 200);
            for (MetricSummary m : page.getRecords()) {
                out.add(metricView(semanticMetaService.getMetricByCode(m.code())));
            }
            return out;
        }
        for (String name : names) {
            out.add(metricView(semanticMetaService.getMetricByCode(name)));
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
        row.put("definition", detail.formulaExpr());
        row.put("domain", detail.domain());
        row.put("available_dimensions", detail.availableDimensions());
        row.put("version", detail.effectiveVersion());
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
