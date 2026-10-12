package com.hrchat.report.source;

import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.ReportActionSource;
import com.hrchat.api.report.ReportActionSourceReader;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.chart.ChartViews.InsightAction;
import com.hrchat.report.chart.ChartViews.InsightTaskView;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptDataVersionMapper;
import com.hrchat.report.service.InsightTaskService;
import com.hrchat.report.service.ReportChartService;
import com.hrchat.report.service.ReportDataVersionService;
import com.hrchat.report.service.ReportService;
import com.hrchat.report.service.ViewKeySupport;
import com.hrchat.report.service.ViewOrgScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 报表洞察动作来源读取实现（A2/R3）。
 *
 * <p>按动作引用恢复该视图冻结的权威期间/组织：动作必须存在于该视图持久化的洞察结果中，
 * 期间由视图快照类目重建，组织在该版本冻结口径内再按组织下钻收窄。前端只回传引用，
 * 服务端不信任任何自报参数，也不回退到“当前”版本或根报表范围。</p>
 *
 * <p>动作引用不可变性（B1 附带契约缺口）：返回的来源只由「报表 + 数据版本 + 组件 + 视图键 +
 * actionId」与该视图的服务端权威范围决定，能力与问题取自用户当前可见的洞察结果，期间/组织
 * 一律由服务端重建，不采用持久化动作里可能过期的参数。因此同一引用的结果与 task/attempt
 * 无关，无需再把轮次写入引用；动作若已不在当前可见域的洞察结果中则直接拒绝。</p>
 */
@Service
@RequiredArgsConstructor
public class ReportActionSourceReaderImpl implements ReportActionSourceReader {

    private final ReportService reportService;
    private final RptDataVersionMapper dataVersionMapper;
    private final UserContextService userContextService;
    private final InsightTaskService insightTaskService;
    private final ReportChartService chartService;
    private final ViewOrgScopeResolver viewOrgScopeResolver;

    @Override
    public ReportActionSource readAuthorizedAction(AskSourceAccess access, Long reportId, Long dataVersionId,
                                                   Long componentId, String viewKey, String actionId) {
        if (access == null || access.tenantId() == null || access.userId() == null || access.empNo() == null
                || reportId == null || dataVersionId == null || componentId == null
                || actionId == null || actionId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "动作来源引用不完整");
        }
        UserContext ctx = userContextService.resolve(access.empNo(), access.tenantId(), null);
        if (ctx == null || !Objects.equals(ctx.getUserId(), access.userId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        reportService.requireViewAccess(ctx, reportId);
        RptDataVersion version = dataVersionMapper.selectById(dataVersionId);
        if (version == null || !Objects.equals(reportId, version.getReportId())
                || !ReportDataVersionService.STATUS_READY.equals(version.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "数据版本不存在或未就绪");
        }
        String key = viewKey == null || viewKey.isBlank() ? ViewKeySupport.ROOT : viewKey;

        // 动作必须属于该视图持久化的洞察结果，避免旧卡片动作绑定到其它视图或版本；
        // A7/D2：按当前请求者的上下文可见域读取，不跨域复用含受限原问答上下文的结果。
        InsightTaskView task = insightTaskService.readForView(report, version, componentId, key,
                insightTaskService.resolveContextVisibilityRef(ctx, report, version, componentId, key));
        InsightAction action = task == null || task.insight() == null ? null
                : task.insight().actions().stream()
                        .filter(a -> actionId.equals(a.actionId())).findFirst().orElse(null);
        if (action == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "动作不属于该视图的洞察结果");
        }

        ReportChartService.ViewScope scope = chartService.resolveViewScope(report, version, componentId, key);
        // B1：按权威组织层级把版本口径收窄到该下钻视图；无法解析或交集为空时明确拒绝，不回退父范围。
        List<Long> orgs = viewOrgScopeResolver.narrowDrillOrgs(report.getTenantId(), scope.orgIds(), key);
        Set<Long> allowed = new TreeSet<>();
        if (ctx.getGrantedOrgs() != null) {
            ctx.getGrantedOrgs().stream().filter(g -> g.getScope() != null && g.getScope() >= 1)
                    .forEach(g -> allowed.addAll(g.getSubtreeOrgKeys()));
        }
        if (!allowed.containsAll(orgs)) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        return new ReportActionSource(reportId, dataVersionId, componentId, key, action.actionId(),
                action.capabilityId(), action.question(), scope.metricCode(), scope.metricVersion(),
                scope.metricName(), scope.unit(), toPeriod(scope.current()), toPeriod(scope.baseline()),
                orgs, scope.organizationLabel());
    }

    private static ReportActionSource.Period toPeriod(com.hrchat.aiclient.model.InsightRequest.Period period) {
        return period == null ? null : new ReportActionSource.Period(period.start(), period.end());
    }
}
