"""P2 租户级 LLM 配置隔离测试（POST /v1/config 带 tenant_no、GET /v1/config/current?tenant_no=、默认回退）。"""
import pytest
from fastapi.testclient import TestClient

from agent_gateway.app import _runtimes, apply_llm_config, create_app


@pytest.fixture(autouse=True)
def _reset_runtime():
    _runtimes.clear()
    yield
    _runtimes.clear()


def test_tenant_config_isolation():
    """t01 应用 openai 不影响 t02 与默认槽位。"""
    client = TestClient(create_app())
    resp = client.post(
        "/v1/config",
        json={"tenant_no": "t01", "llm_profile": "openai", "base_url": "http://t01",
              "api_key": "k", "model": "gpt-t01"},
    )
    assert resp.status_code == 200
    assert resp.json()["tenant_no"] == "t01"

    cur_t01 = client.get("/v1/config/current?tenant_no=t01").json()
    assert cur_t01["llm_profile"] == "openai"
    assert cur_t01["model"] == "gpt-t01"

    cur_t02 = client.get("/v1/config/current?tenant_no=t02").json()
    assert cur_t02["llm_profile"] == "mock"

    cur_default = client.get("/v1/config/current").json()
    assert cur_default["llm_profile"] == "mock"


def test_tenant_config_isolated_apply_keeps_other_slots():
    """默认槽位与租户槽位相互独立：t02 下发不影响默认，t01 保持既有 mock 配置。"""
    client = TestClient(create_app())
    client.post("/v1/config", json={"llm_profile": "openai", "base_url": "http:d", "model": "gpt-def"})
    client.post("/v1/config", json={"tenant_no": "t02", "llm_profile": "openai", "model": "gpt-t02"})

    # 默认槽位模型仍是 default 下发的 gpt-def，不受 t02 影响
    assert client.get("/v1/config/current").json()["model"] == "gpt-def"
    assert client.get("/v1/config/current?tenant_no=t02").json()["model"] == "gpt-t02"
    # t01 未被下发 openai，保持既有 mock 配置（槽位隔离；未配置租户回退见 t03 用例）
    assert client.get("/v1/config/current?tenant_no=t01").json()["llm_profile"] == "mock"


def test_tenant_config_restore_mock():
    """按租户重置回 mock。"""
    client = TestClient(create_app())
    client.post("/v1/config", json={"tenant_no": "t01", "llm_profile": "openai", "model": "gpt"})
    resp = client.post("/v1/config", json={"tenant_no": "t01", "llm_profile": "mock"})
    assert resp.status_code == 200
    cur = client.get("/v1/config/current?tenant_no=t01").json()
    assert cur["llm_profile"] == "mock"
    assert cur["model"] == "mock"


def test_default_fallback_when_tenant_not_configured():
    """租户未显式配置时，config/current 与 asks 回退默认槽位。"""
    client = TestClient(create_app())
    client.post("/v1/config", json={"llm_profile": "openai", "base_url": "http:d", "model": "gpt-def"})
    # 未配置 t03 → 与默认槽位一致
    assert client.get("/v1/config/current?tenant_no=t03").json()["llm_profile"] == "openai"
    assert client.get("/v1/config/current?tenant_no=t03").json()["model"] == "gpt-def"


def test_default_update_invalidates_only_inherited_tenants():
    """默认配置变更后继承槽位随之刷新，显式租户配置不受影响。"""
    client = TestClient(create_app())
    client.post("/v1/config", json={
        "llm_profile": "openai", "base_url": "http://default-v1",
        "model": "gpt-v1", "config_version": 1,
    })
    assert client.get("/v1/config/current?tenant_no=inherited").json()["config_version"] == 1
    client.post("/v1/config", json={
        "tenant_no": "explicit", "llm_profile": "openai",
        "base_url": "http://explicit", "model": "gpt-explicit", "config_version": 9,
    })

    client.post("/v1/config", json={
        "llm_profile": "openai", "base_url": "http://default-v2",
        "model": "gpt-v2", "config_version": 2,
    })

    inherited = client.get("/v1/config/current?tenant_no=inherited").json()
    explicit = client.get("/v1/config/current?tenant_no=explicit").json()
    assert inherited["model"] == "gpt-v2"
    assert inherited["config_version"] == 2
    assert explicit["model"] == "gpt-explicit"
    assert explicit["config_version"] == 9
