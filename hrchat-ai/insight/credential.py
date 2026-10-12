"""Insight trusted-call credential (R2).

Java signs a compact payload with the shared service secret (HMAC-SHA256) and
sends it as ``X-Insight-Credential``. The payload is purpose-bound
(``purpose == "insight"``) and binds tenant, report/component/view source,
snapshot, task/attempt and expiry. Python verifies it before selecting a tenant
adapter, building a prompt or calling a model — a global token is therefore not
authorization for an arbitrary tenant or snapshot.

The credential and its signature never enter prompts or logs.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from typing import Any

from fastapi import HTTPException

PURPOSE = "insight"
HEADER = "X-Insight-Credential"
SECRET_ENV = "HRCHAT_MCP_SERVICE_TOKEN"
# Clock skew tolerated for a freshly issued credential.
_SKEW_SECONDS = 30

# Fields the credential pins and the raw body must reproduce.
_SOURCE_FIELDS = ("report_id", "component_id", "data_version", "view_key")
_INVOCATION_FIELDS = ("task_id", "attempt")


def is_rich(body: dict[str, Any]) -> bool:
    """A body only reaches the model path when it carries context and/or snapshot."""
    return "context" in body or "snapshot" in body


def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _b64d(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def sign_credential(secret: str, claims: dict[str, Any]) -> str:
    """Mirror of the Java signer, used by fixtures/tests; signing needs the secret."""
    raw = json.dumps(claims, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    signature = hmac.new(secret.encode("utf-8"), raw, hashlib.sha256).digest()
    return f"{_b64e(raw)}.{_b64e(signature)}"


def _match(claims: dict[str, Any], key: str, expected: Any, what: str) -> None:
    if str(claims.get(key)) != str(expected):
        raise HTTPException(403, f"洞察调用{what}与凭据不一致")


def require_insight_credential(
    token: str | None,
    tenant_no: str | None,
    body: dict[str, Any],
    *,
    now: int | None = None,
) -> dict[str, Any] | None:
    """Validate the insight credential for a rich body.

    Returns the verified claims (``None`` for legacy template bodies, which never
    invoke the tenant adapter). Raises ``HTTPException`` before any adapter or
    model selection when the credential is missing, forged, expired, mis-scoped,
    replayed across purpose, or inconsistent with the declared source.
    """
    if not is_rich(body):
        return None
    if not token:
        raise HTTPException(403, "洞察调用缺少可信凭据")
    secret = os.getenv(SECRET_ENV, "")
    if not secret:
        raise HTTPException(403, "洞察调用凭据不可验证")
    parts = token.split(".")
    if len(parts) != 2 or not parts[0] or not parts[1]:
        raise HTTPException(403, "洞察调用凭据格式无效")
    try:
        raw = _b64d(parts[0])
        signature = _b64d(parts[1])
    except (ValueError, TypeError):
        raise HTTPException(403, "洞察调用凭据格式无效")
    expected = hmac.new(secret.encode("utf-8"), raw, hashlib.sha256).digest()
    if not hmac.compare_digest(expected, signature):
        raise HTTPException(403, "洞察调用凭据无效")
    try:
        claims = json.loads(raw)
    except (ValueError, TypeError):
        raise HTTPException(403, "洞察调用凭据无效")
    if not isinstance(claims, dict) or claims.get("purpose") != PURPOSE:
        raise HTTPException(403, "洞察调用凭据用途不匹配")
    current = int(time.time()) if now is None else int(now)
    issued = claims.get("iat")
    expires = claims.get("exp")
    if type(issued) is not int or type(expires) is not int:
        raise HTTPException(403, "洞察调用凭据缺少有效期")
    if expires <= current or issued > current + _SKEW_SECONDS:
        raise HTTPException(403, "洞察调用上下文已过期")
    if claims.get("tenant_id") != (tenant_no or ""):
        raise HTTPException(403, "洞察调用租户不一致")

    context = body.get("context") or {}
    snapshot = body.get("snapshot") or {}
    for field in _SOURCE_FIELDS:
        _match(claims, field, context.get(field), "来源")
    _match(claims, "snapshot_id", snapshot.get("snapshot_id"), "快照")
    _match(claims, "data_version", snapshot.get("data_version"), "来源")
    _match(claims, "view_key", snapshot.get("view_key"), "来源")
    invocation = body.get("invocation") or {}
    for field in _INVOCATION_FIELDS:
        _match(claims, field, invocation.get(field), "任务")
    return claims
