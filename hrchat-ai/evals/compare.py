"""Compare task evidence across replays, ignoring IDs/timestamps/latency."""
import argparse
import json
from pathlib import Path


def task_signature(row):
    return {"passed": row.get("passed"), "execution_error": row.get("execution_error"),
            "turns": [{"expected": turn["expected"], "passed": turn["passed"], "errors": turn["errors"],
                       "status": turn["actual"]["status"], "error_codes": turn["actual"]["error_codes"],
                       "table": (turn["actual"].get("answer") or {}).get("table"),
                       "conclusion": (turn["actual"].get("answer") or {}).get("conclusion"),
                       "chart": (turn["actual"].get("answer") or {}).get("chart"),
                       "caliber": (turn["actual"].get("answer") or {}).get("caliber")}
                      for turn in row["turns"]]}


def compare(originals, replay):
    old = {}
    for report in originals:
        if report["dataset"]["cases_sha256"] != replay["dataset"]["cases_sha256"]:
            raise ValueError("Different dataset versions cannot be a replay")
        if report["summary"]["status"] != "COMPLETED":
            raise ValueError("Original run is incomplete")
        for row in report["results"]:
            if row["case_id"] in old:
                raise ValueError("Original cases overlap")
            old[row["case_id"]] = task_signature(row)
    new = {row["case_id"]: task_signature(row) for row in replay["results"]}
    missing = sorted(set(old) - set(new))
    extra = sorted(set(new) - set(old))
    changed = sorted(key for key in old.keys() & new.keys() if old[key] != new[key])
    return {"matched": not (missing or extra or changed) and replay["summary"]["status"] == "COMPLETED",
            "compared_cases": len(old.keys() & new.keys()), "missing": missing, "extra": extra, "changed": changed}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--original", type=Path, nargs="+", required=True)
    parser.add_argument("--replay", type=Path, required=True)
    args = parser.parse_args()
    result = compare([json.loads(p.read_text(encoding="utf-8")) for p in args.original],
                     json.loads(args.replay.read_text(encoding="utf-8")))
    (args.replay.parent / "replay-check.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))
    return 0 if result["matched"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
