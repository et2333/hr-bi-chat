package com.hrchat.semantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.semantic.entity.BizMetricDim;
import org.apache.ibatis.annotations.Mapper;

/**
 * 指标-维度关联表 Mapper（N:N）。
 */
@Mapper
public interface BizMetricDimMapper extends BaseMapper<BizMetricDim> {
}
