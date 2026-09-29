package com.hrchat.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 同步任务（itg_sync_task，数据库文档 4.5）。
 */
@Data
@TableName("itg_sync_task")
public class ItgSyncTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dsId;

    /** 任务类型：1表全量 2表增量 3维表 */
    private Integer taskType;

    /** 业务日期（T-1） */
    private LocalDate bizDate;

    /** 执行状态：0待执行 1执行中 2成功 3失败 4重试中 */
    private Integer execState;

    private Long rowsRead;

    private Long rowsWritten;

    private String failReason;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
