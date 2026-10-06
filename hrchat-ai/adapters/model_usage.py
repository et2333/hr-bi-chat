"""Per-attempt model evidence and Decimal estimates; never a provider bill."""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
from typing import Any


@dataclass
class ModelResult:
    text: str
    provider: str
    model: str
    call_id: str
    elapsed_ms: int
    finish_reason: str | None = None
    request_id: str | None = None
    usage: dict[str, Any] | None = None
    usage_source: str = "unknown"
    status: str = "completed"
    structured: dict[str, Any] | None = None
    started_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    http_status: int | None = None
    provider_error_code: str | None = None
    failure_kind: str | None = None
    exception_type: str | None = None

    def evidence(self, *, ask_id=None, invocation_id=None, prices=None):
        out = asdict(self)
        # Prompts, model text and employee rows are not billing evidence.
        out.pop("text")
        out.pop("structured")
        out.update(ask_id=ask_id, invocation_id=invocation_id)
        out["cost"] = estimate_cost(self, prices or [])
        return out


class ModelCallError(RuntimeError):
    def __init__(self, result: ModelResult):
        super().__init__("Model call failed")
        self.result = result


def load_prices(path: str | None) -> list[dict]:
    if not path:
        return []
    document = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(document, dict):
        raise ValueError("price configuration must be an object")
    prices = document["prices"]
    if not isinstance(prices, list) or any(not isinstance(p, dict) or not isinstance(p.get("rates", {}), dict) for p in prices):
        raise ValueError("prices must be a list")
    return prices


def estimate_cost(result: ModelResult, prices: list[dict]) -> dict:
    unknown = {"complete": False, "amount": None, "currency": None,
               "price_snapshot": None, "reason": "missing_price_or_usage"}
    matching = [p for p in prices if p.get("provider") == result.provider and p.get("model") == result.model]
    if len(matching) != 1 or result.usage_source != "actual" or not result.usage:
        return unknown
    p = matching[0]
    if not all(p.get(k) for k in ("version", "currency", "effective_at", "source", "verified_at")):
        return {**unknown, "reason": "incomplete_price_metadata"}
    try:
        def timestamp(value):
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed
        when = timestamp(result.started_at)
        if when < timestamp(p["effective_at"]) or (p.get("valid_until") and when >= timestamp(p["valid_until"])):
            return {**unknown, "reason": "price_not_effective"}
    except (ValueError, TypeError, AttributeError):
        return {**unknown, "reason": "invalid_price_dates"}
    usage = result.usage
    inp, out = usage.get("prompt_tokens"), usage.get("completion_tokens")
    details = usage.get("prompt_tokens_details") or {}
    cached = details.get("cached_tokens", usage.get("prompt_cache_hit_tokens"))
    # Missing cache breakdown is safe only if the tariff has no separate cache rate.
    if cached is None and "cached_input" in p.get("rates", {}):
        return {**unknown, "reason": "unknown_cache_usage"}
    cached = cached or 0
    if any(type(v) is not int or v < 0 for v in (inp, out, cached)) or cached > inp:
        return {**unknown, "reason": "invalid_usage"}
    # A provider's extra categories must be explicitly covered by this tariff.
    if (details.get("audio_tokens", 0) or
            (usage.get("completion_tokens_details") or {}).get("audio_tokens", 0)):
        return {**unknown, "reason": "unsupported_usage_category"}
    try:
        rates = {k: Decimal(str(v)) for k, v in p["rates"].items()}
        scale = Decimal(str(p["tokens_per_unit"]))
        if not scale.is_finite() or scale <= 0 or any(not v.is_finite() or v < 0 for v in rates.values()):
            raise ValueError("invalid tariff")
        cache_rate = rates.get("cached_input", rates["input"])
        amount = ((inp - cached) * rates["input"] + cached * cache_rate + out * rates["output"]) / scale
    except (KeyError, ValueError, InvalidOperation, TypeError):
        return {**unknown, "reason": "invalid_tariff"}
    return {"complete": True, "amount": str(amount), "currency": p["currency"],
            "price_snapshot": json.loads(json.dumps(p)), "reason": None}


def summarize_calls(records: list[dict], successes: int | None = None) -> dict:
    calls = {}
    for record in records:
        calls.setdefault(record["call_id"], record)
    totals: dict[str, Decimal] = {}
    unknown = 0
    tokens: dict[str, int] = {}
    for r in calls.values():
        cost = r.get("cost") or {}
        if not cost.get("complete"):
            unknown += 1
        elif cost.get("currency") and cost.get("amount") is not None:
            currency = cost["currency"]
            totals[currency] = totals.get(currency, Decimal(0)) + Decimal(cost["amount"])
        if r.get("usage_source") == "actual":
            for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
                value = (r.get("usage") or {}).get(key)
                if type(value) is int and value >= 0:
                    tokens[key] = tokens.get(key, 0) + value
    return {"call_count": len(calls), "unknown_cost_count": unknown,
            "cost_coverage": (len(calls) - unknown) / len(calls) if calls else None,
            "known_cost_by_currency": {k: str(v) for k, v in totals.items()},
            "actual_usage_known_sum": tokens,
            "cost_per_success": {k: str(v / successes) for k, v in totals.items()}
            if calls and not unknown and successes else None}
