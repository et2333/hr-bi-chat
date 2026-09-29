package com.hrchat.aiclient.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Chat Completions 客户端（JDK 内置 HttpClient，无额外依赖）。
 *
 * <p>兼容智谱（{@code /api/paas/v4}）、硅基流动（{@code /v1}）、OpenAI 官方等。</p>
 */
@Slf4j
public class OpenAiCompatChatClient implements LlmChatClient {

    private final LlmConnection conn;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatChatClient(LlmConnection conn, ObjectMapper objectMapper) {
        this.conn = conn;
        this.objectMapper = objectMapper;
        int connectTimeoutMs = Math.max(1000, conn.timeoutMs() / 3);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public boolean enabled() {
        return conn.usable();
    }

    @Override
    public String model() {
        return conn.model();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) throws LlmCallException {
        if (!enabled()) {
            throw new LlmCallException("LLM 连接配置不可用");
        }
        Map<String, Object> body = Map.of(
                "model", conn.model(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", 0.3);
        String url = conn.baseUrl().endsWith("/")
                ? conn.baseUrl() + "chat/completions"
                : conn.baseUrl() + "/chat/completions";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(conn.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + conn.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new LlmCallException("LLM 服务返回 HTTP " + resp.statusCode() + ": "
                        + abbreviate(resp.body(), 300));
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new LlmCallException("LLM 响应缺少 choices[0].message.content");
            }
            return content.asText().trim();
        } catch (LlmCallException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmCallException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
