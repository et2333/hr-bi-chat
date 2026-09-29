package com.hrchat.chat.dto;

import java.util.Map;

/**
 * 查询逻辑视图（接口文档 2.2.8，SQL 中组织过滤以注释标注权限注入位置）。
 */
public record SqlView(String askId, String sql, Map<String, String> caliber) {
}
