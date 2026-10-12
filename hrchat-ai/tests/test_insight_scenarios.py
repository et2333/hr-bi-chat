"""I4 insight scenarios: frozen dataset integrity, scoring rules and arm behavior."""
from __future__ import annotations

import asyncio
import copy
import json
import shutil
from pathlib import Path

import pytest

from evals.insight_compare import FixtureInsightAdapter
from evals.insight_oracle import ORACLE_VERSION, expected_facts
from evals.insight_scenarios import (
    DATASET,
    SCORING_VERSION,
    build_request,
    load_dataset,
    normalize_java_insight,
    score_ablation,
    score_cross_service,
    score_insight,
    score_transport,
    verify_oracle,
)
from insight.service import handle_insight_async

EXPECTED_SCENARIOS = ["rd_offset_flat", "small_base_change", "missing_month",
                      "ratio_insufficient", "partial_context"]


def _run(coro):
    return asyncio.run(coro)


def _llm_result(case):
    """Run the scripted model arm and round-trip through JSON (persisted + API shape)."""
    request = build_request(case["context"], case["snapshot"])
    result = _run(handle_insight_async({"context": case["context"], "snapshot": case["snapshot"]},
                                       FixtureInsightAdapter(request)))
    return json.loads(json.dumps(result, ensure_ascii=False))


def _template_result(case):
    return _run(handle_insight_async({"context": case["context"], "snapshot": case["snapshot"]}, None))


def test_dataset_integrity_and_hand_expectations():
    manifest, cases, ablations = load_dataset()
    assert [c["scenario"] for c in cases] == EXPECTED_SCENARIOS
    assert len(cases) == 5 and len(ablations) == 3
    assert manifest["version"] == "insight-scenarios-v1"
    assert SCORING_VERSION == "insight-scoring-4" and ORACLE_VERSION == "insight-oracle-1"


def test_oracle_covers_every_expected_fact_and_detects_drift():
    _, cases, _ = load_dataset()
    for case in cases:
        oracle = expected_facts(case["context"], case["snapshot"])
        assert set(case["expected"]["facts"]) <= set(oracle), case["case_id"]
    tampered = copy.deepcopy(cases[0])
    tampered["expected"]["facts"]["absolute_change:overall"]["computable"] = False
    with pytest.raises(ValueError, match="oracle disagreement"):
        verify_oracle(tampered)


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
        result = _llm_result(case)
        score = score_insight(case, result, "llm")
        assert score["ok"], (case["case_id"], score)
        assert score["coverage"]["missing"] == [], case["case_id"]
        for dimension in ("value_errors", "unit_errors", "period_errors", "scope_errors",
                          "context_errors", "visible_errors", "limitation_errors", "action_param_errors"):
            assert score[dimension] == [], (case["case_id"], dimension, score[dimension])


def test_scripted_llm_arm_spends_one_call_per_case():
    _, cases, _ = load_dataset()
    for case in cases:
        request = build_request(case["context"], case["snapshot"])
        adapter = FixtureInsightAdapter(request)
        _run(handle_insight_async({"context": case["context"], "snapshot": case["snapshot"]}, adapter))
        assert adapter.calls == 1, case["case_id"]


# --- C19: negative controls. Each fabricated or lost result must fail. ---

def _case(cases, case_id):
    return next(c for c in cases if c["case_id"] == case_id)


def test_negative_control_empty_claims_cannot_pass():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-02")
    result = _llm_result(case)
    result["claims"] = []
    result["narrative"] = None
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "complete_without_claims" for e in score["visible_errors"])


def test_negative_control_fabricated_values_are_detected():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-02")
    result = _llm_result(case)
    for fact in result["facts"]:
        if fact.get("value") is not None:
            fact["value"] = 999999
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert score["value_errors"], score
    assert all(e["issue"] == "value" for e in score["value_errors"])


def test_negative_control_wrong_unit_period_and_scope_are_detected():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-02")
    result = _llm_result(case)
    by_id = {f["fact_id"]: f for f in result["facts"]}
    by_id["point_compare:overall"]["unit"] = "kg"
    by_id["period_over_period:2026-01:2026-02"]["period_ref"] = "1999-01:1999-02"
    by_id["absolute_change:overall"]["scope_ref"] = "org:999"
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert score["unit_errors"] and score["period_errors"] and score["scope_errors"]


def test_negative_control_unrelated_reference_is_detected():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-02")
    result = _llm_result(case)
    result["claims"].append({"claim_id": "ghost", "kind": "fact", "text": "无关引用",
                             "fact_ids": ["ghost:fact"], "predicate": None})
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "unknown" for e in score["claim_ref_errors"])


