import hashlib
import json
from pathlib import Path

DATASET = Path(__file__).parent / "datasets/hr-query-v1"


def select_cases(cases, split, case_ids=None):
    selected = [c for c in cases if split == "all" or c["split"] == split]
    if case_ids:
        requested = set(case_ids)
        missing = requested - {c["case_id"] for c in selected}
        if missing:
            raise ValueError("Case IDs outside selected split: " + ", ".join(sorted(missing)))
        selected = [c for c in selected if c["case_id"] in requested]
    return selected


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sha_text(path):
    # Git checks out text files with platform-dependent line endings. Hash the
    # same LF representation on Windows and Linux so a frozen dataset is portable.
    data = Path(path).read_bytes().replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()


def load_dataset(path=DATASET):
    manifest = json.loads((path / "manifest.json").read_text(encoding="utf-8"))
    if sha_text(path / "cases.json") != manifest["cases_sha256"]:
        raise ValueError("Dataset hash mismatch; publish a new version before scoring")
    cases = json.loads((path / "cases.json").read_text(encoding="utf-8"))
    ids, groups, templates = set(), {}, {}
    for c in cases:
        for key in ("case_id", "group_id", "template_id", "scene", "data_version", "as_of_date", "identity_fixture", "session_owner", "turns"):
            if not c.get(key):
                raise ValueError(f"Missing case field: {key}")
        if c["split"] not in {"dev", "frozen"}:
            raise ValueError("Invalid split")
        if c["data_version"] != manifest["data_version"] or c["as_of_date"] != manifest["as_of_date"]:
            raise ValueError("Case data/clock mismatch")
        if c["case_id"] in ids:
            raise ValueError("Duplicate case ID")
        ids.add(c["case_id"])
        for key, seen in [("group_id", groups), ("template_id", templates)]:
            if c[key] in seen and seen[c[key]] != c["split"]:
                raise ValueError(f"Cross-split leakage: {key}")
            seen[c[key]] = c["split"]
        if not c["turns"] or any(not t.get("expected") for t in c["turns"]):
            raise ValueError("Missing expected turns")
        for turn in c["turns"]:
            expected = turn["expected"]
            if not expected.get("statuses"):
                raise ValueError("Missing expected terminal state")
            if "COMPLETED" in expected["statuses"] and not expected.get("plan"):
                raise ValueError("Successful query requires plan evidence")
    if len(cases) != manifest["case_count"] or sum(len(c["turns"]) for c in cases) != manifest["turn_count"]:
        raise ValueError("Manifest case/turn count mismatch")
    return manifest, cases
