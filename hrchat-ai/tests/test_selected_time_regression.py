from datetime import date
from copy import deepcopy

import pytest

from langgraph_flows.query_memory import ContextQueryDraft, CatalogOrganization, compile_contextual
from langgraph_flows.query_plan import PlanRejected
from langgraph_flows.time_intent import resolve_expression
from tests.test_query_plan import CATALOG


WINDOW = {"preset": "CUSTOM", "start": "2026-08-01", "end": "2026-09-01"}
MEMORY = {"schema_version": "1", "context_version": 1}


@pytest.mark.parametrize("phrase", ["八月", "八月份", "2026年八月份", "8月份"])
def test_calendar_month_suffix_has_same_window(phrase):
    assert resolve_expression(phrase, date(2026, 9, 28)) == (date(2026, 8, 1), date(2026, 9, 1))


@pytest.mark.parametrize("phrase", ["在我选定的月份里", "选好的日期", "所选期间", "已选日期"])
@pytest.mark.parametrize("model_extracted_reference", [True, False])
def test_selected_window_is_used_without_model_inventing_dates(phrase, model_extracted_reference):
    question = phrase + "研发中心在职人数"
    draft = ContextQueryDraft(decision="execute", metric_codes=["headcount"], metric_text="在职人数",
                              organization=CatalogOrganization(kind="catalog_id", org_id="2", source_text="研发中心"),
                              time_expression=phrase if model_extracted_reference else None)
    plan, compilation, _ = compile_contextual(draft, question, CATALOG, {"time_range": WINDOW}, MEMORY, "test")
    assert plan.time_range.start == "2026-08-01"
    assert plan.time_range.end == "2026-09-01"
    assert compilation["slot_sources"]["time_range"] == "selection"
    with pytest.raises(PlanRejected) as rejected:
        compile_contextual(draft, question, CATALOG, {}, MEMORY, "test")
    assert rejected.value.reason == "missing_slots"


def test_reference_does_not_hide_explicit_conflicting_month():
    question = "用已选日期查上月在职人数"
    draft = ContextQueryDraft(decision="execute", metric_codes=["headcount"], metric_text="在职人数", time_expression="上月")
    with pytest.raises(PlanRejected) as rejected:
        compile_contextual(draft, question, CATALOG, {"time_range": {**WINDOW, "start": "2026-07-01", "end": "2026-08-01"}}, MEMORY, "test")
    assert rejected.value.reason == "context_conflict"


def test_unsupported_capability_is_not_overridden_by_unknown_time_phrase():
    draft = ContextQueryDraft(decision="unsupported", unsupported_reason="unsupported_capability", time_expression="同期")
    plan, _, _ = compile_contextual(draft, "同期主动辞职占比", CATALOG, {}, MEMORY, "test")
    assert plan.decision == "unsupported" and plan.time_range is None


def test_generic_count_alias_does_not_override_semantic_metric_but_explicit_alias_does():
    catalog = deepcopy(CATALOG)
    catalog["metrics"][0]["aliases"] = ["人数", "员工人数", "在职"]
    draft = ContextQueryDraft(decision="execute", metric_codes=["leave_count"], metric_text="离开公司的同事人数",
                              organization=CatalogOrganization(kind="catalog_id", org_id="2", source_text="研发中心"))
    plan, _, _ = compile_contextual(draft, "研发中心离开公司的同事人数", catalog, {"time_range": WINDOW}, MEMORY, "test")
    assert plan.metric_codes == ["leave_count"]
    bad = draft.model_copy(update={"metric_text": "在职人数"})
    with pytest.raises(PlanRejected) as rejected:
        compile_contextual(bad, "研发中心在职人数", catalog, {"time_range": WINDOW}, MEMORY, "test")
    assert rejected.value.reason == "metric_condition_omitted"
