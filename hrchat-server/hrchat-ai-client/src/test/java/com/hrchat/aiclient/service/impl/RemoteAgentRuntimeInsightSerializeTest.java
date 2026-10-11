package com.hrchat.aiclient.service.impl;

import com.hrchat.aiclient.model.InsightRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I1：Java InsightRequest → Python snake_case 往返字段不丢版本/期间/null。
 */
class RemoteAgentRuntimeInsightSerializeTest {

    @Test
    void toRemoteInsight_preservesRichContractFieldsAndNullSeriesValues() {
        List<Number> data = new ArrayList<>();
        data.add(1);
        data.add(null);
        data.add(2);
        InsightRequest request = new InsightRequest(
                "编制对比",
                "在职人数",
                Map.of("categories", List.of("2026-01", "2026-02", "2026-03"),
                        "series", List.of(Map.of("name", "在职人数", "data", data))),
                new InsightRequest.InsightContext(
                        "1", "7", "23", "headcount", "1", "在职人数", "编制对比", "人", "stock",
                        new InsightRequest.Period("2026-03-01", "2026-04-01"),
                        new InsightRequest.Period("2026-01-01", "2026-02-01"),
                        List.of(10L), "88", "root", "org:10", null, null),
                new InsightRequest.InsightSnapshot(
                        "snap-1", "88", "root",
                        List.of("2026-01", "2026-02", "2026-03"),
                        List.of(new InsightRequest.SeriesItem("在职人数", data)),
                        null, false, null, null, List.of()));

        Map<String, Object> body = RemoteAgentRuntimeClient.toRemoteInsight(request);

        assertThat(body.get("report_name")).isEqualTo("编制对比");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("context");
        assertThat(context.get("data_version")).isEqualTo("88");
        assertThat(context.get("view_key")).isEqualTo("root");
        assertThat(context.get("metric_kind")).isEqualTo("stock");
        assertThat(context.get("unit")).isEqualTo("人");
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) context.get("current_period");
        assertThat(current.get("start")).isEqualTo("2026-03-01");
        assertThat(current.get("end")).isEqualTo("2026-04-01");
        assertThat(context.get("effective_org_ids")).isEqualTo(List.of(10L));

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) body.get("snapshot");
        assertThat(snapshot.get("data_version")).isEqualTo("88");
        assertThat(snapshot.get("view_key")).isEqualTo("root");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) snapshot.get("series");
        @SuppressWarnings("unchecked")
        List<Number> remoteData = (List<Number>) series.get(0).get("data");
        assertThat(remoteData).containsExactly(1, null, 2);
    }

    @Test
    void toRemoteInsight_legacyPayloadOmitsContextAndSnapshot() {
        Map<String, Object> body = RemoteAgentRuntimeClient.toRemoteInsight(
                new InsightRequest("报表", "人数", Map.of("categories", List.of(), "series", List.of())));

        assertThat(body).containsKeys("report_name", "metric_name", "summary");
        assertThat(body).doesNotContainKeys("context", "snapshot");
    }
}
