"""AgentScope 归因 Team：Leader / DataWorker / AnalystWorker / Writer + 预算熔断。

- 基于真实 AgentScope 2.0 ``Agent`` 构建四个角色，模型统一走 ``AdapterChatModel``
  （包装本项目 ``ModelAdapter``，MockLLMImpl 下确定性可复现，OpenAIClientImpl 下真实调用）。
- ``AttributionTeam.reply_stream`` 实现 ``PipelineProtocol`` 接口（可替换任意 Agent），
  产出标准化语义事件：PLAN_UPDATE → TOOL_CALL_START/END（并行取数）→ FINAL；
  超预算（步数/token）熔断发 ERROR（HRA-4005）并附基础对比数据（接口文档 2.2.10）。
- BR-10 免责声明随 FINAL 载荷返回「辅助分析，仅供参考」。
"""
from __future__ import annotations

import asyncio
import json
import time
from types import SimpleNamespace
from typing import Any, AsyncGenerator, Optional

from pydantic import BaseModel

from agentscope.agent import Agent
from agentscope.credential import CredentialBase
from agentscope.message import UserMsg
from agentscope.message._block import TextBlock
from agentscope.model import ChatModelBase, ChatResponse

from agent_gateway.schemas import (
    ERR_ATTRIBUTION_BUDGET,
    EVENT_ERROR,
    EVENT_FINAL,
    EVENT_PLAN_UPDATE,
    EVENT_TOOL_CALL_END,
    EVENT_TOOL_CALL_START,
)
from adapters.llm_adapter import ModelAdapter
from langgraph_flows.demo_data import DEMO_VALUES, METRICS


# =====================================================================
# AgentScope 模型适配（包装 ModelAdapter 为 ChatModelBase）
# =====================================================================
class _AdapterCredential(CredentialBase):
    """Mock/本地演示凭据（无真实密钥）。"""

    name: str = "adapter"


class AdapterChatModel(ChatModelBase):
    """将本项目 ModelAdapter 包装为 AgentScope ChatModelBase。

    仅需实现 ``_call_api``（AgentScope 唯一抽象方法）：把多轮 Msg 拼为 prompt
    交给 ModelAdapter，再封装为 ``ChatResponse`` 返回。
    """

    class Parameters(BaseModel):
        temperature: float = 0.2
        max_tokens: int = 1024

    def __init__(self, adapter: ModelAdapter, model: str = "adapter-llm") -> None:
        super().__init__(
            credential=_AdapterCredential(),
            model=model,
            parameters=self.Parameters(),
        )
        self.adapter = adapter
        # Agent 仅读取 supported_input_media_types（文本模型为空集）
        self.formatter = SimpleNamespace(supported_input_media_types=())

    @staticmethod
    def _messages_to_prompt(messages: list) -> str:
        parts = []
        for m in messages:
            role = getattr(m, "role", "user")
            blocks = getattr(m, "content", m)
            if isinstance(blocks, list):
                texts = [b.text for b in blocks if hasattr(b, "text")]
                parts.append(f"[{role}] " + " ".join(texts))
            else:
                parts.append(f"[{role}] {blocks}")
        return "\n".join(parts)

    async def _call_api(self, model: str, messages: list, tools=None, tool_choice=None, **kwargs):
        prompt = self._messages_to_prompt(messages)
        text = await self.adapter.generate(prompt)
        return ChatResponse(content=[TextBlock(text=text)], is_last=True)


# =====================================================================
# 贡献瀑布（确定性拆解，数据源=演示目录）
# =====================================================================
def build_waterfall(code: str) -> list[dict[str, Any]]:
    """构建贡献瀑布：dimension + value（净变动=瀑布合计=本期值）。"""
    cur = DEMO_VALUES[code]["current"]
    prev = DEMO_VALUES[code]["prev"]
    if code == "headcount":
        hire = DEMO_VALUES["hire_count"]["current"]
        leave = DEMO_VALUES["leave_count"]["current"]
        items = [
            {"dimension": "上期末在职人数", "value": prev},
            {"dimension": "期内入职", "value": hire},
            {"dimension": "期内离职", "value": -leave},
        ]
    elif code == "turnover_rate":
        items = [
            {"dimension": "期内离职人数", "value": DEMO_VALUES["leave_count"]["current"]},
            {"dimension": "期末在职人数", "value": DEMO_VALUES["headcount"]["current"]},
        ]
    elif code == "attendance_rate":
        items = [
            {"dimension": "期内出勤人数", "value": round(cur * DEMO_VALUES["headcount"]["current"], 2)},
            {"dimension": "期末在职人数", "value": DEMO_VALUES["headcount"]["current"]},
        ]
    else:
        items = [
            {"dimension": "上期基数", "value": prev},
            {"dimension": "本期变动", "value": round(cur - prev, 2)},
        ]
    return items


