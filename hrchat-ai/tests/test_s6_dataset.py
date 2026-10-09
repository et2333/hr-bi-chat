import json

import pytest

from evals.dataset import sha_text
from evals.s6_dataset import load_dataset, reference, seed_evidence


def test_manual_departure_anchors_are_independent_of_sql_and_validator():
    _, cases = load_dataset()
    growth, decline, offset = [reference(case) for case in cases[:3]]
    assert (growth["baseline_total"], growth["current_total"], growth["delta"]) == (1, 3, 2)
    assert {k: v for k, v in decline["department_deltas"].items() if v} == {"3": -1, "4": 1, "7": -1}
    assert offset["delta"] == 0
    assert offset["department_deltas"] == {"2": 0, "3": -1, "4": 1}
    assert reference(cases[4])["delta"] == 1  # includes start, excludes end
    assert reference(cases[5])["delta"] == -1


def test_dataset_keeps_safety_injections_and_frozen_scenarios_explicit():
    manifest, cases = load_dataset()
    assert manifest["case_count"] == 24
    assert manifest["splits"] == {"dev": 12, "frozen": 12}
    assert sum(c["origin"] == "seed" for c in cases) == 8
    assert sum(c["origin"] == "injected" for c in cases) == 16
    assert {c["group_id"] for c in cases if c["split"] == "dev"}.isdisjoint(
        c["group_id"] for c in cases if c["split"] == "frozen")


def test_fake_java_evidence_does_not_double_count_organization_ancestors():
    _, cases = load_dataset()
    evidence = seed_evidence(cases[0])
    rows = {row["org_id"]: row for row in evidence["departments"]}
    assert rows[1]["current_count"] == rows[2]["current_count"] == 0
    assert rows[3]["current_count"] == rows[4]["current_count"] == 1
    assert sum(row["current_count"] for row in rows.values()) == 3
    assert sum(row["count"] for row in evidence["daily"] if row["period"] == "current") == 3


def test_changed_frozen_numeric_anchor_cannot_silently_pass(tmp_path):
    manifest, cases = load_dataset()
    cases[1]["expected"]["numbers"]["delta"] = 999
    (tmp_path / "cases.json").write_text(json.dumps(cases), encoding="utf-8")
    manifest["cases_sha256"] = sha_text(tmp_path / "cases.json")
    (tmp_path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="hand-audited"):
        load_dataset(tmp_path)


def test_builder_refuses_overwriting_published_cases():
    from evals.build_s6_dataset import main
    with pytest.raises(FileExistsError, match="must not be overwritten"):
        main()
