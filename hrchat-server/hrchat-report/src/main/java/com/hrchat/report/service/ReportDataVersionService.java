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

    /** 进程内幂等：key → versionId */
    private final Map<String, Long> idempotency = new ConcurrentHashMap<>();

    public record VersionView(Long dataVersionId, int versionNo, String status, String dataAsOf,
                              String reportGeneratedAt, String failureReason, boolean published) {
    }

    /**
     * 确保存在当前成功版本；无则按幂等键生成一次（旧报表打开详情初始化）。
     */
    @Transactional
    public VersionView ensureCurrent(UserContext ctx, Long reportId, String idempotencyKey) {
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        if (report.getCurrentDataVersionId() != null) {
            RptDataVersion current = dataVersionMapper.selectById(report.getCurrentDataVersionId());
            if (current != null && STATUS_READY.equals(current.getStatus())) {
                return toView(current, true);
            }
        }
        String key = idemKey(ctx, reportId, "ensure", idempotencyKey);
        return generate(ctx, report, key, false);
    }

    /** 显式刷新：新建数据版本；失败保留旧成功版本。 */
    @Transactional
    public VersionView refresh(UserContext ctx, Long reportId, String idempotencyKey) {
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        String key = idemKey(ctx, reportId, "refresh", idempotencyKey);
        return generate(ctx, report, key, true);
    }

    public RptDataVersion requireReadyVersion(RptReport report) {
        if (report.getCurrentDataVersionId() == null) {
            return null;
        }
        RptDataVersion version = dataVersionMapper.selectById(report.getCurrentDataVersionId());
        if (version == null || !STATUS_READY.equals(version.getStatus())) {
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
            Long existingId = idempotency.get(key);
            if (existingId != null) {
                RptDataVersion existing = dataVersionMapper.selectById(existingId);
                if (existing != null) {
                    return toView(existing, STATUS_READY.equals(existing.getStatus())
                            && existing.getId().equals(report.getCurrentDataVersionId()));
                }
            }
            RptDataVersion byDb = dataVersionMapper.selectOne(new LambdaQueryWrapper<RptDataVersion>()
                    .eq(RptDataVersion::getReportId, report.getId())
                    .eq(RptDataVersion::getIdempotencyKey, key)
                    .last("LIMIT 1"));
            if (byDb != null) {
                idempotency.put(key, byDb.getId());
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
        version.setSnapshotReuse(report.getSnapshotReuse() == null ? 0 : report.getSnapshotReuse());
        version.setScopeRef(ctx.getPermissionFingerprint());
        version.setIdempotencyKey(key);
        version.setTenantId(TenantContextHolder.get());
        version.setCreatedAt(LocalDateTime.now());
        version.setCreatedBy(ctx.getEmpNo());
        dataVersionMapper.insert(version);
        if (key != null) {
            idempotency.put(key, version.getId());
        }

        try {
            List<RptComponent> charts = componentMapper.selectList(new LambdaQueryWrapper<RptComponent>()
                    .eq(RptComponent::getReportId, report.getId())
                    .eq(RptComponent::getCompType, 1)
                    .orderByAsc(RptComponent::getSortNo));
            LocalDateTime dataAsOf = LocalDateTime.now();
            for (RptComponent chart : charts) {
                materializeComponent(ctx, version.getId(), chart);
            }
            version.setStatus(STATUS_READY);
            version.setDataAsOf(dataAsOf);
            version.setReportGeneratedAt(LocalDateTime.now());
            dataVersionMapper.updateById(version);
            reportService.updateCurrentDataVersion(report.getId(), version.getId());
            report.setCurrentDataVersionId(version.getId());
            return toView(version, true);
        } catch (RuntimeException ex) {
            log.warn("report data version failed reportId={} versionId={}: {}",
                    report.getId(), version.getId(), ex.getMessage());
            version.setStatus(STATUS_FAILED);
            version.setFailureReason(ex.getMessage() == null ? "生成失败" : trim(ex.getMessage(), 250));
            dataVersionMapper.updateById(version);
            // 失败不覆盖上一成功版本
            if (!forceNew && report.getCurrentDataVersionId() == null) {
                throw ex instanceof BizException be ? be
                        : new BizException(ErrorCode.SYSTEM_BUSY, "报表数据版本生成失败");
            }
            return toView(version, false);
        }
    }

    private void materializeComponent(UserContext ctx, Long dataVersionId, RptComponent chart) {
        ChartDataView root = chartService.materializeChart(chart, null, ctx);
        saveSnapshot(dataVersionId, chart.getId(), ViewKeySupport.ROOT, root, root);
        // 首版：为根分类预生成组织下钻快照，保证同版只读
        if (root.categories() != null && ("BAR".equalsIgnoreCase(root.chartType())
                || "PIE".equalsIgnoreCase(root.chartType()))) {
            for (String category : root.categories()) {
                if (category == null || category.isBlank()) {
                    continue;
                }
                try {
                    ChartDataView drilled = chartService.materializeChart(chart, category, ctx);
                    saveSnapshot(dataVersionId, chart.getId(), ViewKeySupport.forOrgDrill(category),
                            drilled, drilled);
                } catch (BizException ex) {
                    // 单个下钻失败不阻断根版本；读取时返回不可派生
                    log.debug("skip drill snapshot comp={} dim={}: {}", chart.getId(), category, ex.getMessage());
                }
            }
        }
    }

    private void saveSnapshot(Long dataVersionId, Long componentId, String viewKey,
                              ChartDataView chart, Object aggregate) {
        RptViewSnapshot snap = new RptViewSnapshot();
        snap.setDataVersionId(dataVersionId);
        snap.setComponentId(componentId);
        snap.setViewKey(viewKey);
        snap.setChartJson(toJson(chart));
        snap.setAggregateJson(toJson(aggregate));
        snap.setCreatedAt(LocalDateTime.now());
        viewSnapshotMapper.insert(snap);
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
        return tenant + ":" + reportId + ":" + action + ":" + raw.trim();
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
        return new LinkedHashMap<>(idempotency);
    }
}
