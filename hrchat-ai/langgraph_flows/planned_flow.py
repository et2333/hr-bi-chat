"""S2 retrieve -> plan_query -> validate -> tool -> present. No semantic retry or demo fallback."""
from __future__ import annotations

from datetime import date, timedelta
import os
import re
import time
import uuid

from pydantic import ValidationError
from adapters.mcp_client import McpBusinessError
from adapters.model_usage import ModelCallError, load_prices, summarize_calls
from adapters.semantic_tool_client import metric_view
from langgraph_flows.query_plan import QueryPlan, OrgScope, PlanRejected, planning_prompt, validate_plan
from langgraph_flows.query_draft import (ModelQueryDraft, draft_messages, compile_draft, DRAFT_VERSION,
                                        is_slot_only_question, FOLLOWUP_GUARD_VERSION)
from langgraph_flows.demo_data import Window, resolve_window


MESSAGES = {
    "metric_unavailable": "当前可用目录未提供该指标口径，暂不支持这种统计。",
    "unsupported_capability": "暂不支持这种统计、筛选或分析方式，请调整问题。",
    "unknown_organization": "组织无法识别或不在当前可用范围，请使用可用组织的完整名称。",
    "ambiguous_metric": "请明确您希望查询的指标。",
    "missing_slots": "请补充指标、组织或统计时间等缺少的条件。",
}


