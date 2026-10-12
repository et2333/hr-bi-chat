"""Lightweight insight generation: one call, ≤1 repair, no model call without data."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelCallError, ModelResult
from insight.fact_builder import build_fact_bundle
from insight.generation import build_prompt, generate_insight
from insight.schema import InsightDraft, InsightRequest, InsightStatus

FIXTURES = Path(__file__).parent / "fixtures" / "insight"

_VALID_DRAFT = json.dumps({
    "selected_fact_ids": ["absolute_change:overall"],
    "claims": [{
        "claim_id": "c1", "kind": "fact",
        "segments": [
            {"type": "text", "text": "在职人数出现明显变化，绝对变动"},
            {"type": "fact", "fact_id": "absolute_change:overall", "field": "value"},
            {"type": "fact", "fact_id": "absolute_change:overall", "field": "unit"},
        ],
        "fact_ids": ["absolute_change:overall"], "predicate": "absolute_increase",
        "unit": "人", "scope_ref": "org:10",
    }],
    "narrative": {"summary_claim_id": "c1", "finding_claim_ids": ["c1"]},
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


def test_fabricated_limitation_is_rejected_and_degrades():
    """B3: the top-level limitations list is model-generated visible text, so a fabricated
    sentence appended there must not survive as a successful insight."""
    draft = json.loads(_VALID_DRAFT)
    draft["limitations"] = ["999999 people because management failed"]
    adapter = ScriptAdapter(json.dumps(draft, ensure_ascii=False))
    result = _run(adapter)

    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "draft_validation_failed"
    assert "999999" not in json.dumps(result.model_dump(mode="json"), ensure_ascii=False)
    # 降级后只保留程序按已核验事实生成的局限，模型那条虚构文本不得进入任何可见字段。
    assert all("999999" not in text and "因为" not in text for text in result.limitations)


def test_controlled_fact_type_limitation_is_kept():
    """程序生成的 ``fact_type:reason`` 局限不受自由文本约束，仍应原样保留。"""
    draft = json.loads(_VALID_DRAFT)
    draft["limitations"] = ["continuous_trend:non_temporal_axis"]
    result = _run(ScriptAdapter(json.dumps(draft, ensure_ascii=False)))

    assert result.status == InsightStatus.COMPLETE
    assert result.limitations == ["continuous_trend:non_temporal_axis"]


def test_prompt_payload_exposes_slot_metadata_for_facts():
    """事实 JSON 必须暴露 period_ref/category/tied_categories，否则模型无法判断哪些槽可绑定。

    真实模型首稿失败的主因正是：提示允许 period_label/category 槽，但载荷不带这些字段，
    模型只能猜，猜错即整条 claim 被拒。
    """
    request = _request()
    bundle = build_fact_bundle(request.context, request.snapshot)
    _, user_prompt = build_prompt(request.context, bundle)

    line = next(line for line in user_prompt.splitlines() if line.startswith("事实(JSON)："))
    payload = json.loads(line.split("事实(JSON)：", 1)[1])
    fields = set().union(*(set(fact) for fact in payload["facts"]))
    assert {"period_ref", "category", "tied_categories"} <= fields


def test_prompt_lists_incomputable_fact_types_for_disclosure():
    """用户提示须直接给出必须披露的不可计算事实类型，模型才可能按受控格式逐条引用。"""
    request = _request()
    bundle = build_fact_bundle(request.context, request.snapshot)
    expected = sorted({f.fact_type.value for f in bundle.facts if not f.computable})
    assert expected, "该 fixture 应含不可计算事实"

    _, user_prompt = build_prompt(request.context, bundle)
    line = next(line for line in user_prompt.splitlines() if line.startswith("不可计算的事实类型"))
    assert all(fact_type in line for fact_type in expected)


def test_prompt_forbids_empty_text_segments_and_schema_rejects_them():
    """IS-02 失败的真因：模型输出空 text 片段（非 JSON 语法错）→ Schema 拒绝。

    处理方式为提示明确「不需要文字就省略，不要空串占位」，不放松 Schema。
    """
    request = _request()
    bundle = build_fact_bundle(request.context, request.snapshot)
    system_prompt, _ = build_prompt(request.context, bundle)
    assert "空字符串" in system_prompt

    payload = InsightDraft.model_validate(json.loads(_VALID_DRAFT)).model_dump(mode="json")
    payload["claims"][0]["segments"] = [{"type": "text", "text": ""}]
    with pytest.raises(ValueError):
        InsightDraft.model_validate(payload)


def test_rejected_draft_records_validation_issues_for_diagnosis():
    """被拒草稿要留下结构化原因，使归档报告无需付费重跑即可定因，且不落模型原文。"""
    draft = json.loads(_VALID_DRAFT)
    draft["limitations"] = ["999999 people because management failed"]
    result = _run(ScriptAdapter(json.dumps(draft, ensure_ascii=False)))

    log = result.generation["validation_issues"]
    assert [entry["stage"] for entry in log] == ["initial", "repair"]
    assert all(entry["outcome"] == "validation_failed" for entry in log)
    assert any(issue["kind"] for entry in log for issue in entry["issues"])
    # 只保留我们自己的校验消息与标识，模型原文不进 generation（该字段会经任务 API 暴露）。
    assert "999999" not in json.dumps(log, ensure_ascii=False)


def test_repaired_draft_keeps_first_attempt_issues():
    bad = json.dumps({"selected_fact_ids": ["absolute_change:overall"],
                      "claims": [{"claim_id": "c1", "kind": "fact", "text": "x",
                                  "fact_ids": ["fact:missing"], "predicate": None}],
                      "limitations": [], "next_actions": []}, ensure_ascii=False)
    result = _run(ScriptAdapter(bad, _VALID_DRAFT))

    assert result.status == InsightStatus.COMPLETE
    log = result.generation["validation_issues"]
    # 修复成功也保留首稿被拒原因，用于判断提示与校验是否系统性不匹配。
    assert [entry["stage"] for entry in log] == ["initial"]


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


class UsageAdapter(ModelAdapter):
    """Returns explicit ModelResults (with or without actual usage) one per call."""

    name = "usage"

    def __init__(self, *results):
        self.results = list(results)
        self.calls = 0

    async def generate(self, prompt: str) -> str:  # pragma: no cover - not used
        raise AssertionError("complete_analysis is the entry point under test")

    async def complete_analysis(self, system_prompt: str, user_prompt: str) -> ModelResult:
        item = self.results[min(self.calls, len(self.results) - 1)]
        self.calls += 1
        return item


def _usage_result(text: str, total_tokens: int | None) -> ModelResult:
    if total_tokens is None:
        return ModelResult(text, "openai", "gpt-x", "llm_u", 10, status="completed")
    return ModelResult(text, "openai", "gpt-x", "llm_u", 10, status="completed",
                       usage_source="actual",
                       usage={"prompt_tokens": total_tokens - 20, "completion_tokens": 20,
                              "total_tokens": total_tokens})


def test_two_calls_usage_is_summed():
    adapter = UsageAdapter(_usage_result("not json", 120), _usage_result(_VALID_DRAFT, 120))
    result = _run(adapter)
    generation = result.generation
    assert generation["call_count"] == 2
    assert generation["usage_complete"] is True
    assert generation["usage_total"]["total_tokens"] == 240
    assert generation["usage_known_subtotal"]["total_tokens"] == 240


def test_missing_usage_marks_incomplete_without_fake_total():
    adapter = UsageAdapter(_usage_result("not json", 120), _usage_result(_VALID_DRAFT, None))
    result = _run(adapter)
    generation = result.generation
    assert generation["call_count"] == 2
    assert generation["usage_complete"] is False
    assert generation["usage_total"] is None
    assert generation["usage_known_subtotal"]["total_tokens"] == 120


def test_prompt_binds_context_and_facts():
    request = _request()
    from insight.fact_builder import build_fact_bundle

    system, user = build_prompt(request.context, build_fact_bundle(request.context, request.snapshot))
    assert "【洞察-DRAFT】" in user
    assert '"facts"' in user
    assert "在职人数" in user
    assert "segments" in system


# --- A7/D2: authorized business context (analysis goal + re-authorized source question) ---

def test_prompt_includes_authorized_goal_and_source_question():
    request = _request().model_copy(deep=True)
    request.context.schema_version = "2"
    request.context.analysis_goal = "针对研发中心在职人数，分析其逐期变化与拐点并给出可核查的重点。"
    request.context.goal_source = "ask"
    request.context.question_summary = "研发中心近三月在职人数为何上升"
    from insight.fact_builder import build_fact_bundle

    _, user = build_prompt(request.context, build_fact_bundle(request.context, request.snapshot))
    assert "分析目标：针对研发中心在职人数" in user
    assert "来源问题（已授权，仅作目标参考）：研发中心近三月在职人数为何上升" in user


def test_report_domain_prompt_omits_source_question():
    request = _request().model_copy(deep=True)
    request.context.schema_version = "2"
    request.context.analysis_goal = "针对全部组织在2026-02~2026-03的在职人数，分析其逐期变化与拐点并给出可核查的重点。"
    request.context.goal_source = "report"
    request.context.question_summary = None
    from insight.fact_builder import build_fact_bundle

    _, user = build_prompt(request.context, build_fact_bundle(request.context, request.snapshot))
    assert "分析目标：针对全部组织" in user
    # 仅报表上下文可见域绝不携带受限原问题。
    assert "来源问题" not in user


# --- C16: the model interpretation (not Fact.label) must reach the visible result ---

def test_model_narrative_summary_is_rendered_not_fact_label():
    adapter = ScriptAdapter(_VALID_DRAFT)
    result = _run(adapter)
    assert result.status == InsightStatus.COMPLETE
    # Rendered from validated segments with the fact value slot, not a Fact.label concatenation.
    assert result.summary.startswith("在职人数出现明显变化")
    assert result.points and result.points[0].label == result.summary
    assert result.claims[0].text == result.summary
    assert result.narrative is not None and result.narrative.summary_claim_id == "c1"
    assert result.schema_version == "2"


# --- C17: fabricated numbers, causality and illegal actions must not become visible ---

_FABRICATED = json.dumps({
    "selected_fact_ids": ["absolute_change:overall"],
    "claims": [{
        "claim_id": "c1", "kind": "fact",
        "segments": [{"type": "text", "text": "在职人数变为 999999 人，因为组织扩张"}],
        "fact_ids": ["absolute_change:overall"], "predicate": "absolute_increase",
        "unit": "人", "scope_ref": "org:10",
    }],
    "narrative": {"summary_claim_id": "c1", "finding_claim_ids": ["c1"]},
    "limitations": [], "next_actions": [],
}, ensure_ascii=False)


def test_fabricated_numbers_are_rejected_then_degrade():
    adapter = ScriptAdapter(_FABRICATED, _FABRICATED)
    result = _run(adapter)
    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "draft_validation_failed"
    assert result.claims == []
    assert adapter.calls == 2  # one generate + one repair, no more
    assert result.summary and "999999" not in result.summary


def test_narrative_referencing_unknown_claim_is_repaired():
    bad = json.dumps({
        "selected_fact_ids": ["absolute_change:overall"],
        "claims": [{"claim_id": "c1", "kind": "fact",
                    "segments": [{"type": "fact", "fact_id": "absolute_change:overall", "field": "value"}],
                    "fact_ids": ["absolute_change:overall"], "predicate": "absolute_increase",
                    "unit": "人", "scope_ref": "org:10"}],
        "narrative": {"summary_claim_id": "ghost", "finding_claim_ids": []},
        "limitations": [], "next_actions": [],
    }, ensure_ascii=False)
    adapter = ScriptAdapter(bad, _VALID_DRAFT)
    result = _run(adapter)
    assert result.status == InsightStatus.COMPLETE
    assert adapter.calls == 2


# --- A3: no usable narrative must degrade; every visible claim text passes segment checks ---

def test_empty_draft_degrades_instead_of_reporting_success():
    # 合法但全空的草稿没有任何可用正文：不得再标 COMPLETE，须明确降级。
    adapter = ScriptAdapter("{}")
    result = _run(adapter)
    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "no_usable_narrative"
    assert adapter.calls == 1  # 结构合法，不触发修复调用
    assert result.facts  # 已核验事实仍返回


_LIMITATION_AS_SUMMARY = json.dumps({
    "selected_fact_ids": ["absolute_change:overall"],
    "claims": [
        {"claim_id": "c1", "kind": "fact",
         "segments": [{"type": "fact", "fact_id": "absolute_change:overall", "field": "value"}],
         "fact_ids": ["absolute_change:overall"], "predicate": "absolute_increase",
         "unit": "人", "scope_ref": "org:10"},
        {"claim_id": "c2", "kind": "limitation", "text": "结构性调整期的稳定性需要人工复核", "fact_ids": []},
    ],
    "narrative": {"summary_claim_id": "c2", "finding_claim_ids": ["c1"]},
    "limitations": [], "next_actions": [],
}, ensure_ascii=False)


def test_limitation_claim_cannot_become_the_visible_summary():
    # limitation/next_check 不是带证据的事实类断言，不能作为可见主结论。
    adapter = ScriptAdapter(_LIMITATION_AS_SUMMARY, _LIMITATION_AS_SUMMARY)
    result = _run(adapter)
    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "draft_validation_failed"
    assert result.claims == []


_FABRICATED_LIMITATION = json.dumps({
    "selected_fact_ids": ["absolute_change:overall"],
    "claims": [{"claim_id": "c1", "kind": "limitation",
                "text": "999999 人因为管理失效", "fact_ids": []}],
    "narrative": {"summary_claim_id": "c1", "finding_claim_ids": []},
    "limitations": [], "next_actions": [],
}, ensure_ascii=False)


def test_fabricated_limitation_text_is_not_bypassed_by_missing_fact_ids():
    # 无 fact_ids 的自由文本过去会跳过槽位校验；现在必须同样被拒。
    adapter = ScriptAdapter(_FABRICATED_LIMITATION, _FABRICATED_LIMITATION)
    result = _run(adapter)
    assert result.status == InsightStatus.DEGRADED
    assert result.degradation_reason == "draft_validation_failed"
    assert result.claims == []
    assert "999999" not in result.summary