def test_negative_control_illegal_action_parameter_is_detected():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    result = _llm_result(case)
    action = next(a for a in result["next_actions"] if a.get("executable"))
    action["params"]["current_period"] = {"start": "1999-01-01", "end": "1999-02-01"}
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e.get("param") == "current_period" for e in score["action_param_errors"])


def test_negative_control_degradation_is_not_complete_success():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    degraded = _template_result(case)
    baseline = score_insight(case, degraded, "template")
    assert baseline["degraded"] is True and baseline["ok"] is False
    # Re-labelling a degraded result as complete must not turn it into a pass.
    relabelled = copy.deepcopy(degraded)
    relabelled["status"] = "complete"
    score = score_insight(case, relabelled, "template")
    assert score["degraded"] is False and score["ok"] is False
    assert any(e["issue"] == "complete_without_claims" for e in score["visible_errors"])


# --- C20: score the persisted / API-serialised visible result, not hidden structure. ---

def test_final_visible_result_passes_only_when_narrative_reaches_the_page():
    _, cases, _ = load_dataset()
    for case in cases:
        score = score_insight(case, _llm_result(case), "llm")
        assert score["ok"], (case["case_id"], score)


def test_final_visible_result_detects_transport_loss_of_claims():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    result = _llm_result(case)
    result["claims"] = []
    result["narrative"] = None  # Java dropped the model body but kept the template summary
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "complete_without_claims" for e in score["visible_errors"])


def test_final_visible_result_detects_template_text_over_model_narrative():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    result = _llm_result(case)
    result["summary"] = "报告「研发两期持平抵消」指标离职人数：模板摘要"  # claims kept, visible text lost
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "visible_summary_not_from_narrative" for e in score["visible_errors"])
    result["points"][0]["label"] = "模板要点"
    score = score_insight(case, result, "llm")
    assert any(e["issue"] == "point_not_from_claim" for e in score["visible_errors"])


def test_final_visible_result_detects_dropped_action_and_evidence_mapping():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-03")
    result = _llm_result(case)
    result["next_actions"] = []
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "missing_executable_action" for e in score["action_param_errors"])


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


# --- C21: the visible body must be re-derived from evidence, never from model-typed text. ---

def test_negative_control_fabricated_body_with_correct_facts_cannot_pass():
    """The A4 negative: keep every fact/reference/action correct, replace all visible text."""
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-02")
    result = _llm_result(case)
    fabricated = "999999 人离职，因为管理失败"
    for claim in result["claims"]:
        claim["text"] = fabricated
    result["summary"] = fabricated
    for point in result["points"]:
        if point.get("type") == "finding":
            point["label"] = fabricated
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "claim_text_not_from_segments" for e in score["visible_errors"])
    assert any(e["issue"] == "visible_summary_not_from_narrative" for e in score["visible_errors"])
    assert any(e["issue"] == "point_not_from_claim" for e in score["visible_errors"])


def test_negative_control_typed_number_in_text_segment_cannot_pass():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-02")
    result = _llm_result(case)
    result["claims"][0]["segments"] = [{"type": "text", "text": "离职 999999 人"}]
    result["claims"][0]["text"] = "离职 999999 人"
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "fabricated_number_in_text" for e in score["visible_errors"])


def test_negative_control_unbound_fact_slot_cannot_pass():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-02")
    result = _llm_result(case)
    result["claims"][0]["segments"] = [{"type": "fact", "fact_id": "ghost:fact", "field": "value"}]
    result["claims"][0]["text"] = "重点关注："
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "segment_unknown_fact" for e in score["visible_errors"])


# --- C22: score the cross-service output (Java POST → persisted → GET shape), not Python memory. ---

def _java_view(result):
    """Project the Python result into the shape the Java GET ``InsightView`` returns."""
    return {
        "reportName": result.get("report_name", ""),
        "metricName": result.get("metric_name", ""),
        "summary": result.get("summary", ""),
        "points": list(result.get("points") or []),
        "status": result.get("status"),
        "degradationReason": result.get("degradation_reason"),
        "dataVersion": result.get("data_version"),
        "viewKey": result.get("view_key"),
        "limitations": list(result.get("limitations") or []),
        "actions": [{"actionId": a.get("action_id"), "capabilityId": a.get("capability_id"),
                     "question": a.get("question", ""), "executable": a.get("executable"),
                     "params": a.get("params") or {}, "missingParams": a.get("missing_params") or []}
                    for a in result.get("next_actions") or []],
        "source": {"snapshotId": result.get("snapshot_id")},
        "schemaVersion": result.get("schema_version"),
        "claims": list(result.get("claims") or []),
        "narrative": result.get("narrative"),
        "facts": list(result.get("facts") or []),
    }


