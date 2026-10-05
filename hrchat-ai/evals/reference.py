"""Read literal seed rows; compute expectations without production SQL/code."""
import ast
import re
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "hrchat-server/hrchat-bootstrap/src/main/resources/db/migration/h2"


def seed_rows(table):
    text = (MIGRATIONS / "V2__demo_seed.sql").read_text(encoding="utf-8")
    rows = []
    for columns, values in re.findall(
        rf"INSERT INTO {table}\s*\(([^)]+)\) VALUES\s*(.*?);", text, re.S
    ):
        keys = [c.strip() for c in columns.split(",")]
        for raw in re.findall(r"\(([^()]*)\)", values):
            items = ast.literal_eval("(" + re.sub(r"\bNULL\b", "None", raw) + ")")
            rows.append(dict(zip(keys, items, strict=True)))
    if not rows:
        raise ValueError(f"No literal seed rows: {table}")
    return rows


def count(metric, orgs, start, end):
    if metric == "headcount":
        as_of = (date.fromisoformat(end) - timedelta(days=1)).isoformat()
        return len({r["emp_key"] for r in seed_rows("dim_employee")
                    if r["org_key"] in orgs and r["hire_date"] <= as_of
                    and (r["leave_date"] is None or r["leave_date"] > as_of)})
    types = {1} if metric == "hire_count" else {5, 6}
    if metric not in {"hire_count", "leave_count"}:
        raise ValueError(metric)
    return sum(r["org_key"] in orgs and r["change_type"] in types
               and start <= r["change_date"] < end for r in seed_rows("fact_emp_change"))
