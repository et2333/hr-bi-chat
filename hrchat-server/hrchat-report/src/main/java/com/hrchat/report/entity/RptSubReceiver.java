package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 订阅收件人（rpt_sub_receiver，数据库文档 4.4）。
 */
@Data
@TableName("rpt_sub_receiver")
public class RptSubReceiver {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long subscriptionId;

    /** sec_user.id */
    private Long userId;

    /** 最近推送：0待推 1成功 2失败 */
    private Integer pushStatus;
}
