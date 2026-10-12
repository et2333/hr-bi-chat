package com.hrchat.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartSeries;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptDataVersionMapper;
import com.hrchat.report.mapper.RptViewSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportDataVersionServiceTest {

    @Mock
    private ReportService reportService;
    @Mock
    private ReportChartService chartService;
    @Mock
    private RptComponentMapper componentMapper;
    @Mock
    private RptDataVersionMapper dataVersionMapper;
    @Mock
    private RptViewSnapshotMapper viewSnapshotMapper;

    private ReportDataVersionService service;
    private UserContext ctx;

    @BeforeEach
    void setUp() {
        service = new ReportDataVersionService(reportService, chartService, componentMapper,
                dataVersionMapper, viewSnapshotMapper, new ObjectMapper());
        ctx = UserContext.builder().userId(1L).empNo("hr01").tenantId("t01").build();
    }

    @Test
    void ensureCurrent_reusesReadyVersionWithoutRematerializing() {
        RptReport report = report(7L, 88L);
        when(reportService.requireAccessibleReport(ctx, 7L)).thenReturn(report);
        RptDataVersion ready = new RptDataVersion();
        ready.setId(88L);
        ready.setVersionNo(2);
        ready.setStatus(ReportDataVersionService.STATUS_READY);
        ready.setDefinitionRevision(1);
        ready.setScopeRef(ReportDataScope.fingerprint(ctx));
        when(dataVersionMapper.selectById(88L)).thenReturn(ready);

        var view = service.ensureCurrent(ctx, 7L, "k1");

        assertThat(view.dataVersionId()).isEqualTo(88L);
        assertThat(view.published()).isTrue();
        verify(chartService, never()).materializeChart(any(), any(), any());
        verify(dataVersionMapper, never()).insert(any());
    }

    @Test
    void refresh_failureDoesNotOverwriteCurrentSuccessVersion() {
        RptReport report = report(7L, 88L);
        when(reportService.requireAccessibleReport(ctx, 7L)).thenReturn(report);
        when(dataVersionMapper.selectOne(any())).thenReturn(null);
        when(dataVersionMapper.insert(any())).thenAnswer(inv -> {
            RptDataVersion v = inv.getArgument(0);
            v.setId(99L);
            return 1;
        });
        RptComponent chart = new RptComponent();
        chart.setId(10L);
        chart.setCompType(1);
        when(componentMapper.selectList(any())).thenReturn(List.of(chart));
        when(chartService.materializeChart(eq(chart), any(), eq(ctx)))
                .thenThrow(new BizException(ErrorCode.SYSTEM_BUSY, "取数失败"));

        var view = service.refresh(ctx, 7L, "refresh-1");

        assertThat(view.status()).isEqualTo(ReportDataVersionService.STATUS_FAILED);
        assertThat(view.published()).isFalse();
        verify(reportService, never()).updateCurrentDataVersion(eq(7L), eq(99L));
        ArgumentCaptor<RptDataVersion> captor = ArgumentCaptor.forClass(RptDataVersion.class);
        verify(dataVersionMapper, times(1)).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportDataVersionService.STATUS_FAILED);
    }

    @Test
    void ensure_idempotentKeyReturnsSameVersion() {
        RptReport report = report(7L, null);
        when(reportService.requireAccessibleReport(ctx, 7L)).thenReturn(report);
        when(dataVersionMapper.selectOne(any())).thenReturn(null);
        when(dataVersionMapper.insert(any())).thenAnswer(inv -> {
            RptDataVersion v = inv.getArgument(0);
            v.setId(11L);
            return 1;
        });
        RptComponent chart = new RptComponent();
        chart.setId(10L);
        chart.setCompType(1);
        when(componentMapper.selectList(any())).thenReturn(List.of(chart));
        when(chartService.materializeChart(eq(chart), any(), eq(ctx)))
                .thenReturn(new ChartDataView("BAR", List.of("研发"), List.of(new ChartSeries("人数", List.of(1))), List.of()));
        when(viewSnapshotMapper.insert(any())).thenReturn(1);
        when(dataVersionMapper.selectById(11L)).thenAnswer(inv -> {
            RptDataVersion v = new RptDataVersion();
            v.setId(11L);
            v.setVersionNo(1);
            v.setStatus(ReportDataVersionService.STATUS_READY);
            return v;
        });

        var first = service.ensureCurrent(ctx, 7L, "ensure-7");
        // 第二次：持久化幂等命中（不依赖进程内缓存）。
        RptDataVersion persisted = new RptDataVersion();
        persisted.setId(11L);
        persisted.setStatus(ReportDataVersionService.STATUS_READY);
        when(dataVersionMapper.selectOne(any())).thenReturn(persisted);
        RptReport stillNoCurrent = report(7L, null);
        when(reportService.requireAccessibleReport(ctx, 7L)).thenReturn(stillNoCurrent);
        var second = service.ensureCurrent(ctx, 7L, "ensure-7");

        assertThat(first.dataVersionId()).isEqualTo(second.dataVersionId()).isEqualTo(11L);
        verify(dataVersionMapper, times(1)).insert(any());
    }

    /**
     * 版本 scopeRef 必须先按冻结来源上下文收窄再取指纹：与读取路径 requireScope/readSnapshot 同口径，
     * 否则同一用户生成时按全量范围、读取时按收窄范围计算，指纹恒不相等 → 组件端点恒 403。
     */
    @Test
    void generatedScopeRefMustMatchNarrowedScopeUsedByReadPath() {
        UserContext wide = UserContext.builder().userId(1L).empNo("hr04").tenantId("t01")
                .dataLevel(1).grantedOrgs(List.of(grant(2L), grant(9L))).build();
        RptReport report = report(7L, null);
        report.setContextJson("{\"effectiveOrgIds\":[2]}");
        when(reportService.requireAccessibleReport(wide, 7L)).thenReturn(report);
        when(dataVersionMapper.insert(any())).thenAnswer(inv -> {
            RptDataVersion v = inv.getArgument(0);
            v.setId(11L);
            return 1;
        });
        when(componentMapper.selectList(any())).thenReturn(List.of());

        service.ensureCurrent(wide, 7L, "ensure-7");

        ArgumentCaptor<RptDataVersion> captor = ArgumentCaptor.forClass(RptDataVersion.class);
        verify(dataVersionMapper).insert(captor.capture());
        RptDataVersion created = captor.getValue();
        UserContext narrowed = ReportDataScope.narrow(wide, Map.of("effectiveOrgIds", List.of(2)));
        assertThat(created.getScopeRef()).isEqualTo(ReportDataScope.fingerprint(narrowed));
        assertThat(created.getScopeRef()).isNotEqualTo(ReportDataScope.fingerprint(wide));
        // 读取路径同口径：收窄后指纹一致，不再抛 403。
        service.requireScope(wide, report, created);
    }

    private static UserContext.GrantedOrg grant(long orgKey) {
        return UserContext.GrantedOrg.builder().orgNodeId(orgKey).orgCode("org" + orgKey)
                .orgName("org" + orgKey).orgPath("/1/" + orgKey + "/").scope(1)
                .subtreeOrgKeys(List.of(orgKey)).build();
    }

    private static RptReport report(Long id, Long currentVersionId) {
        RptReport r = new RptReport();
        r.setId(id);
        r.setOwnerId(1L);
        r.setDefinitionRevision(1);
        r.setCurrentDataVersionId(currentVersionId);
        r.setSnapshotReuse(0);
        return r;
    }
}
