"""Launch a fresh, loopback-only H2 server, run baseline, stop our process."""
import argparse
from datetime import datetime, timezone
import json
import socket
import subprocess
import sys
import time
import urllib.request

from evals.dataset import sha
from evals.reference import ROOT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--split", choices=["dev", "frozen", "all"], default="dev")
    parser.add_argument("--port", type=int, default=18086)
    args = parser.parse_args()
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    if not jar.is_file():
        parser.error("Build first: mvn -pl hrchat-bootstrap -am package -DskipTests")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))  # Never accidentally evaluate an existing process.
    run_dir = ROOT / "docs/evaluation-runs" / ("launch-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    run_dir.mkdir(parents=True)
    command = ["java", "-jar", str(jar), "--server.address=127.0.0.1", f"--server.port={args.port}",
               "--spring.profiles.active=local", "--hrchat.ai.runtime=local", "--hrchat.demo.now=2026-09-28",
               "--logging.level.root=WARN", "--logging.level.com.hrchat=INFO", "--debug=false"]
    base_url = f"http://127.0.0.1:{args.port}"
    evidence = json.loads((ROOT / "hrchat-ai/evals/fixtures/local-server.json").read_text(encoding="utf-8"))
    evidence.update(launch_command=command, jar_sha256=sha(jar),
                    started_at=datetime.now(timezone.utc).isoformat(), startup_log="server.log",
                    note="Fresh subprocess launched by evals.launch_local; H2 in-memory seed, no external config changes.")
    with (run_dir / "server.log").open("wb") as log:
        process = subprocess.Popen(command, cwd=ROOT / "hrchat-server", stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            while True:
                if process.poll() is not None:
                    raise RuntimeError("Server exited; see " + str(run_dir / "server.log"))
                try:
                    with urllib.request.urlopen(base_url + "/actuator/health", timeout=2) as response:
                        if json.load(response).get("status") == "UP":
                            break
                except (OSError, ValueError):
                    pass
                if time.monotonic() > deadline:
                    raise TimeoutError("Server startup timeout")
                time.sleep(.25)
            evidence["pid"] = process.pid
            path = run_dir / "server-evidence.json"
            path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            result = subprocess.run([sys.executable, "-m", "evals.run", "--base-url", base_url,
                                     "--split", args.split, "--server-evidence", str(path)], cwd=ROOT / "hrchat-ai")
            return result.returncode
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
