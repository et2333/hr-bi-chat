"""Independent insight scenarios: frozen dataset loading and arm scoring.

The expected facts, forbidden conclusions and actions are hand-audited from
metric semantics. ``verify_case_facts`` re-checks them against the production
FactBuilder only to detect drift; scoring never imports the production result
validator or prompt.

``SCORING_VERSION`` versions the rule set. The scorer checks values (with
tolerance), units, periods, scopes, source version identity, fact references,
required coverage, status, limitations and action parameters against the
independent oracle (``evals.insight_oracle``) and the visible narrative, not just
``fact_type``/``computable``.

``insight-scoring-3`` additionally re-derives each claim's server-rendered text
from its fact slots (``_rebuild_claim_text``) and rebuilds ``summary``/``points``
from that reconstruction, so a result whose visible text was replaced by a
fabricated sentence fails even when the facts stay correct. ``score_cross_service``
scores the Java-persisted ``GET`` payload (camelCase, ``actions``, ``source``)
after normalising its transport shape — a Python JSON round-trip does not prove
the real Java mapping kept the model body.

``insight-scoring-4`` extends the same rule to the top-level ``limitations`` list:
it is model-generated text shown on the card, so a fabricated number or
unsupported causal sentence appended there is a visible-body error too.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from evals.dataset import sha_text
from evals.insight_oracle import ORACLE_VERSION, TOLERANCE, expected_facts
from insight.fact_builder import build_fact_bundle
from insight.schema import InsightRequest

DATASET = Path(__file__).parent / "datasets/insight-scenarios-v1"
VERIFIABLE_FACT_TYPES = ("fact", "statistical_contribution")
SCORING_VERSION = "insight-scoring-4"

# Independent copy of the slot/free-text rules. A scoring pass must re-derive the visible
# text from the evidence instead of trusting the model-authored fields, so the scorer does
# not import ``insight.validator``. Kept in sync by the negative controls in
# ``tests/test_insight_scenarios.py`` (C21 for claim/points text, C24 for limitations).
_SIGNED_FACT_TYPES = {"absolute_change", "relative_change", "period_over_period",
                      "percentage_point_change"}
_NUMBER_RE = re.compile(r"\d")
_CAUSAL_WORDS = ("导致", "因为", "由于", "造成", "引起", "以致", "使得", "因此导致")
# 局限/下一步类文本中的限定表述（"由于数据缺失，无法判定…"）不算因果断言，与生产校验同口径。
_HEDGE_MARKERS = ("无法", "不能", "不可", "不足", "缺失", "缺少", "不连续", "暂", "尚", "需",
                  "建议", "难以")
_LIMITATION_KINDS = {"limitation", "next_check"}


def _causal_hit(text: str, *, hedged_ok: bool) -> bool:
    """True when unsupported causal wording is present and not excused as a hedged limitation."""
    if not any(word in text for word in _CAUSAL_WORDS):
        return False
    return not (hedged_ok and any(marker in text for marker in _HEDGE_MARKERS))


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
        verify_oracle(case)
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


def verify_oracle(case) -> None:
    """The independent oracle must cover the hand-audited fact set and agree with it."""
    oracle = expected_facts(case["context"], case["snapshot"])
    expected = case["expected"]["facts"]
    missing = sorted(set(expected) - set(oracle))
    if missing:
        raise ValueError(f"{case['case_id']} oracle gap (extend evals/insight_oracle): {missing}")
    for fid, want in expected.items():
        got = oracle[fid]
        if got["fact_type"] != want["fact_type"] or got["computable"] != want["computable"]:
            raise ValueError(
                f"{case['case_id']} {fid} oracle disagreement: "
                f"oracle={got['fact_type']}/{got['computable']} "
                f"dataset={want['fact_type']}/{want['computable']}")


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
    """Independent per-dimension score; never trusts the production validator.

    Verifies the *visible, persisted* result: fact values (with tolerance),
    units, periods, scopes, source version identity, references, required
    coverage, status, revealed limitations and action parameters.
    """
    expected = case["expected"]
    facts = _fact_index(result)
    actual_ids = set(facts)
    oracle = expected_facts(case["context"], case["snapshot"])
    status = result.get("status")
    claims = result.get("claims") or []

    fact_drift = []
    for fid, want in expected["facts"].items():
        got = facts.get(fid)
        if got is None:
            fact_drift.append({"fact_id": fid, "issue": "missing"})
        elif got.get("computable") != want["computable"] or got.get("fact_type") != want["fact_type"]:
            fact_drift.append({"fact_id": fid, "issue": "computable_or_type",
                               "expected_computable": want["computable"], "actual_computable": got.get("computable")})
    unexpected_facts = sorted(actual_ids - set(expected["facts"]))

    value_errors, unit_errors, period_errors, scope_errors = [], [], [], []
    for fid, want in oracle.items():
        got = facts.get(fid)
        if got is None or not want["computable"]:
            continue
        if not got.get("computable"):
            continue  # already reported by fact_drift when the dataset expects it
        actual_value = got.get("value")
        if actual_value is None or abs(float(actual_value) - float(want["value"])) > TOLERANCE:
            value_errors.append({"fact_id": fid, "issue": "value",
                                 "expected": want["value"], "actual": actual_value})
        if want.get("unit") is not None and got.get("unit") != want["unit"]:
            unit_errors.append({"fact_id": fid, "issue": "unit",
                                "expected": want["unit"], "actual": got.get("unit")})
        if want.get("period_ref") is not None and got.get("period_ref") != want["period_ref"]:
            period_errors.append({"fact_id": fid, "issue": "period",
                                  "expected": want["period_ref"], "actual": got.get("period_ref")})
        if got.get("scope_ref") != want["scope_ref"]:
            scope_errors.append({"fact_id": fid, "issue": "scope",
                                 "expected": want["scope_ref"], "actual": got.get("scope_ref")})

    context_errors = _context_errors(case, result)

    incomputable = {fid for fid, f in facts.items() if not f.get("computable")}
    forbidden_facts = set(expected.get("forbidden_fact_ids", []))
    forbidden_predicates = set(expected.get("forbidden_predicates", []))
    forbidden_kinds = set(expected.get("forbidden_claim_kinds", []))

    claim_ref_errors, forbidden_conclusions, covered_types = [], [], set()
    for claim in claims:
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

    visible_errors = _visible_errors(result, claims)
    limitation_errors = _limitation_errors(facts, result, claims)
    action, action_param_errors = _score_action(case, result, expected["action"])

    degraded = status != "complete"
    return {
        "arm": arm, "case_id": case["case_id"], "scenario": case["scenario"],
        "scoring_version": SCORING_VERSION, "oracle_version": ORACLE_VERSION,
        "status": status, "degraded": degraded, "degradation_reason": result.get("degradation_reason"),
        "coverage": {"required": required, "covered": covered, "missing": missing_types,
                     "numerator": len(covered), "denominator": len(required)},
        "fact_drift": fact_drift, "unexpected_facts": unexpected_facts,
        "value_errors": value_errors, "unit_errors": unit_errors,
        "period_errors": period_errors, "scope_errors": scope_errors,
        "context_errors": context_errors, "visible_errors": visible_errors,
        "limitation_errors": limitation_errors, "action_param_errors": action_param_errors,
        "claim_ref_errors": claim_ref_errors, "forbidden_conclusions": forbidden_conclusions,
        "action": action,
        "claim_count": len(claims),
        "ok": (
            not degraded
            and not fact_drift and not unexpected_facts
            and not value_errors and not unit_errors and not period_errors and not scope_errors
            and not context_errors and not visible_errors and not limitation_errors
            and not claim_ref_errors and not forbidden_conclusions and not action_param_errors
            and not missing_types and action["matched"]
        ),
    }


def normalize_java_insight(insight) -> dict:
    """Normalise the Java GET ``InsightView`` (camelCase) into the scorer's result shape.

    A live ``Java POST → persisted → GET`` payload is not the Python in-memory shape:
    field names are camelCase, actions live under ``actions`` with ``capabilityId``/
    ``executable`` and the snapshot identity is carried on ``source``. Scoring the
    cross-service output must go through this mapping, not a Python JSON round-trip.
    """
    if not isinstance(insight, dict):
        raise ValueError("java insight view must be an object")
    actions = []
    for action in insight.get("actions") or []:
        actions.append({
            "action_id": action.get("actionId"),
            "capability_id": action.get("capabilityId"),
            "question": action.get("question") or "",
            "params": action.get("params") or {},
            "missing_params": action.get("missingParams") or [],
            "executable": bool(action.get("executable")),
        })
    source = insight.get("source") or {}
    return {
        "status": insight.get("status"),
        "summary": insight.get("summary") or "",
        "points": [{"type": p.get("type"), "label": p.get("label")} for p in insight.get("points") or []],
        "facts": insight.get("facts") or [],
        "claims": insight.get("claims") or [],
        "narrative": insight.get("narrative"),
        "limitations": insight.get("limitations") or [],
        "next_actions": actions,
        "data_version": insight.get("dataVersion"),
        "view_key": insight.get("viewKey"),
        "schema_version": insight.get("schemaVersion"),
        "snapshot_id": source.get("snapshotId"),
        "report_name": insight.get("reportName") or "",
        "metric_name": insight.get("metricName") or "",
    }


def score_cross_service(case, java_insight, arm="java") -> dict:
    """Score a Java-persisted GET payload after normalising its transport shape.

    Distinct from ``score_insight`` (Python-internal) and from any real-model run: this
    scores the *cross-service* output that actually reaches the page.
    """
    score = score_insight(case, normalize_java_insight(java_insight), arm)
    return {**score, "transport": "java_persisted_get"}


def score_transport(result) -> dict:
    """Transport/shape integrity of a (normalised) result, independent of a frozen case.

    Re-derives the visible body from the fact slots and checks every referenced fact
    exists. A live ``Java POST → persisted → GET`` payload has no frozen scenario case,
    so its visible body is scored here (the report's A4 cross-service requirement).
    Integrity only: a legitimately degraded result (no claims) has no visible-body error,
    it is not a claim of success.
    """
    facts = _fact_index(result)
    claims = result.get("claims") or []
    reference_errors = []
    for claim in claims:
        refs = list(claim.get("fact_ids") or [])
        refs += [s.get("fact_id") for s in (claim.get("segments") or []) if s.get("type") == "fact"]
        for fact_id in refs:
            if fact_id not in facts:
                reference_errors.append({"claim_id": claim.get("claim_id"), "fact_id": fact_id,
                                         "issue": "unknown"})
    visible_errors = _visible_errors(result, claims)
    return {"status": result.get("status"), "claim_count": len(claims), "fact_count": len(facts),
            "visible_errors": visible_errors, "reference_errors": reference_errors,
            "ok": not visible_errors and not reference_errors}


def _context_errors(case, result) -> list[dict]:
    """The result must carry the requested source identity, not a drifting version."""
    context, snapshot = case["context"], case["snapshot"]
    errors = []
    for key, expected in (("data_version", context["data_version"]),
                          ("view_key", context["view_key"]),
                          ("snapshot_id", snapshot["snapshot_id"])):
        if result.get(key) != expected:
            errors.append({"field": key, "expected": expected, "actual": result.get(key)})
    if str(result.get("schema_version")) != "2":
        errors.append({"field": "schema_version", "expected": "2", "actual": result.get("schema_version")})
    return errors


def _slot_text(fact, field) -> str | None:
    """Independently render one whitelisted fact slot.

    Returns ``None`` when a required value slot cannot be rendered. Mirrors the contract
    ("numbers/dates come from a fact slot") without importing the production validator.
    """
    field = field or "value"
    if field == "unit":
        return fact.get("unit") or ""
    if field == "period_label":
        return fact.get("period_ref") or ""
    if field == "scope_label":
        return fact.get("scope_ref") or ""
    if field == "category":
        # tied_extreme 的类目在 tied_categories，生产渲染与这里必须同口径。
        return fact.get("category") or "、".join(fact.get("tied_categories") or [])
    value = fact.get("value")
    if value is None:
        return None
    fact_type = fact.get("fact_type")
    if fact.get("unit") in {"ratio", "share"}:
        return f"{value:+.1%}" if fact_type in _SIGNED_FACT_TYPES else f"{value:.1%}"
    if fact_type in _SIGNED_FACT_TYPES:
        return f"{value:+g}"
    return f"{value:g}"


def _free_text_error(text, claim) -> dict | None:
    """Free text may not type numbers/dates or unsupported causality."""
    if _NUMBER_RE.search(text):
        return {"issue": "fabricated_number_in_text", "claim_id": claim.get("claim_id"), "text": text}
    if _causal_hit(text, hedged_ok=claim.get("kind") in _LIMITATION_KINDS):
        return {"issue": "unsupported_causality_in_text", "claim_id": claim.get("claim_id"), "text": text}
    return None


def _rebuild_claim_text(claim, facts) -> tuple[str | None, dict | None]:
    """Re-derive a claim's visible text from its segments and the verified facts.

    Numbers/dates/units must come from fact slots, so text typed by the model cannot
    smuggle a value that never existed in the facts. Returns ``(text, error)``.
    """
    segments = claim.get("segments") or []
    if not segments:
        text = claim.get("text") or ""
        return text, _free_text_error(text, claim)
    parts: list[str] = []
    for segment in segments:
        if segment.get("type") == "text":
            text = segment.get("text") or ""
            error = _free_text_error(text, claim)
            if error is not None:
                return None, error
            parts.append(text)
            continue
        fact = facts.get(segment.get("fact_id") or "")
        if fact is None:
            return None, {"issue": "segment_unknown_fact", "claim_id": claim.get("claim_id"),
                          "fact_id": segment.get("fact_id")}
        rendered = _slot_text(fact, segment.get("field"))
        if rendered is None:
            return None, {"issue": "segment_unrenderable_slot", "claim_id": claim.get("claim_id"),
                          "fact_id": segment.get("fact_id")}
        parts.append(rendered)
    return "".join(parts), None


def _visible_errors(result, claims) -> list[dict]:
    """A complete result must expose the verified narrative, not model-typed text.

    Independently rebuilds each claim's server-rendered text from its fact slots and
    compares the visible ``summary``/``points`` against that reconstruction, so a result
    whose ``claims.text``/``summary``/``points.label`` were all replaced by a fabricated
    sentence cannot pass even when the facts, references and actions stay correct.
    """
    errors = []
    if result.get("status") != "complete":
        return errors
    # B3：顶层 limitations 同样是模型生成后直接展示的文本；保留合法事实/引用与正文、
    # 只追加一条虚构局限也应被检出，不能因为“它不在 summary 里”就当作安全。
    for text in result.get("limitations") or []:
        raw = str(text)
        if _NUMBER_RE.search(raw):
            errors.append({"issue": "limitation_typed_number", "text": raw})
        elif _causal_hit(raw, hedged_ok=True):
            errors.append({"issue": "limitation_unsupported_causality", "text": raw})
    if not claims:
        errors.append({"issue": "complete_without_claims"})
        return errors
    facts = _fact_index(result)
    by_id = {c.get("claim_id"): c for c in claims}
    rendered: dict[str, str] = {}
    for claim in claims:
        claim_id = claim.get("claim_id")
        text, error = _rebuild_claim_text(claim, facts)
        if error is not None:
            errors.append(error)
            continue
        rendered[claim_id] = text
        if claim.get("text") != text:
            errors.append({"issue": "claim_text_not_from_segments", "claim_id": claim_id,
                           "visible": claim.get("text"), "expected": text})
    narrative = result.get("narrative") or {}
    summary_id = narrative.get("summary_claim_id")
    if not summary_id:
        errors.append({"issue": "complete_without_narrative"})
    else:
        summary_claim = by_id.get(summary_id)
        if summary_claim is None:
            errors.append({"issue": "narrative_summary_missing_claim", "claim_id": summary_id})
        else:
            expected = rendered.get(summary_id, summary_claim.get("text"))
            if result.get("summary") != expected:
                errors.append({"issue": "visible_summary_not_from_narrative",
                               "visible": result.get("summary"), "expected": expected})
    visible_texts = {text for text in rendered.values() if text}
    visible_texts.update(c.get("text") for c in claims if not (c.get("segments") or []) and c.get("text"))
    for point in result.get("points") or []:
        if point.get("type") == "finding" and point.get("label") not in visible_texts:
            errors.append({"issue": "point_not_from_claim", "label": point.get("label")})
    return errors


def _limitation_errors(facts, result, claims) -> list[dict]:
    """Every incomputable fact type must be disclosed, not silently dropped."""
    blocked = {f.get("fact_type") for f in facts.values() if not f.get("computable")}
    disclosed = " ".join(str(x) for x in (result.get("limitations") or []))
    for claim in claims:
        if claim.get("kind") == "limitation":
            disclosed += " " + str(claim.get("text") or "")
    return [{"fact_type": ft, "issue": "undisclosed_incomputable"} for ft in sorted(blocked) if ft not in disclosed]


def _score_action(case, result, want_action) -> tuple[dict, list[dict]]:
    context = case["context"]
    actions = result.get("next_actions") or []
    matched = [a for a in actions if a.get("capability_id") == want_action["capability_id"]
               and bool(a.get("executable")) == want_action["executable"]]
    action = {"expected_capability": want_action["capability_id"], "expected_executable": want_action["executable"],
              "matched": bool(matched),
              "observed": [{"capability_id": a.get("capability_id"), "executable": a.get("executable")} for a in actions]}
    errors: list[dict] = []
    if want_action["executable"] and not matched:
        errors.append({"issue": "missing_executable_action", "capability_id": want_action["capability_id"]})
    for candidate in matched if want_action["executable"] else []:
        params = candidate.get("params") or {}
        checks = (
            ("metric_code", context["metric_code"], params.get("metric_code")),
            ("current_period", context["current_period"], params.get("current_period")),
            ("baseline_period", context.get("baseline_period"), params.get("baseline_period")),
            ("effective_org_ids", list(context["effective_org_ids"]), list(params.get("effective_org_ids") or [])),
        )
        for name, expected, actual in checks:
            if actual != expected:
                errors.append({"issue": "action_param", "param": name, "expected": expected, "actual": actual})
    return action, errors


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
