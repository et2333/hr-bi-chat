"""Independent S6 contribution cases and literal-seed reference calculations.

The reference does not import the production analysis validator, SQL composer,
or model prompts. Fault fixtures are explicitly separate from seed scenarios.
"""
from __future__ import annotations

from collections import Counter
import hashlib
import json
from pathlib import Path

from evals.dataset import sha_text
from evals.reference import MIGRATIONS, seed_rows


DATASET = Path(__file__).parent / "datasets/hr-analysis-v1"
GROUP = list(range(1, 9))
R_AND_D = [2, 3, 4]
PERIODS = {
    "jun": {"start": "2026-06-01", "end": "2026-07-01"},
    "jul": {"start": "2026-07-01", "end": "2026-08-01"},
    "aug": {"start": "2026-08-01", "end": "2026-09-01"},
    "sep": {"start": "2026-09-01", "end": "2026-10-01"},
}


def reference_counts(org_ids, period):
    """COUNT(*) of departure events; change_date uses a half-open window."""
    counts = Counter(r["org_key"] for r in seed_rows("fact_emp_change")
                     if r["change_type"] in {5, 6} and r["org_key"] in org_ids
                     and period["start"] <= r["change_date"] < period["end"])
    return {str(org_id): counts[org_id] for org_id in org_ids}


def reference(case):
    current = reference_counts(case["org_ids"], case["current_period"])
    baseline = reference_counts(case["org_ids"], case["baseline_period"])
    return {"current_total": sum(current.values()), "baseline_total": sum(baseline.values()),
            "delta": sum(current.values()) - sum(baseline.values()),
            "current_counts": current, "baseline_counts": baseline,
            "department_deltas": {key: current[key] - baseline[key] for key in current}}


def seed_evidence(case):
    """A local fake Java response, including explicit zeroes for a complete scope."""
    truth = reference(case)
    names = {r["org_key"]: r["org_name"] for r in seed_rows("dim_org")}
    daily = []
    for label, window in (("current", case["current_period"]), ("baseline", case["baseline_period"])):
        dates = Counter(r["change_date"] for r in seed_rows("fact_emp_change")
                        if r["change_type"] in {5, 6} and r["org_key"] in case["org_ids"]
                        and window["start"] <= r["change_date"] < window["end"])
        daily.extend({"period": label, "date": day, "count": value}
                     for day, value in sorted(dates.items()))
    return {"status": "complete", "metric_code": "leave_count", "metric_version": "1", "unit": "人",
            "data_version": "s6-literal-seed-v1", "current_period": case["current_period"],
            "baseline_period": case["baseline_period"], "effective_org_ids": case["org_ids"],
            "quality_issues": [], "current_total": truth["current_total"], "baseline_total": truth["baseline_total"],
            "departments": [{"org_id": key, "org_name": names[key],
                             "current_count": truth["current_counts"][str(key)],
                             "baseline_count": truth["baseline_counts"][str(key)]}
                            for key in case["org_ids"]], "daily": daily}


def load_dataset(path=DATASET):
    manifest = json.loads((path / "manifest.json").read_text(encoding="utf-8"))
    if sha_text(path / "cases.json") != manifest["cases_sha256"]:
        raise ValueError("S6 dataset changed; publish a new version before scoring")
    if sha_text(MIGRATIONS / "V2__demo_seed.sql") != manifest["seed_sha256"]:
        raise ValueError("S6 seed changed; refresh independent expectations explicitly")
    cases = json.loads((path / "cases.json").read_text(encoding="utf-8"))
    ids, groups = set(), {}
    for case in cases:
        if case["case_id"] in ids or case["split"] not in {"dev", "frozen"}:
            raise ValueError("Duplicate case or invalid split")
        ids.add(case["case_id"])
        if groups.setdefault(case["group_id"], case["split"]) != case["split"]:
            raise ValueError("S6 canonical group crosses splits")
        if case["origin"] not in {"seed", "injected"} or not case.get("expected"):
            raise ValueError("Missing origin or independent expected result")
        if case["origin"] == "seed" and reference(case) != case["expected"]["numbers"]:
            raise ValueError("Literal seed no longer matches the hand-audited anchor")
    if len(cases) != manifest["case_count"]:
        raise ValueError("S6 case count changed")
    return manifest, cases


def dataset_identity(manifest):
    return {key: manifest[key] for key in ("version", "cases_sha256", "seed_sha256")}


def fingerprint(document):
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
