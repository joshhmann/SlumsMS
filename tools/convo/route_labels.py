#!/usr/bin/env python3
"""1.5.0 rc6: label the server's chat-routing decisions offline and score the routing order against the labels.

The server logs one record per routing decision of a player's line (ROUTE CANDIDATES) to
data/convo/routing/<day>.jsonl when auto-QA is on or the JVM has -Dsolomapling.convo.routeLog=true. Each record has the
player's line (the player as a hash, names replaced), the map's recent lines, every candidate with its signals, and the
candidate and step the server chose. This tool shows a model the context and the candidates, asks "which candidate is
this line replying to?", appends the answers to labels.jsonl and prints how often the server's choice matches.

    # cloud (key from the env file; never printed or written)
    set -a; . /root/.config/slumsms/cloud-llm.env; set +a
    python3 tools/convo/route_labels.py data/convo/routing/*.jsonl --base https://api.deepseek.com \
        --model deepseek-flash --key-env SOLOMAPLING_LLM_CLOUD_API_KEY --extra reasoning_effort='"none"'
    # local model (keep it light: it also serves live chat)
    python3 tools/convo/route_labels.py data/convo/routing/*.jsonl --base http://192.168.0.17:8095/v1 \
        --model qwen9b --extra chat_template_kwargs='{"enable_thinking": false}' --price-in 0 --price-out 0
    # score only, no calls
    python3 tools/convo/route_labels.py data/convo/routing/*.jsonl --score-only

Records are sent --batch at a time (default 10 per call). Each call's cost is estimated before it is sent (input
characters / 4 as tokens, plus --max-tokens of output, at --price-in / --price-out dollars per million tokens) and the
run stops before the estimate passes --budget (default $0.20); the real token use then replaces the estimate. The
default prices are deliberately high so the cap errs on the safe side. Records already in labels.jsonl are skipped.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import sys
import time
import urllib.request

NONE = "none"


def load_records(paths):
    out = []
    for p in paths:
        path = pathlib.Path(p)
        if not path.exists() or path.name == "labels.jsonl":
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                continue
    return out


def record_id(r):
    raw = f"{r.get('at')}|{r.get('map')}|{r.get('player')}|{r.get('line')}"
    return hashlib.sha1(raw.encode()).hexdigest()[:16]


def choices(r):
    """The keys the model may pick: every candidate, plus "none" (the line is for nobody / another player)."""
    return [c["key"] for c in r.get("candidates", [])] + [NONE]


def describe(i, r):
    lines = [f"### {i}"]
    lines.append("Recent public chat on the map (oldest first):")
    for c in r.get("context", []):
        who = c.get("who") + (" (bot)" if c.get("bot") else "")
        lines.append(f"  [{c.get('ago')}s ago] {who}: {c.get('text')}")
    lines.append(f"The player ({{player}}) now says: {r.get('line')}")
    lines.append("Candidates:")
    for c in r.get("candidates", []):
        if c["key"].startswith("thread:"):
            lines.append(f"  - {c['key']}: the ongoing bot-to-bot conversation ({c.get('said')}), "
                         f"{'near' if c.get('bubble') else 'far from'} the player")
        else:
            lines.append(f"  - {c['key']}: {c.get('dist')} px away{' (in front of the player)' if c.get('facing') else ''}"
                         f"{', its last line: ' + json.dumps(c.get('said')) if c.get('said') else ', silent'}")
    lines.append(f"  - {NONE}: nobody here / another player / just public chat")
    return "\n".join(lines)


SYSTEM = ("You label chat logs from a MapleStory game. For each numbered case, decide which candidate the player's"
          " line is replying to or talking to. Answer with one JSON object per line and nothing else:"
          ' {"i": <case number>, "label": "<candidate key exactly as written>"}')


def prompt_for(batch):
    return "\n\n".join(describe(i, r) for i, r in batch)


def parse_labels(text, batch):
    """{case number: label} from the model's reply; labels not among the case's candidates are dropped."""
    allowed = {i: set(choices(r)) for i, r in batch}
    out = {}
    for m in re.finditer(r"\{[^{}]*\}", text or ""):
        try:
            o = json.loads(m.group(0))
        except ValueError:
            continue
        i, label = o.get("i"), o.get("label")
        if isinstance(i, int) and i in allowed and label in allowed[i]:
            out[i] = label
    return out


def chosen_of(r):
    return r.get("chosen") or NONE


def score(records, labels):
    """(matched, total, {step: [matched, total]}) of the server's choice against the labels."""
    by_step = {}
    hit = total = 0
    for r in records:
        lab = labels.get(record_id(r))
        if lab is None:
            continue
        ok = chosen_of(r) == lab
        step = r.get("step") or "?"
        s = by_step.setdefault(step, [0, 0])
        s[0] += ok
        s[1] += 1
        hit += ok
        total += 1
    return hit, total, by_step


