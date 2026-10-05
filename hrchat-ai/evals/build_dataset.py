"""Explicit authoring tool. Review and version generated files before any run."""
import json
from datetime import date, timedelta

from evals.dataset import DATASET, sha
from evals.reference import MIGRATIONS, count


def build():
    if (DATASET / "manifest.json").exists():
        raise SystemExit("Dataset already exists. Publish a new version; never overwrite a scored dataset.")
    cases = []

    def expected(metric, orgs, start="2026-09-01", end="2026-09-29", current=False):
        return {"statuses": ["COMPLETED"], "plan": {
            "metric_code": metric, "org_keys": orgs, "query_mode": "scalar",
            "time_label": "截至 2026-09-28" if current else
            start + "/" + (date.fromisoformat(end) - timedelta(days=1)).isoformat()},
            "value": count(metric, orgs, start, end), "tolerance": 0,
            "oracle": "literal_seed_rows_python_reference_v1"}

    def add(group, scene, turns, identity="hr01", split="dev", owner=None):
        cases.append({"case_id": f"hr-v1-{len(cases)+1:03}", "group_id": group,
                      "template_id": group, "scene": scene, "split": split,
                      "data_version": "h2-v6", "as_of_date": "2026-09-28",
                      "identity_fixture": identity, "session_owner": owner or identity,
                      "turns": turns})

    names = {"headcount": "在职人数", "hire_count": "入职人数", "leave_count": "离职人数"}
    # All variants of one expression family stay on the same side of the split.
    families = [
        ("explicit_last_month", "上月{org}{metric}是多少？", "2026-08-01", "2026-09-01", "dev"),
        ("explicit_this_month", "本月{org}{metric}是多少？", "2026-09-01", "2026-09-29", "dev"),
        ("recent_month_window", "近30天{org}{metric}是多少？", "2026-08-30", "2026-09-29", "frozen"),
        ("recent_week_window", "最近7天{org}{metric}是多少？", "2026-09-22", "2026-09-29", "frozen"),
    ]
    for group, template, start, end, split in families:
        for metric, name in names.items():
            for org, orgs in [("研发中心", [2, 3, 4]), ("研发一部", [3])]:
                add(group, "single", [{"question": template.format(org=org, metric=name),
                                      "expected": expected(metric, orgs, start, end)}], split=split)
    # Missing/ambiguous slots: S0 requires a missing period to stop with HRX-1001.
    for metric in ["入职人数", "离职人数"]:
        for org in ["研发中心", "研发一部"]:
            add("missing_period", "clarify", [{"question": org + metric,
                "expected": {"statuses": ["FAILED"], "error_code": "HRX-1001", "no_data": True}}])
    for question in ["帮我看看", "研发中心情况怎么样", "查一下数据", "给我一个统计"]:
        add("missing_metric", "clarify", [{"question": question,
             "expected": {"statuses": ["CLARIFYING"], "no_data": True}}], split="frozen")
    # Complete sequences count as ONE case; second-turn expectations are independent of the first answer.
    for metric, name in names.items():
        for period, start, end in [("上月", "2026-08-01", "2026-09-01"),
                                   ("本月", "2026-09-01", "2026-09-29")]:
            add("followup_org", "multi_turn", [
                {"question": period + "研发中心" + name,
                 "expected": expected(metric, [2, 3, 4], start, end)},
                {"question": "研发一部呢？", "expected": expected(metric, [3], start, end)}])
    for metric, name in names.items():
        add("followup_period", "multi_turn", [
            {"question": "上月研发中心" + name,
             "expected": expected(metric, [2, 3, 4], "2026-08-01", "2026-09-01")},
            {"question": "那本月呢？", "expected": expected(metric, [2, 3, 4])}], split="frozen")
    for user, org in [("hr01", "销售部"), ("hr02", "研发中心"), ("hr03", "研发中心")]:
        for metric in ["在职人数", "入职人数"]:
            add("denied_org", "permission", [{"question": "本月" + org + metric,
                "expected": {"statuses": ["DENIED"], "error_code": "HRC-2003", "no_data": True}}], user)
    for user in ["hr02", "hr03", "hr04"]:
        add("session_owner", "isolation", [{"question": "研发中心在职人数",
            "expected": {"statuses": ["DENIED"], "error_code": "HRC-2002", "no_data": True}}],
            user, "frozen", "hr01")
    for end in ["2026-08-20", "2026-08-21", "2026-09-15", "2026-09-16"]:
        add("asof_day_boundary", "boundary", [{"question": "研发中心在职人数",
            "context_override": {"timeRange": {"preset": "CUSTOM", "start": "2026-08-01", "end": end}},
            "expected": expected("headcount", [2, 3, 4], "2026-08-01", end)}])
    for question in ["产品部在职人数", "市场部在职人数", "研发中心按性别统计在职人数",
                     "研发中心预测下月离职人数", "研发中心为什么离职人数增加了", "研发中心主动离职人数"]:
        add("unsupported_scope", "unsupported", [{"question": question,
            "expected": {"statuses": ["UNSUPPORTED", "CLARIFYING"], "no_data": True}}], split="frozen")
    DATASET.mkdir(parents=True, exist_ok=True)
    (DATASET / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {"schema_version": 1, "dataset_version": "hr-query-v1", "data_version": "h2-v6",
                "as_of_date": "2026-09-28", "timezone": "Asia/Shanghai",
                "cases_sha256": sha(DATASET / "cases.json"),
                "migration_sha256": {p.name: sha(p) for p in sorted(MIGRATIONS.glob("*.sql"))},
                "expected_source": "literal seed rows + independent Python counting; no production SQL imports",
                "freeze_policy": "group/template/sequence disjoint; first scored run is baseline, later runs regression",
                "scope": "Java user API; scalar counts, multi-turn, permission and scope boundaries",
                "limitations": ["No trend/detail scorer in v1", "No tenant-switch mutation fixtures",
                                "Fault injection is runner tests, separately reported; not model task evidence",
                                "SQL scope inspection is temporary local-runtime evidence, not QueryPlan accuracy"],
                "case_count": len(cases), "turn_count": sum(len(c["turns"]) for c in cases)}
    (DATASET / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"cases": len(cases), "turns": manifest["turn_count"]}))


if __name__ == "__main__":
    build()
