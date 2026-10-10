"""python -m evals.run --base-url http://127.0.0.1:18085 --split dev"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import platform
import math
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

from evals.api import JavaApi
from evals.dataset import DATASET, DATASETS, MODE_DATASET, load_dataset, select_cases, sha, sha_text
from evals.reference import ROOT, MIGRATIONS
from evals.scoring import score
from evals.stage_policy import POLICY, load_policy, score_stage, stage_report
from adapters.model_usage import summarize_calls
from evals.repair_metrics import summarize_repair


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def code_evidence():
    status = git("status", "--porcelain")
    diff = git("diff", "HEAD", "--binary")
    untracked = git("ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")
    return {"commit": git("rev-parse", "HEAD").decode().strip(), "dirty": bool(status),
            "patch_sha256": hashlib.sha256(diff).hexdigest(),
            "untracked_sha256": {p: sha(ROOT / p) for p in untracked if p and (ROOT / p).is_file()}}


def execute(cases, api, on_result=lambda results: None, policy=POLICY):
    results = []
    for case in cases:
        row = {"case_id": case["case_id"], "scene": case["scene"], "split": case["split"], "turns": []}
        try:
            session = api.session(case["session_owner"])
            for turn in case["turns"]:
                actual = api.ask(session, case["identity_fixture"], turn)
                if (policy is None and (turn["expected"].get("plan") or {}).get("query_mode") == "detail"
                        and actual.get("status") == "COMPLETED"):
                    actual["saved_pages"] = api.saved_pages(case["identity_fixture"], actual["answer"])
                scored = {"question": turn["question"], "expected": turn["expected"],
                          "actual": actual, **score(turn["expected"], actual)}
                if policy is not None:
                    scored["stage_score"] = score_stage(case, turn, actual, policy)
                row["turns"].append(scored)
        except (Exception, KeyboardInterrupt) as exc:
            # Do not serialize raw exception messages (may include credentials or arbitrary payloads).
            row["execution_error"] = type(exc).__name__
            if isinstance(exc, KeyboardInterrupt):
                results.append(row)
                on_result(results)
                break
        row["passed"] = len(row["turns"]) == len(case["turns"]) and all(t["passed"] for t in row["turns"])
        results.append(row)
        on_result(results)
    return results


def summarize(cases, results):
    completed = sum(len(r["turns"]) == len(c["turns"]) for c, r in zip(cases, results))
    passed = sum(bool(r.get("passed")) for r in results)
    full = completed == len(cases)
    groups = {}
    for c in cases:
        bucket = groups.setdefault(c["scene"], {"passed": 0, "total": 0})
        bucket["total"] += 1
    for r in results:
        groups[r["scene"]]["passed"] += int(bool(r.get("passed")))
    latencies = sorted(t["actual"]["elapsed_ms"] for r in results for t in r["turns"]
                       if "actual" in t and "elapsed_ms" in t["actual"])
    return {"status": "COMPLETED" if full else "PARTIAL" if completed else "FAILED",
            "passed": passed, "total": len(cases), "executed_cases": completed,
            "planned_turns": sum(len(c["turns"]) for c in cases),
            "executed_turns": sum(len(r["turns"]) for r in results),
            "success_rate": passed / len(cases) if full and cases else None,
            "by_scene": groups,
            "turn_latency_ms": {"count": len(latencies), "includes_sql_evidence_fetch": True,
                                "p50": latencies[math.ceil(len(latencies) * .5) - 1] if latencies else None,
                                "p95": latencies[math.ceil(len(latencies) * .95) - 1] if latencies else None},
            "failure_types": dict(Counter(e for r in results for t in r["turns"] for e in t["errors"])),
            "failure_layers": dict(Counter(e for r in results for t in r["turns"] for e in t.get("failure_layers", [])))}


def prior_runs(parent, manifest, cases):
    previous, overlap = [], []
    equivalent_hashes = {manifest["cases_sha256"], *manifest.get("legacy_cases_sha256", [])}
    for path in parent.glob("*/report.json"):
        old = json.loads(path.read_text(encoding="utf-8"))
        # S6/retrieval reports may use a string dataset label or another schema.
        dataset = old.get("dataset")
        if not isinstance(dataset, dict):
            continue
        if dataset.get("cases_sha256") in equivalent_hashes:
            previous.append(path.parent.name)
            scored_ids = {r["case_id"] for r in old.get("results", []) if r.get("turns")}
            if scored_ids.intersection(c["case_id"] for c in cases):
                overlap.append(path.parent.name)
    return previous, overlap


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18085")
    parser.add_argument("--split", choices=["dev", "frozen", "all"], default="dev")
    parser.add_argument("--dataset", choices=sorted(DATASETS), default=DATASET.name)
    parser.add_argument("--case-id", action="append", help="Repeat to select complete cases for a focused run")
    parser.add_argument("--runtime", choices=["local", "remote"], default="local")
    parser.add_argument("--model-kind", choices=["real", "fixture", "rule_based", "unknown"], default="unknown")
    parser.add_argument("--report-pointer", type=Path)
    parser.add_argument("--stage", choices=["s2", "s3", "s4"], default="s2")
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--server-evidence", type=Path, required=True,
                        help="JSON of the isolated server launch/configuration (no secrets)")
    args = parser.parse_args()
    policy = load_policy(args.stage) if args.dataset == DATASET.name else None
    if urlparse(args.base_url).hostname not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("This runner uses demo identity headers; only local isolated servers are supported")
    manifest, all_cases = load_dataset(DATASETS[args.dataset])
    for name, digest in manifest["migration_sha256"].items():
        if sha_text(MIGRATIONS / name) != digest:
            raise ValueError("Migration drift: " + name)
    server = json.loads(args.server_evidence.read_text(encoding="utf-8-sig"))
    for key, value in {"runtime": args.runtime, "as_of_date": manifest["as_of_date"],
                       "data_version": manifest["data_version"], "timezone": manifest["timezone"]}.items():
        if server.get(key) != value:
            raise ValueError("Server evidence mismatch: " + key)
    try:
        cases = select_cases(all_cases, args.split, args.case_id)
        if not cases:
            raise ValueError("Selection is empty; no evaluation will be run")
    except ValueError as exc:
        parser.error(str(exc))
    parent = ROOT / "docs/evaluation-runs"
    parent.mkdir(parents=True, exist_ok=True)
    previous, overlap = prior_runs(parent, manifest, cases)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = parent / run_id
    output.mkdir()
    if args.report_pointer:
        args.report_pointer.write_text(str(output / "report.json"), encoding="utf-8")
    report = {"run_id": run_id, "started_at": datetime.now(timezone.utc).isoformat(),
              "dataset": manifest, "split": args.split, "scope": "java_user_api",
              "selection": "subset" if args.case_id else "full_split",
              "selected_case_ids": [c["case_id"] for c in cases],
              "server": server, "server_evidence_source": "operator launch record, not runtime attestation",
              "runtime": args.runtime, "model_effectiveness": False, "usage": None,
              "usage_reason": "not exposed by current runtime; no estimated tokens treated as actual",
              "code": code_evidence(), "environment": {"python": platform.python_version(), "os": platform.platform()},
              "command": sys.argv, "timeout_seconds": args.timeout, "retries": 0,
              "prior_dataset_runs": previous,
              "prior_overlapping_runs": overlap,
              "measurement_kind": "regression" if overlap else "initial_baseline"}

    def save(results):
        report.update(summary=summarize(cases, results), results=results)
        if policy is not None:
            report["stage_evaluation"] = stage_report(cases, results, summarize, policy)
        elif args.dataset == MODE_DATASET.name:
            # Modes scoring has no stage override file; fingerprint the scorer modules.
            digest = hashlib.sha256()
            for relative in ("evals/scoring.py", "evals/mode_scoring.py", "evals/mode_reference.py"):
                digest.update(sha_text(ROOT / "hrchat-ai" / relative).encode())
            report["stage_evaluation"] = {
                "kind": "hr-query-modes-v1",
                "sha256": digest.hexdigest(),
                "files": ["evals/scoring.py", "evals/mode_scoring.py", "evals/mode_reference.py"],
            }
        report["repair_evaluation"] = summarize_repair(cases, results)
        turns = [t for r in results for t in r["turns"]]
        evidence = [(t.get("actual") or {}).get("evidence") or {} for t in turns]
        calls = [c for e in evidence for c in e.get("model_calls", [])]
        for call in calls:
            call["run_id"] = run_id
        report["cost_summary"] = summarize_calls(calls, report["summary"]["passed"])
        report["evidence_coverage"] = {"observed_turns": len(turns), "planned_turns": report["summary"]["planned_turns"],
                                       "turns_with_evidence": sum(bool(e) for e in evidence)}
        if not evidence or not all(evidence) or report["summary"]["status"] != "COMPLETED":
            report["cost_summary"]["cost_per_success"] = None
        report["model_kind"] = args.model_kind
        report["model_effectiveness"] = args.model_kind == "real" and bool(calls) and all(c.get("provider") != "fixture" for c in calls)
        report["effective_runtimes"] = [dict(t) for t in {
            tuple(sorted(e.get("runtime_config", {}).items())) for e in evidence if e.get("runtime_config")}]
        report["catalog_reason_counts"] = dict(Counter(e.get("reason") for e in evidence if e.get("reason")))
        if calls:
            report["usage"] = report["cost_summary"]["actual_usage_known_sum"]
            report["usage_reason"] = "per-attempt API usage; absent usage remains unknown, estimates are not a provider bill"
        temp = output / "report.tmp"
        temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temp.replace(output / "report.json")

    save([])
    started = time.perf_counter()
    results = execute(cases, JavaApi(args.base_url, args.timeout), save, policy)
    report["elapsed_seconds"] = time.perf_counter() - started
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    save(results)
    print(json.dumps({"report": str(output / "report.json"), **report["summary"]}, ensure_ascii=False))
    complete = report["summary"]["status"] == "COMPLETED"
    # Legacy stage policies override selected v1 expectations. J1 has no overrides.
    passed = policy is not None or report["summary"]["passed"] == len(cases)
    return 0 if complete and passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
