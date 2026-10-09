package com.hrchat.chat.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;

/** A separate streaming connection keeps analysis failures outside ordinary asks. */
@Component
public class AnalysisRuntimeClient {
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String serviceToken;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AnalysisRuntimeClient(ObjectMapper mapper,
            @Value("${hrchat.ai.remote-base-url:http://localhost:8000}") String baseUrl,
            @Value("${HRCHAT_MCP_SERVICE_TOKEN:${hrchat.mcp.service-token:local-dev-mcp-service-token}}") String serviceToken) {
        this.mapper = mapper; this.baseUrl = baseUrl; this.serviceToken = serviceToken;
    }

    public void stream(String taskId, Map<String, Object> body, BiConsumer<String, Map<String, Object>> consumer)
            throws Exception {
        HttpRequest request = request(taskId + "/events", body).header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(65)).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body(); var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            if (response.statusCode() != 200) throw new IllegalStateException("analysis gateway status " + response.statusCode());
            String event = "ANALYSIS_STAGE";
            StringBuilder data = new StringBuilder();
            String line;
            while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) event = line.substring(6).trim();
                else if (line.startsWith("data:")) data.append(line.substring(5).trim());
                else if (line.isBlank() && !data.isEmpty()) {
                    Map<String, Object> frame = mapper.readValue(data.toString(), new TypeReference<>() { });
                    @SuppressWarnings("unchecked") Map<String, Object> payload = frame.get("payload") instanceof Map<?, ?> m
                            ? (Map<String, Object>) m : frame;
                    consumer.accept(event, payload);
                    data.setLength(0);
                }
            }
        }
    }

    public void cancel(String taskId, Map<String, Object> body) {
        try {
            HttpRequest request = request(taskId, body).timeout(Duration.ofSeconds(3))
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "invocation_id", body.get("invocation_id"),
                            "tool_context_token", body.get("tool_context_token"))))).build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Java revokes the task/token immediately; the remote deadline also stops orphaned work.
        }
    }

    private HttpRequest.Builder request(String path, Map<String, Object> body) {
        @SuppressWarnings("unchecked") Map<String, Object> context = (Map<String, Object>) body.get("analysis_context");
        return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/analysis/tasks/" + path))
                .header("Content-Type", "application/json").header("X-Service-Token", serviceToken)
                .header("X-Tenant-No", String.valueOf(context.get("tenant_no")));
    }
}
