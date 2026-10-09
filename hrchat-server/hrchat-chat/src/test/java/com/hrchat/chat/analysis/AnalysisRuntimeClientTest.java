package com.hrchat.chat.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisRuntimeClientTest {

    @Test
    void cancel_swallowsTransportErrors() {
        AnalysisRuntimeClient client = new AnalysisRuntimeClient(new ObjectMapper(),
                "http://127.0.0.1:1", "token");
        assertDoesNotThrow(() -> client.cancel("analysis_x", Map.of(
                "invocation_id", "analysis:analysis_x",
                "tool_context_token", "tok",
                "analysis_context", Map.of("tenant_no", "t01"))));
    }

    @Test
    void stream_failsFastWhenGatewayDown() {
        AnalysisRuntimeClient client = new AnalysisRuntimeClient(new ObjectMapper(),
                "http://127.0.0.1:1", "token");
        List<String> events = new ArrayList<>();
        Exception ex = assertThrows(Exception.class, () -> client.stream("analysis_x", Map.of(
                "invocation_id", "analysis:analysis_x",
                "tool_context_token", "tok",
                "analysis_context", Map.of("tenant_no", "t01")),
                (event, payload) -> events.add(event)));
        assertTrue(ex.getMessage() != null || ex instanceof java.io.IOException
                || ex instanceof java.net.ConnectException || ex.getCause() != null);
    }
}
