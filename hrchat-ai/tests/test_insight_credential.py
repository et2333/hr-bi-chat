"""R2 洞察可信调用：缺失/伪造/过期/跨租户/来源篡改/跨用途重放均在模型调用前拒绝。"""
from __future__ import annotations

import json
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from agent_gateway.app import create_app
from insight.credential import require_insight_credential, sign_credential

FIXTURES = Path(__file__).parent / "fixtures" / "insight"
_SECRET = "unit-secret"


@pytest.fixture(autouse=True)
def _insight_secret(monkeypatch):
    """Verification is only meaningful when the shared secret is configured."""
    monkeypatch.setenv("HRCHAT_MCP_SERVICE_TOKEN", _SECRET)


def _body() -> dict:
    body = json.loads((FIXTURES / "headcount_two_period.json").read_text(encoding="utf-8"))
    body["invocation"] = {"task_id": 50, "attempt": 1, "tenant_id": "t01", "correlation_id": "corr-1"}
    return body


def _claims(body: dict, **overrides) -> dict:
    context, snapshot = body["context"], body["snapshot"]
    now = int(time.time())
    claims = {
        "purpose": "insight", "tenant_id": "t01",
        "report_id": context.get("report_id"), "component_id": context.get("component_id"),
        "data_version": context.get("data_version"), "view_key": context.get("view_key"),
        "snapshot_id": snapshot.get("snapshot_id"),
        "task_id": body["invocation"]["task_id"], "attempt": body["invocation"]["attempt"],
        "correlation_id": body["invocation"].get("correlation_id"),
        "iat": now, "exp": now + 300, "jti": "j1",
    }
    claims.update(overrides)
    return claims


def _reject(body: dict, token: str | None, tenant: str | None = "t01", now: int | None = None) -> None:
    with pytest.raises(Exception) as excinfo:
        require_insight_credential(token, tenant, body, now=now)
    assert getattr(excinfo.value, "status_code", None) == 403


def test_valid_credential_is_accepted():
    body = _body()
    claims = require_insight_credential(sign_credential(_SECRET, _claims(body)), "t01", body,
                                        now=None)
    assert claims is not None and claims["purpose"] == "insight"


def test_legacy_body_needs_no_credential():
    assert require_insight_credential(None, None, {"report_name": "r", "summary": {}}) is None


def test_missing_and_malformed_credentials_are_rejected():
    body = _body()
    _reject(body, None)
    _reject(body, "not-a-token")
    _reject(body, "a.b.c")


def test_forged_credential_is_rejected(monkeypatch):
    monkeypatch.setenv("HRCHAT_MCP_SERVICE_TOKEN", _SECRET)
    body = _body()
    forged = sign_credential("wrong-secret", _claims(body))
    _reject(body, forged)


def test_credential_requires_verifiable_secret(monkeypatch):
    monkeypatch.delenv("HRCHAT_MCP_SERVICE_TOKEN", raising=False)
    body = _body()
    _reject(body, sign_credential(_SECRET, _claims(body)))


def test_expired_credential_is_rejected():
    body = _body()
    stale = _claims(body, iat=int(time.time()) - 4000, exp=int(time.time()) - 3600)
    _reject(body, sign_credential(_SECRET, stale))


def test_tenant_mismatch_is_rejected():
    body = _body()
    token = sign_credential(_SECRET, _claims(body, tenant_id="t02"))
    _reject(body, token, tenant="t01")
    _reject(body, token, tenant=None)


def test_source_tampering_is_rejected():
    body = _body()
    signed = _claims(body)
    tampered = dict(body)
    tampered["snapshot"] = dict(body["snapshot"], snapshot_id="snap-other")
    _reject(tampered, sign_credential(_SECRET, signed))
    tampered_view = dict(body)
    tampered_view["context"] = dict(body["context"], view_key="month|org:99")
    _reject(tampered_view, sign_credential(_SECRET, signed))


def test_task_mismatch_is_rejected():
    body = _body()
    signed = _claims(body)
    tampered = dict(body, invocation=dict(body["invocation"], task_id=999))
    _reject(tampered, sign_credential(_SECRET, signed))


def test_cross_purpose_replay_is_rejected():
    body = _body()
    _reject(body, sign_credential(_SECRET, _claims(body, purpose="ask")))


def test_http_endpoint_rejects_before_producing_result(monkeypatch):
    monkeypatch.setenv("HRCHAT_MCP_SERVICE_TOKEN", _SECRET)
    client = TestClient(create_app())
    body = _body()
    # 缺失凭据：在选定适配器/构建 prompt/调用模型之前拒绝，不返回任何生成结果。
    resp = client.post("/v1/insight", json=body, headers={"X-Tenant-No": "t01"})
    assert resp.status_code == 403
    assert "generation" not in resp.json()
