"""r7 split: 5% of convo ids held out (same hash as r7a), minus anything r5 or r7a trained on, so the fresh set is
unseen by all three models; the death-wish check lines never go into training."""
import hashlib
import json

G = "/root/slumsms-decide-benchmark/chatbank-generator/"
D = "/root/slumsms-decide-benchmark/chat-classifier-2026-10-27/"
pairs = [json.loads(l) for l in open(G + "pairs-50k-r6/pairs.jsonl")]
low = lambda t: t.strip().lower()
seen = {low(json.loads(l)["player_line"]) for f in ("r7a-train.jsonl", "pairs-3k-r5.jsonl") for l in open(D + f)}
checks = {low(l.split("\t")[0]) for l in open(D + "deathwish.tsv") if l.strip() and not l.startswith("#")}
hold = lambda p: int(hashlib.sha256(p["convo_id"].encode()).hexdigest(), 16) % 20 == 0
train, fresh, dropped = [], [], 0
for p in pairs:
    line = low(p["player_line"])
    if line in checks or any(low(c["text"]) in checks for c in p.get("context") or []):
        dropped += 1
    elif hold(p) and line not in seen:
        fresh.append(p)
    elif not hold(p):
        train.append(p)
    else:
        dropped += 1  # held out for r7 but r5/r7a trained on it: in neither set
for n, s in (("r7-train.jsonl", train), ("r7-fresh.jsonl", fresh)):
    with open(D + n, "w") as f:
        f.writelines(json.dumps(p, ensure_ascii=False) + "\n" for p in s)
print("pairs", len(pairs), "train", len(train), "fresh", len(fresh), "dropped", dropped)
