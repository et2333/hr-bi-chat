"""Constrained planning with one bounded S4 repair before authorized execution."""
from __future__ import annotations

from datetime import date, timedelta
import asyncio
import os
import re
import time
import uuid

from pydantic import ValidationError
from adapters.mcp_client import McpBusinessError
from adapters.model_usage import ModelCallError, ModelResult, load_prices, summarize_calls
from adapters.query_budget import QueryBudget, QueryBudgetExceeded, ACTIVE_QUERY_BUDGET
from adapters.semantic_tool_client import metric_view
from langgraph_flows.query_plan import QueryPlan, OrgScope, PlanRejected, planning_prompt, validate_plan
from langgraph_flows.query_draft import (ModelQueryDraft, draft_messages, compile_draft, DRAFT_VERSION,
                                        is_slot_only_question, FOLLOWUP_GUARD_VERSION)
from langgraph_flows.demo_data import Window, resolve_window
from langgraph_flows.query_memory import (ContextQueryDraft, memory_messages, compile_contextual,
    context_candidate, selection_draft, task_action, PERIOD_OPTIONS, MEMORY_PROMPT_VERSION)
from langgraph_flows.query_repair import REPAIR_VERSION, repair_contract, repair_messages, check_repair
from langgraph_flows.time_intent import has_time_cue


MESSAGES = {
    "metric_unavailable": "当前可用目录未提供该指标口径，暂不支持这种统计。",
    "unsupported_capability": "暂不支持这种统计、筛选或分析方式，请调整问题。",
    "unknown_organization": "组织无法识别或不在当前可用范围，请使用可用组织的完整名称。",
    "ambiguous_organization": "存在多个同名组织，请选择要查询的范围。",
    "ambiguous_metric": "请明确您希望查询的指标。",
    "missing_slots": "请补充指标、组织或统计时间等缺少的条件。",
}


def _metric_option_label(metric):
    """Short caliber hint so near-duplicate metric names are distinguishable."""
    name = metric.get("name") or metric.get("code") or ""
    definition = (metric.get("definition") or "").strip().removeprefix("口径：").strip()
    if not definition:
        return name
    short = definition if len(definition) <= 36 else definition[:35] + "…"
    return f"{name}（{short}）"


def _org_option_label(org):
    return org.get("label") or (
        f"{org.get('name', '')}（{org.get('org_code') or org.get('org_id', '')}）"
    )


def guard_known_capabilities(question, allow_analysis=False, catalog=None):
    """Known unsupported requirements take priority over asking for missing slots."""
    if "主动离职" in question:
        raise PlanRejected("metric_unavailable", MESSAGES["metric_unavailable"])
    if catalog is not None:
        # Observed manual failures: never substitute an available count for a
        # salary/cost/rate metric absent from the current authoritative catalog.
        for family in (r"薪资|薪酬|工资|薪水", r"人力成本", r"离职率"):
            if re.search(family, question) and not any(re.search(family,
                    " ".join([m["name"], *m.get("aliases", [])])) and m.get("allowed_modes") for m in catalog["metrics"]):
                raise PlanRejected("metric_unavailable", MESSAGES["metric_unavailable"])
    if re.search(r"预测|性别|职级|司龄|年龄|学历|岗位|男性|女性|男员工|女员工|同比|去年同期", question) or (
            not allow_analysis and re.search(r"为什么|原因|归因", question)):
        raise PlanRejected("unsupported_capability", MESSAGES["unsupported_capability"])


