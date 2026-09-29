package com.hrchat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 澄清记录（cht_clarify，BR-07）。
 */
@Data
@TableName("cht_clarify")
public class ChtClarify {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long turnId;

    /** 歧义类型：1 指标歧义 2 时间歧义 3 维度歧义 */
    private Integer ambiguityType;

    private String questionText;

    /** 选项（JSON 数组） */
    private String optionsJson;

    /** 用户选定选项 */
    private String selectedCode;

    /** 是否记为偏好（FR-02） */
    private Integer savedAsPref;
}
