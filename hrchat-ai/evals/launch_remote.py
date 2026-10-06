"""Isolated Java -> Python -> Java MCP launcher. Fixture runs are integration tests only."""
import argparse
from datetime import datetime, timezone
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request

from adapters.local_env import load_local_model_env
from evals.dataset import load_dataset, select_cases, sha
from evals.reference import ROOT


def wait_for(url, process):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Service exited; inspect the launch logs")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except OSError:
            pass
        time.sleep(.25)
    raise TimeoutError("Service startup timeout")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--split", choices=["dev", "frozen", "all"], default="dev")
    parser.add_argument("--java-port", type=int, default=18088)
    parser.add_argument("--python-port", type=int, default=18089)
    parser.add_argument("--org-catalog-scope", default="authorized", choices=["authorized"])
    parser.add_argument("--fixture-smoke", action="store_true")
    parser.add_argument("--case-id", action="append", help="Repeat to run only these complete cases")
    parser.add_argument("--model", help="Explicit model override for a controlled comparison; does not edit .env.local")
    parser.add_argument("--planner-diagnostics", action="store_true",
                        help="Save mock-data prompts/replies locally; requires selected dev cases")
    parser.add_argument("--planner-variant", choices=["baseline", "contract-notes-v1"], default="baseline",
                        help="Prompt experiment; only used with --planner-diagnostics")
    args = parser.parse_args()
    if args.fixture_smoke and (args.case_id or args.planner_diagnostics):
        parser.error("Fixture smoke does not use case selection or real-model diagnostics")
    if args.planner_diagnostics and (args.split != "dev" or not args.case_id):
        parser.error("Planner diagnostics requires --split dev and explicit --case-id values")
    if args.planner_variant != "baseline" and not args.planner_diagnostics:
        parser.error("Prompt variants require --planner-diagnostics")
    try:
        selected = select_cases(load_dataset()[1], args.split, args.case_id)
    except ValueError as exc:
        parser.error(str(exc))
    if args.planner_diagnostics and sum(len(c["turns"]) for c in selected) > 8:
        parser.error("Planner diagnostics is limited to 8 selected turns per run")
    if not args.fixture_smoke:
        load_local_model_env()
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    if not jar.is_file():
        parser.error("Build the Java JAR first")
    if not args.fixture_smoke and not os.getenv("OPENAI_API_KEY"):
        parser.error("Set OPENAI_API_KEY in hrchat-ai/.env.local or this PowerShell session before a real model run")
    for port in (args.java_port, args.python_port):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    run_dir = ROOT / "docs/evaluation-runs" / ("remote-launch-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    run_dir.mkdir(parents=True)
    java_url, python_url = f"http://127.0.0.1:{args.java_port}", f"http://127.0.0.1:{args.python_port}"
    env = dict(os.environ, QUERY_BACKEND="java_mcp", JAVA_MCP_BASE_URL=java_url + "/mcp",
               HRCHAT_MCP_SERVICE_TOKEN="isolated-s2-service-token", LLM_PROFILE="openai",
               HRCHAT_DEMO_NOW="2026-09-28", PYTHONIOENCODING="utf-8")
    if args.model:
        env["OPENAI_MODEL"] = args.model
    module = "evals.fixture_gateway:app" if args.fixture_smoke else "agent_gateway.app:app"
    if args.planner_diagnostics:
        env["HRCHAT_PLANNER_DIAGNOSTIC_DIR"] = str(run_dir / "planner-diagnostics")
        env["HRCHAT_PLANNER_VARIANT"] = args.planner_variant
        module = "evals.diagnostic_gateway:app"
    python_cmd = [sys.executable, "-m", "uvicorn", module, "--host", "127.0.0.1", "--port", str(args.python_port)]
    java_cmd = ["java", "-jar", str(jar), "--spring.profiles.active=local", "--server.address=127.0.0.1",
                f"--server.port={args.java_port}", "--hrchat.ai.runtime=remote", f"--hrchat.ai.remote-base-url={python_url}",
                "--hrchat.demo.now=2026-09-28", f"--hrchat.ai.planning.org-catalog-scope={args.org_catalog_scope}",
                "--logging.level.root=WARN", "--logging.level.com.hrchat=INFO"]
    procs = []
    logs = []
    try:
        for name, cmd, cwd, health in [("python", python_cmd, ROOT / "hrchat-ai", python_url + "/health"),
                                       ("java", java_cmd, ROOT / "hrchat-server", java_url + "/actuator/health")]:
            log = (run_dir / (name + ".log")).open("wb"); logs.append(log)
            proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            procs.append(proc)
            wait_for(health, proc)
        evidence = {"runtime": "remote", "as_of_date": "2026-09-28", "data_version": "h2-v6",
                    "timezone": "Asia/Shanghai", "query_backend": "java_mcp", "jar_sha256": sha(jar),
                    "model_kind": "fixture" if args.fixture_smoke else "real",
                    "planner_diagnostics": args.planner_diagnostics,
                    "planner_variant": args.planner_variant,
                    "requested_model": env.get("OPENAI_MODEL"),
                    "org_catalog_scope": args.org_catalog_scope,
                    "java_launch_command": java_cmd, "python_launch_command": python_cmd}
        path = run_dir / "server-evidence.json"
        path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        if args.fixture_smoke:
            from evals.api import JavaApi
            api = JavaApi(java_url, 60)
            session = api.session("hr01")
            actual = api.ask(session, "hr01", {"question": "上月研发中心在职人数是多少？"})
            assert actual["status"] == "COMPLETED", actual
            assert actual["answer"]["conclusion"]["value"] == 17, actual
            assert actual["evidence"]["execution"]["effective_org_ids"] == [2, 3, 4]
            assert actual["evidence"]["model_calls"][0]["provider"] == "fixture"
            refused = api.ask(session, "hr01", {"question": "预测研发中心下月离职人数"})
            assert refused["status"] == "UNSUPPORTED", refused
            assert refused["answer"] is None and refused["evidence"].get("execution") is None
            assert refused["evidence"]["reason"] == "unsupported_capability"
            assert not any(t["stage"] == "tool" for t in refused["evidence"]["trace"])
            denied = api.ask(session, "hr01", {"question": "上月在职人数", "context_override": {
                "orgId": "5", "includeChildren": True}})
            assert denied["status"] == "DENIED" and denied["answer"] is None, denied
            unavailable = []
            for org_name in ("销售部", "不存在部门"):
                attempt = api.ask(session, "hr01", {"question": f"上月{org_name}在职人数是多少？"})
                assert attempt["status"] == "CLARIFYING", attempt
                assert attempt["answer"] is None and attempt["evidence"].get("execution") is None
                assert attempt["evidence"]["reason"] == "unknown_organization"
                assert any(t["stage"] == "resolve_organization" and t["status"] == "UNAVAILABLE"
                           for t in attempt["evidence"]["trace"])
                unavailable.append(attempt)
            records = {"scope": "cross_service_fixture_smoke", "model_effectiveness": False,
                       "answer": actual, "unsupported": refused, "denied": denied, "unavailable": unavailable}
            (run_dir / "smoke.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps({"smoke_passed": True, "evidence": str(run_dir / "smoke.json")}, ensure_ascii=False))
            return 0
        case_args = [arg for case_id in (args.case_id or []) for arg in ("--case-id", case_id)]
        return subprocess.run([sys.executable, "-m", "evals.run", "--base-url", java_url,
            "--runtime", "remote", "--model-kind", "real", "--split", args.split,
            "--timeout", "100", "--server-evidence", str(path), *case_args], cwd=ROOT / "hrchat-ai").returncode
    finally:
        for proc in reversed(procs):
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill(); proc.wait(timeout=5)
        for log in logs:
            log.close()


if __name__ == "__main__":
    raise SystemExit(main())
