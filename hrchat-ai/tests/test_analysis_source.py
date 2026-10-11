"""I3-1：分析来源 ASK/REPORT 区分。

问答入口契约保持不变（ASK 仍必须带 askId/轮次）；报表来源不得伪造 askId，
必须携带报表与数据版本标识。
"""
import pytest
from pydantic import ValidationError

from agentscope_teams.analysis_contract import AnalysisContext


def base(**overrides):
    context = {
        "task_id": "task-1",
        "tenant_no": "t1",
        "metric_code": "leave_count",
        "metric_version": "v1",
        "unit": "人",
        "data_version": "snap-1",
        "effective_org_ids": [3, 4],
        "scope_ref": "scope-1",
        "current_period": {"start": "2026-09-01", "end": "2026-10-01"},
        "baseline_period": {"start": "2026-08-01", "end": "2026-09-01"},
    }
    context.update(overrides)
    return context


def test_ask_accepts_legacy_payload_without_source_type():
    ctx = AnalysisContext.model_validate(base(source_ask_id="a1", source_turn_id="t1"))
    assert ctx.source_type == "ASK"
    assert ctx.source_ask_id == "a1"


def test_ask_requires_ask_and_turn_ids():
    with pytest.raises(ValidationError):
        AnalysisContext.model_validate(base())


def test_ask_rejects_report_fields():
    with pytest.raises(ValidationError):
        AnalysisContext.model_validate(
            base(source_ask_id="a1", source_turn_id="t1", source_report_id=7))


def test_report_without_ask_id_is_allowed():
    ctx = AnalysisContext.model_validate(
        base(source_type="REPORT", source_report_id=7, source_data_version_id=88))
    assert ctx.source_type == "REPORT"
    assert ctx.source_ask_id is None
    assert ctx.source_turn_id is None


def test_report_rejects_fake_ask_id():
    with pytest.raises(ValidationError):
        AnalysisContext.model_validate(
            base(source_type="REPORT", source_ask_id="a1",
                 source_report_id=7, source_data_version_id=88))


def test_report_requires_report_and_version_ids():
    with pytest.raises(ValidationError):
        AnalysisContext.model_validate(base(source_type="REPORT", source_report_id=7))
