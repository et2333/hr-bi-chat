"""Typed, bounded inputs and deterministic evidence validation for S6.

Dates are half-open. Department membership is the event's org_key, never the
sum of overlapping organisation subtrees. Missing data is not a zero count.
"""
from __future__ import annotations

from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Period(Contract):
    start: date
    end: date

    @model_validator(mode="after")
    def valid_range(self):
        if not 0 < (self.end - self.start).days <= 366:
            raise ValueError("period must contain 1..366 days; end is exclusive")
        return self


class AnalysisContext(Contract):
    schema_version: Literal["1"] = "1"
    task_id: str = Field(min_length=1, max_length=100)
    source_ask_id: str = Field(min_length=1, max_length=100)
    source_turn_id: str = Field(min_length=1, max_length=100)
    tenant_no: str = Field(min_length=1, max_length=100)
    metric_code: Literal["leave_count"]
    metric_version: str = Field(min_length=1)
    unit: Literal["人"]
    current_period: Period
    baseline_period: Period
    effective_org_ids: list[StrictInt] = Field(min_length=1, max_length=500)
    data_version: str = Field(min_length=1)
    scope_ref: str = Field(min_length=1)

    @model_validator(mode="after")
    def distinct_orgs(self):
        if len(set(self.effective_org_ids)) != len(self.effective_org_ids):
            raise ValueError("duplicate organisation")
        if self.current_period == self.baseline_period:
            raise ValueError("comparison periods must differ")
        return self


class AnalysisRequest(Contract):
    analysis_context: AnalysisContext
    invocation_id: str = Field(min_length=1, max_length=100)
    tool_context_token: str = Field(min_length=1, max_length=8192)
    mode: Literal["deterministic", "single", "dual"] = "dual"


class AnalysisPlan(Contract):
    method: Literal["department_contribution"]
    steps: list[Literal["compare_totals", "department_delta", "check_closure"]] = Field(min_length=3, max_length=3)

    @model_validator(mode="after")
    def required_steps(self):
        if set(self.steps) != {"compare_totals", "department_delta", "check_closure"}:
            raise ValueError("all required checks must be present")
        return self


class Claim(Contract):
    claim_id: str = Field(min_length=1, max_length=64)
    kind: Literal["fact", "statistical_contribution", "unverified_hypothesis", "next_check"]
    evidence_ids: list[str] = Field(max_length=4)
    fact_id: str | None = Field(default=None, description="仅fact/statistical_contribution填写；贡献仅用department:ID，overall用fact。其他类型为null。")
    hypothesis: Literal["timing_concentration", "business_cause_unknown"] | None = Field(
        default=None, description="仅unverified_hypothesis填写，此时fact_id和next_check必须为null。")
    next_check: Literal["daily_counts", "consult_hr_records"] | None = Field(
        default=None, description="仅next_check类型填写，此时fact_id和hypothesis必须为null。建议与假设须拆成两条claim。")


class SupplementNeed(Contract):
    """A checkable evidence gap, not unrestricted model-generated justification."""
    claim_id: str = Field(min_length=1, max_length=64,
                          description="需核查的timing_concentration假设claim_id；不能引用总数或部门贡献冒充每日证据缺口。")
    reason: Literal["verify_timing_concentration"]
    required_fact_ids: list[Literal["daily_peak:current", "daily_peak:baseline"]] = Field(
        min_length=1, max_length=2, description="补查后用于核查日期集中假设的每日事实；不用于证明真实离职原因。")

    @model_validator(mode="after")
    def distinct_facts(self):
        if len(set(self.required_fact_ids)) != len(self.required_fact_ids):
            raise ValueError("duplicate required fact")
        return self


class Claims(Contract):
    claims: list[Claim] = Field(min_length=1, max_length=20)
    request_evidence: Literal["daily_counts"] | None = Field(default=None,
        description="默认null。仅确有时间分布待核查项且supplement_available=true时请求daily_counts；补查后必须null。")
    supplement_need: SupplementNeed | None = Field(default=None,
        description="请求补查时填写具体证据缺口；不请求时为null。部门贡献可由已有部门证据回答，不默认补查。")

    @model_validator(mode="after")
    def unique_ids(self):
        if len({c.claim_id for c in self.claims}) != len(self.claims):
            raise ValueError("duplicate claim id")
        return self


class ReviewResult(Contract):
    decision: Literal["accept", "request_evidence", "insufficient"]
    checked_claim_ids: list[str] = Field(max_length=20)
    drop_claim_ids: list[str] = Field(default_factory=list, max_length=20)
    evidence_request: Literal["daily_counts"] | None = Field(default=None,
        description="仅request_evidence决策填写；补查后必须null，证据足够则accept，仍不足则insufficient。")
    supplement_need: SupplementNeed | None = Field(default=None,
        description="请求时关联未被删除的时间分布假设和缺失事实；其余决策为null。不能仅因Analyst请求就批准。")
    issues: list[Literal["unsupported_claim", "missing_daily_evidence", "cause_unverified", "evidence_insufficient"]] = Field(default_factory=list, max_length=4)

    @model_validator(mode="after")
    def actionable_request(self):
        if (self.decision == "request_evidence") != (self.evidence_request is not None):
            raise ValueError("request_evidence must name an allowed query")
        return self


