#!/usr/bin/env python3
"""Ticket 48: turn a soak's raw files into markdown tables (graphs as tables).

usage: analyze.py <soak dir> <run name> [--bucket-min 60] [--since 'YYYY-mm-dd HH:MM:SS'] [--until ...]
Reads <dir>/collect/metrics.tsv, <dir>/guard/guard.tsv, <dir>/guard/baseline.txt, <dir>/gc/gc.log*,
<dir>/runs/<run>/events.tsv and minute.tsv. Times are UTC.
"""
import argparse, csv, datetime as dt, glob, os, re, statistics as st, collections

ap = argparse.ArgumentParser()
ap.add_argument("dir"); ap.add_argument("run")
ap.add_argument("--bucket-min", type=int, default=60)
ap.add_argument("--since"); ap.add_argument("--until")
a = ap.parse_args()
B = a.bucket_min * 60
P = lambda s: dt.datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=dt.timezone.utc).timestamp()
lo = P(a.since) if a.since else 0
hi = P(a.until) if a.until else 1e12

def num(x):
    try: return float(x)
    except (TypeError, ValueError): return None

def pct(v, q):
    v = sorted(v)
    if not v: return None
    k = (len(v) - 1) * q; f = int(k); c = min(f + 1, len(v) - 1)
    return v[f] + (v[c] - v[f]) * (k - f)

def f0(x, d=0):
    return "" if x is None else (f"{x:.{d}f}")

def slope(xs, ys):
    if len(xs) < 3: return None
    mx, my = st.mean(xs), st.mean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return None if den == 0 else sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den

def rows(path):
    if not os.path.exists(path): return []
    with open(path) as f:
        return list(csv.DictReader(f, delimiter="\t"))

