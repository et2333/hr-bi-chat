"""Isolated cross-service smoke fixture. Never use this adapter as model-quality evidence."""
import json
from adapters.llm_adapter import ModelAdapter
from agent_gateway.app import app, _runtimes, DEFAULT_TENANT_KEY


class SmokePlanner(ModelAdapter):
    supports_query_plan = True
    name = "fixture"
    model = "smoke-query-plan-fixture"

    async def generate(self, prompt):
        data = json.loads(prompt.split("INPUT_JSON:\n", 1)[1])
        question = data["question"]
        p = {"schema_version": "1", "decision": "execute", "reason": "ready",
             "metric_codes": ["headcount"], "query_mode": "scalar", "decision_summary": "Smoke fixture",
             "org_scope": {"org_id": "2", "include_children": True},
             "time_range": {"start": "2026-08-01", "end": "2026-09-01", "grain": "NONE",
                            "timezone": "Asia/Shanghai", "time_type": "as_of"}}
        if "人员流失" in question:
            p["metric_codes"] = ["leave_count"]
            p["time_range"]["time_type"] = "period"
        if "预测" in question:
            p.update(decision="unsupported", reason="unsupported_capability", metric_codes=[])
        for name in ("销售部", "不存在部门"):
            if name in question:
                p["org_scope"] = {"requested_name": name, "include_children": True}
        if "本月" in question:
            p["time_range"].update(start="2026-09-01", end="2026-09-29")
        return json.dumps(p)


_runtimes[DEFAULT_TENANT_KEY] = {"profile": "fixture", "adapter": SmokePlanner(),
    "model": "smoke-query-plan-fixture", "config_version": "fixture-only", "inherited": False}
