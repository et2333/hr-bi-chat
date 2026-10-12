"""Validate InsightDraft against FactBundle and capability catalog.

Distinguishes fixable format errors from evidence/permission errors.
Blocks unsupported actions and definite department contribution without evidence.
"""
from __future__ import annotations

import re

from insight.capabilities import MANUAL_REVIEW, is_supported_capability, resolve_action, action_param_errors
from insight.schema import (
    ClaimKind,
    Fact,
    FactBundle,
    FactType,
    InsightClaim,
    InsightContext,
    InsightDraft,
    NextAction,
    SegmentField,
    ValidationErrorKind,
    ValidationIssue,
    ValidationResult,
)

_ALLOWED_PREDICATES = {
    None,
    "absolute_increase",
    "absolute_decrease",
    "relative_increase",
    "relative_decrease",
    "point_higher",
    "point_lower",
    "tied_extreme",
    "department_net_contribution",
    "offsetting_departments",
}

# Numbers/dates must arrive through a fact slot; unsupported causal wording is rejected.
# 局限/下一步类文本里，"由于数据缺失，无法判定…"属限定表述而非因果断言，按 hedge 标记放行。
_NUMBER_RE = re.compile(r"\d")
_CAUSAL_WORDS = ("导致", "因为", "由于", "造成", "引起", "以致", "使得", "因此导致")
_HEDGE_MARKERS = ("无法", "不能", "不可", "不足", "缺失", "缺少", "不连续", "暂", "尚", "需",
                  "建议", "难以")
_LIMITATION_KINDS = {ClaimKind.LIMITATION, ClaimKind.NEXT_CHECK}
_SIGNED_TYPES = {
    FactType.ABSOLUTE_CHANGE,
    FactType.RELATIVE_CHANGE,
    FactType.PERIOD_OVER_PERIOD,
    FactType.PERCENTAGE_POINT_CHANGE,
}


def category_slot_text(fact: Fact) -> str:
    """类别槽渲染：tied_extreme 的类目存在 ``tied_categories``，渲染与校验必须同口径。

    否则模型把 category 槽绑到 tied_extreme 事实时会被判「无可渲染类目」，而该信息其实就在
    已核验事实里。
    """

    if fact.category:
        return fact.category
    return "、".join(fact.tied_categories or [])


def fact_slot_text(fact: Fact, field: SegmentField | None) -> str:
    """Render one whitelisted fact field. The model never types these values."""

    resolved = field or SegmentField.VALUE
    if resolved == SegmentField.UNIT:
        return fact.unit or ""
    if resolved == SegmentField.PERIOD_LABEL:
        return fact.period_ref or ""
    if resolved == SegmentField.SCOPE_LABEL:
        return fact.scope_ref or ""
    if resolved == SegmentField.CATEGORY:
        return category_slot_text(fact)
    value = fact.value
    if value is None:
        return ""
    if fact.unit in {"ratio", "share"}:
        return f"{value:+.1%}" if fact.fact_type in _SIGNED_TYPES else f"{value:.1%}"
    if fact.fact_type in _SIGNED_TYPES:
        return f"{value:+g}"
    return f"{value:g}"


def render_claim_text(claim: InsightClaim, fact_index: dict[str, Fact]) -> str | None:
    """Server-rendered claim text from segments; None when a slot cannot be resolved."""

    if not claim.segments:
        return claim.text
    parts: list[str] = []
    for segment in claim.segments:
        if segment.type == "text":
            parts.append(segment.text or "")
            continue
        fact = fact_index.get(segment.fact_id or "")
        if fact is None:
            return None
        rendered = fact_slot_text(fact, segment.field)
        if rendered == "" and (segment.field or SegmentField.VALUE) == SegmentField.VALUE:
            return None
        parts.append(rendered)
    return "".join(parts)


