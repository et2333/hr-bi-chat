from copy import deepcopy
import json
from types import SimpleNamespace

import pytest

from evals.build_rag_task_dataset import novelty_audit
from evals.dataset import MODE_DATASET, RAG_TASK_DATASET, load_dataset
from evals.scoring import score
from evals import rag_ablation


def test_unseen_suite_is_frozen_disjoint_and_uses_unchanged_literal_oracles():
    manifest, cases = load_dataset(RAG_TASK_DATASET)
    _, anchors = load_dataset(MODE_DATASET)
    by_id = {c["case_id"]: c for c in anchors}
    assert len(cases) == 12 and all(c["split"] == "frozen" for c in cases)
    assert {scene: sum(c["scene"] == scene for c in cases) for scene in ["trend", "org", "detail", "clarify", "unsupported"]} == {
        "trend": 3, "org": 2, "detail": 1, "clarify": 3, "unsupported": 3}
    assert novelty_audit(cases)["normalized_exact_overlap"] == 0
    assert manifest["human_review"] == "pending"
    for case in cases:
        if "oracle_anchor" in case:
            anchor = by_id[case["oracle_anchor"]]["turns"][0]
            turn = case["turns"][0]
            assert turn["expected"] == anchor["expected"]
            assert turn["context_override"] == anchor["context_override"]


@pytest.mark.parametrize("evidence,passed", [
    ({}, False), ({"trace": [{"stage": "compile"}]}, True),
    ({"trace": [{"stage": "tool"}]}, False),
    ({"trace": [{"stage": "compile"}], "execution": {"query_plan": {}}}, False),
])
def test_stop_requires_positive_trace_of_no_query(evidence, passed):
    expected = {"statuses": ["CLARIFYING"], "no_execution": True, "no_data": True, "require_no_query_trace": True}
    actual = {"status": "CLARIFYING", "evidence": evidence}
    assert score(expected, actual)["passed"] is passed


def test_scored_failure_does_not_skip_remaining_ablation_arms(tmp_path, monkeypatch):
    monkeypatch.setattr(rag_ablation, "ROOT", tmp_path)
    monkeypatch.setattr(rag_ablation, "source_fingerprint", lambda: "source")
    monkeypatch.setattr(rag_ablation, "sha", lambda path: "jar")
    plan_path = rag_ablation.prepare(dataset=RAG_TASK_DATASET, split="frozen", case_ids=None, text_style="semantic")
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    assert plan["max_model_calls"] == 36
    assert all("--rag-text-style" in a["command"] for a in plan["arms"])
    calls = []
    def fake_run(command, **kwargs):
        calls.append(command)
        pointer = __import__("pathlib").Path(command[command.index("--report-pointer") + 1])
        report = pointer.with_suffix(".json")
        report.write_text(json.dumps({"summary": {"status": "COMPLETED", "passed": 0}}), encoding="utf-8")
        pointer.write_text(str(report), encoding="utf-8")
        return SimpleNamespace(returncode=2)
    monkeypatch.setattr(rag_ablation.subprocess, "run", fake_run)
    monkeypatch.setattr(rag_ablation, "write_comparison", lambda path, plan: 0)
    assert rag_ablation.execute(plan_path) == 0
    assert len(calls) == 3
    assert all(a["status"] == "completed" for a in json.loads(plan_path.read_text(encoding="utf-8"))["arms"])
