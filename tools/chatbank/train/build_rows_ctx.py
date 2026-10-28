#!/usr/bin/env python3
"""Pairs -> OneJev unified rows WITH the earlier lines in the state (round 6), intent and con only: tone stays on the
base model (owner 2026-10-28). Usage: build_rows_ctx.py pairs.jsonl out_dir [fresh.jsonl]. Fresh pairs (never trained
on) become eval files: ctx_* (their earlier lines in the state), noctx_* (the same lines without them), fresh_* (all).
Frozen sets are converted from the checksummed repo files for eval only, exactly as build_rows.py does."""
import hashlib, json, pathlib, sys
ROOT = pathlib.Path("/root/slumsms-decide-benchmark")
REPO = ROOT / "chatbank-generator/repo"
for row_ in (REPO / "tools/chatbank/HOLDOUT-SHA256SUMS").read_text().splitlines():
    if row_.strip() and not row_.startswith("#"):
        d, n = row_.split(None, 1)
        assert hashlib.sha256((REPO / n.strip()).read_bytes()).hexdigest() == d, n
Q = json.loads((ROOT / "tools/convo/benchmark/requests.jsonl").open().readline())["request"]["questions"]
Q["con"]["criteria"].update({
    "scam": "begging for free mesos or items, borrowing, doubling, asking you to give first, or asking for "
            "your password or login",
    "rwt": "real money, paypal, dollars or selling accounts",
    # 1.6.1 text (Server Dev SlumsMS, 2026-10-27), shipped with the trained model
    "harassment": "targeted, repeated or personal abuse: telling you to die, going after you personally, "
                  "not generic trash talk"})
PREFIX = "A MapleStory player typed this to you in chat: "
PMAP = {"question": "talk", "chatter": "talk", "none": "talk", "come_with_me": "come", "where_train": "guide"}
WHO = {"P": "player", "B": "you", "O": "someone nearby"}
TASKS = (("player_intent", "convo_intent"), ("player_con", "con"))


def state(text, context=()):
    """What the server sends: the earlier lines (oldest first), then the line exactly as today."""
    lines = [f"{WHO[c['who']]}: {c['text']}" for c in context or ()]
    return ("Earlier in this chat:\n" + "\n".join(lines) + "\n" if lines else "") + PREFIX + text


def row(i, src, task, split, text, label, context=()):
    if label not in Q[task]["criteria"]:
        return None
    return {"id": f"{src}-{task}-{i:05d}", "source": src, "task": task, "split": split, "license": "internal",
            "state": state(text, context), "question": {"type": "choice", "instructions": Q[task]["instructions"],
                                                        "criteria": Q[task]["criteria"]}, "target": {label: 1.0}}


def write(path, rows):
    rows = [r for r in rows if r]
    with open(path, "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    return len(rows)


def pairs(path):
    out = []
    for line in open(path):
        p = json.loads(line)
        if p.get("variant") == "lfg":
            p["player_intent"] = "party"
        out.append(p)
    return out


out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
train = [row(i, "gen", task, "train", p["player_line"], p[field], p.get("context"))
         for i, p in enumerate(pairs(sys.argv[1])) for field, task in TASKS]
print("train", write(out / "train.jsonl", train))


def tsv(p):
    return [l.split("\t") for l in (REPO / p).read_text().splitlines() if l.strip() and not l.startswith("#")]


ch = tsv("src/test/resources/chat/convo-holdout.tsv")      # line, intent, tone, entities
hl = tsv("src/test/resources/chat/holdout-lines.tsv")      # line, tier, intent, con
bench = [json.loads(l) for l in (REPO / "tools/convo/benchmark/eval/fresh-v3/cases.jsonl").read_text().splitlines()]
sets = {
    "h70_intent": [row(i, "h70", "convo_intent", "eval", f[0], PMAP.get(f[1], f[1])) for i, f in enumerate(ch)]
                + [row(100 + i, "h70", "convo_intent", "eval", f[0], PMAP.get(f[2], f[2])) for i, f in enumerate(hl)],
    "h70_con": [row(100 + i, "h70", "con", "eval", f[0], f[3]) for i, f in enumerate(hl) if len(f) > 3],
    "b96_intent": [row(i, "b96", "convo_intent", "eval", b["line"], b["intent"]) for i, b in enumerate(bench)],
    "b96_con": [row(i, "b96", "con", "eval", b["line"], b["con"]) for i, b in enumerate(bench)],
}
for k, v in sets.items():
    print(k, write(out / f"{k}.jsonl", v))

if len(sys.argv) > 3:
    fresh = pairs(sys.argv[3])
    ctx = [p for p in fresh if p.get("context")]
    for field, task in TASKS:
        print(f"ctx_{task}", write(out / f"ctx_{task}.jsonl",
              [row(i, "fresh", task, "eval", p["player_line"], p[field], p["context"]) for i, p in enumerate(ctx)]))
        print(f"noctx_{task}", write(out / f"noctx_{task}.jsonl",
              [row(i, "fresh", task, "eval", p["player_line"], p[field]) for i, p in enumerate(ctx)]))
        print(f"fresh_{task}", write(out / f"fresh_{task}.jsonl",
              [row(i, "fresh", task, "eval", p["player_line"], p[field], p.get("context")) for i, p in enumerate(fresh)]))
