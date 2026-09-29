package com.hrchat.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.audit.entity.AudAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper。
 */
@Mapper
public interface AudAuditLogMapper extends BaseMapper<AudAuditLog> {
}
