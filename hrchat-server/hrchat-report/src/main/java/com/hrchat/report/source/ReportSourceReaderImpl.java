package com.hrchat.report.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.ReportContext;
import com.hrchat.api.report.ReportSourceReader;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptDataVersionMapper;
import com.hrchat.report.service.ReportDataVersionService;
import com.hrchat.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * REPORT 来源读取实现（I3/R3）：复用统一报表访问检查后，恢复该数据版本冻结的可信 {@link ReportContext}。
 *
 * <p>与问答模块 {@code ChatAskSourceReader} 对称：仅负责授权与可信上下文恢复，
 * 指标口径（如 leave_count）等业务校验交由分析核心，保持职责单一。</p>
 *
 * <p>R3 起来源上下文随版本冻结（{@code rpt_data_version.context_json}），读取旧版本不再依赖可变的
 * {@code rpt_report.context_json}；无法恢复的老记录明确报“不可用”，绝不静默使用当前版本。</p>
 */
@Service
@RequiredArgsConstructor
public class ReportSourceReaderImpl implements ReportSourceReader {

    private final ReportService reportService;
    private final RptDataVersionMapper dataVersionMapper;
    private final UserContextService userContextService;
    private final ObjectMapper objectMapper;

    @Override
    public ReportContext readAuthorizedReport(AskSourceAccess access, Long reportId, Long dataVersionId) {
        if (access == null || access.tenantId() == null || access.userId() == null || access.empNo() == null) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        // 按当前授权实时解析用户上下文，复用统一报表访问检查（owner / 订阅接收者 / 管理员），
        // 不限定 owner：具备报表访问权限的共享用户与所有者走同一裁决。
        UserContext ctx = userContextService.resolve(access.empNo(), access.tenantId(), null);
        if (!Objects.equals(ctx.getUserId(), access.userId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        RptReport report = reportId == null ? null : reportService.requireAccessibleReport(ctx, reportId);
        if (report == null) {
            // 不存在 / 已删除按越权处理，不泄露存在性
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        RptDataVersion version = dataVersionId == null ? null : dataVersionMapper.selectById(dataVersionId);
        if (version == null || !Objects.equals(reportId, version.getReportId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "数据版本不存在或不属于该报表");
        }
        if (!ReportDataVersionService.STATUS_READY.equals(version.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "数据版本未就绪（当前状态：" + version.getStatus() + "）");
        }
        String contextJson = ReportDataVersionService.resolveContextJson(report, version);
        if (contextJson == null || contextJson.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报表来源上下文不可恢复（缺少冻结上下文）");
        }
        try {
            return objectMapper.readValue(contextJson, ReportContext.class);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报表可信上下文不可解析");
        }
    }
}
