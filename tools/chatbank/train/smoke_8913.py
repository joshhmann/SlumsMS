"""Smoke the served :8913 on the death-wish checks and bench-96 con (one request per line, as the server asks)."""
import json, sys, urllib.request
E = "/root/OneJev/data/chat-r7/"
for name in ("deathwish_con", "b96_con"):
    ok = n = 0
    for l in open(E + name + ".jsonl"):
        r = json.loads(l)
        body = {"model": "jev-latest", "state": r["state"], "questions": {r["task"]: r["question"]}}
        req = urllib.request.Request("http://127.0.0.1:8913/v1/systemone", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
        got = json.loads(urllib.request.urlopen(req, timeout=10).read())["answers"][r["task"]]["choice"]
        gold = max(r["target"], key=r["target"].get)
        n += 1; ok += got == gold
        if got != gold and name == "deathwish_con":
            print("  miss:", r["state"].split("chat: ")[-1], "gold", gold, "got", got)
    print(name, f"{ok}/{n}")