def free_text_issues(text: str, *, hedged_causality_ok: bool = False) -> list[str]:
    """Free text shown to the user may not type numbers/dates or unsupported causality.

    Applies to text segments and to every other model-generated visible string (B3:
    top-level ``limitations`` was previously passed through unchecked).
    ``hedged_causality_ok`` 供局限/下一步类文本使用：句中出现"缺失/无法/不能…"等限定标记时，
    "由于…缺失，无法判定…"这类**限定表述**不算因果断言；"因为A所以B"式断言仍被拒绝。
    """

    issues: list[str] = []
    if _NUMBER_RE.search(text):
        issues.append("free text must not contain numbers/dates; bind them through a fact slot")
    if any(word in text for word in _CAUSAL_WORDS):
        hedged = hedged_causality_ok and any(marker in text for marker in _HEDGE_MARKERS)
        if not hedged:
            issues.append("unsupported causal wording is not allowed in model-typed text")
    return issues


def validate_draft(
    draft: InsightDraft,
    bundle: FactBundle,
    context: InsightContext,
) -> ValidationResult:
    errors: list[ValidationIssue] = []
    fact_index = {f.fact_id: f for f in bundle.facts}
    computable_ids = {f.fact_id for f in bundle.facts if f.computable}
    dept_evidence_ids = {
        f.fact_id for f in bundle.facts
        if f.fact_type == FactType.DEPARTMENT_CONTRIBUTION and f.computable
        and f.contribution_kind in {"department_delta", "offset"}
    }

    for fid in draft.selected_fact_ids:
        if fid not in fact_index:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_FACT_REF,
                message=f"unknown selected fact_id: {fid}",
                fixable=True,
            ))

    accepted_claims: list[InsightClaim] = []
    for claim in draft.claims:
        claim_errors = _check_claim(claim, fact_index, computable_ids, dept_evidence_ids, context)
        if claim_errors:
            errors.extend(claim_errors)
        else:
            rendered = render_claim_text(claim, fact_index)
            accepted_claims.append(claim if rendered is None else claim.model_copy(update={"text": rendered}))

    accepted_ids = {c.claim_id for c in accepted_claims}
    accepted_by_id = {c.claim_id: c for c in accepted_claims}
    # B3：顶层 limitations 也是模型生成后直接展示的文本，必须与正文同一套自由文本约束，
    # 不能把自由字符串原样透传（否则虚构数字/因果句可只经 limitations 到达页面）。
    for text in draft.limitations:
        for message in free_text_issues(text, hedged_causality_ok=True):
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_SEGMENT,
                message=f"limitation text rejected: {message}",
                fixable=True,
            ))
    if draft.narrative is not None:
        referenced = ([draft.narrative.summary_claim_id] if draft.narrative.summary_claim_id else []) \
            + list(draft.narrative.finding_claim_ids)
        for cid in referenced:
            if cid not in accepted_ids:
                errors.append(ValidationIssue(
                    kind=ValidationErrorKind.SCHEMA,
                    message=f"narrative references unknown claim: {cid}",
                    fixable=True,
                    claim_id=cid,
                ))
        # 主结论只能引用带证据的事实类断言：limitation/next_check 不得成为可见总结（避免虚构句被当成结论）。
        summary = accepted_by_id.get(draft.narrative.summary_claim_id or "")
        if summary is not None and summary.kind not in {
            ClaimKind.FACT, ClaimKind.STATISTICAL_CONTRIBUTION,
        }:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_FACT_REF,
                message="summary claim must be an evidence-bearing fact claim",
                fixable=True,
                claim_id=summary.claim_id,
            ))

    accepted_actions: list[NextAction] = []
    for action in draft.next_actions:
        action_errors = _check_action(action, context)
        if action_errors:
            errors.extend(action_errors)
            continue
        resolved = resolve_action(action, context)
        if resolved.capability_id == MANUAL_REVIEW:
            accepted_actions.append(resolved.model_copy(update={"executable": False}))
        else:
            accepted_actions.append(resolved)

    return ValidationResult(
        ok=not errors,
        errors=errors,
        accepted_claims=accepted_claims,
        accepted_actions=accepted_actions,
        narrative=None if errors else draft.narrative,
    )


