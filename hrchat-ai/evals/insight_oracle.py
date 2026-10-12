"""Independent oracle for the insight scorer.

Recomputes the expected ``value``/``unit``/``period_ref``/``scope_ref`` of the
deterministic facts directly from the raw snapshot with plain arithmetic. It
deliberately does **not** import ``insight.fact_builder`` or ``insight.validator``:
a scoring pass must not be satisfiable by a bug shared with the production
builder. ``load_dataset`` still cross-checks this oracle against the hand-audited
dataset and against the production builder so drift in any of the three surfaces.

Only facts the frozen dataset actually asserts are modelled; a new fact type in a
future dataset must extend this oracle (``load_dataset`` raises on a gap).
"""
from __future__ import annotations

import re

ORACLE_VERSION = "insight-oracle-1"
TOLERANCE = 1e-6
_MONTH_RE = re.compile(r"^(\d{4})-(\d{2})(?:-\d{2})?$")


def _month_key(label) -> tuple[int, int] | None:
    m = _MONTH_RE.match(str(label))
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def _ordered_months(categories: list[str]) -> bool:
    keys = [_month_key(c) for c in categories]
    return bool(keys) and all(k is not None for k in keys) and all(
        keys[i] > keys[i - 1] for i in range(1, len(keys))
    )


def _contiguous(categories: list[str], gaps: list[str]) -> bool:
    if list(gaps):
        return False
    keys = [_month_key(c) for c in categories]
    if any(k is None for k in keys) or len(keys) < 2:
        return False
    for i in range(1, len(keys)):
        y0, m0 = keys[i - 1]
        expected = (y0 + 1, 1) if m0 == 12 else (y0, m0 + 1)
        if keys[i] != expected:
            return False
    return True


def _fact(fact_type, *, computable, value=None, unit=None, period_ref=None, scope_ref=None):
    return {"fact_type": fact_type, "computable": computable, "value": value,
            "unit": unit, "period_ref": period_ref, "scope_ref": scope_ref}


def expected_facts(context: dict, snapshot: dict) -> dict[str, dict]:
    """Expected deterministic facts for one case, recomputed from raw data."""

    unit = context["unit"]
    scope = context["scope_ref"]
    categories = list(snapshot.get("categories") or [])
    series = snapshot.get("series") or []
    values = list(series[0].get("data") or []) if series else list(categories)
    if len(values) != len(categories):
        values = []

    def finite(v) -> bool:
        return isinstance(v, (int, float)) and not isinstance(v, bool) and v == v and abs(v) != float("inf")

    endpoints = None
    if len(values) >= 2 and values[0] is not None and values[-1] is not None \
            and finite(values[0]) and finite(values[-1]):
        endpoints = (float(values[0]), float(values[-1]))

    facts: dict[str, dict] = {}
    temporal = context.get("axis_kind") == "time" and _ordered_months(categories)
    if temporal:
        if endpoints is None:
            facts["absolute_change:overall"] = _fact(
                "absolute_change", computable=False, period_ref="baseline_to_current", scope_ref=scope)
            facts["relative_change:overall"] = _fact(
                "relative_change", computable=False, period_ref="baseline_to_current", scope_ref=scope)
        else:
            baseline, current = endpoints
            facts["absolute_change:overall"] = _fact(
                "absolute_change", computable=True, value=current - baseline, unit=unit,
                period_ref="baseline_to_current", scope_ref=scope)
            if baseline == 0:
                facts["relative_change:overall"] = _fact(
                    "relative_change", computable=False, period_ref="baseline_to_current", scope_ref=scope)
            else:
                facts["relative_change:overall"] = _fact(
                    "relative_change", computable=True, value=(current - baseline) / baseline, unit="ratio",
                    period_ref="baseline_to_current", scope_ref=scope)
        if endpoints is None:
            facts["point_compare:overall"] = _fact("point_compare", computable=False, scope_ref=scope)
        else:
            facts["point_compare:overall"] = _fact(
                "point_compare", computable=True, value=endpoints[1], unit=unit,
                period_ref="baseline_to_current", scope_ref=scope)
        if context.get("metric_kind") == "ratio":
            facts["percentage_point_change:overall"] = _percentage_point(context, endpoints, scope)
        for i in range(1, len(values)):
            a, b = values[i - 1], values[i]
            fid = f"period_over_period:{categories[i - 1]}:{categories[i]}"
            ref = f"{categories[i - 1]}:{categories[i]}"
            if not finite(a) or not finite(b):
                facts[fid] = _fact("period_over_period", computable=False, period_ref=ref, scope_ref=scope)
            else:
                facts[fid] = _fact("period_over_period", computable=True, value=float(b) - float(a),
                                   unit=unit, period_ref=ref, scope_ref=scope)
        facts["continuous_trend:overall"] = _continuous_trend(snapshot, values, categories, scope)
    else:
        facts["continuous_trend:overall"] = _fact("continuous_trend", computable=False, scope_ref=scope)

    facts.update(_tied_extremes(values, categories, unit, scope))
    if context.get("metric_kind") == "stock":
        facts["cross_period_sum:blocked"] = _fact("cross_period_sum", computable=False, unit=unit, scope_ref=scope)
    facts.update(_department_contribution(context, snapshot, endpoints, unit, scope))
    return facts


