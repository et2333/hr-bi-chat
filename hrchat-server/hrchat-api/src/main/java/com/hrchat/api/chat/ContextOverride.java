package com.hrchat.api.chat;

import java.util.List;

/**
 * 显式上下文覆盖（接口文档 2.2.5：下钻/切换维度传参）。
 *
 * @param timeRange       时间范围覆盖
 * @param orgId           组织节点 ID
 * @param includeChildren 是否含下级，默认 true
 * @param metrics         显式指定指标语义对象（下钻传参）
 */
public record ContextOverride(TimeRange timeRange, String orgId, Boolean includeChildren, List<String> metrics) {
}