def test_cross_service_normalises_java_shape_and_passes():
    _, cases, _ = load_dataset()
    for case in cases:
        score = score_cross_service(case, _java_view(_llm_result(case)))
        assert score["ok"], (case["case_id"], score)
        assert score["transport"] == "java_persisted_get"
        assert score["action_param_errors"] == []
        assert score["visible_errors"] == []


def test_cross_service_maps_actions_and_source_identity():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    view = _java_view(_llm_result(case))
    normalized = normalize_java_insight(view)
    assert normalized["next_actions"] and view["actions"]
    assert normalized["next_actions"][0]["capability_id"] == view["actions"][0]["capabilityId"]
    assert normalized["snapshot_id"] == view["source"]["snapshotId"]
    assert normalized["schema_version"] == "2"


def test_cross_service_rejects_transport_loss_hidden_by_python_shape():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    view = _java_view(_llm_result(case))
    view["claims"] = []  # Java persisted the summary but dropped the model body
    view["narrative"] = None
    score = score_cross_service(case, view)
    assert score["ok"] is False
    assert any(e["issue"] == "complete_without_claims" for e in score["visible_errors"])


# --- C23: case-free transport integrity scoring for a live Java GET payload. ---

def test_transport_score_reconstructs_visible_body_and_catches_fabrication():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    result = _llm_result(case)
    score = score_transport(result)
    assert score["ok"] is True, score
    assert score["claim_count"] > 0 and score["fact_count"] > 0
    assert score["reference_errors"] == [] and score["visible_errors"] == []


def test_transport_score_rejects_typed_body_even_when_facts_survive():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    result = _llm_result(case)
    result["summary"] = "999999 人离职，因为管理失败"  # body typed, facts untouched
    score = score_transport(result)
    assert score["ok"] is False
    assert any(e["issue"] == "visible_summary_not_from_narrative" for e in score["visible_errors"])


def test_transport_score_rejects_dangling_fact_reference():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    result = _llm_result(case)
    result["claims"][0]["fact_ids"] = ["ghost:fact"]
    score = score_transport(result)
    assert score["ok"] is False
    assert any(e["fact_id"] == "ghost:fact" for e in score["reference_errors"])


# --- C24 (B3): top-level limitations are model-generated visible text, not a safe free channel. ---

def test_valid_result_limitations_pass_the_free_text_rule():
    """The scripted arm's controlled ``fact_type:reason`` limitations must stay legal."""
    _, cases, _ = load_dataset()
    for case in cases:
        score = score_insight(case, _llm_result(case), "llm")
        assert score["ok"] and score["visible_errors"] == [], case["case_id"]


def test_negative_control_fabricated_limitation_cannot_pass():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-03")
    result = _llm_result(case)
    # Keep every valid fact, reference, claim, narrative and action: only top-level limitations
    # gains a fabricated sentence, which used to be rendered on the card unchecked.
    result["limitations"] = list(result.get("limitations") or []) + [
        "999999 people because management failed"]
    score = score_insight(case, result, "llm")
    assert score["ok"] is False
    assert any(e["issue"] == "limitation_typed_number" for e in score["visible_errors"])


def test_transport_score_rejects_causal_limitation_without_numbers():
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-01")
    result = _llm_result(case)
    result["limitations"] = ["本期下降因为管理失败"]
    score = score_transport(result)
    assert score["ok"] is False
    assert any(e["issue"] == "limitation_unsupported_causality" for e in score["visible_errors"])


def test_structured_limitation_is_required_for_disclosure():
    """受控格式「fact_type:补充说明」才被披露检查识别；纯自然语言局限会被判未披露（prompt-7 契约）。"""
    _, cases, _ = load_dataset()
    case = _case(cases, "IS-03")
    structured = _llm_result(case)
    structured["limitations"] = ["continuous_trend:时间轴缺失，无法判定持续趋势"]
    assert score_insight(case, structured, "llm")["limitation_errors"] == []

    natural = _llm_result(case)
    natural["limitations"] = ["时间轴缺失，无法判定持续趋势"]
    assert score_insight(case, natural, "llm")["limitation_errors"] != []
