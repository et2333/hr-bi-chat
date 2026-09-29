package com.hrchat.authz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.authz.entity.SecUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-角色关联表 Mapper。
 */
@Mapper
public interface SecUserRoleMapper extends BaseMapper<SecUserRole> {
}
