#!/usr/bin/env python3
"""Early-quest list per start job, with a verdict and the reasons for it. R&D, offline, read-only.

    python3 tools/questcheck/early_quests.py --wz <server wz dir> --scripts scripts --db-dir <tsv dir> \
        [--max-level 30] [--json out.json] [--md out.md]

For each start job (Explorer = the Maple Island Beginner path into all five branches, Noblesse, Legend/Aran; Evan is
parked) it lists every quest whose minimum level is at most --max-level that the job's path is offered, and gives each
one a verdict:

  RUNNABLE         nothing here stops a server-side character from doing it; this is NOT a pass, only "no known blocker"
  CLIENT-REQUIRED  the quest has a start or end script (NPC dialogue choices, scripted warps): a real client is needed
  BLOCKED          a stated reason a sweep would fail or stall: an item nothing gives, a mob that spawns nowhere, an NPC
                   on no map, a prerequisite that can never be finished, a map the character cannot reach, or a quest_check ERROR
  EXPIRED          an event quest whose end date is past (excluded from the counts)

Reachability: a character starts on the job's start map and walks the portal graph from Map.wz, plus the warps found in
portal scripts and NPC scripts (every map id such a script names, an over-approximation: a conditional or paid warp
counts as an edge). A map outside that set is "unreachable". Only quests whose start NPC, complete NPC or required mob
and item sources are all inside the set are RUNNABLE. Reads the same inputs as quest_check.py and reuses its parsers.
Findings are leads, not verdicts (quest_check says the same): a code path this tool cannot see can give an item."""
import argparse
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import quest_check as qc  # noqa: E402

# start jobs: (start map, set of job ids the path can hold up to the early levels)
EXPLORER_JOBS = {0, 100, 110, 120, 130, 200, 210, 220, 230, 300, 310, 320, 400, 410, 420, 500, 510, 520}
NOBLESSE_JOBS = {1000, 1100, 1200, 1300, 1400, 1500}
LEGEND_JOBS = {2000, 2100}
# Each start job begins on an intro map the server creates the character on (MapId.STARTING_MAP_NOBLESSE, ARAN_INTRO; beginners
# on map 0). The intro maps have no portal out (the cutscene scripts and the client carry the character on), so reachability
# also starts from the first town after the intro. That second map is an ASSUMPTION of this tool, listed here to be checked:
# 10000 Mushroom Town (Maple Island), 130000000 Ereve, 140000000 Rien.
START_JOBS = {
    "explorer": ((0, 10000), EXPLORER_JOBS),
    "noblesse": ((130030000, 130000000), NOBLESSE_JOBS),
    "legend": ((140090000, 140000000), LEGEND_JOBS),
}
NO_TARGET = 999999999
MAP_ID = re.compile(r"(?<![\d.])(\d{7,9})(?![\d.])")


def portal_edges(wz, scripts, known_maps):
    """{map: set(target maps)} from portal targets, portal scripts and NPC scripts. Returns (edges, how) with how[(a,b)]."""
    edges = collections.defaultdict(set)
    portal_script_maps = collections.defaultdict(set)   # portal script name -> maps that carry it
    root = os.path.join(wz, "Map.wz", "Map")
    for d in os.listdir(root) if os.path.isdir(root) else ():
        p = os.path.join(root, d)
        if not os.path.isdir(p):
            continue
        for f in os.listdir(p):
            if not f.endswith(".img.xml"):
                continue
            mid = qc.ival(f.split(".")[0])
            try:
                portals = [c for c in qc.parse(os.path.join(p, f)) if c.get("name") == "portal"]
            except Exception:
                continue
            for pt in portals:
                for node in pt:
                    tm = qc.ival(qc.leaf(node, "tm"))
                    if tm is not None and tm != NO_TARGET and tm in known_maps:
                        edges[mid].add(tm)
                    sc = qc.leaf(node, "script")
                    if sc:
                        portal_script_maps[sc].add(mid)
    def script_maps(path):
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            return set()
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        return {int(n) for n in MAP_ID.findall(text) if int(n) in known_maps}
    return edges, portal_script_maps, script_maps


def build_graph(g, wz, scripts):
    known = set(g.maps)
    edges, portal_script_maps, script_maps = portal_edges(wz, scripts, known)
    if scripts:
        pdir = os.path.join(scripts, "portal")
        for name, maps in portal_script_maps.items():
            for tgt in script_maps(os.path.join(pdir, name + ".js")):
                for m in maps:
                    if tgt != m:
                        edges[m].add(tgt)
        ndir = os.path.join(scripts, "npc")
        for npc, maps in g.npc_maps.items():
            for tgt in script_maps(os.path.join(ndir, "%d.js" % npc)):
                for m in maps:
                    if tgt not in maps:
                        edges[m].add(tgt)
    return edges


def reachable(edges, starts):
    starts = [starts] if isinstance(starts, int) else list(starts)
    seen, todo = set(starts), list(starts)
    while todo:
        for n in edges.get(todo.pop(), ()):
            if n not in seen:
                seen.add(n)
                todo.append(n)
    return seen


def offered(start_jobs, path_jobs):
    """A quest with no job list is offered to everyone; otherwise the path must hold one of the listed jobs."""
    return not start_jobs or bool(set(start_jobs) & set(path_jobs))


