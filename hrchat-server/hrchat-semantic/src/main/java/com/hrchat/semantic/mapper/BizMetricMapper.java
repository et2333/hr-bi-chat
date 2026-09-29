package com.hrchat.semantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.semantic.entity.BizMetric;
import org.apache.ibatis.annotations.Mapper;

/**
 * 指标定义表 Mapper。
 */
@Mapper
public interface BizMetricMapper extends BaseMapper<BizMetric> {
}
