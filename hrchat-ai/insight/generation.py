"""Lightweight insight generation: facts → one structured model call → validate → result.

Reuses the tenant model adapter; it is a plain generation service, not an Agent/Team and
not a graph. The normal path spends exactly one model call. Only a single structural
repair is allowed; parse/validation failure after that degrades with the verified facts.
No model call is made when there are no computable facts.
"""
from __future__ import annotations

import json
from typing import Any

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelCallError, ModelResult
from insight.capabilities import list_capabilities
from insight.fact_builder import build_fact_bundle
from insight.schema import (
    Fact,
    FactBundle,
    InsightContext,
    InsightDraft,
    InsightRequest,
    InsightResult,
    InsightStatus,
)
from insight.validator import validate_draft

PROMPT_VERSION = "insight-prompt-2"
SCHEMA_REVISION = "insight-draft-1"
DRAFT_MARKER = "【洞察-DRAFT】"

_SYSTEM_PROMPT = (
    "你是 HR 报表洞察助手。只能使用给定的事实(JSON)选择重点并组织表达，"
    "不得新增、修改或推算任何数值、单位、日期与组织范围。"
    "事实优先，缺失不可当零，存量不可跨期求和，比率不可简单平均，"
    "缺月不推断连续趋势。若没有部门分项证据，不得断言部门贡献，只能建议进一步查看。"
    "只输出一个 JSON 对象（不要解释、不要代码块），字段："
    "selected_fact_ids(数组，优先 preferred 且 computable)、"
    "claims(数组，每项含 claim_id、kind(fact|limitation|next_check)、text、fact_ids、"
    "predicate、unit、scope_ref)。"
    "predicate 必须与其引用事实的类型和方向一致，无法对应时必须填 null："
    "absolute_increase/absolute_decrease 只能引用方向一致的 absolute_change 事实；"
    "relative_increase/relative_decrease 只能引用方向一致的 relative_change 事实；"
    "point_higher/point_lower 只能引用方向一致的绝对变化事实；"
    "tied_extreme 只能引用 tied_extreme 事实；"
    "department_net_contribution 只能引用部门分项(department_delta)事实；"
    "offsetting_departments 只能引用部门抵消(offset)事实；"
    "continuous_trend、period_over_period、cross_period_sum、point_compare 等事实一律用 null。"
    "limitations(数组，字符串)、"
    "next_actions(数组，每项含 action_id、capability_id、question、params、missing_params、executable；"
    "capability_id 必须来自给定能力目录，禁止编造；"
    "仅当报告指标与能力的 metric_code 一致时才给出该能力，否则用 capability_id=manual_review)。"
    "next_actions 的 params 必须是对象且只能取自给定上下文："
    "metric_code 用指标 code；current_period、baseline_period 用 "
    "{\"start\":\"YYYY-MM-DD\",\"end\":\"YYYY-MM-DD\"} 对象，日期须与给定的本期/基期完全一致；"
    "effective_org_ids 用给定组织范围的整数数组；"
    "上下文未提供的参数（如缺失的基期）不得补造，须写入 missing_params 且 executable 为 false。"
    "claims 中 kind=limitation 或 next_check 的条目可以不填 fact_ids；kind=fact 的断言必须引用 fact_ids。"
)


def build_prompt(context: InsightContext, bundle: FactBundle) -> tuple[str, str]:
    """System + user prompt combining the report target with the current view facts."""
    facts_json = json.dumps(
        {
            "facts": [
                {
                    "fact_id": f.fact_id,
                    "fact_type": f.fact_type.value,
                    "label": f.label,
                    "value": f.value,
                    "unit": f.unit,
                    "scope_ref": f.scope_ref,
                    "computable": f.computable,
                    "preferred": f.preferred,
                }
                for f in bundle.facts
            ]
        },
        ensure_ascii=False,
    )
    capabilities_json = json.dumps(
        [{"capability_id": c["capability_id"], "metric_code": c.get("metric_code"),
          "description": c.get("description"), "executable": c.get("executable"),
          "required_params": c.get("required_params", [])} for c in list_capabilities()],
        ensure_ascii=False,
    )
    target = (
        f"报告：{context.report_name or context.report_id}；指标：{context.metric_name or context.metric_code}"
        f"（单位 {context.unit}）；本期 {context.current_period.start}~{context.current_period.end}"
    )
    if context.baseline_period is not None:
        target += f"；基期 {context.baseline_period.start}~{context.baseline_period.end}"
    orgs = ",".join(str(o) for o in context.effective_org_ids) if context.effective_org_ids else "全部"
    target += f"；组织范围 {orgs}"
    user_prompt = (
        f"{DRAFT_MARKER}\n"
        f"分析目标：{target}\n"
        f"事实(JSON)：{facts_json}\n"
        f"能力目录(JSON)：{capabilities_json}\n"
        "请基于上述事实输出洞察草稿 JSON。"
    )
    return _SYSTEM_PROMPT, user_prompt


