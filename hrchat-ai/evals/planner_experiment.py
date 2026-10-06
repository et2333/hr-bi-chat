"""Development-only prompt candidate; not enabled in the application runtime."""
from datetime import date, timedelta
import json


def contract_notes(prompt):
    instructions, raw = prompt.split("INPUT_JSON:\n", 1)
    data = json.loads(raw)
    today = date.fromisoformat(data["catalog"]["as_of_date"])
    month_start = today.replace(day=1)
    previous_start = (month_start - timedelta(days=1)).replace(day=1)
    data["resolved_calendar"] = {
        "本月": {"start": month_start.isoformat(), "end": (today + timedelta(days=1)).isoformat()},
        "上月": {"start": previous_start.isoformat(), "end": month_start.isoformat()},
        "boundary": "start inclusive, end exclusive",
    }
    notes = """
输出契约的补充约束（必须与上面的 Schema 一起满足）：
1. question 是当前需要理解的问题；context_override=null、confirmed_metric=null 仅表示没有额外选择，绝不表示用户缺少指标或时间。先从 question 提取已明确的槽位。
2. 与目录名称或别名明确匹配的指标/组织直接选取；“是多少/人数”默认 scalar，无需追问查询模式。已说“上月/本月”不属于缺时间。
3. org_scope 二选一：目录命中时只填 org_id 和 include_children，省略 requested_name；未命中时只填 requested_name 和 include_children，省略 org_id。绝不能同时填两个非空值。
4. clarification_options 只用于存在多个指标候选时的指标选择，option_id 必须是真实 metric code，label 使用目录名称。缺时间、缺组织时该列表为空，不得编造 clarify_metric、metric_1、org_1 等 ID。
5. time_type 必须复制所选指标目录中的值。在职人数 headcount 永远是 as_of，即使用户选择了 CUSTOM 日期范围，也不能改成 period。start/end 仍原样保留用户的显式日期选择。
6. 本月/上月使用 resolved_calendar 的 start/end；结束日已按右开边界处理，不要再减一天。仅在显示时由程序转换。
7. 入职/离职缺少期间必须 clarify/missing_slots，不得补本月。确实需要澄清时保留已明确的 metric_codes/org_scope，仅在 missing_slots 填真正缺少的条件。
8. S2 不支持跨轮记忆，slot_updates 使用空列表；source_turn_ids 只放当前 turn_id。未知组织仍交由 Java 核查，不得猜 ID；不支持的任务仍返回 unsupported。
"""
    return instructions + notes + "\nINPUT_JSON:\n" + json.dumps(data, ensure_ascii=False)
