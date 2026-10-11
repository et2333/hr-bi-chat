package com.hrchat.report.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.ReportContext;
import com.hrchat.api.report.ReportSourceReader;
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
 * REPORT 来源读取实现（I3）：校验租户与报表归属、数据版本可用性后，
 * 恢复创建时服务端写入的持久化 {@link ReportContext}。
 *
 * <p>与问答模块 {@code ChatAskSourceReader} 对称：仅负责授权与可信上下文恢复，
 * 指标口径（如 leave_count）等业务校验交由分析核心，保持职责单一。
 */
@Service
@RequiredArgsConstructor
public class ReportSourceReaderImpl implements ReportSourceReader {

    private final ReportService reportService;
    private final RptDataVersionMapper dataVersionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public ReportContext readAuthorizedReport(AskSourceAccess access, Long reportId, Long dataVersionId) {
        if (access == null || access.tenantId() == null || access.userId() == null) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        RptReport report = reportId == null ? null : reportService.loadReport(reportId);
        if (report == null
                || !Objects.equals(access.tenantId(), report.getTenantId())
                || !Objects.equals(access.userId(), report.getOwnerId())) {
            // 不存在 / 已删除 / 非本人均按越权处理，不泄露存在性
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        RptDataVersion version = dataVersionId == null ? null : dataVersionMapper.selectById(dataVersionId);
        if (version == null || !Objects.equals(reportId, version.getReportId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "数据版本不存在或不属于该报表");
        }
        if (!ReportDataVersionService.STATUS_READY.equals(version.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "数据版本未就绪（当前状态：" + version.getStatus() + "）");
        }
        String contextJson = report.getContextJson();
        if (contextJson == null || contextJson.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报表缺少可信上下文（context_json 为空）");
        }
        try {
            return objectMapper.readValue(contextJson, ReportContext.class);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报表可信上下文不可解析");
        }
    }
}
