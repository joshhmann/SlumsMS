#!/usr/bin/env python3
"""Compare Decide-compatible endpoints using the real Java request/parser/rules. No network by default."""
import argparse
import hashlib
import json
import math
import pathlib
import random
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = next((p for p in pathlib.Path(__file__).resolve().parents if (p / "pom.xml").is_file()), pathlib.Path.cwd())
QUESTIONS = {"intent": "convo_intent", "tone": "convo_tone", "con": "con"}
DEFAULT_CASES = pathlib.Path(__file__).with_name("cases.jsonl")
BRIDGE = "soloMapling.ArtificialPlayer.BotBrainSystem.DecideBenchmarkBridge"


def read_jsonl(path):
    return [json.loads(line) for line in pathlib.Path(path).read_text().splitlines() if line.strip()]


def write_jsonl(path, rows):
    pathlib.Path(path).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))


def load_cases(path):
    cases = read_jsonl(path)
    if not cases:
        raise ValueError("empty dataset")
    ids = set()
    for row in cases:
        if not isinstance(row.get("id"), str) or row["id"] in ids:
            raise ValueError("missing or duplicate case id")
        ids.add(row["id"])
        if not isinstance(row.get("line"), str) or not row["line"].strip():
            raise ValueError("missing line")
        for name in ("intent", "tone", "con"):
            if name not in row or row[name] is not None and not isinstance(row[name], str):
                raise ValueError("expected label or null: " + name)
    return cases


def bridge(rows, classpath):
    with tempfile.TemporaryDirectory(prefix="slums-decide-") as tmp:
        inp, out = pathlib.Path(tmp) / "input.jsonl", pathlib.Path(tmp) / "output.jsonl"
        write_jsonl(inp, rows)
        subprocess.run(["java", "-cp", classpath, BRIDGE, str(inp), str(out)], cwd=ROOT, check=True)
        result = read_jsonl(out)
    if [r["id"] for r in result] != [r["id"] for r in rows]:
        raise ValueError("bridge case order mismatch")
    return result


def endpoint_url(base):
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("use an http(s) endpoint without embedded credentials, query or fragment")
    path = parsed.path.rstrip("/")
    if not path.endswith("/v1/systemone"):
        path += "/v1/systemone"
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def read_api_key(path):
    """A bearer key for a hosted endpoint, read from a private file (never printed, logged or stored in results)."""
    if path is None:
        return None
    if path.stat().st_mode & 0o077:
        raise SystemExit("api key file must not be readable by group or others (chmod 600)")
    key = path.read_text(encoding="utf-8").strip()
    if not key or "\n" in key:
        raise SystemExit("api key file must hold exactly one non-empty line")
    return key


def call(endpoint, request, timeout, api_key=None):
    started = time.perf_counter()
    row = {"response": "", "http_status": None, "error": None}
    try:
        headers = {"Content-Type": "application/json"}
        if api_key:
            headers["Authorization"] = "Bearer " + api_key
        req = urllib.request.Request(endpoint, data=json.dumps(request).encode(), headers=headers)
        # No proxy or redirect: send synthetic cases only to the explicitly named host.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(req, timeout=timeout) as response:
            row["http_status"] = response.status
            body = response.read(1_048_577)
            if len(body) > 1_048_576:
                row["error"] = "response_too_large"
            else:
                row["response"] = body.decode("utf-8")
    except urllib.error.HTTPError as exc:
        row["http_status"] = exc.code
        row["error"] = "http_error"
    except (OSError, ValueError) as exc:
        row["error"] = type(exc).__name__  # Do not echo arbitrary remote error bodies.
    row["elapsed_ms"] = round((time.perf_counter() - started) * 1000, 3)
    return row


def schema_errors(body, request):
    try:
        root = json.loads(body)
    except (ValueError, TypeError):
        return ["invalid_json"]
    answers = root.get("answers") if isinstance(root, dict) else None
    if not isinstance(answers, dict):
        return ["missing_answers"]
    issues = []
    for name, question in request["questions"].items():
        answer = answers.get(name)
        if not isinstance(answer, dict):
            issues.append(name + ":missing")
            continue
        if not isinstance(answer.get("choice"), str) or answer["choice"] not in question["criteria"]:
            issues.append(name + ":label")
        confidence = answer.get("confidence")
        if type(confidence) not in (int, float) or not math.isfinite(confidence) or not 0 <= confidence <= 1:
            issues.append(name + ":confidence")
    return issues


