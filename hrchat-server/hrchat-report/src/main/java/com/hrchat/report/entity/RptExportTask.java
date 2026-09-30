package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表导出任务（rpt_export_task，阶段3：导出落库，DB 为唯一事实源）。
 *
 * <p>status: PENDING/COMPLETED/FAILED；file_blob 存导出文件字节（CSV/XLSX/PDF）；
 * expires_at = 创建时间 + 15min 有效期。</p>
 */
@Data
@TableName("rpt_export_task")
public class RptExportTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 导出 ID（exp_ + UUID 短码，唯一） */
    private String exportId;

    private Long reportId;

    private String tenantId;

    private String ownerEmpNo;

    /** CSV/XLSX/PDF */
    private String format;

    /** PENDING/COMPLETED/FAILED */
    private String status;

    private Integer rowCount;

    /** 导出文件字节 */
    private byte[] fileBlob;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
