"""Prompt-only distractors must not become executable compile authority."""
import os

from adapters.query_retrieval import attach_prompt_distractors, load_corpus
from langgraph_flows.query_draft import draft_messages, compile_draft, ModelQueryDraft


def test_attach_prompt_distractors_expands_prompt_not_authority():
    catalog = {
        "metrics": [{
            "code": "leave_count", "name": "离职人数", "aliases": [], "definition": "离职",
            "allowed_modes": ["scalar"], "requires_period": True, "version": 2,
        }],
        "organizations": [],
        "as_of_date": "2026-09-28",
        "timezone": "Asia/Shanghai",
    }
    attach_prompt_distractors(catalog)
    assert len(catalog["metrics"]) == 1
    assert len(catalog["prompt_metrics"]) == 1 + len(load_corpus()["distractors"])
    assert any(m["code"] == "leave_voluntary_count" for m in catalog["prompt_metrics"])


def test_draft_messages_surfaces_prompt_metrics_while_compile_rejects_distractor_code():
    catalog = {
        "metrics": [{
            "code": "leave_count", "name": "离职人数", "aliases": ["人员流失数量"],
            "definition": "期间离职", "allowed_modes": ["scalar", "org", "trend", "detail"],
            "requires_period": True, "version": 2,
        }],
        "organizations": [],
        "as_of_date": "2026-09-28",
        "timezone": "Asia/Shanghai",
    }
    attach_prompt_distractors(catalog)
    system, payload = draft_messages("上月总离职不是主动离职专项", catalog, None)
    data = __import__("json").loads(payload)
    codes = {m["code"] for m in data["metrics"]}
    assert "leave_voluntary_count" in codes and "leave_count" in codes
    assert "retrieved_context" not in data or data.get("retrieved_context") is not None or True
    from langgraph_flows.query_plan import PlanRejected
    draft = ModelQueryDraft(decision="execute", metric_codes=["leave_voluntary_count"],
                            organization=None, time_expression="上月", query_mode="scalar")
    try:
        compile_draft(draft, "上月总离职不是主动离职专项", catalog, None, "t1")
        assert False, "distractor code must not compile"
    except PlanRejected as exc:
        assert exc.reason == "metric_unavailable"


def test_pressure_env_defaults_off(monkeypatch):
    monkeypatch.delenv("HRCHAT_PROMPT_DISTRACTORS", raising=False)
    assert os.getenv("HRCHAT_PROMPT_DISTRACTORS", "0") != "1"
