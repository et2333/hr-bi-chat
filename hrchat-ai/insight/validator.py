"""Validate InsightDraft against FactBundle and capability catalog.

Distinguishes fixable format errors from evidence/permission errors.
Blocks unsupported actions and definite department contribution without evidence.
"""
from __future__ import annotations

from insight.capabilities import MANUAL_REVIEW, is_supported_capability, resolve_action, action_param_errors
from insight.schema import (
    ClaimKind,
    FactBundle,
    FactType,
    InsightClaim,
    InsightContext,
    InsightDraft,
    NextAction,
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
            accepted_claims.append(claim)

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
    )


def _check_claim(
    claim: InsightClaim,
    fact_index: dict,
    computable_ids: set[str],
    dept_evidence_ids: set[str],
    context: InsightContext,
) -> list[ValidationIssue]:
    errors: list[ValidationIssue] = []
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

