package com.hrchat.api.report;

/**
 * 报表洞察动作来源读取（A2/R3）。
 *
 * <p>按动作引用（报表 + 数据版本 + 组件 + 视图 + 动作 id）恢复该视图冻结的可信期间与组织，
 * 并校验动作确实属于该视图持久化的洞察结果。问答侧只依赖本接口，不依赖报表内部实体。</p>
 */
public interface ReportActionSourceReader {

    /**
     * 校验租户、报表访问权限、数据版本就绪与视图快照后，恢复动作的不可变来源。
     *
     * <p>动作 id 必须存在于该视图持久化的洞察动作目录中，否则视为非法引用。
     *
     * @throws com.hrchat.common.exception.BizException 越权、版本/视图不可用或动作不存在时
     */
    ReportActionSource readAuthorizedAction(AskSourceAccess access, Long reportId, Long dataVersionId,
                                            Long componentId, String viewKey, String actionId);
}
