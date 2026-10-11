"""Publish the frozen insight-scenario dataset; never run on import.

Five fixed simulated business scenarios with hand-written correct facts,
forbidden conclusions and the expected executable action. The expectations are
authored independently of the production FactBuilder/prompt and re-checked by
tests/test_insight_scenarios.py against the builder output.

After publication, change the dataset version before changing frozen cases.
"""
import json

from evals.dataset import sha_text
from evals.insight_scenarios import DATASET


def _context(**over):
    base = {
        "schema_version": "1",
        "report_id": "r",
        "metric_code": "leave_count",
        "metric_version": "1",
        "metric_name": "离职人数",
        "report_name": "场景",
        "unit": "人",
        "metric_kind": "flow",
        "axis_kind": "time",
        "current_period": {"start": "2026-03-01", "end": "2026-04-01"},
        "baseline_period": {"start": "2026-02-01", "end": "2026-03-01"},
        "effective_org_ids": [10],
        "data_version": "dv-is-v1",
        "view_key": "month|org:10",
        "scope_ref": "org:10",
    }
    base.update(over)
    return base


def _snapshot(**over):
    base = {
        "snapshot_id": "snap",
        "data_version": "dv-is-v1",
        "view_key": "month|org:10",
        "categories": ["2026-02", "2026-03"],
        "series": [{"name": "离职人数", "data": [10, 10]}],
        "groups_truncated": False,
    }
    base.update(over)
    return base


def _fact(fact_type, computable, preferred=False):
    return {"fact_type": fact_type, "computable": computable, "preferred": preferred}


