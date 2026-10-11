"""I4 insight scenarios: frozen dataset integrity, scoring rules and arm behavior."""
from __future__ import annotations

import asyncio
import json
import shutil
from pathlib import Path

import pytest

from evals.insight_compare import FixtureInsightAdapter
from evals.insight_scenarios import (
    DATASET,
    build_request,
    load_dataset,
    score_ablation,
    score_insight,
)
from insight.service import handle_insight_async

EXPECTED_SCENARIOS = ["rd_offset_flat", "small_base_change", "missing_month",
                      "ratio_insufficient", "partial_context"]


def _run(coro):
    return asyncio.run(coro)


def test_dataset_integrity_and_hand_expectations():
    manifest, cases, ablations = load_dataset()
    assert [c["scenario"] for c in cases] == EXPECTED_SCENARIOS
    assert len(cases) == 5 and len(ablations) == 3
    assert manifest["version"] == "insight-scenarios-v1"


def test_load_rejects_tampered_cases(tmp_path):
    target = tmp_path / "insight-scenarios-v1"
    shutil.copytree(DATASET, target)
    cases = json.loads((target / "cases.json").read_text(encoding="utf-8"))
    cases[0]["split"] = "frozen"
    (target / "cases.json").write_text(json.dumps(cases, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(ValueError):
        load_dataset(target)


def test_template_arm_never_fabricates_and_recommends_expected_action():
    _, cases, _ = load_dataset()
    for case in cases:
        result = _run(handle_insight_async({"context": case["context"], "snapshot": case["snapshot"]}, None))
        score = score_insight(case, result, "template")
        assert score["fact_drift"] == [], case["case_id"]
        assert score["claim_ref_errors"] == [], case["case_id"]
        assert score["forbidden_conclusions"] == [], case["case_id"]
        assert score["action"]["matched"], case["case_id"]
        # Template arm produces no claims, so it cannot cover interpretation targets.
        assert score["coverage"]["numerator"] == 0
        assert score["coverage"]["denominator"] == len(case["expected"]["must_cover_fact_types"])


def test_scripted_llm_arm_covers_and_passes():
    _, cases, _ = load_dataset()
    for case in cases:
        request = build_request(case["context"], case["snapshot"])
        adapter = FixtureInsightAdapter(request)
        result = _run(handle_insight_async({"context": case["context"], "snapshot": case["snapshot"]}, adapter))
        score = score_insight(case, result, "llm")
        assert score["ok"], (case["case_id"], score)
        assert score["coverage"]["missing"] == [], case["case_id"]
        assert adapter.calls == 1


def test_ablation_arms_degrade_instead_of_asserting_removed_evidence():
    _, _, ablations = load_dataset()
    for ablation in ablations:
        full_request = build_request(ablation["context_full"], ablation["snapshot_full"])
        partial_request = build_request(ablation["context_partial"], ablation["snapshot_partial"])
        full = _run(handle_insight_async({"context": ablation["context_full"], "snapshot": ablation["snapshot_full"]},
                                         FixtureInsightAdapter(full_request)))
        partial = _run(handle_insight_async({"context": ablation["context_partial"], "snapshot": ablation["snapshot_partial"]},
                                            FixtureInsightAdapter(partial_request)))
        score = score_ablation(ablation, full, partial)
        assert score["ok"], (ablation["ablation_id"], score)


def test_scoring_flags_forbidden_fabrication_and_drift():
    _, cases, _ = load_dataset()
    case = next(c for c in cases if c["case_id"] == "IS-01")
    result = {
        "status": "degraded",
        "facts": [
            {"fact_id": "continuous_trend:overall", "fact_type": "continuous_trend", "computable": False},
            {"fact_id": "absolute_change:overall", "fact_type": "absolute_change", "computable": True},
        ],
        "claims": [
            {"claim_id": "c1", "kind": "fact", "fact_ids": ["continuous_trend:overall"], "predicate": None},
            {"claim_id": "c2", "kind": "statistical_contribution",
             "fact_ids": ["absolute_change:overall"], "predicate": "department_net_contribution"},
        ],
        "next_actions": [],
    }
    score = score_insight(case, result, "llm")
    assert any(d["fact_id"] == "tied_extreme:max" and d["issue"] == "missing" for d in score["fact_drift"])
    assert any(e["issue"] == "cites_incomputable_evidence" for e in score["claim_ref_errors"])
    assert any(f["reason"] == "department_net_contribution" for f in score["forbidden_conclusions"])
    assert score["ok"] is False
