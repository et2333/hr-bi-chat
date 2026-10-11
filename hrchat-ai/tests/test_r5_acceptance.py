from evals.s6_dataset import load_dataset
from evals.s6_real import insight_coverage


def test_prepare_only_never_loads_credentials_or_constructs_adapter(tmp_path, monkeypatch):
    import asyncio
    from evals import s6_real
    monkeypatch.setattr(s6_real, "ROOT", tmp_path)
    def forbidden(*args, **kwargs):
        raise AssertionError("prepare-only must not access model configuration")
    monkeypatch.setattr(s6_real, "load_local_model_env", forbidden)
    monkeypatch.setattr(s6_real, "OpenAIClientImpl", forbidden)
    assert asyncio.run(s6_real.run("qwen-plus", ["S6-01", "S6-03"], prepare_only=True)) == 0


def test_insight_denominator_does_not_shrink_when_model_omits_departments():
    _, cases = load_dataset()
    case = next(c for c in cases if c["case_id"] == "S6-01")
    result = {"facts": {"overall": 1}, "claims": [{"kind": "fact", "fact_id": "overall", "evidence_ids": ["e"]}],
              "evidence": [{"evidence_id": "e", "result": {"facts": {"overall": 1}}}]}
    score = insight_coverage(case, result)
    assert score["numerator"] == 1
    assert score["denominator"] > 1
    result["claims"][0]["evidence_ids"] = ["fabricated"]
    assert insight_coverage(case, result)["numerator"] == 0


