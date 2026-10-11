package com.hrchat.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.report.entity.RptReport;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RptReportMapper extends BaseMapper<RptReport> {
    @org.apache.ibatis.annotations.Select("SELECT id FROM rpt_report WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Long lockVersion(@org.apache.ibatis.annotations.Param("id") Long id);
}
