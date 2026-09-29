package com.hrchat.authz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.authz.entity.SecOrgGrant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 组织范围授权表 Mapper。
 */
@Mapper
public interface SecOrgGrantMapper extends BaseMapper<SecOrgGrant> {
}
