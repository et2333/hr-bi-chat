"""Server-side analysis capability catalog for insight nextActions.

First capability: leave_count two-period department contribution.
Non-executable suggestions must not become clickable auto-actions.
"""
from __future__ import annotations

from insight.schema import NextAction, InsightContext, Period
from pydantic import ValidationError

CAPABILITY_LEAVE_COUNT_DEPT = "leave_count_department_contribution"

CAPABILITIES: dict[str, dict] = {
    CAPABILITY_LEAVE_COUNT_DEPT: {
        "metric_code": "leave_count",
        "required_params": [
            "metric_code",
            "current_period",
            "baseline_period",
            "effective_org_ids",
        ],
        "description": "两期离职人数部门贡献分析",
        "executable": True,
    },
}


def list_capabilities() -> list[dict]:
    return [
        {"capability_id": cid, **{k: v for k, v in meta.items() if k != "required_params"},
         "required_params": list(meta["required_params"])}
        for cid, meta in CAPABILITIES.items()
    ]


def resolve_action(action: NextAction, context: InsightContext | None = None) -> NextAction:
    """Fill missing_params and clear executable when confirmation is required."""
    meta = CAPABILITIES.get(action.capability_id)
    if meta is None:
        return action.model_copy(update={"executable": False, "missing_params": list(action.missing_params)})
    required = list(meta["required_params"])
    missing = [p for p in required if _missing(action.params.get(p))]
    # Preserve caller-declared missing fields.
    for p in action.missing_params:
        if p not in missing:
            missing.append(p)
    executable = bool(meta.get("executable")) and not missing and context is not None and not action_param_errors(action, context)
    return action.model_copy(update={"missing_params": missing, "executable": executable})


def is_supported_capability(capability_id: str) -> bool:
    return capability_id in CAPABILITIES


def suggestion_action(*, action_id: str, question: str) -> NextAction:
    """Human-check suggestion: never clickable auto-action."""
    return NextAction(
        action_id=action_id,
        capability_id="manual_review",
        question=question,
        params={},
        missing_params=[],
        executable=False,
    )


def _missing(value) -> bool:
    if value is None:
        return True
    if isinstance(value, str) and not value.strip():
        return True
    if isinstance(value, (list, dict, tuple, set)) and len(value) == 0:
        return True
    return False


def action_param_errors(action: NextAction, context: InsightContext) -> list[str]:
    if action.capability_id != CAPABILITY_LEAVE_COUNT_DEPT:
        return []
    params = action.params
    errors = []
    if context.metric_code != "leave_count" or params.get("metric_code", "leave_count") != "leave_count":
        errors.append("capability only supports leave_count")
    required = set(CAPABILITIES[CAPABILITY_LEAVE_COUNT_DEPT]["required_params"])
    if set(params) - required:
        errors.append("unknown action parameters")
    for name, expected in (("current_period", context.current_period), ("baseline_period", context.baseline_period)):
        raw = params.get(name)
        if _missing(raw):
            continue
        try:
            period = Period.model_validate(raw)
            if expected is None or period != expected:
                errors.append(f"{name} does not match trusted context")
        except (ValidationError, TypeError):
            errors.append(f"invalid {name}")
    ids = params.get("effective_org_ids")
    if not _missing(ids):
        if (not isinstance(ids, list) or any(type(i) is not int or i <= 0 for i in ids)
                or len(set(ids)) != len(ids) or set(ids) != set(context.effective_org_ids)):
            errors.append("organization scope does not match trusted context")
    return errors
