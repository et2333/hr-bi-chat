package com.hrchat.api.report;

/**
 * 共享来源读取接口：按 ASK 来源恢复可信 {@link ReportContext}。
 * 实现位于问答模块；报表模块只依赖本接口，不依赖问答内部实体。
 */
public interface AskSourceReader {

    /**
     * 校验租户、答案完成状态与当前访问权限后，恢复业务口径。
     *
     * @throws com.hrchat.common.exception.BizException 越权、不存在、未完成或不可恢复时
     */
    ReportContext readAuthorizedAsk(AskSourceAccess access, String askId);
}
