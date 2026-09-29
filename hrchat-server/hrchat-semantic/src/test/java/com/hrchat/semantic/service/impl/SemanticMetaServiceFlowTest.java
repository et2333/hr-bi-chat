package com.hrchat.semantic.service.impl;

import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.knowledge.service.SemanticIndexService;
import com.hrchat.semantic.dto.DimensionUpsertRequest;
import com.hrchat.semantic.dto.DimensionValueItem;
import com.hrchat.semantic.dto.MetricVersionInfo;
import com.hrchat.semantic.dto.SynonymCreateRequest;
import com.hrchat.semantic.entity.BizDimValue;
import com.hrchat.semantic.entity.BizDimension;
import com.hrchat.semantic.entity.BizMetric;
import com.hrchat.semantic.entity.BizMetricDim;
import com.hrchat.semantic.entity.BizMetricVersion;
import com.hrchat.semantic.entity.BizSynonym;
import com.hrchat.semantic.mapper.BizDimValueMapper;
import com.hrchat.semantic.mapper.BizDimensionMapper;
import com.hrchat.semantic.mapper.BizMetricDimMapper;
import com.hrchat.semantic.mapper.BizMetricMapper;
import com.hrchat.semantic.mapper.BizMetricVersionMapper;
import com.hrchat.semantic.mapper.BizSynonymMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义层服务补测（S8c）：列表分页/维度/同义词/目标解析等既有用例未覆盖分支。
 */
