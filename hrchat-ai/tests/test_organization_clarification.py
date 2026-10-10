"""J2: ambiguity must survive model guesses and continue through Java-owned memory."""
from copy import deepcopy
import pytest
from langgraph_flows.ask_flow import run_ask_flow
from tests.test_query_draft import DraftPlanner
from tests.test_query_plan import CATALOG, Tools


def catalog():
    value = deepcopy(CATALOG)
    value['organizations'] += [
        {'org_id': '9', 'name': '研发部', 'aliases': [], 'label': '研发部（研发一部 / RD1-DEV）'},
        {'org_id': '10', 'name': '研发部', 'aliases': [], 'label': '研发部（研发二部 / RD2-DEV）'}]
    return value


async def ask(question, draft, memory=None, directory=None):
    tools = Tools(catalog=directory or catalog())
    planner = DraftPlanner(draft)
    result = await run_ask_flow(question=question, session_id='s', ask_id='a', adapter=planner,
        tools=tools, query_context=memory or {'schema_version': '1', 'context_version': 1})
    return result, tools, planner


def pending(result, option):
    candidate = result['evidence']['query_context_candidate']
    return {'schema_version': '1', 'context_version': 2,
        'pending': {**candidate, 'ask_id': 'a', 'source_turn': 'a'},
        'selection': {'question_id': result['clarify_questions'][0]['question_id'], 'option_id': option}}


@pytest.mark.parametrize('period', [None, '上月'])
async def test_guessed_homonym_id_requires_choice_then_preserves_metric_and_period(period):
    question = (period or '') + '研发部离职人数'
    draft = {'decision': 'execute', 'metric_codes': ['leave_count'], 'metric_text': '离职人数',
        'organization': {'kind': 'catalog_id', 'org_id': '9', 'source_text': '研发部'}, 'time_expression': period}
    first, tools, _ = await ask(question, draft)
    assert first['clarify_questions'][0]['slot'] == 'organization'
    assert {o['option_id'] for o in first['clarify_questions'][0]['options']} == {'9', '10'}
    assert not any(c[0] == 'query' for c in tools.calls)
    second, _, planner = await ask(question, {}, pending(first, '10'))
    assert planner.prompts == [], 'validated button selection must not need another model call'
    if not period:
        assert second['clarify_questions'][0]['slot'] == 'time_range'
        second, _, _ = await ask(question, {}, pending(second, 'time:LAST_MONTH'))
    plan = second['evidence']['execution']['query_plan']
    assert plan['metric_codes'] == ['leave_count']
    assert plan['org_scope']['org_id'] == '10'
    assert plan['time_range']['start'] == '2026-08-01'


async def test_choice_rechecks_current_authorization():
    first, _, _ = await ask('研发部在职人数', {'decision': 'execute', 'metric_codes': ['headcount'],
        'metric_text': '在职人数', 'organization': {'kind': 'catalog_id', 'org_id': '9', 'source_text': '研发部'}})
    changed = catalog()
    changed['organizations'] = [o for o in changed['organizations'] if o['org_id'] != '10']
    result, tools, _ = await ask('研发部在职人数', {}, pending(first, '10'), changed)
    assert result['error']['code'] == 'HRC-2003'
    assert not any(c[0] == 'query' for c in tools.calls)


async def test_two_explicit_organizations_are_not_misrepresented_as_homonyms():
    result, tools, _ = await ask('研发中心和研发部在职人数', {'decision': 'execute', 'metric_codes': ['headcount'],
        'metric_text': '在职人数', 'organization': {'kind': 'catalog_id', 'org_id': '9', 'source_text': '研发部'}})
    assert result['evidence']['reason'] == 'unsupported_capability'
    assert not any(c[0] == 'query' for c in tools.calls)
