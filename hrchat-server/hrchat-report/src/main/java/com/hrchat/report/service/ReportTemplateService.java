package com.hrchat.report.service;

import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.dto.ReportViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 报表模板库（接口文档 2.3.3，FR-13：预置 ≥10 个人力场景模板）。
 *
 * <p>模板为代码内置目录（本地无模板表），实例化等价 {@code source_type=TEMPLATE} 创建。</p>
 */
@Service
@RequiredArgsConstructor
public class ReportTemplateService {

    private final ReportService reportService;

    /** 模板目录（预置 12 个）。 */
    private static final List<ReportViews.TemplateItem> TEMPLATES = List.of(
            new ReportViews.TemplateItem("tpl-monthly-hr", "人力月报", "月报",
                    "月度人力全景：在职/入职/离职/离职率趋势"),
            new ReportViews.TemplateItem("tpl-headcount", "编制盘点", "组织",
                    "各部门在职人数与编制对比"),
            new ReportViews.TemplateItem("tpl-turnover", "离职分析", "组织",
                    "离职人数/离职率与离职原因分布"),
            new ReportViews.TemplateItem("tpl-recruit", "招聘漏斗", "招聘",
                    "招聘转化漏斗：简历-面试-offer-入职"),
            new ReportViews.TemplateItem("tpl-attendance", "考勤汇总", "考勤",
                    "出勤率与缺勤统计"),
            new ReportViews.TemplateItem("tpl-payroll-cost", "人力成本", "薪酬",
                    "薪酬总额与人均成本月度趋势"),
            new ReportViews.TemplateItem("tpl-avg-salary", "平均薪酬", "薪酬",
                    "平均薪酬与职级/部门对比"),
            new ReportViews.TemplateItem("tpl-perf", "绩效分布", "绩效",
                    "绩效评分分布与强排比例"),
            new ReportViews.TemplateItem("tpl-tenure", "司龄结构", "组织",
                    "司龄段人数分布"),
            new ReportViews.TemplateItem("tpl-gender", "性别结构", "组织",
                    "在职员工性别分布"),
            new ReportViews.TemplateItem("tpl-level", "职级分布", "组织",
                    "职级人数金字塔"),
            new ReportViews.TemplateItem("tpl-new-hires", "新员工入职", "招聘",
                    "月度入职人数与新人留存"));

    private static final Map<String, String> PARAMS_SCHEMA = Map.of(
            "org_id", "组织ID（必填，含下级）",
            "period", "统计月份（如 2026-08，缺省最近完整月）");

    /** 模板列表。 */
    public PageResult<ReportViews.TemplateItem> list(String category, int page, int size) {
        List<ReportViews.TemplateItem> filtered = category == null || category.isBlank()
                ? TEMPLATES
                : TEMPLATES.stream().filter(t -> t.category().equals(category.trim())).toList();
        List<ReportViews.TemplateItem> records = filtered.stream()
                .skip((long) (page - 1) * size).limit(size).toList();
        return PageResult.of(records, filtered.size(), page, size);
    }

    /** 模板详情（含参数 Schema）。 */
    public ReportViews.TemplateDetail get(String templateId) {
        ReportViews.TemplateItem item = find(templateId);
        return new ReportViews.TemplateDetail(item.id(), item.name(), item.category(),
                item.description(), PARAMS_SCHEMA);
    }

    /** 模板实例化（FR-13）：等价 TEMPLATE 来源创建报表。 */
    public Long instantiate(UserContext ctx, String templateId, Map<String, Object> params, String name) {
        ReportViews.TemplateItem item = find(templateId);
        String reportName = name == null || name.isBlank() ? item.name() + " " + java.time.LocalDate.now() : name.trim();
        return reportService.create(ctx,
                new ReportDtos.ReportCreateRequest("TEMPLATE", templateId, reportName, null, null,
                        defaultComponents(templateId), params == null ? Map.of() : params),
                null);
    }

    private ReportViews.TemplateItem find(String templateId) {
        return TEMPLATES.stream().filter(t -> t.id().equals(templateId)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "templateId"));
    }

    /** 模板默认组件（语义层对象，非 SQL）。 */
    private List<ReportDtos.ComponentSpec> defaultComponents(String templateId) {
        Map<String, Object> chart = Map.of("type", "bar");
        return List.of(
                new ReportDtos.ComponentSpec("METRIC_CARD", null,
                        Map.of("metrics", defaultMetrics(templateId), "chart", chart)),
                new ReportDtos.ComponentSpec("CHART", "bar",
                        Map.of("metrics", defaultMetrics(templateId), "dimensions", List.of("time"), "chart", chart)),
                new ReportDtos.ComponentSpec("TABLE", null,
                        Map.of("metrics", defaultMetrics(templateId), "dimensions", List.of("org"))));
    }

    private List<String> defaultMetrics(String templateId) {
        return switch (templateId) {
            case "tpl-monthly-hr" -> List.of("headcount", "hire_count", "leave_count", "turnover_rate");
            case "tpl-headcount" -> List.of("headcount");
            case "tpl-turnover" -> List.of("leave_count", "turnover_rate");
            case "tpl-recruit" -> List.of("hire_count");
            case "tpl-attendance" -> List.of("attendance_rate");
            case "tpl-payroll-cost" -> List.of("payroll_total");
            case "tpl-avg-salary" -> List.of("avg_salary");
            case "tpl-perf" -> List.of("perf_avg");
            default -> List.of("headcount");
        };
    }
}