class SemanticMetaServiceFlowTest {

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
    }

    private BizMetric metric(long id, String code, String name) {
        BizMetric m = new BizMetric();
        m.setId(id);
        m.setMetricCode(code);
        m.setMetricName(name);
        m.setDomain("staff");
        m.setFormulaExpr("F()");
        m.setCalcScope("口径");
        m.setDefaultPeriod("MONTH");
        m.setGoodDirection(1);
        m.setStatus(1);
        m.setPermLevel(1);
        m.setUpdatedBy("u1");
        m.setUpdatedAt(LocalDateTime.now());
        return m;
    }

    private BizMetricVersion version(long id, int no, int status) {
        BizMetricVersion v = new BizMetricVersion();
        v.setId(id);
        v.setMetricId(1L);
        v.setVersionNo(no);
        v.setFormulaExpr("F()");
        v.setCalcScope("口径");
        v.setStatus(status);
        v.setSubmittedBy("dat01");
        return v;
    }

    private BizDimension dim(long id, String code, String name) {
        BizDimension d = new BizDimension();
        d.setId(id);
        d.setDimCode(code);
        d.setDimName(name);
        d.setDimType(2);
        d.setRefTable("sec_org");
        return d;
    }

    private BizDimValue dimValue(long id, String code, String label) {
        BizDimValue v = new BizDimValue();
        v.setId(id);
        v.setDimId(2L);
        v.setValueCode(code);
        v.setValueLabel(label);
        v.setSortNo(1);
        return v;
    }

    // ---------------- 指标列表/详情/版本 ----------------

    @Test
    void listMetrics_appliesFiltersAndPaginates() {
        when(metricMapper.selectList(any())).thenReturn(List.of(metric(1L, "a", "A"), metric(2L, "b", "B"), metric(3L, "c", "C")));
        var page = service.listMetrics("STAFF", 1, "a", 1, 2);
        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords().get(0).code()).isEqualTo("a");
    }

    @Test
    void listMetrics_rejectsInvalidPageSize() {
        assertThatThrownBy(() -> service.listMetrics(null, null, null, 1, 0))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAGE_SIZE_EXCEED);
    }

    @Test
    void getMetricByCode_returnsDetailWithDimensionCodes() {
        when(metricMapper.selectOne(any())).thenReturn(metric(1L, "turnover", "离职率"));
        when(versionMapper.selectList(any())).thenReturn(List.of(version(11L, 2, SemanticMetaServiceImpl.VERSION_EFFECTIVE)));
        BizMetricDim link = new BizMetricDim();
        link.setMetricId(1L);
        link.setDimId(2L);
        when(metricDimMapper.selectList(any())).thenReturn(List.of(link));
        when(dimensionMapper.selectById(2L)).thenReturn(dim(2L, "org", "组织"));

        var detail = service.getMetricByCode("turnover");
        assertThat(detail.code()).isEqualTo("turnover");
        assertThat(detail.effectiveVersion()).isEqualTo(2);
        assertThat(detail.availableDimensions()).containsExactly("org");
    }

    @Test
    void getMetricByCode_missing_throwsParamInvalid() {
        when(metricMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.getMetricByCode("nope")).isInstanceOf(BizException.class);
    }

    @Test
    void listVersions_returnsVersionInfos() {
        when(metricMapper.selectById(1L)).thenReturn(metric(1L, "a", "A"));
        when(versionMapper.selectList(any()))
                .thenReturn(List.of(version(11L, 1, SemanticMetaServiceImpl.VERSION_HISTORICAL),
                        version(12L, 2, SemanticMetaServiceImpl.VERSION_EFFECTIVE)));

        List<MetricVersionInfo> infos = service.listVersions(1L);
        assertThat(infos).hasSize(2);
        assertThat(infos.stream().map(MetricVersionInfo::versionNo)).containsExactlyInAnyOrder(1, 2);
    }

    // ---------------- 维度 ----------------

    @Test
    void listDimensions_paginates() {
        when(dimensionMapper.selectList(any())).thenReturn(List.of(dim(1L, "org", "组织"), dim(2L, "job", "岗位")));
        var page = service.listDimensions("org", 1, 10);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords().get(0).code()).isEqualTo("org");
    }

    @Test
    void createDimension_ok_insertsEnumValues() {
        when(dimensionMapper.selectCount(any())).thenReturn(0L);
        when(dimValueMapper.selectList(any())).thenReturn(List.of());
        doAnswer(inv -> {
            ((BizDimension) inv.getArgument(0)).setId(9L);
            return 1;
        }).when(dimensionMapper).insert(any(BizDimension.class));

        Long id = service.createDimension(new DimensionUpsertRequest("组织", "org", 2, "sec_org",
                "org_key", "org_name", null, null, null,
                List.of(new DimensionValueItem("1001", "研发中心", null, 1))), "dat01");
        assertThat(id).isEqualTo(9L);
        verify(dimValueMapper).insert(any(BizDimValue.class));
    }

    @Test
    void createDimension_duplicateCode_throws() {
        when(dimensionMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.createDimension(
                new DimensionUpsertRequest("组织", "org", 2, null, null, null, null, null, null, null), "dat01"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void patchDimension_updatesAndReplacesValues() {
        when(dimensionMapper.selectById(2L)).thenReturn(dim(2L, "org", "组织"));
        when(dimValueMapper.selectList(any()))
                .thenReturn(List.of(dimValue(31L, "1001", "研发中心"), dimValue(32L, "1002", "销售部")));

        service.patchDimension(2L, new DimensionUpsertRequest("组织架构", "org", 1, "sec_org",
                "org_key", "org_name", "parent_org_key", null, null,
                List.of(new DimensionValueItem("1001", "研发中心-更新", null, 2),
                        new DimensionValueItem("1003", "职能部", null, 3))), "dat01");

        // 已存在值走 updateById，被移除值走 deleteById
        verify(dimValueMapper).updateById(any(BizDimValue.class));
        verify(dimValueMapper).deleteById(32L);
    }

    @Test
    void patchDimension_removingReferencedValue_throws() {
        when(dimensionMapper.selectById(2L)).thenReturn(dim(2L, "org", "组织"));
        when(dimValueMapper.selectList(any())).thenReturn(List.of(dimValue(31L, "1001", "研发中心")));
        when(metricDimMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.patchDimension(2L, new DimensionUpsertRequest(null, "org", null, null,
                null, null, null, null, null,
                List.of(new DimensionValueItem("2001", "其他", null, 1))), "dat01"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void getDimension_returnsDetail() {
        when(dimensionMapper.selectById(2L)).thenReturn(dim(2L, "org", "组织"));
        when(dimValueMapper.selectList(any())).thenReturn(List.of(dimValue(31L, "1001", "研发中心")));

        var detail = service.getDimension(2L);
        assertThat(detail.code()).isEqualTo("org");
        assertThat(detail.values()).hasSize(1);
        assertThat(detail.values().get(0).valueLabel()).isEqualTo("研发中心");
    }

    // ---------------- 同义词 ----------------

    @Test
    void listSynonyms_paginates() {
        BizSynonym s1 = new BizSynonym();
        s1.setId(1L);
        s1.setTermGroup("研发中心");
        s1.setTargetType(1);
        s1.setTargetId(5L);
        s1.setHitCount(3L);
        BizSynonym s2 = new BizSynonym();
        s2.setId(2L);
        s2.setTermGroup("研发部");
        s2.setHitCount(1L);
        when(synonymMapper.selectList(any())).thenReturn(List.of(s1, s2));

        var page = service.listSynonyms("研发", 1, 10);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords().get(0).termGroup()).isEqualTo("研发中心");
    }

    @Test
    void createSynonym_metricTarget_insertsEachTerm() {
        BizMetric m = new BizMetric();
        m.setId(5L);
        when(metricMapper.selectOne(any())).thenReturn(m);
        doAnswer(inv -> {
            BizSynonym s = inv.getArgument(0);
            s.setId(s.getTermGroup().startsWith("研") ? 100L : 101L);
            return 1;
        }).when(synonymMapper).insert(any(BizSynonym.class));

        Long firstId = service.createSynonym(new SynonymCreateRequest("研发", List.of("研发中心", "研发部"), "metric:turnover_rate"), "dat01");
        assertThat(firstId).isEqualTo(100L);
        verify(synonymMapper, Mockito.times(2)).insert(any(BizSynonym.class));
    }

    @Test
    void createSynonym_dimensionTarget_mapsTypeTwo() {
        BizDimension d = new BizDimension();
        d.setId(7L);
        when(dimensionMapper.selectOne(any())).thenReturn(d);
        doAnswer(inv -> {
            ((BizSynonym) inv.getArgument(0)).setId(9L);
            return 1;
        }).when(synonymMapper).insert(any(BizSynonym.class));

        Long firstId = service.createSynonym(new SynonymCreateRequest("岗位", List.of("岗位"), "dimension:job_level"), "dat01");
        assertThat(firstId).isEqualTo(9L);
    }

    @Test
    void createSynonym_unknownTargetType_throws() {
        assertThatThrownBy(() -> service.createSynonym(new SynonymCreateRequest("x", List.of("x"), "org:1"), "dat01"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void createSynonym_malformedTarget_throws() {
        assertThatThrownBy(() -> service.createSynonym(new SynonymCreateRequest("x", List.of("x"), "nocolon"), "dat01"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void createSynonym_emptyTerms_throws() {
        assertThatThrownBy(() -> service.createSynonym(new SynonymCreateRequest("x", List.of(), "metric:turnover_rate"), "dat01"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void deleteSynonym_removesById() {
        service.deleteSynonym(3L);
        verify(synonymMapper).deleteById(3L);
    }

    // ---------------- P0：删除/启停/待办 ----------------

    @Test
    void deleteMetric_softDeletesAndClearsSynonyms() {
        when(metricMapper.selectById(1L)).thenReturn(metric(1L, "headcount", "在职人数"));

        service.deleteMetric(1L, "adm01");

        // 同事务清理指向该指标的同义词
        verify(synonymMapper).delete(any());
        // @TableLogic 软删
        verify(metricMapper).deleteById(1L);
    }

    @Test
    void deleteMetric_missing_throws() {
        when(metricMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.deleteMetric(9L, "adm01")).isInstanceOf(BizException.class);
    }

    @Test
    void setMetricStatus_valid_updates() {
        when(metricMapper.selectById(1L)).thenReturn(metric(1L, "headcount", "在职人数"));

        service.setMetricStatus(1L, 0, "adm01");

        verify(metricMapper).updateById(any(BizMetric.class));
    }

    @Test
    void setMetricStatus_invalid_throws() {
        assertThatThrownBy(() -> service.setMetricStatus(1L, 2, "adm01"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.setMetricStatus(1L, null, "adm01"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void deleteDimension_noReferences_softDeletes() {
        when(dimensionMapper.selectById(2L)).thenReturn(dim(2L, "job_level", "职级"));
        when(metricDimMapper.selectCount(any())).thenReturn(0L);
        when(synonymMapper.selectCount(any())).thenReturn(0L);

        service.deleteDimension(2L, "adm01");

        verify(dimensionMapper).deleteById(2L);
    }

    @Test
    void deleteDimension_referencedByMetric_throws() {
        when(dimensionMapper.selectById(2L)).thenReturn(dim(2L, "job_level", "职级"));
        when(metricDimMapper.selectCount(any())).thenReturn(2L);
        when(synonymMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.deleteDimension(2L, "adm01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("指标可用维度 2 处");
    }

    @Test
    void deleteDimension_referencedBySynonym_throws() {
        when(dimensionMapper.selectById(2L)).thenReturn(dim(2L, "job_level", "职级"));
        when(metricDimMapper.selectCount(any())).thenReturn(0L);
        when(synonymMapper.selectCount(any())).thenReturn(5L);

        assertThatThrownBy(() -> service.deleteDimension(2L, "adm01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("同义词 5 条");
    }

    @Test
    void submitApproval_firstTime_setsSubmittedAt() {
        BizMetricVersion pending = version(11L, 2, SemanticMetaServiceImpl.VERSION_PENDING);
        when(metricMapper.selectById(1L)).thenReturn(metric(1L, "headcount", "在职人数"));
        when(versionMapper.selectOne(any())).thenReturn(pending);

        Long approvalId = service.submitApproval(1L, "dat01");

        assertThat(approvalId).isEqualTo(11L);
        assertThat(pending.getSubmittedAt()).isNotNull();
        verify(versionMapper).updateById(pending);
    }

    @Test
    void submitApproval_repeat_doesNotOverwriteSubmittedAt() {
        BizMetricVersion pending = version(11L, 2, SemanticMetaServiceImpl.VERSION_PENDING);
        LocalDateTime first = LocalDateTime.now().minusHours(1);
        pending.setSubmittedAt(first);
        when(metricMapper.selectById(1L)).thenReturn(metric(1L, "headcount", "在职人数"));
        when(versionMapper.selectOne(any())).thenReturn(pending);

        service.submitApproval(1L, "dat01");

        assertThat(pending.getSubmittedAt()).isEqualTo(first);
        verify(versionMapper, Mockito.never()).updateById(any());
    }

    @Test
    void submitApproval_noPending_throws() {
        when(metricMapper.selectById(1L)).thenReturn(metric(1L, "headcount", "在职人数"));
        when(versionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.submitApproval(1L, "dat01")).isInstanceOf(BizException.class);
    }

    @Test
    void listApprovalTodos_returnsSubmittedOnly() {
        BizMetricVersion pending = version(11L, 2, SemanticMetaServiceImpl.VERSION_PENDING);
        pending.setSubmittedAt(LocalDateTime.now());
        when(versionMapper.selectList(any())).thenReturn(List.of(pending));
        when(metricMapper.selectList(any())).thenReturn(List.of(metric(1L, "headcount", "在职人数")));

        var todos = service.listApprovalTodos();

        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).approvalId()).isEqualTo(11L);
        assertThat(todos.get(0).metricCode()).isEqualTo("headcount");
        assertThat(todos.get(0).calcScope()).isEqualTo("口径");
    }

    @Test
    void listApprovalTodos_empty_returnsEmpty() {
        when(versionMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listApprovalTodos()).isEmpty();
    }

    @Test
    void listApprovalTodos_skipsOrphanWhenMetricMissing() {
        BizMetricVersion pending = version(11L, 2, SemanticMetaServiceImpl.VERSION_PENDING);
        pending.setSubmittedAt(LocalDateTime.now());
        when(versionMapper.selectList(any())).thenReturn(List.of(pending));
        when(metricMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listApprovalTodos()).isEmpty();
    }
}