def build_cases():
    cases = []

    # IS-01 研发两期总量持平、部门增减抵消（flow 10→10，部门 -1/+1）。
    cases.append({
        "case_id": "IS-01", "split": "dev", "scenario": "rd_offset_flat",
        "context": _context(report_id="r-offset", report_name="研发两期持平抵消",
                            effective_org_ids=[3, 4], view_key="dept|rd", scope_ref="org:3,4"),
        "snapshot": _snapshot(snapshot_id="snap-is-01", view_key="dept|rd",
                              categories=["2026-02", "2026-03"],
                              series=[{"name": "离职人数", "data": [10, 10]}],
                              departments=[{"org_id": 3, "org_name": "研发一部", "baseline_count": 6, "current_count": 5},
                                           {"org_id": 4, "org_name": "研发二部", "baseline_count": 4, "current_count": 5}]),
        "expected": {
            "facts": {
                "absolute_change:overall": _fact("absolute_change", True, True),
                "relative_change:overall": _fact("relative_change", True, False),
                "point_compare:overall": _fact("point_compare", True, False),
                "period_over_period:2026-02:2026-03": _fact("period_over_period", True, False),
                "continuous_trend:overall": _fact("continuous_trend", False),
                "tied_extreme:max": _fact("tied_extreme", True, False),
                "tied_extreme:min": _fact("tied_extreme", True, False),
                "department_contribution:offset": _fact("department_contribution", True, False),
                "department_contribution:net_rate": _fact("department_contribution", False),
            },
            "must_cover_fact_types": ["department_contribution", "absolute_change"],
            "forbidden_predicates": ["department_net_contribution", "relative_increase", "relative_decrease",
                                     "absolute_increase", "absolute_decrease"],
            "forbidden_claim_kinds": [],
            "forbidden_fact_ids": [],
            "action": {"capability_id": "leave_count_department_contribution", "executable": True},
        },
    })

    # IS-02 小基数变化 1→2：绝对变化优先，禁止反向断言。
    cases.append({
        "case_id": "IS-02", "split": "dev", "scenario": "small_base_change",
        "context": _context(report_id="r-small", metric_code="headcount", metric_name="在职人数",
                            report_name="小基数变化", unit="人", metric_kind="stock",
                            current_period={"start": "2026-02-01", "end": "2026-03-01"},
                            baseline_period={"start": "2026-01-01", "end": "2026-02-01"}),
        "snapshot": _snapshot(snapshot_id="snap-is-02",
                              categories=["2026-01", "2026-02"],
                              series=[{"name": "在职人数", "data": [1, 2]}]),
        "expected": {
            "facts": {
                "absolute_change:overall": _fact("absolute_change", True, True),
                "relative_change:overall": _fact("relative_change", True, False),
                "point_compare:overall": _fact("point_compare", True, False),
                "period_over_period:2026-01:2026-02": _fact("period_over_period", True, False),
                "continuous_trend:overall": _fact("continuous_trend", True, False),
                "tied_extreme:max": _fact("tied_extreme", True, False),
                "tied_extreme:min": _fact("tied_extreme", True, False),
                "cross_period_sum:blocked": _fact("cross_period_sum", False),
            },
            "must_cover_fact_types": ["absolute_change"],
            "forbidden_predicates": ["relative_decrease", "absolute_decrease"],
            "forbidden_claim_kinds": [],
            "forbidden_fact_ids": [],
            "action": {"capability_id": "manual_review", "executable": False},
        },
    })

    # IS-03 缺失月份：不得输出持续趋势。
    cases.append({
        "case_id": "IS-03", "split": "dev", "scenario": "missing_month",
        "context": _context(report_id="r-mm", report_name="缺月序列",
                            current_period={"start": "2026-04-01", "end": "2026-05-01"},
                            baseline_period={"start": "2026-01-01", "end": "2026-02-01"}),
        "snapshot": _snapshot(snapshot_id="snap-is-03",
                              categories=["2026-01", "2026-03", "2026-04"],
                              series=[{"name": "离职人数", "data": [2, 5, 8]}],
                              continuity_gaps=["2026-02"]),
        "expected": {
            "facts": {
                "absolute_change:overall": _fact("absolute_change", True, True),
                "relative_change:overall": _fact("relative_change", True, False),
                "point_compare:overall": _fact("point_compare", True, False),
                "period_over_period:2026-01:2026-03": _fact("period_over_period", True, False),
                "period_over_period:2026-03:2026-04": _fact("period_over_period", True, False),
                "continuous_trend:overall": _fact("continuous_trend", False),
                "tied_extreme:max": _fact("tied_extreme", True, False),
                "tied_extreme:min": _fact("tied_extreme", True, False),
            },
            "must_cover_fact_types": ["absolute_change"],
            "forbidden_predicates": ["relative_decrease", "absolute_decrease"],
            "forbidden_claim_kinds": [],
            "forbidden_fact_ids": ["continuous_trend:overall"],
            "action": {"capability_id": "leave_count_department_contribution", "executable": True},
        },
    })

    # IS-04 比率口径不足：缺分子/分母，不得输出百分点结论。
    cases.append({
        "case_id": "IS-04", "split": "frozen", "scenario": "ratio_insufficient",
        "context": _context(report_id="r-ratio", metric_code="leave_rate", metric_name="离职率",
                            report_name="离职率对比", unit="%", metric_kind="ratio", ratio_scale="percent",
                            current_period={"start": "2026-02-01", "end": "2026-03-01"},
                            baseline_period={"start": "2026-01-01", "end": "2026-02-01"}),
        "snapshot": _snapshot(snapshot_id="snap-is-04",
                              categories=["2026-01", "2026-02"],
                              series=[{"name": "离职率", "data": [0.2, 0.25]}]),
        "expected": {
            "facts": {
                "absolute_change:overall": _fact("absolute_change", True, False),
                "relative_change:overall": _fact("relative_change", True, True),
                "point_compare:overall": _fact("point_compare", True, False),
                "percentage_point_change:overall": _fact("percentage_point_change", False),
                "period_over_period:2026-01:2026-02": _fact("period_over_period", True, False),
                "continuous_trend:overall": _fact("continuous_trend", True, False),
                "tied_extreme:max": _fact("tied_extreme", True, False),
                "tied_extreme:min": _fact("tied_extreme", True, False),
            },
            "must_cover_fact_types": ["relative_change"],
            "forbidden_predicates": ["point_higher", "point_lower"],
            "forbidden_claim_kinds": [],
            "forbidden_fact_ids": ["percentage_point_change:overall"],
            "action": {"capability_id": "manual_review", "executable": False},
        },
    })

    # IS-05 来源上下文不完整：缺基期、单点，比较类事实不可计算。
    cases.append({
        "case_id": "IS-05", "split": "frozen", "scenario": "partial_context",
        "context": _context(report_id="r-partial", report_name="上下文不完整",
                            baseline_period=None, effective_org_ids=[3],
                            view_key="dept|org:3", scope_ref="org:3"),
        "snapshot": _snapshot(snapshot_id="snap-is-05", view_key="dept|org:3",
                              categories=["2026-03"],
                              series=[{"name": "离职人数", "data": [5]}]),
        "expected": {
            "facts": {
                "absolute_change:overall": _fact("absolute_change", False),
                "relative_change:overall": _fact("relative_change", False),
                "point_compare:overall": _fact("point_compare", False),
                "continuous_trend:overall": _fact("continuous_trend", False),
                "tied_extreme:max": _fact("tied_extreme", True, False),
                "tied_extreme:min": _fact("tied_extreme", True, False),
            },
            "must_cover_fact_types": [],
            "forbidden_predicates": ["absolute_increase", "absolute_decrease", "relative_increase",
                                     "relative_decrease", "point_higher", "point_lower"],
            "forbidden_claim_kinds": [],
            "forbidden_fact_ids": ["absolute_change:overall", "relative_change:overall",
                                   "point_compare:overall", "continuous_trend:overall"],
            "action": {"capability_id": "leave_count_department_contribution", "executable": False},
        },
    })
    return cases