def _check_claim(
    claim: InsightClaim,
    fact_index: dict,
    computable_ids: set[str],
    dept_evidence_ids: set[str],
    context: InsightContext,
) -> list[ValidationIssue]:
    errors: list[ValidationIssue] = []
    # Every claim's visible text passes segment checks, including limitation/next_check
    # free text (no numbers/dates, no unsupported causality), even without fact_ids.
    errors.extend(_check_segments(claim, fact_index, computable_ids))

    if not claim.fact_ids:
        # Only evidence-bearing claims must cite facts; limitation/next_check stand alone.
        if claim.kind in {ClaimKind.FACT, ClaimKind.STATISTICAL_CONTRIBUTION}:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_FACT_REF,
                message="claim requires fact_ids",
                fixable=True,
                claim_id=claim.claim_id,
            ))
        return errors

    for fid in claim.fact_ids:
        if fid not in fact_index:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_FACT_REF,
                message=f"unknown fact_id: {fid}",
                fixable=True,
                claim_id=claim.claim_id,
            ))
        elif fid not in computable_ids and claim.kind in {
            ClaimKind.FACT, ClaimKind.STATISTICAL_CONTRIBUTION,
        }:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.MISSING_EVIDENCE,
                message=f"fact not computable: {fid}",
                fixable=False,
                claim_id=claim.claim_id,
            ))

    refs = [fact_index[fid] for fid in claim.fact_ids if fid in fact_index]
    if claim.scope_ref is not None and any(f.scope_ref != claim.scope_ref for f in refs):
        errors.append(ValidationIssue(kind=ValidationErrorKind.OUT_OF_SCOPE,
            message="claim scope differs from its evidence", fixable=False, claim_id=claim.claim_id))
    if claim.unit is not None and any(f.unit != claim.unit for f in refs):
        errors.append(ValidationIssue(kind=ValidationErrorKind.UNIT_MISMATCH,
            message="claim unit differs from its evidence", fixable=True, claim_id=claim.claim_id))
    typed = {
        "absolute_increase": (FactType.ABSOLUTE_CHANGE, 1),
        "absolute_decrease": (FactType.ABSOLUTE_CHANGE, -1),
        "relative_increase": (FactType.RELATIVE_CHANGE, 1),
        "relative_decrease": (FactType.RELATIVE_CHANGE, -1),
        "point_higher": (FactType.ABSOLUTE_CHANGE, 1),
        "point_lower": (FactType.ABSOLUTE_CHANGE, -1),
    }
    supported = True
    if claim.predicate in typed:
        fact_type, sign = typed[claim.predicate]
        supported = any(f.computable and f.fact_type == fact_type and f.value is not None
                        and f.value * sign > 0 for f in refs)
    elif claim.predicate == "tied_extreme":
        supported = any(f.computable and f.fact_type == FactType.TIED_EXTREME for f in refs)
    elif claim.predicate == "offsetting_departments":
        supported = any(f.computable and f.contribution_kind == "offset" and f.value and f.value > 0 for f in refs)
    elif claim.predicate == "department_net_contribution":
        supported = any(f.computable and f.contribution_kind == "department_delta" for f in refs)
    if not supported:
        errors.append(ValidationIssue(kind=ValidationErrorKind.BAD_PREDICATE,
            message="predicate not supported by fact type/sign", fixable=False, claim_id=claim.claim_id))

    if claim.predicate not in _ALLOWED_PREDICATES:
        errors.append(ValidationIssue(
            kind=ValidationErrorKind.BAD_PREDICATE,
            message=f"unsupported predicate: {claim.predicate}",
            fixable=True,
            claim_id=claim.claim_id,
        ))

    if claim.kind == ClaimKind.STATISTICAL_CONTRIBUTION or claim.predicate == "department_net_contribution":
        if not (set(claim.fact_ids) & dept_evidence_ids):
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.MISSING_EVIDENCE,
                message="no department evidence for contribution claim",
                fixable=False,
                claim_id=claim.claim_id,
            ))

    return errors


