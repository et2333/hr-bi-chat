"""Explicit eval entry point, never imported by the normal gateway."""
from importlib import import_module

from evals.memory_ablation import ablated_flow

gateway = import_module("agent_gateway.app")
gateway.run_ask_flow = ablated_flow(gateway.run_ask_flow)
app = gateway.app
