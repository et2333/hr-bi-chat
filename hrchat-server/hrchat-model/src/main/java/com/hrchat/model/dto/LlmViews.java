package com.hrchat.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 大模型配置管理视图/请求 DTO 聚合（接口文档 2.9）。
 */
public final class LlmViews {

    private LlmViews() {
    }

    /** 模型列表视图（含部署/健康/版本数）。 */
    public record ModelView(Long id, String modelCode, String modelName, String vendor, String model,
                            Integer status, String deployState, String healthStatus,
                            Integer versionCount, LocalDateTime updatedAt) {
    }

    /** 模型详情视图（apiKey 脱敏）。 */
    public record ModelDetailView(Long id, String modelCode, String modelName, String vendor,
                                  String baseUrl, String apiKeyMasked, String model,
                                  BigDecimal temperature, Integer maxTokens, String deployUrl,
                                  Integer status, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 创建/更新模型请求。 */
    public record ModelCreateRequest(String modelCode, String modelName, String vendor, String baseUrl,
                                     String apiKey, String model, BigDecimal temperature,
                                     Integer maxTokens, String deployUrl) {
    }

    /** 配置版本视图。 */
    public record VersionView(Long id, Integer versionNo, String configJson, String applyResult,
                              LocalDateTime appliedAt, String appliedBy, String changeNote) {
    }

    /** 部署状态视图。 */
    public record DeployStateView(Long configId, String state, String healthStatus, Integer latencyMs,
                                  String llmProfile, LocalDateTime lastCheckedAt) {
    }

    /** 健康检查视图。 */
    public record HealthView(String modelCode, String state, String healthStatus, Integer latencyMs,
                             String llmProfile, LocalDateTime lastCheckedAt) {
    }

    /** 部署监控摘要。 */
    public record MonitorView(Integer total, Integer active, Integer failed, Integer degraded,
                              List<MonitorItemView> items) {
    }

    /** 部署监控条目。 */
    public record MonitorItemView(Long configId, String modelCode, String modelName, String deployState,
                                  String healthStatus, Integer versionCount, LocalDateTime lastCheckedAt) {
    }

    /** 版本回滚请求。 */
    public record RollbackRequest(Long versionId) {
    }
}
