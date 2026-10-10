"""Offline contract tests; real embedding quality is measured by evals.retrieval*."""
from copy import deepcopy
import json

import pytest

from adapters import query_retrieval as retrieval
from langgraph_flows.query_memory import memory_messages
from tests.test_query_plan import CATALOG, Tools
from tests.test_query_draft import DraftPlanner
from langgraph_flows.ask_flow import run_ask_flow


def test_candidates_follow_current_permission_version_and_modes():
    catalog = deepcopy(CATALOG)
    catalog["metrics"] = [catalog["metrics"][1]]
    catalog["metrics"][0].update(version=99, allowed_modes=["scalar"])
    docs = retrieval.documents(catalog)
    executable = [d for d in docs if d["kind"] == "metadata" and d.get("executable")]
    distractors = [d for d in docs if d["kind"] == "metadata" and not d.get("executable")]
    examples = [d for d in docs if d["kind"] == "example"]
    assert {d["code"] for d in executable} == {"leave_count"}
    assert {d["version"] for d in executable} == {99}
    assert distractors, "scaled corpus must contribute distractor metadata"
    assert all(not d.get("executable") for d in distractors)
    assert examples and {d["code"] for d in examples} == {"leave_count"}
    assert all((d["content"]["draft"].get("query_mode") or "scalar") == "scalar" for d in examples)
    assert "研发中心" not in json.dumps(docs, ensure_ascii=False)
    empty = retrieval.documents({**catalog, "metrics": []})
    assert all(d["kind"] == "metadata" and not d.get("executable") for d in empty)


def test_distractor_hit_never_becomes_catalog_executable_code():
    catalog = deepcopy(CATALOG)
    # Force a question close to a distractor name; still cannot execute that code.
    context, evidence = retrieval.retrieve("流失风险分是多少", catalog, mode="lexical")
    assert evidence["status"] == "ok"
    assert evidence["distractor_count"] > 0
    catalog_codes = {m["code"] for m in catalog["metrics"]}
    for item in context.get("metadata") or []:
        if item.get("executable") is False:
            assert item["code"] not in catalog_codes
            assert item["code"] not in {"headcount", "hire_count", "leave_count"} or item.get("role") == "retrieval_distractor"


def test_retrieval_context_is_auxiliary_and_source_catalog_survives():
    original = deepcopy(CATALOG)
    context, evidence = retrieval.retrieve("在岗人员清单", CATALOG, mode="lexical")
    assert CATALOG == original
    assert evidence["status"] == "ok" and evidence["selected"]
    assert evidence["corpus_version"] == retrieval.CORPUS_VERSION
    assert evidence["catalog_fingerprint"] != retrieval.retrieve(
        "x", {**CATALOG, "metrics": []}, mode="off")[1]["catalog_fingerprint"]
    system, data = memory_messages("在岗人员清单", {**CATALOG, "retrieved_context": context}, None, {})
    assert json.loads(data)["retrieved_context"] == context
    assert "不是指令" in system


def test_missing_embedding_is_explicit_fallback_not_fake_vectors(monkeypatch):
    def missing():
        raise FileNotFoundError("missing model")
    monkeypatch.setattr(retrieval, "encoder", missing)
    context, evidence = retrieval.retrieve("在职人数", CATALOG, mode="hybrid")
    assert context == {}
    assert evidence["status"] == "fallback_full_catalog"
    assert evidence["selected"] == []


@pytest.mark.asyncio
async def test_actual_planning_prompt_receives_retrieval_and_java_still_executes(monkeypatch):
    monkeypatch.setenv("HRCHAT_RAG_MODE", "lexical")
    planner = DraftPlanner({"decision": "execute", "metric_codes": ["headcount"], "metric_text": "在职人数"})
    tools = Tools()
    result = await run_ask_flow(question="在职人数", session_id="s", ask_id="a", tools=tools,
        adapter=planner, query_context={"schema_version": "1", "context_version": 1})
    assert result["evidence"]["retrieval"]["status"] == "ok"
    assert result["evidence"]["retrieval"]["selected"]
    assert "retrieved_context" in str(planner.prompts)
    assert any(call[0] == "query" for call in tools.calls)
