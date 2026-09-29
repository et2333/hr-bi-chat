package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.tenant.Tenant;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.dto.ReportViews;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptSnapshot;
import com.hrchat.report.entity.RptSubReceiver;
import com.hrchat.report.entity.RptSubscription;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.report.mapper.RptSnapshotMapper;
import com.hrchat.report.mapper.RptSubReceiverMapper;
import com.hrchat.report.mapper.RptSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 报表服务（接口文档 2.3.1/2.3.2/2.3.4，S5 report-svc）。
 *
 * <p>覆盖：报表 CRUD（三种来源）、订阅管理（BR-13 快照语义）、快照查询。
 * 权限：读 {@code report:view}、建 {@code report:create}、管 {@code report:manage}（所有者）、
 * 订阅 {@code report:subscribe}（FR-14）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    public static final String PERM_READ = "report:view";
    public static final String PERM_CREATE = "report:create";
    public static final String PERM_MANAGE = "report:manage";
    public static final String PERM_SUBSCRIBE = "report:subscribe";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RptReportMapper reportMapper;
    private final RptComponentMapper componentMapper;
    private final RptSubscriptionMapper subscriptionMapper;
    private final RptSubReceiverMapper receiverMapper;
    private final RptSnapshotMapper snapshotMapper;
    private final SecUserMapper secUserMapper;
    private final AuthzService authzService;
    private final ObjectMapper objectMapper;
    private final TenantMapper tenantMapper;

    /** 订阅幂等：header → subscriptionId（回放返回原值）。 */
    private final Map<String, Long> subscribeIdempotency = new ConcurrentHashMap<>();

    // =================================================================
    // 报表 CRUD
    // =================================================================

    @Transactional
    public Long create(UserContext ctx, ReportDtos.ReportCreateRequest request, String idempotencyKey) {
        authzService.checkFunc(ctx, PERM_CREATE);
        checkReportQuota();
        if (request == null || request.sourceType() == null || request.sourceType().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "source_type");
        }
        String name = request.name() == null ? null : request.name().trim();
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID, "name（≤64字符）");
        }
        int sourceType = switch (request.sourceType().toUpperCase()) {
            case "ASK" -> 1;
            case "TEMPLATE" -> 2;
            case "CUSTOM" -> 3;
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "source_type");
        };
        if (sourceType == 1 && (request.sourceId() == null || request.sourceId().isBlank())) {
            throw new BizException(ErrorCode.PARAM_MISSING, "source_id（ASK 需 ask_id）");
        }
        if (sourceType == 3 && (request.components() == null || request.components().isEmpty())) {
            throw new BizException(ErrorCode.PARAM_MISSING, "components（CUSTOM 需组件定义）");
        }

        RptReport report = new RptReport();
        report.setReportName(name);
        report.setOwnerId(ctx.getUserId());
        report.setSourceType(sourceType);
        if (request.sourceId() != null && request.sourceId().length() <= 19
                && request.sourceId().chars().allMatch(Character::isDigit)) {
            report.setTemplateId(Long.valueOf(request.sourceId()));
        }
        report.setParamsJson(toJson(request.params() == null ? Map.of() : request.params()));
        report.setStatus(1);
        report.setTenantId(TenantContextHolder.get());
        report.setIsDeleted(0);
        report.setCreatedBy(ctx.getEmpNo());
        report.setUpdatedBy(ctx.getEmpNo());
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.insert(report);

        saveComponents(report.getId(), request.components());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            subscribeIdempotency.put("report:" + idempotencyKey, report.getId());
        }
        return report.getId();
    }

    /** 报表列表（scope：mine/subscribed/shared）。 */
    public PageResult<ReportViews.ReportSummary> list(UserContext ctx, String scope, String keyword,
                                                      int page, int size) {
        authzService.checkFunc(ctx, PERM_READ);
        LambdaQueryWrapper<RptReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RptReport::getIsDeleted, 0);
        // 仅显式租户请求（X-Tenant-No）时按租户过滤，无租户头=单租户兼容视图
        String tenantId = TenantContextHolder.get();
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(RptReport::getTenantId, tenantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(RptReport::getReportName, keyword.trim());
        }
        wrapper.orderByDesc(RptReport::getUpdatedAt);
        List<RptReport> all = reportMapper.selectList(wrapper);

        String scopeKey = scope == null || scope.isBlank() ? "mine" : scope.trim().toLowerCase();
        Set<Long> subscribedIds = subscribedReportIds(ctx.getUserId());
        List<RptReport> filtered = new ArrayList<>();
        for (RptReport r : all) {
            boolean mine = r.getOwnerId().equals(ctx.getUserId());
            boolean subscribed = subscribedIds.contains(r.getId());
            boolean hit = switch (scopeKey) {
                case "mine" -> mine;
                case "subscribed" -> subscribed;
                case "shared" -> subscribed && !mine;
                default -> true;
            };
            if (hit) {
                filtered.add(r);
            }
        }
        List<ReportViews.ReportSummary> records = filtered.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(r -> toSummary(r, countSubscriptions(r.getId())))
                .toList();
        return PageResult.of(records, filtered.size(), page, size);
    }

    /** 报表详情（含组件、最近快照、订阅状态；BR-05 访问隔离）。 */
    public ReportViews.ReportDetail getDetail(UserContext ctx, Long reportId) {
        authzService.checkFunc(ctx, PERM_READ);
        RptReport report = requireReport(reportId);
        requireAccess(ctx, report);

        List<RptComponent> comps = componentMapper.selectList(new LambdaQueryWrapper<RptComponent>()
                .eq(RptComponent::getReportId, reportId).orderByAsc(RptComponent::getSortNo));
        List<ReportViews.ComponentView> compViews = comps.stream()
                .map(c -> new ReportViews.ComponentView(c.getId(), compTypeName(c.getCompType()),
                        c.getChartType(), parseMap(c.getDefJson())))
                .toList();

        List<RptSubscription> subs = listVisibleSubscriptions(ctx, reportId);
        List<ReportViews.SubscriptionView> subViews = subs.stream()
                .map(s -> toSubscriptionView(s, receiverCount(s.getId()))).toList();

        ReportViews.SnapshotView latest = latestSnapshot(reportId);

        return new ReportViews.ReportDetail(reportId, report.getReportName(), sourceTypeName(report.getSourceType()),
                report.getTemplateId(), parseMap(report.getParamsJson()), report.getMetricVersions(),
                compViews, "DAILY 08:00", report.getCreatedAt() == null ? "" : TS.format(report.getCreatedAt()),
                subViews, latest);
    }

    @Transactional
    public void patch(UserContext ctx, Long reportId, ReportDtos.ReportPatchRequest request) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        RptReport report = requireOwner(ctx, reportId);
        if (request.name() != null && !request.name().isBlank()) {
            if (request.name().trim().length() > 64) {
                throw new BizException(ErrorCode.PARAM_INVALID, "name（≤64字符）");
            }
            report.setReportName(request.name().trim());
        }
        report.setUpdatedBy(ctx.getEmpNo());
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        if (request.components() != null) {
            componentMapper.delete(new LambdaQueryWrapper<RptComponent>()
                    .eq(RptComponent::getReportId, reportId));
            saveComponents(reportId, request.components());
        }
    }

    @Transactional
    public void delete(UserContext ctx, Long reportId) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        RptReport report = requireOwner(ctx, reportId);
        report.setStatus(2);
        report.setIsDeleted(1);
        report.setUpdatedBy(ctx.getEmpNo());
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);
    }

    // =================================================================
    // 订阅管理（FR-14 / BR-13）
    // =================================================================

    @Transactional
    public ReportViews.SubscriptionView subscribe(UserContext ctx, Long reportId,
                                                  ReportDtos.SubscribeRequest request, String idempotencyKey) {
        authzService.checkFunc(ctx, PERM_SUBSCRIBE);
        checkSubscriptionQuota();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Long existing = subscribeIdempotency.get("sub:" + idempotencyKey);
            if (existing != null) {
                RptSubscription s = subscriptionMapper.selectById(existing);
                if (s != null && !Integer.valueOf(1).equals(s.getIsDeleted())) {
                    return toSubscriptionView(s, receiverCount(s.getId()));
                }
            }
        }
        RptReport report = requireReport(reportId);
        requireAccess(ctx, report);

        int freq = switch (request.frequency() == null ? "" : request.frequency().toUpperCase()) {
            case "DAILY" -> 1;
            case "WEEKLY" -> 2;
            case "MONTHLY" -> 3;
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "frequency");
        };
        int channel = switch (request.channel() == null ? "" : request.channel().toUpperCase()) {
            case "EMAIL" -> 1;
            case "WECOM" -> 2;
            case "DINGTALK" -> 3;
            case "FEISHU" -> 4;
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "channel");
        };
        if (request.receivers() == null || request.receivers().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "receivers");
        }

        // 收件人逐个解析为 sec_user.id，权限快照仅作展示（BR-13 访问实时裁决）
        List<Map<String, Object>> receivers = new ArrayList<>();
        for (ReportDtos.Receiver r : request.receivers()) {
            if (r == null || r.id() == null || r.id().isBlank()) {
                continue;
            }
            SecUser user = secUserMapper.selectOne(new LambdaQueryWrapper<SecUser>()
                    .eq(SecUser::getEmpNo, r.id().trim()).last("LIMIT 1"));
            if (user == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "收件人工号不存在: " + r.id());
            }
            Map<String, Object> item = new HashMap<>();
            item.put("type", r.type() == null ? "USER" : r.type());
            item.put("emp_no", user.getEmpNo());
            item.put("user_id", user.getId());
            receivers.add(item);
        }
        if (receivers.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "receivers");
        }

        RptSubscription sub = new RptSubscription();
        sub.setReportId(reportId);
        sub.setFreq(freq);
        sub.setChannel(channel);
        sub.setNextRunAt(computeNextRun(request.frequency(), request.pushTime(), request.dayOfMonth()));
        sub.setStatus(1);
        sub.setTenantId(TenantContextHolder.get());
        sub.setPermSnapshotJson(toJson(receivers));
        sub.setIsDeleted(0);
        sub.setCreatedBy(ctx.getEmpNo());
        sub.setUpdatedBy(ctx.getEmpNo());
        sub.setUpdatedAt(LocalDateTime.now());
        subscriptionMapper.insert(sub);

        for (Map<String, Object> item : receivers) {
            RptSubReceiver receiver = new RptSubReceiver();
            receiver.setSubscriptionId(sub.getId());
            receiver.setUserId(((Number) item.get("user_id")).longValue());
            receiver.setPushStatus(0);
            receiverMapper.insert(receiver);
        }
        subscribeIdempotency.put("sub:" + idempotencyKey, sub.getId());

        return toSubscriptionView(sub, receivers.size());
    }

    /** 订阅列表：所有者视角全量；他人仅自己（接口文档 2.3.2）。 */
    public List<ReportViews.SubscriptionView> listSubscriptions(UserContext ctx, Long reportId) {
        authzService.checkFunc(ctx, PERM_SUBSCRIBE);
        requireReport(reportId);
        return listVisibleSubscriptions(ctx, reportId).stream()
                .map(s -> toSubscriptionView(s, receiverCount(s.getId()))).toList();
    }

    @Transactional
    public void cancelSubscription(UserContext ctx, Long reportId, Long subscriptionId) {
        authzService.checkFunc(ctx, PERM_SUBSCRIBE);
        RptReport report = requireReport(reportId);
        RptSubscription sub = subscriptionMapper.selectById(subscriptionId);
        if (sub == null || !sub.getReportId().equals(reportId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "subscriptionId");
        }
        boolean owner = report.getOwnerId().equals(ctx.getUserId());
        boolean receiver = receiverMapper.selectCount(new LambdaQueryWrapper<RptSubReceiver>()
                .eq(RptSubReceiver::getSubscriptionId, subscriptionId)
                .eq(RptSubReceiver::getUserId, ctx.getUserId())) > 0;
        if (!owner && !receiver) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        sub.setStatus(0);
        sub.setIsDeleted(1);
        sub.setUpdatedBy(ctx.getEmpNo());
        sub.setUpdatedAt(LocalDateTime.now());
        subscriptionMapper.updateById(sub);
        receiverMapper.delete(new LambdaQueryWrapper<RptSubReceiver>()
                .eq(RptSubReceiver::getSubscriptionId, subscriptionId));
    }

    // =================================================================
    // 快照（接口文档 2.3.4）
    // =================================================================

    public PageResult<ReportViews.SnapshotView> listSnapshots(UserContext ctx, Long reportId,
                                                              int page, int size) {
        authzService.checkFunc(ctx, PERM_READ);
        requireAccess(ctx, requireReport(reportId));
        List<RptSnapshot> all = snapshotMapper.selectList(new LambdaQueryWrapper<RptSnapshot>()
                .eq(RptSnapshot::getReportId, reportId)
                // 仅显式租户请求时按租户过滤
                .eq(TenantContextHolder.get() != null && !TenantContextHolder.get().isBlank(),
                        RptSnapshot::getTenantId, TenantContextHolder.get())
                .orderByDesc(RptSnapshot::getGeneratedAt));
        List<ReportViews.SnapshotView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(this::toSnapshotView).toList();
        return PageResult.of(records, all.size(), page, size);
    }

    public ReportViews.SnapshotView getSnapshot(UserContext ctx, Long reportId, Long snapshotId) {
        authzService.checkFunc(ctx, PERM_READ);
        requireAccess(ctx, requireReport(reportId));
        RptSnapshot snap = snapshotMapper.selectById(snapshotId);
        if (snap == null || !snap.getReportId().equals(reportId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "snapshotId");
        }
        return toSnapshotView(snap);
    }

    // =================================================================
    // 内部：访问裁决 / 组装 / 工具
    // =================================================================

    /**
     * 读路径访问校验（BR-05：owner/share/admin），供图表数据/导出取数复用。
     *
     * @param ctx      用户上下文
     * @param reportId 报表 id
     */
    public void requireViewAccess(UserContext ctx, Long reportId) {
        requireAccess(ctx, requireReport(reportId));
    }

    private RptReport requireReport(Long reportId) {
        RptReport report = reportMapper.selectById(reportId);
        if (report == null || Integer.valueOf(1).equals(report.getIsDeleted())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "reportId");
        }
        return report;
    }

    /**
     * 报表数配额校验：仅显式租户请求且租户配额记录存在时生效；
     * 无租户头/租户无配额记录 = 单租户兼容，跳过校验。
     */
    private void checkReportQuota() {
        String tenantId = TenantContextHolder.get();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        Tenant tenant = findTenant(tenantId);
        if (tenant == null || tenant.getReportQuota() == null) {
            return;
        }
        long used = reportMapper.selectCount(new LambdaQueryWrapper<RptReport>()
                .eq(RptReport::getTenantId, tenantId));
        if (used >= tenant.getReportQuota()) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, "报表数");
        }
    }

    /**
     * 订阅数配额校验：语义同 {@link #checkReportQuota()}。
     */
    private void checkSubscriptionQuota() {
        String tenantId = TenantContextHolder.get();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        Tenant tenant = findTenant(tenantId);
        if (tenant == null || tenant.getSubscriptionQuota() == null) {
            return;
        }
        long used = subscriptionMapper.selectCount(new LambdaQueryWrapper<RptSubscription>()
                .eq(RptSubscription::getTenantId, tenantId));
        if (used >= tenant.getSubscriptionQuota()) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, "订阅数");
        }
    }

    private Tenant findTenant(String tenantCode) {
        return tenantMapper == null ? null : tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>()
                        .eq(Tenant::getTenantCode, tenantCode)
                        .last("LIMIT 1"));
    }

    private RptReport requireOwner(UserContext ctx, Long reportId) {
        RptReport report = requireReport(reportId);
        if (!report.getOwnerId().equals(ctx.getUserId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        return report;
    }

    /** BR-05 结果权限隔离：所有者 / 订阅收件人 / 管理员可访问。 */
    private void requireAccess(UserContext ctx, RptReport report) {
        if (report.getOwnerId().equals(ctx.getUserId())) {
            return;
        }
        boolean receiver = receiverMapper.selectCount(new LambdaQueryWrapper<RptSubReceiver>()
                .eq(RptSubReceiver::getUserId, ctx.getUserId())
                .apply("subscription_id IN (SELECT id FROM rpt_subscription WHERE report_id = {0} AND is_deleted = 0)",
                        report.getId())) > 0;
        if (receiver || ctx.getRoles().contains("ADMIN")) {
            return;
        }
        throw new BizException(ErrorCode.FUNC_FORBIDDEN);
    }

    private List<RptSubscription> listVisibleSubscriptions(UserContext ctx, Long reportId) {
        List<RptSubscription> subs = subscriptionMapper.selectList(new LambdaQueryWrapper<RptSubscription>()
                .eq(RptSubscription::getReportId, reportId).eq(RptSubscription::getIsDeleted, 0)
                // 仅显式租户请求时按租户过滤
                .eq(TenantContextHolder.get() != null && !TenantContextHolder.get().isBlank(),
                        RptSubscription::getTenantId, TenantContextHolder.get())
                .orderByDesc(RptSubscription::getNextRunAt));
        boolean owner = reportMapper.selectById(reportId) != null
                && reportMapper.selectById(reportId).getOwnerId().equals(ctx.getUserId());
        if (owner || ctx.getRoles().contains("ADMIN")) {
            return subs;
        }
        return subs.stream().filter(s -> receiverMapper.selectCount(new LambdaQueryWrapper<RptSubReceiver>()
                .eq(RptSubReceiver::getSubscriptionId, s.getId())
                .eq(RptSubReceiver::getUserId, ctx.getUserId())) > 0).toList();
    }

    private Set<Long> subscribedReportIds(Long userId) {
        List<RptSubReceiver> mine = receiverMapper.selectList(new LambdaQueryWrapper<RptSubReceiver>()
                .eq(RptSubReceiver::getUserId, userId));
        Set<Long> ids = new LinkedHashSet<>();
        for (RptSubReceiver r : mine) {
            RptSubscription sub = subscriptionMapper.selectById(r.getSubscriptionId());
            if (sub != null && Integer.valueOf(1).equals(sub.getStatus())
                    && !Integer.valueOf(1).equals(sub.getIsDeleted())) {
                ids.add(sub.getReportId());
            }
        }
        return ids;
    }

    private void saveComponents(Long reportId, List<ReportDtos.ComponentSpec> specs) {
        if (specs == null) {
            return;
        }
        int sort = 0;
        for (ReportDtos.ComponentSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            RptComponent comp = new RptComponent();
            comp.setReportId(reportId);
            comp.setCompType(compTypeCode(spec.compType()));
            comp.setChartType(spec.chartType());
            comp.setDefJson(toJson(spec.def() == null ? Map.of() : spec.def()));
            comp.setSortNo(sort++);
            componentMapper.insert(comp);
        }
    }

    private ReportViews.ReportSummary toSummary(RptReport r, long subCount) {
        String ownerName = null;
        try {
            SecUser owner = secUserMapper.selectById(r.getOwnerId());
            ownerName = owner == null ? null : owner.getDisplayName();
        } catch (Exception ignored) {
            // 所有者姓名解析失败不影响列表
        }
        return new ReportViews.ReportSummary(r.getId(), r.getReportName(), sourceTypeName(r.getSourceType()),
                r.getOwnerId(), ownerName == null ? String.valueOf(r.getOwnerId()) : ownerName,
                r.getCreatedAt() == null ? "" : TS.format(r.getCreatedAt()), subCount);
    }

    private long countSubscriptions(Long reportId) {
        return subscriptionMapper.selectCount(new LambdaQueryWrapper<RptSubscription>()
                .eq(RptSubscription::getReportId, reportId).eq(RptSubscription::getIsDeleted, 0));
    }

    private int receiverCount(Long subscriptionId) {
        return receiverMapper.selectCount(new LambdaQueryWrapper<RptSubReceiver>()
                .eq(RptSubReceiver::getSubscriptionId, subscriptionId)).intValue();
    }

    private ReportViews.SubscriptionView toSubscriptionView(RptSubscription s, int count) {
        return new ReportViews.SubscriptionView(s.getId(), s.getReportId(), freqName(s.getFreq()),
                channelName(s.getChannel()), s.getStatus(),
                s.getNextRunAt() == null ? "" : TS.format(s.getNextRunAt()), count);
    }

    private ReportViews.SnapshotView toSnapshotView(RptSnapshot s) {
        return new ReportViews.SnapshotView(s.getId(), s.getReportId(), s.getSubId(), s.getFileKey(),
                s.getGeneratedAt() == null ? "" : TS.format(s.getGeneratedAt()),
                s.getExpireAt() == null ? "" : TS.format(s.getExpireAt()));
    }

    private ReportViews.SnapshotView latestSnapshot(Long reportId) {
        List<RptSnapshot> snaps = snapshotMapper.selectList(new LambdaQueryWrapper<RptSnapshot>()
                .eq(RptSnapshot::getReportId, reportId).orderByDesc(RptSnapshot::getGeneratedAt)
                .last("LIMIT 1"));
        return snaps.isEmpty() ? null : toSnapshotView(snaps.get(0));
    }

    /** 计算下次执行时间（按频率 + 推送时间 + 月度日期）。 */
    LocalDateTime computeNextRun(String frequency, String pushTime, Integer dayOfMonth) {
        LocalTime time = LocalTime.of(9, 0);
        if (pushTime != null && !pushTime.isBlank()) {
            try {
                time = LocalTime.parse(pushTime.trim());
            } catch (Exception ignored) {
                time = LocalTime.of(9, 0);
            }
        }
        String freq = frequency == null ? "" : frequency.toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        return switch (freq) {
            case "DAILY" -> {
                LocalDateTime today = LocalDateTime.of(now.toLocalDate(), time);
                yield today.isAfter(now) ? today : today.plusDays(1);
            }
            case "WEEKLY" -> {
                int daysToMonday = (now.getDayOfWeek().getValue() + 5) % 7;
                LocalDate monday = daysToMonday == 0 && !LocalDateTime.of(now.toLocalDate(), time).isAfter(now)
                        ? now.toLocalDate() : now.toLocalDate().plusDays(daysToMonday);
                yield LocalDateTime.of(monday, time);
            }
            case "MONTHLY" -> {
                int dom = dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 28 ? 1 : dayOfMonth;
                LocalDate next = now.toLocalDate().withDayOfMonth(dom);
                if (!LocalDateTime.of(next, time).isAfter(now)) {
                    next = next.plusMonths(1);
                }
                yield LocalDateTime.of(next, time);
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "frequency");
        };
    }

    static String sourceTypeName(Integer code) {
        return switch (code == null ? 0 : code) {
            case 1 -> "ASK";
            case 2 -> "TEMPLATE";
            case 3 -> "CUSTOM";
            default -> "UNKNOWN";
        };
    }

    static String compTypeName(Integer code) {
        return switch (code == null ? 0 : code) {
            case 1 -> "CHART";
            case 2 -> "TABLE";
            case 3 -> "METRIC_CARD";
            default -> "UNKNOWN";
        };
    }

    private static int compTypeCode(String name) {
        return switch (name == null ? "" : name.toUpperCase()) {
            case "CHART" -> 1;
            case "TABLE" -> 2;
            case "METRIC_CARD" -> 3;
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "comp_type");
        };
    }

    private static String freqName(Integer code) {
        return switch (code == null ? 0 : code) {
            case 1 -> "DAILY";
            case 2 -> "WEEKLY";
            case 3 -> "MONTHLY";
            default -> "UNKNOWN";
        };
    }

    private static String channelName(Integer code) {
        return switch (code == null ? 0 : code) {
            case 1 -> "EMAIL";
            case 2 -> "WECOM";
            case 3 -> "DINGTALK";
            case 4 -> "FEISHU";
            default -> "UNKNOWN";
        };
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, "JSON 序列化失败");
        }
    }
}
