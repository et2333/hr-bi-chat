package com.hrchat.aiclient.model;

import java.util.List;

/**
 * 澄清问题（接口文档 2.2.11 INTERRUPT.payload.questions，BR-07：≤2 问、每问 ≤5 选项）。
 *
 * @param questionId 问题 id（客户端澄清应答原样回传）
 * @param question   澄清文案
 * @param options    选项（option_id 为语义对象编码）
 * @param multiple   是否多选（单选默认）
 */
public record ClarifyQuestion(String questionId, String question, List<Option> options, boolean multiple) {

    /** 澄清选项。 */
    public record Option(String optionId, String label) {
    }
}
