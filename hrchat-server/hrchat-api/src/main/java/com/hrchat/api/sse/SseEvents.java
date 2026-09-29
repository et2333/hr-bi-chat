package com.hrchat.api.sse;

/**
 * SSE 事件类型常量（接口文档 2.2.11，问答/归因统一）。
 */
public final class SseEvents {

    private SseEvents() {
    }

    /** 结论摘要流式生成（delta 增量文本，phase: PARSING/SUMMARIZING） */
    public static final String MESSAGE_DELTA = "MESSAGE_DELTA";

    /** 歧义澄清（interrupt_type=CLARIFY，BR-07：≤2 问、每问 ≤5 选项） */
    public static final String INTERRUPT = "INTERRUPT";

    /** 归因任务计划更新 */
    public static final String PLAN_UPDATE = "PLAN_UPDATE";

    /** 工具调用开始 */
    public static final String TOOL_CALL_START = "TOOL_CALL_START";

    /** 工具调用结束 */
    public static final String TOOL_CALL_END = "TOOL_CALL_END";

    /** 答案完成（终态，三段式 payload） */
    public static final String ANSWER_DONE = "ANSWER_DONE";

    /** 流内错误（终态，可重试） */
    public static final String ERROR = "ERROR";

    /** 保活心跳（seq=-1，不占用序号） */
    public static final String HEARTBEAT = "HEARTBEAT";

    // ---- ANSWER_DONE.payload.status ----
    public static final String ASK_PENDING = "PENDING";
    public static final String ASK_CLARIFYING = "CLARIFYING";
    public static final String ASK_RUNNING = "RUNNING";
    public static final String ASK_COMPLETED = "COMPLETED";
    public static final String ASK_ASYNC_RUNNING = "ASYNC_RUNNING";
    public static final String ASK_FAILED = "FAILED";

    // ---- intent ----
    public static final String INTENT_QUERY = "QUERY";
    public static final String INTENT_ANALYSIS = "ANALYSIS";
    public static final String INTENT_OPERATION = "OPERATION";
    public static final String INTENT_CHITCHAT = "CHITCHAT";
}
