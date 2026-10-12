"""Online cross-service insight scoring: real Java POST → persisted → GET.

Starts the real Java bootstrap jar (local/H2 profile, remote AI runtime) and the Python
fixture gateway, then drives the public report-insight HTTP flow end to end:

    session → ask → create ASK report → ensure data version → chart-analysis
    → POST insight-tasks → GET insight (persisted)

The model is a scripted fixture (no provider API calls, no cost). What this run adds over
the offline scorer is the *cross-service output*: the payload the page actually reads back
after Java signs the credential, forwards POST /v1/insight, validates, persists the result
and serves GET. It is scored by ``score_transport`` (visible body re-derived from fact
slots + reference integrity) and a fabricated visible body must fail.

This is not real-model-quality evidence: the draft is scripted. It is scored separately
from the Python-internal scorer.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
import json
import os
import socket
import subprocess
import sys
import time

from evals.api import JavaApi
from evals.dataset import sha
from evals.insight_scenarios import normalize_java_insight, score_transport
from evals.launch_remote import wait_for
from evals.reference import ROOT

TERMINAL = {"complete", "degraded", "failed", "legacy", "insufficient"}


def _json(raw):
    parsed = json.loads(raw)
    return parsed.get("data", parsed)


def _wait_version(api, report_id, identity, timeout=30.0):
    deadline = time.time() + timeout
    view = None
    while time.time() < deadline:
        status, raw = api.request(f"/api/v1/reports/{report_id}/data-versions/ensure", identity,
                                  {}, method="POST", headers={"X-Idempotency-Key": "insight-cross-ensure"})
        assert status < 300, (status, raw)
        view = _json(raw)
        if view.get("status") == "READY":
            return view
        time.sleep(0.3)
    raise AssertionError(f"data version not READY: {view}")


def _poll_insight(api, path, identity, timeout=60.0):
    deadline = time.time() + timeout
    view = None
    while time.time() < deadline:
        status, raw = api.request(path, identity)
        assert status == 200, (status, raw)
        view = _json(raw)
        if view.get("status") in TERMINAL:
            return view
        time.sleep(0.4)
    raise AssertionError(f"insight did not reach a terminal status: {view}")


def run(java_url):
    api = JavaApi(java_url, 30)
    identity = "hr04"
    results = {"run_kind": "insight_cross_service_scripted_fixture", "real_model_calls": 0, "checks": {}}

    session = api.session(identity)
    answer = api.ask(session, identity, {"question": "上月研发中心人员流失人数"})
    assert answer["status"] == "COMPLETED", answer
    ask_id = answer["answer"]["askId"]

    status, raw = api.request("/api/v1/reports", identity,
                              {"sourceType": "ASK", "sourceId": ask_id, "name": "洞察跨服务评分"})
    assert status == 201, (status, raw)
    report_id = _json(raw)

    status, raw = api.request(f"/api/v1/reports/{report_id}", identity)
    assert status == 200, (status, raw)
    components = _json(raw).get("components") or []
    chart = next((c for c in components
                  if str(c.get("compType", "")).upper() == "CHART"
                  and str(c.get("chartType", "")).upper() != "PIE"), None)
    assert chart is not None, components
    comp_id = chart["componentId"]

    version = _wait_version(api, report_id, identity)
    results["data_version"] = version

    analysis_path = f"/api/v1/reports/{report_id}/components/{comp_id}/chart-analysis"
    status, raw = api.request(analysis_path, identity)
    assert status == 200, (status, raw)
    analysis = _json(raw)
    source = analysis.get("source")
    assert source and analysis.get("canEnsure"), analysis

    task_path = f"/api/v1/reports/{report_id}/components/{comp_id}/insight-tasks"
    status, raw = api.request(task_path, identity, {"source": source})
    assert status < 300, (status, raw)
    results["task"] = _json(raw)

    insight_path = f"/api/v1/reports/{report_id}/components/{comp_id}/insight"
    java_insight = _poll_insight(api, insight_path, identity)
    results["java_insight"] = java_insight
    assert java_insight.get("status") == "complete", java_insight

    normalized = normalize_java_insight(java_insight)
    score = score_transport(normalized)
    results["score"] = score
    assert score["ok"], score
    assert score["claim_count"] > 0 and score["fact_count"] > 0, score
    results["checks"]["cross_service_visible_body_reconstructed"] = True
    assert normalized["data_version"] == str(version.get("dataVersionId")), (normalized["data_version"], version)
    results["checks"]["cross_service_source_identity"] = True
    results["checks"]["cross_service_references_resolve"] = True

    # Negative control: a fabricated visible body must fail even with the facts intact.
    tampered = deepcopy(java_insight)
    tampered["summary"] = "999999 人离职，因为管理失败"
    tampered_score = score_transport(normalize_java_insight(tampered))
    results["tampered_score"] = tampered_score
    assert tampered_score["ok"] is False, tampered_score
    assert any(e["issue"] == "visible_summary_not_from_narrative"
               for e in tampered_score["visible_errors"]), tampered_score
    results["checks"]["fabricated_body_rejected"] = True

    results["passed"] = True
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-port", type=int, default=18098)
    parser.add_argument("--python-port", type=int, default=18099)
    args = parser.parse_args()
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    if not jar.is_file():
        parser.error("Build Java first")
    for port in (args.java_port, args.python_port):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    run_dir = ROOT / "docs/evaluation-runs" / ("insight-cross-service-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    run_dir.mkdir(parents=True)
    java_url, python_url = f"http://127.0.0.1:{args.java_port}", f"http://127.0.0.1:{args.python_port}"
    env = dict(os.environ, LLM_PROFILE="mock", QUERY_BACKEND="java_mcp", JAVA_MCP_BASE_URL=java_url + "/mcp",
               HRCHAT_MCP_SERVICE_TOKEN="isolated-insight-cross-token", HRCHAT_DEMO_NOW="2026-09-28",
               PYTHONIOENCODING="utf-8")
    python_cmd = [sys.executable, "-m", "uvicorn", "evals.s6_fixture_gateway:app", "--host", "127.0.0.1",
                  "--port", str(args.python_port)]
    java_cmd = ["java", "-jar", str(jar), "--spring.profiles.active=local", "--server.address=127.0.0.1",
                f"--server.port={args.java_port}", "--hrchat.ai.runtime=remote",
                f"--hrchat.ai.remote-base-url={python_url}",
                "--spring.datasource.url=jdbc:h2:mem:insight_cross;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
                "--spring.datasource.password=", "--spring.flyway.locations=classpath:db/migration/h2",
                "--hrchat.demo.now=2026-09-28", "--logging.level.root=WARN", "--logging.level.com.hrchat=INFO"]
    processes, logs = [], []
    results = {"run_kind": "insight_cross_service_scripted_fixture", "real_model_calls": 0, "checks": {},
               "jar_sha256": sha(jar)}
    try:
        for name, command, cwd, health in [("python", python_cmd, ROOT / "hrchat-ai", python_url + "/health"),
                                           ("java", java_cmd, ROOT / "hrchat-server", java_url + "/actuator/health")]:
            log = (run_dir / (name + ".log")).open("wb")
            logs.append(log)
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            processes.append(process)
            wait_for(health, process)
        results = run(java_url)
        results["jar_sha256"] = sha(jar)
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
        print(json.dumps({"report": str(run_dir / "report.json"), "checks": results.get("checks", {}),
                          "passed": results.get("passed", False)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
