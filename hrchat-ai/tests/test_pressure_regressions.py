"""Pressure v2: capability contrasts, independent periods and diagnostic scoring.

Model outputs here are scripted; these tests do not claim real-model accuracy.
"""
from copy import deepcopy
import pytest

from evals.rag_pressure import CASES, EXPECTED_PERIODS, score_arm, score_case
from langgraph_flows.ask_flow import run_ask_flow
from tests.test_query_draft import DraftPlanner
from tests.test_query_plan import CATALOG, Tools


async def run(question, metric='leave_count', period='上月', ui=None):
    catalog = deepcopy(CATALOG)
    catalog['metrics'].append({**catalog['metrics'][1], 'code': 'hire_count', 'name': '入职人数'})
    tools = Tools(catalog=catalog)
    draft = {'decision': 'execute', 'metric_codes': [metric],
             'metric_text': '入职' if metric == 'hire_count' else '离职人数', 'time_expression': period}
    if '研发中心' in question:
        draft['organization'] = {'kind': 'catalog_id', 'org_id': '2', 'source_text': '研发中心'}
    planner = DraftPlanner(draft)
    result = await run_ask_flow(question=question, session_id='s', ask_id='pressure-test',
        tools=tools, adapter=planner, query_context={'schema_version': '1', 'context_version': 1},
        context_override=ui or {'org': {'org_id': '2', 'include_children': True}}, repair_enabled=False)
    return result, tools, planner


@pytest.mark.parametrize('question', [
    '上月总离职人数，不是主动离职专项',
    '并非主动离职口径，上月总离职人数',
    '上月研发中心离职人数；而非主动离职人数。',
])
async def test_explicit_contrast_runs_total_and_keeps_original_model_input(question):
    result, tools, planner = await run(question)
    assert result['evidence']['execution']['query_plan']['metric_codes'] == ['leave_count']
    assert any(c[0] == 'query' for c in tools.calls)
    assert question in str(planner.prompts)
    assert len(result['evidence']['model_calls']) == 1


@pytest.mark.parametrize('question', [
    '上月主动离职人数',
    '上月离职人数，排除主动离职',
    '上月离职人数，不包括主动离职',
    '上月离职人数，只看主动离职',
    '上月总离职人数，不是主动离职专项，只查主动离职',
    '上月总离职人数，不是只查主动离职',
    '上月总离职人数，不是主动离职',
    '上月总离职人数，不是主动离职专项里的人',
    '不是离职人数，不是主动离职专项',
])
async def test_active_filtered_or_ambiguous_submetric_never_runs_total(question):
    result, tools, planner = await run(question)
    assert result['evidence']['reason'] == 'metric_unavailable'
    assert not any(c[0] == 'query' for c in tools.calls)
    assert planner.prompts == []


@pytest.mark.parametrize('case', [c for c in CASES if c['id'] in ('rp-03', 'rp-05')])
async def test_month_pressure_selection_agrees_with_question_and_conflict_still_stops(case):
    ui = {'org': {'org_id': '2', 'include_children': True},
          'time_range': case['context_override']['timeRange']}
    result, tools, _ = await run(case['question'], 'hire_count', '本月', ui)
    plan = result['evidence']['execution']['query_plan']
    assert (plan['time_range']['start'], plan['time_range']['end']) == EXPECTED_PERIODS[case['id']]
    assert plan['metric_codes'] == ['hire_count'] and plan['org_scope']['org_id'] == '2'
    # Replay the *old input defect*, not a supposed model response from the old run.
    ui['time_range'] = {'preset': 'CUSTOM', 'start': '2026-09-01', 'end': '2026-09-28'}
    conflict, tools, _ = await run(case['question'], 'hire_count', '本月', ui)
    assert conflict['evidence']['reason'] == 'context_conflict'
    assert conflict['clarify_questions']
    assert not any(c[0] == 'query' for c in tools.calls)


def actual(case):
    period = EXPECTED_PERIODS[case['id']]
    plan = {'metric_codes': [case['expected_metric']], 'query_mode': 'scalar',
            'org_scope': {'org_id': '2', 'include_children': True}}
    if period:
        plan['time_range'] = {'start': period[0], 'end': period[1], 'time_type': 'period'}
    return {'status': 'COMPLETED', 'evidence_http_status': 200,
            'evidence': {'execution': {'query_plan': plan, 'effective_org_ids': [2, 3, 4]}}}


@pytest.mark.parametrize('case', CASES)
def test_scorer_accepts_independent_scope_and_period_expectations(case):
    assert score_case(case, actual(case))[0] == []


@pytest.mark.parametrize('fault,expected', [
    ('period', 'period_mismatch'), ('org', 'organization_mismatch'),
    ('scope', 'effective_scope_mismatch'), ('mode', 'mode_mismatch'),
    ('evidence', 'execution_evidence_missing'), ('terminal', 'not_completed'),
])
def test_metric_only_success_cannot_hide_wrong_scope_or_period(fault, expected):
    case = CASES[2]
    value = actual(case)
    execution = value['evidence']['execution']
    if fault == 'period': execution['query_plan']['time_range']['end'] = '2026-09-28'
    elif fault == 'org': execution['query_plan']['org_scope']['org_id'] = '3'
    elif fault == 'scope': execution['effective_org_ids'].append(5)
    elif fault == 'mode': execution['query_plan']['query_mode'] = 'org'
    elif fault == 'evidence': value['evidence'] = {}
    elif fault == 'terminal': value['conflicting_terminal_events'] = True
    assert expected in score_case(case, value)[0]


def test_report_preserves_clarification_draft_and_stop_reason():
    class Api:
        def session(self, _): return 's'
        def ask(self, *_):
            return {'status': 'CLARIFYING', 'evidence_http_status': 200,
                'events': [{'event': 'INTERRUPT', 'payload': {'questions': [{'message': '期间冲突'}]}}],
                'evidence': {'reason': 'context_conflict', 'model_query_draft': {'time_expression': '本月'},
                    'validation_error': {'stage': 'plan_query', 'code': 'context_conflict'}}}
    report = score_arm(Api(), 'off')
    assert report['passed'] == 0 and report['total'] == 6
    for row in report['cases']:
        assert row['diagnostics']['reason'] == 'context_conflict'
        assert row['diagnostics']['clarification'][0]['questions']
        assert row['diagnostics']['model_query_draft']['time_expression'] == '本月'
        assert 'not_completed' in row['score_errors']
