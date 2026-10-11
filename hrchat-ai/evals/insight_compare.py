"""Group A/B insight comparison: template vs LLM interpretation, partial vs full context.

Group A fixes the fact bundle and compares the programmatic template arm (no model
call) against the LLM interpretation arm. Group B removes one context variable and
checks the model degrades instead of asserting the removed evidence. Group C
(single vs dual Agent) is reused from evals.s6_real and is not duplicated here.

The offline arm uses a scripted role adapter and never loads credentials or opens a
network connection. Real runs require --confirm-real-calls and are bounded.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelResult
from insight.fact_builder import build_fact_bundle
from insight.schema import InsightRequest
from insight.capabilities import CAPABILITY_LEAVE_COUNT_DEPT, resolve_action, suggestion_action
from insight.schema import NextAction
from insight.service import handle_insight_async
from evals.insight_scenarios import build_request, dataset_identity, load_dataset, score_ablation, score_insight
from evals.s5_compare import source_fingerprint
from evals.s6_run import ROOT


class FixtureInsightAdapter(ModelAdapter):
    """Scripted 'correct model' role for offline plumbing only; not a quality claim."""

    name = "insight-fixture"
    model = "insight-scripted-v1"
    supports_query_plan = False

    def __init__(self, request: InsightRequest):
        self.request = request
        self.calls = 0

    async def generate(self, prompt: str) -> str:  # pragma: no cover - complete_analysis is used
        raise NotImplementedError

    async def complete_analysis(self, system_prompt: str, user_prompt: str) -> ModelResult:
        self.calls += 1
        request = self.request
        bundle = build_fact_bundle(request.context, request.snapshot)
        computable = [f for f in bundle.facts if f.computable]
        selected = computable[:8]
        claims = []
        for idx, fact in enumerate(selected, start=1):
            claims.append({
                "claim_id": f"f{idx}", "kind": "fact", "text": fact.label or fact.fact_id,
                "fact_ids": [fact.fact_id], "predicate": _predicate(fact),
                "unit": fact.unit, "scope_ref": fact.scope_ref,
            })
        text = json.dumps({
            "selected_fact_ids": [f.fact_id for f in selected],
            "claims": claims, "limitations": [], "next_actions": _next_actions(request),
        }, ensure_ascii=False)
        return ModelResult(text, self.name, self.model, f"fixture-insight-{idx if selected else 0}",
                           elapsed_ms=0, finish_reason="stop", usage_source="unknown")


def _predicate(fact) -> str | None:
    kind = fact.fact_type.value
    if kind == "department_contribution" and fact.contribution_kind == "offset":
        return "offsetting_departments"
    if fact.value is None:
        return None
    if kind == "absolute_change":
        return "absolute_increase" if fact.value > 0 else "absolute_decrease" if fact.value < 0 else None
    if kind == "relative_change":
        return "relative_increase" if fact.value > 0 else "relative_decrease" if fact.value < 0 else None
    if kind == "tied_extreme":
        return "tied_extreme"
    return None


def _next_actions(request: InsightRequest) -> list[dict]:
    ctx = request.context
    if ctx.metric_code == "leave_count":
        drafted = resolve_action(NextAction(
            action_id="leave_dept_1", capability_id=CAPABILITY_LEAVE_COUNT_DEPT,
            question="进一步分析两期部门贡献",
            params={"metric_code": ctx.metric_code,
                    "current_period": ctx.current_period.model_dump(mode="json"),
                    "baseline_period": ctx.baseline_period.model_dump(mode="json") if ctx.baseline_period else None,
                    "effective_org_ids": list(ctx.effective_org_ids)},
        ), ctx)
        return [drafted.model_dump(mode="json")]
    return [suggestion_action(action_id="manual_1", question="结合业务背景人工核查异常点").model_dump(mode="json")]


async def _interpret(context, snapshot, adapter):
    body = {"context": context, "snapshot": snapshot}
    return await handle_insight_async(body, adapter)


async def run_group_a(cases, adapter_factory, *, offline):
    rows, calls = [], 0
    for case in cases:
        template = await handle_insight_async({"context": case["context"], "snapshot": case["snapshot"]}, None)
        rows.append({"case_id": case["case_id"], "arm": "template", "result": template,
                     "score": score_insight(case, template, "template"), "calls": 0})
        adapter = adapter_factory(case["context"], case["snapshot"])
        result = await _interpret(case["context"], case["snapshot"], adapter)
        used = getattr(adapter, "calls", 0)
        calls += used
        rows.append({"case_id": case["case_id"], "arm": "llm", "result": result,
                     "score": score_insight(case, result, "llm"), "calls": used})
    return rows, calls


async def run_group_b(ablations, adapter_factory):
    rows, calls = [], 0
    for ablation in ablations:
        full_adapter = adapter_factory(ablation["context_full"], ablation["snapshot_full"])
        full = await _interpret(ablation["context_full"], ablation["snapshot_full"], full_adapter)
        partial_adapter = adapter_factory(ablation["context_partial"], ablation["snapshot_partial"])
        partial = await _interpret(ablation["context_partial"], ablation["snapshot_partial"], partial_adapter)
        used = getattr(full_adapter, "calls", 0) + getattr(partial_adapter, "calls", 0)
        calls += used
        rows.append({"ablation_id": ablation["ablation_id"], "scenario": ablation["scenario"],
                     "full": full, "partial": partial,
                     "score": score_ablation(ablation, full, partial), "calls": used})
    return rows, calls


def _summarize(group_a, group_b):
    llm = [r for r in group_a if r["arm"] == "llm"]
    template = [r for r in group_a if r["arm"] == "template"]
    return {
        "group_a": {
            "template_ok": sum(r["score"]["ok"] for r in template), "total": len(template),
            "template_claim_coverage_numerator": sum(r["score"]["coverage"]["numerator"] for r in template),
            "llm_ok": sum(r["score"]["ok"] for r in llm),
            "llm_coverage_numerator": sum(r["score"]["coverage"]["numerator"] for r in llm),
            "coverage_denominator": sum(r["score"]["coverage"]["denominator"] for r in llm),
            "note": "Template arm emits no claims by design; coverage compares interpretation only, not arithmetic.",
        },
        "group_b": {"ok": sum(r["score"]["ok"] for r in group_b), "total": len(group_b)},
    }


async def run(*, group="all", model="qwen-plus", offline=True, max_calls=60, prepare_only=False, output=None):
    manifest, cases, ablations = load_dataset()
    run_kind = "offline_scripted_insight_compare" if offline else "real_model_insight_compare"
    run_dir = output.parent if output else ROOT / "docs/evaluation-runs" / (
        "insight-compare-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    run_dir.mkdir(parents=True, exist_ok=True)
    planned = 0
    if group in {"a", "all"}:
        planned += len(cases)
    if group in {"b", "all"}:
        planned += 2 * len(ablations)
    plan = {"run_kind": run_kind, "group": group, "requested_model": model, "offline": offline,
            "dataset": dataset_identity(manifest), "source_sha256": source_fingerprint(),
            "max_model_calls_total": max_calls, "planned_calls": planned,
            "note": "Group C (single vs dual Agent) is reused from evals.s6_real; see README."}
    (run_dir / "manifest.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
    if prepare_only:
        print(json.dumps({"plan": str(run_dir / "manifest.json"), "planned_calls": planned, "actual_calls": 0}))
        return 0

    real_adapter = None
    if offline:
        def adapter_factory(context, snapshot):
            request = InsightRequest.model_validate({"context": context, "snapshot": snapshot})
            return FixtureInsightAdapter(request)
    else:
        from adapters.local_env import load_local_model_env
        from adapters.openai_client import OpenAIClientImpl
        load_local_model_env()
        real_adapter = OpenAIClientImpl(model=model)

        def adapter_factory(context, snapshot):
            return real_adapter

    group_a, group_b, calls = [], [], 0
    try:
        if group in {"a", "all"}:
            group_a, calls_a = await run_group_a(cases, adapter_factory, offline=offline)
            calls += calls_a
        if group in {"b", "all"}:
            group_b, calls_b = await run_group_b(ablations, adapter_factory)
            calls += calls_b
    finally:
        if real_adapter is not None:
            await real_adapter.aclose()

    report = {"schema_version": "1", "created_at": datetime.now(timezone.utc).isoformat(),
              "manifest": plan, "group_a": group_a, "group_b": group_b,
              "summary": {"calls": calls, **_summarize(group_a, group_b)},
              "limitations": [
                  "Offline arms use a scripted role adapter; they prove plumbing, not model quality.",
                  "Fixed simulated scenarios are engineering regressions, not real-user generalization evidence.",
                  "Coverage counts AI claims over verified facts, not table completeness or causal correctness.",
              ]}
    report_path = output or run_dir / "report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(report_path), "run_kind": run_kind, "calls": calls,
                      "summary": report["summary"]}, ensure_ascii=False))
    return 0 if calls <= max_calls else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group", choices=("a", "b", "all"), default="all")
    parser.add_argument("--model", default="qwen-plus")
    parser.add_argument("--offline", action="store_true", help="Scripted adapter, zero paid calls (default)")
    parser.add_argument("--real", dest="offline", action="store_false", help="Use the configured real model")
    parser.add_argument("--max-calls", type=int, default=60)
    parser.add_argument("--confirm-real-calls", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.set_defaults(offline=True)
    args = parser.parse_args()
    if not args.offline and not args.confirm_real_calls and not args.prepare_only:
        parser.error("Real mode sends synthetic HR aggregates to a model API; explicitly confirm with --confirm-real-calls")
    return asyncio.run(run(group=args.group, model=args.model, offline=args.offline,
                           max_calls=args.max_calls, prepare_only=args.prepare_only, output=args.output))


if __name__ == "__main__":
    raise SystemExit(main())
