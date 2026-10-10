"""J3 RAG ablation: register arms, compare only rag_mode, never invent gains."""
import pytest

from evals.rag_ablation import DEFAULT_CASE_IDS, prepare
from evals.s5_compare import compare_pair


def report(*, rag_mode="off", passed=True, case_ids=None):
    case_ids = list(case_ids or DEFAULT_CASE_IDS)
    rows = []
    for case_id in case_ids:
        rows.append({
            "case_id": case_id, "scene": "trend",
            "turns": [{
                "stage_score": {"passed": passed, "errors": [] if passed else ["terminal_state"]},
                "actual": {
                    "elapsed_ms": 120,
                    "evidence": {
                        "retrieval": {"mode": rag_mode, "status": "disabled" if rag_mode == "off" else "ok"},
                        "prompt_version": "same",
                        "decoding": {"temperature": 0},
                        "model_calls": [{"call_id": f"{case_id}-initial", "model": "qwen-plus",
                                         "provider": "openai", "phase": "initial",
                                         "usage": {"prompt_tokens": 10, "completion_tokens": 2}}],
                        "repair": {"attempts": 0},
                    },
                },
            }],
        })
    return {
        "run_id": f"rag-{rag_mode}", "runtime": "remote", "model_kind": "real",
        "selected_case_ids": case_ids,
        "summary": {"status": "COMPLETED", "executed_turns": len(case_ids), "planned_turns": len(case_ids)},
        "dataset": {"cases_sha256": "x", "migration_sha256": {"v1": "y"}, "as_of_date": "2026-09-28",
                    "data_version": "v6", "timezone": "Asia/Shanghai"},
        "stage_evaluation": {"sha256": "same-policy"},
        "server": {
            "jar_sha256": "jar", "source_sha256": "source", "org_catalog_scope": "authorized",
            "as_of_date": "2026-09-28", "data_version": "v6", "timezone": "Asia/Shanghai",
            "memory_enabled": True, "requested_model": "qwen-plus", "repair_enabled": False,
            "planner_variant": "baseline", "rag_mode": rag_mode,
        },
        "results": rows,
    }


def test_prepare_registers_three_rag_arms_with_shared_cases_and_budget(tmp_path, monkeypatch):
    monkeypatch.setattr("evals.rag_ablation.ROOT", tmp_path)
    monkeypatch.setattr("evals.rag_ablation.source_fingerprint", lambda: "source")
    monkeypatch.setattr("evals.rag_ablation.sha", lambda _path: "jar")
    (tmp_path / "docs/evaluation-runs").mkdir(parents=True)
    (tmp_path / "hrchat-server/hrchat-bootstrap/target").mkdir(parents=True)
    (tmp_path / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar").write_bytes(b"jar")
    path = prepare(model="qwen-plus", case_ids=DEFAULT_CASE_IDS)
    plan = __import__("json").loads(path.read_text(encoding="utf-8"))
    assert plan["version"] == "j3-rag-ablation-v1"
    assert plan["case_ids"] == list(DEFAULT_CASE_IDS)
    assert plan["max_model_calls"] == 9
    assert [arm["name"] for arm in plan["arms"]] == ["rag-off", "rag-lexical", "rag-hybrid"]
    for arm, mode in zip(plan["arms"], ("off", "lexical", "hybrid")):
        assert arm["status"] == "planned"
        assert "--rag" in arm["command"] and mode in arm["command"]
        assert arm["command"][arm["command"].index("--rag") + 1] == mode
        assert "--repair" in arm["command"] and arm["command"][arm["command"].index("--repair") + 1] == "off"
        for case_id in DEFAULT_CASE_IDS:
            assert case_id in arm["command"]


def test_rag_ablation_compare_isolates_mode_and_reports_paired_delta():
    baseline, treated = report(rag_mode="off", passed=False), report(rag_mode="hybrid", passed=True)
    result = compare_pair(baseline, treated, kind="rag_ablation", ids=list(DEFAULT_CASE_IDS))
    assert result["kind"] == "rag_ablation"
    assert result["baseline"]["passed"] == 0 and result["full"]["passed"] == 3
    assert result["paired_changes"]["fixed"] == list(DEFAULT_CASE_IDS)
    assert result["success_rate_delta_percentage_points"] == 100.0


@pytest.mark.parametrize("change", [
    "partial", "model", "repair", "memory", "rag_same", "evidence_mode", "cases", "jar",
])
def test_rag_ablation_rejects_incomparable_arms(change):
    old, new = report(rag_mode="off"), report(rag_mode="lexical")
    if change == "partial":
        old["summary"]["status"] = "PARTIAL"
    elif change == "model":
        old["server"]["requested_model"] = "other"
    elif change == "repair":
        old["server"]["repair_enabled"] = True
    elif change == "memory":
        old["server"]["memory_enabled"] = False
    elif change == "rag_same":
        new["server"]["rag_mode"] = "off"
        for row in new["results"]:
            row["turns"][0]["actual"]["evidence"]["retrieval"]["mode"] = "off"
    elif change == "evidence_mode":
        old["results"][0]["turns"][0]["actual"]["evidence"]["retrieval"]["mode"] = "lexical"
    elif change == "cases":
        old["selected_case_ids"] = ["hr-modes-v1-01"]
        old["results"] = old["results"][:1]
        old["summary"].update(executed_turns=1, planned_turns=1)
    elif change == "jar":
        old["server"]["jar_sha256"] = "other"
    with pytest.raises(ValueError):
        compare_pair(old, new, kind="rag_ablation", ids=list(DEFAULT_CASE_IDS if change != "cases" else ["hr-modes-v1-01"]))
