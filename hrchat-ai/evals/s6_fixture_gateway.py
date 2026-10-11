"""Loopback integration fixture: real services/SDK, scripted model only."""
import asyncio
import json
import os
import uuid

# Never read a developer's key or construct a real model adapter in this fixture.
os.environ["LLM_PROFILE"] = "mock"
from adapters.model_usage import ModelResult
from evals.fixture_gateway import SmokePlanner
from evals.s6_run import FixtureAdapter
from agent_gateway.app import app, _runtimes, DEFAULT_TENANT_KEY

# 首轮能力：报表洞察推荐动作（与 insight.capabilities 保持一致）。
REPORT_INSIGHT_CAPABILITY = "leave_count_department_contribution"


class AnalysisSmokePlanner(SmokePlanner):
    async def complete_analysis(self, system, user):
        # 报表 AI 洞察（【洞察-DRAFT】）：确定性草稿，给出可点击的进一步分析动作。
        # params 留空，由服务端按可信上下文补全并校验，避免 fixture 伪造期间/组织。
        if "【洞察-DRAFT】" in user:
            return ModelResult(
                json.dumps({
                    "selected_fact_ids": [],
                    "claims": [],
                    "limitations": [],
                    "next_actions": [{
                        "action_id": "leave_dept_1",
                        "capability_id": REPORT_INSIGHT_CAPABILITY,
                        "question": "进一步分析两期部门贡献",
                        "params": {},
                        "missing_params": [],
                        "executable": False,
                    }],
                }, ensure_ascii=False),
                self.name, self.model, "fixture_" + uuid.uuid4().hex,
                elapsed_ms=0, finish_reason="stop", usage_source="unknown",
            )
        # Deliberately slow enough to observe progress and exercise cancellation.
        await asyncio.sleep(.3)
        return await FixtureAdapter("conditional_daily_query").complete_analysis(system, user)


_runtimes[DEFAULT_TENANT_KEY] = {"profile": "fixture", "adapter": AnalysisSmokePlanner(),
    "model": "s6-scripted-integration", "config_version": "fixture-only", "inherited": False}
