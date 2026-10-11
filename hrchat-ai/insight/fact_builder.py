"""Pure FactBuilder: trusted snapshot + metric semantics → FactBundle.

Never fabricates values for missing months, nulls, or incomparable scopes.
Stock metrics are not summed across periods. Ratios need numerator/denominator.
"""
from __future__ import annotations

import re
from typing import Iterable

from insight.schema import (
    Fact,
    FactBundle,
    FactType,
    InsightContext,
    MetricKind,
    Snapshot,
)

_MONTH_RE = re.compile(r"^(\d{4})-(\d{2})(?:-\d{2})?$")


def build_fact_bundle(context: InsightContext, snapshot: Snapshot) -> FactBundle:
    facts: list[Fact] = []
    values, categories = _primary_series(snapshot)
    snap_id = snapshot.snapshot_id
    unit = context.unit
    scope = context.scope_ref

    facts.extend(_absolute_and_relative(context, snap_id, values, categories, unit, scope))
    facts.extend(_point_compare(context, snap_id, values, categories, unit, scope))
    facts.extend(_percentage_point(context, snap_id, values, categories, scope))
    facts.extend(_period_over_period(snap_id, values, categories, unit, scope))
    facts.extend(_continuous_trend(snapshot, snap_id, values, categories, scope))
    facts.extend(_tied_extremes(snap_id, values, categories, unit, scope))
    facts.extend(_stock_sum_guard(context, snap_id, values, unit, scope))
    facts.extend(_rank_share(snapshot, snap_id, unit, scope))
    facts.extend(_department_contribution(context, snapshot, snap_id, unit, scope))

    return FactBundle(
        snapshot_id=snap_id,
        data_version=snapshot.data_version,
        view_key=snapshot.view_key,
        metric_code=context.metric_code,
        metric_kind=context.metric_kind,
        unit=unit,
        facts=facts,
    )


def _primary_series(snapshot: Snapshot) -> tuple[list[float | None], list[str]]:
    if not snapshot.series:
        return [], list(snapshot.categories)
    data = list(snapshot.series[0].data)
    cats = list(snapshot.categories)
    if len(cats) != len(data):
        # Misaligned: treat as incomparable by clearing numeric use downstream.
        return data, cats
    return data, cats


def _finite(v: float | None) -> bool:
    return isinstance(v, (int, float)) and v == v and abs(v) != float("inf")


def _endpoints(values: list[float | None], categories: list[str]) -> tuple[float | None, float | None, str | None, str | None]:
    if len(values) < 2 or len(values) != len(categories):
        return None, None, None, None
    b, c = values[0], values[-1]
    if not _finite(b) or not _finite(c):
        return None, None, None, None
    return float(b), float(c), categories[0], categories[-1]


def _absolute_and_relative(
    context: InsightContext,
    snap_id: str,
    values: list[float | None],
    categories: list[str],
    unit: str,
    scope: str,
) -> list[Fact]:
    facts: list[Fact] = []
    baseline, current, b_cat, c_cat = _endpoints(values, categories)
    if baseline is None or current is None:
        facts.append(Fact(
            fact_id="absolute_change:overall",
            fact_type=FactType.ABSOLUTE_CHANGE,
            label="无法计算绝对变化",
            snapshot_id=snap_id,
            period_ref="baseline_to_current",
            scope_ref=scope,
            computable=False,
            incomputable_reason="missing_value" if any(v is None for v in values[:1] + values[-1:]) else "incomparable",
            basis="endpoints_required",
        ))
        facts.append(Fact(
            fact_id="relative_change:overall",
            fact_type=FactType.RELATIVE_CHANGE,
            label="无法计算相对变化",
            snapshot_id=snap_id,
            period_ref="baseline_to_current",
            scope_ref=scope,
            computable=False,
            incomputable_reason="missing_value",
            basis="endpoints_required",
        ))
        return facts

    delta = current - baseline
    prefer_abs = context.metric_kind in {MetricKind.STOCK, MetricKind.FLOW}
    facts.append(Fact(
        fact_id="absolute_change:overall",
        fact_type=FactType.ABSOLUTE_CHANGE,
        label=f"{b_cat}→{c_cat} 绝对变化 {delta:+g}{unit}",
        value=delta,
        unit=unit,
        snapshot_id=snap_id,
        period_ref="baseline_to_current",
        scope_ref=scope,
        computable=True,
        preferred=prefer_abs,
        basis="current_minus_baseline",
    ))
    if baseline == 0:
        facts.append(Fact(
            fact_id="relative_change:overall",
            fact_type=FactType.RELATIVE_CHANGE,
            label="零基期不可计算增长率",
            snapshot_id=snap_id,
            period_ref="baseline_to_current",
            scope_ref=scope,
            computable=False,
            preferred=False,
            incomputable_reason="zero_baseline",
            basis="relative_requires_nonzero_baseline",
        ))
    else:
        rate = (current - baseline) / baseline
        facts.append(Fact(
            fact_id="relative_change:overall",
            fact_type=FactType.RELATIVE_CHANGE,
            label=f"{b_cat}→{c_cat} 相对变化 {rate:+.1%}",
            value=rate,
            unit="ratio",
            snapshot_id=snap_id,
            period_ref="baseline_to_current",
            scope_ref=scope,
            computable=True,
            preferred=not prefer_abs,
            basis="delta_over_baseline",
        ))
    return facts


