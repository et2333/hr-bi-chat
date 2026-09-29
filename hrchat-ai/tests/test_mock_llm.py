"""MockLLMImpl 确定性输出测试。"""
import pytest

from adapters.llm_adapter import ModelAdapter
from adapters.mock_llm import GREETINGS, MockLlmAdapter, get_llm_adapter


@pytest.mark.asyncio
async def test_mock_llm_greeting_deterministic():
    adapter = MockLlmAdapter()
    prompt = "你好，帮我分析一下本月离职率"
    first = await adapter.generate(prompt)
    second = await adapter.generate(prompt)
    assert first == second
    assert "HR 智能问数助手" in first


@pytest.mark.asyncio
async def test_mock_llm_other_prompt():
    adapter = MockLlmAdapter()
    assert await adapter.generate("计算 6 月招聘达成率") == "（mock）已收到"


@pytest.mark.asyncio
async def test_mock_intent_classify():
    adapter = MockLlmAdapter()
    assert await adapter.generate("【意图判断】你好呀") == "CHITCHAT"
    assert await adapter.generate("【意图判断】本月离职率是多少") == "QUERY"
    assert await adapter.generate("【意图判断】谢谢") == "CHITCHAT"


@pytest.mark.asyncio
async def test_mock_summary_with_numbers():
    adapter = MockLlmAdapter()
    sentence = await adapter.generate(
        "【结论摘要】metric=离职率|current=1.88|compare=2.05|prev_period=2026-08-01/2026-08-31|unit=%|percent=1"
    )
    assert "离职率" in sentence
    assert "下降" in sentence  # 1.88 < 2.05


@pytest.mark.asyncio
async def test_get_llm_adapter_mock_profile():
    adapter = get_llm_adapter("mock")
    assert isinstance(adapter, MockLlmAdapter)
    assert isinstance(adapter, ModelAdapter)
    assert await adapter.generate("随便说点什么") == "（mock）已收到"


@pytest.mark.asyncio
async def test_estimate_tokens_deterministic():
    adapter = MockLlmAdapter()
    assert adapter.estimate_tokens("你好") == adapter.estimate_tokens("你好")


def test_greetings_covers_hello():
    assert any("你好" in g for g in GREETINGS)
