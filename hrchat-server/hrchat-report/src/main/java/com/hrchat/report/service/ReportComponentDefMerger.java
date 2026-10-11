package com.hrchat.report.service;

import com.hrchat.api.report.ReportContext;
import com.hrchat.report.dto.ReportDtos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将展示配置与服务端恢复的业务口径合并：强制覆盖期间/指标/维度，去除任意折线默认近三月。
 */
public final class ReportComponentDefMerger {

    private ReportComponentDefMerger() {
    }

    public static List<ReportDtos.ComponentSpec> merge(ReportContext context,
                                                       List<ReportDtos.ComponentSpec> displaySpecs) {
        if (context == null) {
            return displaySpecs == null ? List.of() : displaySpecs;
        }
        if (displaySpecs == null || displaySpecs.isEmpty()) {
            return defaultSpecs(context);
        }
        List<ReportDtos.ComponentSpec> out = new ArrayList<>();
        for (ReportDtos.ComponentSpec spec : displaySpecs) {
            if (spec == null) {
                continue;
            }
            Map<String, Object> def = new LinkedHashMap<>();
            if (spec.def() != null) {
                def.putAll(spec.def());
            }
            applyContext(def, context, spec.compType(), spec.chartType());
            out.add(new ReportDtos.ComponentSpec(spec.compType(), spec.chartType(), def));
        }
        return out;
    }

    private static List<ReportDtos.ComponentSpec> defaultSpecs(ReportContext context) {
        List<String> dims = context.dimensions() == null || context.dimensions().isEmpty()
                ? List.of("org") : context.dimensions();
        boolean trend = dims.stream().anyMatch(d -> "time".equalsIgnoreCase(d));
        String chartType = trend ? "LINE" : "BAR";
        Map<String, Object> chartDef = baseDef(context, dims, trend);
        Map<String, Object> tableDef = baseDef(context, dims, trend);
        tableDef.put("title", trend ? "月度明细" : "数据明细");
        List<ReportDtos.ComponentSpec> specs = new ArrayList<>();
        specs.add(new ReportDtos.ComponentSpec("METRIC_CARD", null, Map.of(
                "metric", context.metricCode(),
                "title", context.metricName() == null ? context.metricCode() : context.metricName(),
                "unit", context.unit() == null ? "" : context.unit(),
                "definition", context.caliberDefinition() == null ? "" : context.caliberDefinition())));
        specs.add(new ReportDtos.ComponentSpec("CHART", chartType, chartDef));
        specs.add(new ReportDtos.ComponentSpec("TABLE", null, tableDef));
        return specs;
    }

    private static Map<String, Object> baseDef(ReportContext context, List<String> dims, boolean trend) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("metric", context.metricCode());
        def.put("title", context.metricName() == null ? context.metricCode() : context.metricName());
        def.put("dimensions", dims);
        def.put("dims", dims);
        applyPeriod(def, context, trend);
        return def;
    }

    private static void applyContext(Map<String, Object> def, ReportContext context,
                                     String compType, String chartType) {
        def.put("metric", context.metricCode());
        if (context.metricName() != null && !context.metricName().isBlank()) {
            Object title = def.get("title");
            if (title == null || String.valueOf(title).isBlank()
                    || String.valueOf(title).contains("近三月")) {
                def.put("title", context.metricName());
            }
        }
        List<String> dims = context.dimensions() == null || context.dimensions().isEmpty()
                ? inferDims(compType, chartType, def) : context.dimensions();
        def.put("dimensions", dims);
        def.put("dims", dims);
        boolean trend = dims.stream().anyMatch(d -> "time".equalsIgnoreCase(d))
                || "LINE".equalsIgnoreCase(chartType);
        applyPeriod(def, context, trend);
        if (context.unit() != null) {
            def.putIfAbsent("unit", context.unit());
        }
        if (context.caliberDefinition() != null) {
            def.putIfAbsent("definition", context.caliberDefinition());
        }
        if (context.organizationLabel() != null) {
            def.put("organization", context.organizationLabel());
        }
        if (context.metricVersion() != null) {
            def.put("metricVersion", context.metricVersion());
        }
    }

    private static void applyPeriod(Map<String, Object> def, ReportContext context, boolean trend) {
        // 始终移除前端默认近三月，再按恢复口径写入（柱/线/饼/表/指标卡均写入期间）
        def.remove("months");
        ReportContext.PeriodSpec period = context.period();
        if (period == null) {
            return;
        }
        if (period.displayLabel() != null && !period.displayLabel().isBlank()) {
            def.put("periodLabel", period.displayLabel());
        }
        if ("ROLLING".equalsIgnoreCase(period.kind()) && "LAST_N_MONTHS".equals(period.rollingRule())
                && period.rollingParam() != null) {
            def.put("periodKind", "ROLLING");
            def.put("rollingRule", "LAST_N_MONTHS");
            def.put("months", period.rollingParam());
            if (period.displayLabel() == null || period.displayLabel().isBlank()) {
                def.put("periodLabel", "近" + period.rollingParam() + "月");
            }
            return;
        }
        if (period.start() != null && period.endExclusive() != null) {
            def.put("periodKind", "FIXED");
            def.put("periodStart", period.start());
            def.put("periodEnd", period.endExclusive());
            if (period.displayLabel() == null || period.displayLabel().isBlank()) {
                def.put("periodLabel", period.start() + "/" + period.endExclusive());
            }
        }
    }

    private static List<String> inferDims(String compType, String chartType, Map<String, Object> def) {
        Object dims = def.get("dimensions");
        if (dims instanceof List<?> list && !list.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o).toLowerCase(Locale.ROOT));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        if ("LINE".equalsIgnoreCase(chartType)) {
            return List.of("time");
        }
        return List.of("org");
    }
}
