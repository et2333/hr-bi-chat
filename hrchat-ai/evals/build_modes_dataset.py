"""Author a separate J1 dataset once; never regenerate a scored version."""
import json
from datetime import date, timedelta

from evals.dataset import MODE_DATASET, sha_text
from evals.mode_reference import expected_rows
from evals.reference import MIGRATIONS, count


def build():
    if (MODE_DATASET / "manifest.json").exists():
        raise SystemExit("Dataset exists. Publish a new version; never overwrite scored expectations.")
    names = {"headcount": "在职人数", "hire_count": "入职人数", "leave_count": "离职人数"}
    org_names = {2: "研发中心", 3: "研发一部", 4: "研发二部", 5: "销售部", 6: "职能部"}
    mode_text = {"trend": "趋势", "org": "按部门对比", "detail": "明细", "scalar": "汇总"}
    # id, metric, mode, org, include children, inclusive start, exclusive end, split, user
    specs = [
        ("01", "headcount", "trend", 2, True, "2026-07-01", "2026-09-01", "dev", "hr01"),
        ("02", "hire_count", "trend", 2, True, "2026-07-01", "2026-09-29", "dev", "hr01"),
        ("03", "leave_count", "trend", 2, True, "2026-08-01", "2026-09-20", "dev", "hr01"),
        ("04", "headcount", "trend", 4, True, "2026-08-01", "2026-09-20", "frozen", "hr01"),
        ("05", "hire_count", "trend", 3, True, "2026-09-01", "2026-09-15", "frozen", "hr01"),
        ("06", "headcount", "org", 2, True, "2026-08-01", "2026-09-01", "dev", "hr01"),
        ("07", "hire_count", "org", 2, True, "2026-08-01", "2026-09-01", "dev", "hr01"),
        ("08", "leave_count", "org", 2, True, "2026-09-01", "2026-09-29", "dev", "hr01"),
        ("09", "headcount", "org", 2, False, "2026-08-01", "2026-09-01", "frozen", "hr01"),
        ("10", "leave_count", "org", 6, True, "2026-08-01", "2026-09-01", "frozen", "hr03"),
        ("11", "headcount", "detail", 4, True, "2026-08-01", "2026-08-20", "dev", "hr01"),
        ("12", "headcount", "detail", 4, True, "2026-08-01", "2026-08-21", "dev", "hr01"),
        ("13", "hire_count", "detail", 2, True, "2026-09-01", "2026-09-16", "dev", "hr01"),
        ("14", "leave_count", "detail", 2, True, "2026-09-01", "2026-09-29", "dev", "hr01"),
        ("15", "hire_count", "detail", 3, True, "2026-09-01", "2026-09-15", "frozen", "hr01"),
        ("16", "headcount", "detail", 5, True, "2026-08-01", "2026-09-01", "frozen", "hr02"),
        ("17", "leave_count", "org", 5, True, "2026-08-01", "2026-09-01", "frozen", "hr01"),
        ("18", "headcount", "scalar", 2, True, "2026-08-01", "2026-09-01", "dev", "hr01"),
    ]
    cases = []
    for number, metric, mode, org, children, start, end, split, user in specs:
        orgs = {2: [2, 3, 4], 6: [6, 7, 8]}.get(org, [org]) if children else [org]
        family = "mode_" + mode
        # This is a contract regression set, not an unseen language holdout.
        # Keep related expression templates together, all in the development split.
        split = "dev"
        question = f"{org_names[org]}{names[metric]}{mode_text[mode]}"
        # The date control is explicit user input, not an inferred model date.
        # Distinguish same-phrase cases for the scripted draft lookup via the chosen dates.
        turn = {"question": question, "context_override": {"orgId": str(org), "includeChildren": children,
                "timeRange": {"preset": "CUSTOM", "start": start, "end": end}}}
        expected = {"statuses": ["COMPLETED"], "plan": {"metric_code": metric, "query_mode": mode,
            "org_keys": orgs, "org_id": str(org), "include_children": children,
            "time_label": start + "/" + (date.fromisoformat(end) - timedelta(days=1)).isoformat(),
            "metric_version": 2 if metric == "headcount" else 1}, "require_execution_evidence": True,
            "oracle": "literal_seed_records_modes_v1"}
        if number in {"16", "17"}:
            expected = {"statuses": ["DENIED"], "no_data": True, "no_execution": True,
                        "error_code": "HRC-2002" if number == "16" else "HRC-2003"}
        elif mode == "scalar":
            expected["value"] = count(metric, orgs, start, end)
        else:
            expected["rows"] = expected_rows(metric, mode, orgs, start, end)
            keys = (["period", metric] if mode == "trend" else ["org_name", metric] if mode == "org"
                    else ["emp_no", "emp_name", "org_key", "job_level"] if metric == "headcount"
                    else ["dt", "emp_key", "org_key"])
            expected["columns"] = [{"key": k, "masked": mode == "detail"} for k in keys]
            if mode == "org":
                expected["partition_total"] = count(metric, orgs, start, end)
            if mode == "detail":
                expected["row_limit"] = 50
        turn["expected"] = expected
        cases.append({"case_id": "hr-modes-v1-" + number, "group_id": family, "template_id": family,
            "scene": "permission" if number in {"16", "17"} else mode, "split": split,
            "data_version": "h2-v6", "as_of_date": "2026-09-28", "identity_fixture": user,
            "session_owner": user, "turns": [turn]})
    MODE_DATASET.mkdir(parents=True, exist_ok=True)
    (MODE_DATASET / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {"schema_version": 1, "dataset_version": MODE_DATASET.name, "data_version": "h2-v6",
        "as_of_date": "2026-09-28", "timezone": "Asia/Shanghai", "hash_scheme": "sha256-lf-text-v1",
        "cases_sha256": sha_text(MODE_DATASET / "cases.json"),
        "migration_sha256": {p.name: sha_text(p) for p in sorted(MIGRATIONS.glob("*.sql"))},
        "case_count": len(cases), "turn_count": len(cases),
        "expected_source": "literal seed records; independent row/group/calendar oracle; no production SQL imports",
        "scope": "J1 query mode result contracts; explicit user date/org controls; separate from hr-query-v1",
        "freeze_policy": "No expectation changes after first scored run; all later runs are regression",
        "limitations": ["Not broad language generalization: date/org controls are supplied explicitly",
            "Fixture drafts test orchestration and execution, not LLM understanding",
            "Masked display rows can collide; exact identity and >50 truncation use separate Java tests",
            "Event trends omit empty months; no invented zero fill", "No browser rendering or production load measurement"]}
    (MODE_DATASET / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"dataset": MODE_DATASET.name, "cases": len(cases)}))


if __name__ == "__main__":
    build()
