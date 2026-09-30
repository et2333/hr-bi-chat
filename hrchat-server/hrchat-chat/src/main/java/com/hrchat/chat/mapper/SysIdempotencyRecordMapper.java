package com.hrchat.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.chat.entity.SysIdempotencyRecord;
import org.apache.ibatis.annotations.Mapper;

/** 幂等事实表 Mapper。 */
@Mapper
public interface SysIdempotencyRecordMapper extends BaseMapper<SysIdempotencyRecord> {
}
