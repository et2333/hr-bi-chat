package com.hrchat.api.chat;

/**
 * 时间范围（接口文档 2.2.5 context_override.time_range）。
 *
 * @param preset 时间预设：LAST_7D/LAST_30D/THIS_MONTH/LAST_MONTH/THIS_QUARTER/LAST_QUARTER/THIS_YEAR/LAST_YEAR/CUSTOM
 * @param start  CUSTOM 时必填，ISO-8601
 * @param end    CUSTOM 时必填，ISO-8601
 * @param grain  时间粒度：NONE/DAY/WEEK/MONTH/QUARTER/YEAR
 */
public record TimeRange(String preset, String start, String end, String grain) {
}
