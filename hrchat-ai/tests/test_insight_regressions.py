import json
from pathlib import Path

import pytest
from insight.fact_builder import build_fact_bundle
from insight.schema import InsightRequest, InsightDraft, InsightClaim, NextAction
from insight.validator import validate_draft
from insight.service import handle_insight


def request():
    return InsightRequest.model_validate(json.loads(
        (Path(__file__).parent / 'fixtures/insight/headcount_two_period.json').read_text(encoding='utf-8')))


def test_department_categories_cannot_be_used_as_time_trend():
    req = request()
    req.context.axis_kind = 'category'
    req.snapshot.categories = ['Department A', 'Department B']
    facts = build_fact_bundle(req.context, req.snapshot).facts
    assert not any(f.computable and f.fact_type.value in {
        'absolute_change', 'relative_change', 'continuous_trend', 'period_over_period'
    } for f in facts)


@pytest.mark.parametrize('axis', ['unknown', 'category'])
def test_date_shaped_category_is_not_sufficient_time_evidence(axis):
    req = request()
    req.context.axis_kind = axis
    assert not any(f.computable and f.fact_type.value == 'continuous_trend'
                   for f in build_fact_bundle(req.context, req.snapshot).facts)


@pytest.mark.parametrize('scale,values', [('percent', [.5, .8]), ('fraction', [.005, .008])])
def test_percentage_point_uses_explicit_scale(scale, values):
    req = request()
    req.context.metric_kind = 'ratio'
    req.context.unit = '%'
    req.context.ratio_scale = scale
    req.context.numerator_metric_code = 'leave_count'
    req.context.denominator_metric_code = 'headcount'
    req.snapshot.series[0].data = values
    fact = next(f for f in build_fact_bundle(req.context, req.snapshot).facts
                if f.fact_type.value == 'percentage_point_change')
    assert fact.value == pytest.approx(.3)


@pytest.mark.parametrize('predicate,unit', [('absolute_decrease', '人'), ('absolute_increase', '%')])
def test_claim_cannot_reverse_sign_or_change_fact_unit(predicate, unit):
    req = request()
    bundle = build_fact_bundle(req.context, req.snapshot)
    draft = InsightDraft(claims=[InsightClaim(claim_id='bad', kind='fact',
        fact_ids=['absolute_change:overall'], predicate=predicate, unit=unit)])
    assert not validate_draft(draft, bundle, req.context).ok


@pytest.mark.parametrize('bad', [
    {'metric_code': 'salary'},
    {'current_period': 'invalid'},
    {'effective_org_ids': [999]},
    {'current_period': {'start': '2025-01-01', 'end': '2025-02-01'}},
])
def test_executable_actions_must_match_trusted_metric_period_scope(bad):
    req = request()
    req.context.metric_code = 'leave_count'
    params = dict(metric_code='leave_count', current_period=req.context.current_period.model_dump(mode='json'),
                  baseline_period=req.context.baseline_period.model_dump(mode='json'),
                  effective_org_ids=req.context.effective_org_ids)
    params.update(bad)
    draft = InsightDraft(next_actions=[NextAction(action_id='bad',
        capability_id='leave_count_department_contribution', params=params, executable=True)])
    result = validate_draft(draft, build_fact_bundle(req.context, req.snapshot), req.context)
    assert not result.ok
    assert not result.accepted_actions


def test_known_action_parameters_are_reused_without_artificial_confirmation():
    req = request()
    req.context.metric_code = 'leave_count'
    result = handle_insight(req.model_dump(mode='json'))
    action = result['next_actions'][0]
    assert action['executable'] is True
    assert action['params']['effective_org_ids'] == req.context.effective_org_ids
    assert not action['missing_params']


def test_partial_rich_request_does_not_silently_enter_legacy_path():
    result = handle_insight({'context': request().context.model_dump(mode='json')})
    assert result['status'] == 'failed'
    assert result['degradation_reason'] == 'invalid_rich_request'