def guard_request(plan, question, metadata, context, selected, organization_choice=None):
    """Deterministic known-failure checks complement, not replace, model understanding."""
    if plan.decision in {"unsupported", "chitchat"}:
        return
    guard_known_capabilities(question, catalog=metadata)
    residual = question
    matches = set()
    names = {}
    for org in metadata["organizations"]:
        for name in [org["name"], *org.get("aliases", [])]:
            if name:
                names.setdefault(name, set()).add(org["org_id"])
    surfaces = []
    for name in sorted(names, key=len, reverse=True):
        if name in residual:
            matches.update(names[name])
            surfaces.append(name)
            residual = residual.replace(name, " ")
    for phrase in ("按部门对比", "按组织对比", "全部部门", "所有部门", "全部组织", "所有组织", "全公司",
                   "不限部门", "取消组织条件", "不限制部门", "全部", "各部门"):
        residual = residual.replace(phrase, " ")
    if re.search(r"[\u4e00-\u9fffA-Za-z0-9]{1,24}(?:部门|事业群|分公司|事业部|中心|团队|小组|部)", residual):
        raise PlanRejected("unknown_organization", MESSAGES["unknown_organization"], "clarify")
    override_org = (context or {}).get("org") or {}
    if len(matches) > 1:
        if len(surfaces) > 1:
            raise PlanRejected("unsupported_capability", "暂不支持同时指定多个组织，请分别查询。")
        # Same surface name under multiple visible parents → clarify, not multi-org execute.
        if organization_choice and organization_choice in matches:
            matches = {organization_choice}
        else:
            candidates = [o for o in metadata["organizations"] if o["org_id"] in matches]
            raise PlanRejected(
                "ambiguous_organization", MESSAGES["ambiguous_organization"], "clarify",
                options=[{"option_id": o["org_id"], "label": _org_option_label(o)} for o in candidates],
                slot="organization")
    if plan.decision != "execute":
        return
    expected_org = override_org.get("org_id") or (next(iter(matches)) if matches else None)
    if expected_org and (not plan.org_scope or plan.org_scope.org_id != expected_org):
        raise PlanRejected("invalid_plan", "查询计划没有保留指定的组织范围", "failed")
    if override_org.get("org_id") and plan.org_scope and plan.org_scope.include_children != override_org.get("include_children", True):
        raise PlanRejected("invalid_plan", "查询计划没有保留组织下级范围设置", "failed")
    expected_metrics = [selected] if selected else (context or {}).get("metrics")
    if expected_metrics and plan.metric_codes != expected_metrics:
        raise PlanRejected("invalid_plan", "查询计划没有保留已选择的指标", "failed")
    if (context or {}).get("time_range"):
        expected = resolve_window("", context, date.fromisoformat(metadata["as_of_date"]))
        if expected is None or plan.time_range is None or (
                plan.time_range.start, plan.time_range.end) != (expected.start.isoformat(), expected.end.isoformat()):
            raise PlanRejected("invalid_plan", "查询计划没有保留已选择的时间范围", "failed")


