"""本地演示数据目录与执行器（MockLLM 模式下的确定性数据源）。

- 指标语义目录：同义词最长匹配 → 指标 code（与 Java LocalAgentRuntimeImpl 对齐）。
- 时间窗口解析：问句关键词/context_override → [start, end) 左闭右开。
- DemoQueryExecutor：按指标返回确定性的当期/上期数值，供执行节点与归因团队复用。
- 组织权限（BR-02 演示）：按 X-User-No 授予组织子树；提及未授权组织抛 HRC-2003。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, timedelta

# 演示数据最新同步时间（BR-09 数据时效标注）
DATA_UPDATED_AT = "2026-09-12T06:00:00+08:00"

# 固定演示基准日期（确定性）
DEMO_NOW = date(2026, 9, 12)


@dataclass(frozen=True)
class MetricMeta:
    code: str
    name: str
    unit: str
    percent: bool  # True 表示数值为小数比例，呈现时 ×100 加 %
    formula: str  # 口径表达式（演示 SQL 展示用）


# 指标目录（与 Java 侧同名）
METRICS: dict[str, MetricMeta] = {
    m.code: m
    for m in (
        MetricMeta("headcount", "在职人数", "人", False, "COUNT(*) FROM fact_headcount"),
        MetricMeta("hire_count", "入职人数", "人", False, "COUNT(*) FROM fact_hire"),
        MetricMeta("leave_count", "离职人数", "人", False, "COUNT(*) FROM fact_leave"),
        MetricMeta("turnover_rate", "离职率", "%", True, "leave_count / headcount"),
        MetricMeta("attendance_rate", "出勤率", "%", True, "attendance_count / headcount"),
        MetricMeta("payroll_total", "人力成本", "元", False, "SUM(payroll) FROM fact_payroll"),
        MetricMeta("avg_salary", "平均薪酬", "元", False, "payroll_total / headcount"),
    )
}

# 指标同义词 → metric code（仅保留最长命中，与 Java resolve 一致）
METRIC_SYNONYMS: list[tuple[str, str]] = [
    ("在职人数", "headcount"),
    ("在职员工", "headcount"),
    ("在职", "headcount"),
    ("入职人数", "hire_count"),
    ("入职", "hire_count"),
    ("离职人数", "leave_count"),
    ("离职率", "turnover_rate"),
    ("离职", "leave_count"),
    ("出勤率", "attendance_rate"),
    ("考勤率", "attendance_rate"),
    ("人力成本", "payroll_total"),
    ("薪酬总额", "payroll_total"),
    ("平均薪酬", "avg_salary"),
    ("平均工资", "avg_salary"),
    ("薪酬", "avg_salary"),
]

# 组织同义词 → 组织键（演示权限用）
ORG_SYNONYMS: list[tuple[str, str]] = [
    ("研发中心", "研发"),
    ("研发部", "研发"),
    ("研发", "研发"),
    ("产品中心", "产品"),
    ("产品部", "产品"),
    ("产品", "产品"),
    ("销售中心", "销售"),
    ("销售部", "销售"),
    ("销售", "销售"),
    ("市场部", "市场"),
    ("市场", "市场"),
]

# 用户授权组织（BR-02 演示：hr01=研发/产品 HRBP，hr02=销售，hr03=市场；未知用户默认全授权）
GRANTED_ORGS: dict[str, set[str]] = {
    "hr01": {"研发", "产品"},
    "hr02": {"销售"},
    "hr03": {"市场"},
}


# =====================================================================
# 时间窗口
# =====================================================================
@dataclass(frozen=True)
class Window:
    start: date
    end: date  # 左闭右开

    @property
    def label(self) -> str:
        return f"{self.start.isoformat()}/{self.end.isoformat()}"

    @property
    def prev_label(self) -> str:
        return self.prev.label

    def prev(self) -> "Window":
        days = (self.end - self.start).days
        return Window(self.start - timedelta(days=days), self.end - timedelta(days=days))


def _add_months(d: date, months: int) -> date:
    """返回 d 顺移 months 个月后的日期（d 建议为每月 1 号）。"""
    idx = d.month - 1 + months
    return date(d.year + idx // 12, idx % 12 + 1, 1)


def _preset_window(preset: str) -> Window | None:
    now = DEMO_NOW
    if preset == "LAST_7D":
        return Window(now - timedelta(days=6), now + timedelta(days=1))
    if preset == "LAST_30D":
        return Window(now - timedelta(days=29), now + timedelta(days=1))
    if preset == "THIS_MONTH":
        first_this = now.replace(day=1)
        return Window(first_this, _add_months(first_this, 1))
    if preset == "LAST_MONTH":
        first_this = now.replace(day=1)
        return Window(_add_months(first_this, -1), first_this)
    if preset == "THIS_QUARTER":
        q_start = date(now.year, ((now.month - 1) // 3) * 3 + 1, 1)
        return Window(q_start, _add_months(q_start, 3))
    if preset == "LAST_QUARTER":
        q_start = date(now.year, ((now.month - 1) // 3) * 3 + 1, 1)
        return Window(_add_months(q_start, -3), q_start)
    if preset == "THIS_YEAR":
        return Window(date(now.year, 1, 1), date(now.year + 1, 1, 1))
    if preset == "LAST_YEAR":
        return Window(date(now.year - 1, 1, 1), date(now.year, 1, 1))
    return None


def resolve_window(question: str, context_override: dict | None) -> Window | None:
    """时间范围：context_override 显式覆盖 > 问句关键词（与 Java resolveWindow 一致）。"""
    co = context_override or {}
    time_range = co.get("time_range") or {}
    if time_range.get("preset"):
        if time_range["preset"] == "CUSTOM" and time_range.get("start") and time_range.get("end"):
            start = date.fromisoformat(time_range["start"][:10])
            end = date.fromisoformat(time_range["end"][:10]) + timedelta(days=1)
            return Window(start, end)
        return _preset_window(time_range["preset"])
    if "上月" in question:
        return _preset_window("LAST_MONTH")
    if "本月" in question or "这个月" in question:
        return _preset_window("THIS_MONTH")
    if "上季度" in question or "上个季度" in question:
        return _preset_window("LAST_QUARTER")
    if "今年" in question:
        return _preset_window("THIS_YEAR")
    if "去年" in question:
        return _preset_window("LAST_YEAR")
    if "近7天" in question or "最近7天" in question:
        return _preset_window("LAST_7D")
    if "近30天" in question or "最近30天" in question:
        return _preset_window("LAST_30D")
    return None


# =====================================================================
# 语义解析（同义词最长匹配）
# =====================================================================
def resolve_metrics(question: str) -> list[str]:
    """问句 → 指标 code 候选（同义词最长匹配，仅保留最长命中组）。"""
    best_len = -1
    hits: list[str] = []
    for term, code in METRIC_SYNONYMS:
        if term in question and len(term) > best_len:
            best_len = len(term)
            hits = [code]
        elif term in question and len(term) == best_len:
            hits.append(code)
    return hits


def resolve_orgs(question: str) -> list[str]:
    """问句 → 组织键候选（同义词最长匹配）。"""
    best_len = -1
    hits: list[str] = []
    for term, key in ORG_SYNONYMS:
        if term in question and len(term) > best_len:
            best_len = len(term)
            hits = [key]
        elif term in question and len(term) == best_len:
            hits.append(key)
    return hits


def check_org_permission(user_no: str | None, org_keys: list[str]) -> None:
    """BR-02：问句提及组织须在用户授权范围内，否则抛越权异常。"""
    if not org_keys:
        return
    granted = GRANTED_ORGS.get(user_no or "", None)
    if granted is None:
        return  # 未知用户默认全授权（本地演示）
    denied = [k for k in org_keys if k not in granted]
    if denied:
        raise PermissionError(denied[0])


# =====================================================================
# 演示执行器
# =====================================================================
# 确定性数值：current=当期，prev=上期（headcount 保持 1240 + 58 - 23 = 1275 自洽）
DEMO_VALUES: dict[str, dict[str, float]] = {
    "headcount": {"current": 1275.0, "prev": 1240.0},
    "hire_count": {"current": 58.0, "prev": 51.0},
    "leave_count": {"current": 23.0, "prev": 27.0},
    "turnover_rate": {"current": 0.0188, "prev": 0.0205},
    "attendance_rate": {"current": 0.9746, "prev": 0.9703},
    "payroll_total": {"current": 18527000.0, "prev": 17860000.0},
    "avg_salary": {"current": 15800.0, "prev": 15420.0},
}

_METRIC_IN_SQL = re.compile(r'AS\s+"([^"]+)"')


def extract_metric_code(sql: str) -> str:
    m = _METRIC_IN_SQL.search(sql)
    if not m:
        raise ValueError(f"无法从 SQL 解析指标 code: {sql!r}")
    return m.group(1)


class DemoQueryExecutor:
    """确定性演示执行器：解析 SQL 中的指标 code，返回当期/上期数值。"""

    def execute(self, sql: str, window: Window | None, prev_window: bool = False) -> dict:
        code = extract_metric_code(sql)
        if code not in DEMO_VALUES:
            raise ValueError(f"演示数据缺少指标: {code}")
        phase = "prev" if prev_window else "current"
        return {"code": code, "value": DEMO_VALUES[code][phase], "rows": 1}
