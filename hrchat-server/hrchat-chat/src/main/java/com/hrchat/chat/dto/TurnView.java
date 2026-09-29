package com.hrchat.chat.dto;

import java.time.LocalDateTime;

/**
 * 会话轮次视图（接口文档 2.2.2 历史轮次）。
 */
public record TurnView(Long turnId, String question, String intent, String status,
                       String conclusionBrief, String askId, LocalDateTime createdAt) {
}
