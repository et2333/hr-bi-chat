"""Independent insight scenarios: frozen dataset loading and arm scoring.

The expected facts, forbidden conclusions and actions are hand-audited from
metric semantics. ``verify_case_facts`` re-checks them against the production
FactBuilder only to detect drift; scoring never imports the production result
validator or prompt.
"""
from __future__ import annotations

import json
from pathlib import Path

from evals.dataset import sha_text
from insight.fact_builder import build_fact_bundle
from insight.schema import InsightRequest

DATASET = Path(__file__).parent / "datasets/insight-scenarios-v1"
VERIFIABLE_FACT_TYPES = ("fact", "statistical_contribution")


def load_dataset(path=DATASET):
    """Load and validate the frozen dataset; raise on any drift."""
    manifest = json.loads((path / "manifest.json").read_text(encoding="utf-8"))
    if sha_text(path / "cases.json") != manifest["cases_sha256"]:
        raise ValueError("Insight dataset changed; publish a new version before scoring")
    if sha_text(path / "ablations.json") != manifest["ablations_sha256"]:
        raise ValueError("Insight ablation set changed; publish a new version before scoring")
    cases = json.loads((path / "cases.json").read_text(encoding="utf-8"))
    ablations = json.loads((path / "ablations.json").read_text(encoding="utf-8"))

    ids = set()
    for case in cases:
        if case["case_id"] in ids or case["split"] not in {"dev", "frozen"}:
            raise ValueError("Duplicate case or invalid split")
        ids.add(case["case_id"])
        if not case.get("scenario") or not case.get("expected"):
            raise ValueError("Missing scenario or independent expectation")
        verify_case_facts(case)
    ab_ids = set()
    for ablation in ablations:
        if ablation["ablation_id"] in ab_ids:
            raise ValueError("Duplicate ablation")
        ab_ids.add(ablation["ablation_id"])
        if not ablation.get("expected"):
            raise ValueError("Missing ablation expectation")
        verify_ablation(ablation)
    if len(cases) != manifest["case_count"] or len(ablations) != manifest["ablation_count"]:
        raise ValueError("Insight dataset count changed")
    return manifest, cases, ablations


def build_request(context, snapshot) -> InsightRequest:
    return InsightRequest.model_validate({"context": context, "snapshot": snapshot})


def verify_case_facts(case) -> None:
    """Hand-audited expectations must still agree with the production FactBuilder."""
    request = build_request(case["context"], case["snapshot"])
    bundle = build_fact_bundle(request.context, request.snapshot)
    actual = {f.fact_id: f for f in bundle.facts}
    expected = case["expected"]["facts"]
    missing = set(expected) - set(actual)
    extra = set(actual) - set(expected)
    if missing or extra:
        raise ValueError(
            f"{case['case_id']} fact set drift: missing={sorted(missing)} unexpected={sorted(extra)}")
    for fid, want in expected.items():
        got = actual[fid]
        if got.fact_type.value != want["fact_type"] or got.computable != want["computable"]:
            raise ValueError(f"{case['case_id']} {fid} drift: type/computable mismatch")
        if got.computable and got.preferred != want["preferred"]:
            raise ValueError(f"{case['case_id']} {fid} preferred drift")


def verify_ablation(ablation) -> None:
    """The partial/full expectations must agree with what each arm's bundle yields."""
    full = build_request(ablation["context_full"], ablation["snapshot_full"])
    partial = build_request(ablation["context_partial"], ablation["snapshot_partial"])
    full_facts = {f.fact_id: f for f in build_fact_bundle(full.context, full.snapshot).facts}
    partial_facts = {f.fact_id: f for f in build_fact_bundle(partial.context, partial.snapshot).facts}
    expected = ablation["expected"]
    for fid in expected["full_computable"]:
        if fid not in full_facts or not full_facts[fid].computable:
            raise ValueError(f"{ablation['ablation_id']} {fid} should be computable in the full arm")
    for fid in expected["partial_incomputable"]:
        if fid not in partial_facts or partial_facts[fid].computable:
            raise ValueError(f"{ablation['ablation_id']} {fid} should be incomputable in the partial arm")
    for fid in expected["partial_absent_fact_ids"]:
        if fid in partial_facts:
            raise ValueError(f"{ablation['ablation_id']} {fid} should be absent in the partial arm")


def dataset_identity(manifest):
    return {key: manifest[key] for key in ("version", "cases_sha256", "ablations_sha256")}


def _fact_index(result):
    return {f["fact_id"]: f for f in result.get("facts", [])}


