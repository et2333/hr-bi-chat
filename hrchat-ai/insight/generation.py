"""Lightweight insight generation: facts → one structured model call → validate → result.

Reuses the tenant model adapter; it is a plain generation service, not an Agent/Team and
not a graph. The normal path spends exactly one model call. Only a single structural
repair is allowed; parse/validation failure after that degrades with the verified facts.
No model call is made when there are no computable facts.

Every model call (generate and, when needed, the single repair) is recorded with its own
index, purpose, provider request id, elapsed time, status and usage. Aggregate usage is
honest: a missing usage marks the run incomplete instead of fabricating a precise total.
"""
from __future__ import annotations

import json
import time
import uuid
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
    InsightPoint,
    InsightRequest,
    InsightResult,
    InsightStatus,
)
from insight.validator import validate_draft

PROMPT_VERSION = "insight-prompt-9"
SCHEMA_REVISION = "insight-draft-2"
DRAFT_MARKER = "【洞察-DRAFT】"

_SYSTEM_PROMPT = (
    "你是 HR 报表洞察助手。只能使用给定的事实(JSON)选择重点并组织表达，"
    "不得新增、修改或推算任何数值、单位、日期与组织范围。"
    "事实优先，缺失不可当零，存量不可跨期求和，比率不可简单平均，"
    "缺月不推断连续趋势。若没有部门分项证据，不得断言部门贡献，只能建议进一步查看。"
    "只输出一个 JSON 对象（不要解释、不要代码块），字段："
    "selected_fact_ids(数组，优先 preferred 且 computable)、"
    "claims(数组，每项含 claim_id、kind(fact|limitation|next_check)、segments、fact_ids、"
    "predicate、unit、scope_ref)，"
    "narrative(对象，含 summary_claim_id 与 finding_claim_ids 数组，仅引用上面 claims 的 claim_id；"
    "summary 为核心结论，finding 为重点，最多 3 条)、"
    "limitations(数组，字符串；每条必须是受控格式「fact_type:补充说明」，"
    "fact_type 取自事实(JSON)中 computable=false 的事实类型，且必须覆盖不可计算事实类型清单里的每一项；"
    "补充说明可为空，同样禁止出现任何数字、日期或单位，也禁止断言式因果)、"
    "next_actions(数组，每项含 action_id、capability_id、question、params、missing_params、executable；"
    "capability_id 必须来自给定能力目录，禁止编造；"
    "仅当报告指标与能力的 metric_code 一致时才给出该能力，否则用 capability_id=manual_review)。"
    "segments 是正文的组成片段，每项二选一："
    "{\"type\":\"text\",\"text\":\"...\"}，text 片段中禁止出现任何数字、日期或单位，"
    "必须改用事实槽位表达；text 必须非空——该处不需要文字时直接省略整个 text 片段，"
    "不要输出空字符串占位（如 {\"type\":\"text\",\"text\":\"\"}）；"
    "{\"type\":\"fact\",\"fact_id\":\"...\",\"field\":\"value|unit|period_label|scope_label|category\"}，"
    "field 只允许这些白名单取值，数值/日期/单位一律由事实槽位渲染；"
    "只有事实里确实带有对应字段时才可绑定该槽：period_label 需事实含非空 period_ref，"
    "scope_label 需非空 scope_ref；category 槽渲染事实的 category，"
    "tied_extreme 等并列事实的类目请用 category 槽（渲染其 tied_categories），不得凭空要求期间。"
    "禁止在 text 中使用未获支持的确定性因果表述（如“导致/因为/由于/造成/引起”）；"
    "但在 kind=limitation/next_check 的文本与 limitations 中，允许“由于数据缺失，无法判定…”"
    "这类限定表述，只要不做断言式因果归因。"
    "next_actions 的 params 必须是对象且只能取自给定上下文："
    "metric_code 用指标 code；current_period、baseline_period 用 "
    "{\"start\":\"YYYY-MM-DD\",\"end\":\"YYYY-MM-DD\"} 对象，日期须与给定的本期/基期完全一致；"
    "effective_org_ids 用给定组织范围的整数数组；"
    "上下文未提供的参数（如缺失的基期）不得补造，须写入 missing_params 且 executable 为 false。"
    "predicate 必须与其引用事实的类型和方向一致，无法对应时必须填 null："
    "absolute_increase/absolute_decrease 只能引用方向一致的 absolute_change 事实；"
    "relative_increase/relative_decrease 只能引用方向一致的 relative_change 事实；"
    "point_higher/point_lower 只能引用方向一致的绝对变化事实；"
    "tied_extreme 只能引用 tied_extreme 事实；"
    "department_net_contribution 只能引用部门分项(department_delta)事实；"
    "offsetting_departments 只能引用部门抵消(offset)事实；"
    "continuous_trend、period_over_period、cross_period_sum、point_compare 等事实一律用 null。"
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
                    "period_ref": f.period_ref,
                    "category": f.category,
                    "tied_categories": list(f.tied_categories or []),
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
    if context.analysis_goal:
        target += f"；分析目标：{context.analysis_goal}"
    if context.question_summary:
        # 仅在来源问数授权有效时由服务端填入；这是业务目标，不是可证明事实的证据。
        target += f"；来源问题（已授权，仅作目标参考）：{context.question_summary}"
    incomputable_types = sorted({f.fact_type.value for f in bundle.facts if not f.computable})
    user_prompt = (
        f"{DRAFT_MARKER}\n"
        f"分析目标：{target}\n"
        f"事实(JSON)：{facts_json}\n"
        f"不可计算的事实类型（每条都必须在 limitations 中以「fact_type:补充说明」披露）："
        f"{'、'.join(incomputable_types) if incomputable_types else '无'}\n"
        f"能力目录(JSON)：{capabilities_json}\n"
        "请基于上述事实输出洞察草稿 JSON。"
    )
    return _SYSTEM_PROMPT, user_prompt


async def generate_insight(request: InsightRequest, adapter: ModelAdapter | None) -> InsightResult:
    """Run the lightweight generation chain for a rich request."""
    assert request.context is not None and request.snapshot is not None
    correlation_id = _correlation_id(request)
    started = time.perf_counter()
    bundle = build_fact_bundle(request.context, request.snapshot)
    computable = [f for f in bundle.facts if f.computable]
    calls: list[dict[str, Any]] = []

    if adapter is None:
        return _degrade(request, bundle, "model_unavailable", calls=calls,
                        correlation_id=correlation_id, started=started)
    if not computable:
        # No verified data — do not spend a model call.
        return _degrade(request, bundle, "no_verified_facts", calls=calls,
                        correlation_id=correlation_id, started=started)

    system_prompt, user_prompt = build_prompt(request.context, bundle)

    try:
        result = await adapter.complete_analysis(system_prompt, user_prompt)
    except ModelCallError as exc:
        calls.append(_call_record(1, "generate", exc.result))
        return _degrade(request, bundle, "model_call_failed", calls=calls,
                        correlation_id=correlation_id, started=started)
    except Exception:  # noqa: BLE001 — adapter failure degrades, never 500s
        calls.append(_call_record(1, "generate", None))
        return _degrade(request, bundle, "model_unavailable", calls=calls,
                        correlation_id=correlation_id, started=started)

    calls.append(_call_record(1, "generate", result))
    draft = _parse_draft(result.text)
    validation = None if draft is None else validate_draft(draft, bundle, request.context)
    issues_log: list[dict[str, Any]] = []

    if draft is None or not validation.ok:
        # 首稿被拒的结构化原因：即使修复成功也保留，便于从归档判断提示与校验是否系统性不匹配。
        issues_log.append(_attempt_issues(1, "initial", draft, validation))
        # Single structural repair attempt, then degrade with the verified facts.
        reason = "结构不是合法 JSON" if draft is None else _format_errors(validation)
        repaired, repair_result = await _repair(adapter, system_prompt, user_prompt, result.text, reason)
        calls.append(_call_record(len(calls) + 1, "repair", repair_result))
        if repaired is None:
            return _degrade(request, bundle,
                            "draft_parse_failed" if draft is None else "draft_validation_failed",
                            calls=calls, correlation_id=correlation_id, started=started,
                            validation_issues=issues_log)
        draft, result = repaired, repair_result
        validation = validate_draft(draft, bundle, request.context)
        if not validation.ok:
            issues_log.append(_attempt_issues(len(calls), "repair", draft, validation))
            return _degrade(request, bundle, "draft_validation_failed", calls=calls,
                            correlation_id=correlation_id, started=started,
                            validation_issues=issues_log)

    selected = [f for f in bundle.facts if f.fact_id in set(draft.selected_fact_ids) and f.computable]
    if not selected:
        selected = [f for f in bundle.facts if f.computable and f.preferred] or computable
    from insight.service import render_narrative

    summary_text, point_claims = render_narrative(draft, validation)
    if summary_text is None and not point_claims:
        # Validation passed but nothing usable reaches the page (no summary and no findings);
        # degrade explicitly instead of labelling a template fallback as a successful insight.
        return _degrade(request, bundle, "no_usable_narrative", calls=calls,
                        correlation_id=correlation_id, started=started)
    return _result_from_facts(
        request, bundle.facts, selected,
        status=InsightStatus.COMPLETE,
        degradation_reason=None,
        claims=validation.accepted_claims,
        limitations=list(draft.limitations),
        actions=validation.accepted_actions,
        calls=calls,
        correlation_id=correlation_id,
        started=started,
        summary_text=summary_text,
        point_claims=point_claims,
        narrative=validation.narrative,
        validation_issues=issues_log,
    )


async def _repair(adapter, system_prompt, user_prompt, bad_text, reason):
    """One structural repair. Returns (draft|None, ModelResult|None) without raising."""
    repair_prompt = (
        f"{user_prompt}\n\n上次输出无法使用（{reason}）。"
        "请仅修正结构并重新输出一个合法 JSON 对象，不要改动事实数值：\n"
        f"{bad_text[:2000]}"
    )
    try:
        result = await adapter.complete_analysis(system_prompt, repair_prompt)
    except ModelCallError as exc:
        return None, exc.result
    except Exception:  # noqa: BLE001 — repair failure just degrades
        return None, None
    return _parse_draft(result.text), result


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
    calls: list[dict[str, Any]],
    correlation_id: str,
    started: float,
    validation_issues: list[dict[str, Any]] | None = None,
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
        correlation_id=correlation_id,
        started=started,
        validation_issues=validation_issues,
    )


