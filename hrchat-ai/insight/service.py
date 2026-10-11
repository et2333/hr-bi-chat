"""Insight service: rich FactBuilder path vs legacy template build_insight."""
from __future__ import annotations

from typing import Any

from insight.capabilities import CAPABILITY_LEAVE_COUNT_DEPT, resolve_action, suggestion_action
from insight.fact_builder import build_fact_bundle
from insight.schema import (
    Fact,
    FactType,
    InsightPoint,
    InsightRequest,
    InsightResult,
    InsightStatus,
    NextAction,
)
from insight.validator import validate_draft


def handle_insight(body: dict | None) -> dict:
    """Route POST /v1/insight body to rich programmatic path or legacy template."""
    raw = body or {}
    if _looks_rich(raw):
        try:
            request = InsightRequest.model_validate(raw)
        except Exception as exc:  # noqa: BLE001 — surface as degraded insight, not 500
            return InsightResult(
                report_name=str(raw.get("report_name") or ""),
                metric_name=str(raw.get("metric_name") or ""),
                summary=f"洞察请求不符合契约：{exc}",
                points=[],
                status=InsightStatus.FAILED,
                degradation_reason="invalid_rich_request",
            ).model_dump(mode="json")
        if request.is_rich():
            return _handle_rich(request).model_dump(mode="json")

    return _handle_legacy(raw)


def _looks_rich(raw: dict) -> bool:
    return isinstance(raw.get("context"), dict) and isinstance(raw.get("snapshot"), dict)


def _handle_rich(request: InsightRequest) -> InsightResult:
    assert request.context is not None and request.snapshot is not None
    bundle = build_fact_bundle(request.context, request.snapshot)
    degradation_reason = "no_model_draft"
    claims = []
    limitations: list[str] = []
    actions: list[NextAction] = []

    if request.draft is not None:
        validated = validate_draft(request.draft, bundle, request.context)
        if validated.ok:
            degradation_reason = None
            claims = validated.accepted_claims
            limitations = list(request.draft.limitations)
            actions = validated.accepted_actions
            status = InsightStatus.COMPLETE
            selected = [f for f in bundle.facts if f.fact_id in set(request.draft.selected_fact_ids) and f.computable]
            if not selected:
                selected = [f for f in bundle.facts if f.computable and f.preferred] or [
                    f for f in bundle.facts if f.computable
                ]
            return _result_from_facts(
                request, bundle.facts, selected, status=status,
                degradation_reason=None, claims=claims, limitations=limitations, actions=actions,
            )
        degradation_reason = "draft_validation_failed"
        actions = []

    selected = _default_selected(bundle.facts)
    actions = _default_actions(request) if degradation_reason == "no_model_draft" else []
    limitations = _default_limitations(bundle.facts)
    return _result_from_facts(
        request, bundle.facts, selected,
        status=InsightStatus.DEGRADED,
        degradation_reason=degradation_reason,
        claims=claims,
        limitations=limitations,
        actions=actions,
    )


def _default_selected(facts: list[Fact]) -> list[Fact]:
    preferred = [f for f in facts if f.computable and f.preferred]
    if preferred:
        return preferred[:3]
    computable = [f for f in facts if f.computable]
    return computable[:3]


def _default_limitations(facts: list[Fact]) -> list[str]:
    reasons = []
    for f in facts:
        if not f.computable and f.incomputable_reason:
            reasons.append(f"{f.fact_type.value}:{f.incomputable_reason}")
    # stable unique
    seen = []
    for r in reasons:
        if r not in seen:
            seen.append(r)
    return seen[:8]


def _default_actions(request: InsightRequest) -> list[NextAction]:
    assert request.context is not None
    ctx = request.context
    if ctx.metric_code == "leave_count" and request.snapshot and request.snapshot.departments:
        action = resolve_action(NextAction(
            action_id="leave_dept_1",
            capability_id=CAPABILITY_LEAVE_COUNT_DEPT,
            question="进一步分析两期部门贡献",
            params={
                "metric_code": ctx.metric_code,
                "current_period": ctx.current_period.model_dump(mode="json"),
                "baseline_period": (
                    ctx.baseline_period.model_dump(mode="json") if ctx.baseline_period else None
                ),
                "effective_org_ids": list(ctx.effective_org_ids),
            },
            missing_params=[],
            executable=True,
        ))
        return [action]
    if ctx.metric_code == "leave_count":
        return [resolve_action(NextAction(
            action_id="leave_dept_confirm",
            capability_id=CAPABILITY_LEAVE_COUNT_DEPT,
            question="确认两期与组织范围后分析部门贡献",
            params={"metric_code": "leave_count"},
            missing_params=[],
            executable=True,
        ))]
    return [suggestion_action(action_id="manual_1", question="结合业务背景人工核查异常点")]


def _result_from_facts(
    request: InsightRequest,
    all_facts: list[Fact],
    selected: list[Fact],
    *,
    status: InsightStatus,
    degradation_reason: str | None,
    claims,
    limitations: list[str],
    actions: list[NextAction],
) -> InsightResult:
    ctx = request.context
    assert ctx is not None
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
        next_actions=actions,
        data_version=ctx.data_version,
        view_key=ctx.view_key,
        limitations=limitations,
        claims=list(claims),
    )


def _fact_to_point(fact: Fact) -> InsightPoint:
    mapping = {
        FactType.ABSOLUTE_CHANGE: "value",
        FactType.RELATIVE_CHANGE: "value",
        FactType.POINT_COMPARE: "value",
        FactType.PERIOD_OVER_PERIOD: "trend",
        FactType.CONTINUOUS_TREND: "trend",
        FactType.TIED_EXTREME: "extreme",
        FactType.RANK_SHARE: "value",
        FactType.DEPARTMENT_CONTRIBUTION: "value",
        FactType.PERCENTAGE_POINT_CHANGE: "value",
    }
    return InsightPoint(type=mapping.get(fact.fact_type, "value"), label=fact.label or fact.fact_id)


def _handle_legacy(raw: dict[str, Any]) -> dict:
    from agent_gateway.app import build_insight

    report_name = raw.get("report_name") or ""
    metric_name = raw.get("metric_name") or ""
    summary = raw.get("summary") or {}
    result = build_insight(
        report_name,
        metric_name,
        summary.get("categories") or [],
        summary.get("series") or [],
    )
    result.setdefault("status", InsightStatus.LEGACY.value)
    return result
