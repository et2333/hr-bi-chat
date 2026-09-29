package com.hrchat.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 评测问句（evl_eval_question，数据库文档 4.7）。
 */
@Data
@TableName("evl_eval_question")
public class EvlEvalQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String question;

    /** 场景标签：simple/complex/attribution/… */
    private String sceneTag;

    /** 期望结果 JSON */
    private String expectJson;

    /** 最近结果：1通过 0失败 */
    private Integer lastResult;

    /** 来源：1手工 2线上回放 */
    private Integer sourceType;
}