def guard_request(plan, question, metadata, context, selected):
    """Deterministic known-failure checks complement, not replace, model understanding."""
    if plan.decision != "execute":
        return
    if re.search(r"预测|为什么|原因|归因|主动离职|性别|职级|司龄|年龄|学历|岗位|男性|女性|男员工|女员工|同比|去年同期", question):
        raise PlanRejected("unsupported_capability", MESSAGES["unsupported_capability"])
    residual = question
    matches = set()
    names = sorted([(name, o["org_id"]) for o in metadata["organizations"]
                    for name in [o["name"], *o["aliases"]] if name], key=lambda x: -len(x[0]))
    for name, oid in names:
        if name in residual:
            matches.add(oid)
            residual = residual.replace(name, " ")
    for phrase in ("按部门对比", "按组织对比", "全部", "各部门"):
        residual = residual.replace(phrase, " ")
    if re.search(r"[\u4e00-\u9fffA-Za-z0-9]{1,24}(?:部门|事业群|分公司|事业部|中心|团队|小组|部)", residual):
        raise PlanRejected("unknown_organization", MESSAGES["unknown_organization"], "clarify")
    if len(matches) > 1:
        raise PlanRejected("unsupported_capability", "暂不支持同时指定多个组织，请分别查询。")
    override_org = (context or {}).get("org") or {}
    expected_org = override_org.get("org_id") or (next(iter(matches)) if matches else None)
    if expected_org and (not plan.org_scope or plan.org_scope.org_id != expected_org):
        raise PlanRejected("invalid_plan", "查询计划没有保留指定的组织范围", "failed")
    if override_org.get("org_id") and plan.org_scope.include_children != override_org.get("include_children", True):
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
                           invocation_id=None, trace_id=None, use_langgraph=True, runtime_evidence=None, **unused):
    from langgraph_flows.ask_flow import _build_payload, _window_to_dict
    started = time.perf_counter()
    uses_draft = getattr(adapter, "supports_query_draft", False)
    evidence = {"schema_version": "1", "planner": "model", "prompt_version": DRAFT_VERSION if uses_draft else "query-plan-v1",
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
            options = [{"option_id": o.option_id, "label": by_code[o.option_id]["name"]}
                       for o in (plan.clarification_options if plan else []) if o.option_id in by_code]
            s["clarify_questions"] = [{"question_id": ask_id + "-q1", "question": message, "options": options}]
            s["events"].append({"event": "INTERRUPT", "payload": {
                "interrupt_type": "CLARIFY", "ask_id": ask_id, "questions": s["clarify_questions"]}})
        else:
            s["error"] = {"code": code or ("HRA-4006" if decision == "unsupported" else "HRA-4002"),
                          "message": message, "recoverable": False}
            s["events"].append({"event": "ERROR", "payload": s["error"]})

    def context(tool_id):
        return {"tool_context_token": tool_context_token, "invocation_id": invocation_id,
                "trace_id": trace_id, "tool_call_id": tool_id}

    async def retrieve(s, trace):
        if tools.backend != "java_mcp":
            raise PlanRejected("backend_unavailable", "模型规划需要连接权威业务数据服务", "failed")
        trace["tool_call_id"] = "tc_" + uuid.uuid4().hex
        s["catalog"] = await tools.planning_catalog(context(trace["tool_call_id"]),
            requested_org_id=((context_override or {}).get("org") or {}).get("org_id"))
        s["evidence"]["catalog"] = {k: s["catalog"][k] for k in
            ("as_of_date", "timezone", "metric_count", "org_count", "complete", "capability_version")}
        s["evidence"]["metric_versions"] = {m["code"]: m["version"] for m in s["catalog"]["metrics"]}

    async def plan_query(s, trace):
        if uses_draft:
            s["evidence"]["followup_guard_version"] = FOLLOWUP_GUARD_VERSION
            if is_slot_only_question(question, s["catalog"], context_override, forced_metric_code):
                raise PlanRejected("missing_slots", "请补充希望查询的指标；入职或离职人数还需提供统计期间。", "clarify")
            messages = draft_messages(question, s["catalog"], context_override, forced_metric_code)
            result = await adapter.complete_query_draft(*messages)
        else:
            result = await adapter.complete(planning_prompt(question, s["catalog"], context_override,
                                                           ask_id, forced_metric_code))
        record = result.evidence(ask_id=ask_id, invocation_id=invocation_id, prices=prices)
        s["evidence"]["model_calls"].append(record)
        trace["call_id"] = result.call_id
        if result.finish_reason not in (None, "stop"):
            raise PlanRejected("incomplete_model_output", "模型未完成查询计划，请重试", "failed")
        # Strict JSON only, no automatic text repair and no extra model attempt.
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
        if p.org_scope and p.org_scope.requested_name:
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
            if resolution["status"] != "RESOLVED":
                raise PlanRejected("unknown_organization", MESSAGES["unknown_organization"], "clarify")
            org = resolution["organization"]
            existing = next((o for o in s["catalog"]["organizations"] if o["org_id"] == org["org_id"]), None)
            if existing:
                existing["aliases"] = list(set(existing["aliases"] + org["aliases"]))
            else:
                s["catalog"]["organizations"].append(org)
            p.org_scope = OrgScope(org_id=org["org_id"], include_children=p.org_scope.include_children)
            s["evidence"]["query_plan"] = p.model_dump(exclude_none=True)
        validate_plan(p, s["catalog"], turn_id=ask_id)
        guard_request(p, question, s["catalog"], context_override, forced_metric_code)
        if p.decision in ("unsupported", "clarify"):
            stop(s, p.reason, MESSAGES.get(p.reason, MESSAGES["missing_slots"]), p.decision)
        elif p.decision == "chitchat":
            s["answer_payload"] = {
                "ask_id": ask_id, "answer_id": "ans_" + ask_id, "status": "COMPLETED", "intent": "CHITCHAT",
                "degraded": False, "degraded_tip": None,
                "conclusion": {"type": "TEXT", "value": "您好，我可以帮您查询授权范围内的人事指标。", "unit": None, "compare": None},
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
            try:
                await fn(s, trace)
                trace["status"] = "stopped" if stopped(s) and name != "present" else "ok"
            except ModelCallError as exc:
                s["evidence"]["model_calls"].append(exc.result.evidence(ask_id=ask_id, invocation_id=invocation_id, prices=prices))
                trace["status"] = "failed"
                stop(s, "model_service_error", "模型服务暂不可用，请稍后重试", code="HRS-3002")
            except PlanRejected as exc:
                trace["status"] = "rejected"
                s["evidence"]["validation_error"] = {"stage": name, "code": exc.reason, "message": exc.message}
                stop(s, exc.reason, exc.message, exc.decision)
            except ValidationError as exc:
                trace["status"] = "failed"
                safe_fields = {"organization", "kind", "org_id", "source_text", "name", "decision",
                               "metric_codes", "time_expression", "query_mode", "unsupported_reason",
                               "org_scope", "requested_name", "time_range", "start", "end"}
                s["evidence"]["validation_error"] = {"stage": name, "code": "schema_validation",
                    "errors": [{"type": e["type"], "path": [v if isinstance(v, int) or v in safe_fields else "<field>"
                               for v in e["loc"]]} for e in exc.errors(include_input=False, include_context=False, include_url=False)[:10]]}
                stop(s, "invalid_plan", "模型返回的查询计划不合法，请重试")
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
            return s
        return node

    nodes = {name: wrapped(name, fn) for name, fn in
             [("retrieve", retrieve), ("plan_query", plan_query), ("validate", validate), ("tool", tool), ("present", present)]}
    if use_langgraph:
        from langgraph.graph import StateGraph, START, END
        graph = StateGraph(dict)
        for name, node in nodes.items():
            graph.add_node(name, node)
        graph.add_edge(START, "retrieve")
        names = list(nodes)
        for index, name in enumerate(names[:-1]):
            next_node = names[index + 1]
            graph.add_conditional_edges(name, lambda s, target=next_node: END if stopped(s) else target,
                                        {END: END, next_node: next_node})
        graph.add_edge("present", END)
        state = await graph.compile().ainvoke(state)
    else:
        for node in nodes.values():
            state = await node(state)
            if stopped(state):
                break
    state["elapsed_ms"] = int((time.perf_counter() - started) * 1000)
    state["evidence"]["cost_summary"] = summarize_calls(state["evidence"]["model_calls"])
    if state.get("answer_payload"):
        state["answer_payload"]["elapsed_ms"] = state["elapsed_ms"]
    return state
