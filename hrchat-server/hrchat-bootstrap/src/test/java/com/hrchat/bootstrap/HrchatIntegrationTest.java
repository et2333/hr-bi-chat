package com.hrchat.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S8 全应用集成测试（local profile：H2 + 内存中间件，零外部依赖）。
 *
 * <p>覆盖核心业务流程：问数主链路（SYNC/STREAM/状态/反馈）、数据越权拦截（HRC-2003 + 审计留痕）、
 * 报表 CRUD + 订阅（FR-12/FR-14）、审计检索（FR-20）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class HrchatIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    // ---------------- 问数主链路（2.2.3/2.2.4/2.2.5） ----------------

    @Test
    void askFlow_syncStreamStatusAndFeedback() throws Exception {
        // 1) 创建会话
        MvcResult session = mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"集成测试会话\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        long sessionId = objectMapper.readTree(session.getResponse().getContentAsString())
                .at("/data/id").asLong();

        // 2) SYNC 问数 → ANSWER_DONE.payload 三段式
        MvcResult syncStarted = mockMvc.perform(post("/api/v1/chat/sessions/" + sessionId + "/asks")
                        .header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"研发中心在职人数\",\"mode\":\"SYNC\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult sync = completeAsync(syncStarted)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.conclusion").exists())
                .andExpect(jsonPath("$.table.columns[0].key").value("headcount"))
                .andReturn();
        String askId = objectMapper.readTree(sync.getResponse().getContentAsString())
                .at("/askId").asText();
        assertFalse(askId.isBlank());

        // 3) STREAM 问数 → SSE 帧流（HEARTBEAT seq=-1 + ANSWER_DONE 终态）
        MvcResult streamStarted = mockMvc.perform(post("/api/v1/chat/sessions/" + sessionId + "/asks")
                        .header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"研发中心在职人数\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        completeAsync(streamStarted)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/event-stream")))
                .andExpect(content().string(containsString("event: HEARTBEAT")))
                .andExpect(content().string(containsString("\"seq\":-1")))
                .andExpect(content().string(containsString("event: ANSWER_DONE")))
                .andExpect(content().string(not(containsString("event: ERROR"))));

        // 4) 任务状态兜底拉取 + 纠错反馈（UP → 204）
        mockMvc.perform(get("/api/v1/chat/asks/" + askId)
                        .header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/chat/asks/" + askId + "/feedback")
                        .header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"UP\",\"comment\":\"很准\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void dataRangeDenied_returnsStreamErrorNotData() throws Exception {
        // hr02 自建会话后询问研发中心：数据越权在运行时内被转为流内 ERROR（HRC-2003），HTTP 200
        MvcResult session = mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("X-User-No", "hr02")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"销售越权用例\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long sessionId = objectMapper.readTree(session.getResponse().getContentAsString())
                .at("/data/id").asLong();

        MvcResult deniedStarted = mockMvc.perform(post("/api/v1/chat/sessions/" + sessionId + "/asks")
                        .header("X-User-No", "hr02")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"研发中心在职人数\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        completeAsync(deniedStarted)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/event-stream")))
                .andExpect(content().string(containsString("event: ERROR")))
                .andExpect(content().string(containsString("HRC-2003")))
                .andExpect(content().string(not(containsString("event: ANSWER_DONE"))));
    }

    // ---------------- 报表 CRUD + 订阅（2.3 / FR-12 / FR-14 / BR-13） ----------------

    @Test
    void report_createListSubscribeCancel() throws Exception {
        // 创建 CUSTOM 报表
        MvcResult created = mockMvc.perform(post("/api/v1/reports")
                        .header("X-User-No", "hr01")
                        .header("X-Idempotency-Key", "it-report-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceType":"CUSTOM","sourceId":null,"name":"研发在职月报","folder":"default",
                                 "refresh":{"frequency":"MONTHLY","time":"09:00"},
                                 "components":[{"compType":"METRIC_CARD","chartType":"NUMBER_CARD",
                                   "def":{"metric":"headcount","dim":"org"}}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").isNumber())
                .andReturn();
        long reportId = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data").asLong();

        // 列表可见
        mockMvc.perform(get("/api/v1/reports?keyword=研发在职")
                        .header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.id == " + reportId + ")]").exists());

        // 订阅 + 取消订阅
        MvcResult sub = mockMvc.perform(post("/api/v1/reports/" + reportId + ":subscribe")
                        .header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"frequency":"DAILY","channel":"FEISHU",
                                 "receivers":[{"type":"user","id":"hr02"}],
                                 "params":{"metric":"headcount"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        long subscriptionId = objectMapper.readTree(sub.getResponse().getContentAsString())
                .at("/data/id").asLong();

        mockMvc.perform(delete("/api/v1/reports/" + reportId + "/subscriptions/" + subscriptionId)
                        .header("X-User-No", "hr01"))
                .andExpect(status().isOk());

        // 非所有者更新 → HRC-2002（报告归属校验）
        mockMvc.perform(patch("/api/v1/reports/" + reportId)
                        .header("X-User-No", "hr02")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权改名\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HRC-2002"));
    }

    // ---------------- 审计检索（2.6 / FR-20 / BR-11） ----------------

    @Test
    void auditLogs_searchByUserAndAction() throws Exception {
        // 自足数据：hr01 问数（记 ASK 审计）+ hr02 跨会话越权（会话归属 403 → 记 PERM_DENIED 审计）
        MvcResult s1 = mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"审计会话\"}"))
                .andExpect(status().isCreated()).andReturn();
        long hr01Session = objectMapper.readTree(s1.getResponse().getContentAsString())
                .at("/data/id").asLong();

        MvcResult askStarted = mockMvc.perform(post("/api/v1/chat/sessions/" + hr01Session + "/asks")
                        .header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"研发中心在职人数\",\"mode\":\"SYNC\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        completeAsync(askStarted).andExpect(status().isOk());

        MvcResult deniedStarted = mockMvc.perform(post("/api/v1/chat/sessions/" + hr01Session + "/asks")
                        .header("X-User-No", "hr02")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"在职人数\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        completeAsync(deniedStarted)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HRC-2002"));

        // 管理员检索：ASK（hr01）与 PERM_DENIED（hr02）均已留痕（BR-02/BR-11）
        mockMvc.perform(get("/api/v1/admin/audit/logs?action=ASK&user_id=hr01")
                        .header("X-User-No", "adm01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.action == 'ASK')]").exists());

        mockMvc.perform(get("/api/v1/admin/audit/logs?action=PERM_DENIED&user_id=hr02")
                        .header("X-User-No", "adm01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.action == 'PERM_DENIED')]").exists());

        // 非审计角色 → HRC-2002
        mockMvc.perform(get("/api/v1/admin/audit/logs")
                        .header("X-User-No", "hr01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HRC-2002"));
    }

    /** Callable 返回 StreamingResponseBody 时 MockMvc 会经历两层异步调度。 */
    private ResultActions completeAsync(MvcResult started) throws Exception {
        MvcResult current = started;
        while (true) {
            ResultActions dispatched = mockMvc.perform(asyncDispatch(current));
            MvcResult result = dispatched.andReturn();
            if (!result.getRequest().isAsyncStarted()) {
                return dispatched;
            }
            current = result;
        }
    }
}
