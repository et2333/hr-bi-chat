package com.hrchat.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一 API 响应包装（接口文档 6.1 错误响应模型 + 成功响应约定）。
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一响应")
public class ApiResponse<T> implements Serializable {

    /** 业务错误码，成功为 SUCCESS */
    @Schema(description = "业务错误码，成功为 SUCCESS", example = "SUCCESS")
    private String code;

    /** 用户可读消息 */
    @Schema(description = "用户可读消息")
    private String message;

    /** 全链路追踪 ID */
    @Schema(description = "全链路追踪 ID")
    private String traceId;

    /** 错误详情（成功时为空） */
    @Schema(description = "错误详情")
    private Object details;

    /** 业务数据 */
    @Schema(description = "业务数据")
    private T data;

    public static final String SUCCESS = "SUCCESS";

    /**
     * 成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 响应体
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS, "ok", TraceIdHolder.currentTraceId(), null, data);
    }

    /**
     * 成功响应（无数据）。
     *
     * @param <T> 数据类型
     * @return 响应体
     */
    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    /**
     * 失败响应。
     *
     * @param code    错误码
     * @param message 消息
     * @param <T>     数据类型
     * @return 响应体
     */
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, TraceIdHolder.currentTraceId(), null, null);
    }

    /**
     * 失败响应（带详情）。
     *
     * @param code    错误码
     * @param message 消息
     * @param details 错误详情
     * @param <T>     数据类型
     * @return 响应体
     */
    public static <T> ApiResponse<T> error(String code, String message, Object details) {
        return new ApiResponse<>(code, message, TraceIdHolder.currentTraceId(), details, null);
    }

    /**
     * trace_id 持有器：从 ThreadLocal/MDC 读取，由 TraceContext 维护。
     */
    static final class TraceIdHolder {
        private TraceIdHolder() {
        }

        static String currentTraceId() {
            return com.hrchat.common.context.TraceContext.currentTraceId();
        }
    }
}
