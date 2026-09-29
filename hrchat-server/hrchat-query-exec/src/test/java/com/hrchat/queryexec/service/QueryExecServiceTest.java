package com.hrchat.queryexec.service;

import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.queryexec.model.QueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueryExecService 单测：校验只读白名单后转发执行器，超时参数正确下发。
 */
@ExtendWith(MockitoExtension.class)
class QueryExecServiceTest {

    @Mock
    private SqlRewriteService sqlRewriteService;
    @Mock
    private QueryExecutor queryExecutor;
    @InjectMocks
    private QueryExecService service;

    @Test
    void executeReadonly_validatesThenExecutesWithDefaultTimeout() {
        QueryResult expected = new QueryResult(List.of(), List.of(Map.of()), 0);
        when(queryExecutor.execute("SELECT 1", QueryExecService.DEFAULT_TIMEOUT_SECONDS)).thenReturn(expected);
        assertSame(expected, service.executeReadonly("SELECT 1"));
        verify(sqlRewriteService).validateReadOnly("SELECT 1");
        verify(queryExecutor).execute("SELECT 1", QueryExecService.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    void executeWithTimeout_validatesThenExecutesWithGivenTimeout() {
        QueryResult expected = new QueryResult(List.of(), List.of(Map.of()), 0);
        when(queryExecutor.execute("SELECT 2", 3)).thenReturn(expected);
        assertSame(expected, service.executeWithTimeout("SELECT 2", 3));
        verify(sqlRewriteService).validateReadOnly("SELECT 2");
    }
}
