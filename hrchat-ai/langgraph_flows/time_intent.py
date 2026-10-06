"""Deterministic time compilation. Text dates are inclusive; UI CUSTOM end is exclusive."""
from datetime import date, timedelta
import re

from langgraph_flows.query_plan import PlanRejected

PRESETS = {"本月": "THIS_MONTH", "这个月": "THIS_MONTH", "当月": "THIS_MONTH",
           "上月": "LAST_MONTH", "上个月": "LAST_MONTH", "今天": "TODAY", "今日": "TODAY",
           "当前": "TODAY", "目前": "TODAY", "今年": "THIS_YEAR", "去年": "LAST_YEAR",
           "本季度": "THIS_QUARTER", "上季度": "LAST_QUARTER", "上个季度": "LAST_QUARTER"}
_NUMBER = r"(?:\d+|[一二三四五六七八九十两]+)"
_RELATIVE = rf"(?:最近|过去|近)\s*({_NUMBER})\s*(?:个)?(天|日|个月|月)"
_DATE = r"\d{4}-\d{2}-\d{2}"
_MENTION = re.compile(rf"{_DATE}\s*(?:至|到|~|～)\s*{_DATE}|{_DATE}|{_RELATIVE}|" +
                      "|".join(sorted(PRESETS, key=len, reverse=True)))


def _month(d, offset):
    index = d.year * 12 + d.month - 1 + offset
    return date(index // 12, index % 12 + 1, 1)


def _number(value):
    if value.isdecimal():
        return int(value)
    digits = {c: i for i, c in enumerate("零一二三四五六七八九")}
    digits["两"] = 2
    if value == "十": return 10
    if "十" in value:
        left, right = value.split("十", 1)
        return digits.get(left, 1) * 10 + digits.get(right, 0)
    return digits.get(value, 0)


def preset_window(preset, today):
    tomorrow = today + timedelta(days=1)
    first = today.replace(day=1)
    quarter = date(today.year, ((today.month - 1) // 3) * 3 + 1, 1)
    windows = {"TODAY": (today, tomorrow), "THIS_MONTH": (first, tomorrow),
               "LAST_MONTH": (_month(first, -1), first), "THIS_YEAR": (date(today.year, 1, 1), tomorrow),
               "LAST_YEAR": (date(today.year - 1, 1, 1), date(today.year, 1, 1)),
               "THIS_QUARTER": (quarter, tomorrow), "LAST_QUARTER": (_month(quarter, -3), quarter),
               "LAST_7D": (today - timedelta(days=6), tomorrow),
               "LAST_30D": (today - timedelta(days=29), tomorrow)}
    if preset not in windows:
        raise PlanRejected("unresolved_time", "暂无法识别该统计期间，请选择明确日期", "clarify")
    return windows[preset]


def resolve_expression(expression, today):
    if expression in PRESETS:
        return preset_window(PRESETS[expression], today)
    match = re.fullmatch(_RELATIVE, expression)
    if match:
        n = _number(match[1])
        if not 1 <= n <= 366:
            raise PlanRejected("unresolved_time", "时间跨度超出当前支持范围，请选择明确日期", "clarify")
        return ((today - timedelta(days=n - 1)) if match[2] in {"天", "日"}
                else _month(today.replace(day=1), -(n - 1)), today + timedelta(days=1))
    match = re.fullmatch(rf"({_DATE})\s*(?:至|到|~|～)\s*({_DATE})", expression)
    try:
        if match:
            start, end = date.fromisoformat(match[1]), date.fromisoformat(match[2]) + timedelta(days=1)
            if start < end:
                return start, end
        if re.fullmatch(_DATE, expression):
            start = date.fromisoformat(expression)
            return start, start + timedelta(days=1)
    except ValueError:
        pass
    raise PlanRejected("unresolved_time", "暂无法完整解释时间条件，请选择明确日期", "clarify")


def resolve_context_time(selection, today):
    if selection.get("preset") != "CUSTOM":
        return preset_window(selection.get("preset"), today)
    try:
        start, end = date.fromisoformat(selection["start"]), date.fromisoformat(selection["end"])
        if start < end:
            return start, end
    except (KeyError, TypeError, ValueError):
        pass
    raise PlanRejected("invalid_time_selection", "显式日期范围无效", "failed")


def time_mentions(question, today):
    return [(m.group(), resolve_expression(m.group(), today)) for m in _MENTION.finditer(question)]


def has_time_cue(question):
    return bool(re.search(r"截至|截止|同期|期间|昨天|前天|\d\s*[年月日号天]|[上本下近前去今昨].{0,3}[月季年天]|[一二三四五六七八九十]+月", question))
