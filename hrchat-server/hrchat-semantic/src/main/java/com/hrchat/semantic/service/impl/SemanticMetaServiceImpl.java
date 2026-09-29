package com.hrchat.semantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.knowledge.service.SemanticIndexService;
import com.hrchat.semantic.dto.ApprovalTodoItem;
import com.hrchat.semantic.dto.ApproveResult;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.DimensionSummary;
import com.hrchat.semantic.dto.DimensionUpsertRequest;
import com.hrchat.semantic.dto.DimensionValueItem;
import com.hrchat.semantic.dto.MetricCreateRequest;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.dto.MetricPatchRequest;
import com.hrchat.semantic.dto.MetricSummary;
import com.hrchat.semantic.dto.MetricVersionInfo;
import com.hrchat.semantic.dto.SynonymCreateRequest;
import com.hrchat.semantic.dto.SynonymItem;
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
import com.hrchat.semantic.service.MetricPublishedEvent;
import com.hrchat.semantic.service.SemanticMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 语义层元数据服务实现（BR-04 口径唯一 / FR-21 版本发布 / FR-22 同义词归一化）。
 *
 * <p>审批通过采用事务性状态迁移：旧生效版本→3 历史、新版本→1 生效，同时回写指标
 * formula/口径，随后向量库双写并广播 {@link MetricPublishedEvent}。</p>
 */
@Service
@RequiredArgsConstructor
public class SemanticMetaServiceImpl implements SemanticMetaService {

    /** 发布事件名（接口文档 hrchat.semantic.version-published） */
    public static final String EVENT_VERSION_PUBLISHED = "hrchat.semantic.version-published";

    private final BizMetricMapper metricMapper;
    private final BizMetricVersionMapper versionMapper;
    private final BizDimensionMapper dimensionMapper;
    private final BizDimValueMapper dimValueMapper;
    private final BizMetricDimMapper metricDimMapper;
    private final BizSynonymMapper synonymMapper;
    private final SemanticIndexService semanticIndexService;
    private final ApplicationEventPublisher eventPublisher;

    // ---------------- 指标 ----------------

