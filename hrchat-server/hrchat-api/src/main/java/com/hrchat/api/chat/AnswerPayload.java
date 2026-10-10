package com.hrchat.api.chat;

import java.util.List;
import java.util.Map;

/**
 * ANSWER_DONE.payload 完整结构（三段式：结论/数据/图表 + 口径溯源，接口文档 2.2.11，FR-04）。
 */
public record AnswerPayload(
        String askId,
        String answerId,
        String status,
        String intent,
        boolean degraded,
        String degradedTip,
        Conclusion conclusion,
        TableData table,
        Chart chart,
        Caliber caliber,
        List<String> followups,
        Long elapsedMs,
        Map<String, Object> analysisPreparation,
        Map<String, Object> usage) {

    public AnswerPayload(String askId, String answerId, String status, String intent, boolean degraded,
                         String degradedTip, Conclusion conclusion, TableData table, Chart chart, Caliber caliber,
                         List<String> followups, Long elapsedMs) {
        this(askId, answerId, status, intent, degraded, degradedTip, conclusion, table, chart, caliber,
                followups, elapsedMs, null, null);
    }

    /** 结论层 */
    public record Conclusion(String type, Object value, String unit, Compare compare) {
    }

    /** 环比/同比对比 */
    public record Compare(String period, Object value, String direction) {
    }

    /** 数据层（rows ≤ 20，脱敏字段 masked=true） */
    public record TableData(List<Column> columns, List<Map<String, Object>> rows, long total, int page, int size) {
    }

    public record Column(String key, String name, String type, boolean masked) {
    }

    /** 图表层（ECharts option 子集） */
    public record Chart(String type, boolean recommended, Map<String, Object> config) {
    }

    /** 口径溯源（BR-09 数据时效标注）；metric 为展示名，metricCode 为语义层 code（存报表用）。 */
    public record Caliber(String metric, String definition, String timeRange, String dataUpdatedAt,
                          String metricCode, String organization, String queryMode) {
        public Caliber(String metric, String definition, String timeRange, String dataUpdatedAt, String metricCode) {
            this(metric, definition, timeRange, dataUpdatedAt, metricCode, null, null);
        }
        /** 兼容旧四参构造（无 code）。 */
        public Caliber(String metric, String definition, String timeRange, String dataUpdatedAt) {
            this(metric, definition, timeRange, dataUpdatedAt, null);
        }
    }
}
