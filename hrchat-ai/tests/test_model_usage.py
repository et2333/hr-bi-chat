import copy
import json
from decimal import Decimal
import pytest
import httpx

from adapters.model_usage import ModelResult, ModelCallError, estimate_cost, summarize_calls, load_prices
from adapters.openai_client import OpenAIClientImpl

PRICE = {"provider": "test", "model": "m", "version": "fixture-v1", "currency": "TEST",
         "effective_at": "2026-10-06", "source": "unit-test fixture; not vendor prices", "verified_at": "2026-10-06",
         "tokens_per_unit": 1000, "rates": {"input": "2", "cached_input": "0.5", "output": "4"}}


@pytest.mark.parametrize("document", [[], {"prices": [None]}, {"prices": [{"rates": []}]}])
def test_malformed_price_configuration_is_rejected_without_claiming_zero_cost(tmp_path, document):
    path = tmp_path / "prices.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    with pytest.raises(ValueError):
        load_prices(str(path))


def result(call_id="a"):
    return ModelResult("", "test", "m", call_id, 1, usage_source="actual", usage={
        "prompt_tokens": 1000, "completion_tokens": 100, "total_tokens": 1100,
        "prompt_tokens_details": {"cached_tokens": 200}}, started_at="2026-10-06T06:00:00+00:00")


def test_cache_is_subset_and_price_snapshot_is_immutable():
    prices = [copy.deepcopy(PRICE)]
    cost = estimate_cost(result(), prices)
    assert Decimal(cost["amount"]) == Decimal("2.1")
    prices[0]["rates"]["input"] = "3"
    assert cost["price_snapshot"]["rates"]["input"] == "2"
    assert estimate_cost(result(), prices)["amount"] != cost["amount"]


@pytest.mark.parametrize("change", ["missing_price", "missing_usage", "unknown_cache", "negative", "more_cached"])
def test_unknown_cost_is_not_zero(change):
    r, prices = result(), [PRICE]
    if change == "missing_price": prices = []
    if change == "missing_usage": r.usage = None
    if change == "unknown_cache": r.usage.pop("prompt_tokens_details")
    if change == "negative": r.usage["prompt_tokens"] = -1
    if change == "more_cached": r.usage["prompt_tokens_details"]["cached_tokens"] = 2000
    cost = estimate_cost(r, prices)
    assert cost["amount"] is None and not cost["complete"]


def test_retry_accumulation_deduplication_and_incomplete_unit_cost():
    a, b = result("a").evidence(prices=[PRICE]), result("b").evidence(prices=[PRICE])
    complete = summarize_calls([a, b, a], successes=1)
    assert complete["call_count"] == 2
    assert Decimal(complete["cost_per_success"]["TEST"]) == Decimal("4.2")
    unknown = result("c").evidence()
    assert summarize_calls([a, unknown], successes=1)["cost_per_success"] is None
    assert summarize_calls([a], successes=0)["cost_per_success"] is None


def test_future_or_expired_prices_do_not_create_a_false_total():
    future = {**PRICE, "effective_at": "2026-10-07T00:00:00+00:00"}
    expired = {**PRICE, "valid_until": "2026-10-06T00:00:00+00:00"}
    assert estimate_cost(result(), [future])["reason"] == "price_not_effective"
    assert estimate_cost(result(), [expired])["reason"] == "price_not_effective"


async def test_compatible_client_collects_api_usage_without_additional_call():
    adapter = OpenAIClientImpl(base_url="https://vendor.test/v1", api_key="secret", model="requested")
    await adapter.aclose()
    calls = []
    def reply(request):
        calls.append(request)
        return httpx.Response(200, json={"id": "req_1", "model": "actual", "usage": {"prompt_tokens": 15, "completion_tokens": 5},
            "choices": [{"finish_reason": "stop", "message": {"content": "{}"}}]})
    adapter._client = httpx.AsyncClient(base_url=adapter.base_url, transport=httpx.MockTransport(reply))
    r = await adapter.complete("plan")
    assert r.model == "actual" and r.provider == "vendor.test" and r.usage_source == "actual"
    assert r.usage["prompt_tokens"] == 15 and len(calls) == 1
    assert "thinking" not in json.loads(calls[0].content)
    assert "secret" not in str(r.evidence())
    await adapter.aclose()


async def test_deepseek_plan_request_disables_default_thinking_and_bounds_output():
    adapter = OpenAIClientImpl(base_url="https://api.deepseek.com", api_key="secret", model="deepseek-flash")
    await adapter.aclose()
    def reply(request):
        payload = json.loads(request.content)
        assert payload["thinking"] == {"type": "disabled"}
        assert payload["max_tokens"] == 2048
        assert "response_format" not in payload  # generate() remains usable for natural-language tasks
        return httpx.Response(200, json={"model": "deepseek-flash", "usage": {"prompt_tokens": 10, "completion_tokens": 2},
            "choices": [{"finish_reason": "stop", "message": {"content": "{}"}}]})
    adapter._client = httpx.AsyncClient(base_url=adapter.base_url, transport=httpx.MockTransport(reply))
    assert (await adapter.complete("JSON plan")).provider == "api.deepseek.com"
    await adapter.aclose()


async def test_timeout_has_one_unknown_attempt_and_does_not_retry():
    adapter = OpenAIClientImpl(api_key="secret")
    await adapter.aclose()
    def timeout(request): raise httpx.ReadTimeout("no response", request=request)
    adapter._client = httpx.AsyncClient(base_url=adapter.base_url, transport=httpx.MockTransport(timeout))
    with pytest.raises(ModelCallError) as error:
        await adapter.complete("plan")
    assert error.value.result.usage is None and error.value.result.status == "failed"
    assert error.value.result.exception_type == "ReadTimeout"
    await adapter.aclose()


@pytest.mark.parametrize("model", ["qwen-turbo", "qwen-plus"])
async def test_json_mode_and_system_role_only_apply_to_planning(model):
    adapter = OpenAIClientImpl(base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", api_key="secret", model=model)
    await adapter.aclose()
    calls = []
    def reply(request):
        calls.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": "{}"}}]})
    adapter._client = httpx.AsyncClient(base_url=adapter.base_url, transport=httpx.MockTransport(reply))
    await adapter.complete_query_draft("JSON rules", "untrusted question")
    await adapter.generate("natural language task")
    assert calls[0]["response_format"] == {"type": "json_object"}
    assert [m["role"] for m in calls[0]["messages"]] == ["system", "user"]
    assert calls[0]["enable_thinking"] is False
    assert "response_format" not in calls[1]
    await adapter.aclose()


async def test_http_failure_records_only_safe_diagnostics():
    adapter = OpenAIClientImpl(base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                               api_key="secret", model="qwen-turbo")
    await adapter.aclose()
    def reply(request):
        assert str(request.url) == "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        assert json.loads(request.content)["enable_thinking"] is False
        return httpx.Response(401, headers={"x-request-id": "request-123"},
            json={"error": {"code": "InvalidApiKey", "message": "secret should not be stored"}})
    adapter._client = httpx.AsyncClient(base_url=adapter.base_url, transport=httpx.MockTransport(reply))
    with pytest.raises(ModelCallError) as error:
        await adapter.complete("plan")
    evidence = error.value.result.evidence()
    assert evidence["http_status"] == 401
    assert evidence["provider_error_code"] == "InvalidApiKey"
    assert evidence["failure_kind"] == "http_error"
    assert evidence["request_id"] == "request-123"
    assert "secret" not in str(evidence)
    await adapter.aclose()
