package com.hrchat.aiclient.mcp;

import com.hrchat.authz.model.UserContext;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import java.util.List;
import java.util.Map;

/** S0 verified capabilities. Unverified definitions remain visible but are not executable by a planner. */
public final class PlanningCapabilities {
    private PlanningCapabilities() { }

    public static boolean visible(UserContext user, MetricDetail metric) {
        int level = user.getDataLevel() == null ? 1 : user.getDataLevel();
        return Integer.valueOf(1).equals(metric.status()) && metric.effectiveVersion() > 0
                && (metric.permLevel() == null || metric.permLevel() <= level);
    }

    public static Map<String, Object> describe(MetricDetail metric) {
        boolean count = List.of("headcount", "hire_count", "leave_count").contains(metric.code());
        return Map.of("unit", count ? "人" : "", "percent", false,
                "allowed_modes", count ? List.of("scalar", "org", "trend", "detail") : List.of(),
                "requires_period", !"headcount".equals(metric.code()),
                "time_type", "headcount".equals(metric.code()) ? "as_of" : "period");
    }

    public static void requireVisible(UserContext user, MetricDetail metric) {
        if (!visible(user, metric)) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
    }
}
