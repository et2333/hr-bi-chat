"""R5 Reviewer fault-injection harness. Scripted SDK turns, ZERO provider calls.

Reference and faulty reviewer outputs exercise the same production graph.
Scores describe these controlled replies, not learned reasoning or AI uplift.
"""
from __future__ import annotations

import asyncio
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

from adapters.model_usage import ModelResult
from adapters.query_budget import ACTIVE_QUERY_BUDGET
from agentscope_teams.analysis_contract import AnalysisRequest
from agentscope_teams.attribution_team import AttributionTeam

ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = ("clean", "missing_department", "unknown_fact", "cause_as_fact",
             "zero_baseline_growth", "unnecessary_supplement", "false_deletion_control", "supported_supplement")
PLAN = {"method": "department_contribution", "steps": ["compare_totals", "department_delta", "check_closure"]}


def fixture(scenario):
    context = {"task_id": "r5-" + scenario, "source_ask_id": "fixture-ask", "source_turn_id": "fixture-turn",
        "tenant_no": "fixture-tenant", "metric_code": "leave_count", "metric_version": "1", "unit": "人",
        "data_version": "r5-literal-v1", "effective_org_ids": [3, 4], "scope_ref": "fixture-scope",
        "current_period": {"start": "2026-09-01", "end": "2026-10-01"},
        "baseline_period": {"start": "2026-08-01", "end": "2026-09-01"}}
    zero = scenario == "zero_baseline_growth"
    raw = {**{k: context[k] for k in ("metric_code", "metric_version", "unit", "data_version",
                                    "effective_org_ids", "current_period", "baseline_period")},
        "status": "complete", "quality_issues": [], "current_total": 4, "baseline_total": 0 if zero else 4,
        "departments": [{"org_id": 3, "org_name": "研发一部", "current_count": 3, "baseline_count": 0 if zero else 1},
                        {"org_id": 4, "org_name": "研发二部", "current_count": 1, "baseline_count": 0 if zero else 3}],
        "daily": [{"period": "current", "date": "2026-09-02", "count": 4},
                  {"period": "baseline", "date": "2026-08-05", "count": 0 if zero else 4}]}
    claims = [{"claim_id": cid, "kind": "fact" if fid == "overall" else "statistical_contribution",
               "fact_id": fid, "evidence_ids": ["ev_department"]}
              for cid, fid in (("total", "overall"), ("up", "department:3"), ("down", "department:4"))]
    if scenario == "missing_department":
        claims = claims[:1]
    bad = {"unknown_fact": "department:999", "cause_as_fact": "cause:management",
           "zero_baseline_growth": "growth:infinite"}.get(scenario)
    if bad:
        claims.append({"claim_id": "bad", "kind": "fact", "fact_id": bad, "evidence_ids": ["ev_department"]})
    need = None
    if scenario == "supported_supplement":
        claims.append({"claim_id": "timing", "kind": "unverified_hypothesis", "hypothesis": "timing_concentration",
                       "evidence_ids": ["ev_department"]})
        need = {"claim_id": "timing", "reason": "verify_timing_concentration", "required_fact_ids": ["daily_peak:current"]}
    draft = {"claims": claims}
    if scenario in {"supported_supplement", "unnecessary_supplement"}:
        draft.update(request_evidence="daily_counts", supplement_need=need)
    return {"id": scenario, "context": context, "raw": raw, "draft": draft,
            "bad_claim_ids": ["bad"] if bad else [], "keep_claim_ids": [c["claim_id"] for c in claims if c["claim_id"] != "bad"],
            "required_fact_ids": ["overall", "department:3", "department:4"],
            "allow_daily": scenario == "supported_supplement"}


class ScriptedRoles:
    name, model = "fixture", "r5-scripted-role-v1"

    def __init__(self, case, policy):
        self.case, self.policy, self.calls = case, policy, []

    async def complete_analysis(self, system, user):
        self.calls.append({"system": system, "user": user})
        data = json.loads(user)
        title = json.loads(system.split("JSON schema:\n", 1)[1])["title"]
        if title == "AnalysisPlan":
            out = PLAN
        elif title == "Claims":
            out = deepcopy(self.case["draft"])
            if data["phase"] == "post_supplement":
                out.update(request_evidence=None, supplement_need=None,
                           supplement_assessment={"fact_ids": ["daily_peak:current"], "conclusion": "descriptive_only"})
        else:
            out = {"decision": "accept", "checked_claim_ids": [c["claim_id"] for c in data["claims"]["claims"]]}
            if self.policy == "reference":
                out["drop_claim_ids"] = self.case["bad_claim_ids"]
                if self.case["id"] == "missing_department":
                    out.update(highlight_fact_ids=["department:3", "department:4"], issues=["missing_key_fact"])
            elif self.case["id"] == "false_deletion_control":
                out["drop_claim_ids"] = ["up"]
            if self.case["allow_daily"] and data["supplement_available"]:
                out.update(decision="request_evidence", evidence_request="daily_counts",
                           supplement_need=self.case["draft"]["supplement_need"])
            elif self.policy == "faulty" and self.case["id"] == "unnecessary_supplement":
                out.update(decision="request_evidence", evidence_request="daily_counts")
            if data["phase"] == "post_supplement":
                out["supplement_assessment"] = {"fact_ids": ["daily_peak:current"], "conclusion": "descriptive_only"}
        return ModelResult(json.dumps(out, ensure_ascii=False), self.name, self.model,
                           f"fixture-{len(self.calls)}", 0, usage_source="unknown")


