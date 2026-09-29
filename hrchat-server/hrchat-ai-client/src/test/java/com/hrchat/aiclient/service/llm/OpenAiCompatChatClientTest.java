package com.hrchat.aiclient.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容客户端契约：POST /chat/completions、Bearer 认证、choices 解析、非 2xx 抛错。
 */
class OpenAiCompatChatClientTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String lastAuth;
    private volatile String lastPath;
    private volatile JsonNode lastBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void chat_postsBearerRequestAndParsesChoices() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            lastPath = exchange.getRequestURI().getPath();
            lastBody = objectMapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"研发中心在职18人。\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        LlmChatClient client = new OpenAiCompatChatClient(
                new LlmConnection(baseUrl, "secret-key", "glm-4-flash", 5000), objectMapper);
        assertTrue(client.enabled());
        assertEquals("glm-4-flash", client.model());

        String answer = client.chat("系统提示", "用户问题");

        assertEquals("研发中心在职18人。", answer);
        assertEquals("Bearer secret-key", lastAuth);
        assertEquals("/v1/chat/completions", lastPath);
        assertEquals("glm-4-flash", lastBody.path("model").asText());
        assertEquals(2, lastBody.path("messages").size());
        assertEquals("系统提示", lastBody.path("messages").get(0).path("content").asText());
    }

    @Test
    void chat_non2xx_throwsLlmCallException() {
        server.createContext("/v1/chat/completions", (HttpExchange exchange) -> {
            byte[] resp = "{\"error\":{\"message\":\"invalid api key\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        LlmChatClient client = new OpenAiCompatChatClient(
                new LlmConnection(baseUrl, "bad", "glm-4-flash", 5000), objectMapper);
        LlmCallException ex = assertThrows(LlmCallException.class, () -> client.chat("s", "u"));
        assertTrue(ex.getMessage().contains("HTTP 401"), ex.getMessage());
    }

    @Test
    void chat_missingContentNode_throws() {
        server.createContext("/v1/chat/completions", (HttpExchange exchange) -> {
            byte[] resp = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        LlmChatClient client = new OpenAiCompatChatClient(
                new LlmConnection(baseUrl, "k", "glm-4-flash", 5000), objectMapper);
        assertThrows(LlmCallException.class, () -> client.chat("s", "u"));
    }

    @Test
    void connection_usable_validatesRequiredFields() {
        assertTrue(new LlmConnection(baseUrl, "k", "m", 5000).usable());
        assertFalse(new LlmConnection(null, "k", "m", 5000).usable());
        assertFalse(new LlmConnection(baseUrl, " ", "m", 5000).usable());
        assertFalse(new LlmConnection(baseUrl, "k", " ", 5000).usable());
        assertFalse(new LlmConnection("ftp://x", "k", "m", 5000).usable());
    }
}