def build_ablations():
    """Group B: partial vs full context, one variable isolated per pair."""
    ab_01 = {
        "ablation_id": "AB-01", "scenario": "baseline_absent",
        "context_full": _context(report_id="r-offset", report_name="研发两期持平抵消",
                                 effective_org_ids=[3, 4], view_key="dept|rd", scope_ref="org:3,4"),
        "snapshot_full": _snapshot(snapshot_id="snap-is-01", view_key="dept|rd",
                                   categories=["2026-02", "2026-03"],
                                   series=[{"name": "离职人数", "data": [10, 10]}],
                                   departments=[{"org_id": 3, "org_name": "研发一部", "baseline_count": 6, "current_count": 5},
                                                {"org_id": 4, "org_name": "研发二部", "baseline_count": 4, "current_count": 5}]),
        "context_partial": _context(report_id="r-offset", report_name="研发两期持平抵消",
                                    effective_org_ids=[3, 4], view_key="dept|rd", scope_ref="org:3,4",
                                    baseline_period=None),
        "snapshot_partial": _snapshot(snapshot_id="snap-is-01", view_key="dept|rd",
                                      categories=["2026-03"],
                                      series=[{"name": "离职人数", "data": [10]}],
                                      departments=[{"org_id": 3, "org_name": "研发一部", "baseline_count": 6, "current_count": 5},
                                                   {"org_id": 4, "org_name": "研发二部", "baseline_count": 4, "current_count": 5}]),
        "expected": {
            "full_computable": ["absolute_change:overall", "relative_change:overall", "department_contribution:offset"],
            "partial_incomputable": ["absolute_change:overall", "relative_change:overall",
                                     "point_compare:overall", "continuous_trend:overall",
                                     "department_contribution:blocked"],
            "partial_absent_fact_ids": [],
        },
    }
    ab_02 = {
        "ablation_id": "AB-02", "scenario": "departments_absent",
        "context_full": _context(report_id="r-offset", report_name="研发两期持平抵消",
                                 effective_org_ids=[3, 4], view_key="dept|rd", scope_ref="org:3,4"),
        "snapshot_full": _snapshot(snapshot_id="snap-is-01", view_key="dept|rd",
                                   categories=["2026-02", "2026-03"],
                                   series=[{"name": "离职人数", "data": [10, 10]}],
                                   departments=[{"org_id": 3, "org_name": "研发一部", "baseline_count": 6, "current_count": 5},
                                                {"org_id": 4, "org_name": "研发二部", "baseline_count": 4, "current_count": 5}]),
        "context_partial": _context(report_id="r-offset", report_name="研发两期持平抵消",
                                    effective_org_ids=[3, 4], view_key="dept|rd", scope_ref="org:3,4"),
        "snapshot_partial": _snapshot(snapshot_id="snap-is-01", view_key="dept|rd",
                                      categories=["2026-02", "2026-03"],
                                      series=[{"name": "离职人数", "data": [10, 10]}]),
        "expected": {
            "full_computable": ["department_contribution:offset"],
            "partial_incomputable": [],
            "partial_absent_fact_ids": ["department_contribution:offset", "department_contribution:net_rate"],
        },
    }
    ab_03 = {
        "ablation_id": "AB-03", "scenario": "ratio_components",
        "context_full": _context(report_id="r-ratio", metric_code="leave_rate", metric_name="离职率",
                                 report_name="离职率对比", unit="%", metric_kind="ratio", ratio_scale="percent",
                                 current_period={"start": "2026-02-01", "end": "2026-03-01"},
                                 baseline_period={"start": "2026-01-01", "end": "2026-02-01"},
                                 numerator_metric_code="leave_count", denominator_metric_code="headcount"),
        "snapshot_full": _snapshot(snapshot_id="snap-is-04",
                                   categories=["2026-01", "2026-02"],
                                   series=[{"name": "离职率", "data": [0.2, 0.25]}]),
        "context_partial": _context(report_id="r-ratio", metric_code="leave_rate", metric_name="离职率",
                                    report_name="离职率对比", unit="%", metric_kind="ratio", ratio_scale="percent",
                                    current_period={"start": "2026-02-01", "end": "2026-03-01"},
                                    baseline_period={"start": "2026-01-01", "end": "2026-02-01"}),
        "snapshot_partial": _snapshot(snapshot_id="snap-is-04",
                                      categories=["2026-01", "2026-02"],
                                      series=[{"name": "离职率", "data": [0.2, 0.25]}]),
        "expected": {
            "full_computable": ["percentage_point_change:overall"],
            "partial_incomputable": ["percentage_point_change:overall"],
            "partial_absent_fact_ids": [],
        },
    }
    return [ab_01, ab_02, ab_03]


def main():
    if (DATASET / "cases.json").exists() or (DATASET / "manifest.json").exists():
        raise FileExistsError("Published insight dataset must not be overwritten; publish a new version instead")
    DATASET.mkdir(parents=True, exist_ok=True)
    cases = build_cases()
    ablations = build_ablations()
    (DATASET / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (DATASET / "ablations.json").write_text(json.dumps(ablations, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "schema_version": "1", "version": "insight-scenarios-v1",
        "case_count": len(cases), "ablation_count": len(ablations),
        "cases_sha256": sha_text(DATASET / "cases.json"),
        "ablations_sha256": sha_text(DATASET / "ablations.json"),
        "splits": {split: sum(c["split"] == split for c in cases) for split in ("dev", "frozen")},
        "scenarios": [c["scenario"] for c in cases],
        "scope": "light-weight insight over a trusted snapshot; single metric, no causal inference",
        "limits": {"max_model_calls": 2, "timeout_seconds": 60},
        "expectation_note": "Expected facts are hand-audited from the metric semantics, not copied from the production builder; tests re-check builder agreement.",
        "privacy_note": "Synthetic HR aggregates only; no real employee or tenant data.",
    }
    (DATASET / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
