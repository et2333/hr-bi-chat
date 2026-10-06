"""One-call provider probe. Prints safe codes only, never the Key or response body."""
from __future__ import annotations

import asyncio
import json
import os
from urllib.parse import urlparse

from adapters.local_env import load_local_model_env
from adapters.model_usage import ModelCallError
from adapters.openai_client import OpenAIClientImpl


def hint(status: int | None, code: str | None, kind: str | None) -> str:
    marker = (code or "").lower()
    if kind == "network_error":
        return "network_or_proxy: check network access and the API address"
    if kind == "configuration_error":
        return "missing_local_key: set OPENAI_API_KEY in .env.local or this PowerShell session"
    if status == 401:
        return "authentication: check Key completeness, account region and whether the Key requires a dedicated Base URL"
    if status == 403:
        return "access_or_free_quota: check model permission, account state and remaining free quota"
    if status == 404 or "model" in marker:
        return "model_or_endpoint: check the model ID and the API region/endpoint"
    if status == 429:
        return "quota_or_rate_limit: check remaining quota and request limits"
    if status == 400:
        return "request_parameters: check model support and provider error code"
    if status is not None and status >= 500:
        return "provider_service: retry later using the request ID"
    return "response_format: inspect the provider request ID and model compatibility"


async def probe() -> int:
    load_local_model_env()
    base_url = os.getenv("OPENAI_BASE_URL", "")
    model = os.getenv("OPENAI_MODEL", "")
    key = os.getenv("OPENAI_API_KEY", "")
    host = urlparse(base_url).hostname
    if not host or not model or not key:
        print(json.dumps({"status": "not_configured", "required": [
            "OPENAI_BASE_URL", "OPENAI_MODEL", "OPENAI_API_KEY"]}, ensure_ascii=False))
        return 2
    if urlparse(base_url).scheme != "https" and host not in {"localhost", "127.0.0.1"}:
        print(json.dumps({"status": "invalid_url", "hint": "Use an HTTPS provider URL"}))
        return 2

    adapter = OpenAIClientImpl(base_url=base_url, model=model, api_key=key)
    try:
        try:
            result = await adapter.complete('请只输出一个 JSON 对象：{"ok":true}')
        except ModelCallError as exc:
            result = exc.result
    finally:
        await adapter.aclose()
    print(json.dumps({"status": result.status, "provider": result.provider, "model": result.model,
        "http_status": result.http_status, "provider_error_code": result.provider_error_code,
        "request_id": result.request_id, "failure_kind": result.failure_kind,
        "usage_source": result.usage_source,
        "hint": None if result.status == "completed" else hint(result.http_status,
            result.provider_error_code, result.failure_kind)}, ensure_ascii=False))
    return 0 if result.status == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(probe()))
