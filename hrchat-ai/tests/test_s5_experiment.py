from copy import deepcopy

import pytest

from evals.memory_ablation import ablated_flow, without_history
from evals.s5_compare import compare_pair, metrics
from langgraph_flows.ask_flow import run_ask_flow
from tests.test_query_draft import DraftPlanner
from tests.test_query_memory import BASE, memory
from tests.test_query_plan import Tools


async def test_history_ablation_changes_followup_only_and_preserves_prompt_and_identity():
    snapshot = memory(BASE)
    snapshot["pending"] = {"plan": BASE}
    snapshot["selection"] = {"question_id": "old"}
    stripped = without_history(snapshot)
    assert stripped == {"schema_version": "1", "context_version": 10}
    assert "confirmed" in snapshot and "pending" in snapshot
    snapshot.pop("pending"); snapshot.pop("selection")
    results, prompts = [], []
    for flow in (run_ask_flow, ablated_flow(run_ask_flow)):
        adapter = DraftPlanner({"decision": "execute", "time_expression": "本月"})
        result = await flow(question="那本月呢？", session_id="s", ask_id="turn", query_context=deepcopy(snapshot),
                            tools=Tools(), adapter=adapter, invocation_id="fresh", tool_context_token="fresh-token")
        results.append(result); prompts.append(adapter.prompts)
    assert prompts[0] == prompts[1]
    assert results[0]["answer_payload"] and results[0]["evidence"]["execution"]
    assert results[1]["clarify_questions"] and not results[1]["evidence"]["execution"]
    assert results[1]["evidence"]["experiment"]["memory_enabled"] is False
    assert results[1]["evidence"]["query_context_candidate"]["context_version"] == 10


def report(*, enabled=True, passed=True):
    row = {"case_id": "a", "scene": "multi_turn", "turns": []}
    for ok in (True, passed):
        row["turns"].append({"stage_score": {"passed": ok, "errors": [] if ok else ["terminal_state"]},
            "actual": {"elapsed_ms": 100, "evidence": {"experiment": {"memory_enabled": enabled},
                "prompt_version": "same", "decoding": {"temperature": 0}, "model_calls": [], "repair": {"attempts": 0}}}})
    return {"run_id": str(enabled), "runtime": "remote", "model_kind": "real", "selected_case_ids": ["a"],
        "summary": {"status": "COMPLETED", "executed_turns": 2, "planned_turns": 2},
        "dataset": {"cases_sha256": "x", "migration_sha256": {"v1": "y"}, "as_of_date": "2026-09-28", "data_version": "v6", "timezone": "Asia/Shanghai"},
        "stage_evaluation": {"sha256": "same-policy"}, "server": {"jar_sha256": "jar", "source_sha256": "source",
            "org_catalog_scope": "authorized", "as_of_date": "2026-09-28", "data_version": "v6", "timezone": "Asia/Shanghai",
            "memory_enabled": enabled, "requested_model": "qwen-plus", "repair_enabled": True, "planner_variant": "baseline"},
        "results": [row]}


def test_paired_tasks_use_whole_sequence_not_successful_first_turn():
    result = compare_pair(report(enabled=False, passed=False), report(), kind="memory_ablation", ids=["a"])
    assert result["baseline"]["passed"] == 0 and result["baseline"]["first_turn_passed"] == 1
    assert result["full"]["passed"] == 1 and result["paired_changes"]["fixed"] == ["a"]


def test_public_score_separates_diagnostics_without_relaxing_stage_or_response_checks():
    current = report()
    score = current["results"][0]["turns"][0]["stage_score"]
    score.update(passed=False, errors=["stop_reason", "unexpected_query_or_missing_trace"],
                 override_reason="missing time", no_query_verified=False)
    result = metrics(current, ["a"])
    assert result["passed"] == 0 and result["public_task_passed"] == 1
    assert result["no_query_verification"]["failed"] == 1
    score["errors"].append("public_clarification")
    assert metrics(current, ["a"])["public_task_passed"] == 0


@pytest.mark.parametrize("change", ["partial", "model", "policy", "jar", "source", "clock", "repair", "missing", "duplicate", "prompt", "ablation", "turns"])
def test_incomparable_or_incomplete_runs_cannot_report_gain(change):
    old, new = report(enabled=False), report()
    if change == "partial": old["summary"]["status"] = "PARTIAL"
    elif change == "model": old["server"]["requested_model"] = "other"
    elif change == "policy": old["stage_evaluation"]["sha256"] = "other"
    elif change in {"jar", "source"}: old["server"][change + "_sha256"] = "other"
    elif change == "clock": old["dataset"]["as_of_date"] = "other"
    elif change == "repair": old["server"]["repair_enabled"] = False
    elif change == "missing": old["results"] = []
    elif change == "duplicate": old["results"] *= 2
    elif change == "prompt": old["results"][0]["turns"][0]["actual"]["evidence"]["prompt_version"] = "other"
    elif change == "ablation": old["results"][0]["turns"][0]["actual"]["evidence"].pop("experiment")
    elif change == "turns": old["results"][0]["turns"].pop()
    with pytest.raises(ValueError):
        compare_pair(old, new, kind="memory_ablation", ids=["a"])
