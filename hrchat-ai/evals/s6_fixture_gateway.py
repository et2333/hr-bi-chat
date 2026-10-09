"""Loopback integration fixture: real services/SDK, scripted model only."""
import asyncio
import os

# Never read a developer's key or construct a real model adapter in this fixture.
os.environ["LLM_PROFILE"] = "mock"
from evals.fixture_gateway import SmokePlanner
from evals.s6_run import FixtureAdapter
from agent_gateway.app import app, _runtimes, DEFAULT_TENANT_KEY


class AnalysisSmokePlanner(SmokePlanner):
    async def complete_analysis(self, system, user):
        # Deliberately slow enough to observe progress and exercise cancellation.
        await asyncio.sleep(.3)
        return await FixtureAdapter("conditional_daily_query").complete_analysis(system, user)


_runtimes[DEFAULT_TENANT_KEY] = {"profile": "fixture", "adapter": AnalysisSmokePlanner(),
    "model": "s6-scripted-integration", "config_version": "fixture-only", "inherited": False}
