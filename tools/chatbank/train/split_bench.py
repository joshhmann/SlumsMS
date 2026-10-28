"""Score the 1.6.1 serving split end to end: intent+con from the trained model, tone from the base, one request
per endpoint per line (as ConvoSense would send it), against all-base and all-trained."""
import json, urllib.request, collections, time
E = "/root/OneJev/data/chat3k-r5/"
D = "/root/slumsms-decide-benchmark/chat-classifier-2026-10-27/"
FILES = {"h70": ["h70_intent.jsonl", "h70_tone.jsonl", "h70_con.jsonl"],
         "b96": ["b96_intent.jsonl", "b96_tone.jsonl", D + "b96_con_161.jsonl"]}
def ask(port, model, state, qs):
    body = {"model": model, "state": state, "questions": qs}
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/systemone", json.dumps(body).encode(), {"Content-Type": "application/json"})
    t = time.time(); a = json.loads(urllib.request.urlopen(req, timeout=10).read())["answers"]
    return {k: v["choice"] for k, v in a.items()}, time.time() - t
out = {}
for s, fs in FILES.items():
    lines = collections.defaultdict(dict)
    for f in fs:
        for l in open(f if f.startswith("/") else E + f):
            r = json.loads(l); lines[r["state"]][r["task"]] = (r["question"], max(r["target"], key=r["target"].get))
    plans = {"base": {"base": ("intent", "tone", "con")}, "trained": {"trained": ("intent", "tone", "con")},
             "split": {"trained": ("intent", "con"), "base": ("tone",)}}
    ports = {"base": (8918, "base"), "trained": (8919, "chat-r5")}
    for name, plan in plans.items():
        hit = collections.Counter(); n = collections.Counter(); allok = 0; ms = []
        for state, tasks in lines.items():
            got = {}; slow = 0
            for ep, want in plan.items():
                qs = {t: tasks[t][0] for t in tasks if any(w in t for w in want)}
                if qs:
                    a, dt = ask(*ports[ep], state, qs); got.update(a); slow = max(slow, dt)
            ms.append(slow * 1000); ok = True
            for t, (_, gold) in tasks.items():
                n[t] += 1; hit[t] += got[t] == gold; ok &= got[t] == gold
            allok += ok
        ms.sort()
        out[f"{s}/{name}"] = {**{t: f"{hit[t]/n[t]:.1%} (n {n[t]})" for t in n}, "all_right": f"{allok/len(lines):.1%} of {len(lines)} lines",
                              "p50_ms": round(ms[len(ms)//2]), "p95_ms": round(ms[int(len(ms)*.95)])}
        print(s, name, out[f"{s}/{name}"], flush=True)
json.dump(out, open(D + "split-bench.json", "w"), indent=1)
