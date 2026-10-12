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
import hashlib
import json
from pathlib import Path

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelResult
from insight.fact_builder import build_fact_bundle
from insight.schema import InsightRequest
from insight.capabilities import CAPABILITY_LEAVE_COUNT_DEPT, resolve_action, suggestion_action
from insight.schema import NextAction
from insight.service import handle_insight_async
from evals.insight_scenarios import (
    ORACLE_VERSION,
    SCORING_VERSION,
    build_request,
    dataset_identity,
    load_dataset,
    score_ablation,
    score_insight,
)
from evals.s5_compare import source_fingerprint
from evals.s6_run import ROOT


# 归档原始模型输出：仅离线/评测归档，绝不进入生产结果或任务 API。
_ARCHIVED_TEXT_LIMIT = 4000


def _archived_text(text: str) -> dict:
    """Keep the raw model text (truncated) plus its digest, so a failed arm can be diagnosed."""
    raw = text or ""
    return {
        "text": raw[:_ARCHIVED_TEXT_LIMIT],
        "text_sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
        "text_length": len(raw),
        "truncated": len(raw) > _ARCHIVED_TEXT_LIMIT,
    }


class RecordingInsightAdapter(ModelAdapter):
    """Wraps an adapter and archives every raw model response for this arm.

    ``calls`` reports this arm's own model-call count, so ``summary.calls`` is accurate for the
    real adapter too (it exposes no ``calls`` attribute of its own).
    """

    name = "recording"

    def __init__(self, inner: ModelAdapter):
        self._inner = inner
        self.model_io: list[dict] = []

    @property
    def calls(self) -> int:
        return len(self.model_io)

    def _record(self, result: ModelResult) -> ModelResult:
        self.model_io.append({
            "index": len(self.model_io) + 1,
            "model": getattr(result, "model", None),
            "request_id": getattr(result, "request_id", None),
            "finish_reason": getattr(result, "finish_reason", None),
            **_archived_text(getattr(result, "text", "")),
        })
        return result

    async def generate(self, prompt: str) -> str:
        return await self._inner.generate(prompt)

    async def complete_analysis(self, system_prompt: str, user_prompt: str) -> ModelResult:
        return self._record(await self._inner.complete_analysis(system_prompt, user_prompt))


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
            segments = [{"type": "text", "text": "重点关注"}]
            if fact.value is not None:
                segments += [
                    {"type": "text", "text": "："},
                    {"type": "fact", "fact_id": fact.fact_id, "field": "value"},
                    {"type": "fact", "fact_id": fact.fact_id, "field": "unit"},
                ]
            claims.append({
                "claim_id": f"f{idx}", "kind": "fact", "segments": segments,
                "fact_ids": [fact.fact_id], "predicate": _predicate(fact),
                "unit": fact.unit, "scope_ref": fact.scope_ref,
            })
        text = json.dumps({
            "selected_fact_ids": [f.fact_id for f in selected],
            "claims": claims,
            "narrative": {
                "summary_claim_id": claims[0]["claim_id"] if claims else None,
                "finding_claim_ids": [c["claim_id"] for c in claims[1:4]],
            },
            "limitations": sorted({
                f"{f.fact_type.value}:{f.incomputable_reason or 'incomputable'}"
                for f in bundle.facts if not f.computable
            }),
            "next_actions": _next_actions(request),
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
                     "score": score_insight(case, result, "llm"), "calls": used,
                     "model_io": _drain(adapter)})
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
                     "score": score_ablation(ablation, full, partial), "calls": used,
                     "model_io": {"full": _drain(full_adapter), "partial": _drain(partial_adapter)}})
    return rows, calls


def _drain(adapter) -> list[dict]:
    """Raw model I/O captured for this arm (empty when the adapter records nothing)."""
    return getattr(adapter, "model_io", [])


def _tally(rows):
    """Counts are reported separately so degradation/coverage/execution loss is not averaged away."""
    return {
        "total": len(rows),
        "ok": sum(r["score"]["ok"] for r in rows),
        "degraded": sum(r["score"]["degraded"] for r in rows),
        "coverage_incomplete": sum(bool(r["score"]["coverage"]["missing"]) for r in rows),
        "value_errors": sum(bool(r["score"]["value_errors"]) for r in rows),
        "claim_ref_errors": sum(bool(r["score"]["claim_ref_errors"]) for r in rows),
        "visible_errors": sum(bool(r["score"]["visible_errors"]) for r in rows),
        "action_param_errors": sum(bool(r["score"]["action_param_errors"]) for r in rows),
    }


