from pathlib import Path
import json

from adapters.query_retrieval import load_corpus
from evals.retrieval_scale import load_cases


def test_hard_split_is_nonoverlap_and_targets_distractor_wording():
    hard = load_cases("hard")
    assert len(hard) == 12
    examples = {row["question"].strip() for row in load_corpus()["examples"]}
    assert not any(case["question"].strip() in examples for case in hard)
    blob = " ".join(case["question"] for case in hard)
    for cue in ("编制", "Offer", "主动离职", "流失风险", "计划入职", "FTE"):
        assert cue in blob


def test_holdout_still_twenty():
    assert len(load_cases("holdout")) == 20
    payload = json.loads(Path("evals/datasets/retrieval-scale-v1/cases.json").read_text(encoding="utf-8"))
    assert sum(1 for c in payload["cases"] if c["split"] == "hard") == 12
