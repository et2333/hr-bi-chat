import json
from pathlib import Path
import pytest
from tests.test_query_memory import run, BASE
from tests.test_query_plan import Tools

CASES = json.loads((Path(__file__).resolve().parents[1] / 'evals/datasets/manual-feedback-v1/cases.json').read_text(encoding='utf-8'))['cases']


@pytest.mark.parametrize('case', [c for c in CASES if c['expected'] == 'metric_unavailable'])
async def test_unsupported_salary_never_substitutes_headcount_even_with_bad_draft(case):
    tools = Tools()
    result = await run(case['question'], {'metric_codes': ['headcount'], 'metric_text': case['question']}, tools=tools)
    assert result['evidence']['validation_error']['code'] == 'metric_unavailable'
    assert not any(c[0] == 'query' for c in tools.calls)


async def test_department_followup_cannot_silently_keep_scalar_when_model_omits_mode():
    tools = Tools()
    result = await run('各部门', {}, tools=tools)
    assert result['evidence']['validation_error']['code'] == 'mode_condition_omitted'
    assert not any(c[0] == 'query' for c in tools.calls)


async def test_last_month_keeps_previous_metric_and_organization():
    result = await run('上月？', {'time_expression': '上月'})
    plan = result['evidence']['execution']['query_plan']
    assert plan['metric_codes'] == BASE['metric_codes']
    assert plan['org_scope'] == BASE['org_scope']
    assert plan['time_range']['start'] == '2026-08-01'
