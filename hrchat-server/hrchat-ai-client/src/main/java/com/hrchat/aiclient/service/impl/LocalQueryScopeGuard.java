package com.hrchat.aiclient.service.impl;

import com.hrchat.semantic.entity.BizSynonym;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative guard for the local rule runtime; not a general natural-language parser. */
final class LocalQueryScopeGuard {
    private static final Pattern ORGANIZATION = Pattern.compile(
            "[\\p{IsHan}A-Za-z0-9]{1,24}(?:部门|事业群|分公司|事业部|中心|团队|小组|部)");
    private static final Pattern UNSUPPORTED_DIMENSION = Pattern.compile(
            "性别|职级|司龄|年龄|学历|岗位|男性|女性|男员工|女员工");

    private LocalQueryScopeGuard() {
    }

    static String refusal(String question, List<BizSynonym> synonyms) {
        String residual = question;
        Set<Long> matchedOrgs = new HashSet<>();
        // Consume known names longest-first. A shorter alias must not hide a second organization.
        List<BizSynonym> organizations = synonyms.stream()
                .filter(s -> Integer.valueOf(1).equals(s.getStatus())
                        && Integer.valueOf(2).equals(s.getTargetType())
                        && s.getTargetId() != null && s.getTermGroup() != null && !s.getTermGroup().isBlank())
                .sorted(Comparator.comparingInt((BizSynonym s) -> s.getTermGroup().length()).reversed())
                .toList();
        for (BizSynonym organization : organizations) {
            if (residual.contains(organization.getTermGroup())) {
                matchedOrgs.add(organization.getTargetId());
                residual = residual.replace(organization.getTermGroup(), " ");
            }
        }
        // These are generic scope/mode words, not organization names.
        residual = residual.replace("按部门对比", " ").replace("按组织对比", " ")
                .replace("全部", " ").replace("各部门", " ");
        if (ORGANIZATION.matcher(residual).find()) {
            return "组织无法识别，请使用组织目录中的完整名称；本次未执行查询，也未改用您的全部授权范围。";
        }
        if (matchedOrgs.size() > 1) {
            return "暂不支持同时指定多个组织，请分别查询；本次未选择其中一个组织代替原问题。";
        }
        if (UNSUPPORTED_DIMENSION.matcher(residual).find()) {
            return "暂不支持按性别、职级、司龄、年龄、学历或岗位筛选及分组统计；本次未执行查询，请调整条件。";
        }
        return null;
    }
}
