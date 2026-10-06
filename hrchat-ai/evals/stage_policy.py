"""Parallel versioned stage scoring; never edits or replaces the S1 benchmark."""
import hashlib
import json
from pathlib import Path

from evals.scoring import score

POLICY_PATH = Path(__file__).parent / "policies/s2-v2.json"
POLICY = json.loads(POLICY_PATH.read_text(encoding="utf-8"))


def policy_evidence():
    return {"version": POLICY["version"], "sha256": hashlib.sha256(
        POLICY_PATH.read_bytes().replace(b"\r\n", b"\n")).hexdigest(), "source": "evals/policies/" + POLICY_PATH.name}


def score_stage(case, turn, actual):
    rule = next((r for r in POLICY["overrides"] if case["case_id"] in r["case_ids"]), None)
    expected = rule["expected"] if rule else turn["expected"]
    result = {**score(expected, actual), "expected": expected, "override_reason": rule["reason"] if rule else None,
              "deferred_to": POLICY["deferred_scenes"].get(case["scene"]), "no_query_verified": None}
    if not rule:
        return result
    questions = [q.get("question") for event in actual.get("events", []) if event.get("event") == "INTERRUPT"
                 for q in event.get("payload", {}).get("questions", [])]
    if rule.get("public_question") and questions != [rule["public_question"]]:
        result["errors"].append("public_clarification")
    evidence = actual.get("evidence") or {}
    if evidence:
        if evidence.get("reason") != rule["evidence_reason"]:
            result["errors"].append("stop_reason")
        if rule.get("require_no_query"):
            result["no_query_verified"] = bool(evidence.get("trace")) and not evidence.get("execution") and not any(
                t.get("stage") == "tool" for t in evidence.get("trace", []))
            if not result["no_query_verified"]:
                result["errors"].append("unexpected_query_or_missing_trace")
    # Evidence API permission is never broadened to improve an evaluation score.
    # Missing evidence leaves no-query verification unknown; public behavior is scored separately.
    result["passed"] = not result["errors"]
    return result


def stage_report(cases, results, summarize):
    scored = []
    for row in results:
        turns = [{**t, **t["stage_score"]} for t in row["turns"]]
        case = next(c for c in cases if c["case_id"] == row["case_id"])
        scored.append({**row, "turns": turns,
                       "passed": len(turns) == len(case["turns"]) and all(t["passed"] for t in turns)})
    eligible = [c for c in cases if c["scene"] not in POLICY["deferred_scenes"]]
    eligible_ids = {c["case_id"] for c in eligible}
    guarded = [t["stage_score"] for r in results for t in r["turns"] if t["stage_score"]["override_reason"]]
    return {**policy_evidence(), "full_suite": summarize(cases, scored),
            "s2_scope": summarize(eligible, [r for r in scored if r["case_id"] in eligible_ids]),
            "deferred_cases": [{"case_id": c["case_id"], "reason": POLICY["deferred_scenes"][c["scene"]]}
                               for c in cases if c["case_id"] not in eligible_ids],
            "no_query_verification": {"required_observed_turns": len(guarded),
                "verified": sum(g["no_query_verified"] is True for g in guarded),
                "failed": sum(g["no_query_verified"] is False for g in guarded),
                "unknown": sum(g["no_query_verified"] is None for g in guarded)},
            "interpretation": "Public task outcome; missing protected evidence is not proof that no query executed. Multi-turn remains in full_suite."}
