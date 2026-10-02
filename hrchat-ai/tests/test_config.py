"""LLM 配置热应用测试（POST /v1/config、GET /v1/config/current）。"""
import pytest
from fastapi.testclient import TestClient

from agent_gateway.app import _runtimes, apply_llm_config, create_app


@pytest.fixture(autouse=True)
def _reset_runtime():
    """用例结束后复位全局 _runtime，避免污染其它测试文件。"""
    _runtimes.clear()
    yield
    _runtimes.clear()


def test_config_current_initial_mock():
    client = TestClient(create_app())
    resp = client.get("/v1/config/current")
    assert resp.status_code == 200
    body = resp.json()
    assert body["llm_profile"] == "mock"
    assert body["model"] == "mock"
    assert body["base_url"] is None


def test_apply_openai_config_then_current():
    client = TestClient(create_app())
    resp = client.post(
        "/v1/config",
        json={
            "llm_profile": "openai",
            "base_url": "http://x",
            "api_key": "k",
            "model": "gpt-test",
            "temperature": 0.5,
            "config_version": 7,
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["llm_profile"] == "openai"
    assert body["model"] == "gpt-test"
    assert body["config_version"] == 7

    cur = client.get("/v1/config/current").json()
    assert cur["llm_profile"] == "openai"
    assert cur["model"] == "gpt-test"
    assert cur["base_url"] == "http://x"
    assert cur["config_version"] == 7


def test_apply_mock_restores_current():
    client = TestClient(create_app())
    client.post(
        "/v1/config",
        json={"llm_profile": "openai", "base_url": "http://x", "api_key": "k", "model": "gpt-test"},
    )
    resp = client.post("/v1/config", json={"llm_profile": "mock"})
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
    cur = client.get("/v1/config/current").json()
    assert cur["llm_profile"] == "mock"
    assert cur["model"] == "mock"


def test_health_reflects_applied_profile():
    client = TestClient(create_app())
    client.post(
        "/v1/config",
        json={"tenant_no": "t01", "llm_profile": "openai", "base_url": "http://x",
              "api_key": "k", "model": "gpt-test", "config_version": "v3"},
    )
    resp = client.get("/health?tenant_no=t01")
    assert resp.status_code == 200
    assert resp.json()["llm_profile"] == "openai"
    assert resp.json()["tenant_no"] == "t01"
    assert resp.json()["model"] == "gpt-test"
    assert resp.json()["config_version"] == "v3"
