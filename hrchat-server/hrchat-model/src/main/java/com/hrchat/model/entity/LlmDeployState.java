package com.hrchat.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 部署状态与健康检查（llm_deploy_state，每配置一行）。
 */
@Data
@TableName("llm_deploy_state")
public class LlmDeployState {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联配置 id（唯一） */
    private Long configId;

    /** PENDING/APPLYING/ACTIVE/FAILED */
    private String state;

    /** UP/DOWN/UNKNOWN */
    private String healthStatus;

    /** 最近健康检查耗时（ms） */
    private Integer latencyMs;

    /** 运行时 profile：mock/openai */
    private String llmProfile;

    private LocalDateTime lastCheckedAt;

    private LocalDateTime updatedAt;
}
