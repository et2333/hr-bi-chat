package com.hrchat.admin.dto;

import java.util.List;

/**
 * 管理后台视图/请求 DTO 聚合（接口文档 2.7 数据源与同步 / 2.8 问句评测 / 2.5 权限管理）。
 */
public final class AdminViews {

    private AdminViews() {
    }

    // ---------------- 数据源与同步（2.7） ----------------

    /** 数据源健康视图。 */
    public record DatasourceView(String dsCode, String dsName, String syncMode, Integer status,
                                 String lastSyncAt, String lastSyncState, String latestBizDate) {
    }

    /** 同步任务视图。 */
    public record SyncJobView(Long jobId, String dsCode, String dsName, String taskType,
                              String bizDate, String execState, Long rowsRead, Long rowsWritten,
                              String failReason, String startedAt, String finishedAt) {
    }

    /** 数据质量摘要。 */
    public record QualitySummary(String bizDate, long totalJobs, long success, long failed,
                                 long running, long delayed, double completeness) {
    }

    // ---------------- 问句评测（2.8） ----------------

    /** 评测问题集视图。 */
    public record QuestionSetView(String setId, String name, int questionCount, String createdAt) {
    }

    /** 回归运行结果。 */
    public record RunResultView(String runId, String status, double accuracy, double factAccuracy,
                                int passed, int total, List<String> failedQuestions) {
    }

    /** 创建问题集请求。 */
    public record QuestionSetCreateRequest(String name, List<QuestionSpec> questions) {
        public record QuestionSpec(String question, String sceneTag, String expectJson) {
        }
    }

    // ---------------- 权限管理（2.5） ----------------

    /** 角色视图。 */
    public record RoleView(Long roleId, String roleCode, String roleName, Integer dataLevel,
                           List<String> functionPerms) {
    }

    /** 创建/更新角色请求。 */
    public record RoleCreateRequest(String roleCode, String roleName, Integer dataLevel,
                                    List<String> functionPerms) {
    }

    /** 组织数据范围授权请求（行级）。 */
    public record DataScopeRequest(List<Long> orgIds, Integer grantScope) {
    }

    /** 字段策略请求（列级脱敏）。 */
    public record FieldPolicyRequest(List<FieldPolicy> policies) {
        public record FieldPolicy(String fieldCode, String domain, Integer policyType,
                                  Integer minGroupSize, Boolean approvalRequired) {
        }
    }

    /** 用户有效权限视图（合并角色/数据范围/字段策略/功能权限）。 */
    public record EffectivePermissionsView(String userId, List<String> roles,
                                           List<DataScope> dataScopes,
                                           List<String> fieldPolicies, List<String> functionPerms,
                                           String effectiveAt) {
        public record DataScope(Long orgNodeId, String orgName, Integer scope, Integer orgLevel) {
        }
    }
}
