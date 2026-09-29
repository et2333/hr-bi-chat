package com.hrchat.bootstrap.config;

import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理补测（S8c）：越权上报审计、业务/参数/兜底错误映射。
 */
class GlobalExceptionHandlerTest {

    private final AuditCollector auditCollector = mock(AuditCollector.class);
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(auditCollector);
    }

    private HttpServletRequest request(String uri, String userNo) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getHeader("X-User-No")).thenReturn(userNo);
        return req;
    }

    @Test
    void handleBiz_permissionDenied_recordsAudit() {
        BizException e = new BizException(ErrorCode.FUNC_FORBIDDEN);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBiz(e, request("/api/v1/chat/sessions", "hr01"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(auditCollector).record(Mockito.any(AuditEvent.class));
    }

    @Test
    void handleBiz_commonError_noAudit() {
        BizException e = new BizException(ErrorCode.PARAM_INVALID, "title");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBiz(e, request("/api/v1/chat/sessions", "hr01"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        verify(auditCollector, times(0)).record(Mockito.any(AuditEvent.class));
    }

    @Test
    void handleValidation_mapsFieldError() {
        BindException bind = new BindException(new Object(), "target");
        bind.addError(new FieldError("target", "question", "不能为空"));
        ResponseEntity<ApiResponse<Void>> resp = handler.handleValidation(bind);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    void handleMissingParam_mapsParamMissing() throws Exception {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleMissingParam(new MissingServletRequestParameterException("page", "int"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.PARAM_MISSING.getCode());
    }

    @Test
    void handleTypeError_mapsParamTypeError() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleTypeError(new MethodArgumentTypeMismatchException("x", Integer.class, "page", null, null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.PARAM_TYPE_ERROR.getCode());
    }

    @Test
    void handleUnknown_mapsSystemBusy() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleUnknown(new IllegalStateException("boom"), request("/api/v1/x", "hr01"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.SYSTEM_BUSY.getCode());
    }
}
