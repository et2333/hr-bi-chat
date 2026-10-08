"""Request-scoped limits shared by planner calls and MCP transport attempts."""
from contextvars import ContextVar
from dataclasses import dataclass, field
import time


class QueryBudgetExceeded(RuntimeError):
    def __init__(self, reason):
        self.reason = reason
        super().__init__(reason)


@dataclass
class QueryBudget:
    timeout_seconds: float = 75.0
    max_model_calls: int = 2
    max_mcp_attempts: int = 8
    started: float = field(default_factory=time.monotonic)
    attempts: list = field(default_factory=list)

    def remaining(self):
        remaining = self.timeout_seconds - (time.monotonic() - self.started)
        if remaining <= 0:
            raise QueryBudgetExceeded("task_deadline_exceeded")
        return remaining

    def consume(self, kind, *, retry=False):
        self.remaining()
        count = sum(a["kind"] == kind for a in self.attempts)
        limit = self.max_model_calls if kind == "model" else self.max_mcp_attempts
        if count >= limit:
            raise QueryBudgetExceeded(kind + "_budget_exceeded")
        record = {"kind": kind, "ordinal": count + 1, "transport_retry": retry}
        self.attempts.append(record)
        return record

    def evidence(self):
        return {"timeout_seconds": self.timeout_seconds,
                "max_model_calls": self.max_model_calls, "max_mcp_attempts": self.max_mcp_attempts,
                "model_calls": sum(a["kind"] == "model" for a in self.attempts),
                "mcp_attempts": sum(a["kind"] == "mcp" for a in self.attempts),
                "transport_retries": sum(a["transport_retry"] for a in self.attempts)}


ACTIVE_QUERY_BUDGET = ContextVar("active_query_budget", default=None)
