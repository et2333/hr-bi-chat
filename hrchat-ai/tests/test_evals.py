import copy
import json

import pytest

from evals.api import JavaApi
from evals.compare import compare
from evals.dataset import load_dataset, sha
from evals.reference import count, seed_rows
from evals.run import execute, summarize
from evals.scoring import score


def example():
    _, cases = load_dataset()
    expected = cases[0]["turns"][0]["expected"]
    actual = {"status": "COMPLETED", "sql": "SELECT COUNT(*) WHERE org_key IN (2, 3, 4)",
              "answer": {"caliber": {"metricCode": "headcount", "timeRange": "2026-08-01/2026-08-31"},
                         "conclusion": {"unit": "人", "value": expected["value"]},
                         "table": {"rows": [{"headcount": expected["value"]}]}}}
    return copy.deepcopy(expected), actual


def test_reference_manual_anchors():
    assert len(seed_rows("dim_employee")) == 33
    assert len(seed_rows("fact_emp_change")) == 38
    assert count("headcount", [2, 3, 4], "2026-08-01", "2026-08-20") == 17
    assert count("headcount", [2, 3, 4], "2026-08-01", "2026-08-21") == 16
    assert count("hire_count", [2, 3, 4], "2026-09-01", "2026-09-15") == 0
    assert count("hire_count", [2, 3, 4], "2026-09-01", "2026-09-16") == 1
    assert count("leave_count", [2, 3, 4], "2026-09-01", "2026-09-29") == 2


def test_correct_and_equal_value_wrong_scope():
    expected, actual = example()
    assert score(expected, actual)["passed"]
    actual["sql"] = "SELECT COUNT(*) WHERE org_key IN (5)"
    assert "org_scope_evidence" in score(expected, actual)["errors"]


@pytest.mark.parametrize("mutation,error", [
    (lambda a: a["answer"]["caliber"].update(metricCode="leave_count"), "metric"),
    (lambda a: a["answer"]["caliber"].update(timeRange="2026-09-01/2026-09-28"), "time_range"),
    (lambda a: a["answer"]["table"]["rows"][0].update(headcount=None), "value"),
    (lambda a: a["answer"]["table"]["rows"][0].update(period="2026-08"), "query_mode"),
    (lambda a: a.update(sql=None), "org_scope_evidence"),
    (lambda a: a["answer"]["table"]["rows"][0].update(headcount=True), "value"),
    (lambda a: a["answer"]["conclusion"].update(value=999), "display_value"),
])
def test_scorer_rejects_bad_evidence(mutation, error):
    expected, actual = example()
    mutation(actual)
    assert error in score(expected, actual)["errors"]


def test_denied_with_data_is_not_success():
    expected = {"statuses": ["DENIED"], "no_data": True, "error_code": "HRC-2003"}
    actual = {"status": "DENIED", "error_codes": ["HRC-2003"], "answer": {"table": {"rows": [{"x": 1}]}}}
    assert score(expected, actual)["errors"] == ["unexpected_data"]


def test_denied_number_card_without_table_is_not_success():
    actual = {"status": "DENIED", "answer": {"conclusion": {"type": "NUMBER_CARD", "value": 0}}}
    assert score({"statuses": ["DENIED"], "no_data": True}, actual)["errors"] == ["unexpected_data"]


def test_split_leakage_rejected(tmp_path):
    manifest, cases = load_dataset()
    cases[1]["split"] = "frozen"
    (tmp_path / "cases.json").write_text(json.dumps(cases), encoding="utf-8")
    manifest["cases_sha256"] = sha(tmp_path / "cases.json")
    (tmp_path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="leakage"):
        load_dataset(tmp_path)


@pytest.mark.parametrize("failure", [TimeoutError, ConnectionError, ValueError])
def test_runner_faults_keep_full_denominator(failure):
    _, cases = load_dataset()
    class BrokenApi:
        def session(self, identity):
            raise failure("not serialized")
    results = execute(cases[:2], BrokenApi())
    summary = summarize(cases[:2], results)
    assert summary["status"] == "FAILED"
    assert summary["total"] == 2
    assert summary["success_rate"] is None
    assert all(r["execution_error"] == failure.__name__ for r in results)


def test_multiturn_partial_is_not_a_pass():
    _, cases = load_dataset()
    case = next(c for c in cases if len(c["turns"]) > 1)
    row = {"scene": case["scene"], "turns": [{"passed": True, "errors": []}], "passed": False}
    summary = summarize([case], [row])
    assert summary["status"] == "FAILED"
    assert summary["planned_turns"] == 2
    assert summary["success_rate"] is None


def test_stream_error_and_answer_cannot_be_success():
    class FakeApi(JavaApi):
        def request(self, path, identity, body=None):
            return 200, '\n\n'.join('data: ' + json.dumps(e) for e in [
                {"event": "ERROR", "payload": {"code": "HRC-2003"}},
                {"event": "ANSWER_DONE", "payload": {"table": {"rows": [{"x": 1}]}}}])
    actual = FakeApi("http://localhost").ask(1, "hr01", {"question": "test"})
    assert actual["status"] == "DENIED"
    assert not score({"statuses": ["DENIED"], "no_data": True}, actual)["passed"]


def test_explicit_unsupported_code_preserves_reason_without_inventing_success():
    class FakeApi(JavaApi):
        def request(self, path, identity, body=None):
            return 200, 'data: ' + json.dumps({"event": "ERROR", "payload": {
                "code": "HRA-4006", "message": "组织无法识别", "recoverable": False}})
    actual = FakeApi("http://localhost").ask(1, "hr01", {"question": "市场部在职人数"})
    assert actual["status"] == "UNSUPPORTED"
    assert actual["error_codes"] == ["HRA-4006"]
    assert actual["answer"] is None
    assert score({"statuses": ["UNSUPPORTED"], "no_data": True}, actual)["passed"]


def test_interrupt_preserves_unexecuted_denominator():
    _, cases = load_dataset()
    saved = []
    class InterruptedApi:
        def session(self, identity):
            raise KeyboardInterrupt()
    result = execute(cases[:3], InterruptedApi(), lambda rows: saved.append(len(rows)))
    assert saved == [1]
    assert summarize(cases[:3], result)["total"] == 3
    assert summarize(cases[:3], result)["success_rate"] is None


def test_duplicate_answer_is_invalid():
    expected, actual = example()
    actual["answer_event_count"] = 2
    assert "conflicting_terminal_events" in score(expected, actual)["errors"]


def test_nonfinite_number_is_rejected():
    expected, actual = example()
    actual["answer"]["table"]["rows"][0]["headcount"] = float("nan")
    assert "value" in score(expected, actual)["errors"]


def test_replay_checks_values_even_when_pass_flags_match():
    expected, actual = example()
    actual["error_codes"] = []
    original = {"dataset": {"cases_sha256": "same"}, "summary": {"status": "COMPLETED"},
                "results": [{"case_id": "a", "passed": True, "turns": [
                    {"expected": expected, "actual": actual, "passed": True, "errors": []}]}]}
    replay = copy.deepcopy(original)
    assert compare([original], replay)["matched"]
    replay["results"][0]["turns"][0]["actual"]["answer"]["table"]["rows"][0]["headcount"] = 999
    assert compare([original], replay)["changed"] == ["a"]


def test_replay_cannot_hide_missing_cases():
    report = {"dataset": {"cases_sha256": "same"}, "summary": {"status": "COMPLETED"},
              "results": [{"case_id": "a", "passed": False, "turns": []}]}
    replay = {**report, "results": []}
    assert compare([report], replay)["missing"] == ["a"]
