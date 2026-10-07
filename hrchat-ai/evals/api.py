"""Java public HTTP entrypoint. No direct calls to the query implementation."""
import json
import time
import urllib.error
import urllib.request


class JavaApi:
    def __init__(self, base_url, timeout=30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def request(self, path, identity, body=None, *, method=None, headers=None):
        headers = {"X-User-No": identity, "Content-Type": "application/json", **(headers or {})}
        req = urllib.request.Request(self.base_url + path, headers=headers, method=method,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as response:
                return response.status, response.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8")

    def session(self, identity):
        status, raw = self.request("/api/v1/chat/sessions", identity, {"title": "S1 offline evaluation"})
        if status != 201:
            raise RuntimeError(f"Session creation HTTP {status}")
        return json.loads(raw)["data"]["id"]

    def ask(self, session, identity, turn):
        started = time.perf_counter()
        body = {"question": turn["question"], "mode": "STREAM"}
        if "context_override" in turn:
            body["contextOverride"] = turn["context_override"]
        status, raw = self.request(f"/api/v1/chat/sessions/{session}/asks", identity, body)
        return self.read_turn(status, raw, identity, started)

    def clarify(self, session, identity, ask_id, question_id, option_id):
        started = time.perf_counter()
        status, raw = self.request(f"/api/v1/chat/sessions/{session}/asks/{ask_id}/clarifications", identity,
                                  {"answers": [{"questionId": question_id, "optionIds": [option_id]}]})
        return self.read_turn(status, raw, identity, started)

    def read_turn(self, status, raw, identity, started):
        events = []
        for block in raw.replace("\r\n", "\n").split("\n\n"):
            data = "\n".join(line[5:].strip() for line in block.splitlines() if line.startswith("data:"))
            if data:
                events.append(json.loads(data))
        answers = [e["payload"] for e in events if e.get("event") == "ANSWER_DONE"]
        errors = [e["payload"] for e in events if e.get("event") == "ERROR"]
        if status >= 400:
            errors.append(json.loads(raw))
        codes = [e.get("code", e.get("error_code")) for e in errors]
        terminal = ("DENIED" if any(str(c).startswith("HRC-") for c in codes) else
                    "UNSUPPORTED" if codes and all(c == "HRA-4006" for c in codes) else
                    "FAILED" if errors else "CLARIFYING" if any(e.get("event") == "INTERRUPT" for e in events)
                    else "COMPLETED" if len(answers) == 1 else "INVALID_RESPONSE")
        actual = {"http_status": status, "status": terminal, "answer": answers[-1] if answers else None,
                  "error_codes": codes, "events": events, "sql": None,
                  "answer_event_count": len(answers),
                  "conflicting_terminal_events": bool(answers and (errors or any(e.get("event") == "INTERRUPT" for e in events)))}
        if answers and answers[-1].get("askId"):
            code, view = self.request(f'/api/v1/chat/asks/{answers[-1]["askId"]}/sql', identity)
            actual["sql_http_status"] = code
            if code == 200:
                actual["sql"] = json.loads(view)["data"].get("sql")
        task_ids = [e.get("payload", {}).get("askId") or e.get("payload", {}).get("ask_id") for e in events]
        ask_id = next((a for a in task_ids if a), None)
        if ask_id:
            evidence_status, raw_evidence = self.request(f"/api/v1/chat/asks/{ask_id}/evidence", identity)
            actual["evidence_http_status"] = evidence_status
            if evidence_status == 200:
                actual["evidence"] = json.loads(raw_evidence)["data"]
        actual["elapsed_ms"] = round((time.perf_counter() - started) * 1000, 3)
        return actual
