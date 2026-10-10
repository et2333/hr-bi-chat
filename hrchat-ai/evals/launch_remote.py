"""Isolated Java -> Python -> Java MCP launcher. Fixture runs are integration tests only."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

from adapters.local_env import load_local_model_env
from evals.dataset import DATASET, DATASETS, MODE_DATASET, load_dataset, select_cases, sha
from evals.reference import ROOT
from evals.s5_compare import source_fingerprint


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
    parser.add_argument("--dataset", choices=sorted(DATASETS), default=DATASET.name)
    parser.add_argument("--fixture-cases", action="store_true",
                        help="J1 scripted planner over the selected mode cases; zero external model calls")
    parser.add_argument("--stage", choices=["s2", "s3", "s4"], default="s3")
    parser.add_argument("--runtime", choices=["remote", "local"], default="remote")
    parser.add_argument("--memory", choices=["on", "off"], default="on", help="Evaluation-only history ablation")
    parser.add_argument("--rag", choices=["off", "lexical", "hybrid"], default=None,
                        help="Evaluation-only RAG mode; sets HRCHAT_RAG_MODE for the Python gateway")
    parser.add_argument("--prompt-distractors", action="store_true",
                        help="Evaluation-only: put retrieval distractors into the planning prompt metrics list")
    parser.add_argument("--pressure-suite", action="store_true",
                        help="Run rag_pressure hard metric-selection cases instead of dataset runner")
    parser.add_argument("--report-pointer", help="Write the exact report path for an experiment controller")
    parser.add_argument("--repair", choices=["on", "off"], help="Defaults on for S4, off for S2/S3")
    parser.add_argument("--repair-smoke", action="store_true", help="S4 injected fixture checks through Java API")
    parser.add_argument("--repair-real-smoke", action="store_true", help="S4: 3 injected first drafts with real API repair calls")
    parser.add_argument("--java-port", type=int, default=18088)
    parser.add_argument("--python-port", type=int, default=18089)
    parser.add_argument("--org-catalog-scope", default="authorized", choices=["authorized"])
    parser.add_argument("--fixture-smoke", action="store_true")
    parser.add_argument("--memory-smoke", action="store_true", help="Run S3 continuation and isolation sequences")
    parser.add_argument("--case-id", action="append", help="Repeat to run only these complete cases")
    parser.add_argument("--model", help="Explicit model override for a controlled comparison; does not edit .env.local")
    parser.add_argument("--planner-diagnostics", action="store_true",
                        help="Save mock-data prompts/replies locally; requires selected dev cases")
    parser.add_argument("--planner-variant", choices=["baseline", "contract-notes-v1"], default="baseline",
                        help="Prompt experiment; only used with --planner-diagnostics")
    args = parser.parse_args()
    if args.fixture_cases and (args.dataset != MODE_DATASET.name or args.runtime != "remote"
            or args.fixture_smoke or args.memory_smoke or args.repair_smoke or args.repair_real_smoke
            or args.planner_diagnostics or args.model or args.memory != "on" or args.repair == "on"):
        parser.error("Fixture cases require remote hr-query-modes-v1 with memory on; no model/repair/smoke options")
    if args.dataset == MODE_DATASET.name and (args.runtime != "remote" or args.fixture_smoke
            or args.memory_smoke or args.repair_smoke or args.repair_real_smoke):
        parser.error("Mode dataset requires remote execution evidence; use --fixture-cases or real model")
    if args.runtime == "local" and (args.fixture_smoke or args.memory_smoke or args.repair_smoke
                                   or args.repair_real_smoke or args.planner_diagnostics or args.model
                                   or args.memory == "off" or args.repair == "on"):
        parser.error("Local rule baseline cannot use model, ablation, repair or smoke options")
    if args.memory == "off" and (args.fixture_smoke or args.memory_smoke or args.repair_smoke
                                or args.repair_real_smoke or args.planner_diagnostics):
        parser.error("Memory ablation uses natural cases only")
    if args.rag is not None and (args.runtime != "remote" or args.fixture_smoke or args.fixture_cases
                                 or args.repair_smoke or args.repair_real_smoke):
        parser.error("RAG mode ablation requires remote real-model or natural launch; not fixture/smoke/local")
    if args.pressure_suite and (args.runtime != "remote" or not args.prompt_distractors or args.rag is None
                                or args.fixture_smoke or args.fixture_cases or args.case_id):
        parser.error("Pressure suite requires remote --prompt-distractors --rag and no case-id/fixture options")
    real_model = args.runtime == "remote" and not args.fixture_smoke and not args.fixture_cases
    if args.repair_real_smoke and (args.fixture_smoke or args.repair_smoke or args.memory_smoke or args.case_id
                                 or args.planner_diagnostics or args.stage != "s4" or args.repair == "off"):
        parser.error("Real repair smoke requires --stage s4 with repair enabled, without other smoke/selection options")
    if args.repair_smoke and (not args.fixture_smoke or args.memory_smoke or args.stage != "s4" or args.repair == "off"):
        parser.error("Repair smoke requires --fixture-smoke --stage s4 with repair enabled")
    if args.fixture_smoke and (args.case_id or args.planner_diagnostics):
        parser.error("Fixture smoke does not use case selection or real-model diagnostics")
    if args.planner_diagnostics and (args.split != "dev" or not args.case_id):
        parser.error("Planner diagnostics requires --split dev and explicit --case-id values")
    if args.planner_variant != "baseline" and not args.planner_diagnostics:
        parser.error("Prompt variants require --planner-diagnostics")
    selected = []
    if not args.pressure_suite:
        try:
            selected = select_cases(load_dataset(DATASETS[args.dataset])[1], args.split, args.case_id)
            if not selected:
                raise ValueError("Selection is empty; no services will be started")
        except ValueError as exc:
            parser.error(str(exc))
        if args.planner_diagnostics and sum(len(c["turns"]) for c in selected) > 8:
            parser.error("Planner diagnostics is limited to 8 selected turns per run")
    if real_model:
        load_local_model_env()
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    if not jar.is_file():
        parser.error("Build the Java JAR first")
    if real_model and not os.getenv("OPENAI_API_KEY"):
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
    env["HRCHAT_QUERY_REPAIR_ENABLED"] = "1" if args.runtime == "remote" and (args.repair == "on" or args.repair is None and args.stage == "s4") else "0"
    if args.fixture_cases:
        env["HRCHAT_QUERY_REPAIR_ENABLED"] = "0"
    if args.model:
        env["OPENAI_MODEL"] = args.model
    if args.rag is not None:
        env["HRCHAT_RAG_MODE"] = args.rag
        if args.rag == "hybrid":
            from adapters.query_retrieval import MODEL_DIR
            if not (MODEL_DIR / "config.json").is_file():
                parser.error("hybrid RAG requires local embedding at " + str(MODEL_DIR)
                             + "; run python -m adapters.prepare_embedding first")
    if args.prompt_distractors:
        env["HRCHAT_PROMPT_DISTRACTORS"] = "1"
    module = "evals.fixture_gateway:app" if args.fixture_smoke else "agent_gateway.app:app"
    if args.fixture_cases:
        module = "evals.mode_fixture_gateway:app"
    if args.memory == "off":
        module = "evals.memory_ablation_gateway:app"
    if args.fixture_smoke and args.memory_smoke:
        module = "evals.memory_fixture_gateway:app"
    if args.repair_smoke:
        module = "evals.repair_fixture_gateway:app"
    if args.repair_real_smoke:
        module = "evals.repair_real_gateway:app"
    if args.planner_diagnostics:
        env["HRCHAT_PLANNER_DIAGNOSTIC_DIR"] = str(run_dir / "planner-diagnostics")
        env["HRCHAT_PLANNER_VARIANT"] = args.planner_variant
        module = "evals.diagnostic_gateway:app"
    python_cmd = [sys.executable, "-m", "uvicorn", module, "--host", "127.0.0.1", "--port", str(args.python_port)]
    java_cmd = ["java", "-jar", str(jar), "--spring.profiles.active=local", "--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:hrchat_eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
                "--spring.datasource.password=", "--spring.flyway.locations=classpath:db/migration/h2",
                f"--server.port={args.java_port}", f"--hrchat.ai.runtime={args.runtime}", f"--hrchat.ai.remote-base-url={python_url}",
                "--hrchat.demo.now=2026-09-28", f"--hrchat.ai.planning.org-catalog-scope={args.org_catalog_scope}",
                "--logging.level.root=WARN", "--logging.level.com.hrchat=INFO"]
    procs = []
    logs = []
    try:
        services = [("java", java_cmd, ROOT / "hrchat-server", java_url + "/actuator/health")]
        if args.runtime == "remote":
            services.insert(0, ("python", python_cmd, ROOT / "hrchat-ai", python_url + "/health"))
        for name, cmd, cwd, health in services:
            log = (run_dir / (name + ".log")).open("wb"); logs.append(log)
            proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            procs.append(proc)
            wait_for(health, proc)
        evidence = {"runtime": args.runtime, "as_of_date": "2026-09-28", "data_version": "h2-v6",
                    "source_sha256": source_fingerprint(),
                    "timezone": "Asia/Shanghai", "query_backend": "java_local" if args.runtime == "local" else "java_mcp", "jar_sha256": sha(jar),
                    "model_kind": "rule_based" if args.runtime == "local" else "injected_first_real_repair" if args.repair_real_smoke else "fixture" if args.fixture_smoke or args.fixture_cases else "real",
                    "planner_diagnostics": args.planner_diagnostics,
                    "planner_variant": args.planner_variant,
                    "requested_model": env.get("OPENAI_MODEL") if real_model else None,
                    "memory_enabled": args.memory == "on" if args.runtime == "remote" else None,
                    "rag_mode": env.get("HRCHAT_RAG_MODE") if args.runtime == "remote" else None,
                    "prompt_distractors": args.prompt_distractors,
                    "repair_enabled": env["HRCHAT_QUERY_REPAIR_ENABLED"] == "1",
                    "org_catalog_scope": args.org_catalog_scope,
                    "java_launch_command": java_cmd, "python_launch_command": python_cmd if args.runtime == "remote" else None}
        path = run_dir / "server-evidence.json"
        path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        if args.repair_smoke or args.repair_real_smoke:
            from evals.api import JavaApi
            from evals.repair_smoke import run_repair_smoke
            summary = run_repair_smoke(JavaApi(java_url, 100), run_dir / "repair-smoke.json", real_repair=args.repair_real_smoke)
            print(json.dumps(summary, ensure_ascii=False))
            return 0 if summary["passed"] == summary["total"] else 2
        if args.memory_smoke:
            from evals.api import JavaApi
            from evals.memory_smoke import run_memory_smoke
            summary = run_memory_smoke(JavaApi(java_url, 100), run_dir / "memory-smoke.json", evidence["model_kind"])
            print(json.dumps(summary, ensure_ascii=False))
            return 0 if summary["passed"] == summary["total"] else 2
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
        if args.pressure_suite:
            from evals.api import JavaApi
            from evals.rag_pressure import score_arm
            from adapters.model_usage import summarize_calls
            scored = score_arm(JavaApi(java_url, 100), args.rag)
            run_id = run_dir.name.replace("remote-launch-", "")
            report = {
                "run_id": run_id,
                "summary": {
                    "status": "COMPLETED",
                    "passed": scored["passed"],
                    "total": scored["total"],
                    "executed_turns": scored["total"],
                    "planned_turns": scored["total"],
                },
                "rag_mode": args.rag,
                "suite_version": scored["suite_version"],
                "server": evidence,
                "results": scored["cases"],
                "cost_summary": summarize_calls([
                    call for row in scored["cases"] for call in (row.get("usage_calls") or [])
                    if isinstance(call, dict) and call.get("call_id")
                ], scored["passed"]),
            }
            report_path = run_dir / "report.json"
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if args.report_pointer:
                Path(args.report_pointer).write_text(str(report_path), encoding="utf-8")
            print(json.dumps({"report": str(report_path), "passed": scored["passed"],
                              "total": scored["total"]}, ensure_ascii=False))
            return 0  # arm completed; pass rate is in the report, not the process exit
        case_args = [arg for case_id in (args.case_id or []) for arg in ("--case-id", case_id)]
        pointer_args = ["--report-pointer", args.report_pointer] if args.report_pointer else []
        return subprocess.run([sys.executable, "-m", "evals.run", "--base-url", java_url,
            "--runtime", args.runtime, "--model-kind", evidence["model_kind"], "--split", args.split,
            "--timeout", "100", "--stage", args.stage, "--dataset", args.dataset,
            "--server-evidence", str(path), *case_args, *pointer_args], cwd=ROOT / "hrchat-ai").returncode
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