async def generate_insight(request: InsightRequest, adapter: ModelAdapter | None) -> InsightResult:
    """Run the lightweight generation chain for a rich request."""
    assert request.context is not None and request.snapshot is not None
    bundle = build_fact_bundle(request.context, request.snapshot)
    computable = [f for f in bundle.facts if f.computable]

    if adapter is None:
        return _degrade(request, bundle, "model_unavailable")
    if not computable:
        # No verified data — do not spend a model call.
        return _degrade(request, bundle, "no_verified_facts")

    system_prompt, user_prompt = build_prompt(request.context, bundle)
    calls = 0

    try:
        result = await adapter.complete_analysis(system_prompt, user_prompt)
    except ModelCallError as exc:
        return _degrade(request, bundle, "model_call_failed", calls=1, model=exc.result)
    except Exception:  # noqa: BLE001 — adapter failure degrades, never 500s
        return _degrade(request, bundle, "model_unavailable", calls=1, model=None)
    calls += 1
    draft = _parse_draft(result.text)
    validation = None if draft is None else validate_draft(draft, bundle, request.context)

    if draft is None or not validation.ok:
        # Single structural repair attempt, then degrade with the verified facts.
        reason = "结构不是合法 JSON" if draft is None else _format_errors(validation)
        repaired = await _repair(adapter, system_prompt, user_prompt, result.text, reason)
        calls += 1
        if repaired is None:
            return _degrade(request, bundle,
                            "draft_parse_failed" if draft is None else "draft_validation_failed",
                            calls=calls, model=result)
        draft, result = repaired
        validation = validate_draft(draft, bundle, request.context)
        if not validation.ok:
            return _degrade(request, bundle, "draft_validation_failed", calls=calls, model=result)

    selected = [f for f in bundle.facts if f.fact_id in set(draft.selected_fact_ids) and f.computable]
    if not selected:
        selected = [f for f in bundle.facts if f.computable and f.preferred] or computable
    return _result_from_facts(
        request, bundle.facts, selected,
        status=InsightStatus.COMPLETE,
        degradation_reason=None,
        claims=validation.accepted_claims,
        limitations=list(draft.limitations),
        actions=validation.accepted_actions,
        calls=calls,
        model=result,
    )


async def _repair(adapter, system_prompt, user_prompt, bad_text, reason):
    repair_prompt = (
        f"{user_prompt}\n\n上次输出无法使用（{reason}）。"
        "请仅修正结构并重新输出一个合法 JSON 对象，不要改动事实数值：\n"
        f"{bad_text[:2000]}"
    )
    try:
        result = await adapter.complete_analysis(system_prompt, repair_prompt)
    except Exception:  # noqa: BLE001 — repair failure just degrades
        return None
    draft = _parse_draft(result.text)
    return None if draft is None else (draft, result)


def _parse_draft(text: str) -> InsightDraft | None:
    if not text:
        return None
    candidate = text.strip()
    if candidate.startswith("```"):
        candidate = candidate.strip("`")
        candidate = candidate.split("\n", 1)[1] if "\n" in candidate else candidate
    start, end = candidate.find("{"), candidate.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return InsightDraft.model_validate(json.loads(candidate[start:end + 1]))
    except Exception:  # noqa: BLE001 — schema/JSON error is a fixable structural issue
        return None


def _format_errors(validation) -> str:
    return "；".join(f"{e.kind.value}:{e.message}" for e in validation.errors[:5]) or "校验未通过"


def _degrade(
    request: InsightRequest,
    bundle: FactBundle,
    reason: str,
    *,
    calls: int = 0,
    model: ModelResult | None = None,
) -> InsightResult:
    from insight.service import _default_actions, _default_limitations, _default_selected

    return _result_from_facts(
        request, bundle.facts, _default_selected(bundle.facts),
        status=InsightStatus.DEGRADED,
        degradation_reason=reason,
        claims=[],
        limitations=_default_limitations(bundle.facts),
        actions=_default_actions(request) if reason in {"no_verified_facts", "model_unavailable"} else [],
        calls=calls,
        model=model,
    )


def _result_from_facts(
    request: InsightRequest,
    all_facts: list[Fact],
    selected: list[Fact],
    *,
    status: InsightStatus,
    degradation_reason: str | None,
    claims,
    limitations: list[str],
    actions,
    calls: int,
    model: ModelResult | None,
) -> InsightResult:
    ctx = request.context
    assert ctx is not None
    from insight.service import _fact_to_point

    points = [_fact_to_point(f) for f in selected]
    if not points:
        summary = f"报告「{ctx.report_name}」暂无可用的已核验事实。"
    else:
        head = selected[0].label or "已核验事实"
        summary = f"报告「{ctx.report_name}」指标{ctx.metric_name or ctx.metric_code}：{head}"
    return InsightResult(
        report_name=ctx.report_name,
        metric_name=ctx.metric_name or ctx.metric_code,
        summary=summary,
        points=points,
        facts=all_facts,
        status=status,
        degradation_reason=degradation_reason,
        next_actions=list(actions),
        data_version=ctx.data_version,
        view_key=ctx.view_key,
        limitations=limitations,
        claims=list(claims),
        generation=_generation_evidence(model, calls),
    )


def _generation_evidence(model: ModelResult | None, calls: int) -> dict[str, Any]:
    return {
        "prompt_version": PROMPT_VERSION,
        "schema_revision": SCHEMA_REVISION,
        "call_count": calls,
        "repair_count": max(0, calls - 1),
        "provider": model.provider if model else None,
        "model": model.model if model else None,
        "finish_reason": model.finish_reason if model else None,
        "failure_kind": model.failure_kind if model else None,
        "elapsed_ms": model.elapsed_ms if model else None,
        "usage": model.usage if model else None,
        "usage_source": model.usage_source if model else "unknown",
    }
