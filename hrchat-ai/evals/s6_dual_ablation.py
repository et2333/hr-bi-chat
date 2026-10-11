"""Controlled single-vs-dual ablation: isolates the Reviewer's marginal value.

Offline, scripted roles over the real LangGraph/AgentScope team. A controlled
experiment, not held-out evidence and not a model-quality claim. It answers the
question the fixed S6 set cannot: under which inputs, if any, does the Reviewer
change the final claims at all.

Two controlled inputs share one arithmetic anchor (group 2026-07 -> 2026-08,
three changed departments):
  * complete: the scripted Analyst cites every changed department.
  * omit:     the scripted Analyst cites only ``overall``, dropping the changed
              departments — a coverage gap the program detects via
              ``missing_focus_fact_ids`` but does not itself repair.

The Reviewer may repair the gap only by highlighting existing facts, so the
measured gain is the coverage of program-identified focus facts, never new data.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path
import uuid

from adapters.model_usage import ModelResult
from adapters.query_budget import QueryBudget
from agentscope_teams.attribution_team import AttributionTeam
from evals.s6_dataset import PERIODS, dataset_identity, load_dataset
from evals.s6_run import ROOT, FixtureTools, behavior_observations, grounded_fact_ids, make_request

MODES = ("single", "dual")
CASE = {
    "case_id": "ablation-jul-aug", "group_id": "ablation", "split": "dev", "origin": "injected",
    "injection": None, "org_ids": list(range(1, 9)),
    "current_period": PERIODS["aug"], "baseline_period": PERIODS["jul"],
}


class AblationAdapter:
    """Scripted roles; ``omit_changed`` decides whether the Analyst drops coverage."""

    name = "ablation"
    model = "s6-ablation-scripted-v1"

    def __init__(self, omit_changed: bool):
        self.omit_changed = omit_changed
        self.calls = 0

    async def complete_analysis(self, system, user):
        self.calls += 1
        schema = json.loads(system.split("JSON schema:\n", 1)[1])
        data = json.loads(user)
        title = schema["title"]
        if title == "AnalysisPlan":
            reply = {"method": "department_contribution",
                     "steps": ["compare_totals", "department_delta", "check_closure"]}
        elif title == "Claims":
            claims = [{"claim_id": "overall", "kind": "fact", "fact_id": "overall",
                       "evidence_ids": ["ev_department"]}]
            if not self.omit_changed:
                for fact_id in data["facts"]:
                    if fact_id.startswith("department:"):
                        claims.append({"claim_id": fact_id, "kind": "statistical_contribution",
                                       "fact_id": fact_id, "evidence_ids": ["ev_department"]})
            reply = {"claims": claims}
        elif title == "ReviewResult":
            reply = {"decision": "accept",
                     "checked_claim_ids": [c["claim_id"] for c in data["claims"]["claims"]],
                     "drop_claim_ids": data["invalid_claim_ids"],
                     "highlight_fact_ids": data["missing_focus_fact_ids"], "issues": []}
        else:
            raise AssertionError("unrecognized role contract: " + title)
        return ModelResult(json.dumps(reply, ensure_ascii=False), self.name, self.model,
                           "ablation_" + uuid.uuid4().hex, elapsed_ms=0, finish_reason="stop",
                           usage_source="unknown")


async def run_arm(mode, omit_changed):
    adapter, tools = AblationAdapter(omit_changed), FixtureTools(CASE)
    budget = QueryBudget(timeout_seconds=60, max_model_calls=4, max_mcp_attempts=6)
    team = AttributionTeam(adapter, tools, make_request(CASE, mode), budget=budget)

    async def emit(_event):
        return None

    result = await team.run(emit)
    grounded = grounded_fact_ids(result)
    changed = {f"department:{r['org_id']}" for r in result.get("contributions", []) if r["delta"] != 0}
    return {"mode": mode, "omit_changed": omit_changed, "status": result["status"],
            "model_calls": result["usage"].get("model_calls", 0),
            "changed_departments_available": len(changed),
            "changed_departments_cited": len(changed & grounded),
            "reviewer_removed_program_flagged": behavior_observations(result, tools.calls)["reviewer_removed_program_flagged"],
            "reviewer_other_removed_unverified": behavior_observations(result, tools.calls)["reviewer_other_removed_unverified"]}


async def run():
    manifest, _ = load_dataset()
    complete = [await run_arm(mode, False) for mode in MODES]
    omit = [await run_arm(mode, True) for mode in MODES]
    by = {(row["omit_changed"], row["mode"]): row for row in complete + omit}
    # Expected pattern: when the Analyst is complete, dual changes nothing; when the
    # Analyst omits, only dual repairs the program-identified coverage gap.
    checks = {
        "complete_dual_changes_nothing": by[(False, "dual")]["changed_departments_cited"]
                                         == by[(False, "single")]["changed_departments_cited"]
                                         == by[(False, "single")]["changed_departments_available"],
        "omit_single_has_gap": by[(True, "single")]["changed_departments_cited"] == 0,
        "omit_dual_repairs_gap": by[(True, "dual")]["changed_departments_cited"]
                                == by[(True, "dual")]["changed_departments_available"] > 0,
        "dual_adds_one_call": all(by[(omit, "dual")]["model_calls"] - by[(omit, "single")]["model_calls"] == 1
                                  for omit in (False, True)),
        "no_independent_finding": all(row["reviewer_other_removed_unverified"] == []
                                      for row in complete + omit),
    }
    return {"schema_version": "1", "run_kind": "offline_controlled_dual_ablation",
            "created_at": datetime.now(timezone.utc).isoformat(),
            "dataset": dataset_identity(manifest), "case": CASE, "modes": list(MODES),
            "arms": complete + omit, "checks": checks, "passed": all(checks.values()),
            "conclusion": (
                "Reviewer 的唯一可测增量是在 Analyst 遗漏时，修复程序已通过 missing_focus_fact_ids 指出的覆盖缺口；"
                "Analyst 完整时双 Agent 不改变任何结论，仅多一次复核。这不是独立发现：Reviewer 只能高亮既有事实。"),
            "limitations": [
                "Scripted roles: no real-model quality, tokens, cost or latency conclusion.",
                "Controlled derived input (one arithmetic anchor), not a held-out or natural-failure sample.",
                "Coverage gain concerns which verified facts are cited, not business value or causal accuracy."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = asyncio.run(run())
    run_id = "s6-dual-ablation-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = args.output or ROOT / "docs/evaluation-runs" / run_id / "report.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(path), "run_kind": report["run_kind"], "checks": report["checks"],
                      "passed": report["passed"]}, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
