package com.hrchat.chat.source;

import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.AskSourceReader;
import com.hrchat.api.report.ReportContext;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.chat.entity.ChtSession;
import com.hrchat.chat.mapper.ChtSessionMapper;
import com.hrchat.chat.store.ChatAskStore;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ASK 来源读取实现：校验租户/会话/完成态后恢复 {@link ReportContext}。
 */
@Service
public class ChatAskSourceReader implements AskSourceReader {

    private static final Pattern DATE_RANGE = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s*/\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern LAST_N_MONTHS = Pattern.compile("近\\s*(\\d+)\\s*月");

    private final ChatAskStore asks;
    private final ChtSessionMapper sessions;
    private final UserContextService users;

    public ChatAskSourceReader(ChatAskStore asks, ChtSessionMapper sessions, UserContextService users) {
        this.asks = asks;
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public ReportContext readAuthorizedAsk(AskSourceAccess access, String askId) {
        if (access == null || askId == null || askId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "source_ask_id");
        }
        UserContext user = users.resolve(access.empNo(), access.tenantId(), null);
        if (user == null
                || !Objects.equals(user.getUserId(), access.userId())
                || !Objects.equals(user.getTenantId(), access.tenantId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        ChatAskStore.AskRecord ask = asks.get(askId.trim());
        if (ask == null
                || !Objects.equals(ask.userId(), user.getUserId())
                || !Objects.equals(ask.tenantId(), user.getTenantId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        ChtSession session = sessions.selectById(ask.sessionId());
        if (session == null || Objects.equals(session.getIsDeleted(), 1)
                || !Objects.equals(session.getUserId(), user.getUserId())
                || !Objects.equals(session.getTenantId(), user.getTenantId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        if (!"COMPLETED".equals(ask.status()) || ask.payload() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅支持已完成的答案作为报表来源");
        }

        Map<String, Object> evidenceRoot = asks.evidence(askId);
        Map<String, Object> execution = map(evidenceRoot.get("execution"));
        Map<String, Object> plan = map(execution.get("query_plan"));
        boolean hasTrustedEvidence = !execution.isEmpty() && !plan.isEmpty();

        AnswerPayload.Caliber caliber = ask.payload().caliber();
        String metricCode = firstMetricCode(plan, caliber);
        if (metricCode == null || metricCode.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "来源答案缺少指标编码");
        }
        Integer metricVersion = asInt(execution.get("metric_version"));
        String queryMode = stringOr(plan.get("query_mode"),
                caliber == null ? null : caliber.queryMode());
        List<String> dimensions = resolveDimensions(queryMode, ask.payload());
        ReportContext.PeriodSpec period = resolvePeriod(plan, caliber);
        List<Long> orgIds = effectiveOrgIds(execution);
        String completeness = hasTrustedEvidence && period != null && metricVersion != null
                ? "complete" : "partial";
        String unit = ask.payload().conclusion() == null ? null : ask.payload().conclusion().unit();
        String metricName = caliber == null ? metricCode : stringOr(caliber.metric(), metricCode);
        String orgLabel = caliber == null ? null : caliber.organization();
        String definition = caliber == null ? null : caliber.definition();

        return new ReportContext(
                ReportContext.SCHEMA_VERSION,
                "ASK",
                ask.askId(),
                ask.turnId(),
                ask.question(),
                metricCode,
                metricVersion,
                metricName,
                unit,
                queryMode,
                dimensions,
                period,
                orgLabel,
                orgIds,
                Map.of(),
                completeness,
                hasTrustedEvidence,
                definition);
    }

    private static List<String> resolveDimensions(String queryMode, AnswerPayload payload) {
        String mode = queryMode == null ? "" : queryMode.toLowerCase(Locale.ROOT);
        if ("trend".equals(mode) || (payload.chart() != null
                && "LINE".equalsIgnoreCase(payload.chart().type()))) {
            return List.of("time");
        }
        return List.of("org");
    }

    private static ReportContext.PeriodSpec resolvePeriod(Map<String, Object> plan,
                                                          AnswerPayload.Caliber caliber) {
        Map<String, Object> tr = map(plan.get("time_range"));
        String start = stringOr(tr.get("start"), null);
        String end = stringOr(tr.get("end"), null);
        if (start != null && end != null) {
            return new ReportContext.PeriodSpec("FIXED", start, end, null, null, start, end,
                    start + "/" + end);
        }
        String label = caliber == null ? null : caliber.timeRange();
        if (label != null) {
            Matcher fixed = DATE_RANGE.matcher(label);
            if (fixed.find()) {
                String s = fixed.group(1);
                String e = fixed.group(2);
                // caliber 展示常为含尾日；统一存 endExclusive=次日需调用方自行处理时保留原字符串端点
                return new ReportContext.PeriodSpec("FIXED", s, e, null, null, s, e, label);
            }
            Matcher rolling = LAST_N_MONTHS.matcher(label);
            if (rolling.find()) {
                int months = Integer.parseInt(rolling.group(1));
                return new ReportContext.PeriodSpec("ROLLING", null, null, "LAST_N_MONTHS", months,
                        null, null, label);
            }
        }
        return new ReportContext.PeriodSpec("FIXED", null, null, null, null, null, null, label);
    }

    private static String firstMetricCode(Map<String, Object> plan, AnswerPayload.Caliber caliber) {
        Object codes = plan.get("metric_codes");
        if (codes instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
            return String.valueOf(list.get(0));
        }
        return caliber == null ? null : caliber.metricCode();
    }

    private static List<Long> effectiveOrgIds(Map<String, Object> execution) {
        Object raw = execution.get("effective_org_ids");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (Object v : list) {
            if (v instanceof Number n) {
                out.add(n.longValue());
            }
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    private static String stringOr(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() || "null".equals(s) ? fallback : s;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