def classify(qid, g, src, chk, reach, errors_by_quest, expired):
    """-> (verdict, [reasons])."""
    if qid in expired:
        return "EXPIRED", ["event quest past its end date"]
    ph = qc.children(g.check.get(qid))
    start, end = qc.children(ph.get("0")), qc.children(ph.get("1"))
    blocked, client = [], []
    for side, node in (("start", ph.get("0")), ("end", ph.get("1"))):
        if qc.leaf(node, side + "script") is not None or qc.leaf(node, "script") is not None:
            client.append("%s script (scripts/quest/%d.js): NPC dialogue or scripted steps need a client" % (side, qid))
    for e in errors_by_quest.get(qid, ()):
        if e.code == "missing-script":
            # quest_check: the server falls back to medalQuest.js; whether that completes the quest needs a client
            client.append("quest_check missing-script: %s" % e.detail)
        else:
            blocked.append("quest_check %s: %s" % (e.code, e.detail))
    for side, npc in (("start", qc.ival(qc.leaf(ph.get("0"), "npc"))), ("end", qc.ival(qc.leaf(ph.get("1"), "npc")))):
        if npc:
            maps = set(g.npc_maps.get(npc, ())) | set(src.plife.get(("n", npc), ()))
            if maps and not (maps & reach):
                blocked.append("%s NPC %s is only on maps this job cannot reach (%s)" % (side, npc, sorted(maps)[:3]))
    mobs = [(qc.ival(qc.leaf(c, "id")), qc.ival(qc.leaf(c, "count"), 0)) for c in (end["mob"] if "mob" in end else ())]
    for mob, _n in mobs:
        if not mob:
            continue
        mm = set(g.mob_maps.get(mob, ())) | set(src.plife.get(("m", mob), ()))
        for alias in qc.QUEST_MOB_ALIASES.get(mob, ()):
            mm |= set(g.mob_maps.get(alias, ())) | set(src.plife.get(("m", alias), ()))
        if mm and not (mm & reach):
            blocked.append("quest mob %s spawns only on maps this job cannot reach" % mob)
    for item in [qc.ival(qc.leaf(c, "id")) for c in (end["item"] if "item" in end else ())]:
        if not item:
            continue
        droppers = src.mob_drops.get(item, set())
        other = (src.reactor_drops.get(item) or item in src.global_drops or src.shop_items.get(item)
                 or item in chk.reward_items or item in g.maker_outputs or item in src.script_literals)
        if droppers and not other:
            spawn_maps = set()
            for mob in droppers:
                spawn_maps |= set(g.mob_maps.get(mob, ())) | set(src.plife.get(("m", mob), ()))
            if spawn_maps and not (spawn_maps & reach):
                blocked.append("item %s drops only from mobs on maps this job cannot reach" % item)
    if blocked:
        return "BLOCKED", blocked
    if client:
        return "CLIENT-REQUIRED", client
    return "RUNNABLE", []


def build(wz, scripts, db_dir, job_java, max_level, today=None):
    g = qc.load_wz(wz)
    src = qc.Sources(db_dir, scripts)
    chk = qc.Checker(g, src, qc.load_jobs(job_java), today=today).run()
    errors = collections.defaultdict(list)
    for f in chk.findings:
        if f.severity == qc.ERROR:
            errors[f.quest].append(f)
    edges = build_graph(g, wz, scripts)
    out = {"max_level": max_level, "jobs": {}}
    for name, (start_map, path_jobs) in START_JOBS.items():
        reach = reachable(edges, start_map)
        rows = []
        for qid in sorted(g.check):
            ph = qc.children(g.check.get(qid))
            s0 = ph.get("0")
            lvmin = qc.ival(qc.leaf(s0, "lvmin"), 0) or 0
            if lvmin > max_level:
                continue
            js = [qc.ival(c.get("value")) for c in (qc.children(s0)["job"] if "job" in qc.children(s0) else ())]
            if not offered(js, path_jobs):
                continue
            verdict, reasons = classify(qid, g, src, chk, reach, errors, chk.expired)
            rows.append({"quest": qid, "name": g.quest_names.get(qid, "?"), "lvmin": lvmin, "verdict": verdict,
                         "reasons": reasons})
        counts = collections.Counter(r["verdict"] for r in rows)
        out["jobs"][name] = {"start_map": list(start_map), "reachable_maps": len(reach), "counts": dict(counts), "quests": rows}
    return out


def markdown(res):
    md = ["# Early quests per start job (max level %d)" % res["max_level"], ""]
    md.append("Verdicts are leads from offline data (see early_quests.py): RUNNABLE means no known blocker, not a pass.")
    for name, j in res["jobs"].items():
        md += ["", "## %s (start maps %s, %d maps reachable)" % (name, j["start_map"], j["reachable_maps"]), "",
               "Counts: " + ", ".join("%s %d" % kv for kv in sorted(j["counts"].items())), ""]
        for v in ("BLOCKED", "CLIENT-REQUIRED"):
            rows = [r for r in j["quests"] if r["verdict"] == v]
            if rows:
                md += ["### %s (%d)" % (v, len(rows)), ""]
                for r in rows:
                    md.append("- %d %s (lvmin %d): %s" % (r["quest"], r["name"], r["lvmin"], "; ".join(r["reasons"])[:300]))
                md.append("")
    return "\n".join(md) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wz", required=True)
    ap.add_argument("--scripts", default="scripts")
    ap.add_argument("--db-dir")
    ap.add_argument("--job-java", default="src/main/java/client/Job.java")
    ap.add_argument("--max-level", type=int, default=30)
    ap.add_argument("--today")
    ap.add_argument("--json")
    ap.add_argument("--md")
    a = ap.parse_args(argv)
    res = build(a.wz, a.scripts, a.db_dir, a.job_java, a.max_level, a.today)
    if a.json:
        json.dump(res, open(a.json, "w"), indent=1, ensure_ascii=False)
    text = markdown(res)
    if a.md:
        open(a.md, "w").write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
