package com.hrchat.report.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * 洞察上下文可见域（A7/D2）：由服务端从数据版本冻结上下文与当前授权解析，客户端不能自选以提高可见范围。
 *
 * <p>可见域只有两种：{@link #DOMAIN_FULL}（可访问来源原问答，可携带 questionSummary）与
 * {@link #DOMAIN_REPORT}（仅有报表授权，只使用报表自身合法上下文）。不同可见域使用不同的
 * {@code contextVisibilityRef}，既隔离幂等生成键也隔离结果选择；同一可见域与内容修订内复用。</p>
 *
 * <p>{@code contextVisibilityRef} 是不可逆摘要，只标识“上下文可见域 + 内容修订”，不是 ownerId，
 * 也不保证永久授权：读取与恢复仍按当前授权重新解析 ref 后重新选择结果。</p>
 */
public final class InsightContextVisibility {

    /** 上下文修订：授权业务上下文的含义/内容口径变化时递增，仅影响新生成键。 */
    public static final String CONTEXT_REVISION = "insight-context-1";

    public static final String DOMAIN_FULL = "full";
    public static final String DOMAIN_REPORT = "report";

    public static final String GOAL_SOURCE_ASK = "ask";
    public static final String GOAL_SOURCE_REPORT = "report";

    /**
     * 解析结果。
     *
     * @param ref              不可逆上下文可见域标识（用于生成键与结果选择）
     * @param domain           {@link #DOMAIN_FULL} / {@link #DOMAIN_REPORT}
     * @param questionSummary  经授权的原问题摘要；报表可见域为 null
     * @param analysisGoal     服务端派生的分析目标（不来自模型、不可被前端覆盖）
     * @param goalSource       ask / report
     */
    public record Resolved(String ref, String domain, String questionSummary,
                           String analysisGoal, String goalSource) {
    }

    private InsightContextVisibility() {
    }

    /**
     * 解析可见域。
     *
     * @param frozen        数据版本冻结的 {@code ReportContext} 映射（只读）
     * @param askAuthorized 当前请求者是否仍被授权访问来源原问答
     */
    public static Resolved resolve(String tenantId, Long reportId, Long componentId, Long versionId,
                                   String viewKey, Map<String, Object> frozen, boolean askAuthorized) {
        Map<String, Object> ctx = frozen == null ? Map.of() : frozen;
        String question = askAuthorized ? asString(ctx.get("questionSummary")) : null;
        boolean full = question != null && !question.isBlank();
        String domain = full ? DOMAIN_FULL : DOMAIN_REPORT;
        String analysisGoal = deriveGoal(ctx);
        String goalSource = full ? GOAL_SOURCE_ASK : GOAL_SOURCE_REPORT;
        String ref = refOf(tenantId, reportId, componentId, versionId, viewKey, domain,
                full ? question : null, analysisGoal, goalSource);
        return new Resolved(ref, domain, full ? question : null, analysisGoal, goalSource);
    }

    /** 不可逆摘要：租户 + 来源身份 + 可见域 + 内容修订。不含 userId，同可见域内可复用。 */
    public static String refOf(String tenantId, Long reportId, Long componentId, Long versionId,
                               String viewKey, String domain, String questionSummary,
                               String analysisGoal, String goalSource) {
        String material = String.join("\u0001",
                nullTo(tenantId, "_"), nullTo(reportId, "_"), nullTo(componentId, "_"), nullTo(versionId, "_"),
                nullTo(normalizeViewKey(viewKey), "_"), nullTo(domain, "_"),
                nullTo(questionSummary, "_"), nullTo(analysisGoal, "_"), nullTo(goalSource, "_"),
                CONTEXT_REVISION);
        return sha256Hex(material);
    }

    /** 服务端派生的分析目标：只取冻结上下文中的可信文本，不引入任何计算数值。 */
    static String deriveGoal(Map<String, Object> frozen) {
        String metric = firstNonBlank(asString(frozen.get("metricName")), asString(frozen.get("metricCode")), "该指标");
        String org = firstNonBlank(asString(frozen.get("organizationLabel")), "全部组织");
        String period = periodLabel(frozen);
        boolean timeAxis = isTimeAxis(frozen);
        String focus = timeAxis ? "逐期变化与拐点" : "构成与两期差异";
        return "针对" + org + "在" + period + "的" + metric + "，分析其" + focus + "并给出可核查的重点。";
    }

    private static String periodLabel(Map<String, Object> frozen) {
        Object raw = frozen.get("period");
        if (raw instanceof Map<?, ?> period) {
            Object label = period.get("displayLabel");
            if (label != null && !String.valueOf(label).isBlank()) {
                return String.valueOf(label).trim();
            }
            Object start = period.get("start");
            Object end = period.get("endExclusive");
            if (start != null && end != null) {
                return start + "~" + end;
            }
            if (start != null) {
                return String.valueOf(start);
            }
        }
        return "当前期间";
    }

    private static boolean isTimeAxis(Map<String, Object> frozen) {
        return frozen.get("dimensions") instanceof List<?> dims && dims.contains("time")
                || "trend".equals(frozen.get("queryMode"));
    }

    static String normalizeViewKey(String viewKey) {
        return viewKey == null || viewKey.isBlank() ? ViewKeySupport.ROOT : viewKey;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullTo(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? fallback : s;
    }

    private static String sha256Hex(String material) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
