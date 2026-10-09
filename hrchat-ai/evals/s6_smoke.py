"""Fresh H2 + Python + public Java SSE acceptance, without provider API calls."""
import argparse
from datetime import datetime, timezone
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request

from evals.api import JavaApi
from evals.dataset import sha
from evals.launch_remote import wait_for
from evals.reference import ROOT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-port", type=int, default=18096)
    parser.add_argument("--python-port", type=int, default=18097)
    parser.add_argument("--browser", action="store_true", help="Also run the isolated Playwright user flow")
    args = parser.parse_args()
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    if not jar.is_file():
        parser.error("Build Java first")
    for port in (args.java_port, args.python_port):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    run_dir = ROOT / "docs/evaluation-runs" / ("s6-smoke-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    run_dir.mkdir(parents=True)
    java_url, python_url = f"http://127.0.0.1:{args.java_port}", f"http://127.0.0.1:{args.python_port}"
    env = dict(os.environ, LLM_PROFILE="mock", QUERY_BACKEND="java_mcp", JAVA_MCP_BASE_URL=java_url + "/mcp",
               HRCHAT_MCP_SERVICE_TOKEN="isolated-s6-smoke-token", HRCHAT_DEMO_NOW="2026-09-28", PYTHONIOENCODING="utf-8")
    python_cmd = [sys.executable, "-m", "uvicorn", "evals.s6_fixture_gateway:app", "--host", "127.0.0.1", "--port", str(args.python_port)]
    java_cmd = ["java", "-jar", str(jar), "--spring.profiles.active=local", "--server.address=127.0.0.1",
                f"--server.port={args.java_port}", "--hrchat.ai.runtime=remote", f"--hrchat.ai.remote-base-url={python_url}",
                "--spring.datasource.url=jdbc:h2:mem:s6_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.flyway.locations=classpath:db/migration/h2", "--hrchat.demo.now=2026-09-28",
                "--logging.level.root=WARN", "--logging.level.com.hrchat=INFO"]
    results = {"run_kind": "cross_service_scripted_fixture", "real_model_calls": 0, "jar_sha256": sha(jar), "checks": {}}
    processes, logs = [], []
    try:
        for name, command, cwd, health in [("python", python_cmd, ROOT / "hrchat-ai", python_url + "/health"),
                                           ("java", java_cmd, ROOT / "hrchat-server", java_url + "/actuator/health")]:
            log = (run_dir / (name + ".log")).open("wb")
            logs.append(log)
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            processes.append(process)
            wait_for(health, process)
        api = JavaApi(java_url, 30)
        session = api.session("hr04")
        answer = api.ask(session, "hr04", {"question": "上月研发中心人员流失人数"})
        results["source_answer"] = answer
        assert answer["status"] == "COMPLETED", answer
        ask_id = answer["answer"]["askId"]
        prefix = f"/api/v1/chat/asks/{ask_id}/attribution"

        def call(path, body=None, identity="hr04", headers=None):
            status, raw = api.request(path, identity, body, headers=headers)
            data = json.loads(raw)
            assert status < 300, (status, data)
            return data["data"]

        context = call(prefix + "/context")
        assert context["suggestedBaselinePeriod"] == {"start": "2026-07-01", "end": "2026-08-01"}, context
        results["checks"]["confirmed_periods"] = True
        params = {"baselinePeriod": context["suggestedBaselinePeriod"]}  # default is dual
        started = call(prefix, params, headers={"X-Idempotency-Key": "smoke-dual"})
        task_id = started["taskId"]
        task_path = f"/api/v1/chat/attribution/tasks/{task_id}"
        assert call(prefix, params, headers={"X-Idempotency-Key": "smoke-dual"})["taskId"] == task_id
        results["checks"]["idempotent_start"] = True
        seen = []
        request = urllib.request.Request(java_url + task_path + "/events", headers={"X-User-No": "hr04"})
        first_at, final_at = None, None
        with urllib.request.urlopen(request, timeout=30) as response:
            for raw in response:
                if raw.startswith(b"data:"):
                    frame = json.loads(raw[5:])
                    seen.append(frame)
                    if frame["event"] == "TOOL_CALL_START" and first_at is None:
                        first_at = time.perf_counter()
                    if frame["event"] in {"FINAL", "ERROR"}:
                        final_at = time.perf_counter()
        final = call(task_path)
        results["task"] = final
        assert final["status"] == "COMPLETED", final
        output = final["result"]
        assert output["summary"]["current_total"] == output["summary"]["baseline_total"] == 1
        assert {r["org_id"]: r["delta"] for r in output["contributions"]} == {2: 0, 3: -1, 4: 1}
        assert output["usage"]["model_calls"] == 4 and output["usage"]["mcp_attempts"] == 2
        assert first_at is not None and final_at - first_at > .2
        results["checks"]["live_progress_before_completion"] = True
        results["checks"]["actual_h2_contributions_and_supplement"] = True
        status, raw = api.request(task_path + "/events", "hr04", headers={"Last-Event-ID": str(seen[-2]["seq"])})
        assert status == 200 and '"COMPLETED"' in raw
        assert call(task_path)["result"]["usage"]["model_calls"] == 4
        results["checks"]["replay_without_new_calls"] = True
        assert api.request(task_path, "hr02")[0] == 403
        assert api.request(task_path, "hr04", headers={"X-Tenant-No": "t02"})[0] == 403
        results["checks"]["ownership_and_tenant_denied"] = True
        running = call(prefix, params, headers={"X-Idempotency-Key": "smoke-cancel"})
        cancel_path = f"/api/v1/chat/attribution/tasks/{running['taskId']}"
        assert call(cancel_path + "/cancel", {})["status"] == "CANCELLED"
        time.sleep(.4)
        assert call(cancel_path)["status"] == "CANCELLED"
        results["checks"]["cancel_does_not_become_success"] = True
        if args.browser:
            browser_env = dict(env, HRCHAT_API_TARGET=java_url, S6_FIXTURE_E2E="1")
            # Use the known local package entrypoint, avoiding shell-built commands.
            cli = ROOT / "hrchat-web/node_modules/@playwright/test/cli.js"
            browser = subprocess.run(["node", str(cli), "test", "e2e/analysis.spec.ts"],
                                     cwd=ROOT / "hrchat-web", env=browser_env, timeout=180)
            assert browser.returncode == 0, "Browser analysis acceptance failed"
            results["checks"]["browser_analysis_flow"] = True
        results["passed"] = True
    finally:
        (run_dir / "report.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()
        print(json.dumps({"report": str(run_dir / "report.json"), "checks": results["checks"], "passed": results.get("passed", False)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
