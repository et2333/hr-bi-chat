package com.hrchat.chat.dto;

import java.time.LocalDateTime;

/**
 * 会话视图（接口文档 2.2.1-2.2.3）。
 */
public record SessionView(Long id, String title, Integer status, LocalDateTime lastActiveAt,
                          Boolean pinned, LocalDateTime createdAt) {
}
