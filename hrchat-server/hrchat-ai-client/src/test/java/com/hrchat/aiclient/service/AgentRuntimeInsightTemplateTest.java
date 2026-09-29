package com.hrchat.aiclient.service;

import com.hrchat.aiclient.model.InsightRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentRuntimeClient 默认实现单测（P3-C 本地模板解读）：均值/趋势/极值要点与空数据回退。
 */
class AgentRuntimeInsightTemplateTest {

    private Map<String, Object> render(Map<String, Object> summary) {
        AgentRuntimeClient client = Mockito.mock(AgentRuntimeClient.class);
        Mockito.when(client.generateInsight(Mockito.any(InsightRequest.class))).thenCallRealMethod();
        return client.generateInsight(new InsightRequest("人力编制报表", "在职人数", summary));
    }

    @Test
    void defaultInsight_buildsTemplatePoints() {
        Map<String, Object> summary = Map.of(
                "categories", List.of("研发一部", "研发二部", "研发三部"),
                "series", List.of(Map.of("name", "在职人数", "data", List.of(10, 5, 15))));

        Map<String, Object> view = render(summary);

        assertThat(view.get("report_name")).isEqualTo("人力编制报表");
        assertThat(String.valueOf(view.get("summary"))).contains("3 个周期");
        assertThat((List<?>) view.get("points")).hasSize(3);
    }

    @Test
    void defaultInsight_emptyDataFallsBack() {
        Map<String, Object> summary = Map.of("categories", List.of(), "series", List.of());

        Map<String, Object> view = render(summary);

        assertThat(String.valueOf(view.get("summary"))).contains("暂无可用数据");
        assertThat((List<?>) view.get("points")).isEmpty();
    }
}
