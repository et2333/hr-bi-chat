package com.hrchat.semantic.service.impl;

import com.hrchat.common.exception.BizException;
import com.hrchat.semantic.dto.ApproveResult;
import com.hrchat.semantic.dto.MetricCreateRequest;
import com.hrchat.semantic.dto.MetricPatchRequest;
import com.hrchat.semantic.dto.SynonymCreateRequest;
import com.hrchat.semantic.entity.BizDimValue;
import com.hrchat.semantic.entity.BizDimension;
import com.hrchat.semantic.entity.BizMetric;
import com.hrchat.semantic.entity.BizMetricVersion;
import com.hrchat.semantic.mapper.BizDimValueMapper;
import com.hrchat.semantic.mapper.BizDimensionMapper;
import com.hrchat.semantic.mapper.BizMetricDimMapper;
import com.hrchat.semantic.mapper.BizMetricMapper;
import com.hrchat.semantic.mapper.BizMetricVersionMapper;
import com.hrchat.semantic.mapper.BizSynonymMapper;
import com.hrchat.semantic.service.MetricPublishedEvent;
import com.hrchat.knowledge.service.SemanticIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义层服务单测：指标创建/口径变更审批流/版本发布/维度枚举引用保护/同义词归一化。
 */
class SemanticMetaServiceImplTest {

