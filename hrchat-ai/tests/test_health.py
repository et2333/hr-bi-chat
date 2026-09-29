"""GET /health 健康检查测试。"""
from fastapi.testclient import TestClient

from agent_gateway.app import create_app


def test_health_returns_ok():
    client = TestClient(create_app())
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["llm_profile"] == "mock"
