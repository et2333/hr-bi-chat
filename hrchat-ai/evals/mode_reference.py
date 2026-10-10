"""Independent row oracle over literal demo records; no production query imports."""
from collections import Counter
from datetime import date, timedelta

from evals.reference import count, seed_rows


def matching_records(metric, orgs, start, end):
    if metric == "headcount":
        as_of = (date.fromisoformat(end) - timedelta(days=1)).isoformat()
        return [r for r in seed_rows("dim_employee") if r["org_key"] in orgs
                and r["hire_date"] <= as_of and (r["leave_date"] is None or r["leave_date"] > as_of)]
    kinds = {"hire_count": {1}, "leave_count": {5, 6}}[metric]
    return [r for r in seed_rows("fact_emp_change") if r["org_key"] in orgs
            and r["change_type"] in kinds and start <= r["change_date"] < end]


def mask_demo_field(value):
    # Demo identities have no plaintext grants for the detail projection.
    # This public display oracle cannot distinguish records that mask identically.
    if value is None:
        return None
    text = str(value)
    if len(text) <= 4:
        return "****"
    edge = max(1, len(text) // 4)
    return text[:edge] + "*" * (len(text) - edge * 2) + text[-edge:]


def expected_rows(metric, mode, orgs, start, end):
    records = matching_records(metric, orgs, start, end)
    if mode == "org":
        names = {r["org_key"]: r["org_name"] for r in seed_rows("dim_org") if r["is_current"] == 1}
        # Direct membership is mutually exclusive: parent is not a subtree subtotal.
        counts = Counter(r["org_key"] for r in records)
        return [{"org_name": names[k], metric: v} for k, v in sorted(counts.items())]
    if mode == "trend":
        if metric != "headcount":
            counts = Counter(r["change_date"][:7] for r in records)
            # Existing event-trend contract is sparse: absent months are not invented.
            return [{"period": k, metric: v} for k, v in sorted(counts.items())]
        start_day, stop = date.fromisoformat(start), date.fromisoformat(end)
        # Derive month endpoints from calendar days, independently of the SQL builder.
        snapshots = {}
        for offset in range((stop - start_day).days):
            day = start_day + timedelta(days=offset)
            snapshots[day.strftime("%Y-%m")] = day
        return [{"period": month, metric: count(metric, orgs, start, (day + timedelta(days=1)).isoformat())}
                for month, day in sorted(snapshots.items())]
    if mode == "detail":
        fields = ["emp_no", "emp_name", "org_key", "job_level"] if metric == "headcount" else ["dt", "emp_key", "org_key"]
        # Seed records are below the 50-row cap. Cap behavior has a separate DB fixture.
        if len(records) > 50:
            raise ValueError("Version this oracle with a deterministic truncation fixture")
        return [{field: mask_demo_field(r[field]) for field in fields} for r in records]
    raise ValueError(mode)
