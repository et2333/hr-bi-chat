package com.hrchat.report.chart;

import com.hrchat.report.chart.ChartViews.ChartAnalysisView;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.InsightView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChartViews 兼容构造器与视图键兼容工具的补测。
 */
class ChartViewsTest {

    @Test
    void chartAnalysisView_compatConstructor_infersRootAndDrillViewKey() {
        ChartDataView chart = new ChartDataView("BAR", List.of("研发一部"), List.of(), List.of());

        ChartAnalysisView root = new ChartAnalysisView("7", null, chart, null);
        assertThat(root.viewKey()).isEqualTo("root");
        assertThat(root.dimValue()).isNull();
        assertThat(root.chartStatus()).isEqualTo("READY");
        assertThat(root.insightStatus()).isEqualTo("PENDING");

        ChartAnalysisView drill = new ChartAnalysisView("7", "研发一部", null, null);
        assertThat(drill.viewKey()).startsWith("dim=org|value=");
        assertThat(drill.viewKey()).contains("value=");
        assertThat(drill.chartStatus()).isEqualTo("NEED_GENERATE");
    }

    @Test
    void insightView_compatConstructors_defaultEmptyRichFields() {
        InsightView legacy = new InsightView("r", "m", "s", List.of());
        assertThat(legacy.status()).isNull();
        assertThat(legacy.limitations()).isEmpty();
        assertThat(legacy.actions()).isEmpty();

        InsightView rich = new InsightView("r", "m", "s", List.of(),
                "degraded", "no_model_draft", "7", "root");
        assertThat(rich.status()).isEqualTo("degraded");
        assertThat(rich.viewKey()).isEqualTo("root");
        assertThat(rich.actions()).isEmpty();
    }
}