def _point_compare(
    context: InsightContext,
    snap_id: str,
    values: list[float | None],
    categories: list[str],
    unit: str,
    scope: str,
) -> list[Fact]:
    baseline, current, b_cat, c_cat = _endpoints(values, categories)
    if baseline is None or current is None:
        return [Fact(
            fact_id="point_compare:overall",
            fact_type=FactType.POINT_COMPARE,
            label="时点不可比较",
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=False,
            incomputable_reason="missing_value",
            basis="same_metric_endpoints",
        )]
    return [Fact(
        fact_id="point_compare:overall",
        fact_type=FactType.POINT_COMPARE,
        label=f"{c_cat}（{current:g}{unit}）对比 {b_cat}（{baseline:g}{unit}）",
        value=current,
        unit=unit,
        snapshot_id=snap_id,
        period_ref="baseline_to_current",
        scope_ref=scope,
        computable=True,
        basis="point_in_time",
        category=c_cat,
    )]


def _percentage_point(
    context: InsightContext,
    snap_id: str,
    values: list[float | None],
    categories: list[str],
    scope: str,
) -> list[Fact]:
    if context.metric_kind != MetricKind.RATIO:
        return []
    if not context.numerator_metric_code or not context.denominator_metric_code:
        return [Fact(
            fact_id="percentage_point_change:overall",
            fact_type=FactType.PERCENTAGE_POINT_CHANGE,
            label="比率缺少分子/分母，不可计算百分点变化",
            snapshot_id=snap_id,
            period_ref="baseline_to_current",
            scope_ref=scope,
            computable=False,
            incomputable_reason="ratio_components_missing",
            basis="requires_numerator_and_denominator",
        )]
    baseline, current, *_ = _endpoints(values, categories)
    if baseline is None or current is None:
        return [Fact(
            fact_id="percentage_point_change:overall",
            fact_type=FactType.PERCENTAGE_POINT_CHANGE,
            label="百分点变化不可计算",
            snapshot_id=snap_id,
            period_ref="baseline_to_current",
            scope_ref=scope,
            computable=False,
            incomputable_reason="missing_value",
            basis="ratio_endpoints",
        )]
    pp = (current - baseline) * 100 if abs(current) <= 1 and abs(baseline) <= 1 else current - baseline
    return [Fact(
        fact_id="percentage_point_change:overall",
        fact_type=FactType.PERCENTAGE_POINT_CHANGE,
        label=f"百分点变化 {pp:+g}pp",
        value=pp,
        unit="pp",
        snapshot_id=snap_id,
        period_ref="baseline_to_current",
        scope_ref=scope,
        computable=True,
        basis="ratio_with_components",
    )]


def _period_over_period(
    snap_id: str,
    values: list[float | None],
    categories: list[str],
    unit: str,
    scope: str,
) -> list[Fact]:
    facts: list[Fact] = []
    if len(values) != len(categories):
        return facts
    for i in range(1, len(values)):
        a, b = values[i - 1], values[i]
        fid = f"period_over_period:{categories[i - 1]}:{categories[i]}"
        if not _finite(a) or not _finite(b):
            facts.append(Fact(
                fact_id=fid,
                fact_type=FactType.PERIOD_OVER_PERIOD,
                label=f"{categories[i - 1]}→{categories[i]} 存在缺失",
                snapshot_id=snap_id,
                period_ref=f"{categories[i - 1]}:{categories[i]}",
                scope_ref=scope,
                computable=False,
                incomputable_reason="missing_value",
                basis="adjacent_observed_points",
            ))
            continue
        delta = float(b) - float(a)
        facts.append(Fact(
            fact_id=fid,
            fact_type=FactType.PERIOD_OVER_PERIOD,
            label=f"{categories[i - 1]}→{categories[i]} 变化 {delta:+g}{unit}",
            value=delta,
            unit=unit,
            snapshot_id=snap_id,
            period_ref=f"{categories[i - 1]}:{categories[i]}",
            scope_ref=scope,
            computable=True,
            basis="adjacent_delta",
            category=categories[i],
        ))
    return facts


