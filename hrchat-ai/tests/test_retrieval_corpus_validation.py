from copy import deepcopy
from evals.build_retrieval_corpus import load_jsonl, validate, CORPUS_DIR
from evals import retrieval_scale


def test_examples_are_compiler_checked_not_claimed_human_reviewed():
    examples = load_jsonl(CORPUS_DIR / 'examples_v2.jsonl')
    distractors = load_jsonl(CORPUS_DIR / 'metrics_distractors.jsonl')
    assert validate(examples, distractors) == []
    assert all(row['compiled_outcome'] for row in examples)
    assert not any(row['reviewed'] for row in examples + distractors)
    corrupted = deepcopy(examples)
    corrupted[0]['draft']['metric_text'] = 'not in original question'
    assert any('compiler rejected' in error for error in validate(corrupted, distractors))


def test_example_scoring_does_not_count_null_mode_as_automatic_hit(monkeypatch):
    monkeypatch.setattr(retrieval_scale, 'retrieve', lambda *args, **kwargs: ({'metadata': [], 'examples': []},
        {'status': 'ok', 'elapsed_ms': 1, 'candidate_count': 0, 'distractor_count': 0}))
    result = retrieval_scale.score_mode('lexical', [{'id': 'test', 'question': 'independent question',
        'expected_metric': 'headcount', 'expected_mode': None}], {})
    assert result['example_hit_at_2'] == 0
    assert result['exact_overlap_count'] == 0
    assert result['nonoverlap_example_hit_at_2'] == 0
    assert result['metric_mrr'] == 0
    assert result['nonoverlap_metric_mrr'] == 0


def test_metric_mrr_uses_first_gold_rank():
    assert retrieval_scale.reciprocal_rank(['hire_count', 'headcount'], 'headcount') == 0.5
    assert retrieval_scale.reciprocal_rank(['headcount'], 'headcount') == 1.0
    assert retrieval_scale.reciprocal_rank(['leave_count'], 'headcount') == 0.0


def test_score_mode_reports_metric_mrr(monkeypatch):
    def fake_retrieve(question, catalog, *, mode=None, embedder=None, include_distractors=False):
        meta = [{'code': 'leave_count', 'executable': True}, {'code': 'headcount', 'executable': True}]
        return {'metadata': meta, 'examples': []}, {
            'status': 'ok', 'elapsed_ms': 1, 'candidate_count': 2, 'distractor_count': 0}
    monkeypatch.setattr(retrieval_scale, 'retrieve', fake_retrieve)
    result = retrieval_scale.score_mode('hybrid', [
        {'id': 'a', 'question': 'q1', 'expected_metric': 'headcount', 'expected_mode': 'scalar'},
        {'id': 'b', 'question': 'q2', 'expected_metric': 'leave_count', 'expected_mode': 'scalar'},
    ], {})
    assert result['metric_mrr'] == 0.75
    assert result['metric_hit_at_1'] == 0.5
    assert result['metric_hit_at_2'] == 1.0
