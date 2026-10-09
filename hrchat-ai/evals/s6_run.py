"""Offline S6 regression runner through the real LangGraph/AgentScope workflow.

The model and Java transport are fixtures. This runner never loads credentials
or opens a network connection, and is not evidence of model or Java accuracy.
"""
from __future__ import annotations

import argparse
import asyncio
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import time
import uuid

from pydantic import ValidationError

from adapters.mcp_client import McpBusinessError
from adapters.model_usage import ModelResult
from adapters.query_budget import ACTIVE_QUERY_BUDGET, QueryBudget
from agentscope_teams.analysis_contract import AnalysisRequest
from agentscope_teams.attribution_team import AttributionTeam
from evals.s6_dataset import dataset_identity, fingerprint, load_dataset, seed_evidence


MODES = ("deterministic", "single", "dual")
ROOT = Path(__file__).resolve().parents[2]


class FixtureAdapter:
    """Scripted role replies, not a mock of the team, graph, or role SDK."""

    name = "fixture"
    model = "s6-scripted-roles-v1"

    def __init__(self, injection=None):
        self.injection = injection
        self.calls = []

    async def complete_analysis(self, system, user):
        self.calls.append({"system": system, "user": user})
        if self.injection == "invalid_model_json":
            text = "{not valid JSON"
        else:
            schema = json.loads(system.split("JSON schema:\n", 1)[1])
            data = json.loads(user)
            if schema["title"] == "AnalysisPlan":
                reply = {"method": "department_contribution",
                         "steps": ["compare_totals", "department_delta", "check_closure"]}
            elif schema["title"] == "Claims":
                claims = [{"claim_id": "overall", "kind": "fact", "fact_id": "overall",
                           "evidence_ids": ["ev_department"]}]
                for fact_id in data["facts"]:
                    if fact_id.startswith("department:"):
                        claims.append({"claim_id": fact_id, "kind": "statistical_contribution",
                                       "fact_id": fact_id, "evidence_ids": ["ev_department"]})
                if self.injection == "unknown_claim_reference":
                    claims.append({"claim_id": "unsupported", "kind": "fact",
                                   "fact_id": "department:99999", "evidence_ids": ["ev_department"]})
                reply = {"claims": claims}
                if self.injection == "conditional_daily_query" and data["supplement_available"]:
                    reply["request_evidence"] = "daily_counts"
            elif schema["title"] == "ReviewResult":
                reply = {"decision": "accept",
                         "checked_claim_ids": [c["claim_id"] for c in data["claims"]["claims"]],
                         "drop_claim_ids": data["invalid_claim_ids"], "issues": []}
                if self.injection == "conditional_daily_query" and data["supplement_available"]:
                    reply.update(decision="request_evidence", evidence_request="daily_counts",
                                 issues=["missing_daily_evidence"])
            else:
                raise AssertionError("Fixture does not recognize this role contract")
            text = json.dumps(reply, ensure_ascii=False)
        return ModelResult(text, self.name, self.model, "fixture_" + uuid.uuid4().hex,
                           elapsed_ms=0, finish_reason="stop", usage_source="unknown")


