package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 视图组织范围解析（B1）：把版本级有效组织按权威组织层级收窄到某下钻视图的真实范围。
 *
 * <p>下钻值只是组织名称。用户授权通常只记录被授权的父节点及其子树 id，不会逐条列出
 * 子部门名称，因此不能用「授权组织名」做匹配——匹配不到就回退父范围会让分析口径从
 * 子视图扩大到整个父范围。这里一律以 {@code sec_org_node} 的物化路径为准解析下钻组织
 * 子树，再与版本级有效组织求交集；无法解析、或交集为空时明确拒绝，绝不回退到父范围。
 * 授权越界仍由调用方按用户授权子树另行校验。</p>
 */
@Service
@RequiredArgsConstructor
public class ViewOrgScopeResolver {

    private final SecOrgNodeMapper orgNodeMapper;

    /**
     * @param tenantId 报表租户；为空（旧单租户数据）时不加租户过滤
     * @param base     版本级有效组织；为空表示不受版本口径限制，直接采用解析出的下钻子树
     * @param viewKey  视图键；根视图或非组织下钻时原样返回 base
     */
    public List<Long> narrowDrillOrgs(String tenantId, List<Long> base, String viewKey) {
        String drill = ViewKeySupport.orgDrillValue(viewKey);
        if (drill == null) {
            return base;
        }
        LambdaQueryWrapper<SecOrgNode> query = new LambdaQueryWrapper<>();
        if (tenantId != null && !tenantId.isBlank()) {
            query.eq(SecOrgNode::getTenantId, tenantId);
        }
        List<SecOrgNode> nodes = orgNodeMapper.selectList(query);
        List<String> paths = nodes.stream()
                .filter(n -> drill.trim().equals(n.getOrgName() == null ? null : n.getOrgName().trim()))
                .map(SecOrgNode::getOrgPath)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (paths.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "无法解析下钻组织范围");
        }
        // 同名组织可能出现在不同父节点下，取全部同名节点子树再与版本口径求交即可分辨。
        List<Long> subtree = nodes.stream()
                .filter(n -> n.getOrgPath() != null && paths.stream().anyMatch(n.getOrgPath()::startsWith))
                .map(SecOrgNode::getId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (base == null || base.isEmpty()) {
            return subtree;
        }
        List<Long> narrowed = base.stream().filter(subtree::contains).distinct().sorted().toList();
        if (narrowed.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "下钻视图超出该数据版本范围");
        }
        return narrowed;
    }
}
