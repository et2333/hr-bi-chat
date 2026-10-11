package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptViewSnapshot;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptDataVersionMapper;
import com.hrchat.report.mapper.RptViewSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一报表数据版本生成（首次/手动刷新/定时刷新/旧报表初始化）。
 * 先授权取数并保存快照，再发布为当前版本；失败不覆盖上一成功版本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportDataVersionService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private final ReportService reportService;
    private final ReportChartService chartService;
    private final RptComponentMapper componentMapper;
    private final RptDataVersionMapper dataVersionMapper;
    private final RptViewSnapshotMapper viewSnapshotMapper;
    private final ObjectMapper objectMapper;

    private ReportTableService tableService;
    private org.springframework.transaction.support.TransactionTemplate snapshotTransaction;

    /** I2：版本发布后创建洞察任务；懒加载打破与图表/洞察服务的循环依赖。 */
    private InsightTaskService insightTaskService;

    @Autowired
    public void configure(ReportTableService tableService,
                          org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.tableService = tableService;
        this.snapshotTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        snapshotTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshotTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotTransaction.setReadOnly(true);
    }

    @Autowired
    public void setInsightTaskService(@Lazy InsightTaskService insightTaskService) {
        this.insightTaskService = insightTaskService;
    }

    public void requireScope(UserContext ctx, RptReport report, RptDataVersion version) {
        if (!java.util.Objects.equals(version.getScopeRef(), ReportDataScope.fingerprint(reportScope(ctx, report)))) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "当前数据权限与报表快照不一致，请由所有者刷新");
        }
    }

    private UserContext reportScope(UserContext ctx, RptReport report) {
        if (report.getContextJson() == null) return ctx;
        return ReportDataScope.narrow(ctx, chartService.parseMap(report.getContextJson()));
    }

    public void requireExportScope(UserContext ctx, Long reportId) {
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        UserContext effective = reportScope(ctx, report);
        if (!ReportDataScope.orgs(ctx, 3).containsAll(ReportDataScope.orgs(effective, 1))) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "导出权限未覆盖报表全部组织");
        }
    }

    public <T> T readSnapshot(UserContext ctx, Long reportId, Long componentId, String viewKey, Class<T> type) {
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        RptDataVersion version = requireReadyVersion(report);
        if (version == null) throw new BizException(ErrorCode.PARAM_INVALID, "报表数据尚未生成");
        requireScope(ctx, report, version);
        RptViewSnapshot snap = findSnapshot(version.getId(), componentId, viewKey);
        if (snap == null) throw new BizException(ErrorCode.PARAM_INVALID, "当前版本无法派生该视图");
        try { return objectMapper.readValue(snap.getChartJson(), type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException(ErrorCode.PARSE_FAILED, "报表快照不可解析");
        }
    }

    private void requireGenerator(UserContext ctx, RptReport report) {
        if (!java.util.Objects.equals(report.getOwnerId(), ctx.getUserId()))
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "仅报表所有者可生成或刷新报表");
    }

    public record VersionView(Long dataVersionId, int versionNo, String status, String dataAsOf,
                              String reportGeneratedAt, String failureReason, boolean published) {
    }

    /**
     * 确保存在当前成功版本；无则按幂等键生成一次（旧报表打开详情初始化）。
     */
    @Transactional
    public VersionView ensureCurrent(UserContext ctx, Long reportId, String idempotencyKey) {
        reportService.lockVersion(reportId);
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        if (report.getCurrentDataVersionId() != null) {
            RptDataVersion current = dataVersionMapper.selectById(report.getCurrentDataVersionId());
            if (current != null && STATUS_READY.equals(current.getStatus())
                    && java.util.Objects.equals(current.getDefinitionRevision(), report.getDefinitionRevision())) {
                requireScope(ctx, report, current);
                return toView(current, true);
            }
        }
        requireGenerator(ctx, report);
        String key = idemKey(ctx, reportId, "ensure:" + report.getDefinitionRevision(), idempotencyKey);
        return generate(ctx, report, key, false);
    }

    /** 显式刷新：新建数据版本；失败保留旧成功版本。 */
    @Transactional
    public VersionView refresh(UserContext ctx, Long reportId, String idempotencyKey) {
        reportService.lockVersion(reportId);
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        requireGenerator(ctx, report);
        String key = idemKey(ctx, reportId, "refresh:" + report.getDefinitionRevision(), idempotencyKey);
        return generate(ctx, report, key, true);
    }

    public RptDataVersion requireReadyVersion(RptReport report) {
        if (report.getCurrentDataVersionId() == null) {
            return null;
        }
        RptDataVersion version = dataVersionMapper.selectById(report.getCurrentDataVersionId());
        if (version == null || !STATUS_READY.equals(version.getStatus())
                || !java.util.Objects.equals(version.getDefinitionRevision(), report.getDefinitionRevision())) {
            return null;
        }
        return version;
    }

    public RptViewSnapshot findSnapshot(Long dataVersionId, Long componentId, String viewKey) {
        return viewSnapshotMapper.selectOne(new LambdaQueryWrapper<RptViewSnapshot>()
                .eq(RptViewSnapshot::getDataVersionId, dataVersionId)
                .eq(RptViewSnapshot::getComponentId, componentId)
                .eq(RptViewSnapshot::getViewKey, viewKey == null ? ViewKeySupport.ROOT : viewKey)
                .last("LIMIT 1"));
    }

    private VersionView generate(UserContext ctx, RptReport report, String key, boolean forceNew) {
        if (key != null) {
            RptDataVersion byDb = dataVersionMapper.selectOne(new LambdaQueryWrapper<RptDataVersion>()
                    .eq(RptDataVersion::getReportId, report.getId())
                    .eq(RptDataVersion::getIdempotencyKey, key)
                    .last("LIMIT 1"));
            if (byDb != null) {
                return toView(byDb, STATUS_READY.equals(byDb.getStatus())
                        && byDb.getId().equals(report.getCurrentDataVersionId()));
            }
        }

        int nextNo = nextVersionNo(report.getId());
        RptDataVersion version = new RptDataVersion();
        version.setReportId(report.getId());
        version.setVersionNo(nextNo);
        version.setDefinitionRevision(report.getDefinitionRevision() == null ? 1 : report.getDefinitionRevision());
        version.setStatus(STATUS_RUNNING);
        version.setSnapshotReuse(0);
        version.setScopeRef(ReportDataScope.fingerprint(reportScope(ctx, report)));
        version.setIdempotencyKey(key);
        version.setTenantId(TenantContextHolder.get());
        version.setCreatedAt(LocalDateTime.now());
        version.setCreatedBy(ctx.getEmpNo());
        dataVersionMapper.insert(version);

        try {
            List<RptComponent> components = componentMapper.selectList(new LambdaQueryWrapper<RptComponent>()
                    .eq(RptComponent::getReportId, report.getId()).orderByAsc(RptComponent::getSortNo));
            LocalDateTime dataAsOf = LocalDateTime.now();
            java.util.function.Supplier<List<RptViewSnapshot>> freeze = () -> {
                List<RptViewSnapshot> snapshots = new java.util.ArrayList<>();
                for (RptComponent component : components) materializeComponent(ctx, version.getId(), component, snapshots);
                return snapshots;
            };
            // Queries share one repeatable-read snapshot. Query failures cannot mark the metadata transaction rollback-only.
            List<RptViewSnapshot> snapshots = snapshotTransaction == null ? freeze.get()
                    : snapshotTransaction.execute(status -> freeze.get());
            for (RptViewSnapshot snap : snapshots) viewSnapshotMapper.insert(snap);
            version.setStatus(STATUS_READY);
            version.setDataAsOf(dataAsOf);
            version.setReportGeneratedAt(LocalDateTime.now());
            dataVersionMapper.updateById(version);
            reportService.updateCurrentDataVersion(report.getId(), version.getId());
            report.setCurrentDataVersionId(version.getId());
            // 洞察任务失败不影响图表版本；入队在事务提交后执行
            scheduleInsightTasks(report, version, components);
            return toView(version, true);
        } catch (RuntimeException ex) {
            log.warn("report data version failed reportId={} versionId={}: {}",
                    report.getId(), version.getId(), ex.getMessage());
            version.setStatus(STATUS_FAILED);
            version.setFailureReason(ex.getMessage() == null ? "生成失败" : trim(ex.getMessage(), 250));
            dataVersionMapper.updateById(version);
            // 失败不覆盖上一成功版本
            return toView(version, false);
        }
    }

    /** 版本 READY 后为图表组件创建/取得洞察任务；失败仅记录，不抛出以保护图表版本。 */
    private void scheduleInsightTasks(RptReport report, RptDataVersion version, List<RptComponent> components) {
        if (insightTaskService == null) {
            return;
        }
        try {
            insightTaskService.ensureAndSchedule(report, version, components, version.getCreatedBy());
        } catch (RuntimeException ex) {
            log.warn("schedule insight tasks failed reportId={} versionId={}: {}",
                    report.getId(), version.getId(), ex.getMessage());
        }
    }

    private void materializeComponent(UserContext ctx, Long dataVersionId, RptComponent chart, List<RptViewSnapshot> snapshots) {
        if (Integer.valueOf(3).equals(chart.getCompType())) {
            Object card = chartService.materializeMetricCard(chart, ctx);
            snapshots.add(snapshot(dataVersionId, chart.getId(), ViewKeySupport.ROOT, card, card));
            return;
        }
        if (Integer.valueOf(2).equals(chart.getCompType())) {
            Object table = tableService.materializeTable(chart, ctx);
            snapshots.add(snapshot(dataVersionId, chart.getId(), ViewKeySupport.ROOT, table, table));
            return;
        }
        if (!Integer.valueOf(1).equals(chart.getCompType())) return;
        ChartDataView root = chartService.materializeChart(chart, null, ctx);
        snapshots.add(snapshot(dataVersionId, chart.getId(), ViewKeySupport.ROOT, root, root));
        // 首版：为根分类预生成组织下钻快照，保证同版只读
        Map<String, Object> def = chartService.parseMap(chart.getDefJson());
        if ("org".equals(chartService.resolveDimKey(def))) {
            List<String> categories = "PIE".equalsIgnoreCase(root.chartType())
                    ? root.pieData().stream().map(p -> p.name()).toList() : root.categories();
            for (String category : categories) {
                if (category == null || category.isBlank()) {
                    continue;
                }
                try {
                    ChartDataView drilled = chartService.materializeChart(chart, category, ctx);
                    snapshots.add(snapshot(dataVersionId, chart.getId(), ViewKeySupport.forOrgDrill(category),
                            drilled, drilled));
                } catch (BizException ex) {
                    throw ex;
                }
            }
        }
    }

    private RptViewSnapshot snapshot(Long dataVersionId, Long componentId, String viewKey,
                              Object chart, Object aggregate) {
        RptViewSnapshot snap = new RptViewSnapshot();
        snap.setDataVersionId(dataVersionId);
        snap.setComponentId(componentId);
        snap.setViewKey(viewKey);
        snap.setChartJson(toJson(chart));
        snap.setAggregateJson(toJson(aggregate));
        snap.setCreatedAt(LocalDateTime.now());
        return snap;
    }

    private int nextVersionNo(Long reportId) {
        RptDataVersion latest = dataVersionMapper.selectOne(new LambdaQueryWrapper<RptDataVersion>()
                .eq(RptDataVersion::getReportId, reportId)
                .orderByDesc(RptDataVersion::getVersionNo)
                .last("LIMIT 1"));
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    private static String idemKey(UserContext ctx, Long reportId, String action, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String tenant = ctx.getTenantId() == null ? "_" : ctx.getTenantId();
        String source = tenant + ":" + reportId + ":" + action + ":" + raw.trim();
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private VersionView toView(RptDataVersion v, boolean published) {
        return new VersionView(v.getId(), v.getVersionNo() == null ? 0 : v.getVersionNo(), v.getStatus(),
                v.getDataAsOf() == null ? null : v.getDataAsOf().toString(),
                v.getReportGeneratedAt() == null ? null : v.getReportGeneratedAt().toString(),
                v.getFailureReason(), published);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, "JSON 序列化失败");
        }
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 调试/测试辅助：导出幂等缓存状态。 */
    Map<String, Object> debugIdempotency() {
        return Map.of();
    }
}
