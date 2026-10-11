"""Lightweight insight generation: one call, ≤1 repair, no model call without data."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelCallError, ModelResult
from insight.generation import build_prompt, generate_insight
from insight.schema import InsightRequest, InsightStatus

FIXTURES = Path(__file__).parent / "fixtures" / "insight"

_VALID_DRAFT = json.dumps({
    "selected_fact_ids": ["absolute_change:overall"],
    "claims": [{
        "claim_id": "c1", "kind": "fact", "text": "在职人数由 1 人增至 2 人",
        "fact_ids": ["absolute_change:overall"], "predicate": "absolute_increase",
        "unit": "人", "scope_ref": "org:10",
    }],
    "limitations": [],
    "next_actions": [],
}, ensure_ascii=False)


def _request() -> InsightRequest:
    return InsightRequest.model_validate(json.loads(
        (FIXTURES / "headcount_two_period.json").read_text(encoding="utf-8")))


class ScriptAdapter(ModelAdapter):
    """Deterministic script model that records call count and prompts."""

    name = "script"

    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = 0

    async def generate(self, prompt: str) -> str:
        item = self.responses[min(self.calls, len(self.responses) - 1)]
        self.calls += 1
        if isinstance(item, Exception):
            raise item
        return item


def _run(adapter):
    return asyncio.run(generate_insight(_request(), adapter))


def test_valid_draft_generates_once():
    adapter = ScriptAdapter(_VALID_DRAFT)
    result = _run(adapter)
    assert result.status == InsightStatus.COMPLETE
    assert adapter.calls == 1
    assert result.generation["call_count"] == 1
    assert result.generation["repair_count"] == 0
    assert result.generation["usage_source"] == "unknown"
    assert result.claims and result.claims[0].fact_ids == ["absolute_change:overall"]


def test_malformed_output_is_repaired_once():
    adapter = ScriptAdapter("not json at all", _VALID_DRAFT)
    result = _run(adapter)
    assert result.status == InsightStatus.COMPLETE
    assert adapter.calls == 2
    assert result.generation["repair_count"] == 1


def test_double_failure_degrades_after_one_repair():
    adapter = ScriptAdapter("nope", "still nope")
    result = _run(adapter)
    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "draft_parse_failed"
    assert adapter.calls == 2
    assert result.facts  # verified facts still returned


def test_invalid_claim_is_repaired_then_accepted():
    bad = json.dumps({"selected_fact_ids": ["absolute_change:overall"],
                      "claims": [{"claim_id": "c1", "kind": "fact", "text": "x",
                                  "fact_ids": ["fact:missing"], "predicate": None}],
                      "limitations": [], "next_actions": []}, ensure_ascii=False)
    adapter = ScriptAdapter(bad, _VALID_DRAFT)
    result = _run(adapter)
    assert result.status == InsightStatus.COMPLETE
    assert adapter.calls == 2


def test_no_computable_facts_skips_model_call():
    request = _request()
    request.snapshot.series[0].data = [None, None]
    adapter = ScriptAdapter(_VALID_DRAFT)
    result = asyncio.run(generate_insight(request, adapter))
    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "no_verified_facts"
    assert adapter.calls == 0
    assert result.generation["call_count"] == 0


def test_model_call_failure_degrades_with_evidence():
    failure = ModelCallError(ModelResult("", "openai", "gpt-x", "llm_1", 12,
                                         status="failed", failure_kind="http_error"))
    adapter = ScriptAdapter(failure)
    result = _run(adapter)
    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "model_call_failed"
    assert adapter.calls == 1
    assert result.generation["failure_kind"] == "http_error"


def test_missing_adapter_never_calls_model():
    result = asyncio.run(generate_insight(_request(), None))
    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "model_unavailable"
    assert result.generation["call_count"] == 0


def test_prompt_binds_context_and_facts():
    request = _request()
    from insight.fact_builder import build_fact_bundle

    system, user = build_prompt(request.context, build_fact_bundle(request.context, request.snapshot))
    assert "【洞察-DRAFT】" in user
    assert '"facts"' in user
    assert "在职人数" in user
    assert system