def score_insight(case, result, arm):
    """Independent per-dimension score; never trusts the production validator."""
    expected = case["expected"]
    facts = _fact_index(result)
    actual_ids = set(facts)

    fact_drift = []
    for fid, want in expected["facts"].items():
        got = facts.get(fid)
        if got is None:
            fact_drift.append({"fact_id": fid, "issue": "missing"})
        elif got.get("computable") != want["computable"] or got.get("fact_type") != want["fact_type"]:
            fact_drift.append({"fact_id": fid, "issue": "computable_or_type",
                               "expected_computable": want["computable"], "actual_computable": got.get("computable")})
    unexpected_facts = sorted(actual_ids - set(expected["facts"]))

    incomputable = {fid for fid, f in facts.items() if not f.get("computable")}
    forbidden_facts = set(expected.get("forbidden_fact_ids", []))
    forbidden_predicates = set(expected.get("forbidden_predicates", []))
    forbidden_kinds = set(expected.get("forbidden_claim_kinds", []))

    claim_ref_errors, forbidden_conclusions, covered_types = [], [], set()
    for claim in result.get("claims", []):
        fact_ids = list(claim.get("fact_ids") or [])
        for fid in fact_ids:
            if fid not in facts:
                claim_ref_errors.append({"claim_id": claim.get("claim_id"), "fact_id": fid, "issue": "unknown"})
            elif fid in incomputable and claim.get("kind") in VERIFIABLE_FACT_TYPES:
                claim_ref_errors.append({"claim_id": claim.get("claim_id"), "fact_id": fid,
                                         "issue": "cites_incomputable_evidence"})
        hit_forbidden = fact_ids and (set(fact_ids) & forbidden_facts)
        if claim.get("predicate") in forbidden_predicates:
            forbidden_conclusions.append({"claim_id": claim.get("claim_id"), "reason": claim.get("predicate")})
        elif claim.get("kind") in forbidden_kinds:
            forbidden_conclusions.append({"claim_id": claim.get("claim_id"), "reason": claim.get("kind")})
        elif hit_forbidden:
            forbidden_conclusions.append({"claim_id": claim.get("claim_id"),
                                          "reason": "forbidden_fact:" + ",".join(sorted(set(fact_ids) & forbidden_facts))})
        if claim.get("kind") in VERIFIABLE_FACT_TYPES:
            covered_types.update(facts[fid]["fact_type"] for fid in fact_ids if fid in facts)

    required = list(expected.get("must_cover_fact_types", []))
    covered = [ft for ft in required if ft in covered_types]
    missing_types = [ft for ft in required if ft not in covered_types]

    want_action = expected["action"]
    actions = result.get("next_actions", [])
    matched = any(a.get("capability_id") == want_action["capability_id"]
                  and bool(a.get("executable")) == want_action["executable"] for a in actions)
    action = {"expected_capability": want_action["capability_id"], "expected_executable": want_action["executable"],
              "matched": matched,
              "observed": [{"capability_id": a.get("capability_id"), "executable": a.get("executable")} for a in actions]}

    return {
        "arm": arm, "case_id": case["case_id"], "scenario": case["scenario"],
        "status": result.get("status"), "degradation_reason": result.get("degradation_reason"),
        "coverage": {"required": required, "covered": covered, "missing": missing_types,
                     "numerator": len(covered), "denominator": len(required)},
        "fact_drift": fact_drift, "unexpected_facts": unexpected_facts,
        "claim_ref_errors": claim_ref_errors, "forbidden_conclusions": forbidden_conclusions,
        "action": action,
        "claim_count": len(result.get("claims", [])),
        "ok": not fact_drift and not claim_ref_errors and not forbidden_conclusions and matched,
    }


def score_ablation(ablation, full_result, partial_result):
    """Group B: one context variable removed; the removed evidence must not be asserted."""
    expected = ablation["expected"]
    full_facts = _fact_index(full_result)
    partial_facts = _fact_index(partial_result)

    missing_full = [fid for fid in expected["full_computable"]
                    if fid not in full_facts or not full_facts[fid].get("computable")]
    partial_not_incomputable = [fid for fid in expected["partial_incomputable"]
                                if fid not in partial_facts or partial_facts[fid].get("computable")]
    partial_present = [fid for fid in expected["partial_absent_fact_ids"] if fid in partial_facts]

    def _fabrications(result, facts):
        out = []
        for claim in result.get("claims", []):
            if claim.get("kind") not in VERIFIABLE_FACT_TYPES:
                continue
            for fid in claim.get("fact_ids") or []:
                if fid in facts and not facts[fid].get("computable"):
                    out.append({"claim_id": claim.get("claim_id"), "fact_id": fid})
        return out

    full_fabrications = _fabrications(full_result, full_facts)
    partial_fabrications = _fabrications(partial_result, partial_facts)
    return {
        "ablation_id": ablation["ablation_id"], "scenario": ablation["scenario"],
        "full": {"status": full_result.get("status"), "claims": len(full_result.get("claims", [])),
                 "missing_full_computable": missing_full, "fabrications": full_fabrications},
        "partial": {"status": partial_result.get("status"), "claims": len(partial_result.get("claims", [])),
                    "partial_not_incomputable": partial_not_incomputable,
                    "partial_present_when_absent": partial_present, "fabrications": partial_fabrications},
        "ok": not missing_full and not partial_not_incomputable and not partial_present
              and not full_fabrications and not partial_fabrications,
    }
