package com.hrchat.chat.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisRuntimeClientTest {

    @Test
    void stream_usesHttp11WithoutUpgradeAndPreservesJsonBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> upgrade = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/v1/analysis/tasks/task/events", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "event: FINAL\ndata: {\"payload\":{\"status\":\"COMPLETED\"}}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AnalysisRuntimeClient client = new AnalysisRuntimeClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
            List<String> events = new ArrayList<>();
            client.stream("task", Map.of("analysis_context", Map.of("tenant_no", "t01", "unit", "人")),
                    (event, payload) -> events.add(event));
            org.junit.jupiter.api.Assertions.assertNull(upgrade.get());
            assertTrue(received.get().contains("人"));
            org.junit.jupiter.api.Assertions.assertEquals(List.of("FINAL"), events);
        } finally { server.stop(0); }
    }

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