def bucket(t, t0):
    return int((t - t0) // B)

def lab(k):
    return dt.datetime.fromtimestamp(t0 + k * B, dt.timezone.utc).strftime("%m-%d %H:%M")

out = []
w = out.append

# ── target metrics ──
m = [r for r in rows(os.path.join(a.dir, "collect/metrics.tsv")) if lo <= P(r["utc"]) <= hi]
t0 = lo if lo else (P(m[0]["utc"]) if m else 0)
lo = max(lo, t0)
if m:
    w(f"### Soak target over time ({a.bucket_min} min buckets, from {m[0]['utc']} UTC)\n")
    w("| UTC | bots online (min-max) | players | heap used MB avg / max | old gen MB min / max | GC ms/min avg | CPU % avg (of one core) | RSS MB max | DB pool waiting max | BotDbGate free min / overruns | macro tick avg ms | movement tick ms/s | wheel-lag lines | LLM fallbacks | errors |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    g = collections.defaultdict(list)
    for r in m: g[bucket(P(r["utc"]), t0)].append(r)
    for k in sorted(g):
        rs = g[k]
        col = lambda c: [v for v in (num(r[c]) for r in rs) if v is not None]
        on = col("bots_online"); heap = col("heap_used_mb"); old = col("old_used_mb")
        ov = col("gate_overruns")
        w("| {} | {} | {} | {} / {} | {} / {} | {} | {} | {} | {} | {} / {} | {} | {} | {} | {} | {} |".format(
            lab(k),
            f"{f0(min(on))}-{f0(max(on))}" if on else "", f0(max(col("players")) if col("players") else None),
            f0(st.mean(heap)) if heap else "", f0(max(heap)) if heap else "",
            f0(min(old)) if old else "", f0(max(old)) if old else "",
            f0(st.mean(col("gc_ms_per_min")), 0) if col("gc_ms_per_min") else "",
            f0(st.mean(col("cpu_pct")), 0) if col("cpu_pct") else "",
            f0(max(col("rss_mb"))) if col("rss_mb") else "",
            f0(max(col("pool_waiting"))) if col("pool_waiting") else "",
            f0(min(col("gate_free"))) if col("gate_free") else "", f0(max(ov)) if ov else "",
            f0(st.mean(col("macro_avg_ms")), 0) if col("macro_avg_ms") else "",
            f0(st.mean(col("movement_ms_per_s")), 1) if col("movement_ms_per_s") else "",
            f0(sum(col("wheel_lag_lines"))), f0(sum(col("llm_fallbacks"))), f0(sum(col("err_lines")))))
    reasons = collections.Counter()
    for r in m:
        for kv in filter(None, r["llm_fallback_reasons"].split(";")):
            k_, v_ = kv.rsplit("=", 1); reasons[k_] += int(v_)
    w("")
    w("LLM fallbacks by reason over the window: " + (", ".join(f"`{k}` {v}" for k, v in reasons.most_common()) or "none"))
    # growth: heap after GC (old gen minimum per bucket) and tick time, excluding the first bucket (warm-up)
    xs, ys_old, ys_mov, ys_mac = [], [], [], []
    for k in sorted(g):
        if k == 0: continue
        rs = g[k]
        old = [v for v in (num(r["old_used_mb"]) for r in rs) if v is not None]
        mov = [v for v in (num(r["movement_ms_per_s"]) for r in rs) if v is not None]
        mac = [v for v in (num(r["macro_avg_ms"]) for r in rs) if v is not None]
        if old and mov and mac:
            xs.append(k * a.bucket_min / 60); ys_old.append(min(old)); ys_mov.append(st.mean(mov)); ys_mac.append(st.mean(mac))
    w("")
    w(f"Trend after the first bucket (least squares): old gen minimum {f0(slope(xs, ys_old), 1)} MB/h, "
      f"movement tick {f0(slope(xs, ys_mov), 2)} ms/s per h, macro tick avg {f0(slope(xs, ys_mac), 1)} ms per h "
      f"({len(xs)} buckets).")
    w("")

# ── GC pauses ──
pauses = []   # (t, kind, ms, after_mb)
start_re = re.compile(r"^\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+)([+-]\d{4})\]")
pause_re = re.compile(r"GC\(\d+\) (Pause [A-Za-z]+(?: \([^)]*\))*) (\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms")
for f in sorted(glob.glob(os.path.join(a.dir, "gc/gc.log*"))):
    for line in open(f, errors="replace"):
        mm = pause_re.search(line)
        if not mm: continue
        ts = start_re.match(line)
        if not ts: continue
        t = dt.datetime.strptime(ts.group(1) + ts.group(2), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp()
        if lo <= t <= hi:
            pauses.append((t, mm.group(1), float(mm.group(5)), int(mm.group(3)), int(mm.group(4))))
pauses.sort()
if pauses:
    tp0 = t0
    w(f"### GC pauses ({len(pauses)} pauses, G1, from the -Xlog:gc* log)\n")
    kinds = collections.Counter(p[1] for p in pauses)
    w("| pause kind | count | p50 ms | p99 ms | max ms |")
    w("|---|---|---|---|---|")
    for k_, n in kinds.most_common():
        v = [p[2] for p in pauses if p[1] == k_]
        w(f"| {k_} | {n} | {f0(pct(v, .5), 1)} | {f0(pct(v, .99), 1)} | {f0(max(v), 1)} |")
    w("")
    w(f"| UTC | pauses | p50 ms | p99 ms | max ms | heap after GC MB min / max | pause ms per min |")
    w("|---|---|---|---|---|---|---|")
    g = collections.defaultdict(list)
    for p in pauses: g[bucket(p[0], tp0)].append(p)
    xs, ys = [], []
    for k in sorted(g):
        v = [p[2] for p in g[k]]; after = [p[3] for p in g[k]]
        w(f"| {lab(k)} | {len(v)} | {f0(pct(v, .5), 1)} | {f0(pct(v, .99), 1)} | "
          f"{f0(max(v), 1)} | {min(after)} / {max(after)} | {f0(sum(v) / a.bucket_min, 0)} |")
        if k > 0:
            xs.append(k * a.bucket_min / 60); ys.append(min(after))
    w("")
    w(f"Heap after GC, bucket minimum, trend after the first bucket: {f0(slope(xs, ys), 1)} MB/h over {len(xs)} buckets.")
    w("")

# ── load generator ──
ev = []
evf = os.path.join(a.dir, "runs", a.run, "events.tsv")
if os.path.exists(evf):
    for line in open(evf):
        p = line.rstrip("\n").split("\t")
        if len(p) < 6: continue
        t = int(p[0]) / 1000
        if lo <= t <= hi: ev.append((t, p[1], p[2], float(p[3]), p[4] == "1", p[5]))
if ev:
    w(f"### Player experience (load generator, run `{a.run}`)\n")
    w("| what | n | ok % | p50 ms | p95 ms | max ms | settled p50 / p95 ms (map fully loaded) |")
    w("|---|---|---|---|---|---|---|")
    for what in ["login", "cc", "warp", "chat_echo", "whisper_reply", "error", "disconnected", "warp_skip"]:
        e = [x for x in ev if x[2] == what]
        if not e: continue
        okv = [x[3] for x in e if x[4]]
        settled = []
        for x in e:
            s = re.search(r"settled_ms=(\d+)", x[5])
            if s and x[4]: settled.append(float(s.group(1)))
        dd = 1 if what == "chat_echo" else 0
        w(f"| {what} | {len(e)} | {100 * len(okv) / len(e):.0f} | {f0(pct(okv, .5), dd)} | {f0(pct(okv, .95), dd)} | "
          f"{f0(max(okv) if okv else None, dd)} | {f0(pct(settled, .5), 0)} / {f0(pct(settled, .95), 0)} |")
    w("")
    busy = [x for x in ev if x[2] in ("login", "cc", "warp")]
    by_map = collections.defaultdict(list)
    for x in busy:
        mm = re.search(r"players_on_screen=(\d+)", x[5]); s = re.search(r"settled_ms=(\d+)", x[5])
        mp = re.search(r"map=(\d+)", x[5]) or re.search(r"->(\d+)", x[5])
        if mm and s and mp and x[4]:
            by_map[int(mp.group(1))].append((int(mm.group(1)), float(s.group(1))))
    if by_map:
        w("Map entry by map (players on screen at entry and time until the map's packet burst ends; >= 400 ms by"
          " construction, the client waits for 400 ms of quiet):\n")
        w("| map | entries | players on screen avg / max | settled p50 / p95 ms |")
        w("|---|---|---|---|")
        for mp, v in sorted(by_map.items(), key=lambda kv: -st.mean(p[0] for p in kv[1]))[:12]:
            w(f"| {mp} | {len(v)} | {st.mean(p[0] for p in v):.0f} / {max(p[0] for p in v)} | "
              f"{f0(pct([p[1] for p in v], .5), 0)} / {f0(pct([p[1] for p in v], .95), 0)} |")
        w("")
    te0 = t0
    g = collections.defaultdict(list)
    for x in ev: g[bucket(x[0], te0)].append(x)
    w("| UTC | logins p50 / p95 ms | cc p50 / p95 ms | warp settled p95 ms | chat echo p50 / p95 / max ms | whisper reply p50 / p95 ms (ok/n) | errors |")
    w("|---|---|---|---|---|---|---|")
    for k in sorted(g):
        e = g[k]
        sel = lambda what: [x[3] for x in e if x[2] == what and x[4]]
        ws = [re.search(r"settled_ms=(\d+)", x[5]) for x in e if x[2] == "warp" and x[4]]
        wsv = [float(s.group(1)) for s in ws if s]
        wh = [x for x in e if x[2] == "whisper_reply"]
        w(f"| {lab(k)} | {f0(pct(sel('login'), .5))} / {f0(pct(sel('login'), .95))} | "
          f"{f0(pct(sel('cc'), .5))} / {f0(pct(sel('cc'), .95))} | {f0(pct(wsv, .95))} | "
          f"{f0(pct(sel('chat_echo'), .5), 1)} / {f0(pct(sel('chat_echo'), .95), 1)} / {f0(max(sel('chat_echo')) if sel('chat_echo') else None, 1)} | "
          f"{f0(pct(sel('whisper_reply'), .5))} / {f0(pct(sel('whisper_reply'), .95))} ({sum(1 for x in wh if x[4])}/{len(wh)}) | "
          f"{sum(1 for x in e if x[2] in ('error', 'disconnected'))} |")
    w("")
mi = os.path.join(a.dir, "runs", a.run, "minute.tsv")
if os.path.exists(mi):
    by = collections.defaultdict(list)
    for line in open(mi):
        p = line.rstrip("\n").split("\t")
        if len(p) < 8 or not (lo <= int(p[0]) / 1000 <= hi): continue
        by[int(p[3])].append((int(p[4]), int(p[5]), int(p[6]), int(p[7])))
    if by:
        w("Traffic a client receives while standing in a map (per-minute samples):\n")
        w("| map | client-minutes | players on screen avg | packets/s avg / max | KB/s avg / max | movement packets/s avg |")
        w("|---|---|---|---|---|---|")
        for mp, v in sorted(by.items(), key=lambda kv: -st.mean(x[1] for x in kv[1]))[:10]:
            w(f"| {mp} | {len(v)} | {st.mean(x[0] for x in v):.0f} | {st.mean(x[1] for x in v) / 60:.1f} / {max(x[1] for x in v) / 60:.1f} | "
              f"{st.mean(x[2] for x in v) / 60 / 1024:.1f} / {max(x[2] for x in v) / 60 / 1024:.1f} | {st.mean(x[3] for x in v) / 60:.1f} |")
        w("")

# ── live guard ──
gd = rows(os.path.join(a.dir, "guard/guard.tsv"))
if gd:
    base = open(os.path.join(a.dir, "guard/baseline.txt")).read().strip() if os.path.exists(os.path.join(a.dir, "guard/baseline.txt")) else ""
    w(f"### Live (slumsms.service) during the soak, from the guard\n")
    w(f"Baseline (before the soak's load generator; the test server already ran its bots): `{base}`\n")
    gs = [r for r in gd if r["phase"] == "soak" and lo <= P(r["utc"]) <= hi]
    if gs:
        tg0 = t0
        g = collections.defaultdict(list)
        for r in gs: g[bucket(P(r["utc"]), tg0)].append(r)
        w("| UTC | live CPU % median | live CPU stall ms/s median / p95 / max | live GC ms/min median / max | live heap MB max | MemAvailable MB min | load1 max | live wheel-lag lines | guard actions |")
        w("|---|---|---|---|---|---|---|---|---|")
        for k in sorted(g):
            rs = g[k]
            col = lambda c: [v for v in (num(r.get(c)) for r in rs) if v is not None]
            rq = col("live_cpu_stall_ms_per_s") or col("live_rq_ms_per_s")
            acts = [r["action"] for r in rs if r.get("action")]
            w(f"| {lab(k)} | {f0(st.median(col('live_cpu_pct')), 1)} | "
              f"{f0(st.median(rq), 1)} / {f0(pct(rq, .95), 1)} / {f0(max(rq), 1)} | {f0(st.median(col('live_gc_ms_per_min')))} / {f0(max(col('live_gc_ms_per_min')))} | "
              f"{f0(max(col('live_heap_mb')))} | {f0(min(col('mem_avail_mb')))} | {f0(max(col('load1')), 2)} | {f0(sum(col('live_wheel_lag_lines')))} | {', '.join(acts) or '-'} |")
        w("")
print("\n".join(out))
