package com.hrchat.common.exception;

import com.hrchat.common.error.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：携带统一错误码，由全局异常处理器转换为错误响应模型。
 */
@Getter
public class BizException extends RuntimeException {

    /** 错误码 */
    private final ErrorCode errorCode;

    /** 错误码占位参数（用于格式化 message） */
    private final transient Object[] args;

    public BizException(ErrorCode errorCode, Object... args) {
        super(errorCode.format(args));
        this.errorCode = errorCode;
        this.args = args;
    }

    public BizException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode.format(args), cause);
        this.errorCode = errorCode;
        this.args = args;
    }

    /** 格式化后的用户可读文案。 */
    public String getMessageText() {
        return super.getMessage();
    }
}
