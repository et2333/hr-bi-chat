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

# 确定性草稿正文片段（纯文本，不含数字/日期/单位；数值一律由事实槽位渲染）。
_INSIGHT_SLOT_WORDS = ("本期变化", "较基期", "重点：", "补充：")


def _insight_draft(user):
    """合法完整草稿：从提示词事实槽位重建正文，供在线评分走 COMPLETE 重建路径。

    只使用提示词暴露的 value 槽位（不伪造期间/组织/单位），predicate 置 null，
    避免 fixture 让生成链路降级为 no_usable_narrative。
    """
    line = next((x for x in user.splitlines() if x.startswith("事实(JSON)：")), None)
    facts = json.loads(line.split("事实(JSON)：", 1)[1])["facts"] if line else []
    renderable = [f for f in facts if f.get("computable") and f.get("value") is not None]

    claims = []
    for index, fact in enumerate(renderable[:4]):
        claim_id = f"f{index + 1}"
        claims.append({
            "claim_id": claim_id,
            "kind": "fact",
            "segments": [
                {"type": "text", "text": _INSIGHT_SLOT_WORDS[index % len(_INSIGHT_SLOT_WORDS)]},
                {"type": "fact", "fact_id": fact["fact_id"], "field": "value"},
            ],
            "fact_ids": [fact["fact_id"]],
            "predicate": None,
        })
    draft = {
        "selected_fact_ids": [f["fact_id"] for f in renderable[:4]],
        "claims": claims,
        "limitations": sorted({f"{f['fact_type']}:{f.get('computable')}" for f in facts
                               if not f.get("computable")}),
        "next_actions": [{
            "action_id": "leave_dept_1",
            "capability_id": REPORT_INSIGHT_CAPABILITY,
            "question": "进一步分析两期部门贡献",
            "params": {},
            "missing_params": [],
            "executable": False,
        }],
    }
    if claims:
        draft["narrative"] = {"summary_claim_id": "f1",
                              "finding_claim_ids": [c["claim_id"] for c in claims[1:4]]}
    return draft


class AnalysisSmokePlanner(SmokePlanner):
    async def complete_analysis(self, system, user):
        # 报表 AI 洞察（【洞察-DRAFT】）：确定性草稿，给出可点击的进一步分析动作。
        # params 留空，由服务端按可信上下文补全并校验，避免 fixture 伪造期间/组织。
        if "【洞察-DRAFT】" in user:
            return ModelResult(
                json.dumps(_insight_draft(user), ensure_ascii=False),
                self.name, self.model, "fixture_" + uuid.uuid4().hex,
                elapsed_ms=0, finish_reason="stop", usage_source="unknown",
            )
        # Deliberately slow enough to observe progress and exercise cancellation.
        await asyncio.sleep(.3)
        return await FixtureAdapter("conditional_daily_query").complete_analysis(system, user)


_runtimes[DEFAULT_TENANT_KEY] = {"profile": "fixture", "adapter": AnalysisSmokePlanner(),
    "model": "s6-scripted-integration", "config_version": "fixture-only", "inherited": False}
