"""Exercise only CLI dispatch; never load a key or contact a provider."""
import sys

import pytest

from evals import s6_real


@pytest.mark.parametrize("exit_code", [0, 1])
def test_cli_preserves_acceptance_failure_exit_code(monkeypatch, exit_code):
    async def fake_run(model, cases):
        assert model == "qwen-plus" and cases == ["S6-01", "S6-03"]
        return exit_code

    monkeypatch.setattr(s6_real, "run", fake_run)
    monkeypatch.setattr(sys, "argv", ["s6_real", "--confirm-real-calls"])
    assert s6_real.main() == exit_code


def test_cli_without_confirmation_cannot_start_a_model_run(monkeypatch):
    async def forbidden(*args):
        pytest.fail("A model run must not start without confirmation")

    monkeypatch.setattr(s6_real, "run", forbidden)
    monkeypatch.setattr(sys, "argv", ["s6_real"])
    with pytest.raises(SystemExit) as error:
        s6_real.main()
    assert error.value.code == 2
