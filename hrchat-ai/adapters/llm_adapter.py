"""ModelAdapter 抽象接口（计划 S7：OpenAIClientImpl + MockLLMImpl）。

问数状态机与归因团队统一通过 ModelAdapter 调用模型能力：
- ``generate(prompt)`` 生成完整文本（确定性/真实 LLM 两形态共用）。
- ``estimate_tokens`` 供预算熔断做粗粒度计费（确定性估算）。
"""
from __future__ import annotations

from abc import ABC, abstractmethod
import time
import uuid

from adapters.model_usage import ModelResult


class ModelAdapter(ABC):
    """LLM/模型适配器抽象基类。"""

    name: str = "adapter"
    supports_query_plan: bool = False

    async def complete(self, prompt: str) -> ModelResult:
        """Backward-compatible adapters have unknown API usage, never invented tokens."""
        started = time.perf_counter()
        text = await self.generate(prompt)
        return ModelResult(text, self.name, getattr(self, "model", self.name),
                           "llm_" + uuid.uuid4().hex, int((time.perf_counter() - started) * 1000))

    @abstractmethod
    async def generate(self, prompt: str) -> str:
        """根据 prompt 生成回复文本。"""
        raise NotImplementedError

    async def complete_analysis(self, system_prompt: str, user_prompt: str) -> ModelResult:
        """Structured analysis hook; local adapters retain unknown API usage."""
        return await self.complete(system_prompt + "\n" + user_prompt)

    def estimate_tokens(self, text: str) -> int:
        """粗粒度 token 估算（中英文混合按字符计，供预算熔断使用）。"""
        # 中文按 1 字符 ≈ 1 token 粗略估算，保证确定性即可
        return max(1, len(text) // 2 + 1)
