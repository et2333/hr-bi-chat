"""Opt-in isolated H2 evaluation only. Raw prompts/replies stay in ignored run output."""
import json
import os
from pathlib import Path

from pydantic import ValidationError

from adapters.model_usage import ModelCallError
from adapters.openai_client import OpenAIClientImpl
from agent_gateway.app import app, _runtimes, DEFAULT_TENANT_KEY
from evals.reference import ROOT
from evals.planner_experiment import contract_notes
from langgraph_flows.query_plan import QueryPlan
from langgraph_flows.query_draft import ModelQueryDraft, DRAFT_VERSION


def output_directory():
    value = os.getenv("HRCHAT_PLANNER_DIAGNOSTIC_DIR")
    if not value:
        raise RuntimeError("Use evals.launch_remote --planner-diagnostics")
    path = Path(value).resolve()
    if not path.is_relative_to((ROOT / "docs/evaluation-runs").resolve()):
        raise RuntimeError("Diagnostic output must stay in the ignored evaluation directory")
    path.mkdir(parents=True, exist_ok=True)
    return path


class DiagnosticPlanner(OpenAIClientImpl):
    # Keep the failed v1 experiment replayable, explicitly outside the v2 runtime.
    supports_query_draft = os.getenv("HRCHAT_PLANNER_VARIANT", "baseline") == "baseline"

    async def complete_query_draft(self, system_prompt, user_prompt):
        record = {"scope": "isolated_mock_data_only", "variant": DRAFT_VERSION,
                  "messages": [{"role": "system", "content": system_prompt}, {"role": "user", "content": user_prompt}]}
        try:
            result = await super().complete_query_draft(system_prompt, user_prompt)
        except ModelCallError as exc:
            record.update(model_call=exc.result.evidence(), exception_type=type(exc.__cause__).__name__)
            self.save(exc.result.call_id, record)
            raise
        record.update(model_call=result.evidence(), response_text=result.text)
        try:
            ModelQueryDraft.model_validate_json(result.text)
            record["schema_valid"] = True
        except ValidationError as exc:
            record["schema_valid"] = False
            record["schema_errors"] = exc.errors(include_url=False, include_context=False, include_input=False)
        self.save(result.call_id, record)
        return result

    async def complete(self, prompt):
        variant = os.getenv("HRCHAT_PLANNER_VARIANT", "baseline")
        original = prompt
        if variant == "contract-notes-v1":
            prompt = contract_notes(prompt)
        elif variant != "baseline":
            raise ValueError("Unknown diagnostic prompt variant")
        record = {"scope": "isolated_mock_data_only", "variant": variant,
                  "original_prompt": original, "prompt": prompt}
        try:
            result = await super().complete(prompt)
        except ModelCallError as exc:
            record.update(model_call=exc.result.evidence(),
                          exception_type=type(exc.__cause__).__name__)
            self.save(exc.result.call_id, record)
            raise
        record.update(model_call=result.evidence(), response_text=result.text)
        try:
            QueryPlan.model_validate_json(result.text)
            record["schema_valid"] = True
        except ValidationError as exc:
            record["schema_valid"] = False
            record["schema_errors"] = exc.errors(include_url=False, include_context=False, include_input=False)
        self.save(result.call_id, record)
        return result

    def save(self, call_id, record):
        (output_directory() / (call_id + ".json")).write_text(
            json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")


_adapter = DiagnosticPlanner()
_runtimes[DEFAULT_TENANT_KEY] = {"profile": "openai", "adapter": _adapter,
    "model": _adapter.model, "base_url": _adapter.base_url,
    "config_version": "planner-diagnostics-" + os.getenv("HRCHAT_PLANNER_VARIANT", "baseline"),
    "inherited": False}
