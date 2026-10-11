"""Validator：引用、范围、动作与部门贡献证据门禁。"""
from __future__ import annotations

import json
from pathlib import Path

from insight.fact_builder import build_fact_bundle
from insight.schema import (
    ClaimKind,
    InsightClaim,
    InsightDraft,
    InsightRequest,
    NextAction,
    ValidationErrorKind,
)
from insight.validator import validate_draft

FIXTURES = Path(__file__).parent / "fixtures" / "insight"


def _rich_request(fixture: str = "headcount_two_period.json") -> InsightRequest:
    return InsightRequest.model_validate(json.loads((FIXTURES / fixture).read_text(encoding="utf-8")))


def test_bad_fact_refs_blocked():
    req = _rich_request()
    bundle = build_fact_bundle(req.context, req.snapshot)
    draft = InsightDraft.model_validate(json.loads((FIXTURES / "bad_model_draft.json").read_text(encoding="utf-8")))
    result = validate_draft(draft, bundle, req.context)
    assert result.ok is False
    kinds = {e.kind for e in result.errors}
    assert ValidationErrorKind.BAD_FACT_REF in kinds
    assert any(e.fixable for e in result.errors if e.kind == ValidationErrorKind.BAD_FACT_REF)


def test_out_of_scope_blocked():
    req = _rich_request()
    bundle = build_fact_bundle(req.context, req.snapshot)
    known = next(f.fact_id for f in bundle.facts if f.computable)
    draft = InsightDraft(
        selected_fact_ids=[known],
        claims=[
            InsightClaim(
                claim_id="scope1",
                kind=ClaimKind.FACT,
                text="越权范围结论",
                fact_ids=[known],
                scope_ref="org:999",
            )
        ],
        limitations=[],
        next_actions=[],
    )
    result = validate_draft(draft, bundle, req.context)
    assert result.ok is False
    assert any(e.kind == ValidationErrorKind.OUT_OF_SCOPE and not e.fixable for e in result.errors)


def test_unsupported_actions_blocked():
    req = _rich_request("dept_net_zero.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    known = next(f.fact_id for f in bundle.facts if f.computable)
    draft = InsightDraft(
        selected_fact_ids=[known],
        claims=[],
        limitations=[],
        next_actions=[
            NextAction(
                action_id="a1",
                capability_id="unsupported_magic_analysis",
                question="魔法归因",
                params={},
                missing_params=[],
                executable=True,
            )
        ],
    )
    result = validate_draft(draft, bundle, req.context)
    assert result.ok is False
    assert any(e.kind == ValidationErrorKind.UNSUPPORTED_ACTION for e in result.errors)


def test_no_confirmed_dept_contribution_without_evidence():
    req = _rich_request("headcount_two_period.json")
    bundle = build_fact_bundle(req.context, req.snapshot)
    assert not any(f.fact_type.value.startswith("department") and f.computable for f in bundle.facts
                   if "department" in f.fact_id)
    draft = InsightDraft(
        selected_fact_ids=[bundle.facts[0].fact_id],
        claims=[
            InsightClaim(
                claim_id="dept1",
                kind=ClaimKind.STATISTICAL_CONTRIBUTION,
                text="研发贡献了增长",
                fact_ids=["department_contribution:1"],
                predicate="department_net_contribution",
            )
        ],
        limitations=[],
        next_actions=[],
    )
    result = validate_draft(draft, bundle, req.context)
    assert result.ok is False
    assert any(e.kind == ValidationErrorKind.MISSING_EVIDENCE and not e.fixable for e in result.errors)


def test_leave_count_capability_missing_params_need_confirmation():
    from insight.capabilities import resolve_action

    action = resolve_action(
        NextAction(
            action_id="a1",
            capability_id="leave_count_department_contribution",
            question="分析部门贡献",
            params={"metric_code": "leave_count"},
            missing_params=[],
            executable=True,
        )
    )
    assert action.executable is False
    assert set(action.missing_params) >= {"current_period", "baseline_period", "effective_org_ids"}