class FixtureTools:
    """MCP-shaped aggregate fixture; authorisation itself belongs to Java tests."""

    def __init__(self, case):
        self.case = case
        self.calls = []
        self.entered = asyncio.Event()

    async def tools_call(self, name, arguments, context):
        assert name == "analysis_evidence"
        assert context["tool_context_token"] == "fixture-token"
        budget = ACTIVE_QUERY_BUDGET.get()
        assert budget is not None
        if self.case["injection"] == "tool_budget_exhausted":
            # Six explicitly injected transport attempts, then the real limiter
            # rejects the seventh. No artificial lower ceiling is used.
            for attempt in range(7):
                budget.consume("mcp", retry=attempt > 0)
        budget.consume("mcp")
        self.calls.append(deepcopy(arguments))
        self.entered.set()
        injection = self.case["injection"]
        if injection == "cancel_during_tool":
            await asyncio.Event().wait()
        if injection == "permission_denied":
            raise McpBusinessError("HRC-2003", "Injected access denial")
        raw = seed_evidence(self.case)
        if injection == "missing_department":
            raw["departments"].pop()
        elif injection == "missing_total":
            raw.pop("baseline_total")
        elif injection == "snapshot_mismatch":
            raw["data_version"] = "different-snapshot"
        elif injection == "metric_version_mismatch":
            raw["metric_version"] = "different-version"
        elif injection == "scope_mismatch":
            raw["effective_org_ids"] = raw["effective_org_ids"][:-1]
        elif injection == "duplicate_department":
            raw["departments"][-1] = deepcopy(raw["departments"][0])
        elif injection == "nonclosing_total":
            raw["current_total"] += 1
        elif injection == "organization_history_gap":
            raw["quality_issues"] = ["organization_history_gap"]
        if arguments["detail"] != "daily":
            raw.pop("daily")
        return raw


def make_request(case, mode):
    return AnalysisRequest.model_validate({
        "analysis_context": {
            "task_id": f"fixture-{case['case_id']}-{mode}", "source_ask_id": "fixture-ask",
            "source_turn_id": "fixture-turn", "tenant_no": "fixture-tenant",
            "metric_code": "headcount" if case["injection"] == "unsupported_metric" else "leave_count",
            "metric_version": "1", "unit": "人", "current_period": case["current_period"],
            "baseline_period": case["baseline_period"], "effective_org_ids": case["org_ids"],
            "data_version": "s6-literal-seed-v1", "scope_ref": "fixture-scope",
        }, "invocation_id": "fixture-invocation", "tool_context_token": "fixture-token", "mode": mode,
    })


def score_result(case, mode, result, tool_calls):
    """Independent expected values; never call the production result validator."""
    expected, failures = case["expected"], []
    statuses = expected.get("mode_statuses", {}).get(mode, expected["statuses"])
    if result["status"] not in statuses:
        failures.append("unexpected_status")
    numbers = expected.get("numbers")
    if numbers:
        summary = result.get("summary") or {}
        if any(summary.get(key) != numbers[key] for key in ("current_total", "baseline_total", "delta")):
            failures.append("incorrect_summary")
        rows = result.get("contributions", [])
        actual = {str(row["org_id"]): row["delta"] for row in rows}
        if actual != numbers["department_deltas"] or len(rows) != len(actual):
            failures.append("incorrect_department_deltas")
        if any(row["current_count"] != numbers["current_counts"][str(row["org_id"])]
               or row["baseline_count"] != numbers["baseline_counts"][str(row["org_id"])] for row in rows
               if str(row["org_id"]) in numbers["current_counts"]):
            failures.append("incorrect_department_counts")
        if not summary.get("closure_verified"):
            failures.append("missing_closure_verification")
    if expected.get("no_unverified_data") and any(result.get(key) for key in ("summary", "contributions", "evidence", "claims")):
        failures.append("unverified_data_returned")
    evidence_ids = {row["evidence_id"] for row in result.get("evidence", [])}
    facts = result.get("facts", {})
    for claim in result.get("claims", []):
        if not claim["evidence_ids"] or not set(claim["evidence_ids"]) <= evidence_ids:
            failures.append("unsupported_evidence_reference")
        if claim["kind"] in {"fact", "statistical_contribution"} and claim["fact_id"] not in facts:
            failures.append("unsupported_fact_reference")
    supplements = sum(call["detail"] == "daily" for call in tool_calls)
    if supplements > 1:
        failures.append("supplement_limit_exceeded")
    if expected.get("conditional_supplement") and supplements != (0 if mode == "deterministic" else 1):
        failures.append("conditional_supplement_not_followed")
    usage = result.get("usage", {})
    if usage.get("model_calls", 0) > 4 or usage.get("mcp_attempts", 0) > 6:
        failures.append("budget_limit_exceeded")
    if mode == "deterministic" and usage.get("model_calls", 0):
        failures.append("deterministic_called_model")
    return {"passed": not failures, "failures": sorted(set(failures))}


