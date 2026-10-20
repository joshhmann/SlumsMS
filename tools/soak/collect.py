#!/usr/bin/env python3
"""Ticket 48 soak collector: one row every INTERVAL s about the soak target (read-only).

Sources: the dashboard exporter's live.json (-Dsolomapling.dashboard=true: bots online/target, players, heap, GC,
DB pool, BotDbGate, BotPerformanceMonitor phases), jstat -gc on the target (old gen, GC counts/times; reads the
hsperfdata file, no attach), /proc (RSS, CPU), and the target's journal since the last row (LLM fallbacks by
reason, BotTickService wheel-lag lines, BotDbGate overrun warnings, ERROR/Exception lines, stuck map changes).

usage: collect.py <outdir> [--unit slums-persistence-test] [--dashboard DIR] [--interval 30]
"""
import argparse, json, os, re, subprocess, time, datetime, collections

ap = argparse.ArgumentParser()
ap.add_argument("out")
ap.add_argument("--unit", default="slums-persistence-test")
ap.add_argument("--dashboard", default="/opt/mapleroot-dev/soak-t48/dashboard")
ap.add_argument("--interval", type=int, default=30)
a = ap.parse_args()
os.makedirs(a.out, exist_ok=True)
tsv = os.path.join(a.out, "metrics.tsv")
HZ = os.sysconf("SC_CLK_TCK")
COLS = ["utc", "uptime_s", "bots_online", "bots_target", "roster", "players", "lod_dynamic", "lod_active_maps",
        "heap_used_mb", "old_used_mb", "old_cap_mb", "young_gc", "young_gc_s", "full_gc", "full_gc_s", "conc_gc", "gc_ms_per_min",
        "rss_mb", "cpu_pct", "threads", "pool_active", "pool_waiting", "gate_free", "gate_permits", "gate_overruns",
        "tick_ms_per_s", "macro_calls_per_s", "macro_avg_ms", "movement_ms_per_s", "movement_avg_us", "pathfind_ms_per_s",
        "offscreen_ms_per_s", "observer_ms_per_s", "active_ms_per_s", "llm_fallbacks", "llm_fallback_reasons",
        "wheel_lag_lines", "wheel_lag_max_ms", "gate_warn_lines", "err_lines", "stuck_map_lines"]

def sh(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=60).stdout
    except Exception:
        return ""

def pid():
    v = sh(["systemctl", "show", "-p", "MainPID", "--value", a.unit]).strip()
    return int(v) if v.isdigit() else 0

def ticks(p):
    try:
        f = open(f"/proc/{p}/stat").read().rsplit(")", 1)[1].split()
        return int(f[11]) + int(f[12])
    except Exception:
        return 0

def rss(p):
    try:
        for l in open(f"/proc/{p}/status"):
            if l.startswith("VmRSS"):
                return int(l.split()[1]) // 1024
    except Exception:
        pass
    return 0

def jstat(p):
    out = sh(["jstat", "-gc", str(p)]).split("\n")
    if len(out) < 2 or not out[1].strip():
        return {}
    return dict(zip(out[0].split(), out[1].split()))

if not os.path.exists(tsv):
    with open(tsv, "w") as f:
        f.write("\t".join(COLS) + "\n")
p = pid(); prev_t = ticks(p); prev_w = time.time(); since = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
while True:
    time.sleep(a.interval)
    np_ = pid()
    if np_ != p:
        p = np_; prev_t = ticks(p); prev_w = time.time()
    now = time.time(); t = ticks(p)
    cpu = (t - prev_t) / HZ / max(1e-3, now - prev_w) * 100
    prev_t, prev_w = t, now
    j = sh(["journalctl", "-u", a.unit, "--since", since, "--no-pager", "-o", "cat"])
    since = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    reasons = collections.Counter()
    lag = []; gate = err = stuck = 0
    for line in j.splitlines():
        if "fell back to scripted dialogue" in line:
            r = line.split("dialogue:", 1)[-1].strip()
            reasons[re.sub(r"\d+", "N", r)[:40]] += 1
        elif "wheel lag avg" in line:
            m = re.search(r"wheel lag avg (\d+)ms", line)
            if m: lag.append(int(m.group(1)))
        elif "for the bots' share of the pool" in line:
            gate += 1
        elif "got stuck when changing maps" in line:
            stuck += 1
        if re.search(r" ERROR |Exception", line):
            err += 1
    row = dict.fromkeys(COLS, "")
    row.update(utc=datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S"), rss_mb=rss(p), cpu_pct=f"{cpu:.1f}",
               llm_fallbacks=sum(reasons.values()), llm_fallback_reasons=";".join(f"{k}={v}" for k, v in reasons.most_common()),
               wheel_lag_lines=len(lag), wheel_lag_max_ms=max(lag) if lag else "", gate_warn_lines=gate, err_lines=err,
               stuck_map_lines=stuck)
    g = jstat(p)
    if g:
        f = lambda k: float(g.get(k, "0") or 0)
        row.update(old_used_mb=f"{f('OU')/1024:.0f}", old_cap_mb=f"{f('OC')/1024:.0f}", young_gc=g.get("YGC"),
                   young_gc_s=g.get("YGCT"), full_gc=g.get("FGC"), full_gc_s=g.get("FGCT"), conc_gc=g.get("CGC", ""))
    try:
        d = json.load(open(os.path.join(a.dashboard, "live.json")))
        b, h = d.get("bots", {}), d.get("health", {})
        ph = {x[0]: x for x in h.get("phases", [])}
        pv = lambda n, i: ph[n][i] if n in ph else ""
        row.update(uptime_s=h.get("uptimeS"), bots_online=b.get("online"), bots_target=b.get("target"), roster=b.get("roster"),
                   players=b.get("players"), lod_dynamic=b.get("lod", {}).get("dynamicBots"),
                   lod_active_maps=b.get("lod", {}).get("activeMaps"), heap_used_mb=h.get("heapUsedMb"),
                   gc_ms_per_min=h.get("gcMsPerMin"), threads=h.get("threads"),
                   pool_active=h.get("pool", {}).get("active"), pool_waiting=h.get("pool", {}).get("waiting"),
                   gate_free=h.get("gate", {}).get("free"), gate_permits=h.get("gate", {}).get("permits"),
                   gate_overruns=h.get("gate", {}).get("overruns"), tick_ms_per_s=h.get("tickMsPerS"),
                   macro_calls_per_s=pv("macro-tick", 4),
                   macro_avg_ms=(f"{ph['macro-tick'][5]/1000:.1f}" if "macro-tick" in ph else ""),
                   movement_ms_per_s=pv("movement-tick", 3), movement_avg_us=pv("movement-tick", 5),
                   pathfind_ms_per_s=pv("pathfind", 3), offscreen_ms_per_s=pv("offscreen-accrual", 3),
                   observer_ms_per_s=pv("observer-poll", 3), active_ms_per_s=h.get("buckets", {}).get("ACTIVE"))
    except Exception:
        pass
    with open(tsv, "a") as f:
        f.write("\t".join(str(row[c]) for c in COLS) + "\n")
