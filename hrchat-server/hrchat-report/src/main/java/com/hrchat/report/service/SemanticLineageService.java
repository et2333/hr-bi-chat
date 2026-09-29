package com.hrchat.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.report.dto.SemanticLineageItem;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义层引用血缘：扫描 rpt_component.def_json（{metric/metrics, dim/dimensions, filter}），
 * Java 侧 JSON 精确匹配 code，不使用 SQL LIKE（避免 headcount 误匹配 headcount_yoy）。
 *
 * <p>仅统计未删除报表（rpt_report 的 @TableLogic 自动过滤）。
 * 删除指标/维度的拦截与详情 Drawer 的血缘展示共用本服务，信息同源。</p>
 */
@Service
@RequiredArgsConstructor
public class SemanticLineageService {

    private final RptComponentMapper componentMapper;
    private final RptReportMapper reportMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 查询引用某指标的组件。 */
    public List<SemanticLineageItem> findMetricLineage(String metricCode) {
        return match(normalize(metricCode), true);
    }

    /** 查询引用某维度的组件。 */
    public List<SemanticLineageItem> findDimensionLineage(String dimCode) {
        return match(normalize(dimCode), false);
    }

    private List<SemanticLineageItem> match(String targetCode, boolean metric) {
        List<SemanticLineageItem> hits = new ArrayList<>();
        if (targetCode == null) {
            return hits;
        }
        Map<Long, RptReport> reports = loadReports();
        List<RptComponent> components = componentMapper.selectList(null);
        for (RptComponent comp : components) {
            RptReport report = reports.get(comp.getReportId());
            if (report == null) {
                continue;
            }
            Map<String, Object> def = parseMap(comp.getDefJson());
            Set<String> codes = metric
                    ? extractCodes(def, "metric", "metrics")
                    : extractCodes(def, "dim", "dimensions");
            if (codes.contains(targetCode)) {
                hits.add(new SemanticLineageItem(
                        report.getId(), report.getReportName(),
                        comp.getId(), comp.getCompType(), comp.getChartType()));
            }
        }
        return hits;
    }

    private Map<Long, RptReport> loadReports() {
        Map<Long, RptReport> map = new LinkedHashMap<>();
        for (RptReport report : reportMapper.selectList(null)) {
            map.put(report.getId(), report);
        }
        return map;
    }

    /** 提取单值/数组形态的 code：字符串直接取，对象取 code 字段。 */
    private Set<String> extractCodes(Map<String, Object> def, String singleKey, String listKey) {
        Set<String> codes = new HashSet<>();
        addCode(def.get(singleKey), codes);
        if (def.get(listKey) instanceof List<?> list) {
            for (Object o : list) {
                addCode(o, codes);
            }
        }
        return codes;
    }

    private void addCode(Object value, Set<String> codes) {
        if (value == null) {
            return;
        }
        String code;
        if (value instanceof Map<?, ?> map) {
            Object c = map.get("code");
            code = c == null ? null : String.valueOf(c);
        } else {
            code = String.valueOf(value);
        }
        if (code != null && !code.isBlank()) {
            codes.add(code.trim().toLowerCase());
        }
    }

    private String normalize(String code) {
        return code == null ? null : code.trim().toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
