"""Bounded Analyst/Reviewer handoff over trusted, versioned Java evidence.

LangGraph owns branches; AgentScope owns role turns. Code validates all facts.
No demo-number fallback, arbitrary generated prose, or confidence percentage.
"""
from __future__ import annotations

import asyncio
import time
import uuid
from typing import TypedDict

from adapters.mcp_client import McpBusinessError
from adapters.model_usage import summarize_calls
from adapters.query_budget import ACTIVE_QUERY_BUDGET, QueryBudget, QueryBudgetExceeded
from agentscope_teams.analysis_contract import (
    AnalysisPlan, AnalysisRequest, Claim, Claims, EvidenceError, ReviewResult,
    check_claims, validate_evidence,
)


class FlowState(TypedDict, total=False):
    route: str


class AttributionTeam:
    def __init__(self, adapter, tool_client, request: AnalysisRequest, *, budget=None):
        self.adapter, self.tools, self.request = adapter, tool_client, request
        self.context = request.analysis_context
        self.budget = budget or QueryBudget(timeout_seconds=60, max_model_calls=4, max_mcp_attempts=6)
        self.records, self.evidence, self.reviews, self.trace = [], [], [], []
        self.computed = None
        self.claims = None
        self.plan = None
        self.supplemented = False
        self.unresolved = []
        self.status = "COMPLETED"
        self.emit = None

    async def event(self, name, payload):
        await self.emit({"event": name, "payload": {"task_id": self.context.task_id, **payload}})

    async def role(self, name, schema, data):
        from agentscope_teams.analysis_roles import role_reply
        await self.event("PLAN_UPDATE", {"stage": name, "message": {
            "AnalystPlan": "制定部门贡献分析计划", "Analyst": "整理统计事实和待核查项",
            "Reviewer": "核对证据与结论边界"}[name]})
        role_name = "Analyst" if name == "AnalystPlan" else name
        result = await role_reply(self.adapter, self.budget, self.records, self.request, role_name, schema, data)
        self.trace.append({"role": role_name, "stage": name, "schema": schema.__name__, "output": result.model_dump(mode="json")})
        return result

    async def fetch(self, detail):
        self.budget.remaining()
        tool_id = "tc_" + uuid.uuid4().hex[:16]
        await self.event("TOOL_CALL_START", {"tool": "analysis_evidence", "tool_call_id": tool_id,
                                            "detail": detail, "message": "读取两期部门聚合" if detail == "department" else "补查两期每日聚合"})
        raw = await self.tools.tools_call("analysis_evidence", {"task_id": self.context.task_id, "detail": detail},
                                         {"tool_context_token": self.request.tool_context_token,
                                          "invocation_id": self.request.invocation_id, "tool_call_id": tool_id})
        computed = validate_evidence(self.context, raw, detail=detail)
        if self.computed and any(computed[k] != self.computed[k] for k in ("current_total", "baseline_total", "departments")):
            raise EvidenceError("supplement_changed_snapshot")
        evidence_id = "ev_department" if detail == "department" else "ev_daily"
        item = {"evidence_id": evidence_id, "tool_call_id": tool_id, "status": "verified",
                "metric_code": self.context.metric_code, "metric_version": self.context.metric_version,
                "data_version": self.context.data_version, "unit": self.context.unit,
                "scope_ref": self.context.scope_ref, "effective_org_ids": self.context.effective_org_ids,
                "query": {"detail": detail, "current_period": self.context.current_period.model_dump(mode="json"),
                          "baseline_period": self.context.baseline_period.model_dump(mode="json")},
                "result": computed}
        if detail == "daily":
            item["daily"] = [{k: r[k] for k in ("period", "date", "count")} for r in raw["daily"]]
        self.evidence.append(item)
        self.computed = computed
        await self.event("TOOL_CALL_END", {"tool": "analysis_evidence", "tool_call_id": tool_id,
                                          "evidence_id": evidence_id, "status": "verified"})

    def handoff(self):
        return {"plan": self.plan.model_dump(), "evidence": self.evidence,
                "facts": self.computed["facts"] if self.computed else {},
                "claims": self.claims.model_dump() if self.claims else None,
                "invalid_claim_ids": self.invalid_claims(), "supplement_available": not self.supplemented,
                "allowed_supplement": "daily_counts" if not self.supplemented else None}

    def invalid_claims(self):
        if not self.claims:
            return []
        return check_claims(self.claims, self.computed["facts"], {e["evidence_id"] for e in self.evidence},
                            {e["evidence_id"]: e["result"]["facts"] for e in self.evidence})

    async def plan_node(self, state):
        data = {k: v for k, v in self.context.model_dump(mode="json").items()
                if k not in {"tenant_no", "scope_ref", "source_ask_id", "source_turn_id", "task_id"}}
        self.plan = (AnalysisPlan(method="department_contribution", steps=["compare_totals", "department_delta", "check_closure"])
                     if self.request.mode == "deterministic" else await self.role("AnalystPlan", AnalysisPlan, data))
        return {}

    async def collect_node(self, state):
        await self.fetch("department")
        return {}

    async def draft_node(self, state):
        if self.request.mode == "deterministic":
            self.claims = Claims(claims=[Claim(claim_id="overall", kind="fact", fact_id="overall", evidence_ids=["ev_department"])])
            return {"route": "render"}
        self.claims = await self.role("Analyst", Claims, self.handoff())
        if self.request.mode == "dual":
            return {"route": "review"}
        if self.invalid_claims():
            self.status = "PARTIAL"
            self.unresolved.append("unsupported_claim")
            invalid = set(self.invalid_claims())
            self.claims.claims = [c for c in self.claims.claims if c.claim_id not in invalid]
        if self.claims.request_evidence:
            if self.supplemented:
                self.status = "PARTIAL"
                self.unresolved.append("supplement_limit_reached")
            else:
                return {"route": "supplement"}
        return {"route": "render"}

    async def review_node(self, state):
        review = await self.role("Reviewer", ReviewResult, self.handoff())
        ids = {c.claim_id for c in self.claims.claims}
        if set(review.checked_claim_ids) != ids or not set(review.drop_claim_ids) <= ids:
            raise EvidenceError("review_did_not_cover_claims")
        self.reviews.append(review.model_dump(mode="json"))
        self.claims.claims = [c for c in self.claims.claims if c.claim_id not in review.drop_claim_ids]
        if self.invalid_claims():
            self.status = "PARTIAL"
            self.unresolved.append("unsupported_claim")
            invalid = set(self.invalid_claims())
            self.claims.claims = [c for c in self.claims.claims if c.claim_id not in invalid]
        if review.decision == "request_evidence":
            if not self.supplemented:
                return {"route": "supplement"}
            self.status = "PARTIAL"
            self.unresolved.append("supplement_limit_reached")
        elif review.decision == "insufficient":
            self.status = "PARTIAL"
            self.unresolved.append("review_evidence_insufficient")
        if not self.claims.claims:
            self.status = "PARTIAL"
            self.unresolved.append("no_supported_claims")
        return {"route": "render"}

    async def supplement_node(self, state):
        self.supplemented = True
        await self.fetch("daily")
        return {"route": "review" if self.request.mode == "dual" else "draft"}

    async def render_node(self, state):
        return {}

    def result(self):
        c = self.computed
        safe_claims = [x.model_dump(mode="json") for x in self.claims.claims] if self.claims else []
        invalid = set(self.invalid_claims()) if self.claims and c else set()
        safe_claims = [x for x in safe_claims if x["claim_id"] not in invalid]
        return {"schema_version": "1", "task_id": self.context.task_id, "source_ask_id": self.context.source_ask_id,
                "status": self.status, "mode": self.request.mode,
                "scope": self.context.model_dump(mode="json", exclude={"tenant_no"}),
                "summary": {k: c[k] for k in ("current_total", "baseline_total", "delta", "closure_verified")} if c else None,
                "contributions": c["departments"] if c else [], "facts": c["facts"] if c else {},
                "claims": safe_claims, "review": self.reviews, "evidence": self.evidence,
                "data_quality": ["按离职事件所属部门拆解；不包含完整历史组织关系。", "统计贡献不等于真实离职原因。"],
                "unresolved": list(dict.fromkeys(self.unresolved)), "disclaimer": "辅助分析，仅供参考",
                "usage": {**self.budget.evidence(), **summarize_calls(self.records), "calls": self.records},
                "trace": self.trace}

    async def run(self, emit):
        self.emit = emit
        started = time.perf_counter()
        token = ACTIVE_QUERY_BUDGET.set(self.budget)
        try:
            from langgraph.graph import StateGraph, START, END
            graph = StateGraph(FlowState)
            for name in ("plan", "collect", "draft", "review", "supplement", "render"):
                graph.add_node(name, getattr(self, name + "_node"))
            graph.add_edge(START, "plan")
            graph.add_edge("plan", "collect")
            graph.add_edge("collect", "draft")
            for name in ("draft", "review", "supplement"):
                graph.add_conditional_edges(name, lambda state: state["route"])
            graph.add_edge("render", END)
            await self.event("PLAN_UPDATE", {"stage": "START", "message": "开始分析已确认的两期离职人数"})
            remaining = self.budget.remaining()
            await asyncio.wait_for(graph.compile().ainvoke({}), remaining)
        except asyncio.CancelledError:
            self.status = "CANCELLED"
            self.unresolved.append("cancelled")
        except McpBusinessError:
            # A tool denial can invalidate previously authorised evidence as well.
            self.computed, self.evidence, self.claims = None, [], None
            self.status = "FAILED"
            self.unresolved.append("tool_access_or_execution_failed")
        except (QueryBudgetExceeded, TimeoutError) as exc:
            self.status = "PARTIAL" if self.computed else "FAILED"
            self.unresolved.append(getattr(exc, "reason", "task_deadline_exceeded"))
        except Exception as exc:
            self.status = "PARTIAL" if self.computed else "FAILED"
            self.unresolved.append(str(exc) if isinstance(exc, EvidenceError) else "analysis_execution_failed")
        finally:
            ACTIVE_QUERY_BUDGET.reset(token)
        output = self.result()
        output["usage"]["elapsed_ms"] = int((time.perf_counter() - started) * 1000)
        await self.event("FINAL" if self.status == "COMPLETED" else "ERROR", output)
        return output
