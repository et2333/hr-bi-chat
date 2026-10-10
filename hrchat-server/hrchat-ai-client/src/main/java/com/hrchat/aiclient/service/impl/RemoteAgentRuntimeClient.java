package com.hrchat.aiclient.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.hrchat.aiclient.model.AgentInvocationContext;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.api.chat.ContextOverride;
import com.hrchat.api.sse.SseEvent;
import com.hrchat.api.sse.SseEvents;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程 Python agent-gateway 客户端（AgentRuntimeClient 的 remote 形态，D-3）。
 *
 * <p>SYNC 调用远程运行时：{@code /v1/chat/sessions/{sessionId}/asks} 与
 * {@code …/asks/{askId}/clarifications}，两者均返回固定终态信封。</p>
 */
@Slf4j
public class RemoteAgentRuntimeClient implements AgentRuntimeClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String tenantNo;
    private final ObjectMapper objectMapper;
    /** Python 信封/payload 为 snake_case，与 Web camelCase 区分。 */
    private final ObjectMapper snakeCaseMapper;
    private final RestTemplate restTemplate;
    private final Map<String, String> pendingSessionIds = new ConcurrentHashMap<>();
    private String serviceToken;

    public RemoteAgentRuntimeClient withServiceToken(String value) {
        this.serviceToken = value;
        return this;
    }

    /** 兼容无租户构造（P1 既有签名）：tenantNo 为 null，不携带 X-Tenant-No。 */
    public RemoteAgentRuntimeClient(String baseUrl, String apiKey, String model,
                                    ObjectMapper objectMapper, RestTemplate restTemplate) {
        this(baseUrl, apiKey, model, null, objectMapper, restTemplate);
    }

    /** 租户级构造（P2）：请求头携带 X-Tenant-No，Python 端据此选择租户运行时。 */
    public RemoteAgentRuntimeClient(String baseUrl, String apiKey, String model, String tenantNo,
                                    ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.tenantNo = tenantNo;
        this.objectMapper = objectMapper;
        this.snakeCaseMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.restTemplate = restTemplate;
    }

    @Override
    public AgentResult ask(AskRequest request, UserContext ctx) {
        return ask(request, ctx, null);
    }

    @Override
    public AgentResult ask(AskRequest request, UserContext ctx, AgentInvocationContext invocation) {
        return ask(request, ctx, invocation, null);
    }

    @Override
    public AgentResult ask(AskRequest request, UserContext ctx, AgentInvocationContext invocation,
                           java.util.function.Consumer<SseEvent> progress) {
        long start = System.currentTimeMillis();
        String sessionId = resolveSessionId(invocation, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", request.question());
        body.put("mode", progress == null ? "SYNC" : "STREAM");
        if (request.contextOverride() != null) {
            body.put("context_override", toRemoteContext(request.contextOverride()));
        }
        putInvocationFields(body, invocation);
        HttpHeaders headers = requestHeaders(ctx, invocation);
        String url = baseUrl + "/v1/chat/sessions/" + sessionId + "/asks";
        Map<?, ?> terminal = progress == null
                ? restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class).getBody()
                : streamTerminal(url, body, headers, progress);
        AgentResult result = parse(terminal, System.currentTimeMillis() - start);
        if (result.isClarifying()) {
            pendingSessionIds.put(result.askId(), sessionId);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> streamTerminal(String url, Map<String, Object> body, HttpHeaders headers,
                                     java.util.function.Consumer<SseEvent> progress) {
        return restTemplate.execute(url, org.springframework.http.HttpMethod.POST, request -> {
            request.getHeaders().putAll(headers);
            request.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
            request.getHeaders().set("X-Hrchat-Stream-Protocol", "terminal-v1");
            objectMapper.writeValue(request.getBody(), body);
        }, response -> {
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.getBody(), java.nio.charset.StandardCharsets.UTF_8));
            Map<?, ?> terminal = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                Map<?, ?> frame = objectMapper.readValue(line.substring(5).trim(), Map.class);
                String event = String.valueOf(frame.get("event"));
                if (!(frame.get("payload") instanceof Map<?, ?> payload)) continue;
                if ("TERMINAL".equals(event)) {
                    if (terminal != null) throw new java.io.IOException("Duplicate terminal");
                    terminal = payload;
                } else if (Set.of("PROGRESS", "USAGE").contains(event)) {
                    progress.accept(new SseEvent(event, (Map<String, Object>) payload));
                }
            }
            if (terminal == null) throw new java.io.IOException("Agent stream ended without terminal");
            return terminal;
        });
    }

    @Override
    public AgentResult clarify(String askId, String question, ClarifyAnswerRequest.Answer answers, UserContext ctx) {
        return clarify(askId, question, answers, ctx, null);
    }

    @Override
    public AgentResult clarify(String askId, String question, ClarifyAnswerRequest.Answer answers,
                               UserContext ctx, AgentInvocationContext invocation) {
        long start = System.currentTimeMillis();
        String sessionId = resolveSessionId(invocation, askId);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("question_id", answers.questionId());
        answer.put("option_ids", answers.optionIds());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answers", List.of(answer));
        putInvocationFields(body, invocation);
        HttpHeaders headers = requestHeaders(ctx, invocation);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl + "/v1/chat/sessions/" + sessionId + "/asks/" + askId + "/clarifications",
                new HttpEntity<>(body, headers), Map.class);
        AgentResult result = parse(resp.getBody(), System.currentTimeMillis() - start);
        if (result.isClarifying()) {
            pendingSessionIds.put(askId, sessionId);
        } else {
            pendingSessionIds.remove(askId);
        }
        return result;
    }

    /**
     * 报表 AI 洞察：转发 Python {@code POST /v1/insight}（P3-C），携带租户头。
     * 远程异常时抛 {@link BizException}{@code AI_DEGRADED}，由上层降级到本地模板。
     */
    @Override
    public Map<String, Object> generateInsight(InsightRequest request) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("report_name", request.reportName());
            body.put("metric_name", request.metricName());
            body.put("summary", request.summary());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Tenant-No", tenantNo);
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    baseUrl + "/v1/insight", new HttpEntity<>(body, headers), Map.class);
            return resp.getBody() == null ? Map.of() : resp.getBody();
        } catch (Exception e) {
            log.warn("insight 远程调用失败, baseUrl={}, err={}", baseUrl, e.getMessage());
            throw new BizException(ErrorCode.AI_DEGRADED);
        }
    }

    /** 解析固定终态信封：COMPLETED / CLARIFYING / FAILED。 */
    @SuppressWarnings("unchecked")
    private AgentResult parse(Map<?, ?> json, long elapsedMs) {
        AgentResult result = parseTerminal(json, elapsedMs);
        Map<String, Object> evidence = json != null && json.get("evidence") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return result.withEvidence(evidence);
    }

    @SuppressWarnings("unchecked")
    private AgentResult parseTerminal(Map<?, ?> json, long elapsedMs) {
        String status = json == null ? null : stringValue(json.get("status"));
        if (SseEvents.ASK_COMPLETED.equals(status) && json.get("answer_payload") != null) {
            Object answerPayload = json.get("answer_payload");
            // Python 输出 snake_case；SSE/前端契约为 camelCase
            AnswerPayload payload = snakeCaseMapper.convertValue(answerPayload, AnswerPayload.class);
            String askId = payload.askId() != null && !payload.askId().isBlank()
                    ? payload.askId() : stringValue(json.get("ask_id"));
            if (askId == null || askId.isBlank()) {
                askId = "ask_remote";
            }
            if (payload.askId() == null || payload.askId().isBlank()) {
                payload = new AnswerPayload(askId, payload.answerId(), payload.status(), payload.intent(),
                        payload.degraded(), payload.degradedTip(), payload.conclusion(), payload.table(),
                        payload.chart(), payload.caliber(), payload.followups(), payload.elapsedMs());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = objectMapper.convertValue(payload, Map.class);
            payloadMap.put("askId", askId);
            return new AgentResult(askId,
                    List.of(new SseEvent(SseEvents.ANSWER_DONE, payloadMap)),
                    payload, List.of(), null, payload.intent(), false, elapsedMs);
        }
        Object questionsObj = json == null ? null : json.get("questions");
        if (SseEvents.ASK_CLARIFYING.equals(status)
                && questionsObj instanceof List && !((List<?>) questionsObj).isEmpty()) {
            Map<String, Object> payloadMap = (Map<String, Object>) json;
            List<ClarifyQuestion> questions = new ArrayList<>();
            for (Object q : (List<?>) questionsObj) {
                Map<String, Object> qm = (Map<String, Object>) q;
                List<ClarifyQuestion.Option> options = new ArrayList<>();
                Object optionsObj = qm.get("options");
                if (optionsObj instanceof List) {
                    for (Object o : (List<?>) optionsObj) {
                        Map<String, Object> om = (Map<String, Object>) o;
                        options.add(new ClarifyQuestion.Option(String.valueOf(om.get("option_id")),
                                String.valueOf(om.get("label"))));
                    }
                }
                questions.add(new ClarifyQuestion(String.valueOf(qm.get("question_id")),
                        String.valueOf(qm.get("question")), options,
                        Boolean.TRUE.equals(qm.get("multiple"))));
            }
            String askId = String.valueOf(payloadMap.getOrDefault("ask_id", "ask_remote"));
            return new AgentResult(askId,
                    List.of(new SseEvent(SseEvents.INTERRUPT, payloadMap)),
                    null, questions, null, SseEvents.INTENT_QUERY, false, elapsedMs);
        }
        if (SseEvents.ASK_FAILED.equals(status) && json.get("error") instanceof Map<?, ?> error) {
            String remoteCode = stringValue(error.get("code"));
            String remoteMessage = stringValue(error.get("message"));
            if (remoteMessage == null || remoteMessage.isBlank()) {
                ErrorCode mapped = findErrorCode(remoteCode);
                remoteMessage = mapped.format();
            }
            String askId = stringValue(json.get("ask_id"));
            if (askId == null || askId.isBlank()) {
                askId = "ask_remote";
            }
            boolean recoverable = Boolean.TRUE.equals(error.get("recoverable"));
            log.warn("agent-gateway 返回业务失败, code={}, message={}", remoteCode, remoteMessage);
            // 不抛异常：走 SSE ERROR 事件，避免 MVC 异步线程未捕获 BizException 变成 HTTP 500
            Map<String, Object> errPayload = new LinkedHashMap<>();
            errPayload.put("code", remoteCode == null ? ErrorCode.AI_DEGRADED.getCode() : remoteCode);
            errPayload.put("message", remoteMessage);
            errPayload.put("recoverable", recoverable);
            errPayload.put("askId", askId);
            return new AgentResult(askId,
                    List.of(new SseEvent(SseEvents.ERROR, errPayload)),
                    null, List.of(), null, SseEvents.INTENT_QUERY, false, elapsedMs);
        }
        Map<String, Object> degraded = new LinkedHashMap<>();
        degraded.put("code", ErrorCode.AI_DEGRADED.getCode());
        degraded.put("message", "智能解析暂不可用，本次未执行查询，请稍后重试");
        degraded.put("recoverable", true);
        String askId = json == null ? "ask_remote" : stringValue(json.get("ask_id"));
        if (askId == null || askId.isBlank()) {
            askId = "ask_remote";
        }
        return new AgentResult(askId,
                List.of(new SseEvent(SseEvents.ERROR, degraded)),
                null, List.of(), null, SseEvents.INTENT_QUERY, true, elapsedMs);
    }

    private static String sqlFromCaliber(AnswerPayload payload) {
        if (payload == null || payload.caliber() == null) {
            return null;
        }
        String definition = payload.caliber().definition();
        return definition == null || definition.isBlank() ? null : definition;
    }

    private String resolveSessionId(AgentInvocationContext invocation, String askId) {
        if (invocation != null && invocation.sessionId() != null && !invocation.sessionId().isBlank()) {
            return invocation.sessionId();
        }
        if (askId != null) {
            return pendingSessionIds.getOrDefault(askId, "java-" + UUID.randomUUID());
        }
        return "java-" + UUID.randomUUID();
    }

    private void putInvocationFields(Map<String, Object> body, AgentInvocationContext invocation) {
        if (invocation == null) {
            return;
        }
        putIfNotNull(body, "invocation_id", invocation.invocationId());
        putIfNotNull(body, "tool_context_token", invocation.toolContextToken());
        putIfNotNull(body, "trace_id", invocation.traceId());
        putIfNotNull(body, "java_ask_id", invocation.javaAskId());
        putIfNotNull(body, "query_context", invocation.queryContext());
    }

    private HttpHeaders requestHeaders(UserContext ctx, AgentInvocationContext invocation) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (invocation != null && invocation.queryContext() != null && serviceToken != null && !serviceToken.isBlank())
            headers.set("X-Hrchat-Service-Token", serviceToken);
        headers.set("X-User-No", ctx.getEmpNo());
        String effectiveTenant = invocation != null && invocation.tenantId() != null && !invocation.tenantId().isBlank()
                ? invocation.tenantId()
                : (tenantNo != null && !tenantNo.isBlank() ? tenantNo : ctx.getTenantId());
        if (effectiveTenant != null && !effectiveTenant.isBlank()) {
            headers.set("X-Tenant-No", effectiveTenant);
        }
        if (invocation != null && invocation.traceId() != null && !invocation.traceId().isBlank()) {
            headers.set("X-Trace-Id", invocation.traceId());
        }
        return headers;
    }

    /**
     * Web camelCase 扁平 ContextOverride → 跨栈嵌套 snake_case（阶段 B 契约 3A）。
     */
    public static Map<String, Object> toRemoteContext(ContextOverride context) {
        Map<String, Object> remote = new LinkedHashMap<>();
        if (context.timeRange() != null) {
            Map<String, Object> timeRange = new LinkedHashMap<>();
            putIfNotNull(timeRange, "preset", context.timeRange().preset());
            putIfNotNull(timeRange, "start", context.timeRange().start());
            putIfNotNull(timeRange, "end", context.timeRange().end());
            putIfNotNull(timeRange, "grain", context.timeRange().grain());
            remote.put("time_range", timeRange);
        }
        if (context.orgId() != null || context.includeChildren() != null) {
            Map<String, Object> org = new LinkedHashMap<>();
            putIfNotNull(org, "org_id", context.orgId());
            putIfNotNull(org, "include_children", context.includeChildren());
            remote.put("org", org);
        }
        putIfNotNull(remote, "metrics", context.metrics());
        return remote;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private ErrorCode findErrorCode(String code) {
        if (code != null) {
            for (ErrorCode value : ErrorCode.values()) {
                if (value.getCode().equals(code)) {
                    return value;
                }
            }
        }
        return ErrorCode.AI_DEGRADED;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
