"""LangGraph 问数状态机用例（MockLLM + QUERY_BACKEND=demo）。"""
import pytest

from agent_gateway.schemas import (
    ASK_COMPLETED,
    ERR_DATA_RANGE_FORBIDDEN,
    ERR_INTENT_NOT_UNDERSTOOD,
    EVENT_ANSWER_DONE,
    EVENT_INTERRUPT,
    EVENT_MESSAGE_DELTA,
    EVENT_TOOL_CALL_END,
    EVENT_TOOL_CALL_START,
    INTENT_CHITCHAT,
    INTENT_QUERY,
)
from adapters.mock_llm import MockLlmAdapter
from adapters.semantic_tool_client import DemoSemanticToolClient
from langgraph_flows.ask_flow import build_graph, run_ask_flow
from langgraph_flows.demo_data import DemoQueryExecutor

ADAPTER = MockLlmAdapter()
TOOLS = DemoSemanticToolClient(DemoQueryExecutor())


async def _ask(question: str, **kwargs):
    return await run_ask_flow(
        question=question,
        session_id="s1",
        ask_id="ask_test01",
        adapter=ADAPTER,
        tools=TOOLS,
        **kwargs,
    )


@pytest.mark.asyncio
async def test_simple_query_answer_done():
    result = await _ask("研发中心在职人数")
    payload = result["answer_payload"]
    assert payload["status"] == ASK_COMPLETED
    assert payload["intent"] == INTENT_QUERY
    assert payload["conclusion"]["type"] == "NUMBER_CARD"
    assert payload["conclusion"]["value"] == 1275
    assert payload["caliber"]["metric"] == "在职人数"
    assert payload["caliber"]["data_updated_at"].endswith("+08:00")
    # 事件序列契约：PARSING delta → semantic_search → sql_exec → SUMMARIZING → ANSWER_DONE
    events = result["events"]
    kinds = [e["event"] for e in events]
    assert kinds[0] == EVENT_MESSAGE_DELTA
    assert kinds[-1] == EVENT_ANSWER_DONE
    assert EVENT_TOOL_CALL_START in kinds and EVENT_TOOL_CALL_END in kinds
    assert payload["table"]["columns"][0]["key"] == "headcount"


@pytest.mark.asyncio
async def test_time_window_from_question():
    result = await _ask("上月离职率是多少")
    payload = result["answer_payload"]
    assert payload["caliber"]["time_range"].startswith("2026-08-01/")
    assert payload["conclusion"]["compare"]["period"].startswith("2026-07-")
    assert payload["conclusion"]["compare"]["direction"] == "DOWN"  # 1.88 < 2.05


@pytest.mark.asyncio
async def test_chitchat_intent():
    result = await _ask("你好")
    payload = result["answer_payload"]
    assert payload["intent"] == INTENT_CHITCHAT
    assert payload["conclusion"]["type"] == "TEXT"
    assert "HR 智能问数助手" in payload["conclusion"]["value"]


@pytest.mark.asyncio
async def test_ambiguous_question_interrupt_and_resume():
    # 「离职率」「出勤率」等长同义词同时命中 → 澄清
    result = await _ask("离职率和出勤率")
    assert result["clarify_questions"], "应进入澄清态"
    interrupt = next(e for e in result["events"] if e["event"] == EVENT_INTERRUPT)
    assert interrupt["payload"]["interrupt_type"] == "CLARIFY"
    assert interrupt["payload"]["questions"][0]["options"]
    # 澄清续跑：强制指定指标
    resumed = await _ask("离职率和出勤率", forced_metric_code="turnover_rate")
    assert resumed["answer_payload"]["conclusion"]["value"] == 1.88


@pytest.mark.asyncio
async def test_no_permission_error():
    # hr02 仅授权销售，问研发中心 → HRC-2003
    result = await _ask("研发中心离职率是多少", user_no="hr02")
    assert result["error"]
    assert result["error"]["code"] == ERR_DATA_RANGE_FORBIDDEN
    assert result["error"]["recoverable"] is False
    assert not result["answer_payload"]


@pytest.mark.asyncio
async def test_permission_allowed_user():
    result = await _ask("研发中心离职率是多少", user_no="hr01")
    assert result["answer_payload"]
    assert result["answer_payload"]["caliber"]["metric"] == "离职率"


@pytest.mark.asyncio
async def test_not_understood_error():
    result = await _ask("今天天气怎么样呀")
    assert result["error"]
    assert result["error"]["code"] == ERR_INTENT_NOT_UNDERSTOOD
    assert result["error"]["recoverable"] is True


@pytest.mark.asyncio
async def test_langgraph_build_and_invoke():
    graph = build_graph(ADAPTER, TOOLS)
    result = await graph.ainvoke({
        "session_id": "s1", "ask_id": "ask_g", "question": "研发中心在职人数",
        "events": [], "clarify_questions": [],
    })
    assert result["answer_payload"]["conclusion"]["value"] == 1275


@pytest.mark.asyncio
async def test_sync_mode_result():
    result = await run_ask_flow(
        question="入职人数",
        session_id="s1", ask_id="ask_sync",
        adapter=ADAPTER, tools=TOOLS, mode="SYNC",
    )
    assert result["answer_payload"]["conclusion"]["value"] == 58
    assert result["answer_payload"]["elapsed_ms"] >= 0


@pytest.mark.asyncio
async def test_legacy_executor_kwarg_still_works():
    """兼容旧参数名 executor=DemoQueryExecutor。"""
    result = await run_ask_flow(
        question="入职人数",
        session_id="s1",
        ask_id="ask_legacy",
        adapter=ADAPTER,
        executor=DemoQueryExecutor(),
        mode="SYNC",
    )
    assert result["answer_payload"]["conclusion"]["value"] == 58


@pytest.mark.asyncio
async def test_context_override_time_range():
    from agent_gateway.schemas import ContextOverride, TimeRangeOverride

    co = ContextOverride(time_range=TimeRangeOverride(preset="LAST_MONTH"))
    result = await _ask("离职率", context_override=co.model_dump())
    assert result["answer_payload"]["caliber"]["time_range"].startswith("2026-08-01/")
