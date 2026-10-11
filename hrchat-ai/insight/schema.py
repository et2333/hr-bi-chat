"""Strict insight contracts. Dates are half-open like analysis_contract.

Java camelCase maps to snake_case here. Report contracts stay out of S6 Claim enums.
"""
from __future__ import annotations

from datetime import date
from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MetricKind(str, Enum):
    STOCK = "stock"
    FLOW = "flow"
    RATIO = "ratio"


class FactType(str, Enum):
    ABSOLUTE_CHANGE = "absolute_change"
    RELATIVE_CHANGE = "relative_change"
    POINT_COMPARE = "point_compare"
    PERCENTAGE_POINT_CHANGE = "percentage_point_change"
    RANK_SHARE = "rank_share"
    PERIOD_OVER_PERIOD = "period_over_period"
    TIED_EXTREME = "tied_extreme"
    CONTINUOUS_TREND = "continuous_trend"
    CROSS_PERIOD_SUM = "cross_period_sum"
    DEPARTMENT_CONTRIBUTION = "department_contribution"


class InsightStatus(str, Enum):
    COMPLETE = "complete"
    DEGRADED = "degraded"
    INSUFFICIENT = "insufficient"
    FAILED = "failed"
    LEGACY = "legacy"


class ClaimKind(str, Enum):
    FACT = "fact"
    STATISTICAL_CONTRIBUTION = "statistical_contribution"
    LIMITATION = "limitation"
    NEXT_CHECK = "next_check"


class ValidationErrorKind(str, Enum):
    SCHEMA = "schema"
    BAD_FACT_REF = "bad_fact_ref"
    OUT_OF_SCOPE = "out_of_scope"
    UNIT_MISMATCH = "unit_mismatch"
    UNSUPPORTED_ACTION = "unsupported_action"
    MISSING_EVIDENCE = "missing_evidence"
    BAD_PREDICATE = "bad_predicate"
    BAD_ACTION_PARAMS = "bad_action_params"


class Period(Contract):
    start: date
    end: date

    @model_validator(mode="after")
    def valid_range(self):
        if not 0 < (self.end - self.start).days <= 366:
            raise ValueError("period must contain 1..366 days; end is exclusive")
        return self


class SeriesItem(Contract):
    name: str = ""
    data: list[float | None] = Field(default_factory=list)


class GroupRow(Contract):
    group_id: str = Field(min_length=1, max_length=100)
    group_name: str = Field(min_length=1, max_length=200)
    value: float


class DepartmentRow(Contract):
    org_id: StrictInt
    org_name: str = Field(min_length=1, max_length=200)
    baseline_count: StrictInt = Field(ge=0)
    current_count: StrictInt = Field(ge=0)


class InsightContext(Contract):
    schema_version: Literal["1"] = "1"
    report_id: str = Field(min_length=1, max_length=100)
    component_id: str | None = Field(default=None, max_length=100)
    metric_code: str = Field(min_length=1, max_length=100)
    metric_version: str = Field(min_length=1, max_length=100)
    metric_name: str = ""
    report_name: str = ""
    unit: str = Field(min_length=1, max_length=40)
    metric_kind: MetricKind
    current_period: Period
    baseline_period: Period | None = None
    effective_org_ids: list[StrictInt] = Field(default_factory=list, max_length=500)
    data_version: str = Field(min_length=1, max_length=100)
    view_key: str = Field(min_length=1, max_length=500)
    scope_ref: str = Field(min_length=1, max_length=500)
    numerator_metric_code: str | None = None
    denominator_metric_code: str | None = None

    @model_validator(mode="after")
    def distinct_orgs(self):
        if len(set(self.effective_org_ids)) != len(self.effective_org_ids):
            raise ValueError("duplicate organisation")
        if self.baseline_period is not None and self.baseline_period == self.current_period:
            raise ValueError("comparison periods must differ")
        return self


