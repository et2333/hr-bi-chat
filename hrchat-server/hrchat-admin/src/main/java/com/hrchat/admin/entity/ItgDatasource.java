package com.hrchat.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 数据源（itg_datasource，数据库文档 4.5）。
 */
@Data
@TableName("itg_datasource")
public class ItgDatasource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsCode;

    private String dsName;

    /** 同步模式：1全量 2增量 3CDC */
    private Integer syncMode;

    /** 凭据引用（KMS key） */
    private String credRef;

    private String scheduleCron;

    /** 状态：1启用 0停用 */
    private Integer status;
}