class LiteralTools:
    def __init__(self, case):
        self.case, self.calls = case, []

    async def tools_call(self, name, arguments, context):
        if name != "analysis_evidence" or context["tool_context_token"] != "fixture-token":
            raise AssertionError("unexpected tool boundary")
        ACTIVE_QUERY_BUDGET.get().consume("mcp")
        self.calls.append(deepcopy(arguments))
        raw = deepcopy(self.case["raw"])
        if arguments["detail"] != "daily":
            raw.pop("daily")
        return raw


def measure(case, result, calls):
    """Score reviewer outputs, not program cleanup; retain explicit denominators."""
    reviews = [t for t in result.get("trace", []) if t["role"] == "Reviewer"]
    drops = {cid for t in reviews for cid in t["output"].get("drop_claim_ids", [])}
    bad, keep = set(case["bad_claim_ids"]), set(case["keep_claim_ids"])
    flagged = {cid for t in reviews for cid in t.get("input_invalid_claim_ids", [])}
    evidence = {e["evidence_id"]: e["result"]["facts"] for e in result.get("evidence", [])}
    grounded = {c["fact_id"] for c in result.get("claims", []) if c.get("fact_id")
                and c.get("kind") in {"fact", "statistical_contribution"}
                and c.get("evidence_ids") and all(e in evidence for e in c["evidence_ids"])
                and any(c["fact_id"] in evidence[e] and evidence[e][c["fact_id"]] == result.get("facts", {}).get(c["fact_id"])
                        for e in c["evidence_ids"])}
    required = set(case["required_fact_ids"])
    daily = sum(c["detail"] == "daily" for c in calls)
    numerator = len(drops & bad)
    return {"reviewer_error_detection": {"numerator": numerator, "denominator": len(bad) if reviews else 0,
                "value": numerator / len(bad) if reviews and bad else None},
            "reviewer_false_deletion": {"numerator": len(drops & keep), "denominator": len(keep) if reviews else 0,
                "value": len(drops & keep) / len(keep) if reviews and keep else None},
            "key_fact_coverage": {"numerator": len(grounded & required), "denominator": len(required),
                "value": len(grounded & required) / len(required)},
            "removed_program_flagged": sorted(drops & flagged),
            "other_removed_requires_adjudication": sorted(drops - flagged),
            "highlighted_program_suggested": sorted({f for t in reviews for f in t["output"].get("highlight_fact_ids", [])
                if f in t.get("input_missing_focus_fact_ids", [])}),
            "unnecessary_supplement_requests": sum(t["output"].get("decision") == "request_evidence" for t in reviews)
                if not case["allow_daily"] else 0,
            "unnecessary_daily_queries": daily if not case["allow_daily"] else 0,
            "daily_queries": daily, "scripted_role_calls": result.get("usage", {}).get("model_calls", 0),
            "real_model_calls": 0, "tokens": None,
            "note": "Controlled scripted replies, not Reviewer intelligence; tokens/cost are unmeasured. Coverage concerns AI claims, not the full statistics table."}


async def run_case(scenario, arm):
    case = fixture(scenario)
    mode = "dual" if arm.startswith("dual") else arm
    adapter, tools = ScriptedRoles(case, "faulty" if arm == "dual_faulty" else "reference"), LiteralTools(case)
    request = AnalysisRequest.model_validate({"analysis_context": case["context"], "mode": mode,
        "invocation_id": "fixture-inv", "tool_context_token": "fixture-token"})
    events = []

    async def emit(event):
        events.append(event["event"])

    result = await AttributionTeam(adapter, tools, request).run(emit)
    return {"case": scenario, "arm": arm, "result": result, "events": events,
            "observed": measure(case, result, tools.calls)}


async def main():
    rows = [await run_case(case, arm) for case in SCENARIOS
            for arm in ("deterministic", "single", "dual_reference", "dual_faulty")]
    indexed = {(r["case"], r["arm"]): r for r in rows}
    checks = {
        "bounded_calls": all(r["observed"]["scripted_role_calls"] <= 4 and r["observed"]["daily_queries"] <= 1 for r in rows),
        "reference_omission_repaired": indexed["missing_department", "dual_reference"]["observed"]["key_fact_coverage"]["value"] == 1,
        "faulty_omission_observed": indexed["missing_department", "dual_faulty"]["observed"]["key_fact_coverage"]["value"] < 1,
        "false_deletion_observed": indexed["false_deletion_control", "dual_faulty"]["observed"]["reviewer_false_deletion"]["numerator"] == 1,
        "unnecessary_query_blocked": indexed["unnecessary_supplement", "dual_faulty"]["observed"]["unnecessary_daily_queries"] == 0,
        "program_cleanup_not_reviewer_credit": all(indexed[c, "dual_faulty"]["observed"]["reviewer_error_detection"]["value"] == 0
            for c in ("unknown_fact", "cause_as_fact", "zero_baseline_growth")),
        "supplement_assessed": indexed["supported_supplement", "dual_reference"]["result"].get("supplement_assessment", {}).get("conclusion") == "descriptive_only",
    }
    sources = [Path(__file__), *sorted((ROOT / "hrchat-ai/agentscope_teams").glob("*.py"))]
    report = {"version": "r5-reviewer-injection-v1", "real_model_calls": 0,
              "purpose": "32 controlled offline scenarios through actual LangGraph/AgentScope; no model-quality claim",
              "source_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
              "checks": checks, "passed": all(checks.values()), "cases": rows}
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    out = ROOT / "docs/evaluation-runs" / ("r5-reviewer-" + run_id)
    out.mkdir(parents=True)
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(out / "report.json"), "scenarios": len(rows), "real_model_calls": 0,
                      "checks": checks, "passed": report["passed"]}))
    if not report["passed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    asyncio.run(main())
