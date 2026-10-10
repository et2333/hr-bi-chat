"""Scripted J4/J5/clarification acceptance; no external model adapter."""
import asyncio
import json
import os
os.environ['LLM_PROFILE'] = 'mock'
from evals.mode_fixture_gateway import ModeFixturePlanner
from adapters.model_usage import ModelResult
from agent_gateway.app import app, _runtimes, DEFAULT_TENANT_KEY


class ConversationPlanner(ModeFixturePlanner):
    async def complete_query_draft(self, system, user):
        await asyncio.sleep(.35)  # observable stage, explicitly synthetic
        q = json.loads(user)['question']
        if q == '分析变化':
            draft = {'action': 'prepare_analysis', 'decision': 'execute'}
        elif q == '人员变动情况':
            draft = {'decision': 'clarify', 'metric_codes': ['hire_count', 'leave_count'], 'metric_text': '人员变动'}
        else:
            original = await super().complete_query_draft(system, user)
            draft = json.loads(original.text)
            for word in ['上月', '本月', '近30天']:
                if word in q:
                    draft['time_expression'] = word
            if q == '各部门':
                draft.update(query_mode='org', mode_text=q)
        return ModelResult(json.dumps(draft, ensure_ascii=False), 'fixture', 'conversation-fixture',
                           'fixture-call', 350, finish_reason='stop')


_runtimes[DEFAULT_TENANT_KEY] = {'profile': 'fixture', 'adapter': ConversationPlanner(),
    'model': 'conversation-fixture', 'config_version': 'fixture-only', 'inherited': False}
