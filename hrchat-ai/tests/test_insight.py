"""报表 AI 洞察解读测试（POST /v1/insight 模板化均值/趋势/极值）。"""
from fastapi.testclient import TestClient

from agent_gateway.app import create_app


def test_insight_returns_template_points():
    client = TestClient(create_app())
    resp = client.post(
        "/v1/insight",
        json={
            "report_name": "人力成本月度报表",
            "metric_name": "人力成本",
            "summary": {
                "categories": ["2026-01", "2026-02", "2026-03"],
                "series": [{"name": "人力成本", "data": [100, 120, 110]}],
            },
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["report_name"] == "人力成本月度报表"
    assert "均值" in body["summary"]
    assert "走势" in body["summary"]
    types = [p["type"] for p in body["points"]]
    assert types == ["value", "trend", "extreme"]
    # 峰值 120 出现在 2026-02
    extreme = [p for p in body["points"] if p["type"] == "extreme"][0]
    assert "2026-02" in extreme["label"]
    assert "120.0" in extreme["label"]


def test_insight_empty_data_returns_generic():
    client = TestClient(create_app())
    resp = client.post(
        "/v1/insight",
        json={"report_name": "空报表", "metric_name": "人数", "summary": {"categories": [], "series": []}},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert "暂无可用数据" in body["summary"]
    assert body["points"] == []


def test_insight_missing_payload_ok():
    client = TestClient(create_app())
    resp = client.post("/v1/insight", json={})
    assert resp.status_code == 200
    assert resp.json()["points"] == []
