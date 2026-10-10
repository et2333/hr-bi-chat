"""Adversarial scorer checks: consistent-looking wrong charts must still fail."""
from copy import deepcopy
import json

import pytest

from evals.dataset import MODE_DATASET, load_dataset, sha_text, DATASET
from evals.mode_reference import expected_rows, matching_records
from evals.mode_scoring import score_mode, score_saved_pages
from evals.run import prior_runs
from evals.scoring import score


def trend_example():
    expected = {"plan": {"query_mode": "trend", "metric_code": "headcount"},
                "columns": [{"key": "period", "masked": False}, {"key": "headcount", "masked": False}],
                "rows": [{"period": "2026-07", "headcount": 15}, {"period": "2026-08", "headcount": 17}]}
    payload = {"table": {"columns": deepcopy(expected["columns"]), "rows": deepcopy(expected["rows"]),
                         "total": 2, "page": 1, "size": 2},
               "conclusion": {"type": "TEXT", "value": "在职人数近期末为 17人。", "unit": None},
               "chart": {"type": "LINE", "config": {"xAxis": {"data": ["2026-07", "2026-08"]},
                         "series": [{"type": "line", "data": [15, 17]}]}}}
    return expected, payload


def test_independent_snapshot_and_direct_membership_anchors():
    assert expected_rows("headcount", "trend", [2, 3, 4], "2026-07-01", "2026-09-01") == [
        {"period": "2026-07", "headcount": 15}, {"period": "2026-08", "headcount": 17}]
    assert expected_rows("headcount", "org", [2, 3, 4], "2026-08-01", "2026-09-01") == [
        {"org_name": "研发一部", "headcount": 10}, {"org_name": "研发二部", "headcount": 7}]
    assert expected_rows("headcount", "org", [2], "2026-08-01", "2026-09-01") == []
    before = {r["emp_no"] for r in matching_records("headcount", [4], "2026-08-01", "2026-08-20")}
    after = {r["emp_no"] for r in matching_records("headcount", [4], "2026-08-01", "2026-08-21")}
    assert before - after == {"E2007"}
    assert (len(before), len(after)) == (8, 7)


def test_event_empty_months_are_sparse_and_day_boundary_is_exclusive():
    assert expected_rows("hire_count", "trend", [3], "2026-09-01", "2026-09-15") == []
    assert expected_rows("hire_count", "trend", [4], "2026-09-01", "2026-09-16") == [
        {"period": "2026-09", "hire_count": 1}]


def test_positive_trend_and_org_order_is_not_assumed():
    expected, payload = trend_example()
    assert score_mode(expected, payload) == []
    expected["plan"]["query_mode"] = "org"
    expected["columns"][0]["key"] = "org_name"
    expected["rows"] = [{"org_name": "B", "headcount": 7}, {"org_name": "A", "headcount": 10}]
    expected["partition_total"] = 17
    payload["table"].update(columns=deepcopy(expected["columns"]), rows=list(reversed(expected["rows"])))
    payload["chart"] = {"type": "BAR", "config": {"xAxis": {"data": ["A", "B"]},
                         "series": [{"type": "bar", "data": [10, 7]}]}}
    payload["conclusion"]["value"] = "A 10人最高，B 7人最低；合计 17人。"
    assert score_mode(expected, payload) == []


@pytest.mark.parametrize("bad", [None, True, "17", float("nan"), float("inf")])
def test_wrong_numeric_type_cannot_be_a_count(bad):
    expected, payload = trend_example()
    payload["table"]["rows"][1]["headcount"] = bad
    assert "result_numeric" in score_mode(expected, payload)


def test_wrong_values_cannot_pass_even_if_chart_and_table_agree():
    expected, payload = trend_example()
    payload["table"]["rows"][1]["headcount"] = 32
    payload["chart"]["config"]["series"][0]["data"][1] = 32
    assert "result_rows" in score_mode(expected, payload)


def test_equal_finite_float_counts_are_valid_but_mask_flags_are_booleans():
    expected, payload = trend_example()
    payload["table"]["rows"][1]["headcount"] = 17.0
    assert score_mode(expected, payload) == []
    payload["table"]["columns"][0]["masked"] = 0
    assert "result_columns" in score_mode(expected, payload)


@pytest.mark.parametrize("mutate,error", [
    (lambda p: p["table"]["rows"].reverse(), "period_order"),
    (lambda p: p["table"]["rows"].append(deepcopy(p["table"]["rows"][0])), "result_rows"),
    (lambda p: p["chart"]["config"]["series"][0].update(data=[15, 0]), "chart_table_mismatch"),
    (lambda p: p["chart"]["config"]["xAxis"].update(data=["Aug", "Jul"]), "chart_table_mismatch"),
    (lambda p: p["table"]["columns"][0].update(masked=True), "result_columns"),
    (lambda p: p["table"]["rows"][0].update(emp_name="leak"), "result_rows"),
    (lambda p: p["table"].update(total=999), "saved_result_pagination"),
    (lambda p: p["conclusion"].update(value="在职人数合计 32人。"), "snapshot_sum_claim"),
])
def test_presentation_mutations(mutate, error):
    expected, payload = trend_example()
    mutate(payload)
    assert error in score_mode(expected, payload)