def estimate_cost(chars_in, tokens_out, price_in, price_out):
    return (chars_in / 4.0) * price_in / 1e6 + tokens_out * price_out / 1e6


def call(base, key, model, system, user, max_tokens, extra, timeout):
    body = {"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "max_tokens": max_tokens, "temperature": 0, "stream": False}
    body.update(extra)
    req = urllib.request.Request(base.rstrip("/") + "/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    if key:
        req.add_header("Authorization", "Bearer " + key)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read())
    msg = data.get("choices", [{}])[0].get("message", {})
    return (msg.get("content") or "").strip(), data.get("usage", {})


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+", help="routing JSONL files (data/convo/routing/<day>.jsonl)")
    ap.add_argument("--labels", default="", help="labels file (default: labels.jsonl next to the first input)")
    ap.add_argument("--base", default="")
    ap.add_argument("--model", default="")
    ap.add_argument("--key-env", default="", help="name of the env var holding the API key (never printed)")
    ap.add_argument("--extra", action="append", default=[], help="name=<raw JSON>, merged into the body")
    ap.add_argument("--batch", type=int, default=10)
    ap.add_argument("--budget", type=float, default=0.20, help="dollars per run")
    ap.add_argument("--price-in", type=float, default=1.0, help="dollars per million input tokens")
    ap.add_argument("--price-out", type=float, default=4.0, help="dollars per million output tokens")
    ap.add_argument("--max-tokens", type=int, default=400)
    ap.add_argument("--pause", type=float, default=1.0)
    ap.add_argument("--timeout", type=float, default=60)
    ap.add_argument("--score-only", action="store_true")
    a = ap.parse_args(argv)

    records = load_records(a.files)
    labels_path = pathlib.Path(a.labels) if a.labels else pathlib.Path(a.files[0]).resolve().parent / "labels.jsonl"
    labels = {}
    if labels_path.exists():
        for x in labels_path.read_text(encoding="utf-8").splitlines():
            try:
                row = json.loads(x)
                labels[row["id"]] = row["label"]
            except (ValueError, KeyError, TypeError):
                continue

    todo = [r for r in records if record_id(r) not in labels and r.get("candidates")]
    spent = 0.0
    if not a.score_only and todo:
        if not a.base or not a.model:
            ap.error("--base and --model are needed to label (or use --score-only)")
        key = os.environ.get(a.key_env, "") if a.key_env else ""
        if a.key_env and not key:
            ap.error(f"{a.key_env} is not set (load the env file with set -a; . file; set +a)")
        extra = {}
        for e in a.extra:
            k, v = e.split("=", 1)
            extra[k] = json.loads(v)
        with labels_path.open("a", encoding="utf-8") as out:
            for start in range(0, len(todo), a.batch):
                batch = list(enumerate(todo[start:start + a.batch], 1))
                user = prompt_for(batch)
                est = estimate_cost(len(SYSTEM) + len(user), a.max_tokens, a.price_in, a.price_out)
                if spent + est > a.budget:
                    print(f"budget: stopping before ${spent + est:.4f} > ${a.budget:.4g}"
                          f" ({len(todo) - start} records left)")
                    break
                try:
                    reply, usage = call(a.base, key, a.model, SYSTEM, user, a.max_tokens, extra, a.timeout)
                except Exception as e:  # noqa: BLE001 - report and stop: a broken endpoint should not burn budget
                    print(f"call failed: {type(e).__name__}: {str(e)[:200]}")
                    break
                if usage.get("prompt_tokens") is not None:
                    spent += (usage.get("prompt_tokens", 0) * a.price_in
                              + usage.get("completion_tokens", 0) * a.price_out) / 1e6
                else:
                    spent += est
                got = parse_labels(reply, batch)
                for i, r in batch:
                    if i in got:
                        rid = record_id(r)
                        labels[rid] = got[i]
                        out.write(json.dumps({"id": rid, "label": got[i], "chosen": chosen_of(r),
                                              "step": r.get("step"), "model": a.model}) + "\n")
                out.flush()
                time.sleep(a.pause)
        print(f"spent about ${spent:.4f} (budget ${a.budget:.4g})")

    hit, total, by_step = score(records, labels)
    if total == 0:
        print(f"{len(records)} records, none labelled yet")
        return 0
    print(f"{len(records)} records, {total} labelled: current rule matches {hit}/{total} = {100.0 * hit / total:.1f}%")
    for step, (h, t) in sorted(by_step.items()):
        print(f"  {step:<22} {h}/{t} = {100.0 * h / t:.1f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
