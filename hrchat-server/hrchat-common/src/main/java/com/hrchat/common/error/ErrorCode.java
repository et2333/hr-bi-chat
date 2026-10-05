package com.hrchat.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 统一业务错误码（接口文档 6.1-6.3）。
 *
 * <p>五段式 {@code HR<域>-<序号>}，与 HTTP 状态码一对多映射：</p>
 * <ul>
 *   <li>HRX-1xxx 参数错误（400/409）</li>
 *   <li>HRC-2xxx 权限错误（401/403/404）</li>
 *   <li>HRS-3xxx 系统错误（429/500/503/409/410）</li>
 *   <li>HRA-4xxx AI 能力错误（200+ERROR/500/503）</li>
 *   <li>HRD-5xxx 数据错误（200/500/504）</li>
 *   <li>HRK-6xxx 知识库错误（200/500）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ---------------- 参数错误 HRX-1xxx ----------------
    PARAM_MISSING("HRX-1001", 400, "参数缺失：{0}"),
    PARAM_TYPE_ERROR("HRX-1002", 400, "参数类型错误：{0}"),
    PARAM_INVALID("HRX-1003", 400, "参数取值非法：{0}"),
    PAGE_SIZE_EXCEED("HRX-1004", 400, "分页参数越界（size≤200）"),
    QUESTION_TOO_LONG("HRX-1005", 400, "问句长度超限（≤500字符）"),
    IDEMPOTENT_PROCESSING("HRX-1006", 409, "幂等请求处理中，请勿重复提交"),
    IDEMPOTENCY_CONFLICT("HRX-1007", 409, "幂等键已用于不同请求"),

    // ---------------- 权限错误 HRC-2xxx ----------------
    AUTH_EXPIRED("HRC-2001", 401, "登录已过期，请重新登录"),
    FUNC_FORBIDDEN("HRC-2002", 403, "您暂无该功能的访问权限"),
    DATA_RANGE_FORBIDDEN("HRC-2003", 403, "您暂无{0}数据的查看权限，请联系管理员"),
    FIELD_PLAIN_FORBIDDEN("HRC-2004", 403, "字段「{0}」需明文查看授权（申请入口）"),
    EXPORT_FORBIDDEN("HRC-2005", 403, "导出未授权或超出限制（≤5000行/5次日）"),
    QUOTA_EXCEEDED("HRC-2006", 403, "资源配额不足：{0}"),

    // ---------------- 系统错误 HRS-3xxx ----------------
    SYSTEM_BUSY("HRS-3001", 500, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE("HRS-3002", 503, "服务暂不可用（{0}）"),
    RATE_LIMITED("HRS-3003", 429, "请求过于频繁，请{0}秒后重试"),
    SSE_REPLAY_EXPIRED("HRS-3004", 409, "SSE 回放窗口已过期（5分钟）"),
    API_DEPRECATED("HRS-3005", 410, "接口已下线，请升级客户端"),

    // ---------------- AI 能力错误 HRA-4xxx ----------------
    INTENT_NOT_UNDERSTOOD("HRA-4001", 200, "未能理解您的问题，换个说法试试？"),
    PARSE_FAILED("HRA-4002", 200, "问题解析失败：{0}"),
    QUERY_ASYNC_TIMEOUT("HRA-4003", 200, "查询超时，已转后台执行，完成后将通知您"),
    AI_DEGRADED("HRA-4004", 200, "智能解析暂不可用，已返回预设查询"),
    BUDGET_EXCEEDED("HRA-4005", 200, "分析预算已达上限，已返回基础对比结果"),
    QUERY_UNSUPPORTED("HRA-4006", 200, "当前查询条件无法执行：{0}"),

    // ---------------- 数据错误 HRD-5xxx ----------------
    QUERY_TIMEOUT("HRD-5001", 200, "数据查询超时，请缩小时间范围后重试"),
    EMPTY_RESULT("HRD-5002", 200, "暂未查询到相关数据"),
    DATA_STALE("HRD-5003", 200, "数据可能延迟，更新至{0}"),

    // ---------------- 知识库错误 HRK-6xxx ----------------
    KB_INDEX_NOT_READY("HRK-6001", 503, "知识库索引未就绪，请稍后重试"),
    KB_SEARCH_FAILED("HRK-6002", 500, "知识库检索失败：{0}");

    /** 错误码，如 HRC-2003 */
    private final String code;

    /** HTTP 状态码 */
    private final int httpStatus;

    /** message 模板（{0}{1} 占位符由格式化器替换） */
    private final String messageTemplate;

    /**
     * 格式化 message。
     *
     * @param args 占位参数
     * @return 格式化后的用户可读文案
     */
    public String format(Object... args) {
        if (args == null || args.length == 0) {
            return messageTemplate;
        }
        String msg = messageTemplate;
        for (int i = 0; i < args.length; i++) {
            msg = msg.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return msg;
    }
}