    @Override
    public PageResult<MetricSummary> listMetrics(String domain, Integer status, String keyword, int page, int size) {
        validatePage(size);
        LambdaQueryWrapper<BizMetric> wrapper = new LambdaQueryWrapper<BizMetric>()
                .eq(domain != null && !domain.isBlank(), BizMetric::getDomain, normalizeDomain(domain))
                .eq(status != null, BizMetric::getStatus, status)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(BizMetric::getMetricName, keyword)
                                .or().like(BizMetric::getMetricCode, keyword))
                .orderByDesc(BizMetric::getId);
        List<BizMetric> metrics = metricMapper.selectList(wrapper);
        // 内存分页（管理后台数据量小，避免引入分页插件依赖到本模块）
        long total = metrics.size();
        int from = Math.min((int) ((long) (page - 1) * size), metrics.size());
        int to = Math.min(from + size, metrics.size());
        List<MetricSummary> records = metrics.subList(from, to).stream()
                .map(m -> toSummary(m, versionState(m.getId())))
                .toList();
        return PageResult.of(records, total, page, size);
    }

    @Override
    public MetricDetail getMetric(Long metricId) {
        BizMetric metric = requireMetric(metricId);
        return toDetail(metric, versionsOf(metricId));
    }

    @Override
    public MetricDetail getMetricByCode(String metricCode) {
        BizMetric metric = metricMapper.selectOne(new LambdaQueryWrapper<BizMetric>()
                .eq(BizMetric::getMetricCode, metricCode)
                .eq(BizMetric::getStatus, 1));
        if (metric == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标不存在或已停用：" + metricCode);
        }
        return toDetail(metric, versionsOf(metric.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createMetric(MetricCreateRequest request, String operator) {
        checkParam(request.code(), "指标编码");
        checkParam(request.name(), "指标名称");
        checkParam(request.formula(), "计算公式");
        checkParam(request.definition(), "口径说明");
        if (metricMapper.selectCount(new LambdaQueryWrapper<BizMetric>()
                .eq(BizMetric::getMetricCode, request.code())) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标编码 " + request.code() + " 已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        BizMetric metric = new BizMetric();
        metric.setMetricCode(request.code());
        metric.setMetricName(request.name());
        metric.setDomain(normalizeDomain(request.domain()));
        metric.setFormulaExpr(request.formula());
        metric.setCalcScope(request.definition());
        metric.setDefaultPeriod(request.defaultGrain() == null ? "MONTH" : request.defaultGrain());
        metric.setGoodDirection(1);
        metric.setPermLevel(request.sensitive() ? PERM_SENSITIVE : PERM_PUBLIC);
        metric.setStatus(1);
        metric.setCreatedBy(operator);
        metric.setUpdatedBy(operator);
        metricMapper.insert(metric);

        // 初始版本直接生效（保存即生效，无需审批）
        insertVersion(metric.getId(), 1, request.formula(), request.definition(), VERSION_EFFECTIVE,
                operator, operator, now, "初始发布");
        bindDimensions(metric.getId(), request.availableDimensions());
        indexMetric(metric);
        return metric.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MetricDetail patchMetric(Long metricId, MetricPatchRequest request, String operator) {
        BizMetric metric = requireMetric(metricId);
        boolean formulaChanged = request.formula() != null && !request.formula().equals(metric.getFormulaExpr());
        boolean definitionChanged = request.definition() != null && !request.definition().equals(metric.getCalcScope());

        if (formulaChanged || definitionChanged) {
            // BR-04：口径变更自动触发审批流，审批通过前线上仍用旧口径
            int nextVersion = maxVersionNo(metricId) + 1;
            insertVersion(metricId, nextVersion,
                    request.formula() != null ? request.formula() : metric.getFormulaExpr(),
                    request.definition() != null ? request.definition() : metric.getCalcScope(),
                    VERSION_PENDING, operator, null, null,
                    request.formula() != null ? "口径/公式变更提交审批" : "口径变更提交审批");
        }
        if (request.name() != null) {
            metric.setMetricName(request.name());
        }
        if (request.defaultGrain() != null) {
            metric.setDefaultPeriod(request.defaultGrain());
        }
        if (request.goodDirection() != null) {
            metric.setGoodDirection(request.goodDirection());
        }
        if (request.sensitive() != null) {
            metric.setPermLevel(request.sensitive() ? PERM_SENSITIVE : PERM_PUBLIC);
        }
        metric.setUpdatedBy(operator);
        metricMapper.updateById(metric);
        if (request.availableDimensions() != null) {
            bindDimensions(metricId, request.availableDimensions());
        }
        return toDetail(metric, versionsOf(metricId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitApproval(Long metricId, String operator) {
        requireMetric(metricId);
        BizMetricVersion pending = versionMapper.selectOne(new LambdaQueryWrapper<BizMetricVersion>()
                .eq(BizMetricVersion::getMetricId, metricId)
                .eq(BizMetricVersion::getStatus, VERSION_PENDING)
                .orderByDesc(BizMetricVersion::getVersionNo).last("LIMIT 1"));
        if (pending == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "无待审批版本，请先修改指标口径触发审批流");
        }
        // 首次提交写入 submitted_at（草稿→待审批）；重复提交保持原值，接口幂等
        if (pending.getSubmittedAt() == null) {
            pending.setSubmittedAt(LocalDateTime.now());
            versionMapper.updateById(pending);
        }
        return pending.getId();
    }

    @Override
    public List<ApprovalTodoItem> listApprovalTodos() {
        List<BizMetricVersion> pendingVersions = versionMapper.selectList(
                new LambdaQueryWrapper<BizMetricVersion>()
                        .eq(BizMetricVersion::getStatus, VERSION_PENDING)
                        .isNotNull(BizMetricVersion::getSubmittedAt)
                        .orderByDesc(BizMetricVersion::getSubmittedAt));
        if (pendingVersions.isEmpty()) {
            return List.of();
        }
        List<Long> metricIds = pendingVersions.stream()
                .map(BizMetricVersion::getMetricId).distinct().toList();
        Map<Long, BizMetric> metrics = metricMapper.selectList(
                        new LambdaQueryWrapper<BizMetric>().in(BizMetric::getId, metricIds))
                .stream().collect(Collectors.toMap(BizMetric::getId, Function.identity()));

        List<ApprovalTodoItem> items = new ArrayList<>();
        for (BizMetricVersion v : pendingVersions) {
            BizMetric metric = metrics.get(v.getMetricId());
            // 兜底：指标若已软删则不展示（删除已有引用拦截，正常不会出现）
            if (metric == null) {
                continue;
            }
            items.add(new ApprovalTodoItem(v.getId(), metric.getId(),
                    metric.getMetricCode(), metric.getMetricName(), v.getVersionNo(),
                    v.getSubmittedBy(), v.getSubmittedAt(), v.getCalcScope(), v.getFormulaExpr()));
        }
        return items;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApproveResult approve(Long approvalId, String operator) {
        BizMetricVersion version = requireVersion(approvalId);
        if (version.getStatus() != VERSION_PENDING) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "审批单状态非待审批（status=" + version.getStatus() + "），无法通过");
        }
        BizMetric metric = requireMetric(version.getMetricId());
        LocalDateTime now = LocalDateTime.now();

        // 事务性状态迁移：旧生效版本→历史，新版本→生效
        List<BizMetricVersion> effectiveVersions = versionMapper.selectList(
                new LambdaQueryWrapper<BizMetricVersion>()
                        .eq(BizMetricVersion::getMetricId, metric.getId())
                        .eq(BizMetricVersion::getStatus, VERSION_EFFECTIVE));
        for (BizMetricVersion old : effectiveVersions) {
            old.setStatus(VERSION_HISTORICAL);
            versionMapper.updateById(old);
        }
        version.setStatus(VERSION_EFFECTIVE);
        version.setApprovedBy(operator);
        version.setEffectiveAt(now);
        versionMapper.updateById(version);

        // 回写指标当前口径
        metric.setFormulaExpr(version.getFormulaExpr());
        metric.setCalcScope(version.getCalcScope());
        metric.setUpdatedBy(operator);
        metricMapper.updateById(metric);

        // 语义层双写：向量库同步（本地 InMemory，生产 Milvus）
        indexMetric(metric);
        // 广播版本发布事件（FR-21：通知订阅相关报表的用户）
        eventPublisher.publishEvent(new MetricPublishedEvent(
                metric.getId(), metric.getMetricCode(), version.getVersionNo(), version.getSubmittedBy(), now));
        return new ApproveResult(approvalId, version.getVersionNo(), EVENT_VERSION_PUBLISHED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long approvalId, String operator, String comment) {
        BizMetricVersion version = requireVersion(approvalId);
        if (version.getStatus() != VERSION_PENDING) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审批单状态非待审批，无法驳回");
        }
        version.setStatus(VERSION_REJECTED);
        if (comment != null && !comment.isBlank()) {
            version.setChangeNote(version.getChangeNote() == null ? comment
                    : version.getChangeNote() + "；驳回：" + comment);
        }
        versionMapper.updateById(version);
    }

    @Override
    public List<MetricVersionInfo> listVersions(Long metricId) {
        requireMetric(metricId);
        return versionMapper.selectList(new LambdaQueryWrapper<BizMetricVersion>()
                        .eq(BizMetricVersion::getMetricId, metricId)
                        .orderByDesc(BizMetricVersion::getVersionNo))
                .stream()
                .map(v -> new MetricVersionInfo(v.getVersionNo(), v.getFormulaExpr(), v.getCalcScope(),
                        v.getStatus(), v.getSubmittedBy(), v.getApprovedBy(), v.getEffectiveAt(), v.getChangeNote()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMetric(Long metricId, String operator) {
        BizMetric metric = requireMetric(metricId);
        // 删除指标的同义词已无意义：同事务清理
        synonymMapper.delete(new LambdaQueryWrapper<BizSynonym>()
                .eq(BizSynonym::getTargetType, TARGET_METRIC)
                .eq(BizSynonym::getTargetId, metricId));
        metric.setUpdatedBy(operator);
        metricMapper.updateById(metric);
        // @TableLogic：deleteById 执行 UPDATE is_deleted=1，版本与维度关联保留可审计
        metricMapper.deleteById(metricId);
    }

    @Override
    public void setMetricStatus(Long metricId, Integer status, String operator) {
        if (status == null || (status != STATUS_DISABLED && status != STATUS_ENABLED)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅支持 0(停用)/1(启用)");
        }
        BizMetric metric = requireMetric(metricId);
        metric.setStatus(status);
        metric.setUpdatedBy(operator);
        metricMapper.updateById(metric);
    }

    // ---------------- 维度 ----------------

    @Override
    public PageResult<DimensionSummary> listDimensions(String keyword, int page, int size) {
        validatePage(size);
        LambdaQueryWrapper<BizDimension> wrapper = new LambdaQueryWrapper<BizDimension>()
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(BizDimension::getDimName, keyword)
                                .or().like(BizDimension::getDimCode, keyword))
                .orderByDesc(BizDimension::getId);
        List<BizDimension> dims = dimensionMapper.selectList(wrapper);
        long total = dims.size();
        int from = Math.min((int) ((long) (page - 1) * size), dims.size());
        int to = Math.min(from + size, dims.size());
        List<DimensionSummary> records = dims.subList(from, to).stream()
                .map(d -> new DimensionSummary(d.getId(), d.getDimCode(), d.getDimName(), d.getDimType()))
                .toList();
        return PageResult.of(records, total, page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDimension(DimensionUpsertRequest request, String operator) {
        checkParam(request.code(), "维度编码");
        checkParam(request.name(), "维度名称");
        if (dimensionMapper.selectCount(new LambdaQueryWrapper<BizDimension>()
                .eq(BizDimension::getDimCode, request.code())) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "维度编码 " + request.code() + " 已存在");
        }
        BizDimension dim = new BizDimension();
        dim.setDimCode(request.code());
        dim.setDimName(request.name());
        dim.setDimType(request.dimType() == null ? 2 : request.dimType());
        applyMapping(dim, request);
        dimensionMapper.insert(dim);
        replaceDimValues(dim.getId(), request.enumValues());
        return dim.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DimensionDetail patchDimension(Long dimensionId, DimensionUpsertRequest request, String operator) {
        BizDimension dim = requireDimension(dimensionId);
        if (request.name() != null) {
            dim.setDimName(request.name());
        }
        if (request.dimType() != null) {
            dim.setDimType(request.dimType());
        }
        applyMapping(dim, request);
        dimensionMapper.updateById(dim);
        if (request.enumValues() != null) {
            replaceDimValues(dimensionId, request.enumValues());
        }
        return toDimDetail(dim);
    }

    @Override
    public DimensionDetail getDimension(Long dimensionId) {
        return toDimDetail(requireDimension(dimensionId));
    }

    @Override
    public DimensionDetail getDimensionByCode(String dimCode) {
        if (dimCode == null || dimCode.isBlank()) {
            return null;
        }
        BizDimension dim = dimensionMapper.selectOne(new LambdaQueryWrapper<BizDimension>()
                .eq(BizDimension::getDimCode, dimCode.trim()));
        return dim == null ? null : toDimDetail(dim);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDimension(Long dimensionId, String operator) {
        requireDimension(dimensionId);
        long metricRefs = metricDimMapper.selectCount(new LambdaQueryWrapper<BizMetricDim>()
                .eq(BizMetricDim::getDimId, dimensionId));
        long synonymRefs = synonymMapper.selectCount(new LambdaQueryWrapper<BizSynonym>()
                .eq(BizSynonym::getTargetType, TARGET_DIMENSION)
                .eq(BizSynonym::getTargetId, dimensionId));
        if (metricRefs > 0 || synonymRefs > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该维度被引用，无法删除（指标可用维度 " + metricRefs + " 处、同义词 " + synonymRefs + " 条）");
        }
        // @TableLogic 软删；biz_dim_value 保留，随维度复活可复用
        dimensionMapper.deleteById(dimensionId);
    }

    // ---------------- 同义词 ----------------

    @Override
    public PageResult<SynonymItem> listSynonyms(String keyword, int page, int size) {
        validatePage(size);
        LambdaQueryWrapper<BizSynonym> wrapper = new LambdaQueryWrapper<BizSynonym>()
                .like(keyword != null && !keyword.isBlank(), BizSynonym::getTermGroup, keyword)
                .orderByDesc(BizSynonym::getId);
        List<BizSynonym> synonyms = synonymMapper.selectList(wrapper);
        long total = synonyms.size();
        int from = Math.min((int) ((long) (page - 1) * size), synonyms.size());
        int to = Math.min(from + size, synonyms.size());
        List<SynonymItem> records = synonyms.subList(from, to).stream()
                .map(s -> new SynonymItem(s.getId(), s.getTermGroup(), s.getTargetType(),
                        s.getTargetId(), s.getHitCount()))
                .toList();
        return PageResult.of(records, total, page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSynonym(SynonymCreateRequest request, String operator) {
        checkParam(request.target(), "目标定位串（metric:code / dimension:code）");
        if (request.terms() == null || request.terms().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "同义词组至少包含一个词条");
        }
        int[] target = resolveTarget(request.target());
        Long firstId = null;
        for (String term : request.terms()) {
            BizSynonym synonym = new BizSynonym();
            synonym.setTermGroup(term);
            synonym.setTargetType(target[0]);
            synonym.setTargetId((long) target[1]);
            synonym.setHitCount(0L);
            synonym.setStatus(1);
            synonymMapper.insert(synonym);
            if (firstId == null) {
                firstId = synonym.getId();
            }
        }
        return firstId;
    }

    @Override
    public void deleteSynonym(Long synonymId) {
        synonymMapper.deleteById(synonymId);
    }

    // ---------------- 内部工具 ----------------

    /** 版本号快照：effectiveVersion（生效中最高版本）+ pendingVersion（待审批最高版本）。 */
    private int[] versionState(Long metricId) {
        List<BizMetricVersion> versions = versionsOf(metricId);
        int effective = 0;
        int pending = 0;
        for (BizMetricVersion v : versions) {
            if (v.getStatus() == VERSION_EFFECTIVE) {
                effective = Math.max(effective, v.getVersionNo());
            } else if (v.getStatus() == VERSION_PENDING) {
                pending = Math.max(pending, v.getVersionNo());
            }
        }
        return new int[]{effective, pending};
    }

    private List<BizMetricVersion> versionsOf(Long metricId) {
        return versionMapper.selectList(new LambdaQueryWrapper<BizMetricVersion>()
                .eq(BizMetricVersion::getMetricId, metricId));
    }

    private int maxVersionNo(Long metricId) {
        return versionsOf(metricId).stream()
                .mapToInt(BizMetricVersion::getVersionNo)
                .max().orElse(0);
    }

    private void insertVersion(Long metricId, int versionNo, String formula, String calcScope,
                               int status, String submittedBy, String approvedBy,
                               LocalDateTime effectiveAt, String changeNote) {
        BizMetricVersion version = new BizMetricVersion();
        version.setMetricId(metricId);
        version.setVersionNo(versionNo);
        version.setFormulaExpr(formula);
        version.setCalcScope(calcScope);
        version.setStatus(status);
        version.setSubmittedBy(submittedBy);
        version.setApprovedBy(approvedBy);
        version.setEffectiveAt(effectiveAt);
        version.setChangeNote(changeNote);
        versionMapper.insert(version);
    }

    /** 重绑指标可用维度（先清后插）。 */
    private void bindDimensions(Long metricId, List<String> dimCodes) {
        if (dimCodes == null) {
            return;
        }
        metricDimMapper.delete(new LambdaQueryWrapper<BizMetricDim>().eq(BizMetricDim::getMetricId, metricId));
        if (dimCodes.isEmpty()) {
            return;
        }
        Map<String, BizDimension> byCode = dimensionMapper.selectList(
                        new LambdaQueryWrapper<BizDimension>().in(BizDimension::getDimCode, dimCodes))
                .stream().collect(Collectors.toMap(BizDimension::getDimCode, Function.identity()));
        for (String code : dimCodes) {
            BizDimension dim = byCode.get(code);
            if (dim == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "维度编码不存在：" + code);
            }
            BizMetricDim link = new BizMetricDim();
            link.setMetricId(metricId);
            link.setDimId(dim.getId());
            metricDimMapper.insert(link);
        }
    }

    /** 全量替换维度枚举值：新增即时生效；删除需确认无指标引用（BR）。 */
    private void replaceDimValues(Long dimId, List<DimensionValueItem> values) {
        List<BizDimValue> existing = dimValueMapper.selectList(
                new LambdaQueryWrapper<BizDimValue>().eq(BizDimValue::getDimId, dimId));
        Map<String, BizDimValue> byCode = existing.stream()
                .collect(Collectors.toMap(BizDimValue::getValueCode, Function.identity()));
        List<String> incoming = values == null ? List.of() : values.stream().map(DimensionValueItem::valueCode).toList();
        boolean removedAny = byCode.keySet().stream().anyMatch(code -> !incoming.contains(code));
        if (removedAny && referencedByMetric(dimId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该维度已被指标引用，禁止删除枚举值");
        }
        // 新增/更新（value_label/sort_no 为 NOT NULL，insert 前必须补全）
        if (values != null) {
            for (DimensionValueItem item : values) {
                BizDimValue val = byCode.get(item.valueCode());
                if (val == null) {
                    val = new BizDimValue();
                    val.setDimId(dimId);
                    val.setValueCode(item.valueCode());
                    val.setValueLabel(item.valueLabel());
                    val.setParentCode(item.parentCode());
                    val.setSortNo(item.sortNo());
                    dimValueMapper.insert(val);
                } else {
                    val.setValueLabel(item.valueLabel());
                    val.setParentCode(item.parentCode());
                    val.setSortNo(item.sortNo());
                    dimValueMapper.updateById(val);
                }
            }
        }
        // 删除已移除的
        for (BizDimValue val : existing) {
            if (!incoming.contains(val.getValueCode())) {
                dimValueMapper.deleteById(val.getId());
            }
        }
    }

    private boolean referencedByMetric(Long dimId) {
        return metricDimMapper.selectCount(
                new LambdaQueryWrapper<BizMetricDim>().eq(BizMetricDim::getDimId, dimId)) > 0;
    }

    private DimensionDetail toDimDetail(BizDimension dim) {
        List<DimensionValueItem> values = dimValueMapper.selectList(
                        new LambdaQueryWrapper<BizDimValue>().eq(BizDimValue::getDimId, dim.getId())
                                .orderByAsc(BizDimValue::getSortNo))
                .stream()
                .map(v -> new DimensionValueItem(v.getValueCode(), v.getValueLabel(),
                        v.getParentCode(), v.getSortNo()))
                .toList();
        return new DimensionDetail(dim.getId(), dim.getDimCode(), dim.getDimName(),
                dim.getDimType(), dim.getRefTable(), dim.getKeyColumn(), dim.getValueColumn(),
                dim.getParentColumn(), dim.getFactColumn(), dim.getCurrentColumn(), values);
    }

    /**
     * 物理映射落库（创建/更新共用）。映射模式：
     * <ul>
     *   <li>refTable + keyColumn + valueColumn：查表维度（事实表外键关联来源表取值）；</li>
     *   <li>仅 valueColumn：事实表自带属性维度；</li>
     *   <li>parentColumn 非空：层级维度，支持下钻。</li>
     * </ul>
     * 所有标识符经白名单校验，防止 SQL 拼接注入。
     */
    private void applyMapping(BizDimension dim, DimensionUpsertRequest request) {
        String refTable = checkIdentifier(request.refTable(), "物理来源表");
        String key = checkIdentifier(request.keyColumn(), "主键列");
        String value = checkIdentifier(request.valueColumn(), "取值列");
        String parent = checkIdentifier(request.parentColumn(), "父键列");
        String fact = checkIdentifier(request.factColumn(), "事实表外键列");
        String current = checkIdentifier(request.currentColumn(), "时效标记列");
        if (refTable != null && (key == null || value == null)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "查表维度必须同时配置来源表、主键列与取值列");
        }
        if (refTable == null && key != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "配置主键列时必须同时配置物理来源表");
        }
        if (parent != null && refTable == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "层级下钻仅支持查表维度，请补全来源表与主键列");
        }
        if (fact != null && key == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "配置事实表外键列时必须配置来源表主键列");
        }
        dim.setRefTable(refTable);
        dim.setKeyColumn(key);
        dim.setValueColumn(value);
        dim.setParentColumn(parent);
        dim.setFactColumn(fact);
        dim.setCurrentColumn(current);
    }

    /** 物理标识符白名单：^[a-z_][a-z0-9_]{0,63}$；空值透传 null。 */
    private String checkIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().toLowerCase();
        if (!trimmed.matches("^[a-z_][a-z0-9_]{0,63}$")) {
            throw new BizException(ErrorCode.PARAM_INVALID, label + "含非法标识符: " + value);
        }
        return trimmed;
    }

    private MetricSummary toSummary(BizMetric metric, int[] versionState) {
        return new MetricSummary(metric.getId(), metric.getMetricCode(), metric.getMetricName(),
                metric.getDomain(), metric.getStatus(), versionState[0], versionState[1], metric.getUpdatedAt());
    }

    private MetricDetail toDetail(BizMetric metric, List<BizMetricVersion> versions) {
        int effective = 0;
        int pending = 0;
        for (BizMetricVersion v : versions) {
            if (v.getStatus() == VERSION_EFFECTIVE) {
                effective = Math.max(effective, v.getVersionNo());
            } else if (v.getStatus() == VERSION_PENDING) {
                pending = Math.max(pending, v.getVersionNo());
            }
        }
        List<String> dimCodes = metricDimMapper.selectList(
                        new LambdaQueryWrapper<BizMetricDim>().eq(BizMetricDim::getMetricId, metric.getId()))
                .stream()
                .map(link -> requireDimension(link.getDimId()).getDimCode())
                .toList();
        return new MetricDetail(metric.getId(), metric.getMetricCode(), metric.getMetricName(),
                metric.getDomain(), metric.getFormulaExpr(), metric.getCalcScope(), metric.getDefaultPeriod(),
                metric.getGoodDirection(), metric.getStatus(), effective, pending,
                metric.getPermLevel(), dimCodes, metric.getUpdatedBy(), metric.getUpdatedAt());
    }

    /** 向量库双写（指标检索文本 = 名称 + 口径摘要）。 */
    private void indexMetric(BizMetric metric) {
        String content = metric.getMetricName() + " " + metric.getCalcScope();
        semanticIndexService.indexMetric(metric.getDomain(), metric.getMetricCode(), metric.getMetricName(),
                List.of(), content, maxVersionNo(metric.getId()),
                metric.getPermLevel() == null ? PERM_PUBLIC : metric.getPermLevel());
    }

    /** 解析目标定位串 {@code metric:code} / {@code dimension:code} → [targetType, targetId]。 */
    private int[] resolveTarget(String target) {
        int idx = target.indexOf(':');
        if (idx <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标定位串格式应为 type:code");
        }
        String type = target.substring(0, idx);
        String code = target.substring(idx + 1);
        return switch (type) {
            case "metric" -> {
                BizMetric metric = metricMapper.selectOne(new LambdaQueryWrapper<BizMetric>()
                        .eq(BizMetric::getMetricCode, code));
                if (metric == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "指标不存在：" + code);
                }
                yield new int[]{1, metric.getId().intValue()};
            }
            case "dimension" -> {
                BizDimension dim = dimensionMapper.selectOne(new LambdaQueryWrapper<BizDimension>()
                        .eq(BizDimension::getDimCode, code));
                if (dim == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "维度不存在：" + code);
                }
                yield new int[]{2, dim.getId().intValue()};
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知目标类型：" + type);
        };
    }

    private BizMetric requireMetric(Long metricId) {
        BizMetric metric = metricMapper.selectById(metricId);
        if (metric == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标不存在：id=" + metricId);
        }
        return metric;
    }

    private BizMetricVersion requireVersion(Long approvalId) {
        BizMetricVersion version = versionMapper.selectById(approvalId);
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审批单不存在：id=" + approvalId);
        }
        return version;
    }

    private BizDimension requireDimension(Long dimensionId) {
        BizDimension dim = dimensionMapper.selectById(dimensionId);
        if (dim == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "维度不存在：id=" + dimensionId);
        }
        return dim;
    }

    private static void validatePage(int size) {
        if (size <= 0 || size > 200) {
            throw new BizException(ErrorCode.PAGE_SIZE_EXCEED);
        }
    }

    private static void checkParam(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, name);
        }
    }

    /** 请求域（STAFF/PAYROLL…）归一化为存储小写（staff/payroll…）。 */
    static String normalizeDomain(String domain) {
        return domain == null ? "staff" : domain.toLowerCase();
    }
}