def test_masked_detail_duplicates_must_not_be_deduplicated():
    _, cases = load_dataset(MODE_DATASET)
    expected = deepcopy(cases[13]["turns"][0]["expected"])
    assert len(expected["rows"]) == 2
    expected["rows"] = [expected["rows"][0]] * 2
    payload = {"table": {"columns": expected["columns"], "rows": expected["rows"][:1],
                         "total": 1, "page": 1, "size": 1},
               "conclusion": {"type": "TEXT", "unit": None, "value": "明细本次返回 1 行（最多展示 50 行）"}}
    assert "result_rows" in score_mode(expected, payload)


def test_missing_execution_cannot_fall_back_to_matching_sql():
    _, cases = load_dataset(MODE_DATASET)
    expected = cases[0]["turns"][0]["expected"]
    _, payload = trend_example()
    payload["caliber"] = {"metricCode": "headcount", "timeRange": "2026-07-01/2026-08-31"}
    actual = {"status": "COMPLETED", "answer": payload, "sql": "SELECT 1 WHERE org_key IN (2,3,4)"}
    assert "execution_evidence_missing" in score(expected, actual)["errors"]


@pytest.mark.parametrize("wrong", [{"grain": "NONE"}, {"time_type": "period"}, {"timezone": "UTC"}])
def test_same_numbers_with_wrong_time_semantics_are_not_success(wrong):
    _, cases = load_dataset(MODE_DATASET)
    expected = cases[0]["turns"][0]["expected"]
    _, payload = trend_example()
    payload["caliber"] = {"metricCode": "headcount", "timeRange": "2026-07-01/2026-08-31"}
    plan = {"metric_codes": ["headcount"], "query_mode": "trend",
            "org_scope": {"org_id": "2", "include_children": True}, "time_range": {
                "start": "2026-07-01", "end": "2026-09-01", "grain": "MONTH", "time_type": "as_of", "timezone": "Asia/Shanghai"}}
    evidence = {"query_plan": plan, "execution": {"query_plan": plan, "metric_version": 2, "effective_org_ids": [2, 3, 4]}}
    actual = {"status": "COMPLETED", "answer": payload, "evidence": evidence}
    assert score(expected, actual)["passed"]
    plan["time_range"].update(wrong)
    assert "executed_time_semantics" in score(expected, actual)["errors"]


def test_dataset_is_separate_and_original_sixty_cases_unchanged():
    old, old_cases = load_dataset()
    new, cases = load_dataset(MODE_DATASET)
    assert old["case_count"] == len(old_cases) == 60
    assert sha_text(DATASET / "cases.json") == "70e992921582b1f18438577ffdba9a13f7eafa9397e6b15807763cce01fc8913"
    assert new["case_count"] == len(cases) == 18
    assert {c["scene"] for c in cases} == {"scalar", "trend", "org", "detail", "permission"}
    assert {c["split"] for c in cases} == {"dev"}


def test_prior_run_discovery_ignores_unrelated_s6_schema(tmp_path):
    for name, report in {
        "s6": {"experiment": "s6"},
        "string-dataset": {"dataset": "retrieval-scale-v1"},
        "j1": {"dataset": {"cases_sha256": "new"}, "results": [{"case_id": "m1", "turns": [1]}]},
    }.items():
        folder = tmp_path / name
        folder.mkdir()
        (folder / "report.json").write_text(json.dumps(report), encoding="utf-8")
    assert prior_runs(tmp_path, {"cases_sha256": "new"}, [{"case_id": "m1"}]) == (["j1"], ["j1"])


def test_pagination_is_of_saved_rows_and_page_two_cannot_repeat_page_one():
    rows = [{"emp_no": f"E***{i}"} for i in range(5)]
    columns = [{"key": "emp_no", "masked": True}]
    actual = {"answer": {"table": {"rows": rows, "columns": columns}}, "saved_pages": []}
    for page in (1, 2, 3):
        actual["saved_pages"].append({"page": page, "size": 3, "http_status": 200, "data": {
            "page": page, "size": 3, "total": 5, "rows": rows[(page-1)*3:page*3], "columns": columns}})
    assert score_saved_pages(actual) == []
    actual["saved_pages"][1]["data"]["rows"] = rows[:3]
    assert score_saved_pages(actual) == ["saved_pages_mismatch"]
    actual.pop("saved_pages")
    assert score_saved_pages(actual) == ["saved_pages_missing"]
