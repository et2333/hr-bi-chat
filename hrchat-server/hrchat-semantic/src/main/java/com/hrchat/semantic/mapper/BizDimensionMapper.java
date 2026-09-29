package com.hrchat.semantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.semantic.entity.BizDimension;
import org.apache.ibatis.annotations.Mapper;

/**
 * 维度定义表 Mapper。
 */
@Mapper
public interface BizDimensionMapper extends BaseMapper<BizDimension> {
}
