"""Executable metadata should outrank near-tied distractors; Hit@K can ignore distractors."""
from adapters.query_retrieval import rank, documents
from evals.retrieval_scale import reciprocal_rank, score_executable_pool, synthetic_catalog


def test_executable_boost_breaks_near_tie_toward_executable():
    # Identical surface text → equal BM25; boost must prefer the executable code.
    docs = [
        {"id": "distractor:leave_voluntary_count", "kind": "metadata", "code": "leave_voluntary_count",
         "executable": False, "text": "总离职人数", "content": {}},
        {"id": "metric:leave_count", "kind": "metadata", "code": "leave_count",
         "executable": True, "text": "总离职人数", "content": {}},
    ]
    plain = rank("总离职人数", docs, "lexical", prefer_executable=False)
    boosted = rank("总离职人数", docs, "lexical", prefer_executable=True)
    assert abs(plain[0][1] - plain[1][1]) < 1e-9
    assert boosted[0][0]["code"] == "leave_count"
    assert boosted[0][0]["executable"] is True
    assert boosted[0][1] > boosted[1][1]


def test_score_executable_pool_filters_distractors_before_hitk():
    ranked_codes = ["leave_voluntary_count", "leave_count", "headcount"]
    executable = {"leave_count", "headcount", "hire_count"}
    pool = score_executable_pool(ranked_codes, gold="leave_count", executable_codes=executable)
    assert pool["codes"] == ["leave_count", "headcount"]
    assert pool["metric_hit_at_1"] is True
    assert pool["metric_hit_at_2"] is True
    assert pool["metric_rr"] == 1.0
    assert reciprocal_rank(["leave_voluntary_count", "leave_count"], "leave_count") == 0.5
