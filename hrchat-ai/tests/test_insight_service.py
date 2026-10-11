"""Insight service：富上下文程序降级 vs 旧模板路径。"""
from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from agent_gateway.app import create_app
from insight.schema import InsightStatus
from insight.service import handle_insight

FIXTURES = Path(__file__).parent / "fixtures" / "insight"


def test_java_python_roundtrip_sample_is_accepted():
    body = json.loads((FIXTURES / "java_python_roundtrip_sample.json").read_text(encoding="utf-8"))
    body.pop("_comment", None)
    result = handle_insight(body)
    assert result["status"] == InsightStatus.DEGRADED.value
    assert result["data_version"] == "88"
    assert result["view_key"] == "root"
    assert any(f.get("fact_type") == "absolute_change" and f.get("value") == 1.0 for f in result["facts"])


def test_rich_payload_returns_facts_and_degraded_result():
    body = json.loads((FIXTURES / "headcount_two_period.json").read_text(encoding="utf-8"))
    result = handle_insight(body)
    assert result["status"] == InsightStatus.DEGRADED.value
    assert result["degradation_reason"] == "no_model_draft"
    assert result["data_version"] == "dv-hc-1"
    assert result["view_key"] == "month|org:10"
    assert result["report_name"] == "编制对比"
    assert result["metric_name"] == "在职人数"
    assert isinstance(result["summary"], str) and result["summary"]
    assert isinstance(result["points"], list) and result["points"]
    assert isinstance(result["facts"], list) and result["facts"]
    assert any(f.get("fact_type") == "absolute_change" and f.get("value") == 1.0 for f in result["facts"])
    assert "next_actions" in result


def test_rich_payload_via_http_endpoint():
    client = TestClient(create_app())
    body = json.loads((FIXTURES / "headcount_two_period.json").read_text(encoding="utf-8"))
    resp = client.post("/v1/insight", json=body)
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "degraded"
    assert data["facts"]


def test_legacy_payload_still_template_compatible():
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
    assert "观测值" in body["summary"]
    types = [p["type"] for p in body["points"]]
    assert types == ["value", "trend", "extreme"]
    # 旧路径不强制新字段，但若存在不应破坏兼容
    assert body.get("status") in (None, "legacy", "complete")


def test_invalid_draft_falls_back_to_program_degrade():
    body = json.loads((FIXTURES / "headcount_two_period.json").read_text(encoding="utf-8"))
    body["draft"] = json.loads((FIXTURES / "bad_model_draft.json").read_text(encoding="utf-8"))
    result = handle_insight(body)
    assert result["status"] == InsightStatus.DEGRADED.value
    assert result["degradation_reason"] == "draft_validation_failed"
    assert result["facts"]
    assert result["next_actions"] == [] or all(
        not a.get("executable") for a in result["next_actions"]
    )
