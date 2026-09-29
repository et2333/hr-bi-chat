package com.hrchat.report.service;

import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptReportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SemanticLineageService 测试：def_json code 提取、精确匹配、已删除报表过滤。
 */
class SemanticLineageServiceTest {

    private final RptComponentMapper componentMapper = Mockito.mock(RptComponentMapper.class);
    private final RptReportMapper reportMapper = Mockito.mock(RptReportMapper.class);

    private SemanticLineageService service;

    @BeforeEach
    void setUp() {
        service = new SemanticLineageService(componentMapper, reportMapper);
        when(reportMapper.selectList(any()))
                .thenReturn(List.of(report(1L, "研发在职月报"), report(2L, "人力看板")));
    }

    private RptComponent component(long id, long reportId, String defJson) {
        RptComponent c = new RptComponent();
        c.setId(id);
        c.setReportId(reportId);
        c.setCompType(1);
        c.setChartType("BAR");
        c.setDefJson(defJson);
        return c;
    }

    private RptReport report(long id, String name) {
        RptReport r = new RptReport();
        r.setId(id);
        r.setReportName(name);
        return r;
    }

    @Test
    void metricLineage_stringMetric_matches() {
        when(componentMapper.selectList(any())).thenReturn(List.of(
                component(10L, 1L, "{\"metric\":\"headcount\",\"dim\":\"org\"}")));

        var hits = service.findMetricLineage("headcount");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).reportName()).isEqualTo("研发在职月报");
        assertThat(hits.get(0).componentId()).isEqualTo(10L);
    }

    @Test
    void metricLineage_objectAndArray_extractsAll() {
        when(componentMapper.selectList(any())).thenReturn(List.of(
                component(10L, 1L, "{\"metric\":{\"code\":\"headcount\"}}"),
                component(11L, 2L, "{\"metrics\":[\"avg_salary\",{\"code\":\"headcount\"}]}")));

        var hits = service.findMetricLineage("HEADCOUNT");

        // 对象形态 + 数组中对象形态各命中一次
        assertThat(hits).hasSize(2);
    }

    @Test
    void metricLineage_substringCode_doesNotMatch() {
        when(componentMapper.selectList(any())).thenReturn(List.of(
                component(10L, 1L, "{\"metric\":\"headcount_yoy\"}")));

        // 精确匹配：headcount 不得匹配 headcount_yoy
        assertThat(service.findMetricLineage("headcount")).isEmpty();
    }

    @Test
    void metricLineage_deletedReport_excluded() {
        // 组件挂在报表 3，但报表 3 不在（已删除）结果中
        when(componentMapper.selectList(any())).thenReturn(List.of(
                component(10L, 3L, "{\"metric\":\"headcount\"}")));

        assertThat(service.findMetricLineage("headcount")).isEmpty();
    }

    @Test
    void dimensionLineage_stringAndArray_matches() {
        when(componentMapper.selectList(any())).thenReturn(List.of(
                component(10L, 1L, "{\"metric\":\"headcount\",\"dim\":\"job_level\"}"),
                component(11L, 2L, "{\"metric\":\"headcount\",\"dimensions\":[\"org\",\"job_level\"]}")));

        var hits = service.findDimensionLineage("job_level");

        assertThat(hits).hasSize(2);
    }

    @Test
    void dimensionLineage_inMetricSlot_doesNotMatch() {
        when(componentMapper.selectList(any())).thenReturn(List.of(
                component(10L, 1L, "{\"metric\":\"job_level\",\"dim\":\"org\"}")));

        // job_level 出现在 metric 位，不应算作维度血缘
        assertThat(service.findDimensionLineage("job_level")).isEmpty();
    }

    @Test
    void lineage_invalidJson_noMatch() {
        when(componentMapper.selectList(any())).thenReturn(List.of(
                component(10L, 1L, "not-a-json")));

        assertThat(service.findMetricLineage("headcount")).isEmpty();
        assertThat(service.findDimensionLineage("org")).isEmpty();
    }
}