    private final BizMetricMapper metricMapper = Mockito.mock(BizMetricMapper.class);
    private final BizMetricVersionMapper versionMapper = Mockito.mock(BizMetricVersionMapper.class);
    private final BizDimensionMapper dimensionMapper = Mockito.mock(BizDimensionMapper.class);
    private final BizDimValueMapper dimValueMapper = Mockito.mock(BizDimValueMapper.class);
    private final BizMetricDimMapper metricDimMapper = Mockito.mock(BizMetricDimMapper.class);
    private final BizSynonymMapper synonymMapper = Mockito.mock(BizSynonymMapper.class);
    private final SemanticIndexService indexService = Mockito.mock(SemanticIndexService.class);
    private final ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);

    private SemanticMetaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SemanticMetaServiceImpl(metricMapper, versionMapper, dimensionMapper,
                dimValueMapper, metricDimMapper, synonymMapper, indexService, eventPublisher);
        // 通用桩：指标-维度关联默认无记录
        when(metricDimMapper.selectList(any())).thenReturn(List.of());
    }

    // ---------------- 指标创建 ----------------

    @Test
    void createMetricCreatesEffectiveVersionAndIndexes() {
        when(metricMapper.selectCount(any())).thenReturn(0L);
        when(dimensionMapper.selectList(any())).thenReturn(List.of(
                dim(2L, "org"), dim(3L, "job_level")));
        doAnswer(inv -> {
            ((BizMetric) inv.getArgument(0)).setId(100L);
            return 1;
        }).when(metricMapper).insert(any(BizMetric.class));

        Long id = service.createMetric(new MetricCreateRequest("主动离职率", "voluntary_turnover_rate",
                "STAFF", "期间主动离职人数÷(期初+期末)/2×100%",
                "SELECT COUNT(*)/AVG(headcount)*100 FROM fact_emp_change WHERE leave_type='ACTIVE'",
                "MONTH", List.of("org", "job_level"), true), "dat01");

        assertThat(id).isEqualTo(100L);
        // 初始版本直接生效
        ArgumentCaptor<BizMetricVersion> vCaptor = ArgumentCaptor.forClass(BizMetricVersion.class);
        verify(versionMapper).insert(vCaptor.capture());
        assertThat(vCaptor.getValue().getVersionNo()).isEqualTo(1);
        assertThat(vCaptor.getValue().getStatus()).isEqualTo(SemanticMetaServiceImpl.VERSION_EFFECTIVE);
        // 域归一化小写 + 密级敏感
        ArgumentCaptor<BizMetric> mCaptor = ArgumentCaptor.forClass(BizMetric.class);
        verify(metricMapper).insert(mCaptor.capture());
        assertThat(mCaptor.getValue().getDomain()).isEqualTo("staff");
        assertThat(mCaptor.getValue().getPermLevel()).isEqualTo(SemanticMetaServiceImpl.PERM_SENSITIVE);
        // 双写向量库
        verify(indexService).indexMetric(anyString(), anyString(), anyString(), anyList(), anyString(), anyInt(), anyInt());
    }

    @Test
    void createMetricRejectsDuplicateCode() {
        when(metricMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.createMetric(new MetricCreateRequest("重复", "headcount",
                "STAFF", "d", "f", "MONTH", List.of(), false), "dat01"))
                .isInstanceOf(BizException.class);
    }

    // ---------------- 口径变更审批流 ----------------

    @Test
    void patchMetricFormulaChangeCreatesPendingVersionOnly() {
        BizMetric metric = metric(1L, "headcount", "staff", "SELECT 1", "旧口径");
        when(metricMapper.selectById(1L)).thenReturn(metric);
        when(versionMapper.selectList(any())).thenReturn(List.of(effectiveV(1L, 1, "SELECT 1", "旧口径")));

        service.patchMetric(1L, new MetricPatchRequest("新在职人数", "新口径（不含试用期）",
                "SELECT 2", null, null, null, null), "dat01");

        ArgumentCaptor<BizMetricVersion> vCaptor = ArgumentCaptor.forClass(BizMetricVersion.class);
        verify(versionMapper).insert(vCaptor.capture());
        assertThat(vCaptor.getValue().getVersionNo()).isEqualTo(2);
        assertThat(vCaptor.getValue().getStatus()).isEqualTo(SemanticMetaServiceImpl.VERSION_PENDING);
        // 线上口径不变（仍旧）
        assertThat(metric.getFormulaExpr()).isEqualTo("SELECT 1");
        verify(metricMapper).updateById(any(BizMetric.class));
        verify(indexService, never()).indexMetric(anyString(), anyString(), anyString(), anyList(), anyString(), anyInt(), anyInt());
    }

    @Test
    void patchMetricNameChangeTakesEffectImmediately() {
        BizMetric metric = metric(1L, "headcount", "staff", "SELECT 1", "旧口径");
        when(metricMapper.selectById(1L)).thenReturn(metric);
        when(versionMapper.selectList(any())).thenReturn(List.of(effectiveV(1L, 1, "SELECT 1", "旧口径")));

        service.patchMetric(1L, new MetricPatchRequest("新名字", null, null, "QUARTER", null, null, null), "dat01");

        // 未触发审批流
        verify(versionMapper, never()).insert(any());
        assertThat(metric.getMetricName()).isEqualTo("新名字");
        assertThat(metric.getDefaultPeriod()).isEqualTo("QUARTER");
    }

    @Test
    void submitApprovalWithoutPendingVersionThrows() {
        when(metricMapper.selectById(1L)).thenReturn(metric(1L, "headcount", "staff", "SELECT 1", "旧口径"));
        when(versionMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.submitApproval(1L, "dat01"))
                .isInstanceOf(BizException.class);
    }

    // ---------------- 审批通过：旧版本置历史、新版本生效、双写与广播 ----------------

    @Test
    void approvePublishesNewVersionAndArchivesOld() {
        BizMetric metric = metric(1L, "headcount", "staff", "SELECT 1", "旧口径");
        when(metricMapper.selectById(1L)).thenReturn(metric);
        BizMetricVersion pending = pendingV(99L, 1L, 2, "SELECT 2", "新口径");
        when(versionMapper.selectById(99L)).thenReturn(pending);
        when(versionMapper.selectList(any())).thenReturn(List.of(effectiveV(1L, 1, "SELECT 1", "旧口径")));

        ApproveResult result = service.approve(99L, "adm01");

        // 旧生效版本 → 历史、新版本 → 生效（两次 updateById：先旧后新）
        ArgumentCaptor<BizMetricVersion> vCaptor = ArgumentCaptor.forClass(BizMetricVersion.class);
        verify(versionMapper, times(2)).updateById(vCaptor.capture());
        assertThat(vCaptor.getAllValues().get(0).getStatus()).isEqualTo(SemanticMetaServiceImpl.VERSION_HISTORICAL);
        assertThat(vCaptor.getAllValues().get(1).getStatus()).isEqualTo(SemanticMetaServiceImpl.VERSION_EFFECTIVE);
        // 新版本 → 生效
        assertThat(pending.getStatus()).isEqualTo(SemanticMetaServiceImpl.VERSION_EFFECTIVE);
        assertThat(pending.getApprovedBy()).isEqualTo("adm01");
        assertThat(pending.getEffectiveAt()).isNotNull();
        // 指标口径回写
        assertThat(metric.getFormulaExpr()).isEqualTo("SELECT 2");
        assertThat(metric.getCalcScope()).isEqualTo("新口径");
        // 双写 + 广播事件
        verify(indexService).indexMetric(anyString(), anyString(), anyString(), anyList(), anyString(), anyInt(), anyInt());
        verify(eventPublisher).publishEvent(any(MetricPublishedEvent.class));
        assertThat(result.publishedVersion()).isEqualTo(2);
        assertThat(result.eventName()).isEqualTo("hrchat.semantic.version-published");
    }

    @Test
    void approveNonPendingVersionRejected() {
        BizMetricVersion effective = effectiveV(1L, 1, "SELECT 1", "旧口径");
        effective.setId(99L);
        effective.setStatus(SemanticMetaServiceImpl.VERSION_EFFECTIVE);
        when(versionMapper.selectById(99L)).thenReturn(effective);
        assertThatThrownBy(() -> service.approve(99L, "adm01"))
                .isInstanceOf(BizException.class);
        verify(versionMapper, never()).updateById(any());
        verify(indexService, never()).indexMetric(anyString(), anyString(), anyString(), anyList(), anyString(), anyInt(), anyInt());
    }

    // ---------------- 审批驳回 ----------------

    @Test
    void rejectSetsRejectedStatus() {
        BizMetricVersion pending = pendingV(99L, 1L, 2, "SELECT 2", "新口径");
        when(versionMapper.selectById(99L)).thenReturn(pending);
        service.reject(99L, "adm01", "口径描述有误，请修正");
        assertThat(pending.getStatus()).isEqualTo(SemanticMetaServiceImpl.VERSION_REJECTED);
        assertThat(pending.getChangeNote()).contains("口径描述有误");
        verify(indexService, never()).indexMetric(anyString(), anyString(), anyString(), anyList(), anyString(), anyInt(), anyInt());
    }

    // ---------------- 同义词 ----------------

    @Test
    void createSynonymResolvesMetricTarget() {
        BizMetric metric = metric(1L, "turnover_rate", "staff", "SELECT 1", "口径");
        when(metricMapper.selectOne(any())).thenReturn(metric);
        service.createSynonym(new SynonymCreateRequest("离职率", List.of("流失率", "turnover rate"),
                "metric:turnover_rate"), "dat01");
        ArgumentCaptor<com.hrchat.semantic.entity.BizSynonym> captor =
                ArgumentCaptor.forClass(com.hrchat.semantic.entity.BizSynonym.class);
        verify(synonymMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(s -> s.getTargetType() == 1 && s.getTargetId() == 1L);
    }

    @Test
    void createSynonymRejectsUnknownTarget() {
        when(metricMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.createSynonym(new SynonymCreateRequest("离职率",
                List.of("流失率"), "metric:turnover_rate"), "dat01"))
                .isInstanceOf(BizException.class);
    }

    // ---------------- 维度枚举引用保护 ----------------

    @Test
    void patchDimensionRemoveEnumRejectedWhenReferenced() {
        BizDimension dim = new BizDimension();
        dim.setId(3L);
        dim.setDimCode("job_level");
        dim.setDimName("职级");
        when(dimensionMapper.selectById(3L)).thenReturn(dim);
        BizDimValue existing = new BizDimValue();
        existing.setId(31L);
        existing.setDimId(3L);
        existing.setValueCode("P6");
        existing.setValueLabel("P6");
        when(dimValueMapper.selectList(any())).thenReturn(List.of(existing));
        when(metricDimMapper.selectCount(any())).thenReturn(1L);

        com.hrchat.semantic.dto.DimensionUpsertRequest req =
                new com.hrchat.semantic.dto.DimensionUpsertRequest(
                        "职级", "job_level", 2, null, null, null, null, null, null, List.of());
        assertThatThrownBy(() -> service.patchDimension(3L, req, "dat01"))
                .isInstanceOf(BizException.class);
    }

    private static BizMetric metric(Long id, String code, String domain, String formula, String scope) {
        BizMetric m = new BizMetric();
        m.setId(id);
        m.setMetricCode(code);
        m.setMetricName(code);
        m.setDomain(domain);
        m.setFormulaExpr(formula);
        m.setCalcScope(scope);
        m.setDefaultPeriod("MONTH");
        m.setGoodDirection(1);
        m.setPermLevel(1);
        m.setStatus(1);
        m.setUpdatedAt(LocalDateTime.now());
        return m;
    }

    private static BizDimension dim(Long id, String code) {
        BizDimension d = new BizDimension();
        d.setId(id);
        d.setDimCode(code);
        d.setDimName(code);
        d.setDimType(2);
        return d;
    }

    private static BizMetricVersion effectiveV(Long metricId, int versionNo, String formula, String scope) {
        BizMetricVersion v = new BizMetricVersion();
        v.setId(metricId * 10 + versionNo);
        v.setMetricId(metricId);
        v.setVersionNo(versionNo);
        v.setFormulaExpr(formula);
        v.setCalcScope(scope);
        v.setStatus(SemanticMetaServiceImpl.VERSION_EFFECTIVE);
        v.setSubmittedBy("dat01");
        v.setApprovedBy("adm01");
        v.setEffectiveAt(LocalDateTime.now());
        return v;
    }

    private static BizMetricVersion pendingV(Long id, Long metricId, int versionNo, String formula, String scope) {
        BizMetricVersion v = new BizMetricVersion();
        v.setId(id);
        v.setMetricId(metricId);
        v.setVersionNo(versionNo);
        v.setFormulaExpr(formula);
        v.setCalcScope(scope);
        v.setStatus(SemanticMetaServiceImpl.VERSION_PENDING);
        v.setSubmittedBy("dat01");
        return v;
    }
}
