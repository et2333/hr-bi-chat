package com.hrchat.report.service;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.report.chart.ChartViews.TableColumn;
import com.hrchat.report.chart.ChartViews.TableView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C端明细表数据服务（P3-A 对标 QuickBI 表格：筛选/排序/分页）。
 *
 * <p>复用 {@link ReportChartService} 的组件定义解析与分组聚合 SQL 构造，
 * 多指标按 {@code dim_value} 合并为一行；维度筛选在内存完成（维度集有界，规避 SQL 注入面）；
 * 排序仅允许维度列与各指标列（白名单）；自建 COUNT + 分页切片。</p>
 */
@Service
@RequiredArgsConstructor
public class ReportTableService {

    public static final String PERM_READ = ReportService.PERM_READ;

    /** 维度列 key（前端绑定）。 */
    static final String DIM_KEY = "dimValue";

    private final ReportService reportService;
    private final RptComponentMapper componentMapper;
    private final SemanticMetaService semanticMetaService;
    private final AuthzService authzService;
    private final QueryExecService queryExecService;
    private final ReportChartService chartService;

    private ReportDataVersionService dataVersionService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setDataVersionService(@org.springframework.context.annotation.Lazy ReportDataVersionService service) {
        this.dataVersionService = service;
    }

    /**
     * 表格数据（服务端排序/分页）。
     *
     * @param sortField 排序字段（dimValue 或指标编码；白名单外拒绝）
     * @param sortOrder asc/desc（缺省 asc）
     * @param dimValues 维度值筛选（为空表示全部）
     */
    public TableView data(Long reportId, Long compId, UserContext ctx,
                          int page, int size, String sortField, String sortOrder, List<String> dimValues) {
        authzService.checkFunc(ctx, PERM_READ);
        reportService.requireViewAccess(ctx, reportId);
        RptComponent comp = requireTableComponent(reportId, compId);
        TableView frozen = dataVersionService == null ? materializeTable(comp, ctx)
                : dataVersionService.readSnapshot(ctx, reportId, compId, ViewKeySupport.ROOT, TableView.class);
        List<Map<String, Object>> rows = new ArrayList<>(frozen.rows());
        // 维度值筛选（内存过滤）
        if (dimValues != null && !dimValues.isEmpty()) {
            Set<String> wanted = new HashSet<>(dimValues);
            rows.removeIf(r -> !wanted.contains(String.valueOf(r.get(DIM_KEY))));
        }

        // 排序白名单 + 空值置底（比率指标可能为 NULL）
        String sortKey = sortField == null || sortField.isBlank() ? DIM_KEY : sortField;
        if (frozen.columns().stream().noneMatch(c -> c.key().equals(sortKey))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "sortField 不在白名单");
        }
        boolean desc = "desc".equalsIgnoreCase(sortOrder);
        rows.sort(comparator(sortKey, desc));

