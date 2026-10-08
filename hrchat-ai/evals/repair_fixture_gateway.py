"""Dedicated S4 fault injection entry point. Never selected by the normal gateway."""
import json

from evals.memory_fixture_gateway import MemorySmokePlanner, app, _runtimes, DEFAULT_TENANT_KEY


class RepairSmokePlanner(MemorySmokePlanner):
    model = "repair-injection-fixture"

    async def complete_query_draft(self, system, user):
        result = await super().complete_query_draft(system, user)
        data, draft = json.loads(user), json.loads(result.text)
        question = data["question"]
        if "repair" not in data:
            if "上月" in question and "离职人数" in question:
                draft.pop("time_expression", None)
            elif "在职人数" in question and "格式" in question:
                result.text = "```json\n" + result.text + "\n```"
                return result
            elif "上月" in question and "在职人数" in question:
                draft["metric_codes"] = ["invalid_fixture_code"]
        elif "保持组织范围" in question:
            draft["organization"] = None  # deliberate unsafe repair, must never query
        result.text = json.dumps(draft)
        return result


_runtimes[DEFAULT_TENANT_KEY] = {"profile": "fixture", "adapter": RepairSmokePlanner(),
    "model": "repair-injection-fixture", "config_version": "injected-fixture-only", "inherited": False}
