"""Evaluation-only removal of history; keep the production prompt/compiler and identity."""
from copy import deepcopy


def without_history(snapshot):
    if snapshot is None:
        raise ValueError("Memory ablation requires a trusted Java snapshot")
    result = deepcopy(snapshot)
    for field in ("confirmed", "pending", "selection"):
        result.pop(field, None)
    return result


def ablated_flow(original):
    async def run(**kwargs):
        kwargs["query_context"] = without_history(kwargs.get("query_context"))
        result = await original(**kwargs)
        result["evidence"]["experiment"] = {"version": "memory-ablation-v1", "memory_enabled": False,
                                            "removed": ["confirmed", "pending", "selection"]}
        return result
    return run