        // 自建 COUNT 分页
        long total = rows.size();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(200, size));
        int from = (safePage - 1) * safeSize;
        List<Map<String, Object>> slice = from >= rows.size()
                ? List.of()
                : rows.subList(from, Math.min(from + safeSize, rows.size()));

        return new TableView(frozen.columns(), slice, total, safePage, safeSize);
    }

    public TableView materializeTable(RptComponent comp, UserContext ctx) {
        TableDef def = parseDef(comp);
        ctx = ReportDataScope.narrow(ctx, def.periodDef());
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<TableColumn> columns = new ArrayList<>();
        columns.add(new TableColumn(DIM_KEY, dimName(def.dim()), "string"));
        for (MetricDetail metric : def.metrics()) {
            String sql = chartService.buildScopedSql(metric, def.dim(), def.periodDef(), null);
            QueryResult result = queryExecService.executeReadonly(authzService.authorizeSql(sql, ctx));
            merge(result, metric.code(), merged);
            for (Map<String, Object> row : merged.values()) {
                if (row.get(metric.code()) instanceof Number n)
                    row.put(metric.code(), ReportChartService.scalePercent(metric, n));
            }
            columns.add(new TableColumn(metric.code(), displayName(metric), "number"));
        }
        List<Map<String, Object>> rows = new ArrayList<>(merged.values());
        return new TableView(columns, rows, rows.size(), 1, rows.size());
    }

    public List<String> dimValues(Long reportId, Long compId, UserContext ctx) {
        authzService.checkFunc(ctx, PERM_READ);
        reportService.requireViewAccess(ctx, reportId);
        RptComponent comp = requireTableComponent(reportId, compId);
        TableView frozen = dataVersionService == null ? materializeTable(comp, ctx)
                : dataVersionService.readSnapshot(ctx, reportId, compId, ViewKeySupport.ROOT, TableView.class);
        return frozen.rows().stream().map(r -> String.valueOf(r.get(DIM_KEY))).distinct().sorted().toList();
    }

    // =================================================================
    // 内部工具
    // =================================================================

    private RptComponent requireTableComponent(Long reportId, Long compId) {
        RptComponent comp = componentMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RptComponent>()
                .eq(RptComponent::getReportId, reportId).eq(RptComponent::getId, compId));
        if (comp == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compId");
        }
        if (!Integer.valueOf(2).equals(comp.getCompType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compId（非明细表组件）");
        }
        return comp;
    }

    /** 组件定义解析：指标列表（metrics 或单 metric）+ 维度（缺省 org；兼容 dims）。 */
    private TableDef parseDef(RptComponent comp) {
        Map<String, Object> def = chartService.parseMap(comp.getDefJson());
        List<String> codes = new ArrayList<>();
        Object metricsObj = def.get("metrics");
        if (metricsObj instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) {
                codes.add(String.valueOf(o));
            }
        }
        if (codes.isEmpty()) {
            String single = chartService.asString(def.get("metric"));
            if (single != null && !single.isBlank()) {
                codes.add(single);
            }
        }
        if (codes.isEmpty()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "组件缺少 metric");
        }
        String dimKey = chartService.resolveDimKey(def);
        Map<String, Object> periodDef = new LinkedHashMap<>();
        if (def.get("periodStart") != null) {
            periodDef.put("periodStart", def.get("periodStart"));
        }
        if (def.get("periodEnd") != null) {
            periodDef.put("periodEnd", def.get("periodEnd"));
        }
        if (def.get("months") != null) {
            periodDef.put("months", def.get("months"));
        }
        List<MetricDetail> metrics = new ArrayList<>();
        for (String code : codes) {
            MetricDetail metric = semanticMetaService.getMetricByCode(code);
            if (metric == null) {
                throw new BizException(ErrorCode.PARSE_FAILED, "指标不存在 " + code);
            }
            metrics.add(metric);
        }
        return new TableDef(dimKey, metrics, def);
    }

    /** 排序白名单：dimValue 或任一指标编码；否则拒绝。 */
    private String normalizeSort(String sortField, List<MetricDetail> metrics) {
        if (sortField == null || sortField.isBlank()) {
            return DIM_KEY;
        }
        if (DIM_KEY.equals(sortField)) {
            return DIM_KEY;
        }
        for (MetricDetail m : metrics) {
            if (m.code().equals(sortField)) {
                return sortField;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "sortField 不在白名单");
    }

    /** 合并指标结果到按 dim_value 分组的行。 */
    private void merge(QueryResult result, String metricCode, Map<String, Map<String, Object>> merged) {
        for (Map<String, Object> row : result.rows()) {
            Object dimValue = row.get("dim_value");
            Object metricValue = row.get("metric_value");
            if (dimValue == null) {
                continue;
            }
            String dimKey = String.valueOf(dimValue);
            Map<String, Object> target = merged.computeIfAbsent(dimKey, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(DIM_KEY, dimKey);
                return m;
            });
            if (metricValue instanceof Number n) {
                target.put(metricCode, n);
            }
        }
    }

    /** 数值优先、字符串兜底；空值恒置底。 */
    private Comparator<Map<String, Object>> comparator(String key, boolean desc) {
        return (a, b) -> {
            Object av = a.get(key);
            Object bv = b.get(key);
            if (av == null && bv == null) {
                return 0;
            }
            if (av == null) {
                return 1;
            }
            if (bv == null) {
                return -1;
            }
            int c;
            if (av instanceof Number an && bv instanceof Number bn) {
                c = Double.compare(an.doubleValue(), bn.doubleValue());
            } else {
                c = String.valueOf(av).compareTo(String.valueOf(bv));
            }
            return desc ? -c : c;
        };
    }

    private String dimName(String dim) {
        return switch (dim) {
            case "org" -> "组织";
            case "time" -> "月份";
            case "gender" -> "性别";
            case "job_family" -> "职类";
            case "job_level" -> "职级";
            case "change_type" -> "变动类型";
            default -> "维度";
        };
    }

    private String displayName(MetricDetail metric) {
        return metric.name() == null || metric.name().isBlank() ? metric.code() : metric.name();
    }

    private record TableDef(String dim, List<MetricDetail> metrics, Map<String, Object> periodDef) {
    }
}
