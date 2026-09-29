package com.hrchat.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.model.entity.SysSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统设置 Mapper（t_sys_setting）。
 */
@Mapper
public interface SysSettingMapper extends BaseMapper<SysSetting> {
}
