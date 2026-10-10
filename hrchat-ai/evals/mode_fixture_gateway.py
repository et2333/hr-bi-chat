"""J1 scripted condition extraction; real Java tools/data, zero external model calls."""
import json
from uuid import uuid4

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelResult
from agent_gateway.app import app, _runtimes, DEFAULT_TENANT_KEY


class ModeFixturePlanner(ModelAdapter):
    supports_query_plan = supports_query_draft = True
    name = "fixture"
    model = "query-modes-draft-fixture-v1"

    def planning_options(self):
        return {}

    async def generate(self, prompt):
        raise NotImplementedError("draft only")

    async def complete_query_draft(self, system, user):
        q = json.loads(user)["question"]
        draft = {"decision": "execute"}
        for code, name in [("headcount", "在职人数"), ("hire_count", "入职人数"), ("leave_count", "离职人数")]:
            if name in q:
                draft.update(metric_codes=[code], metric_text=name)
        for mode, text in [("org", "按部门对比"), ("trend", "趋势"), ("detail", "明细"), ("scalar", "汇总")]:
            if text in q:
                draft.update(query_mode=mode, mode_text=text)
        for oid, name in [("2", "研发中心"), ("3", "研发一部"), ("4", "研发二部"), ("5", "销售部"), ("6", "职能部")]:
            if name in q:
                draft["organization"] = {"kind": "catalog_id", "org_id": oid, "source_text": name}
        return ModelResult(json.dumps(draft, ensure_ascii=False), "fixture", self.model,
                           "fixture_" + uuid4().hex, 1, finish_reason="stop")


_runtimes[DEFAULT_TENANT_KEY] = {"profile": "fixture", "adapter": ModeFixturePlanner(),
    "model": ModeFixturePlanner.model, "config_version": "fixture-only", "inherited": False}
