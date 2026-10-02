package com.hrchat.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 配置版本快照（llm_model_version，每次变更全量落一份）。
 */
@Data
@TableName("llm_model_version")
public class LlmModelVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联配置 id */
    private Long configId;

    /** 版本号（每次变更+1） */
    private Integer versionNo;

    /** 脱敏配置快照（JSON，不保存 API Key） */
    private String configJson;

    /** PENDING/SUCCESS/FAILED/ROLLBACK */
    private String applyResult;

    private LocalDateTime appliedAt;

    private String appliedBy;

    private String changeNote;
}
