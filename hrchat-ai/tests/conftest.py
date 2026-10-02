"""pytest 全局夹具：默认走 demo 取数后端，避免单测依赖 Java MCP。"""
from __future__ import annotations

import os

# 必须在导入 agent_gateway.app 之前生效（create_app 模块级装配）
os.environ.setdefault("QUERY_BACKEND", "demo")
