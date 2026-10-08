"""Paired task comparisons; reject changed conditions and incomplete denominators."""
import hashlib
import json
import math

from adapters.model_usage import summarize_calls
from evals.dataset import sha_text
from evals.reference import ROOT


def source_fingerprint():
    files = {}
    for directory in ("adapters", "agent_gateway", "langgraph_flows", "agentscope_teams", "evals"):
        for path in sorted((ROOT / "hrchat-ai" / directory).rglob("*.py")):
            files[path.relative_to(ROOT).as_posix()] = sha_text(path)
    for path in sorted((ROOT / "hrchat-ai/evals/policies").glob("*.json")):
        files[path.relative_to(ROOT).as_posix()] = sha_text(path)
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()


def task_rows(report, ids):
    rows = report["results"]
    if len({r["case_id"] for r in rows}) != len(rows):
        raise ValueError("Duplicate case results")
    by_id = {r["case_id"]: r for r in rows}
    if not set(ids).issubset(by_id):
        raise ValueError("Missing paired cases")
    return [by_id[key] for key in ids]


def task_passed(row):
    return bool(row["turns"]) and not row.get("execution_error") and all(t["stage_score"]["passed"] for t in row["turns"])


def public_task_passed(row):
    # Preserve stage/safety evidence checks separately. These two checks require
    # remote planner internals and must not masquerade as public answer quality.
    diagnostic = {"stop_reason", "unexpected_query_or_missing_trace"}
    return bool(row["turns"]) and not row.get("execution_error") and all(
        not (set(t["stage_score"]["errors"]) - diagnostic) for t in row["turns"])


def metrics(report, ids):
    rows = task_rows(report, ids)
    turns = [t for r in rows for t in r["turns"]]
    latency = sorted(t["actual"]["elapsed_ms"] for t in turns)
    evidence = [(t["actual"].get("evidence") or {}) for t in turns]
    calls = [c for e in evidence for c in e.get("model_calls", [])]
    cost = summarize_calls(calls)
    return {"passed": sum(task_passed(r) for r in rows), "total": len(ids), "turns": len(turns),
            "public_task_passed": sum(public_task_passed(r) for r in rows),
            "score_definition": "passed includes stage diagnostic conformance; public_task_passed excludes only stop_reason and unexpected_query_or_missing_trace. Public success does not prove no unauthorized query executed.",
            "no_query_verification": {label: sum(t["stage_score"].get("no_query_verified") is value for t in turns
                if t["stage_score"].get("override_reason")) for label, value in (("verified", True), ("failed", False), ("unknown", None))},
            "by_scene": {scene: {"passed": sum(task_passed(r) for r in rows if r["scene"] == scene),
                                  "total": sum(r["scene"] == scene for r in rows)} for scene in sorted({r["scene"] for r in rows})},
            "turn_latency_ms": {f"p{p}": latency[math.ceil(len(latency) * p / 100) - 1] if latency else None for p in (50, 95)},
            "turns_with_evidence": sum(bool(e) for e in evidence), "observed_cost": cost,
            "first_turn_passed": sum(bool(r["turns"]) and r["turns"][0]["stage_score"]["passed"] for r in rows),
            "repair_attempts_observed": sum((e.get("repair") or {}).get("attempts", 0) for e in evidence),
            "failed_cases": [{"case_id": r["case_id"], "turn_errors": [t["stage_score"]["errors"] for t in r["turns"]]} for r in rows if not task_passed(r)]}


def compare_pair(baseline, full, *, kind, ids):
    if kind not in {"rules_vs_full", "memory_ablation"} or not ids or len(set(ids)) != len(ids):
        raise ValueError("Invalid comparison contract")
    for report in (baseline, full):
        if report["summary"]["status"] != "COMPLETED":
            raise ValueError("Incomplete runs cannot report an improvement")
        if report["summary"]["executed_turns"] != report["summary"]["planned_turns"]:
            raise ValueError("Incomplete turn denominator")
    for key in ("cases_sha256", "migration_sha256", "as_of_date", "data_version", "timezone"):
        if baseline["dataset"].get(key) != full["dataset"].get(key):
            raise ValueError("Dataset/clock drift: " + key)
    if baseline["stage_evaluation"]["sha256"] != full["stage_evaluation"]["sha256"]:
        raise ValueError("Scoring policy changed")
    for key in ("jar_sha256", "source_sha256", "org_catalog_scope", "as_of_date", "timezone", "data_version"):
        if not baseline["server"].get(key) or baseline["server"][key] != full["server"].get(key):
            raise ValueError("Execution condition changed: " + key)
    if full["runtime"] != "remote" or full["model_kind"] != "real" or not full["server"].get("memory_enabled"):
        raise ValueError("Full arm must use the real model and memory")
    if kind == "memory_ablation":
        if baseline["runtime"] != "remote" or baseline["model_kind"] != "real" or baseline["server"].get("memory_enabled") is not False:
            raise ValueError("Ablation must disable memory only")
        for key in ("requested_model", "repair_enabled", "planner_variant"):
            if baseline["server"].get(key) != full["server"].get(key):
                raise ValueError("Non-memory setting changed: " + key)
        if baseline["selected_case_ids"] != ids:
            raise ValueError("Ablation case selection changed")
        observed = []
        for report in (baseline, full):
            signatures = set()
            for row in task_rows(report, ids):
                for turn in row["turns"]:
                    ev = turn["actual"].get("evidence") or {}
                    if report is baseline and (ev.get("experiment") or {}).get("memory_enabled") is not False:
                        raise ValueError("Missing actual ablation evidence")
                    if not ev.get("decoding") or not ev.get("prompt_version"):
                        raise ValueError("Missing prompt/decoding evidence")
                    signatures.add(json.dumps({"decoding": ev["decoding"], "prompt": ev["prompt_version"]}, sort_keys=True))
                    for call in ev.get("model_calls", []):
                        if call.get("model") != report["server"]["requested_model"] or call.get("provider") in (None, "fixture"):
                            raise ValueError("Actual model differs from requested real model")
            observed.append(signatures)
        if observed[0] != observed[1]:
            raise ValueError("Prompt or decoding changed between arms")
    elif baseline["runtime"] != "local" or baseline["model_kind"] != "rule_based" or baseline["selected_case_ids"] != full["selected_case_ids"] or ids != full["selected_case_ids"]:
        raise ValueError("Rule comparison requires equal full case sets")
    before, after = task_rows(baseline, ids), task_rows(full, ids)
    if any(len(a["turns"]) != len(b["turns"]) for a, b in zip(before, after)):
        raise ValueError("Paired turn count changed")
    changes = {"fixed": [], "regressed": [], "both_passed": [], "both_failed": []}
    for old, new in zip(before, after):
        a, b = task_passed(old), task_passed(new)
        changes["both_passed" if a and b else "both_failed" if not a and not b else "fixed" if b else "regressed"].append(old["case_id"])
    base_metrics, full_metrics = metrics(baseline, ids), metrics(full, ids)
    return {"kind": kind, "baseline_run_id": baseline["run_id"], "full_run_id": full["run_id"],
            "case_ids": ids, "baseline": base_metrics, "full": full_metrics, "paired_changes": changes,
            "success_rate_delta_percentage_points": 100 * (full_metrics["passed"] - base_metrics["passed"]) / len(ids),
            "public_task_delta_percentage_points": 100 * (full_metrics["public_task_passed"] - base_metrics["public_task_passed"]) / len(ids),
            "interpretation": "Fixed simulated regression cases, not production accuracy or independent generalization. Latency includes evidence fetch; missing usage/cost stays unknown."}
