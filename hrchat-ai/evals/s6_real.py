"""Explicit, bounded real-model comparison on two preselected mock-data cases.

Only the model transport is real. Tools use identical frozen aggregate fixtures
in all arms; cross-service execution is validated separately by s6_smoke.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path

from adapters.local_env import load_local_model_env
from adapters.openai_client import OpenAIClientImpl
from adapters.query_budget import QueryBudget
from agentscope_teams.attribution_team import AttributionTeam
from evals.s6_dataset import load_dataset, dataset_identity
from evals.s6_run import FixtureTools, MODES, ROOT, make_request, score_result, behavior_observations
from evals.s5_compare import source_fingerprint


async def run(model, case_ids):
    manifest, dataset = load_dataset()
    cases = [case for case in dataset if case["case_id"] in set(case_ids)]
    if len(cases) != len(set(case_ids)) or any(c["origin"] != "seed" for c in cases):
        raise ValueError("The preregistered seed anchors are missing")
    run_dir = ROOT / "docs/evaluation-runs" / ("s6-real-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    run_dir.mkdir(parents=True)
    plan = {"run_kind": "real_model_mock_aggregate_tools", "requested_model": model,
            "case_ids": [c["case_id"] for c in cases], "modes": list(MODES),
            "max_model_calls_total": 7 * len(cases), "per_task": {"single": 3, "dual": 4, "deterministic": 0},
            "max_tokens_per_response": 1536, "timeout_seconds": 60,
            "dataset": dataset_identity(manifest), "source_sha256": source_fingerprint(),
            "note": "Small behavior acceptance only, not evidence of general collaboration benefit."}
    (run_dir / "manifest.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
    load_local_model_env()
    adapter = OpenAIClientImpl(model=model)
    rows = []
    try:
        for case in cases:
            for mode in MODES:
                tools = FixtureTools(case)
                events = []

                async def emit(event):
                    events.append(event)

                budget = QueryBudget(timeout_seconds=60, max_model_calls=plan["per_task"][mode], max_mcp_attempts=6)
                result = await AttributionTeam(adapter, tools, make_request(case, mode), budget=budget).run(emit)
                rows.append({"case_id": case["case_id"], "mode": mode, "result": result,
                             "score": score_result(case, mode, result, tools.calls),
                             "behavior": behavior_observations(result, tools.calls), "events": events})
                # Preserve every failed attempt; never select only the best rerun.
                (run_dir / "report.json").write_text(json.dumps({"manifest": plan, "cases": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    finally:
        await adapter.aclose()
    print(json.dumps({"report": str(run_dir / "report.json"),
                      "model_calls": sum(r["result"]["usage"]["model_calls"] for r in rows),
                      "passed": sum(r["score"]["passed"] for r in rows), "total": len(rows),
                      "cases": [{"case_id": r["case_id"], "mode": r["mode"], "status": r["result"]["status"],
                                 "passed": r["score"]["passed"], "failures": r["score"]["failures"],
                                 "model_calls": r["result"]["usage"]["model_calls"],
                                 "daily_queries": r["behavior"]["daily_queries"],
                                 "unresolved": r["result"]["unresolved"]} for r in rows]}, ensure_ascii=False))
    return 0 if len(rows) == len(cases) * len(MODES) and all(r["score"]["passed"] for r in rows) else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default="qwen-plus")
    parser.add_argument("--case-id", action="append", choices=["S6-01", "S6-03"],
                        help="Explicitly select one anchor for an authorised smaller run")
    parser.add_argument("--confirm-real-calls", action="store_true")
    args = parser.parse_args()
    if not args.confirm_real_calls:
        parser.error("This run sends mock department aggregates to a model API; explicitly confirm up to 14 calls")
    return asyncio.run(run(args.model, args.case_id or ["S6-01", "S6-03"]))


if __name__ == "__main__":
    raise SystemExit(main())