class EvidenceError(ValueError):
    pass


def _count(value):
    if type(value) is not int or value < 0:
        raise EvidenceError("missing_or_invalid_count")
    return value


def validate_evidence(context: AnalysisContext, raw: dict, *, detail="department") -> dict:
    """Fail closed on snapshot/scope/unit drift before using any returned numbers."""
    expected = context.model_dump(mode="json")
    if not isinstance(raw, dict) or raw.get("status") != "complete":
        raise EvidenceError("incomplete_evidence")
    for key in ("metric_code", "metric_version", "unit", "data_version", "current_period", "baseline_period"):
        if raw.get(key) != expected[key]:
            raise EvidenceError("evidence_mismatch:" + key)
    orgs = raw.get("effective_org_ids")
    if (not isinstance(orgs, list) or any(type(i) is not int for i in orgs)
            or len(orgs) != len(set(orgs)) or set(orgs) != set(context.effective_org_ids)):
        raise EvidenceError("evidence_mismatch:scope")
    if raw.get("quality_issues") != []:
        raise EvidenceError("data_quality_insufficient")
    current, baseline = _count(raw.get("current_total")), _count(raw.get("baseline_total"))
    rows = raw.get("departments")
    if not isinstance(rows, list) or len(rows) != len(orgs):
        raise EvidenceError("incomplete_departments")
    seen = set()
    departments = []
    for row in rows:
        if not isinstance(row, dict) or type(row.get("org_id")) is not int:
            raise EvidenceError("invalid_department")
        org_id = row["org_id"]
        if org_id in seen or org_id not in orgs:
            raise EvidenceError("overlapping_or_unknown_department")
        seen.add(org_id)
        name = row.get("org_name")
        if not isinstance(name, str) or not name.strip() or len(name) > 200:
            raise EvidenceError("invalid_department_name")
        c, b = _count(row.get("current_count")), _count(row.get("baseline_count"))
        departments.append({"org_id": org_id, "org_name": name, "current_count": c,
                            "baseline_count": b, "delta": c - b})
    if sum(r["current_count"] for r in departments) != current or sum(r["baseline_count"] for r in departments) != baseline:
        raise EvidenceError("totals_do_not_close")
    facts = {"overall": {"current": current, "baseline": baseline, "delta": current - baseline}}
    for r in departments:
        facts[f"department:{r['org_id']}"] = r
    if detail == "daily":
        daily = raw.get("daily")
        if not isinstance(daily, list):
            raise EvidenceError("missing_daily_evidence")
        totals, seen_days = {"current": 0, "baseline": 0}, set()
        for row in daily:
            if not isinstance(row, dict) or row.get("period") not in totals:
                raise EvidenceError("invalid_daily_evidence")
            period = row["period"]
            try:
                day = date.fromisoformat(row["date"])
            except (ValueError, TypeError, KeyError):
                raise EvidenceError("invalid_daily_date") from None
            window = context.current_period if period == "current" else context.baseline_period
            if not window.start <= day < window.end or (period, day) in seen_days:
                raise EvidenceError("invalid_daily_range")
            seen_days.add((period, day))
            totals[period] += _count(row.get("count"))
        if totals != {"current": current, "baseline": baseline}:
            raise EvidenceError("daily_totals_do_not_close")
        for period in totals:
            candidates = [r for r in daily if r["period"] == period]
            if candidates:
                peak = max(candidates, key=lambda r: (r["count"], r["date"]))
                facts[f"daily_peak:{period}"] = {"date": peak["date"], "count": peak["count"], "total": totals[period]}
    return {"current_total": current, "baseline_total": baseline, "delta": current - baseline,
            "departments": sorted(departments, key=lambda r: (-abs(r["delta"]), r["org_id"])),
            "closure_verified": True, "facts": facts}


def check_claims(claims: Claims, facts: dict, evidence_ids: set[str], evidence_facts: dict | None = None) -> list[str]:
    """Return invalid IDs; the reviewer may remove them but cannot validate them by vote."""
    invalid = []
    for c in claims.claims:
        ok = bool(c.evidence_ids) and set(c.evidence_ids) <= evidence_ids
        if c.kind in {"fact", "statistical_contribution"}:
            ok = ok and c.fact_id in facts and c.hypothesis is None and c.next_check is None
            if evidence_facts is not None:
                ok = ok and any(c.fact_id in evidence_facts.get(e, {}) for e in c.evidence_ids)
            if c.kind == "statistical_contribution":
                ok = ok and str(c.fact_id).startswith("department:")
        elif c.kind == "unverified_hypothesis":
            ok = ok and c.hypothesis is not None and c.fact_id is None and c.next_check is None
        else:
            ok = ok and c.next_check is not None and c.fact_id is None and c.hypothesis is None
        if not ok:
            invalid.append(c.claim_id)
    return invalid