def _percentage_point(context: dict, endpoints, scope: str) -> dict:
    if not context.get("numerator_metric_code") or not context.get("denominator_metric_code"):
        return _fact("percentage_point_change", computable=False,
                     period_ref="baseline_to_current", scope_ref=scope)
    if endpoints is None:
        return _fact("percentage_point_change", computable=False,
                     period_ref="baseline_to_current", scope_ref=scope)
    scale = context.get("ratio_scale") or ("percent" if context.get("unit") == "%" else None)
    if scale is None:
        return _fact("percentage_point_change", computable=False, scope_ref=scope)
    return _fact("percentage_point_change", computable=True,
                 value=(endpoints[1] - endpoints[0]) * (100 if scale == "fraction" else 1),
                 unit="pp", period_ref="baseline_to_current", scope_ref=scope)


def _continuous_trend(snapshot: dict, values, categories, scope: str) -> dict:
    if any(not isinstance(v, (int, float)) or isinstance(v, bool) or v != v or abs(v) == float("inf")
           for v in values):
        return _fact("continuous_trend", computable=False, scope_ref=scope)
    if not _contiguous(categories, snapshot.get("continuity_gaps") or []):
        return _fact("continuous_trend", computable=False, scope_ref=scope)
    if len(values) != len(categories) or len(values) < 2:
        return _fact("continuous_trend", computable=False, scope_ref=scope)
    nums = [float(v) for v in values]
    rising = all(nums[i] > nums[i - 1] for i in range(1, len(nums)))
    falling = all(nums[i] < nums[i - 1] for i in range(1, len(nums)))
    if not rising and not falling:
        return _fact("continuous_trend", computable=False, scope_ref=scope)
    return _fact("continuous_trend", computable=True, value=1.0 if rising else -1.0, scope_ref=scope)


def _tied_extremes(values, categories, unit: str, scope: str) -> dict[str, dict]:
    pairs = [(categories[i], float(values[i])) for i in range(min(len(values), len(categories)))
             if isinstance(values[i], (int, float)) and not isinstance(values[i], bool)
             and values[i] == values[i] and abs(values[i]) != float("inf")]
    if not pairs:
        return {"tied_extreme:max": _fact("tied_extreme", computable=False, scope_ref=scope)}
    max_v = max(v for _, v in pairs)
    min_v = min(v for _, v in pairs)
    return {
        "tied_extreme:max": _fact("tied_extreme", computable=True, value=max_v, unit=unit, scope_ref=scope),
        "tied_extreme:min": _fact("tied_extreme", computable=True, value=min_v, unit=unit, scope_ref=scope),
    }


def _department_contribution(context: dict, snapshot: dict, endpoints, unit: str, scope: str) -> dict[str, dict]:
    deps = snapshot.get("departments") or []
    if not deps:
        return {}
    categories = list(snapshot.get("categories") or [])
    series = snapshot.get("series") or []
    values = list(series[0].get("data") or []) if series else []
    complete = (
        context.get("metric_code") == "leave_count"
        and context.get("baseline_period") is not None
        and endpoints is not None
        and len(values) == len(categories)
        and float(values[0]) == sum(d["baseline_count"] for d in deps)
        and float(values[-1]) == sum(d["current_count"] for d in deps)
        and {d["org_id"] for d in deps} == set(context.get("effective_org_ids") or [])
    )
    if not complete:
        return {"department_contribution:blocked": _fact(
            "department_contribution", computable=False, scope_ref=scope)}
    current = sum(d["current_count"] for d in deps)
    baseline = sum(d["baseline_count"] for d in deps)
    increases = sum(max(0, d["current_count"] - d["baseline_count"]) for d in deps)
    decreases = sum(max(0, d["baseline_count"] - d["current_count"]) for d in deps)
    facts = {"department_contribution:offset": _fact(
        "department_contribution", computable=True, value=float(min(increases, decreases)),
        unit=unit, scope_ref=scope)}
    if current - baseline == 0:
        facts["department_contribution:net_rate"] = _fact(
            "department_contribution", computable=False, unit="share", scope_ref=scope)
    else:
        facts["department_contribution:net_rate"] = _fact(
            "department_contribution", computable=True, value=1.0, unit="share", scope_ref=scope)
    return facts
