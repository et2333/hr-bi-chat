package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 报表组件（rpt_component，数据库文档 4.4）。
 *
 * <p>def_json 存语义层对象 {metric,dim,filter}，非 SQL（口径由语义层单一事实源保证）。</p>
 */
@Data
@TableName("rpt_component")
public class RptComponent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    /** 组件类型：1图表 2明细表 3指标卡 */
    private Integer compType;

    private String chartType;

    /** 组件定义 JSON */
    private String defJson;

    private Integer sortNo;
}
