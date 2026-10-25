import contextlib
import http.server
import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

spec = importlib.util.spec_from_file_location("decide_benchmark", pathlib.Path(__file__).with_name("run.py"))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)
REQUEST = {"questions": {q: {"criteria": {"talk": "chat"}} for q in bench.QUESTIONS.values()}}
ANSWER = json.dumps({"answers": {q: {"choice": "talk", "confidence": .9} for q in bench.QUESTIONS.values()}})


@contextlib.contextmanager
def endpoint(status=200, body=ANSWER):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            self.server.received = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            self.send_response(status)
            self.end_headers()
            self.wfile.write(body.encode())

        def log_message(self, *_):
            pass
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server, f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class BenchmarkTest(unittest.TestCase):
    def test_explicit_endpoint_contract_and_no_secret_urls(self):
        self.assertEqual("http://localhost:123/v1/systemone", bench.endpoint_url("http://localhost:123"))
        self.assertEqual("http://localhost:123/v1/systemone", bench.endpoint_url("http://localhost:123/v1/systemone/"))
        for url in ("http://user:password@localhost:123", "http://localhost/?key=x", "file:///tmp/model"):
            with self.assertRaises(ValueError):
                bench.endpoint_url(url)

    def test_http_sends_exact_request_and_retains_response(self):
        with endpoint() as (server, base):
            record = bench.call(bench.endpoint_url(base), REQUEST, 1)
            self.assertEqual(REQUEST, server.received)
            self.assertEqual(200, record["http_status"])
            self.assertEqual(ANSWER, record["response"])
            self.assertIsNone(record["error"])
            self.assertGreater(record["elapsed_ms"], 0)

    def test_http_failure_does_not_echo_response_body(self):
        with endpoint(500, "private diagnostic content") as (_, base):
            record = bench.call(bench.endpoint_url(base), REQUEST, 1)
            self.assertEqual(500, record["http_status"])
            self.assertEqual("", record["response"])
            self.assertEqual("http_error", record["error"])

    def test_schema_rejects_partial_invalid_labels_and_invalid_confidences(self):
        self.assertEqual([], bench.schema_errors(ANSWER, REQUEST))
        for body in ("[]", "nonsense", '{"answers":{}}'):
            self.assertTrue(bench.schema_errors(body, REQUEST))
        for confidence in (True, "0.9", -1, 1.1, float("nan"), float("inf")):
            body = json.dumps({"answers": {q: {"choice": "talk", "confidence": confidence} for q in bench.QUESTIONS.values()}})
            self.assertTrue(bench.schema_errors(body, REQUEST), confidence)
        for choice in ("other", [], {}):
            body = json.dumps({"answers": {q: {"choice": choice, "confidence": .9} for q in bench.QUESTIONS.values()}})
            self.assertTrue(bench.schema_errors(body, REQUEST))

    def test_scores_misses_and_new_false_requests_separately_from_baseline_errors(self):
        def row(key, want, rules, combined):
            return {"case": {"id": key, "intent": want, "tone": "neutral", "con": None},
                    "rules": {"intent": rules, "tone": "neutral", "con": "normal"},
                    "combined": {"intent": combined, "tone": "neutral", "con": "normal"},
                    "raw": {"intent": combined, "tone": "neutral", "con": "normal"}, "would_ask": True}
        rows = [row("baseline-error", "talk", "follow", "follow"), row("new-error", "talk", "talk", "party"),
                row("fixed", "help_kill", "talk", "help_kill"), row("missed", "party", "talk", "talk")]
        labels = {"intent": ["talk", "follow", "party", "help_kill"], "tone": ["neutral"], "con": ["normal"]}
        scored = bench.score(rows, labels)
        self.assertEqual(["baseline-error", "new-error"], scored["false_request_ids"]["combined"])
        self.assertEqual(["new-error"], scored["new_wrong_intent_ids"])
        self.assertEqual(["fixed"], scored["fixed_intent_ids"])
        self.assertEqual(["missed"], scored["missed_request_ids"])
        self.assertEqual(0, scored["combined"]["con"]["n"], "unknown historic con labels are never invented")
        self.assertEqual(0, bench.score([], labels)["n"])

    def test_percentiles_include_tail_and_missing_predictions_reduce_recall(self):
        self.assertEqual(999, bench.percentile(list(range(1, 1001)), .999))
        result = bench.classify_metrics([("party", "party"), ("party", None), ("talk", "party")], ["party", "talk"])
        self.assertEqual(.5, result["per_class"]["party"]["precision"])
        self.assertEqual(.5, result["per_class"]["party"]["recall"])

    def test_capture_kit_runs_without_java_or_a_git_checkout_and_preserves_model_id(self):
        with tempfile.TemporaryDirectory() as tmp, endpoint() as (server, base):
            kit = pathlib.Path(tmp) / "kit"
            kit.mkdir()
            shutil.copyfile(pathlib.Path(__file__).with_name("run.py"), kit / "run.py")
            cases = [{"id": "test", "set": "test", "line": "what level r u", "intent": "talk", "tone": "neutral", "con": "normal"}]
            request = {"model": "decide", "state": "synthetic", "questions": {
                q: {"criteria": {cases[0][name]: "expected"}} for name, q in bench.QUESTIONS.items()}}
            bench.write_jsonl(kit / "cases.jsonl", cases)
            bench.write_jsonl(kit / "requests.jsonl", [{"id": "test", "request": request}])
            command = [sys.executable, "run.py", "--cases", "cases.jsonl", "--requests", "requests.jsonl",
                       "--capture-only", "--endpoint", base, "--model-id", "OneJev1.6-0.8B", "--out", "run"]
            done = subprocess.run(command, cwd=kit, capture_output=True, text=True)
            self.assertEqual(0, done.returncode, done.stderr)
            captured = bench.read_jsonl(kit / "run/raw.jsonl")
            self.assertEqual("OneJev1.6-0.8B", server.received["model"])
            self.assertEqual(server.received, captured[0]["request"])
            self.assertEqual(request["questions"], server.received["questions"])
            self.assertFalse((kit / "run/report.json").exists(), "capture must not claim a server replay")
            self.assertNotEqual(0, subprocess.run(command, cwd=kit, capture_output=True).returncode, "never overwrite a run")

    def test_dataset_is_frozen_unique_and_covers_all_production_classes(self):
        cases = bench.load_cases(bench.DEFAULT_CASES)
        self.assertEqual(172, len(cases))
        self.assertEqual(15, len({c["intent"] for c in cases}))
        self.assertEqual(4, len({c["tone"] for c in cases}))
        self.assertEqual(5, len({c["con"] for c in cases if c["con"] is not None}))
        self.assertEqual(83, sum(c["set"] == "challenge-v1" for c in cases))
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "duplicate.jsonl"
            bench.write_jsonl(path, [cases[0], cases[0]])
            with self.assertRaises(ValueError):
                bench.load_cases(path)


if __name__ == "__main__":
    unittest.main()
