"""Explicit test entry: injected first drafts, real model for the repair only."""
import json

from adapters.mock_llm import get_llm_adapter
from evals.repair_fixture_gateway import RepairSmokePlanner, app, _runtimes, DEFAULT_TENANT_KEY


class RealRepairPlanner(RepairSmokePlanner):
    def __init__(self):
        self.real = get_llm_adapter("openai")
        self.name = "injected-first-real-repair"
        self.model = self.real.model
        self.temperature = getattr(self.real, "temperature", None)
        self.timeout = getattr(self.real, "timeout", None)

    def planning_options(self):
        return self.real.planning_options()

    async def complete_query_draft(self, system, user):
        if "repair" in json.loads(user):
            return await self.real.complete_query_draft(system, user)
        return await super().complete_query_draft(system, user)


_adapter = RealRepairPlanner()
_runtimes[DEFAULT_TENANT_KEY] = {"profile": "injected-first-real-repair", "adapter": _adapter,
    "model": _adapter.model, "config_version": "injected-first-real-repair-only", "inherited": False}
