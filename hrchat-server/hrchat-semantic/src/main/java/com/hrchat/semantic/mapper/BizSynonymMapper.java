package com.hrchat.semantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.semantic.entity.BizSynonym;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同义词库表 Mapper（NL 归一化 FR-22）。
 */
@Mapper
public interface BizSynonymMapper extends BaseMapper<BizSynonym> {
}