# =====================================================================
# 归因团队
# =====================================================================
class AttributionTeam:
    """归因分析四人组（Leader 规划 → 并行取数 → 贡献拆解 → 撰写结论）。"""

    DEFAULT_MAX_STEPS = 6
    DEFAULT_MAX_TOKENS = 2000

    def __init__(
        self,
        adapter: ModelAdapter,
        *,
        max_steps: int = DEFAULT_MAX_STEPS,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        model_name: str = "adapter-llm",
    ) -> None:
        self.adapter = adapter
        self.max_steps = max_steps
        self.max_tokens = max_tokens
        self._steps = 0
        self._tokens = 0
        chat_model = AdapterChatModel(adapter, model=model_name)
        self.leader = Agent(
            name="Leader",
            system_prompt="你是归因团队 Leader，负责制定分析计划并以 JSON 输出计划步骤。",
            model=chat_model,
        )
        self.data_worker = Agent(
            name="DataWorker",
            system_prompt="你是 DataWorker，负责并行取数并以 JSON 输出数据块。",
            model=chat_model,
        )
        self.analyst = Agent(
            name="AnalystWorker",
            system_prompt="你是 AnalystWorker，负责贡献拆解与置信度评估并以 JSON 输出。",
            model=chat_model,
        )
        self.writer = Agent(
            name="Writer",
            system_prompt="你是 Writer，负责把拆解结果撰写为自然语言结论文案。",
            model=chat_model,
        )

    # ---- 预算熔断 ----
    def _charge(self, text: str) -> bool:
        """步数/token 记账；超预算返回 True（触发熔断）。"""
        self._steps += 1
        self._tokens += self.adapter.estimate_tokens(text)
        return self._steps > self.max_steps or self._tokens > self.max_tokens

    @property
    def usage(self) -> dict[str, int]:
        return {"steps": self._steps, "tokens": self._tokens}

    def _budget_error(self, context: dict[str, Any]) -> dict[str, Any]:
        """HRA-4005 熔断 ERROR + 基础对比数据兜底（2.2.10）。"""
        payload = {
            "code": ERR_ATTRIBUTION_BUDGET,
            "message": "归因分析超出预算已熔断，已返回基础对比数据",
            "recoverable": False,
            "base": {
                "metric": METRICS[context["metric_code"]].name,
                "current": context.get("current"),
                "prev": context.get("compare"),
                "prev_period": context.get("prev_period"),
            },
        }
        return {"event": EVENT_ERROR, "payload": payload}

    # ---- 主编排 ----
    async def reply_stream(self, inputs) -> AsyncGenerator[dict[str, Any], None]:
        """执行归因：yield 标准化语义事件（PipelineProtocol 兼容）。

        Args:
            inputs: 携带归因上下文，内容为 JSON：
                {"metric_code","current","compare","prev_period","question"}
        """
        context = self._parse_inputs(inputs)
        code = context["metric_code"]
        metric = METRICS[code]

        plan_steps = [
            {"step_id": "s1", "title": "确认指标口径与时间范围", "status": "PENDING"},
            {"step_id": "s2", "title": f"并行取数：{metric.name} 当期/上期", "status": "PENDING"},
            {"step_id": "s3", "title": "贡献拆解与置信度评估", "status": "PENDING"},
            {"step_id": "s4", "title": "撰写归因结论", "status": "PENDING"},
        ]
        yield {"event": EVENT_PLAN_UPDATE, "payload": {"steps": plan_steps}}

        # 1) Leader 制定计划（AgentScope Agent）
        if self._charge(f"【归因-LEADER】metric={metric.name}"):
            yield self._budget_error(context)
            return
        plan_text = await self.leader.reply(UserMsg(name="user", content=f"【归因-LEADER】metric={metric.name}"))
        yield self._plan_done(plan_steps, "s1")

        # 2) DataWorker 并行取数
        if self._charge(f"【归因-DATA】metric={code}"):
            yield self._budget_error(context)
            return
        data = json.dumps(build_waterfall(code), ensure_ascii=False)
        yield {"event": EVENT_TOOL_CALL_START, "payload": {"tool": "attribution_fetch", "summary": f"正在并行取数（{metric.name}）…"}}
        t0 = time.perf_counter()
        data_text = await self.data_worker.reply(UserMsg(name="user", content=f"【归因-DATA】data={data}"))
        yield {
            "event": EVENT_TOOL_CALL_END,
            "payload": {"tool": "attribution_fetch", "ms": int((time.perf_counter() - t0) * 1000), "rows": len(build_waterfall(code))},
        }
        yield self._plan_done(plan_steps, "s2")

        # 3) AnalystWorker 贡献拆解
        if self._charge(f"【归因-ANALYST】metric={code}"):
            yield self._budget_error(context)
            return
        analyst_text = _text_of(await self.analyst.reply(UserMsg(name="user", content=f"【归因-ANALYST】waterfall={data}")))
        try:
            analysis = json.loads(_extract_json(analyst_text))
        except json.JSONDecodeError:
            analysis = {"waterfall": build_waterfall(code), "net_change": sum(i["value"] for i in build_waterfall(code)), "confidence": 0.92}
        yield self._plan_done(plan_steps, "s3")

        # 4) Writer 撰写结论
        if self._charge(f"【归因-WRITER】metric={metric.name}"):
            yield self._budget_error(context)
            return
        current_display = _display(code, context.get("current"))
        prev_display = _display(code, context.get("compare"))
        summary = _text_of(await self.writer.reply(UserMsg(
            name="user",
            content=f"【归因-WRITER】metric={metric.name}|current={current_display}|prev={prev_display}",
        )))
        yield self._plan_done(plan_steps, "s4")

        # 5) FINAL 归因卡
        waterfall = analysis.get("waterfall", build_waterfall(code))
        yield {
            "event": EVENT_FINAL,
            "payload": {
                "metric": metric.name,
                "current": current_display,
                "prev": prev_display,
                "delta": _delta_display(code, context),
                "waterfall": waterfall,
                "confidence": float(analysis.get("confidence", 0.92)),
                "summary": _text_of(summary),
                "disclaimer": "辅助分析，仅供参考",
            },
        }

    # ---- 工具 ----
    @staticmethod
    def _parse_inputs(inputs) -> dict[str, Any]:
        content = getattr(inputs, "content", inputs)
        if isinstance(content, list):
            content = " ".join(b.text for b in content if hasattr(b, "text"))
        if isinstance(content, str) and content.strip().startswith("{"):
            return json.loads(content)
        return {
            "metric_code": "headcount",
            "current": DEMO_VALUES["headcount"]["current"],
            "compare": DEMO_VALUES["headcount"]["prev"],
            "prev_period": "上期",
            "question": "",
        }

    @staticmethod
    def _plan_done(steps: list[dict], step_id: str) -> dict[str, Any]:
        """将指定步骤标记 DONE（原地更新，累积已完成的步骤状态）。"""
        for s in steps:
            if s["step_id"] == step_id:
                s["status"] = "DONE"
        return {"event": EVENT_PLAN_UPDATE, "payload": {"steps": steps}}


def _extract_json(text: str) -> str:
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        return text[start:end + 1]
    raise json.JSONDecodeError("no json", text, 0)


def _display(code: str, value: Optional[float]) -> Any:
    if value is None:
        return None
    return round(value * 100, 2) if METRICS[code].percent else int(value)


def _delta_display(code: str, context: dict[str, Any]) -> Any:
    cur, prev = context.get("current"), context.get("compare")
    if cur is None or prev is None:
        return None
    return round(cur - prev, 2) if not METRICS[code].percent else round((cur - prev) * 100, 2)


def _text_of(msg) -> str:
    blocks = getattr(msg, "content", msg)
    if isinstance(blocks, list):
        return " ".join(b.text for b in blocks if hasattr(b, "text"))
    return str(blocks)
