"""Only scripted model drafts; validates cross-service S3 behavior, never model quality."""
import json
from adapters.model_usage import ModelResult
from adapters.llm_adapter import ModelAdapter
from agent_gateway.app import app, _runtimes, DEFAULT_TENANT_KEY


class MemorySmokePlanner(ModelAdapter):
    supports_query_plan = supports_query_draft = True
    name = "fixture"
    model = "memory-draft-fixture"

    def planning_options(self):
        return {}

    async def generate(self, prompt):
        raise NotImplementedError("draft only")

    async def complete_query_draft(self, system, user):
        question = json.loads(user)["question"]
        draft = {"decision": "execute"}
        for code, name in [("headcount", "在职人数"), ("leave_count", "离职人数")]:
            if name in question:
                draft.update(metric_codes=[code], metric_text=name)
        for oid, name in [("3", "研发一部"), ("2", "研发中心")]:
            if name in question:
                draft["organization"] = {"kind": "catalog_id", "org_id": oid, "source_text": name}
        for expression in ("上月", "本月", "近7天"):
            if expression in question:
                draft["time_expression"] = expression
        if "趋势" in question:
            draft.update(query_mode="trend", mode_text="趋势")
        if "全部部门" in question:
            draft["clear_slots"] = ["organization"]
        return ModelResult(json.dumps(draft), "fixture", self.model, "fixture_" + str(id(draft)), 1, finish_reason="stop")


_runtimes[DEFAULT_TENANT_KEY] = {"profile": "fixture", "adapter": MemorySmokePlanner(),
    "model": "memory-draft-fixture", "config_version": "fixture-only", "inherited": False}
