"""Finite planning action only prepares confirmation; never starts analysis tools."""
import asyncio
import pytest

from tests.test_query_memory import run
from tests.test_query_plan import Tools
from tests.test_query_draft import DraftPlanner
from langgraph_flows.ask_flow import run_ask_flow


@pytest.mark.parametrize('question', ['分析变化', '哪个部门变动最多？', '为什么变化？'])
async def test_analysis_followup_only_prepares(question):
    tools = Tools()
    result = await run(question, {'action': 'prepare_analysis'}, tools=tools)
    assert result['answer_payload']['intent'] == 'ANALYSIS'
    assert result['evidence']['analysis_request']['source'] == 'latest_completed_query'
    assert not result['evidence'].get('execution')
    assert all(c[0] != 'query' for c in tools.calls)


@pytest.mark.parametrize('question', ['分析上月变化', '分析研发中心变化', '分析销售二部变化', '分析薪资变化', '分析入职变化'])
async def test_model_cannot_drop_explicit_conditions_to_reuse_old_answer(question):
    tools = Tools()
    result = await run(question, {'action': 'prepare_analysis'}, tools=tools)
    assert 'analysis_request' not in result['evidence']
    assert all(c[0] != 'query' for c in tools.calls)


async def test_planning_progress_is_emitted_before_model_completes():
    started, release = asyncio.Event(), asyncio.Event()
    class Blocking(DraftPlanner):
        async def complete_query_draft(self, system, user):
            started.set()
            await release.wait()
            return await super().complete_query_draft(system, user)
    planner = Blocking({'decision': 'execute', 'metric_codes': ['headcount']})
    events = []
    task = asyncio.create_task(run_ask_flow(question='在职人数', session_id='s', ask_id='a',
        tools=Tools(), adapter=planner, on_event=events.append))
    try:
        await asyncio.wait_for(started.wait(), 3)
        assert not task.done()
        assert any(e['event'] == 'PROGRESS' and e['payload']['status'] == 'started' for e in events)
    finally:
        release.set()
        await task
    assert any(e['event'] == 'USAGE' for e in events)
