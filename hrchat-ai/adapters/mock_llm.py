"""MockLLMImpl：本地演示/测试用确定性模型适配器。

按 prompt 中的功能标记返回规则化结果，保证 pytest 与本地演示完全可复现。
标记约定（与 ask_flow / 归因团队一致）：

- ``【意图判断】``  →  ``CHITCHAT | QUERY``
- ``【闲聊回复】``  →  问候语模板
- ``【结论摘要】``  →  依据传入数值生成结论句（确定性）
- ``【归因-LEADER】`` → 归因计划 JSON
- ``【归因-DATA】``   → 数据块 JSON（回显）
- ``【归因-ANALYST】`` → 贡献计算 JSON
- ``【归因-WRITER】`` → 归因结论文案
"""
from __future__ import annotations

import json
import re

from adapters.llm_adapter import ModelAdapter

# 问候词（与 Java LocalAgentRuntimeImpl.GREETINGS 对齐）
GREETINGS = (
    "你好", "您好", "hello", "hi", "嗨", "谢谢", "感谢", "再见", "拜拜",
    "你是谁", "介绍一下", "你能做什么",
)


def _extract(prompt: str, key: str, default: str = "") -> str:
    """提取 prompt 中 ``key=..`` 形式的键值（用于确定性模板填充）。"""
    m = re.search(rf"{key}=([^|\n]+)", prompt)
    return m.group(1).strip() if m else default


