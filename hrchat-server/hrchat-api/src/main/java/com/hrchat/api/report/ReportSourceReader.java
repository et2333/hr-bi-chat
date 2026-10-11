package com.hrchat.api.report;

/**
 * 报表来源读取接口（I3）：按报表与其数据版本恢复可信 {@link ReportContext}，
 * 供分析核心重新鉴权并构造自洽快照。
 *
 * <p>实现位于报表模块（恢复 {@code RptReport.contextJson} 并由服务端校验归属与版本状态）；
 * 问答模块只依赖本接口，不依赖报表内部实体，与 {@link AskSourceReader} 方向对称。
 */
public interface ReportSourceReader {

    /**
     * 校验租户、报表归属、数据版本可用性与当前访问权限后，恢复业务口径。
     *
     * <p>前端不可自报指标、组织或期间：返回的 {@link ReportContext} 必须是服务端持久化的可信上下文。
     *
     * @throws com.hrchat.common.exception.BizException 越权、不存在或数据版本不可用时
     */
    ReportContext readAuthorizedReport(AskSourceAccess access, Long reportId, Long dataVersionId);
}
