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
import re
import time
import uuid
from urllib.parse import urlparse

import httpx

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelResult, ModelCallError

_DEFAULT_BASE_URL = "https://api.openai.com/v1"
_DEFAULT_MODEL = "gpt-4o-mini"


class OpenAIClientImpl(ModelAdapter):
    """OpenAI 兼容 Chat Completions 客户端适配器。"""

    name = "openai"
    supports_query_plan = True
    supports_query_draft = True

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
        # Bound the first QueryPlan attempt; do not spend tokens on optional thinking.
        # DeepSeek documents thinking as enabled by default. Other vendors receive no vendor fields.
        self.request_options = {"max_tokens": 2048}
        if urlparse(self.base_url).hostname == "api.deepseek.com":
            self.request_options.update(thinking={"type": "disabled"})
        if (urlparse(self.base_url).hostname == "dashscope.aliyuncs.com"
                and self.model in {"qwen-turbo", "qwen-plus"}):
            # Both demo models support hybrid thinking. Non-streaming QueryPlan needs visible JSON.
            self.request_options["enable_thinking"] = False
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            headers={"Authorization": f"Bearer {self.api_key}"},
            timeout=timeout,
        )

    async def generate(self, prompt: str) -> str:
        """非流式 Chat Completion 调用，返回完整回复文本。"""
        return (await self.complete(prompt)).text

    async def complete(self, prompt: str) -> ModelResult:
        return await self._complete([{"role": "user", "content": prompt}])

    def planning_options(self):
        # JSON Object is supported by the two verified Model Studio models.
        if (urlparse(self.base_url).hostname == "dashscope.aliyuncs.com"
                and self.model in {"qwen-turbo", "qwen-plus"}):
            return {"response_format": {"type": "json_object"}}
        return {}

    async def complete_query_draft(self, system_prompt: str, user_prompt: str) -> ModelResult:
        return await self._complete([{"role": "system", "content": system_prompt},
                                     {"role": "user", "content": user_prompt}], self.planning_options())

    async def _complete(self, messages, options=None) -> ModelResult:
        started = time.perf_counter()
        result = ModelResult("", urlparse(self.base_url).hostname or self.name, self.model,
                             "llm_" + uuid.uuid4().hex, 0, status="failed")
        try:
            if not self.api_key:
                raise RuntimeError("API key not configured")
            resp = await self._client.post("/chat/completions", json={
                "model": self.model, "messages": messages,
                "temperature": self.temperature,
                **self.request_options,
                **(options or {}),
            })
            result.http_status = resp.status_code
            result.request_id = resp.headers.get("x-request-id")
            if resp.is_error:
                try:
                    error_data = resp.json()
                    if isinstance(error_data, dict):
                        error = error_data.get("error")
                        code = (error.get("code") if isinstance(error, dict) else None) or error_data.get("code")
                        if isinstance(code, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", code):
                            result.provider_error_code = code
                        result.request_id = result.request_id or error_data.get("request_id")
                except (ValueError, TypeError):
                    pass
            resp.raise_for_status()
            data = resp.json()
            result.model = data.get("model") or self.model
            result.request_id = result.request_id or data.get("id")
            result.usage = data.get("usage") if isinstance(data.get("usage"), dict) else None
            result.usage_source = "actual" if result.usage else "unknown"
            choice = data["choices"][0]
            result.finish_reason = choice.get("finish_reason")
            result.text = choice["message"]["content"]
            if not isinstance(result.text, str):
                raise ValueError("missing model content")
            result.status = "completed"
        except httpx.HTTPStatusError as exc:
            result.failure_kind = "http_error"
            result.elapsed_ms = int((time.perf_counter() - started) * 1000)
            raise ModelCallError(result) from exc
        except httpx.RequestError as exc:
            result.failure_kind = "network_error"
            result.exception_type = type(exc).__name__
            result.elapsed_ms = int((time.perf_counter() - started) * 1000)
            raise ModelCallError(result) from exc
        except Exception as exc:
            result.failure_kind = "configuration_error" if not self.api_key else "response_error"
            result.elapsed_ms = int((time.perf_counter() - started) * 1000)
            raise ModelCallError(result) from exc
        result.elapsed_ms = int((time.perf_counter() - started) * 1000)
        return result

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
