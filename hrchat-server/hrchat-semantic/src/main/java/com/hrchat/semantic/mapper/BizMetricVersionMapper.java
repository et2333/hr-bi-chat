package com.hrchat.semantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.semantic.entity.BizMetricVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 指标版本表 Mapper（版本审批流核心，BR-04 口径唯一）。
 */
@Mapper
public interface BizMetricVersionMapper extends BaseMapper<BizMetricVersion> {
}
