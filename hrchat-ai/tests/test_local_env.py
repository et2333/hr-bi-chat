"""Model selection must survive starting the gateway after a CLI override."""
import os

from adapters import local_env


def configure_file(tmp_path, monkeypatch):
    path = tmp_path / ".env.local"
    path.write_text("LLM_PROFILE=openai\nOPENAI_MODEL=qwen-turbo\n"
                    "OPENAI_API_KEY=test-only\nOPENAI_BASE_URL=http://localhost/v1\n", encoding="utf-8")
    monkeypatch.setattr(local_env, "LOCAL_ENV_FILE", path)
    for key in local_env.MODEL_ENV_KEYS:
        monkeypatch.delenv(key, raising=False)


def test_gateway_preserves_explicit_launch_model_and_profile(tmp_path, monkeypatch):
    configure_file(tmp_path, monkeypatch)
    monkeypatch.setenv("OPENAI_MODEL", "qwen-plus")
    monkeypatch.setenv("LLM_PROFILE", "mock")
    assert local_env.load_local_model_env(override=False)
    assert os.environ["OPENAI_MODEL"] == "qwen-plus"
    assert os.environ["LLM_PROFILE"] == "mock"
    assert os.environ["OPENAI_BASE_URL"] == "http://localhost/v1"


def test_cli_still_prefers_file_over_stale_shell_config(tmp_path, monkeypatch):
    configure_file(tmp_path, monkeypatch)
    monkeypatch.setenv("OPENAI_MODEL", "stale-model")
    assert local_env.load_local_model_env()
    assert os.environ["OPENAI_MODEL"] == "qwen-turbo"


def test_gateway_respects_explicit_empty_key(tmp_path, monkeypatch):
    configure_file(tmp_path, monkeypatch)
    monkeypatch.setenv("OPENAI_API_KEY", "")
    local_env.load_local_model_env(override=False)
    assert os.environ["OPENAI_API_KEY"] == ""