def classify_metrics(pairs, labels):
    if not pairs:
        return {"n": 0}
    scores = {}
    for label in labels:
        tp = sum(want == label and got == label for want, got in pairs)
        fp = sum(want != label and got == label for want, got in pairs)
        fn = sum(want == label and got != label for want, got in pairs)
        precision = tp / (tp + fp) if tp + fp else 0
        recall = tp / (tp + fn) if tp + fn else 0
        scores[label] = {"support": tp + fn, "precision": precision, "recall": recall,
                         "f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else 0}
    present = [v["f1"] for v in scores.values() if v["support"]]
    return {"n": len(pairs), "accuracy": sum(a == b for a, b in pairs) / len(pairs),
            "macro_f1": sum(present) / len(present), "per_class": scores}


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def score(rows, labels):
    result = {"n": len(rows)}
    for layer in ("raw", "rules", "combined"):
        result[layer] = {}
        for name in QUESTIONS:
            pairs = [(r["case"][name], r[layer].get(name)) for r in rows if r["case"][name] is not None]
            result[layer][name] = classify_metrics(pairs, labels[name])
    # This is a request-intent proxy. Gameplay commitments/permissions still decide whether anything happens.
    result["false_request_ids"] = {
        layer: [r["case"]["id"] for r in rows if r["case"]["intent"] == "talk" and r[layer]["intent"] != "talk"]
        for layer in ("rules", "combined")}
    result["new_wrong_intent_ids"] = [r["case"]["id"] for r in rows
        if r["rules"]["intent"] == r["case"]["intent"] and r["combined"]["intent"] != r["case"]["intent"]]
    result["new_false_con_ids"] = [r["case"]["id"] for r in rows if r["case"]["con"] == "normal"
        and r["rules"]["con"] == "normal" and r["combined"]["con"] != "normal"]
    result["fixed_intent_ids"] = [r["case"]["id"] for r in rows
        if r["rules"]["intent"] != r["case"]["intent"] and r["combined"]["intent"] == r["case"]["intent"]]
    result["missed_request_ids"] = [r["case"]["id"] for r in rows
        if r["case"]["intent"] != "talk" and r["combined"]["intent"] == "talk"]
    times = [r["elapsed_ms"] for r in rows if r.get("elapsed_ms") is not None]
    result["latency_ms"] = {name: percentile(times, q) for name, q in (("p50", .5), ("p95", .95), ("p99", .99))}
    result["transport_errors"] = sum(bool(r.get("error")) for r in rows)
    result["schema_failures"] = sum(bool(r.get("schema_errors")) for r in rows)
    result["within_budget"] = sum(r.get("within_budget", False) for r in rows)
    result["production_model_eligible"] = sum(r["would_ask"] for r in rows)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", help="explicit base URL or full /v1/systemone URL; never defaults to Live")
    parser.add_argument("--api-key-file", type=pathlib.Path, help="private file (chmod 600) with a bearer key for a hosted endpoint such as https://api.typesafe.ai; the key is never written to results")
    parser.add_argument("--model-id", default="decide", help="wire model id; OneJev needs OneJev1.6-0.8B")
    parser.add_argument("--requests", type=pathlib.Path, help="exported requests.jsonl from this dataset; for capture without Java")
    parser.add_argument("--capture-only", action="store_true", help="collect raw answers only; replay on the build host for server scoring")
    parser.add_argument("--name", default="rules-only", help="candidate model/revision/runtime description")
    parser.add_argument("--cases", type=pathlib.Path, default=DEFAULT_CASES)
    parser.add_argument("--out", type=pathlib.Path, required=True, help="new output directory; refuses overwrite")
    parser.add_argument("--classpath", default="target/test-classes:target/Cosmic.jar")
    parser.add_argument("--interval", type=float, default=1, help="minimum seconds between request starts; serial only")
    parser.add_argument("--timeout", type=float, default=3, help="observation timeout, not the production deadline")
    parser.add_argument("--deadline-ms", type=float, default=400, help="production HTTP deadline; default 400 ms")
    parser.add_argument("--seed", type=int, default=160)
    parser.add_argument("--replay", type=pathlib.Path, help="offline rescore a prior raw.jsonl, no endpoint calls")
    args = parser.parse_args()
    if args.endpoint and args.replay:
        parser.error("endpoint and replay are mutually exclusive")
    if not all(math.isfinite(x) and x > 0 for x in (args.interval, args.timeout, args.deadline_ms)):
        parser.error("interval, timeout and deadline must be positive finite numbers")
    if args.capture_only and (not args.endpoint or args.replay):
        parser.error("capture-only requires an endpoint and cannot replay")
    if args.requests and not args.capture_only:
        parser.error("exported requests may only be used with capture-only")
    cases = load_cases(args.cases)
    prepared = read_jsonl(args.requests) if args.requests else bridge(cases, args.classpath)
    if [r["id"] for r in prepared] != [c["id"] for c in cases]:
        parser.error("exported request ids/order do not match cases")
    for row in prepared:
        row["request"]["model"] = args.model_id
    labels = {name: list(prepared[0]["request"]["questions"][q]["criteria"]) for name, q in QUESTIONS.items()}
    for case in cases:
        for name in QUESTIONS:
            if case[name] is not None and case[name] not in labels[name]:
                parser.error("unknown expected label: " + case["id"] + ":" + name)
    endpoint = endpoint_url(args.endpoint) if args.endpoint else None
    api_key = read_api_key(args.api_key_file) if endpoint else None
    saved = {}
    if args.replay:
        records = read_jsonl(args.replay)
        for row in records:
            key = row["id"]
            if key in saved:
                raise ValueError("duplicate replay id")
            saved[key] = row
        if set(saved) != {c["id"] for c in cases}:
            raise ValueError("replay dataset ids differ")
    args.out.mkdir(parents=True, exist_ok=False)
    # Save metadata/requests before the first call, and each answer immediately, so interrupted runs are inspectable.
    try:
        source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, stderr=subprocess.DEVNULL).strip()
    except subprocess.CalledProcessError:
        source_commit = None
    metadata = {"name": args.name, "endpoint": endpoint, "seed": args.seed, "model_id": args.model_id,
                "dataset_sha256": hashlib.sha256(args.cases.read_bytes()).hexdigest(),
                "git_commit": source_commit,
                "runner_sha256": hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
                "exported_requests_sha256": hashlib.sha256(args.requests.read_bytes()).hexdigest() if args.requests else None,
                "deadline_ms": args.deadline_ms, "transport_timeout_seconds": args.timeout,
                "interval_seconds": args.interval, "concurrency": 1, "thresholds": {"intent": .4, "tone": .8, "con": .4},
                "mode": "replay" if args.replay else "endpoint" if endpoint else "rules-only",
                "cache": "direct endpoint; no Java chat cache", "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    (args.out / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    write_jsonl(args.out / "requests.jsonl", prepared)
    order = list(zip(cases, prepared))
    random.Random(args.seed).shuffle(order)
    raw = []
    previous = -math.inf
    with (args.out / "raw.jsonl").open("w") as output:
        for case, prep in order:
            if args.replay:
                record = saved[case["id"]]
                if record.get("request") != prep["request"]:
                    raise ValueError("replay request differs: " + case["id"])
                record = dict(record)
            elif endpoint:
                time.sleep(max(0, previous + args.interval - time.monotonic()))
                previous = time.monotonic()
                record = {"id": case["id"], "request": prep["request"], **call(endpoint, prep["request"], args.timeout, api_key)}
            else:
                record = {"id": case["id"], "request": prep["request"], "response": "", "elapsed_ms": None,
                          "http_status": None, "error": None}
            record["within_budget"] = bool(record["http_status"] is not None and 200 <= record["http_status"] < 300
                and not record["error"] and record["elapsed_ms"] is not None and record["elapsed_ms"] <= args.deadline_ms)
            record["schema_errors"] = schema_errors(record["response"], prep["request"]) if endpoint or args.replay else []
            raw.append(record)
            output.write(json.dumps(record) + "\n")
            output.flush()
    if args.capture_only:
        print(f"Captured {len(raw)} cases in {args.out}; replay raw.jsonl on the build host to score server behavior.")
        return
    case_map = {c["id"]: c for c in cases}
    fused = bridge([{**case_map[r["id"]], "response": r["response"], "within_budget": r["within_budget"]} for r in raw], args.classpath)
    rows = []
    for record, evaluated in zip(raw, fused):
        rows.append({**record, **evaluated, "request": record["request"], "case": case_map[record["id"]],
                     "raw": {name: evaluated["parsed"].get(q, {}).get("choice") for name, q in QUESTIONS.items()}})
    write_jsonl(args.out / "scored.jsonl", rows)
    report = {"metadata": metadata, "overall": score(rows, labels),
              "production_eligible": score([r for r in rows if r["would_ask"]], labels),
              "by_set": {group: score([r for r in rows if r["case"]["set"] == group], labels)
                         for group in sorted({c["set"] for c in cases})}}
    (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    summary = report["overall"]
    print(f"{args.name}: {len(rows)} cases; results in {args.out}")
    print(f"Intent accuracy: rules {summary['rules']['intent']['accuracy']:.1%}, combined {summary['combined']['intent']['accuracy']:.1%}")
    print(f"New wrong intents: {len(summary['new_wrong_intent_ids'])}; new false scam/abuse flags: {len(summary['new_false_con_ids'])}")
    print(f"Latency ms: {summary['latency_ms']}; within {args.deadline_ms:g} ms: {summary['within_budget']}/{len(rows)}")


if __name__ == "__main__":
    main()