def _attempt_issues(index: int, stage: str, draft, validation, *, limit: int = 10) -> dict[str, Any]:
    """Record why one draft was rejected, so an archived run can be diagnosed without a re-run.

    Only our own validation messages and schema identifiers are stored — never the model's raw
    text — because ``generation`` is persisted and returned through the task API.
    """
    if draft is None or validation is None:
        return {"call_index": index, "stage": stage, "outcome": "parse_failed", "issues": []}
    return {
        "call_index": index,
        "stage": stage,
        "outcome": "validation_failed",
        "issue_total": len(validation.errors),
        "issues": [
            {"kind": error.kind.value, "message": (error.message or "")[:200],
             "fixable": error.fixable, "claim_id": error.claim_id}
            for error in validation.errors[:limit]
        ],
    }


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
    calls: list[dict[str, Any]],
    correlation_id: str,
    started: float,
    summary_text: str | None = None,
    point_claims: list | None = None,
    narrative=None,
    validation_issues: list[dict[str, Any]] | None = None,
) -> InsightResult:
    ctx = request.context
    assert ctx is not None
    from insight.service import _fact_to_point

    if point_claims:
        points = [InsightPoint(type="finding", label=c.text) for c in point_claims if c.text]
    else:
        points = [_fact_to_point(f) for f in selected]
    if summary_text:
        summary = summary_text
    elif not points:
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
        snapshot_id=request.snapshot.snapshot_id if request.snapshot else None,
        limitations=limitations,
        claims=list(claims),
        narrative=narrative,
        generation=_generation_evidence(calls, correlation_id, started, validation_issues),
    )


