package com.hrchat.authz.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 当前用户权限上下文：一次请求内权限裁决的唯一事实源（BR-02/BR-03/BR-05）。
 *
 * <p>由 {@link com.hrchat.authz.service.UserContextService} 装配，权限变更后 5min 内生效（BR-12）。</p>
 */
@Data
@Builder
public class UserContext {

    /** 用户 id（sec_user.id） */
    private Long userId;

    /** 工号 */
    private String empNo;

    /** 姓名 */
    private String displayName;

    /** 角色编码列表 */
    private List<String> roles;

    /** 最高数据层级（max(role.dataLevel)） */
    private Integer dataLevel;

    /** 组织授权（含下级子树） */
    private List<GrantedOrg> grantedOrgs;

    /** 字段级策略：fieldCode → 生效策略类型（1隐藏 2脱敏 3汇总可见 4明文） */
    private Map<String, Integer> fieldPolicyByField;

    /** 权限指纹（BR-05：缓存键成分，SHA-256 短值） */
    private String permissionFingerprint;

    /**
     * 组织授权快照。
     */
    @Data
    @Builder
    public static class GrantedOrg {

        /** 授权组织节点 id */
        private Long orgNodeId;

        /** 组织编码 */
        private String orgCode;

        /** 组织名称 */
        private String orgName;

        /** 物化路径 */
        private String orgPath;

        /** 授权范围：1查询 2查询+明细 3查询+明细+导出 */
        private Integer scope;

        /** 含下级的所有组织 id（行级过滤子树） */
        private List<Long> subtreeOrgKeys;
    }

    /**
     * 是否拥有指定授权范围。
     *
     * @param minScope 最小授权范围（2=明细 3=导出）
     * @return true=拥有
     */
    public boolean hasScope(int minScope) {
        return grantedOrgs.stream().anyMatch(g -> g.getScope() >= minScope);
    }

    /**
     * 是否拥有导出权限（BR-06）。
     *
     * @return true=可导出
     */
    public boolean canExport() {
        return hasScope(3);
    }

    /**
     * 是否拥有明细查看权限。
     *
     * @return true=可看明细
     */
    public boolean canViewDetail() {
        return hasScope(2);
    }
}