def _check_segments(
    claim: InsightClaim,
    fact_index: dict,
    computable_ids: set[str],
) -> list[ValidationIssue]:
    """Fact slots must resolve; free text may not type numbers/dates or unsupported causality."""

    errors: list[ValidationIssue] = []
    evidence_bearing = claim.kind in {ClaimKind.FACT, ClaimKind.STATISTICAL_CONTRIBUTION}
    free_text = [s.text or "" for s in claim.segments if s.type == "text"]
    if not claim.segments and claim.text:
        free_text.append(claim.text)
    hedged_ok = claim.kind in _LIMITATION_KINDS

    for text in free_text:
        for message in free_text_issues(text, hedged_causality_ok=hedged_ok):
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_SEGMENT,
                message=message,
                fixable=True,
                claim_id=claim.claim_id,
            ))

    for segment in claim.segments:
        if segment.type != "fact":
            continue
        fact = fact_index.get(segment.fact_id)
        if fact is None:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_FACT_REF,
                message=f"unknown segment fact_id: {segment.fact_id}",
                fixable=True,
                claim_id=claim.claim_id,
            ))
            continue
        if evidence_bearing and fact.fact_id not in computable_ids:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.MISSING_EVIDENCE,
                message=f"segment fact not computable: {fact.fact_id}",
                fixable=False,
                claim_id=claim.claim_id,
            ))
        field = segment.field or SegmentField.VALUE
        if field == SegmentField.VALUE and fact.value is None:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.MISSING_EVIDENCE,
                message=f"fact has no value to render: {fact.fact_id}",
                fixable=False,
                claim_id=claim.claim_id,
            ))
        if field == SegmentField.CATEGORY and not category_slot_text(fact):
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_SEGMENT,
                message=f"fact has no category to render: {fact.fact_id}",
                fixable=True,
                claim_id=claim.claim_id,
            ))
        if field == SegmentField.PERIOD_LABEL and not fact.period_ref:
            errors.append(ValidationIssue(
                kind=ValidationErrorKind.BAD_SEGMENT,
                message=f"fact has no period to render: {fact.fact_id}",
                fixable=True,
                claim_id=claim.claim_id,
            ))
    return errors


def _scope_subset(claim_scope: str, context: InsightContext) -> bool:
    if claim_scope.startswith("org:"):
        try:
            org_id = int(claim_scope.split(":", 1)[1])
        except ValueError:
            return False
        return org_id in context.effective_org_ids
    return False


def _check_action(action: NextAction, context: InsightContext) -> list[ValidationIssue]:
    if action.capability_id == MANUAL_REVIEW:
        if action.executable:
            return [ValidationIssue(
                kind=ValidationErrorKind.UNSUPPORTED_ACTION,
                message="manual_review cannot be executable",
                fixable=True,
                action_id=action.action_id,
            )]
        return []
    if not is_supported_capability(action.capability_id):
        return [ValidationIssue(
            kind=ValidationErrorKind.UNSUPPORTED_ACTION,
            message=f"unsupported capability: {action.capability_id}",
            fixable=False,
            action_id=action.action_id,
        )]
    if not isinstance(action.params, dict):
        return [ValidationIssue(
            kind=ValidationErrorKind.BAD_ACTION_PARAMS,
            message="action params must be an object",
            fixable=True,
            action_id=action.action_id,
        )]
    return [ValidationIssue(kind=ValidationErrorKind.BAD_ACTION_PARAMS, message=error,
                            fixable=False, action_id=action.action_id)
            for error in action_param_errors(action, context)]

