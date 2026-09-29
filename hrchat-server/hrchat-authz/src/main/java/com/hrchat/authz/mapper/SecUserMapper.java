package com.hrchat.authz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.authz.entity.SecUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 */
@Mapper
public interface SecUserMapper extends BaseMapper<SecUser> {
}
