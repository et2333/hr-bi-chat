package com.hrchat.authz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.authz.entity.SecRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色表 Mapper（S5 admin-svc 角色管理使用）。
 */
@Mapper
public interface SecRoleMapper extends BaseMapper<SecRole> {
}