class Snapshot(Contract):
    snapshot_id: str = Field(min_length=1, max_length=100)
    data_version: str = Field(min_length=1, max_length=100)
    view_key: str = Field(min_length=1, max_length=500)
    categories: list[str] = Field(default_factory=list, max_length=120)
    series: list[SeriesItem] = Field(default_factory=list, max_length=20)
    groups: list[GroupRow] | None = None
    groups_truncated: bool = False
    groups_total: float | None = None
    departments: list[DepartmentRow] | None = None
    continuity_gaps: list[str] = Field(default_factory=list, max_length=120)

    @field_validator("series", mode="before")
    @classmethod
    def coerce_series(cls, value):
        if not isinstance(value, list):
            return value
        out = []
        for item in value:
            if isinstance(item, dict) and "data" in item:
                out.append(item)
            else:
                raise ValueError("series items must be objects with data")
        return out


class Fact(Contract):
    fact_id: str = Field(min_length=1, max_length=120)
    fact_type: FactType
    label: str = ""
    value: float | None = None
    unit: str | None = None
    period_ref: str | None = None
    scope_ref: str | None = None
    basis: str = ""
    snapshot_id: str = Field(min_length=1, max_length=100)
    computable: bool = True
    preferred: bool = False
    incomputable_reason: str | None = None
    tied_categories: list[str] = Field(default_factory=list)
    extreme_kind: Literal["max", "min"] | None = None
    groups_truncated: bool | None = None
    represents_full_population: bool | None = None
    contribution_kind: Literal["net_rate", "offset", "department_delta"] | None = None
    category: str | None = None
    related_fact_ids: list[str] = Field(default_factory=list)


class FactBundle(Contract):
    snapshot_id: str
    data_version: str
    view_key: str
    metric_code: str
    metric_kind: MetricKind
    unit: str
    facts: list[Fact] = Field(default_factory=list)


class InsightClaim(Contract):
    claim_id: str = Field(min_length=1, max_length=64)
    kind: ClaimKind
    text: str = Field(default="", max_length=2000)
    fact_ids: list[str] = Field(default_factory=list, max_length=8)
    scope_ref: str | None = None
    predicate: str | None = None
    unit: str | None = None


class NextAction(Contract):
    action_id: str = Field(min_length=1, max_length=64)
    capability_id: str = Field(min_length=1, max_length=100)
    question: str = Field(default="", max_length=500)
    params: dict[str, Any] = Field(default_factory=dict)
    missing_params: list[str] = Field(default_factory=list)
    executable: bool = False


class InsightDraft(Contract):
    selected_fact_ids: list[str] = Field(default_factory=list, max_length=20)
    claims: list[InsightClaim] = Field(default_factory=list, max_length=20)
    limitations: list[str] = Field(default_factory=list, max_length=20)
    next_actions: list[NextAction] = Field(default_factory=list, max_length=10)


class InsightPoint(Contract):
    type: str
    label: str


class InsightResult(Contract):
    report_name: str = ""
    metric_name: str = ""
    summary: str = ""
    points: list[InsightPoint] = Field(default_factory=list)
    facts: list[Fact] = Field(default_factory=list)
    status: InsightStatus = InsightStatus.DEGRADED
    degradation_reason: str | None = None
    next_actions: list[NextAction] = Field(default_factory=list)
    data_version: str | None = None
    view_key: str | None = None
    limitations: list[str] = Field(default_factory=list)
    claims: list[InsightClaim] = Field(default_factory=list)


class ValidationIssue(Contract):
    kind: ValidationErrorKind
    message: str
    fixable: bool
    claim_id: str | None = None
    action_id: str | None = None


class ValidationResult(Contract):
    ok: bool
    errors: list[ValidationIssue] = Field(default_factory=list)
    accepted_claims: list[InsightClaim] = Field(default_factory=list)
    accepted_actions: list[NextAction] = Field(default_factory=list)


class InsightRequest(Contract):
    """Rich path requires context+snapshot; legacy uses report_name/summary."""

    context: InsightContext | None = None
    snapshot: Snapshot | None = None
    draft: InsightDraft | None = None
    report_name: str | None = None
    metric_name: str | None = None
    summary: dict[str, Any] | None = None

    def is_rich(self) -> bool:
        return self.context is not None and self.snapshot is not None

    @model_validator(mode="after")
    def version_alignment(self):
        if self.context is not None and self.snapshot is not None:
            if self.context.data_version != self.snapshot.data_version:
                raise ValueError("context/snapshot data_version mismatch")
            if self.context.view_key != self.snapshot.view_key:
                raise ValueError("context/snapshot view_key mismatch")
        return self
