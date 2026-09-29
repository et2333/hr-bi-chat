"""OpenAIClientImpl：真实 OpenAI 兼容 Chat Completions 适配器（生产形态）。

配置经环境变量注入（本地未配置密钥时仅 ``generate`` 调用会失败，不影响
pytest——pytest 使用 MockLLMImpl）：
- ``OPENAI_BASE_URL``  默认 https://api.openai.com/v1
- ``OPENAI_API_KEY``   必填（发起真实调用时）
- ``OPENAI_MODEL``     默认 gpt-4o-mini
"""
from __future__ import annotations

import json
import os

import httpx

from adapters.llm_adapter import ModelAdapter

_DEFAULT_BASE_URL = "https://api.openai.com/v1"
_DEFAULT_MODEL = "gpt-4o-mini"


class OpenAIClientImpl(ModelAdapter):
    """OpenAI 兼容 Chat Completions 客户端适配器。"""

    name = "openai"

    def __init__(
        self,
        base_url: str | None = None,
        api_key: str | None = None,
        model: str | None = None,
        temperature: float = 0.2,
        timeout: float = 30.0,
    ) -> None:
        self.base_url = base_url or os.getenv("OPENAI_BASE_URL", _DEFAULT_BASE_URL)
        self.api_key = api_key or os.getenv("OPENAI_API_KEY", "")
        self.model = model or os.getenv("OPENAI_MODEL", _DEFAULT_MODEL)
        self.temperature = temperature
        self.timeout = timeout
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            headers={"Authorization": f"Bearer {self.api_key}"},
            timeout=timeout,
        )

    async def generate(self, prompt: str) -> str:
        """非流式 Chat Completion 调用，返回完整回复文本。"""
        if not self.api_key:
            raise RuntimeError("OpenAI API Key 未配置（OPENAI_API_KEY）")
        resp = await self._client.post(
            "/chat/completions",
            json={
                "model": self.model,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": self.temperature,
            },
        )
        resp.raise_for_status()
        data = resp.json()
        return data["choices"][0]["message"]["content"]

    async def stream_generate(self, prompt: str):
        """流式 Chat Completion 调用，逐 chunk 产出增量文本。"""
        if not self.api_key:
            raise RuntimeError("OpenAI API Key 未配置（OPENAI_API_KEY）")
        async with self._client.stream(
            "POST",
            "/chat/completions",
            json={
                "model": self.model,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": self.temperature,
                "stream": True,
            },
        ) as resp:
            resp.raise_for_status()
            async for line in resp.aiter_lines():
                if not line.startswith("data:"):
                    continue
                chunk = line[len("data:"):].strip()
                if chunk == "[DONE]":
                    break
                try:
                    delta = json.loads(chunk)["choices"][0].get("delta", {}).get("content")
                except (json.JSONDecodeError, KeyError, IndexError):
                    delta = None
                if delta:
                    yield delta

    async def aclose(self) -> None:
        await self._client.aclose()
