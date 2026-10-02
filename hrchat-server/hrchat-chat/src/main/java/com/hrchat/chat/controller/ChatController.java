package com.hrchat.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.chat.dto.FeedbackRequest;
import com.hrchat.chat.dto.SessionView;
import com.hrchat.chat.dto.SqlView;
import com.hrchat.chat.dto.TurnView;
import com.hrchat.chat.service.ChatService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 问数会话接口（接口文档 2.2：会话 CRUD、问句 SSE、澄清、任务状态/SQL/表格、反馈）。
 */
@Tag(name = "智能问数", description = "会话/问答 SSE 流式、澄清、状态/SQL/表格、反馈（FR-01~06/22/23）")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    // ---------------- 会话 ----------------

    @Operation(summary = "创建会话")
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SessionView> createSession(@RequestBody(required = false) SessionCreateRequest request,
                                                  @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chatService.createSession(ctx, request == null ? null : request.title()));
    }

    @Operation(summary = "会话列表")
    @GetMapping("/sessions")
    public ApiResponse<PageResult<SessionView>> listSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chatService.listSessions(ctx, page, size));
    }

    @Operation(summary = "重命名会话")
    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<SessionView> renameSession(@PathVariable Long sessionId,
                                                  @RequestBody SessionRenameRequest request,
                                                  @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chatService.renameSession(ctx, sessionId, request.title()));
    }

    @Operation(summary = "删除会话（软删）")
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId, @CurrentUser UserContext ctx) {
        chatService.deleteSession(ctx, sessionId);
        return ApiResponse.ok();
    }

    @Operation(summary = "会话轮次历史")
    @GetMapping("/sessions/{sessionId}/turns")
    public ApiResponse<List<TurnView>> listTurns(@PathVariable Long sessionId, @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chatService.listTurns(ctx, sessionId));
    }

    // ---------------- 问句 / 澄清（SSE 流式或 SYNC） ----------------

    @Operation(summary = "提交问句（SSE 流式 / SYNC 同步）", description = "默认 STREAM；mode=SYNC 返回 ANSWER_DONE.payload JSON")
    @PostMapping(value = "/sessions/{sessionId}/asks", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Callable<ResponseEntity<StreamingResponseBody>> ask(@PathVariable Long sessionId,
                                                               @RequestBody AskRequest request,
                                                               @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
                                                               @CurrentUser UserContext ctx) {
        // remote：Python 会回调本进程 /mcp；必须释放 Tomcat 请求线程，否则同进程回环易 502
        return () -> withTenant(ctx, () -> buildAskResponse(sessionId, request, idempotencyKey, ctx));
    }

    @Operation(summary = "澄清应答（续跑原问答流）", description = "接口文档 2.2.6")
    @PostMapping(value = "/sessions/{sessionId}/asks/{askId}/clarifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Callable<ResponseEntity<StreamingResponseBody>> clarify(@PathVariable Long sessionId,
                                                                   @PathVariable String askId,
                                                                   @RequestBody ClarifyAnswerRequest request,
                                                                   @CurrentUser UserContext ctx) {
        return () -> withTenant(ctx, () -> {
            ChatService.AskOutcome outcome = chatService.clarify(ctx, sessionId, askId, request);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8));
            byte[] body = outcome.sseBody().getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok().headers(headers).body(os -> os.write(body));
        });
    }

    private ResponseEntity<StreamingResponseBody> buildAskResponse(Long sessionId, AskRequest request,
                                                                   String idempotencyKey, UserContext ctx) {
        ChatService.AskOutcome outcome = chatService.ask(ctx, sessionId, request, idempotencyKey);
        String mode = request.mode() == null || request.mode().isBlank() ? "STREAM" : request.mode().trim().toUpperCase();
        HttpHeaders headers = new HttpHeaders();
        if (outcome.replayed()) {
            headers.set("X-Idempotent-Replay", "true");
        }
        if ("SYNC".equals(mode)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            byte[] body = toJsonBytes(outcome.payload());
            return ResponseEntity.ok().headers(headers).body(os -> os.write(body));
        }
        headers.setContentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8));
        byte[] body = outcome.sseBody().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().headers(headers).body(os -> os.write(body));
    }

    private static <T> T withTenant(UserContext ctx, java.util.concurrent.Callable<T> action) throws Exception {
        String previous = TenantContextHolder.get();
        if (ctx != null && ctx.getTenantId() != null && !ctx.getTenantId().isBlank()) {
            TenantContextHolder.set(ctx.getTenantId());
        }
        try {
            return action.call();
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previous);
            }
        }
    }

    // ---------------- 任务状态 / SQL / 表格 / 反馈 ----------------

    @Operation(summary = "问答任务状态（ANSWER_DONE.payload 兜底拉取）")
    @GetMapping("/asks/{askId}")
    public ApiResponse<AnswerPayload> getAsk(@PathVariable String askId, @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chatService.getAsk(ctx, askId));
    }

    @Operation(summary = "查看查询逻辑（SQL 与口径，权限点 chat:view_sql）")
    @GetMapping("/asks/{askId}/sql")
    public ApiResponse<SqlView> getSql(@PathVariable String askId, @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chatService.getSql(ctx, askId));
    }

    @Operation(summary = "表格分页拉取")
    @GetMapping("/asks/{askId}/table")
    public ApiResponse<AnswerPayload.TableData> getTable(@PathVariable String askId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chatService.getTable(ctx, askId, page, size));
    }

    @Operation(summary = "纠错反馈")
    @PostMapping("/asks/{askId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@PathVariable String askId,
                         @RequestBody FeedbackRequest request,
                         @CurrentUser UserContext ctx) {
        chatService.feedback(ctx, askId, request);
    }

    private byte[] toJsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new com.hrchat.common.exception.BizException(com.hrchat.common.error.ErrorCode.SYSTEM_BUSY, e.getMessage());
        }
    }

    /** 创建会话请求体。 */
    public record SessionCreateRequest(String title) {
    }

    /** 重命名请求体。 */
    public record SessionRenameRequest(String title) {
    }
}