def _month_key(label: str) -> tuple[int, int] | None:
    m = _MONTH_RE.match(str(label))
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def _has_continuity_gap(categories: list[str], explicit_gaps: Iterable[str]) -> bool:
    if list(explicit_gaps):
        return True
    keys = [_month_key(c) for c in categories]
    if any(k is None for k in keys) or len(keys) < 2:
        return False
    for i in range(1, len(keys)):
        y0, m0 = keys[i - 1]  # type: ignore[misc]
        y1, m1 = keys[i]  # type: ignore[misc]
        expected_m = m0 + 1
        expected_y = y0
        if expected_m > 12:
            expected_m = 1
            expected_y += 1
        if (y1, m1) != (expected_y, expected_m):
            return True
    return False


def _continuous_trend(
    snapshot: Snapshot,
    snap_id: str,
    values: list[float | None],
    categories: list[str],
    scope: str,
) -> list[Fact]:
    reason = None
    if any(not _finite(v) for v in values):
        reason = "missing_value"
    elif _has_continuity_gap(categories, snapshot.continuity_gaps):
        reason = "missing_period"
    elif len(values) < 2 or len(values) != len(categories):
        reason = "incomparable"
    if reason:
        return [Fact(
            fact_id="continuous_trend:overall",
            fact_type=FactType.CONTINUOUS_TREND,
            label="期间不连续或存在缺失，不输出持续涨跌",
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=False,
            incomputable_reason=reason,
            basis="requires_complete_contiguous_series",
        )]
    nums = [float(v) for v in values]  # type: ignore[arg-type]
    rising = all(nums[i] > nums[i - 1] for i in range(1, len(nums)))
    falling = all(nums[i] < nums[i - 1] for i in range(1, len(nums)))
    if not rising and not falling:
        return [Fact(
            fact_id="continuous_trend:overall",
            fact_type=FactType.CONTINUOUS_TREND,
            label="观测序列未呈现持续单向上升或下降",
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=False,
            incomputable_reason="not_monotonic",
            basis="strict_monotonic_check",
        )]
    return [Fact(
        fact_id="continuous_trend:overall",
        fact_type=FactType.CONTINUOUS_TREND,
        label="持续上升" if rising else "持续下降",
        value=1.0 if rising else -1.0,
        snapshot_id=snap_id,
        scope_ref=scope,
        computable=True,
        basis="strict_monotonic_contiguous",
    )]


def _tied_extremes(
    snap_id: str,
    values: list[float | None],
    categories: list[str],
    unit: str,
    scope: str,
) -> list[Fact]:
    pairs = [(categories[i], float(values[i])) for i in range(min(len(values), len(categories))) if _finite(values[i])]
    if not pairs:
        return [Fact(
            fact_id="tied_extreme:max",
            fact_type=FactType.TIED_EXTREME,
            label="无可用极值",
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=False,
            incomputable_reason="missing_value",
            extreme_kind="max",
            basis="observed_points_only",
        )]
    max_v = max(v for _, v in pairs)
    min_v = min(v for _, v in pairs)
    max_cats = [c for c, v in pairs if v == max_v]
    min_cats = [c for c, v in pairs if v == min_v]
    return [
        Fact(
            fact_id="tied_extreme:max",
            fact_type=FactType.TIED_EXTREME,
            label=f"峰值 {max_v:g}{unit} 出现在 {', '.join(max_cats)}",
            value=max_v,
            unit=unit,
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=True,
            extreme_kind="max",
            tied_categories=max_cats,
            basis="all_ties_kept",
        ),
        Fact(
            fact_id="tied_extreme:min",
            fact_type=FactType.TIED_EXTREME,
            label=f"低点 {min_v:g}{unit} 出现在 {', '.join(min_cats)}",
            value=min_v,
            unit=unit,
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=True,
            extreme_kind="min",
            tied_categories=min_cats,
            basis="all_ties_kept",
        ),
    ]