class MockLlmAdapter(ModelAdapter):
    """确定性输出的本地演示适配器（MockLLMImpl）。"""

    name = "mock"

    async def generate(self, prompt: str) -> str:
        return self._dispatch(prompt)

    def _dispatch(self, prompt: str) -> str:
        if "【意图判断】" in prompt:
            question = prompt.split("【意图判断】", 1)[1].strip()
            return self._classify(question)
        if "【闲聊回复】" in prompt:
            return "您好，我是 HR 智能问数助手，可以帮您查询在职人数、入职/离职、离职率、人力成本、平均薪酬等数据。请问您想了解什么？"
        if "【结论摘要】" in prompt:
            return self._summary(prompt)
        if "【归因-LEADER】" in prompt:
            return self._attribution_plan(prompt)
        if "【归因-DATA】" in prompt:
            return self._data_block(prompt)
        if "【归因-ANALYST】" in prompt:
            return self._analyst(prompt)
        if "【归因-WRITER】" in prompt:
            return self._writer(prompt)
        if "【洞察-DRAFT】" in prompt:
            return self._insight_draft(prompt)
        if any(g.lower() in prompt.lower() for g in GREETINGS):
            return "您好，我是 HR 智能问数助手，可以帮您查询在职人数、入职/离职、离职率、人力成本、平均薪酬等数据。请问您想了解什么？"
        return "（mock）已收到"

    # ------------------------------------------------------------------
    # 确定性规则
    # ------------------------------------------------------------------
    def _classify(self, question: str) -> str:
        lowered = question.lower()
        return "CHITCHAT" if any(g.lower() in lowered for g in GREETINGS) else "QUERY"

    def _summary(self, prompt: str) -> str:
        metric = _extract(prompt, "metric", "指标")
        current = _extract(prompt, "current")
        compare = _extract(prompt, "compare")
        prev_period = _extract(prompt, "prev_period")
        unit = _extract(prompt, "unit")
        percent = _extract(prompt, "percent", "0") == "1"
        suffix = "%" if percent else (f" {unit}" if unit else "")

        if not current:
            return "暂未查询到相关数据，建议调整组织范围或时间后重试。"
        text = f"{metric}为 {current}{suffix}"
        if compare and prev_period:
            cur = float(current.rstrip("%"))
            prev = float(compare.rstrip("%"))
            trend = "上升" if cur > prev else ("下降" if cur < prev else "持平")
            text += f"，环比{prev_period}（{compare}{suffix}）{trend}"
        return text

    def _attribution_plan(self, prompt: str) -> str:
        metric = _extract(prompt, "metric", "指标")
        return json.dumps(
            {
                "plan": [
                    {"step_id": "s1", "title": "确认指标口径与时间范围"},
                    {"step_id": "s2", "title": f"并行取数：{metric} 当期/上期"},
                    {"step_id": "s3", "title": "贡献拆解与置信度评估"},
                    {"step_id": "s4", "title": "撰写归因结论"},
                ],
                "budget_tokens": 2000,
            },
            ensure_ascii=False,
        )

    def _data_block(self, prompt: str) -> str:
        # 数据已由团队注入 prompt，mock 仅做回显（确定性）
        data = _extract(prompt, "data")
        try:
            parsed = json.loads(data) if data else {}
        except json.JSONDecodeError:
            parsed = {}
        return json.dumps(parsed, ensure_ascii=False)

    def _analyst(self, prompt: str) -> str:
        waterfall = _extract(prompt, "waterfall", "[]")
        net_change = _extract(prompt, "net_change", "0")
        try:
            items = json.loads(waterfall)
        except json.JSONDecodeError:
            items = []
        out = [{"dimension": i.get("dimension", ""), "value": i.get("value", 0)} for i in items]
        return json.dumps(
            {"waterfall": out, "net_change": float(net_change), "confidence": 0.92},
            ensure_ascii=False,
        )

    def _writer(self, prompt: str) -> str:
        metric = _extract(prompt, "metric", "指标")
        current = _extract(prompt, "current", "-")
        prev = _extract(prompt, "prev", "-")
        summary = (
            f"{metric}由上期 {prev} 变动至本期 {current}。"
            "经结构拆解，主要受期内入职/离职因素驱动；"
            "以上为辅助分析结果，仅供参考。"
        )
        return summary

    def _insight_draft(self, prompt: str) -> str:
        """确定性洞察草稿：从 prompt 内嵌事实 JSON 选择 preferred 事实并生成引用。"""
        facts = self._insight_facts(prompt)
        computable = [f for f in facts if f.get("computable")]
        preferred = [f for f in computable if f.get("preferred")] or computable
        selected = preferred[:3]
        claims = []
        if selected:
            fact = selected[0]
            claims.append(
                {
                    "claim_id": "c1",
                    "kind": "fact",
                    "segments": [
                        {"type": "text", "text": "重点关注该项变化："},
                        {"type": "fact", "fact_id": fact["fact_id"], "field": "value"},
                        {"type": "text", "text": " "},
                        {"type": "fact", "fact_id": fact["fact_id"], "field": "unit"},
                    ],
                    "fact_ids": [fact["fact_id"]],
                    "predicate": self._insight_predicate(fact),
                    "unit": fact.get("unit"),
                    "scope_ref": fact.get("scope_ref"),
                }
            )
        return json.dumps(
            {
                "selected_fact_ids": [f["fact_id"] for f in selected],
                "claims": claims,
                "narrative": {"summary_claim_id": "c1" if claims else None, "finding_claim_ids": []},
                "limitations": [],
                "next_actions": [],
            },
            ensure_ascii=False,
        )

    @staticmethod
    def _insight_facts(prompt: str) -> list[dict]:
        for line in prompt.splitlines():
            if '"facts"' not in line:
                continue
            start, end = line.find("{"), line.rfind("}")
            if start < 0 or end <= start:
                continue
            try:
                parsed = json.loads(line[start:end + 1])
            except json.JSONDecodeError:
                continue
            facts = parsed.get("facts")
            if isinstance(facts, list):
                return facts
        return []

    @staticmethod
    def _insight_predicate(fact: dict) -> str | None:
        value = fact.get("value")
        if not isinstance(value, (int, float)):
            return None
        fact_type = fact.get("fact_type")
        if fact_type == "absolute_change":
            return "absolute_increase" if value > 0 else "absolute_decrease" if value < 0 else None
        if fact_type == "relative_change":
            return "relative_increase" if value > 0 else "relative_decrease" if value < 0 else None
        return None


# 向后兼容别名（S6 骨架时期命名）
LlmAdapter = ModelAdapter


def get_llm_adapter(
    profile: str,
    base_url: str | None = None,
    api_key: str | None = None,
    model: str | None = None,
    temperature: float = 0.2,
) -> ModelAdapter:
    """ModelAdapter 工厂。

    Args:
        profile: 配置档位，"mock" 本地演示/测试；"openai" 真实 LLM。
        base_url/api_key/model/temperature: openai 分支的覆盖参数（热应用下发）。

    Returns:
        对应适配器实例。

    Raises:
        ValueError: 未知 profile。
    """
    if profile == "mock":
        return MockLlmAdapter()
    if profile == "openai":
        from adapters.openai_client import OpenAIClientImpl

        return OpenAIClientImpl(
            base_url=base_url,
            api_key=api_key,
            model=model,
            temperature=temperature,
        )
    raise ValueError(f"暂不支持的 LLM profile: {profile!r}")