def _summarize(group_a, group_b):
    llm = [r for r in group_a if r["arm"] == "llm"]
    template = [r for r in group_a if r["arm"] == "template"]
    return {
        "scoring_version": SCORING_VERSION,
        "oracle_version": ORACLE_VERSION,
        "scoring_note": ("ok requires a complete status, full required coverage, the visible narrative re-derived "
                         "from each claim's fact slots, disclosed incomputable facts and same-source version identity."),
        "group_a": {
            "template": _tally(template),
            "llm": _tally(llm),
            "template_ok": sum(r["score"]["ok"] for r in template),
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
            "scoring_version": SCORING_VERSION, "oracle_version": ORACLE_VERSION,
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
            return RecordingInsightAdapter(FixtureInsightAdapter(request))
    else:
        from adapters.local_env import load_local_model_env
        from adapters.openai_client import OpenAIClientImpl
        load_local_model_env()
        real_adapter = OpenAIClientImpl(model=model)

        def adapter_factory(context, snapshot):
            return RecordingInsightAdapter(real_adapter)

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


def rescore_archived(report_path, output=None):
    """Re-score archived arm outputs with the current rules; the archived report is kept.

    No model call is made: the stored ``result`` payloads are re-scored offline so an
    older scoring version stays available for comparison.
    """
    report = json.loads(Path(report_path).read_text(encoding="utf-8"))
    manifest, cases, ablations = load_dataset()
    case_by_id = {c["case_id"]: c for c in cases}
    ablation_by_id = {a["ablation_id"]: a for a in ablations}

    rows, changed = [], 0
    for row in report.get("group_a", []):
        case = case_by_id.get(row.get("case_id"))
        if case is None or not isinstance(row.get("result"), dict):
            continue
        score = score_insight(case, row["result"], row.get("arm", "llm"))
        previous = (row.get("score") or {}).get("ok")
        changed += previous is not None and bool(previous) != bool(score["ok"])
        rows.append({"case_id": row["case_id"], "arm": row.get("arm"),
                     "previous_ok": previous, "new_ok": score["ok"], "score": score})
    ab_rows, ab_changed = [], 0
    for row in report.get("group_b", []):
        ablation = ablation_by_id.get(row.get("ablation_id"))
        if ablation is None or not isinstance(row.get("full"), dict) or not isinstance(row.get("partial"), dict):
            continue
        score = score_ablation(ablation, row["full"], row["partial"])
        previous = (row.get("score") or {}).get("ok")
        ab_changed += previous is not None and bool(previous) != bool(score["ok"])
        ab_rows.append({"ablation_id": row["ablation_id"], "previous_ok": previous,
                        "new_ok": score["ok"], "score": score})

    group_a = [{"arm": r["arm"], "score": r["score"]} for r in rows]
    group_b = [{"score": r["score"]} for r in ab_rows]
    rescored = {
        "schema_version": "1", "created_at": datetime.now(timezone.utc).isoformat(),
        "run_kind": "offline_rescore_of_archived_output",
        "archived_report": str(report_path),
        "archived_run_kind": (report.get("manifest") or {}).get("run_kind"),
        "scoring_version": SCORING_VERSION, "oracle_version": ORACLE_VERSION,
        "dataset": dataset_identity(manifest),
        "calls": 0,
        "note": "No model was called; archived outputs were re-scored under the current rules. "
                "The original report is left untouched.",
        "group_a": rows, "group_b": ab_rows,
        "summary": {"scoring_version": SCORING_VERSION, "oracle_version": ORACLE_VERSION,
                    "group_a_rows": len(rows), "group_a_ok_changed": changed,
                    "group_b_rows": len(ab_rows), "group_b_ok_changed": ab_changed,
                    **_summarize(group_a, group_b)},
    }
    target = Path(output) if output else Path(report_path).with_name(
        f"rescore-{SCORING_VERSION}-{Path(report_path).parent.name}.json")
    target.write_text(json.dumps(rescored, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"rescore": str(target), "archived": str(report_path),
                      "group_a_ok_changed": changed, "group_b_ok_changed": ab_changed}, ensure_ascii=False))
    return 0


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
    parser.add_argument("--rescore", type=Path, help="Re-score an archived report offline (no model call)")
    parser.set_defaults(offline=True)
    args = parser.parse_args()
    if args.rescore:
        return rescore_archived(args.rescore, args.output)
    if not args.offline and not args.confirm_real_calls and not args.prepare_only:
        parser.error("Real mode sends synthetic HR aggregates to a model API; explicitly confirm with --confirm-real-calls")
    return asyncio.run(run(group=args.group, model=args.model, offline=args.offline,
                           max_calls=args.max_calls, prepare_only=args.prepare_only, output=args.output))


if __name__ == "__main__":
    raise SystemExit(main())
