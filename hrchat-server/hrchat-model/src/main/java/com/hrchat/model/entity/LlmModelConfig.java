package com.hrchat.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LLM 大模型配置（llm_model_config）。
 */
@Data
@TableName("llm_model_config")
public class LlmModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型编码（唯一） */
    private String modelCode;

    /** 模型名称 */
    private String modelName;

    /** 厂商：openai/qwen/deepseek/ollama */
    private String vendor;

    /** API Base URL */
    private String baseUrl;

    /** API Key（L3 敏感，禁止入审计） */
    private String apiKey;

    /** 模型标识，如 gpt-4o */
    private String model;

    /** 采样温度 */
    private BigDecimal temperature;

    /** 最大输出 token 数 */
    private Integer maxTokens;

    /** Python 运行时部署地址 */
    private String deployUrl;

    /** 所属租户；NULL=系统默认（租户未配置时回退），P2 起生效 */
    private String tenantId;

    /** 1启用 0停用 */
    private Integer status;

    private Integer isDeleted;

    private LocalDateTime createdAt;

    private String createdBy;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
