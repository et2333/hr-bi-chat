"""FactBuilder：可信快照 → FactBundle 边界行为。"""
from __future__ import annotations

import json
from pathlib import Path

from insight.fact_builder import build_fact_bundle
from insight.schema import FactType, InsightRequest, MetricKind

FIXTURES = Path(__file__).parent / "fixtures" / "insight"


def _load(name: str) -> InsightRequest:
    raw = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    return InsightRequest.model_validate(raw)


def _by_type(bundle, fact_type: FactType):
    return [f for f in bundle.facts if f.fact_type == fact_type]


def test_headcount_prefers_absolute_change_plus_one():
    req = _load("headcount_two_period.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    abs_facts = _by_type(bundle, FactType.ABSOLUTE_CHANGE)
    assert abs_facts, "应产出绝对变化事实"
    preferred = abs_facts[0]
    assert preferred.value == 1.0
    assert preferred.unit == "人"
    assert preferred.preferred is True
    rel = _by_type(bundle, FactType.RELATIVE_CHANGE)
    assert rel and rel[0].preferred is False


def test_zero_baseline_no_growth_rate():
    req = _load("zero_baseline.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    rel = _by_type(bundle, FactType.RELATIVE_CHANGE)
    assert rel, "应记录相对变化不可计算"
    assert rel[0].computable is False
    assert rel[0].incomputable_reason == "zero_baseline"
    assert rel[0].value is None
    abs_facts = _by_type(bundle, FactType.ABSOLUTE_CHANGE)
    assert abs_facts and abs_facts[0].value == 5.0 and abs_facts[0].computable is True


def test_stock_metric_no_cross_period_sum():
    req = _load("headcount_two_period.json")
    assert req.context.metric_kind == MetricKind.STOCK
    bundle = build_fact_bundle(req.context, req.snapshot)
    assert not any(f.fact_type == FactType.CROSS_PERIOD_SUM and f.computable for f in bundle.facts)
    blocked = [f for f in bundle.facts if f.incomputable_reason == "stock_no_cross_period_sum"]
    assert blocked, "存量指标应显式拒绝跨期求和"
    assert all(f.computable is False for f in blocked)


def test_missing_month_no_continuous_rise():
    req = _load("missing_month.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    cont = _by_type(bundle, FactType.CONTINUOUS_TREND)
    assert cont and cont[0].computable is False
    assert cont[0].incomputable_reason == "missing_period"
    pop = _by_type(bundle, FactType.PERIOD_OVER_PERIOD)
    assert any(f.computable for f in pop), "逐期变化仍可对相邻已观测点计算"


def test_tied_extremes_include_both():
    req = _load("tied_extremes.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    highs = [f for f in bundle.facts if f.fact_type == FactType.TIED_EXTREME and f.extreme_kind == "max"]
    assert len(highs) == 1
    assert set(highs[0].tied_categories) == {"2026-01", "2026-03"}
    assert highs[0].value == 9.0


def test_truncated_groups_not_claimed_as_full():
    req = _load("truncated_groups.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    shares = _by_type(bundle, FactType.RANK_SHARE)
    assert shares
    assert all(f.groups_truncated is True for f in shares)
    assert all(f.represents_full_population is False for f in shares)
    assert not any("全体" in (f.label or "") for f in shares)


def test_net_zero_dept_change_no_net_contribution_rate():
    req = _load("dept_net_zero.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    net_rate = [
        f for f in bundle.facts
        if f.fact_type == FactType.DEPARTMENT_CONTRIBUTION and f.contribution_kind == "net_rate"
    ]
    assert net_rate and all(f.computable is False for f in net_rate)
    assert all(f.incomputable_reason == "net_delta_zero" for f in net_rate)
    offset = [
        f for f in bundle.facts
        if f.fact_type == FactType.DEPARTMENT_CONTRIBUTION and f.contribution_kind == "offset"
    ]
    assert offset and offset[0].computable is True


def test_null_in_series_not_treated_as_zero():
    req = _load("series_with_nulls.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    abs_across = [
        f for f in bundle.facts
        if f.fact_type == FactType.ABSOLUTE_CHANGE and f.period_ref == "baseline_to_current"
    ]
    # 基期 100、本期 120，中间 null 不应被当成 0 参与跨期绝对变化
    assert abs_across and abs_across[0].value == 20.0
    cont = _by_type(bundle, FactType.CONTINUOUS_TREND)
    assert cont and cont[0].computable is False
    assert cont[0].incomputable_reason in {"missing_value", "missing_period"}


def test_ratio_without_components_not_computable():
    from insight.schema import InsightContext, Period, Snapshot, MetricKind

    ctx = InsightContext(
        schema_version="1",
        report_id="r-ratio",
        metric_code="leave_rate",
        metric_version="1",
        metric_name="离职率",
        report_name="比率",
        unit="%",
        metric_kind=MetricKind.RATIO,
        current_period=Period(start="2026-02-01", end="2026-03-01"),
        baseline_period=Period(start="2026-01-01", end="2026-02-01"),
        effective_org_ids=[1],
        data_version="dv-r",
        view_key="v",
        scope_ref="org:1",
    )
    snap = Snapshot(
        snapshot_id="s",
        data_version="dv-r",
        view_key="v",
        categories=["2026-01", "2026-02"],
        series=[{"name": "离职率", "data": [0.1, 0.12]}],
    )
    bundle = build_fact_bundle(ctx, snap)
    pp = _by_type(bundle, FactType.PERCENTAGE_POINT_CHANGE)
    assert pp and pp[0].computable is False
    assert pp[0].incomputable_reason == "ratio_components_missing"
