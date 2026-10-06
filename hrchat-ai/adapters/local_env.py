"""Load local model settings for S2 CLI commands without exposing secrets."""
from __future__ import annotations

import os
from pathlib import Path

from dotenv import dotenv_values


LOCAL_ENV_FILE = Path(__file__).resolve().parents[1] / ".env.local"
MODEL_ENV_KEYS = ("OPENAI_BASE_URL", "OPENAI_MODEL", "OPENAI_API_KEY")


def load_local_model_env() -> bool:
    """Use .env.local values, when present, ahead of inherited shell values."""
    if not LOCAL_ENV_FILE.is_file():
        return False
    values = dotenv_values(LOCAL_ENV_FILE)
    for name in MODEL_ENV_KEYS:
        if name in values:
            os.environ[name] = values[name] or ""
    return True