def _stock_sum_guard(
    context: InsightContext,
    snap_id: str,
    values: list[float | None],
    unit: str,
    scope: str,
) -> list[Fact]:
    if context.metric_kind != MetricKind.STOCK:
        return []
    return [Fact(
        fact_id="cross_period_sum:blocked",
        fact_type=FactType.CROSS_PERIOD_SUM,
        label="存量指标不可跨期求和",
        value=None,
        unit=unit,
        snapshot_id=snap_id,
        scope_ref=scope,
        computable=False,
        incomputable_reason="stock_no_cross_period_sum",
        basis="metric_kind=stock",
    )]


def _rank_share(snapshot: Snapshot, snap_id: str, unit: str, scope: str) -> list[Fact]:
    groups = snapshot.groups or []
    if not groups:
        return []
    total = snapshot.groups_total
    if total is None:
        total = sum(g.value for g in groups) if not snapshot.groups_truncated else None
    facts: list[Fact] = []
    ranked = sorted(groups, key=lambda g: (-g.value, g.group_id))
    for idx, g in enumerate(ranked, start=1):
        share = None
        computable = True
        reason = None
        if total is None or total == 0:
            computable = False
            reason = "share_denominator_unavailable" if total is None else "zero_total"
        else:
            share = g.value / float(total)
        facts.append(Fact(
            fact_id=f"rank_share:{g.group_id}",
            fact_type=FactType.RANK_SHARE,
            label=(
                f"第{idx}名 {g.group_name}（截断分组样本内）"
                if snapshot.groups_truncated
                else f"第{idx}名 {g.group_name}"
            ),
            value=share,
            unit="share",
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=computable,
            incomputable_reason=reason,
            groups_truncated=snapshot.groups_truncated,
            represents_full_population=not snapshot.groups_truncated,
            basis="group_value_over_available_total",
            category=g.group_name,
        ))
    return facts


def _department_contribution(
    context: InsightContext,
    snapshot: Snapshot,
    snap_id: str,
    unit: str,
    scope: str,
) -> list[Fact]:
    deps = snapshot.departments or []
    if not deps:
        return []
    facts: list[Fact] = []
    current = sum(d.current_count for d in deps)
    baseline = sum(d.baseline_count for d in deps)
    net = current - baseline
    increases = sum(max(0, d.current_count - d.baseline_count) for d in deps)
    decreases = sum(max(0, d.baseline_count - d.current_count) for d in deps)
    facts.append(Fact(
        fact_id="department_contribution:offset",
        fact_type=FactType.DEPARTMENT_CONTRIBUTION,
        label=f"部门增减抵消：上升 {increases}{unit}，下降 {decreases}{unit}，净变化 {net:+g}{unit}",
        value=float(min(increases, decreases)),
        unit=unit,
        snapshot_id=snap_id,
        scope_ref=scope,
        computable=True,
        contribution_kind="offset",
        basis="sum_department_deltas",
    ))
    if net == 0:
        facts.append(Fact(
            fact_id="department_contribution:net_rate",
            fact_type=FactType.DEPARTMENT_CONTRIBUTION,
            label="净变化为零，不计算净贡献率",
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=False,
            contribution_kind="net_rate",
            incomputable_reason="net_delta_zero",
            basis="net_delta_must_be_nonzero_for_rate",
        ))
    else:
        for d in deps:
            delta = d.current_count - d.baseline_count
            facts.append(Fact(
                fact_id=f"department_contribution:{d.org_id}",
                fact_type=FactType.DEPARTMENT_CONTRIBUTION,
                label=f"{d.org_name} 贡献 {delta:+d}{unit}",
                value=float(delta) / float(net),
                unit="share",
                snapshot_id=snap_id,
                scope_ref=scope,
                computable=True,
                contribution_kind="department_delta",
                basis="department_delta_over_net",
                category=str(d.org_id),
            ))
        facts.append(Fact(
            fact_id="department_contribution:net_rate",
            fact_type=FactType.DEPARTMENT_CONTRIBUTION,
            label="净贡献率可用（净变化非零）",
            value=1.0,
            unit="share",
            snapshot_id=snap_id,
            scope_ref=scope,
            computable=True,
            contribution_kind="net_rate",
            basis="net_delta_nonzero",
        ))
    return facts