async def run_planned_flow(*, question, session_id, ask_id, adapter, tools,
                           context_override=None, forced_metric_code=None, tool_context_token=None,
                           invocation_id=None, trace_id=None, use_langgraph=True, runtime_evidence=None,
                           query_context=None, repair_enabled=None, query_timeout_seconds=75.0, on_event=None, **unused):
    from langgraph_flows.ask_flow import _build_payload, _window_to_dict
    started = time.perf_counter()
    uses_draft = getattr(adapter, "supports_query_draft", False)
    uses_memory = uses_draft and query_context is not None
    repair_enabled = (os.getenv("HRCHAT_QUERY_REPAIR_ENABLED", "1") == "1" if repair_enabled is None else repair_enabled) and uses_draft
    budget = QueryBudget(timeout_seconds=query_timeout_seconds, max_model_calls=2 if repair_enabled else 1)
    evidence = {"schema_version": "1", "planner": "model", "prompt_version": MEMORY_PROMPT_VERSION if uses_memory else DRAFT_VERSION if uses_draft else "query-plan-v1",
                "runtime": "remote", "query_backend": tools.backend,
                "runtime_config": runtime_evidence or {}, "trace": [], "model_calls": [],
                "query_plan": None, "execution": None, "reason": None}
    evidence["decoding"] = {"temperature": getattr(adapter, "temperature", None),
                            "timeout_seconds": getattr(adapter, "timeout", None), "model_retries": 0,
                            "request_options": dict(getattr(adapter, "request_options", {}))}
    if uses_draft:
        evidence["decoding"]["request_options"].update(adapter.planning_options())
    evidence["java_ask_id"] = evidence["runtime_config"].pop("java_ask_id", None)
    state = {"ask_id": ask_id, "events": [], "evidence": evidence, "error": None,
             "clarify_questions": [], "answer_payload": None}
    evidence["repair"] = {"version": REPAIR_VERSION, "enabled": repair_enabled, "max_attempts": 1,
                          "attempts": 0, "eligible": False, "status": "not_needed"}
    prices = []
    price_error = False
    try:
        prices = load_prices(os.getenv("HRCHAT_MODEL_PRICES"))
    except (ValueError, OSError, KeyError):
        price_error = True
    evidence["price_config_status"] = "invalid" if price_error else "configured" if prices else "not_configured"

    def stop(s, reason, message, decision="failed", code=None):
        s["evidence"]["reason"] = reason
        if decision == "clarify":
            plan = s.get("plan")
            by_code = {m["code"]: m for m in s.get("catalog", {}).get("metrics", [])}
            slot = s.get("_clarify_slot") or "metric"
            options = list(s.get("_clarify_options") or [])
            if not options:
                options = [{"option_id": o.option_id, "label": _metric_option_label(by_code[o.option_id])}
                           for o in (plan.clarification_options if plan else []) if o.option_id in by_code]
            if not s.get("_clarify_options") and uses_memory and plan and plan.missing_slots == ["time_range"]:
                options = [{"option_id": code, "label": label} for code, label in PERIOD_OPTIONS.items()]
                metric_name = by_code.get(plan.metric_codes[0], {}).get("name", "该指标") if plan.metric_codes else "该指标"
                org_name = next((o.get("label") or o["name"] for o in s["catalog"]["organizations"]
                                 if plan.org_scope and o["org_id"] == plan.org_scope.org_id), "当前授权组织")
                message = f"已保留：{org_name} · {metric_name}。请选择统计期间，也可在输入框直接输入其他期间。"
                slot = "time_range"
            elif reason == "ambiguous_metric":
                slot = "metric"
            s["clarify_questions"] = [{"question_id": ask_id + "-q1", "question": message,
                                      "slot": slot, "options": options}]
            s["events"].append({"event": "INTERRUPT", "payload": {
                "interrupt_type": "CLARIFY", "ask_id": ask_id, "questions": s["clarify_questions"]}})
        else:
            s["error"] = {"code": code or ("HRA-4006" if decision == "unsupported" else "HRA-4002"),
                          "message": message, "recoverable": False}
            s["events"].append({"event": "ERROR", "payload": s["error"]})

    def context(tool_id):
        return {"tool_context_token": tool_context_token, "invocation_id": invocation_id,
                "trace_id": trace_id, "tool_call_id": tool_id}

    async def call_model(s, trace, messages=None, prompt=None, phase="initial"):
        budget.consume("model")
        call_started = time.perf_counter()
        if phase == "repair":
            s["evidence"]["repair"]["attempts"] = 1
        try:
            result = await adapter.complete_query_draft(*messages) if messages else await adapter.complete(prompt)
        except ModelCallError as exc:
            record = exc.result.evidence(ask_id=ask_id, invocation_id=invocation_id, prices=prices)
            s["evidence"]["model_calls"].append({**record, "phase": phase})
            raise
        except asyncio.CancelledError:
            # Cancellation after dispatch may still incur provider usage; never record zero cost.
            result = ModelResult("", getattr(adapter, "name", "unknown"), getattr(adapter, "model", "unknown"),
                "llm_" + uuid.uuid4().hex, int((time.perf_counter() - call_started) * 1000),
                status="failed", failure_kind="task_deadline_exceeded")
            s["evidence"]["model_calls"].append({**result.evidence(ask_id=ask_id,
                invocation_id=invocation_id, prices=prices), "phase": phase})
            raise
        except Exception as exc:
            result = ModelResult("", getattr(adapter, "name", "unknown"), getattr(adapter, "model", "unknown"),
                "llm_" + uuid.uuid4().hex, int((time.perf_counter() - call_started) * 1000),
                status="failed", failure_kind="adapter_error", exception_type=type(exc).__name__)
            s["evidence"]["model_calls"].append({**result.evidence(ask_id=ask_id,
                invocation_id=invocation_id, prices=prices), "phase": phase})
            raise
        s["evidence"]["model_calls"].append({**result.evidence(ask_id=ask_id,
            invocation_id=invocation_id, prices=prices), "phase": phase})
        trace["call_id"] = result.call_id
        if phase == "initial" and uses_draft:
            s["_raw_draft_text"] = result.text
        if result.finish_reason not in (None, "stop"):
            raise PlanRejected("incomplete_model_output", "模型未完成查询计划，请重试", "failed")
        return result

    def compile_model_draft(draft, s):
        if uses_memory:
            return compile_contextual(draft, question, s["catalog"], context_override, query_context, ask_id)
        plan, compilation = compile_draft(draft, question, s["catalog"], context_override, ask_id, forced_metric_code)
        return plan, compilation, context_override

    def stop_original(s):
        reason, message, decision = s["_planning_failure"]
        stop(s, "invalid_plan" if reason == "schema_validation" else reason, message, decision)

    async def observe_result(s, trace):
        reason, message, _ = s["_planning_failure"]
        repair = s["evidence"]["repair"]
        repair.update(original_error=s["evidence"].get("validation_error"),
                      original_query_plan=s["evidence"].get("query_plan"), status="skipped")
        contract = repair_contract(s.get("_raw_draft_text"), reason, question, s["catalog"], memory=uses_memory)
        if contract is None:
            repair["skip_reason"] = "not_unambiguous_or_not_repairable"
            stop_original(s)
            return
        try:
            schema = ContextQueryDraft if uses_memory else ModelQueryDraft
            candidate = schema.model_validate(contract["expected_draft"])
            plan, _, effective = compile_model_draft(candidate, s)
            validate_plan(plan, s["catalog"], turn_id=ask_id)
            guard_request(plan, question, s["catalog"], effective, forced_metric_code)
            if plan.decision != "execute" or (plan.org_scope and plan.org_scope.requested_name):
                raise ValueError("unconfirmed conditions")
        except (PlanRejected, ValidationError, McpBusinessError, ValueError):
            repair["skip_reason"] = "conditions_not_fully_confirmed"
            stop_original(s)
            return
        repair.update(eligible=True, allowed_fields=contract["allowed_fields"],
                      original_draft=contract["original_draft"], original_text_sha256=contract["original_text_sha256"],
                      format_only=contract["format_only"])
        if not repair_enabled:
            repair["skip_reason"] = "disabled"
            stop_original(s)
            return
        s["_repair_contract"] = contract
        s["_repair_target_plan"] = plan.model_dump()

    async def repair_query(s, trace):
        repair, contract = s["evidence"]["repair"], s["_repair_contract"]
        previous_versions = s["evidence"]["metric_versions"]
        # Re-read current metadata and scope before the one repair attempt.
        await retrieve(s, trace)
        intended = contract["expected_draft"]["metric_codes"]
        if any(previous_versions.get(code) != s["evidence"]["metric_versions"].get(code) for code in intended):
            raise PlanRejected("metric_version_changed", "指标口径已更新，请重新明确指标", "clarify")
        schema = ContextQueryDraft if uses_memory else ModelQueryDraft
        preflight, _, effective = compile_model_draft(schema.model_validate(contract["expected_draft"]), s)
        validate_plan(preflight, s["catalog"], turn_id=ask_id)
        guard_request(preflight, question, s["catalog"], effective, forced_metric_code)
        if preflight.model_dump() != s["_repair_target_plan"]:
            raise PlanRejected("repair_context_changed", "查询口径或时间基准已变化，请重新确认条件", "clarify")
        base = memory_messages(question, s["catalog"], context_override, query_context) if uses_memory else draft_messages(
            question, s["catalog"], context_override, forced_metric_code)
        result = await call_model(s, trace, messages=repair_messages(base, contract, repair["original_error"]), phase="repair")
        draft, changes = check_repair(result.text, contract, memory=uses_memory)
        s["plan"], s["evidence"]["compilation"], s["effective_context"] = compile_model_draft(draft, s)
        repair.update(status="validated", field_changes=changes, repaired_draft=draft.model_dump(exclude_none=True),
                      repaired_query_plan=s["plan"].model_dump(exclude_none=True))
        s["evidence"]["query_plan"] = s["plan"].model_dump(exclude_none=True)

    async def retrieve(s, trace):
        if tools.backend != "java_mcp":
            raise PlanRejected("backend_unavailable", "模型规划需要连接权威业务数据服务", "failed")
        trace["tool_call_id"] = "tc_" + uuid.uuid4().hex
        s["catalog"] = await tools.planning_catalog(context(trace["tool_call_id"]),
            requested_org_id=((context_override or {}).get("org") or {}).get("org_id"))
        s["evidence"]["catalog"] = {k: s["catalog"][k] for k in
            ("as_of_date", "timezone", "metric_count", "org_count", "complete", "capability_version")}
        s["evidence"]["metric_versions"] = {m["code"]: m["version"] for m in s["catalog"]["metrics"]}
        import os
        from adapters.query_retrieval import attach_prompt_distractors, retrieve as retrieve_context
        pressure = os.getenv("HRCHAT_PROMPT_DISTRACTORS", "0") == "1"
        if pressure:
            attach_prompt_distractors(s["catalog"])
        retrieved, retrieval_evidence = await asyncio.to_thread(
            retrieve_context, question, s["catalog"], include_distractors=pressure)
        s["catalog"]["retrieved_context"] = retrieved
        s["evidence"]["retrieval"] = retrieval_evidence
        if pressure:
            s["evidence"]["prompt_distractors"] = {
                "enabled": True,
                "prompt_metric_count": len(s["catalog"].get("prompt_metrics") or []),
                "authority_metric_count": len(s["catalog"]["metrics"]),
            }

    async def plan_query(s, trace):
        nonlocal question
        if uses_memory:
            selected = selection_draft(query_context)
            if selected:
                draft, question = selected
            elif task_action(question) == "cancel":
                s["answer_payload"] = {"ask_id": ask_id, "answer_id": "ans_" + ask_id,
                    "status": "COMPLETED", "intent": "CHITCHAT", "degraded": False,
                    "conclusion": {"type": "TEXT", "value": "已取消待澄清问题。", "unit": None},
                    "table": {"columns": [], "rows": [], "total": 0, "page": 1, "size": 1},
                    "chart": None, "caliber": None, "followups": [], "elapsed_ms": 0}
                s["events"].append({"event": "ANSWER_DONE", "payload": s["answer_payload"]})
                return
            else:
                guard_known_capabilities(question, allow_analysis=True, catalog=s["catalog"])
                result = await call_model(s, trace, messages=memory_messages(question, s["catalog"], context_override, query_context))
                draft = ContextQueryDraft.model_validate_json(result.text)
            s["evidence"]["model_query_draft"] = draft.model_dump(exclude_none=True)
            if draft.action == "prepare_analysis":
                # Even when the model omits a named, non-visible organization,
                # do not silently analyze the previous organization's answer.
                org_text = re.sub(r"哪个部门|哪些部门|各部门|哪个组织|哪些组织|各组织|部门贡献", "", question)
                if (not re.search(r"分析|变化|变动|为什么|原因|归因", question) or draft.metric_codes
                        or draft.organization or draft.time_expression or draft.query_mode or draft.clear_slots
                        or draft.decision != "execute" or draft.unsupported_reason or context_override
                        or query_context.get("pending") or has_time_cue(question)
                        or any(o["name"] in question for o in s["catalog"]["organizations"])
                        or re.search(r"[\u4e00-\u9fff]{2,}(?:部|中心|团队|事业群|公司)", org_text)
                        or re.search(r"薪资|薪酬|人力成本|入职|在职|离职率|主动离职|明细|预测|同比", question)):
                    raise PlanRejected("analysis_source_required", "请先完成要分析的离职人数单期查询，再确认两期进行变化分析。", "clarify")
                s["evidence"]["analysis_request"] = {"action": "prepare_analysis", "source": "latest_completed_query"}
                s["answer_payload"] = {"ask_id": ask_id, "answer_id": "ans_" + ask_id,
                    "status": "COMPLETED", "intent": "ANALYSIS", "degraded": False,
                    "conclusion": {"type": "TEXT", "value": "请确认两期后分析部门变化贡献；统计变化不代表真实离职原因。", "unit": None},
                    "table": {"columns": [], "rows": [], "total": 0, "page": 1, "size": 1},
                    "caliber": None, "chart": None, "followups": [], "elapsed_ms": 0}
                s["events"].append({"event": "ANSWER_DONE", "payload": s["answer_payload"]})
                return
            s["plan"], s["evidence"]["compilation"], s["effective_context"] = compile_contextual(
                draft, question, s["catalog"], context_override, query_context, ask_id)
            s["evidence"]["query_plan"] = s["plan"].model_dump(exclude_none=True)
            return
        if uses_draft:
            s["evidence"]["capability_guard_version"] = "known-capability-guard-v1"
            guard_known_capabilities(question, catalog=s["catalog"])
            s["evidence"]["followup_guard_version"] = FOLLOWUP_GUARD_VERSION
            if is_slot_only_question(question, s["catalog"], context_override, forced_metric_code):
                raise PlanRejected("missing_slots", "请补充希望查询的指标；入职或离职人数还需提供统计期间。", "clarify")
            messages = draft_messages(question, s["catalog"], context_override, forced_metric_code)
            result = await call_model(s, trace, messages=messages)
        else:
            result = await call_model(s, trace, prompt=planning_prompt(question, s["catalog"], context_override,
                                                           ask_id, forced_metric_code))
        # The initial parse stays strict; S4 explicitly observes eligible failures.
        if uses_draft:
            draft = ModelQueryDraft.model_validate_json(result.text)
            s["evidence"]["model_query_draft"] = draft.model_dump(exclude_none=True)
            s["plan"], s["evidence"]["compilation"] = compile_draft(
                draft, question, s["catalog"], context_override, ask_id, forced_metric_code)
        else:
            s["plan"] = QueryPlan.model_validate_json(result.text)
            s["evidence"]["model_query_plan"] = s["plan"].model_dump(exclude_none=True)
        s["evidence"]["query_plan"] = s["plan"].model_dump(exclude_none=True)

    async def validate(s, trace):
        p = s["plan"]
        effective = s.get("effective_context") or context_override or {}
        override_org = (effective.get("org") or {}) if isinstance(effective, dict) else {}
        if p.org_scope and p.org_scope.requested_name and override_org.get("org_id"):
            # Clarify / UI already chose a catalog org; do not re-resolve the ambiguous name.
            if override_org["org_id"] not in {o["org_id"] for o in s["catalog"]["organizations"]}:
                raise McpBusinessError("HRC-2003", "当前组织范围已不可用，请重新选择授权组织")
            p.org_scope = OrgScope(org_id=override_org["org_id"],
                                   include_children=override_org.get("include_children", True))
            s["evidence"]["query_plan"] = p.model_dump(exclude_none=True)
            s["evidence"]["org_resolution"] = {"status": "RESOLVED", "source": "context_override"}
        elif p.org_scope and p.org_scope.requested_name:
            name = p.org_scope.requested_name
            if name not in question:
                raise PlanRejected("invalid_plan", "组织核查名称并非来自原问题", "failed")
            lookup_trace = {"stage": "resolve_organization", "ask_id": ask_id, "invocation_id": invocation_id,
                            "trace_id": trace_id, "tool_call_id": "tc_" + uuid.uuid4().hex}
            t = time.perf_counter()
            try:
                resolution = await tools.resolve_organization(name, context(lookup_trace["tool_call_id"]))
                lookup_trace["status"] = resolution["status"]
            except Exception:
                lookup_trace["status"] = "failed"
                raise
            finally:
                lookup_trace["elapsed_ms"] = int((time.perf_counter() - t) * 1000)
                s["evidence"]["trace"].append(lookup_trace)
            s["evidence"]["org_resolution"] = {k: resolution.get(k) for k in ("status", "candidates")
                                               if k in resolution} or {"status": resolution.get("status")}
            if resolution["status"] == "AMBIGUOUS":
                candidates = resolution.get("candidates") or []
                if not candidates:
                    raise PlanRejected("unknown_organization", MESSAGES["unknown_organization"], "clarify")
                raise PlanRejected(
                    "ambiguous_organization", MESSAGES["ambiguous_organization"], "clarify",
                    options=[{"option_id": c["org_id"], "label": _org_option_label(c)} for c in candidates],
                    slot="organization")
            if resolution["status"] != "RESOLVED":
                raise PlanRejected("unknown_organization", MESSAGES["unknown_organization"], "clarify")
            org = resolution["organization"]
            existing = next((o for o in s["catalog"]["organizations"] if o["org_id"] == org["org_id"]), None)
            if existing:
                existing["aliases"] = list(set(existing.get("aliases", []) + org.get("aliases", [])))
                for key in ("label", "org_code"):
                    if org.get(key):
                        existing[key] = org[key]
            else:
                s["catalog"]["organizations"].append(org)
            p.org_scope = OrgScope(org_id=org["org_id"], include_children=p.org_scope.include_children)
            s["evidence"]["query_plan"] = p.model_dump(exclude_none=True)
        validate_plan(p, s["catalog"], turn_id=ask_id)
        # Only a user/UI choice may disambiguate a homonym, not an ID guessed
        # by the model and copied into the compiler's effective context.
        organization_choice = ((context_override or {}).get("org") or {}).get("org_id")
        if uses_memory and query_context.get("selection"):
            choice = query_context["selection"]
            pending_questions = (query_context.get("pending") or {}).get("questions", [])
            if any(q.get("question_id") == choice.get("question_id") and q.get("slot") == "organization"
                   for q in pending_questions):
                organization_choice = choice.get("option_id")
        guard_request(p, question, s["catalog"], effective, forced_metric_code, organization_choice)
        if p.decision in ("unsupported", "clarify"):
            stop(s, p.reason, MESSAGES.get(p.reason, MESSAGES["missing_slots"]), p.decision)
        elif p.decision == "chitchat":
            s["answer_payload"] = {
                "ask_id": ask_id, "answer_id": "ans_" + ask_id, "status": "COMPLETED", "intent": "CHITCHAT",
                "degraded": False, "degraded_tip": None,
                "conclusion": {"type": "TEXT", "value": "您好，当前可查询：" + "、".join(m["name"] for m in s["catalog"]["metrics"]) + "。可继续指定期间、组织或查询方式。", "unit": None, "compare": None},
                "table": {"columns": [], "rows": [], "total": 0, "page": 1, "size": 1},
                "chart": None, "caliber": None, "followups": [], "elapsed_ms": 0}
            s["events"].append({"event": "ANSWER_DONE", "payload": s["answer_payload"]})

    async def tool(s, trace):
        p = s["plan"]
        metric = next(m for m in s["catalog"]["metrics"] if m["code"] == p.metric_codes[0])
        trace["tool_call_id"] = "tc_" + uuid.uuid4().hex
        s["tool_result"] = await tools.execute_plan(p, metric, context(trace["tool_call_id"]))
        s["metric"] = metric
        s["evidence"]["execution"] = s["tool_result"]["execution_evidence"]
        s["evidence"]["reason"] = "empty_result" if s["tool_result"]["current"] is None else "answered"

    async def present(s, trace):
        p, result, metric = s["plan"], s["tool_result"], s["metric"]
        window = Window(date.fromisoformat(p.time_range.start), date.fromisoformat(p.time_range.end)) if p.time_range else None
        output = {**result, "ask_id": ask_id, "metric_view": metric_view(metric),
                  "time_window": _window_to_dict(window), "row_count": result["rows"],
                  "data_updated_at": None}
        payload = _build_payload(output)
        # Never invent a data refresh timestamp. Demo clock is a query reference, not ETL evidence.
        payload["caliber"]["data_updated_at"] = None
        selected_org = next((o.get("label") or o["name"] for o in s["catalog"]["organizations"]
                             if p.org_scope and o["org_id"] == p.org_scope.org_id), "当前全部授权组织")
        payload["caliber"]["organization"] = selected_org + ("（含下级）" if p.org_scope and p.org_scope.include_children else "")
        payload["caliber"]["query_mode"] = {"scalar": "汇总", "org": "按组织对比", "trend": "趋势", "detail": "明细"}[p.query_mode]
        if window:
            payload["caliber"]["time_range"] = f"{window.start.isoformat()}/{(window.end - timedelta(days=1)).isoformat()}"
        elif metric["time_type"] == "as_of":
            payload["caliber"]["time_range"] = "截至 " + str(result.get("as_of_date") or s["catalog"]["as_of_date"])
        if p.query_mode == "scalar":
            payload["table"]["rows"] = [{metric["code"]: result["current"]}] if result["rows"] else []
        s["answer_payload"] = payload
        s["events"].append({"event": "ANSWER_DONE", "payload": payload})

    def stopped(s):
        return bool(s.get("error") or s.get("clarify_questions") or s.get("answer_payload"))

    def wrapped(name, fn):
        async def node(s):
            t = time.perf_counter()
            trace = {"stage": name, "ask_id": ask_id, "invocation_id": invocation_id, "trace_id": trace_id}
            labels = {"retrieve": "正在读取可用业务口径…", "plan_query": "正在理解问题与查询条件…",
                      "observe_result": "正在检查查询草稿…", "repair_query": "正在修正明确遗漏的条件…",
                      "validate": "正在核验口径与查询范围…", "tool": "正在查询授权数据…", "present": "正在整理查询结果…"}
            if on_event:
                on_event({"event": "PROGRESS", "payload": {"stage": name, "status": "started",
                          "message": labels[name], "elapsed_ms": int((t - started) * 1000)}})
            try:
                remaining = budget.remaining()
                await asyncio.wait_for(fn(s, trace), timeout=remaining)
                trace["status"] = "stopped" if stopped(s) and name != "present" else "ok"
            except ModelCallError as exc:
                trace["status"] = "failed"
                stop(s, "model_service_error", "模型服务暂不可用，请稍后重试", code="HRS-3002")
            except PlanRejected as exc:
                trace["status"] = "rejected"
                s["evidence"]["validation_error"] = {"stage": name, "code": exc.reason, "message": exc.message}
                if exc.options:
                    s["_clarify_options"] = exc.options
                    s["_clarify_slot"] = exc.slot or "metric"
                if name == "plan_query" and uses_draft and s.get("_raw_draft_text"):
                    s["_planning_failure"] = (exc.reason, exc.message, exc.decision)
                elif name == "repair_query" and exc.reason == "repair_scope_violation":
                    s["evidence"]["repair"]["failure_reason"] = exc.reason
                    stop_original(s)
                else:
                    stop(s, exc.reason, exc.message, exc.decision)
            except ValidationError as exc:
                trace["status"] = "failed"
                safe_fields = {"organization", "kind", "org_id", "source_text", "name", "decision",
                               "metric_codes", "time_expression", "query_mode", "unsupported_reason",
                               "org_scope", "requested_name", "time_range", "start", "end"}
                s["evidence"]["validation_error"] = {"stage": name, "code": "schema_validation",
                    "errors": [{"type": e["type"], "path": [v if isinstance(v, int) or v in safe_fields else "<field>"
                               for v in e["loc"]]} for e in exc.errors(include_input=False, include_context=False, include_url=False)[:10]]}
                if name == "plan_query" and uses_draft:
                    s["_planning_failure"] = ("schema_validation", "模型返回的查询计划不合法，请重试", "failed")
                elif name == "repair_query":
                    s["evidence"]["repair"]["failure_reason"] = "schema_validation"
                    stop_original(s)
                else:
                    stop(s, "invalid_plan", "模型返回的查询计划不合法，请重试")
            except (QueryBudgetExceeded, asyncio.TimeoutError) as exc:
                trace["status"] = "budget_exhausted"
                stop(s, getattr(exc, "reason", "task_deadline_exceeded"),
                     "本次查询已达到调用或等待上限，请稍后重试", code="HRS-3002")
            except McpBusinessError as exc:
                trace["status"] = "failed"
                reason = "permission_denied" if exc.code.startswith("HRC-2") else "metadata_service_error" if name == "retrieve" else "tool_error"
                stop(s, reason, exc.message, code=exc.code)
            except Exception:
                # No provider response/credentials in public errors.
                trace["status"] = "failed"
                stop(s, "service_error", "查询服务暂不可用，请稍后重试", code="HRS-3002")
            trace["elapsed_ms"] = int((time.perf_counter() - t) * 1000)
            s["evidence"]["trace"].append(trace)
            if on_event:
                on_event({"event": "PROGRESS", "payload": {"stage": name, "status": trace["status"],
                          "elapsed_ms": int((time.perf_counter() - started) * 1000)}})
            return s
        return node

    nodes = {name: wrapped(name, fn) for name, fn in
             [("retrieve", retrieve), ("plan_query", plan_query), ("observe_result", observe_result),
              ("repair_query", repair_query), ("validate", validate), ("tool", tool), ("present", present)]}

    def next_node(name, s):
        if stopped(s) or name == "present":
            return "__end__"
        if name == "plan_query":
            return "observe_result" if s.get("_planning_failure") else "validate"
        return {"retrieve": "plan_query", "observe_result": "repair_query", "repair_query": "validate",
                "validate": "tool", "tool": "present"}[name]

    async def execute_nodes():
        nonlocal state
        if use_langgraph:
            from langgraph.graph import StateGraph, START, END
            graph = StateGraph(dict)
            for name, node in nodes.items():
                graph.add_node(name, node)
            graph.add_edge(START, "retrieve")
            for name in nodes:
                graph.add_conditional_edges(name, lambda s, current=name: next_node(current, s),
                                            {END: END, **{n: n for n in nodes}})
            state = await graph.compile().ainvoke(state)
        else:
            current = "retrieve"
            while current != "__end__":
                state = await nodes[current](state)
                current = next_node(current, state)

    budget_token = ACTIVE_QUERY_BUDGET.set(budget)
    try:
        await execute_nodes()
    finally:
        ACTIVE_QUERY_BUDGET.reset(budget_token)
    evidence = state["evidence"]
    evidence["budget"] = budget.evidence()
    repair = evidence["repair"]
    if repair["attempts"]:
        repair["status"] = "completed" if state.get("answer_payload") and evidence.get("execution") and not state.get("error") else "failed"
    repair["added_elapsed_ms"] = sum(t["elapsed_ms"] for t in evidence["trace"] if t["stage"] in {"observe_result", "repair_query"})
    repair["cost_summary"] = summarize_calls([c for c in evidence["model_calls"] if c.get("phase") == "repair"])
    state["elapsed_ms"] = int((time.perf_counter() - started) * 1000)
    if uses_memory:
        candidate = context_candidate(state, query_context, question)
        if candidate is not None:
            state["evidence"]["query_context_candidate"] = candidate
    state["evidence"]["cost_summary"] = summarize_calls(state["evidence"]["model_calls"])
    if on_event:
        on_event({"event": "USAGE", "payload": state["evidence"]["cost_summary"]})
    if state.get("answer_payload"):
        state["answer_payload"]["elapsed_ms"] = state["elapsed_ms"]
    plan = state.get("plan")
    if plan is not None and getattr(plan, "metric_codes", None):
        state["metric_code"] = plan.metric_codes[0]
    return state