async def run_case(case, mode):
    adapter, tools = FixtureAdapter(case["injection"]), FixtureTools(case)
    budget = QueryBudget(timeout_seconds=60, max_model_calls=4, max_mcp_attempts=6)
    events = []

    async def emit(event):
        # Keep event ordering without duplicating the entire final payload.
        events.append({"event": event["event"], "stage": event["payload"].get("stage"),
                       "detail": event["payload"].get("detail")})

    try:
        request = make_request(case, mode)
    except ValidationError:
        result = {"status": "FAILED", "unresolved": ["request_contract_rejected"],
                  "usage": budget.evidence(), "rejection_layer": "python_request_contract_fixture"}
    else:
        if case["injection"] == "deadline_exhausted":
            budget.started = time.monotonic() - 61
        team = AttributionTeam(adapter, tools, request, budget=budget)
        task = asyncio.create_task(team.run(emit))
        if case["injection"] == "cancel_during_tool":
            await asyncio.wait_for(tools.entered.wait(), timeout=10)
            task.cancel()
        result = await task
    return {"case_id": case["case_id"], "group_id": case["group_id"], "split": case["split"],
            "origin": case["origin"], "injection": case["injection"], "mode": mode,
            "score": score_result(case, mode, result, tools.calls), "result": result,
            "tool_calls": tools.calls, "event_sequence": events}


async def run_fixture(*, split="all", modes=MODES):
    manifest, cases = load_dataset()
    selected = [case for case in cases if split == "all" or case["split"] == split]
    rows = [await run_case(case, mode) for mode in modes for case in selected]
    totals = {}
    for mode in modes:
        arm = [r for r in rows if r["mode"] == mode]
        totals[mode] = {"passed": sum(r["score"]["passed"] for r in arm), "total": len(arm),
                       "by_origin": {origin: {"passed": sum(r["score"]["passed"] for r in arm if r["origin"] == origin),
                                              "total": sum(r["origin"] == origin for r in arm)}
                                     for origin in ("seed", "injected")},
                       "model_calls": sum(r["result"]["usage"].get("model_calls", 0) for r in arm),
                       "mcp_attempts": sum(r["result"]["usage"].get("mcp_attempts", 0) for r in arm)}
    sources = ["agentscope_teams/analysis_contract.py", "agentscope_teams/analysis_roles.py",
               "agentscope_teams/attribution_team.py", "evals/s6_dataset.py", "evals/s6_run.py"]
    source_identity = {name: (ROOT / "hrchat-ai" / name).read_text(encoding="utf-8") for name in sources}
    return {"schema_version": "1", "run_kind": "offline_scripted_fixture",
            "created_at": datetime.now(timezone.utc).isoformat(), "dataset": dataset_identity(manifest),
            "source_sha256": fingerprint(source_identity), "split": split, "modes": list(modes),
            "limits": manifest["limits"], "summary": totals,
            "limitations": ["Scripted model: no real-model quality, tokens, cost or latency conclusions.",
                            "Seed cases are arithmetic anchors, not observed natural model successes.",
                            "Injected tool denial tests Python handling, not Java authorization enforcement.",
                            "Frozen engineering regressions are not held-out real-user generalization evidence.",
                            "Different expected behavior across modes means pass rates cannot rank model quality."],
            "cases": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("dev", "frozen", "all"), default="dev")
    parser.add_argument("--modes", nargs="+", choices=MODES, default=list(MODES))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = asyncio.run(run_fixture(split=args.split, modes=tuple(dict.fromkeys(args.modes))))
    run_id = "s6-fixture-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = args.output or ROOT / "docs/evaluation-runs" / run_id / "report.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(path), "run_kind": report["run_kind"], "summary": report["summary"]}, ensure_ascii=False))
    return 0 if all(row["score"]["passed"] for row in report["cases"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
