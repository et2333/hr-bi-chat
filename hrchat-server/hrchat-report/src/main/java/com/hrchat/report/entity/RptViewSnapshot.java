package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 同版视图快照（rpt_view_snapshot，I0）。 */
@Data
@TableName("rpt_view_snapshot")
public class RptViewSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dataVersionId;
    private Long componentId;
    private String viewKey;
    private String chartJson;
    private String aggregateJson;
    private LocalDateTime createdAt;
}
