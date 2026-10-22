#!/usr/bin/env python3
"""Owner report 2026-10-22: send the server's exact reply prompts (before / after the context fix) to an
OpenAI-compatible endpoint and print the replies side by side.

1. Build the prompts with the server's own code (scenarios.json -> prompts.json):
       mvn -q -o test -Dtest=ConvoEvalPromptsTest -Dconvo.eval.out=$PWD/tools/convo/eval/prompts.json
2. Run them, one call at a time with a pause (the local model also serves live chat):
       python3 tools/convo/eval/run_eval.py --base http://192.168.0.17:8095/v1 --model qwen9b \
           --extra chat_template_kwargs='{"enable_thinking": false}'
       set -a; . /root/.config/slumsms/cloud-llm.env; set +a
       python3 tools/convo/eval/run_eval.py --base https://api.deepseek.com --model deepseek-flash \
           --key-env SOLOMAPLING_LLM_CLOUD_API_KEY --extra reasoning_effort='"none"'

The API key is only ever read from the named environment variable and sent in the Authorization header; it is never
printed or written. Results (replies, token use, latency) go to stdout and, with --out, to a JSON file.
"""
import argparse
import json
import os
import pathlib
import sys
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent


def call(base, key, model, messages, temperature, max_tokens, extra, timeout):
    body = {"model": model, "messages": messages, "max_tokens": max_tokens, "temperature": temperature,
            "stream": False}
    body.update(extra)
    req = urllib.request.Request(base.rstrip("/") + "/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    if key:
        req.add_header("Authorization", "Bearer " + key)
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = json.loads(r.read())
    except Exception as e:  # noqa: BLE001 - report and go on
        return {"error": type(e).__name__ + ": " + str(e)[:200], "ms": int((time.time() - started) * 1000)}
    msg = data.get("choices", [{}])[0].get("message", {})
    return {"reply": (msg.get("content") or "").strip(), "usage": data.get("usage", {}),
            "ms": int((time.time() - started) * 1000)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--key-env", default="")
    ap.add_argument("--extra", action="append", default=[], help="name=<raw JSON>, merged into the body")
    ap.add_argument("--prompts", default=str(HERE / "prompts.json"))
    ap.add_argument("--variants", default="before,after")
    ap.add_argument("--only", default="", help="comma-separated scenario ids")
    ap.add_argument("--max-calls", type=int, default=40)
    ap.add_argument("--pause", type=float, default=3.0)
    ap.add_argument("--max-tokens", type=int, default=40)
    ap.add_argument("--timeout", type=float, default=20)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    key = os.environ.get(a.key_env, "") if a.key_env else ""
    extra = {}
    for e in a.extra:
        k, v = e.split("=", 1)
        extra[k] = json.loads(v)
    scenarios = json.loads(pathlib.Path(a.prompts).read_text())
    only = set(filter(None, a.only.split(",")))
    calls = 0
    results = []
    for sc in scenarios:
        if only and sc["id"] not in only:
            continue
        print("=" * 100)
        print(f"[{sc['id']}] {sc['kind']}")
        last = sc["after"]["messages"][-1]["content"]
        print("  context: " + last.replace("\n", " | ")[:400])
        print(f"  expect:  {sc['expect']}")
        row = {"id": sc["id"], "line": sc["line"]}
        for v in a.variants.split(","):
            if calls >= a.max_calls:
                print("  (call budget reached)")
                break
            p = sc[v]
            r = call(a.base, key, a.model, p["messages"], p["temperature"], a.max_tokens, extra, a.timeout)
            calls += 1
            row[v] = r
            shown = r.get("reply", r.get("error"))
            print(f"  {v:6s} -> {shown!r}  ({r['ms']} ms, usage {r.get('usage', {}).get('prompt_tokens')}/"
                  f"{r.get('usage', {}).get('completion_tokens')})")
            time.sleep(a.pause)
        results.append(row)
    print(f"\n{calls} calls")
    if a.out:
        pathlib.Path(a.out).write_text(json.dumps({"model": a.model, "results": results}, indent=1))


if __name__ == "__main__":
    sys.exit(main())
