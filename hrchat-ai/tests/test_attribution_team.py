"""AgentScope 归因 Team 收敛性与预算熔断用例（2.2.10）。"""
import json

import pytest

from agent_gateway.schemas import ERR_ATTRIBUTION_BUDGET, EVENT_ERROR, EVENT_FINAL, EVENT_PLAN_UPDATE, EVENT_TOOL_CALL_END, EVENT_TOOL_CALL_START
from adapters.mock_llm import MockLlmAdapter
from agentscope_teams.attribution_team import AttributionTeam, build_waterfall


def _context():
    return {
        "metric_code": "headcount",
        "current": 1275.0,
        "compare": 1240.0,
        "prev_period": "2026-08-01/2026-08-31",
        "question": "在职人数为什么涨了",
    }


async def _collect(team, context):
    events = []
    async for evt in team.reply_stream(_Inputs(json.dumps(context, ensure_ascii=False))):
        events.append(evt)
    return events


class _Inputs:
    def __init__(self, content: str) -> None:
        self.content = content
        self.role = "user"
        self.name = "user"


@pytest.mark.asyncio
async def test_attribution_team_converges_to_final():
    team = AttributionTeam(MockLlmAdapter())
    events = await _collect(team, _context())
    kinds = [e["event"] for e in events]
    assert kinds[0] == EVENT_PLAN_UPDATE
    assert kinds[-1] == EVENT_FINAL
    assert EVENT_TOOL_CALL_START in kinds and EVENT_TOOL_CALL_END in kinds
    final = events[-1]["payload"]
    assert final["disclaimer"] == "辅助分析，仅供参考"
    assert final["metric"] == "在职人数"
    assert final["confidence"] == 0.92
    # 瀑布合计 = 期末值拆解（1240 上期末 + 58 入职 - 23 离职 = 1275 期末）
    assert sum(i["value"] for i in final["waterfall"]) == 1275
    # 收敛性：步数不超过预算
    assert team.usage["steps"] <= team.max_steps
    assert team.usage["tokens"] <= team.max_tokens


@pytest.mark.asyncio
async def test_attribution_team_budget_breaker():
    team = AttributionTeam(MockLlmAdapter(), max_steps=1)
    events = await _collect(team, _context())
    error_evt = events[-1]
    assert error_evt["event"] == EVENT_ERROR
    assert error_evt["payload"]["code"] == ERR_ATTRIBUTION_BUDGET
    assert error_evt["payload"]["recoverable"] is False
    # 兜底基础对比数据
    assert error_evt["payload"]["base"]["metric"] == "在职人数"
    assert error_evt["payload"]["base"]["current"] == 1275.0
    # 无 FINAL（熔断终止）
    assert all(e["event"] != EVENT_FINAL for e in events)


@pytest.mark.asyncio
async def test_attribution_token_budget_breaker():
    class TinyTokenAdapter(MockLlmAdapter):
        def estimate_tokens(self, text: str) -> int:
            return 10_000  # 每次调用即超预算

    team = AttributionTeam(TinyTokenAdapter(), max_tokens=100)
    events = await _collect(team, _context())
    assert events[-1]["event"] == EVENT_ERROR
    assert events[-1]["payload"]["code"] == ERR_ATTRIBUTION_BUDGET


@pytest.mark.asyncio
async def test_waterfall_headcount_self_consistent():
    items = build_waterfall("headcount")
    assert sum(i["value"] for i in items) == 1275  # 上期末+入职-离职=期末值


@pytest.mark.asyncio
async def test_plan_update_steps_progress():
    team = AttributionTeam(MockLlmAdapter())
    events = await _collect(team, _context())
    plan_events = [e for e in events if e["event"] == EVENT_PLAN_UPDATE]
    assert len(plan_events) >= 1
    last_plan = plan_events[-1]["payload"]["steps"]
    assert all(s["status"] == "DONE" for s in last_plan)
