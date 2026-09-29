package com.hrchat.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.chat.entity.ChtSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话 Mapper。
 */
@Mapper
public interface ChtSessionMapper extends BaseMapper<ChtSession> {
}
