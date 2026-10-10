"""R5 metric semantics and split/ablation integrity, no model download or API."""
from copy import deepcopy

import pytest

from adapters.query_retrieval import rank
from evals import retrieval_quality as quality


def test_mrr_full_cutoff_and_multirelevant_recall_are_distinct():
    scores = quality.ranking_metrics(["wrong", "wrong", "also_wrong", "gold", "gold2"], ["gold", "gold2"])
    assert scores["mrr"] == pytest.approx(1 / 3)
    assert scores["mrr_at_2"] == 0
    assert scores["mrr_at_5"] == pytest.approx(1 / 3)
    assert scores["recall_at_5"] == 1
    assert scores["top1_error"] == 1
    assert quality.ranking_metrics(["gold"], ["gold", "gold2"])["recall_at_2"] == .5
    assert quality.ranking_metrics([], ["gold"])["top1_error"] == 1
    assert all(v is None for v in quality.ranking_metrics(["x"], []).values())


def test_authored_dataset_has_disjoint_families_and_no_exact_training_overlap():
    payload, docs, audit = quality.load_suite()
    cases = payload["cases"]
    assert len(cases) == 60 and audit["family_count"] == 20
    assert not audit["errors"]
    for split in ("dev", "frozen"):
        subset = [c for c in cases if c["split"] == split]
        assert len(subset) == 30
        assert sum(c["expected"]["decision"] == "unsupported" for c in subset) == 6
        assert all(c["human_review"] == "pending" for c in subset)
    corrupted = deepcopy(cases)
    corrupted[0]["split"] = "frozen"
    assert any("crosses splits" in e for e in quality.audit_cases(corrupted, docs)["errors"])
    leaked = deepcopy(cases)
    leaked[0]["question"] = next(d["content"]["question"] for d in docs if d["kind"] == "example")
    assert any("overlap" in e for e in quality.audit_cases(leaked, docs)["errors"])


def test_correct_unsupported_hit_is_not_an_error_and_boost_risk_is_not_execution():
    payload, docs, _ = quality.load_suite()
    case = next(c for c in payload["cases"] if c["expected"]["decision"] == "unsupported")
    gold = next(d for d in docs if d["code"] in case["relevance"]["metric_codes"])
    executable = next(d for d in docs if d["code"] == "hire_count" and d["kind"] == "metadata")
    good = quality.score_case(case, [(gold, 1)], [], [], 1)["metrics"]
    bad = quality.score_case(case, [(executable, 2), (gold, 1)], [], [], 1)["metrics"]
    assert good["top1_nonexecutable"] == 1
    assert good["metric_top1_error"] == 0
    assert good["unsupported_executable_top1"] == 0
    assert bad["metric_top1_error"] == bad["unsupported_executable_top1"] == 1


def test_missing_lexical_hits_do_not_remove_gold_example_denominator():
    payload, docs, _ = quality.load_suite()
    case = next(c for c in payload["cases"] if c["category"] == "detail")
    examples = [d for d in docs if d["kind"] == "example"]
    result = quality.score_case(case, [], [], examples, 1)
    assert result["metrics"]["example_hit_at_2"] == 0
    assert result["metrics"]["example_mrr_at_2"] == 0


def test_followup_does_not_score_inherited_metric_as_a_retrieval_hit():
    payload, docs, _ = quality.load_suite()
    case = next(c for c in payload["cases"] if c["category"] == "followup")
    doc = next(d for d in docs if d["code"] == "leave_count")
    row = quality.score_case(case, [(doc, 1)], [], [], 1)
    assert row["metrics"]["metric_hit_at_1"] is None
    summary = quality.summarize([row])
    assert summary["metrics"]["metric_hit_at_1"] == {"value": None, "n": 0}


def test_dense_arm_uses_vector_order_and_does_not_change_hybrid_or_lexical():
    class Encoder:
        def encode(self, texts):
            return [[1, 0], [0, 1], [1, 0]]
    docs = [{"id": "a", "text": "query", "executable": True},
            {"id": "b", "text": "other", "executable": False}]
    assert rank("query", docs, "dense", Encoder())[0][0]["id"] == "b"
    assert rank("query", docs, "lexical")[0][0]["id"] == "a"
    with pytest.raises(ValueError):
        rank("query", docs, "invalid")


def test_paired_comparison_matches_ids_and_resamples_families():
    a = [{"id": "a", "family": "one", "metrics": {"metric_hit_at_1": 0}},
         {"id": "b", "family": "two", "metrics": {"metric_hit_at_1": 1}}]
    b = deepcopy(a[::-1])
    b[1]["metrics"]["metric_hit_at_1"] = 1
    result = quality.paired_delta(a, b)
    assert result["delta"] == .5
    assert result["wins"] == 1 and result["families"] == 2
    assert result == quality.paired_delta(a, b)
    with pytest.raises(ValueError):
        quality.paired_delta(a, b[:1])
