package com.hrchat.aiclient.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final RestTemplate restTemplate;
    private final Map<String, String> pendingSessionIds = new ConcurrentHashMap<>();

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
        this.restTemplate = restTemplate;
    }

    @Override
    public AgentResult ask(AskRequest request, UserContext ctx) {
        long start = System.currentTimeMillis();
        // AgentRuntimeClient 暂未接收 Java 会话 ID；先为每次新问答分配隔离 ID，
        // 并将澄清请求绑定回同一远程会话，避免同工号并发会话互相污染。
        String sessionId = "java-" + UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", request.question());
        body.put("mode", "SYNC");
        if (request.contextOverride() != null) {
            body.put("context_override", toRemoteContext(request.contextOverride()));
        }
        HttpHeaders headers = requestHeaders(ctx);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl + "/v1/chat/sessions/" + sessionId + "/asks",
                new HttpEntity<>(body, headers), Map.class);
        AgentResult result = parse(resp.getBody(), System.currentTimeMillis() - start);
        if (result.isClarifying()) {
            pendingSessionIds.put(result.askId(), sessionId);
        }
        return result;
    }

    @Override
    public AgentResult clarify(String askId, String question, ClarifyAnswerRequest.Answer answers, UserContext ctx) {
        long start = System.currentTimeMillis();
        String sessionId = pendingSessionIds.getOrDefault(askId, "java-" + UUID.randomUUID());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("question_id", answers.questionId());
        answer.put("option_ids", answers.optionIds());
        Map<String, Object> body = Map.of("answers", List.of(answer));
        HttpHeaders headers = requestHeaders(ctx);
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
        String status = json == null ? null : stringValue(json.get("status"));
        if (SseEvents.ASK_COMPLETED.equals(status) && json.get("answer_payload") != null) {
            Object answerPayload = json.get("answer_payload");
            AnswerPayload payload = objectMapper.convertValue(answerPayload, AnswerPayload.class);
            String askId = payload.askId() != null && !payload.askId().isBlank()
                    ? payload.askId() : stringValue(json.get("ask_id"));
            if (askId == null || askId.isBlank()) {
                askId = "ask_remote";
            }
            return new AgentResult(askId,
                    List.of(new SseEvent(SseEvents.ANSWER_DONE, objectMapper.convertValue(payload, Map.class))),
                    payload, List.of(), null, SseEvents.INTENT_QUERY, false, elapsedMs);
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
            ErrorCode mapped = findErrorCode(remoteCode);
            log.warn("agent-gateway 返回业务失败, code={}, message={}", remoteCode, remoteMessage);
            throw new BizException(mapped);
        }
        throw new BizException(ErrorCode.AI_DEGRADED);
    }

    private HttpHeaders requestHeaders(UserContext ctx) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-No", ctx.getEmpNo());
        String effectiveTenant = tenantNo != null && !tenantNo.isBlank() ? tenantNo : ctx.getTenantId();
        if (effectiveTenant != null && !effectiveTenant.isBlank()) {
            headers.set("X-Tenant-No", effectiveTenant);
        }
        return headers;
    }

    private Map<String, Object> toRemoteContext(ContextOverride context) {
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

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
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
