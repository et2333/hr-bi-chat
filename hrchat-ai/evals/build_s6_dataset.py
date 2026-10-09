"""Publish the initial S6 engineering regression fixtures; never run on import.

After publication, change the dataset version before changing frozen cases.
The eight numeric anchors below were counted manually from six departure rows.
"""
import json

from evals.dataset import sha_text
from evals.reference import MIGRATIONS
from evals.s6_dataset import DATASET, GROUP, PERIODS, R_AND_D


def _numbers(orgs, current, baseline):
    c = {str(key): current.get(key, 0) for key in orgs}
    b = {str(key): baseline.get(key, 0) for key in orgs}
    return {"current_total": sum(c.values()), "baseline_total": sum(b.values()),
            "delta": sum(c.values()) - sum(b.values()), "current_counts": c, "baseline_counts": b,
            "department_deltas": {key: c[key] - b[key] for key in c}}


def build_cases():
    anchors = [
        ("group_growth", "dev", GROUP, PERIODS["sep"], PERIODS["aug"], {3: 1, 4: 1, 5: 1}, {4: 1}),
        ("group_decline", "frozen", GROUP, PERIODS["aug"], PERIODS["jul"], {4: 1}, {3: 1, 7: 1}),
        ("department_offset", "dev", R_AND_D, PERIODS["aug"], PERIODS["jul"], {4: 1}, {3: 1}),
        ("empty_both_periods", "frozen", [8], PERIODS["sep"], PERIODS["aug"], {}, {}),
        ("start_boundary", "dev", [3], {"start": "2026-09-18", "end": "2026-09-19"},
         {"start": "2026-09-17", "end": "2026-09-18"}, {3: 1}, {}),
        ("end_boundary", "frozen", [4], {"start": "2026-08-21", "end": "2026-08-22"},
         {"start": "2026-08-20", "end": "2026-08-21"}, {}, {4: 1}),
        ("unchanged_nonzero", "dev", [4], PERIODS["sep"], PERIODS["aug"], {4: 1}, {4: 1}),
        ("zero_baseline", "frozen", GROUP, PERIODS["jul"], PERIODS["jun"], {3: 1, 7: 1}, {}),
    ]
    cases = []
    for scene, split, orgs, current, baseline, counts, base_counts in anchors:
        cases.append({"case_id": f"S6-{len(cases) + 1:02d}", "group_id": scene, "split": split,
                      "scene": scene, "origin": "seed", "injection": None, "org_ids": orgs,
                      "current_period": current, "baseline_period": baseline,
                      "expected": {"statuses": ["COMPLETED"], "numbers": _numbers(orgs, counts, base_counts)}})
    faults = [
        ("missing_department", "dev"), ("missing_total", "frozen"),
        ("snapshot_mismatch", "dev"), ("metric_version_mismatch", "frozen"),
        ("scope_mismatch", "dev"), ("duplicate_department", "frozen"),
        ("nonclosing_total", "dev"), ("organization_history_gap", "frozen"),
        ("unsupported_metric", "dev"), ("permission_denied", "frozen"),
        ("tool_budget_exhausted", "dev"), ("deadline_exhausted", "frozen"),
        ("cancel_during_tool", "dev"), ("conditional_daily_query", "frozen"),
        ("unknown_claim_reference", "dev"), ("invalid_model_json", "frozen"),
    ]
    for scene, split in faults:
        expected = {"statuses": ["FAILED"], "no_unverified_data": True}
        if scene == "cancel_during_tool":
            expected["statuses"] = ["CANCELLED"]
        if scene == "conditional_daily_query":
            expected = {"statuses": ["COMPLETED"], "numbers": _numbers(GROUP, {3: 1, 4: 1, 5: 1}, {4: 1}),
                        "conditional_supplement": True}
        if scene == "unknown_claim_reference":
            expected = {"statuses": ["COMPLETED", "PARTIAL"], "no_unsupported_claims": True,
                        "numbers": _numbers(GROUP, {3: 1, 4: 1, 5: 1}, {4: 1})}
        if scene == "invalid_model_json":
            expected = {"statuses": ["FAILED", "PARTIAL"], "mode_statuses": {"deterministic": ["COMPLETED"]},
                        "no_unsupported_claims": True}
        cases.append({"case_id": f"S6-{len(cases) + 1:02d}", "group_id": scene, "split": split,
                      "scene": scene, "origin": "injected", "injection": scene,
                      "org_ids": GROUP, "current_period": PERIODS["sep"], "baseline_period": PERIODS["aug"],
                      "expected": expected})
    return cases


def main():
    if (DATASET / "cases.json").exists() or (DATASET / "manifest.json").exists():
        raise FileExistsError("Published S6 dataset must not be overwritten; publish a new version instead")
    DATASET.mkdir(parents=True, exist_ok=True)
    cases = build_cases()
    (DATASET / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {"schema_version": "1", "version": "hr-analysis-v1", "case_count": len(cases),
                "cases_sha256": sha_text(DATASET / "cases.json"),
                "seed_sha256": sha_text(MIGRATIONS / "V2__demo_seed.sql"),
                "splits": {split: sum(c["split"] == split for c in cases) for split in ("dev", "frozen")},
                "seed_case_count": sum(c["origin"] == "seed" for c in cases),
                "injected_case_count": sum(c["origin"] == "injected" for c in cases),
                "scope": "leave_count COUNT(*) by event org_key; change_date half-open; no causal inference",
                "limits": {"max_model_calls": 4, "max_mcp_attempts": 6, "timeout_seconds": 60, "max_supplements": 1},
                "split_note": "Canonical scenario groups are disjoint. Frozen engineering regressions are not independent real-user generalization evidence.",
                "comparison_note": "Same seed, tools and ceilings for all modes; fixture-role scripts only test orchestration. Report model-dependent/injected cases separately."}
    (DATASET / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
