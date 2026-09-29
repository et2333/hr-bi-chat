package com.hrchat.aiclient.service;

import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.authz.model.UserContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行时客户端（架构 D-3：生产接 Python 运行时 agent-gateway，本地为确定性规则引擎）。
 *
 * <p>问数主链路：意图识别 → 语义检索（Schema Linking）→ SQL 模板拼装 → 权限改写 → 执行 → 呈现。</p>
 */
public interface AgentRuntimeClient {

    /**
     * 提交问句，返回完整语义事件序列与终态载荷。
     *
     * @param request 问数请求（question/mode/contextOverride）
     * @param ctx     当前用户权限上下文（行级/字段级裁决依据）
     * @return 编排结果
     */
    AgentResult ask(AskRequest request, UserContext ctx);

    /**
     * 澄清续答：基于原问句 + 用户选项继续原问答流（接口文档 2.2.6）。
     *
     * @param askId    原问句 id
     * @param question 原问句文本
     * @param answers  澄清应答（option_id 为语义对象编码）
     * @param ctx      当前用户权限上下文
     * @return 续跑后的编排结果
     */
    AgentResult clarify(String askId, String question, ClarifyAnswerRequest.Answer answers, UserContext ctx);

    /**
     * 报表 AI 洞察（P3-C）：默认实现为本地确定性模板解读（均值/趋势/极值）；
     * 远程实现 {@code RemoteAgentRuntimeClient} 转发 Python {@code POST /v1/insight}，
     * 无配置/降级时走默认模板，保证本地演示与远程一致。
     *
     * @param request 报表与图表摘要
     * @return 解读视图（summary + points[{type,label}]）
     */
    default Map<String, Object> generateInsight(InsightRequest request) {
        String reportName = request.reportName() == null ? "" : request.reportName();
        String metricName = request.metricName() == null ? "" : request.metricName();
        Map<String, Object> summary = request.summary() == null ? Map.of() : request.summary();
        @SuppressWarnings("unchecked")
        List<String> categories = summary.get("categories") instanceof List<?> list
                ? (List<String>) list : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = summary.get("series") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();

        List<Number> data = new ArrayList<>();
        if (!series.isEmpty() && series.get(0).get("data") instanceof List<?> dl) {
            for (Object v : dl) {
                data.add(v instanceof Number n ? n : 0.0);
            }
        }
        if (categories.isEmpty() || data.isEmpty()) {
            return Map.of("report_name", reportName, "metric_name", metricName,
                    "summary", "报告「" + reportName + "」暂无可用数据，无法生成洞察。", "points", List.of());
        }
        double avg = 0;
        double maxV = Double.MIN_VALUE, minV = Double.MAX_VALUE;
        int maxI = 0, minI = 0;
        for (int i = 0; i < data.size(); i++) {
            double v = data.get(i).doubleValue();
            avg += v;
            if (v > maxV) { maxV = v; maxI = i; }
            if (v < minV) { minV = v; minI = i; }
        }
        avg /= data.size();
        String trend = "持平";
        if (data.size() >= 2) {
            double first = data.get(0).doubleValue(), last = data.get(data.size() - 1).doubleValue();
            trend = last > first ? "上升" : last < first ? "下降" : "持平";
        }
        String label = metricName.isBlank() ? "本指标" : metricName;
        List<Map<String, String>> points = new ArrayList<>();
        points.add(Map.of("type", "value", "label", String.format("指标%s期间均值约 %.1f", label, avg)));
        points.add(Map.of("type", "trend", "label", "对比首末周期整体呈" + trend + "趋势"));
        points.add(Map.of("type", "extreme", "label", "峰值出现在" + catLabel(categories, maxI) + "（" + fmt(maxV) + "），低点在" + catLabel(categories, minI) + "（" + fmt(minV) + "）"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("report_name", reportName);
        view.put("metric_name", metricName);
        view.put("summary", String.format("报告「%s」共 %d 个周期，指标%s均值为 %.1f，整体呈%s走势。",
                reportName, categories.size(), label, avg, trend));
        view.put("points", points);
        return view;
    }

    private static String catLabel(List<String> categories, int index) {
        return index < categories.size() ? String.valueOf(categories.get(index)) : "第" + (index + 1) + "期";
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
