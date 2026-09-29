package com.hrchat.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统设置（t_sys_setting）：键值配置。
 *
 * <p>默认项：{@code llm.default.profile}（系统回退模型档位）、{@code deploy.mock}（模拟部署开关）。</p>
 */
@Data
@TableName("t_sys_setting")
public class SysSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键（唯一） */
    private String settingKey;

    /** 配置值 */
    private String settingValue;

    /** 配置说明 */
    private String description;

    private LocalDateTime createdAt;

    private String createdBy;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