def _correlation_id(request: InsightRequest) -> str:
    invocation = request.invocation
    if invocation is not None and invocation.correlation_id:
        return invocation.correlation_id
    return uuid.uuid4().hex


def _call_record(index: int, purpose: str, model: ModelResult | None) -> dict[str, Any]:
    """One model call. A call that produced no ModelResult is still counted."""
    if model is None:
        return {
            "index": index, "purpose": purpose, "status": "failed", "provider": None, "model": None,
            "call_id": None, "request_id": None, "finish_reason": None, "failure_kind": "adapter_exception",
            "elapsed_ms": None, "usage": None, "usage_source": "unknown",
        }
    return {
        "index": index,
        "purpose": purpose,
        "status": model.status,
        "provider": model.provider,
        "model": model.model,
        "call_id": model.call_id,
        "request_id": model.request_id,
        "finish_reason": model.finish_reason,
        "failure_kind": model.failure_kind,
        "elapsed_ms": model.elapsed_ms,
        "usage": model.usage,
        "usage_source": model.usage_source,
    }


def _aggregate_usage(calls: list[dict[str, Any]]) -> tuple[bool, dict[str, int] | None, dict[str, int] | None]:
    """Known-part subtotal plus a total only when every call reported actual usage."""
    known = [c for c in calls if c.get("usage_source") == "actual" and isinstance(c.get("usage"), dict)]
    totals: dict[str, int] = {}
    for call in known:
        usage = call["usage"]
        for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
            value = usage.get(key)
            if type(value) is int and value >= 0:
                totals[key] = totals.get(key, 0) + value
    complete = bool(calls) and len(known) == len(calls)
    subtotal = totals or None
    return complete, subtotal, (subtotal if complete else None)


def _generation_evidence(calls: list[dict[str, Any]], correlation_id: str, started: float,
                         validation_issues: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    last = calls[-1] if calls else None
    complete, subtotal, total = _aggregate_usage(calls)
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    return {
        "prompt_version": PROMPT_VERSION,
        "schema_revision": SCHEMA_REVISION,
        "correlation_id": correlation_id,
        "call_count": len(calls),
        "repair_count": sum(1 for c in calls if c["purpose"] == "repair"),
        "calls": calls,
        # 被拒草稿的结构化原因（含修复成功后仍记录首稿原因），供归档定因；不含模型原文。
        "validation_issues": validation_issues or [],
        "usage_complete": complete,
        "usage_known_subtotal": subtotal,
        "usage_total": total,
        "elapsed_ms": elapsed_ms,
        # Compatibility summary of the last call (pre-R2 consumers).
        "provider": last["provider"] if last else None,
        "model": last["model"] if last else None,
        "finish_reason": last["finish_reason"] if last else None,
        "failure_kind": last["failure_kind"] if last else None,
        "usage": last["usage"] if last else None,
        "usage_source": "actual" if complete else "unknown",
    }
