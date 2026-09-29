package com.hrchat.api.chat;

import java.util.List;

/**
 * 澄清应答请求（接口文档 2.2.6 POST …/asks/{askId}/clarifications）。
 */
public record ClarifyAnswerRequest(
        List<Answer> answers) {

    public record Answer(String questionId, List<String> optionIds) {
    }
}
