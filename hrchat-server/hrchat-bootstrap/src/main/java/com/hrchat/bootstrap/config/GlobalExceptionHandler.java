package com.hrchat.bootstrap.config;

import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.context.TraceContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器：统一转换为接口文档 6.1 错误响应模型。
 *
 * <p>越权拦截（HRC-2002/HRC-2003/HRC-2005）自动上报 PERM_DENIED 审计（BR-02 留痕）。</p>
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AuditCollector auditCollector;

    /**
     * 业务异常。
     *
     * @param e 业务异常
     * @param request 请求
     * @return 错误响应
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e, HttpServletRequest request) {
        log.warn("业务异常: code={}, message={}, uri={}, traceId={}",
                e.getErrorCode().getCode(), e.getMessageText(), request.getRequestURI(),
                TraceContext.currentTraceId());
        if (isPermissionDenied(e.getErrorCode())) {
            recordPermDenied(e, request);
        }
        ApiResponse<Void> body = ApiResponse.error(e.getErrorCode().getCode(), e.getMessageText());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(body);
    }

    /** 越权拦截上报审计（BR-02：敏感操作留痕，失败不影响错误响应）。 */
    private void recordPermDenied(BizException e, HttpServletRequest request) {
        try {
            String userNo = request.getHeader("X-User-No");
            auditCollector.record(AuditEvent.of(AuditEvents.PERM_DENIED,
                    userNo == null ? "anonymous" : userNo, "api", request.getRequestURI(),
                    "{\"code\":\"" + e.getErrorCode().getCode() + "\",\"message\":\""
                            + e.getMessageText() + "\"}", false));
        } catch (Exception ex) {
            log.warn("越权审计上报失败: uri={}", request.getRequestURI(), ex);
        }
    }

    private static boolean isPermissionDenied(ErrorCode code) {
        return code == ErrorCode.FUNC_FORBIDDEN
                || code == ErrorCode.DATA_RANGE_FORBIDDEN
                || code == ErrorCode.FIELD_PLAIN_FORBIDDEN
                || code == ErrorCode.EXPORT_FORBIDDEN;
    }

    /**
     * 参数校验异常（@Valid）。
     *
     * @param e 校验异常
     * @return 错误响应（HRX-1001）
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String field = fieldError == null ? "" : fieldError.getField();
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.PARAM_INVALID.getCode(),
                ErrorCode.PARAM_INVALID.format(field));
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 参数缺失。
     *
     * @param e 参数缺失异常
     * @return 错误响应（HRX-1001）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.PARAM_MISSING.getCode(),
                ErrorCode.PARAM_MISSING.format(e.getParameterName()));
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 参数类型错误。
     *
     * @param e 类型错误异常
     * @return 错误响应（HRX-1002）
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleTypeError(Exception e) {
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.PARAM_TYPE_ERROR.getCode(),
                ErrorCode.PARAM_TYPE_ERROR.format("请求体或路径参数"));
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 未捕获异常兜底（HRS-3001）。
     *
     * @param e 异常
     * @param request 请求
     * @return 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("系统异常: uri={}, traceId={}", request.getRequestURI(), TraceContext.currentTraceId(), e);
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.SYSTEM_BUSY.getCode(),
                ErrorCode.SYSTEM_BUSY.getMessageTemplate());
        return ResponseEntity.status(500).body(body);
    }
}
