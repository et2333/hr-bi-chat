package com.hrchat.chat.analysis;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 报表来源的进一步分析入口（I3）：预填/校验后由页面确认，再创建分析任务。
 * 任务进度/取消/事件复用 {@link AttributionController} 的任务端点（按 taskId 与来源无关）。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportAnalysisController {
    private final AttributionService service;

    public ReportAnalysisController(AttributionService service) { this.service = service; }

    /** 开始分析请求体：来源数据版本 + 比较基期 + 运行模式。 */
    public record StartReportAnalysisRequest(Long dataVersionId, AttributionService.Period baselinePeriod, String mode) { }

    @GetMapping("/{reportId}/analysis/context")
    public ApiResponse<Map<String, Object>> context(@PathVariable Long reportId,
            @RequestParam Long dataVersionId,
            @RequestParam(required = false) String capabilityId,
            @CurrentUser UserContext user) {
        return ApiResponse.ok(service.prefillFromReport(user, reportId, dataVersionId, capabilityId));
    }

    @PostMapping("/{reportId}/analysis")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long reportId,
            @RequestBody StartReportAnalysisRequest request,
            @RequestHeader("X-Idempotency-Key") String key, @CurrentUser UserContext user) {
        return ApiResponse.ok(service.startFromReport(user, reportId, request.dataVersionId(),
                new AttributionService.StartRequest(request.baselinePeriod(), request.mode()), key));
    }
}
