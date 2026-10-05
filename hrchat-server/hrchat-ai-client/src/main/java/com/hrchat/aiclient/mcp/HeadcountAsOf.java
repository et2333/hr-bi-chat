package com.hrchat.aiclient.mcp;

import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;

import java.time.LocalDate;

/** 在职人数的时点口径；结束日是排他边界，离职当日不计在职。 */
public final class HeadcountAsOf {

    public static final LocalDate MIN_DEMO_DATE = LocalDate.of(2026, 1, 1);

    private HeadcountAsOf() {
    }

    public static LocalDate date(LocalDate endExclusive, LocalDate demoNow) {
        LocalDate requested = endExclusive == null ? demoNow : endExclusive.minusDays(1);
        LocalDate asOf = requested.isAfter(demoNow) ? demoNow : requested;
        if (asOf.isBefore(MIN_DEMO_DATE)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "演示在职历史仅覆盖 2026-01-01 起");
        }
        return asOf;
    }

    public static String predicate(LocalDate asOf) {
        String day = asOf.toString();
        return "hire_date <= DATE '" + day + "' AND (leave_date IS NULL OR leave_date > DATE '" + day + "')";
    }
}
