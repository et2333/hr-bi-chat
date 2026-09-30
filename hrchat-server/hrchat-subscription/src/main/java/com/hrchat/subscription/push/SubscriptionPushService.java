package com.hrchat.subscription.push;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartPieDatum;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptSnapshot;
import com.hrchat.report.entity.RptSubReceiver;
import com.hrchat.report.entity.RptSubscription;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.report.mapper.RptSnapshotMapper;
import com.hrchat.report.mapper.RptSubReceiverMapper;
import com.hrchat.report.mapper.RptSubscriptionMapper;
import com.hrchat.report.service.ReportChartService;
import com.hrchat.report.service.ReportExcelExporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 订阅推送服务（接口文档 2.3.2 推送链路，S5 subscription-svc）。
 *
 * <p>链路：调度扫描到期订阅 → 按收件人逐个实时权限校验（BR-13）→ 推送（失败重试 3 次）→
 * 渲染快照（本地 JSON 键）→ 推进下次执行时间。阶段3：推送时生成 XLSX 附件
 * （ReportChartService 取数 → ReportExcelExporter），写入本地 push-out 目录并记日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionPushService {

    /** 推送失败最大重试次数 */
    public static final int MAX_RETRIES = 3;

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RptSubscriptionMapper subscriptionMapper;
    private final RptSubReceiverMapper receiverMapper;
    private final RptSnapshotMapper snapshotMapper;
    private final RptReportMapper reportMapper;
    private final SecUserMapper secUserMapper;
    private final AuthzService authzService;
    private final PushChannelClient pushChannelClient;
    private final ReportChartService chartService;
    private final ReportExcelExporter excelExporter;

    /** 推送附件输出目录（默认由配置注入；单测可 setOutputDir 覆盖）。 */
    @Value("${hrchat.push.output-dir:}")
    private String outputDir = "";

    /** 手动触发一轮到期扫描的结果。 */
    public record PushTriggerResult(int pushed, int skipped) {
    }

    /** 扫描并推送所有到期订阅。返回成功推送的收件人次数。 */
    @Transactional
    public int processDueSubscriptions() {
        return scanDue(null).receiverPushes();
    }

    /** 手动触发（admin:push:manage）：reportId 有值则只推该报表的到期订阅。 */
    @Transactional
    public PushTriggerResult triggerPush(Long reportId) {
        ScanResult r = scanDue(reportId);
        return new PushTriggerResult(r.pushedSubs(), r.skippedSubs());
    }

    private ScanResult scanDue(Long reportId) {
        List<RptSubscription> due = subscriptionMapper.selectList(new LambdaQueryWrapper<RptSubscription>()
                .eq(RptSubscription::getStatus, 1)
                .eq(RptSubscription::getIsDeleted, 0)
                .eq(reportId != null, RptSubscription::getReportId, reportId)
                .le(RptSubscription::getNextRunAt, LocalDateTime.now()));
        int receiverPushes = 0;
        int pushedSubs = 0;
        int skippedSubs = 0;
        for (RptSubscription sub : due) {
            try {
                int ok = pushOne(sub);
                receiverPushes += ok;
                if (ok > 0) {
                    pushedSubs++;
                } else {
                    skippedSubs++;
                }
            } catch (Exception e) {
                skippedSubs++;
                log.error("订阅推送失败: subId={}, traceId={}", sub.getId(),
                        com.hrchat.common.context.TraceContext.currentTraceId(), e);
            }
        }
        return new ScanResult(receiverPushes, pushedSubs, skippedSubs);
    }

    /** 单订阅推送：生成附件 → 逐收件人 BR-13 鉴权 → 推送（重试 ≤3）→ 快照。 */
    int pushOne(RptSubscription sub) {
        String attachmentPath = generateAttachment(sub);
        List<RptSubReceiver> receivers = receiverMapper.selectList(new LambdaQueryWrapper<RptSubReceiver>()
                .eq(RptSubReceiver::getSubscriptionId, sub.getId()));
        int ok = 0;
        for (RptSubReceiver receiver : receivers) {
            boolean allowed = isReceiverAllowed(receiver.getUserId(), sub.getReportId());
            if (!allowed) {
                // BR-13：订阅链接打开/推送时实时鉴权，无权限收件人标记失败并跳过
                mark(receiver, 2);
                continue;
            }
            boolean pushed = pushWithRetry(sub, receiver, attachmentPath);
            mark(receiver, pushed ? 1 : 2);
            if (pushed) {
                generateSnapshot(sub);
                ok++;
            }
        }
        sub.setNextRunAt(advance(sub));
        sub.setUpdatedAt(LocalDateTime.now());
        subscriptionMapper.updateById(sub);
        return ok;
    }

    /** BR-13 实时鉴权：报表所有者恒允许；其余按 report:view 功能权限实时裁决。 */
    boolean isReceiverAllowed(Long userId, Long reportId) {
        RptReport report = reportMapper.selectById(reportId);
        if (report == null || Integer.valueOf(1).equals(report.getIsDeleted())) {
            return false;
        }
        SecUser user = secUserMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        try {
            UserContext receiverCtx = authzService.resolveContext(user.getEmpNo());
            if (!java.util.Objects.equals(receiverCtx.getTenantId(), report.getTenantId())) {
                return false;
            }
            if (report.getOwnerId().equals(userId)) {
                return true;
            }
            authzService.checkFunc(receiverCtx, "report:view");
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    /** 推送并重试（≤3 次，接口文档 2.3.2）。 */
    boolean pushWithRetry(RptSubscription sub, RptSubReceiver receiver) {
        return pushWithRetry(sub, receiver, null);
    }

    /** 推送并重试（带附件路径日志，模拟邮件/IM 附件推送）。 */
    boolean pushWithRetry(RptSubscription sub, RptSubReceiver receiver, String attachmentPath) {
        if (attachmentPath != null && !attachmentPath.isBlank()) {
            log.info("订阅推送附件: subId={}, receiverId={}, path={}", sub.getId(), receiver.getId(), attachmentPath);
        }
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                boolean sent = pushChannelClient.send(sub.getChannel(), receiver.getUserId(),
                        sub.getReportId(), snapshotUrl(sub, receiver));
                if (sent) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("订阅推送异常，重试 {}/{}: subId={}, receiverId={}",
                        attempt + 1, MAX_RETRIES, sub.getId(), receiver.getId(), e);
            }
        }
        return false;
    }

    /** 渲染快照（本地生成 rpt_snapshot 索引行，file_key 指向 MinIO/本地对象键）。 */
    void generateSnapshot(RptSubscription sub) {
        RptSnapshot snapshot = new RptSnapshot();
        snapshot.setReportId(sub.getReportId());
        snapshot.setSubId(sub.getId());
        snapshot.setFileKey("snap/report-" + sub.getReportId() + "/" + UUID.randomUUID().toString().substring(0, 12) + ".json");
        snapshot.setGeneratedAt(LocalDateTime.now());
        snapshot.setExpireAt(LocalDateTime.now().plusDays(90));
        snapshotMapper.insert(snapshot);
    }

    private String snapshotUrl(RptSubscription sub, RptSubReceiver receiver) {
        return "/api/v1/reports/" + sub.getReportId() + "/snapshots/latest?token=local";
    }

    private void mark(RptSubReceiver receiver, int status) {
        RptSubReceiver update = new RptSubReceiver();
        update.setId(receiver.getId());
        update.setPushStatus(status);
        receiverMapper.updateById(update);
    }

    /** 推进下次执行时间（日/周/月，简单前推）。 */
    LocalDateTime advance(RptSubscription sub) {
        LocalDateTime next = sub.getNextRunAt();
        if (next == null) {
            next = LocalDateTime.now();
        }
        return switch (sub.getFreq() == null ? 1 : sub.getFreq()) {
            case 2 -> next.plusWeeks(1);
            case 3 -> next.plusMonths(1);
            default -> next.plusDays(1);
        };
    }

    // =================================================================
    // 推送附件（阶段3：XLSX，写本地 push-out 目录）
    // =================================================================

    /** 生成订阅推送附件（统一 XLSX）：报表首个图表组件取数 → 导出器 → 本地文件。 */
    String generateAttachment(RptSubscription sub) {
        if (outputDir == null || outputDir.isBlank()) {
            log.debug("未配置推送附件目录，跳过附件: subId={}", sub.getId());
            return null;
        }
        try {
            RptReport report = reportMapper.selectById(sub.getReportId());
            String reportName = report == null || report.getReportName() == null
                    ? "报表" + sub.getReportId() : report.getReportName();
            UserContext ownerCtx = report == null ? null : resolveOwnerContext(report);
            ChartDataView view = chartService.chartDataForExport(sub.getReportId(), ownerCtx);
            TableData table = toTable(view);
            byte[] bytes = excelExporter.export(reportName, ownerCtx == null ? "" : ownerCtx.getEmpNo(),
                    table.headers(), table.rows());
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);
            String fileName = "report-" + sub.getReportId() + "-" + LocalDateTime.now().format(FILE_TS) + ".xlsx";
            Path file = dir.resolve(fileName);
            Files.write(file, bytes);
            log.info("订阅推送附件已生成: subId={}, path={}", sub.getId(), file);
            return file.toString();
        } catch (Exception e) {
            log.warn("订阅推送附件生成失败，继续推送: subId={}, err={}", sub.getId(), e.getMessage());
            return null;
        }
    }

    private UserContext resolveOwnerContext(RptReport report) {
        SecUser owner = report.getOwnerId() == null ? null : secUserMapper.selectById(report.getOwnerId());
        if (owner == null || owner.getEmpNo() == null) {
            return null;
        }
        return authzService.resolveContext(owner.getEmpNo());
    }

    /** 图表视图 → 二维表（BAR/LINE 取 categories+series，PIE 取 pieData）。 */
    private TableData toTable(ChartDataView view) {
        if (view == null) {
            return new TableData(List.of("提示"), List.of(List.of("暂无图表数据")));
        }
        List<List<String>> rows = new ArrayList<>();
        if (view.pieData() != null && !view.pieData().isEmpty()) {
            for (ChartPieDatum p : view.pieData()) {
                rows.add(List.of(String.valueOf(p.name()), String.valueOf(p.value())));
            }
            return new TableData(List.of("维度", "数值"), rows);
        }
        String seriesName = (view.series() == null || view.series().isEmpty()
                || view.series().get(0).name() == null) ? "数值" : view.series().get(0).name();
        List<String> categories = view.categories() == null ? List.of() : view.categories();
        List<Number> data = (view.series() == null || view.series().isEmpty())
                ? List.of() : view.series().get(0).data();
        for (int i = 0; i < categories.size(); i++) {
            Number v = i < data.size() ? data.get(i) : null;
            rows.add(List.of(categories.get(i), v == null ? "" : String.valueOf(v)));
        }
        return new TableData(List.of("维度", seriesName), rows);
    }

    /** 扫描结果（收件人推送次数 / 推送成功订阅数 / 跳过订阅数）。 */
    private record ScanResult(int receiverPushes, int pushedSubs, int skippedSubs) {
    }

    /** 附件二维表。 */
    private record TableData(List<String> headers, List<List<String>> rows) {
    }

    /** 设置推送附件输出目录（配置/测试注入）。 */
    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }
}
