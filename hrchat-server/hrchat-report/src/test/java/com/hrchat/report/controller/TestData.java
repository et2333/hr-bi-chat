package com.hrchat.report.controller;

import com.hrchat.semantic.dto.MetricDetail;

import java.util.List;

/** 测试用指标详情样例。 */
final class TestData {

    private TestData() {
    }

    static MetricDetail metric() {
        return new MetricDetail(
                1L, "headcount", "在职人数", "staff",
                "SELECT COUNT(*) FROM dim_employee", "期末在职人数",
                "MONTH", 1, 1, 1, 0,
                1, List.of("org"), "adm01", null);
    }
}
