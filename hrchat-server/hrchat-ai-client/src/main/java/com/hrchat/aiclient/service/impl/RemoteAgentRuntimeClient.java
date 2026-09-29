package com.hrchat.aiclient.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
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

/**
 * 远程 Python agent-gateway 客户端（AgentRuntimeClient 的 remote 形态，D-3）。
 *
 * <p>SYNC 调用远程运行时：{code /v1/chat/sessions/{sessionId}/asks} 与
 * {code …/asks/{askId}/clarifications}，响应含 {@code answer_payload}（终态）或
 * {@code questions}（澄清）两种形态，与本地引擎事件语义对齐。</p>
 */
@Slf4j
public class RemoteAgentRuntimeClient implements AgentRuntimeClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String tenantNo;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

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
        String sessionId = "java-" + ctx.getEmpNo();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", request.question());
        body.put("mode", "SYNC");
        if (request.contextOverride() != null) {
            body.put("context_override", request.contextOverride());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-No", ctx.getEmpNo());
        headers.set("X-Tenant-No", tenantNo);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl + "/v1/chat/sessions/" + sessionId + "/asks",
                new HttpEntity<>(body, headers), Map.class);
        return parse(resp.getBody(), System.currentTimeMillis() - start);
    }

    @Override
    public AgentResult clarify(String askId, String question, ClarifyAnswerRequest.Answer answers, UserContext ctx) {
        long start = System.currentTimeMillis();
        String sessionId = "java-" + ctx.getEmpNo();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("question_id", answers.questionId());
        answer.put("option_ids", answers.optionIds());
        Map<String, Object> body = Map.of("answers", List.of(answer));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-No", ctx.getEmpNo());
        headers.set("X-Tenant-No", tenantNo);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl + "/v1/chat/sessions/" + sessionId + "/asks/" + askId + "/clarifications",
                new HttpEntity<>(body, headers), Map.class);
        return parse(resp.getBody(), System.currentTimeMillis() - start);
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

    /**
     * 解析远程响应：answer_payload → ANSWER_DONE；questions → INTERRUPT(CLARIFY)；否则降级。
     */
    @SuppressWarnings("unchecked")
    private AgentResult parse(Map<?, ?> json, long elapsedMs) {
        Object answerPayload = json == null ? null : json.get("answer_payload");
        if (answerPayload != null) {
            AnswerPayload payload = objectMapper.convertValue(answerPayload, AnswerPayload.class);
            String askId = payload.askId() != null && !payload.askId().isBlank()
                    ? payload.askId() : "ask_remote";
            return new AgentResult(askId,
                    List.of(new SseEvent(SseEvents.ANSWER_DONE, objectMapper.convertValue(payload, Map.class))),
                    payload, List.of(), null, SseEvents.INTENT_QUERY, false, elapsedMs);
        }
        Object questionsObj = json == null ? null : json.get("questions");
        if (questionsObj instanceof List && !((List<?>) questionsObj).isEmpty()) {
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
                        String.valueOf(qm.get("question")), options, false));
            }
            String askId = String.valueOf(payloadMap.getOrDefault("ask_id", "ask_remote"));
            return new AgentResult(askId,
                    List.of(new SseEvent(SseEvents.INTERRUPT, payloadMap)),
                    null, questions, null, SseEvents.INTENT_QUERY, false, elapsedMs);
        }
        throw new BizException(ErrorCode.AI_DEGRADED);
    }
}
