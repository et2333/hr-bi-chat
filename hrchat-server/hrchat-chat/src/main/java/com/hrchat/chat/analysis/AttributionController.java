package com.hrchat.chat.analysis;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

/** Explicit analysis opt-in; ordinary asks never launch this workflow. */
@RestController
@RequestMapping("/api/v1/chat")
public class AttributionController {
    private final AttributionService service;
    public AttributionController(AttributionService service) { this.service = service; }

    @GetMapping("/asks/{askId}/attribution/context")
    public ApiResponse<Map<String, Object>> context(@PathVariable String askId, @CurrentUser UserContext user) {
        return ApiResponse.ok(service.context(user, askId));
    }

    @PostMapping("/asks/{askId}/attribution")
    public ApiResponse<Map<String, Object>> start(@PathVariable String askId,
            @RequestBody AttributionService.StartRequest request,
            @RequestHeader("X-Idempotency-Key") String key, @CurrentUser UserContext user) {
        return ApiResponse.ok(service.start(user, askId, request, key));
    }

    @GetMapping("/attribution/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> read(@PathVariable String taskId, @CurrentUser UserContext user) {
        return ApiResponse.ok(service.read(user, taskId));
    }

    @PostMapping("/attribution/tasks/{taskId}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable String taskId, @CurrentUser UserContext user) {
        return ApiResponse.ok(service.cancel(user, taskId));
    }

    @GetMapping(value = "/attribution/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> events(@PathVariable String taskId,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long after,
            @CurrentUser UserContext user) {
        service.read(user, taskId); // Fail with the normal HTTP error before starting the stream.
        service.validateCursor(taskId, after);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no")
                .body(output -> service.writeEvents(user, taskId, after, output));
    }
}
