"""单元测试默认使用 mock/demo，并与开发者本机的模型配置隔离。"""
from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory

import pytest

from adapters import local_env


def pytest_configure(config):
    # 必须在收集测试、导入 agent_gateway.app 之前生效。
    # 网关会自动加载 .env.local；测试不可因本机有 Key 就改用真实模型。
    config._model_env_patch = patch = pytest.MonkeyPatch()
    config._model_env_dir = TemporaryDirectory(prefix="hrchat-pytest-")
    patch.setattr(local_env, "LOCAL_ENV_FILE", Path(config._model_env_dir.name) / ".env.local")
    patch.setenv("LLM_PROFILE", "mock")
    patch.setenv("QUERY_BACKEND", "demo")


def pytest_unconfigure(config):
    config._model_env_patch.undo()
    config._model_env_dir.cleanup()
