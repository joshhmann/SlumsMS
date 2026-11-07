"""Build a whole data/atlas/<release>/ folder (docs/atlas/SCHEMA.md): quests, npcs, items, mobs, maps, scripts and meta.json.
pq.json and bosses.json are written empty until their builder lands (the validator needs the files to exist).

Item reach is settled here, after every kind exists: the entity builder only knows drops, shops, quest tables and reactor
drops, so an item given by a script (gainItem), a quest reward or the cash shop catalogue is upgraded to "player".
"""
import argparse
import datetime
import hashlib
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import entities_atlas  # noqa: E402
import quests_atlas  # noqa: E402
import scripts_index  # noqa: E402

SCHEMA_VERSION = "1.0.0"
UPGRADE_NOTES = ("no player-reachable drop, shop, quest reward or reactor",
                 "its only drops/shops are themselves unobtainable")


def cash_ids(catalog_path):
    """Item ids in cashshop/catalog.tsv (first column of every non-comment row)."""
    ids = set()
    if not os.path.isfile(catalog_path):
        return ids
    with open(catalog_path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            head = line.split("\t", 1)[0].strip()
            if head.isdigit():
                ids.add(int(head))
    return ids


def item_sources(scripts, quests, cash):
    """{item id: sorted list of short reasons} from script gainItem-style mentions, quest rewards and the cash shop."""
    out = {}
    for sid, row in scripts.items():
        for m in row.get("mentions") or []:
            if m.get("k") == "item":
                out.setdefault(m["id"], set()).add(f"script {sid}")
    for qid, row in quests.items():
        if row.get("reach") == "unobtainable":
            continue
        for e in (row.get("rewards") or {}).get("items") or []:
            r = e.get("ref") or {}
            if r.get("k") == "item" and (e.get("count") or 0) > 0:
                out.setdefault(r["id"], set()).add(f"quest {qid} reward")
    for iid in cash:
        out.setdefault(iid, set()).add("cash shop catalogue")
    return {k: sorted(v) for k, v in out.items()}


def fix_item_reach(items, sources):
    """Upgrade items the entity builder called unobtainable for lack of a source it cannot see. Names it rejected
    (brand or empty text) stay as they were. Also lists the scripts that name the item in `scripts`. -> upgraded ids."""
    upgraded = []
    for key, row in items.items():
        why = sources.get(row["id"])
        if not why:
            continue
        for reason in why:
            if reason.startswith("script "):
                r = {"k": "script", "id": reason[len("script "):]}
                if r not in row["scripts"]:
                    row["scripts"].append(r)
        row["scripts"].sort(key=lambda r: r["id"])
        if row["reach"] == "unobtainable" and row.get("reach_note") in UPGRADE_NOTES:
            row["reach"] = "player"
            row["reach_note"] = "also obtainable via: " + "; ".join(why[:5])
            upgraded.append(row["id"])
    return upgraded


FILES = {"item": "items", "mob": "mobs", "map": "maps", "npc": "npcs", "quest": "quests", "script": "scripts"}


def name_scripts(scripts, built):
    """scripts rows need name and reach like every row: name is the id; reach is the reach of the entity the file is
    named for (npc/<id>, quest/<id>), else player."""
    for sid, row in scripts.items():
        row["name"] = sid
        kind, _, ident = sid.partition("/")
        target = built.get({"npc": "npcs", "quest": "quests"}.get(kind, ""), {}).get(ident)
        row["reach"] = target["reach"] if target else "player"


def _refs_in(node):
    if isinstance(node, dict):
        if set(node) >= {"k", "id"} and node["k"] in FILES:
            yield node["k"], node["id"]
        for v in node.values():
            yield from _refs_in(v)
    elif isinstance(node, list):
        for v in node:
            yield from _refs_in(v)


def dangling(built):
    """{ref kind: sorted ids} of refs that point at no row. Written to dangling.json so they stay visible as warnings."""
    out = {}
    for kind in ("quests", "npcs", "items", "mobs", "maps", "scripts"):
        for row in built[kind].values():
            for k, ident in _refs_in(row):
                if str(ident) not in built[FILES[k]]:
                    out.setdefault(k, set()).add(ident)
    return {k: sorted(v, key=str) for k, v in sorted(out.items())}


def wz_sha(wz_root):
    """sha256 over the sorted 'relative path + file sha256' lines of every file under the WZ copy."""
    lines = []
    for dirpath, dirnames, filenames in os.walk(wz_root):
        dirnames.sort()
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            h = hashlib.sha256()
            with open(full, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            lines.append(f"{os.path.relpath(full, wz_root).replace(os.sep, '/')} {h.hexdigest()}")
    return hashlib.sha256("\n".join(sorted(lines)).encode()).hexdigest()


def manifest(folder):
    """sha256sum-style lines for every .json in the folder except the manifest itself, sorted by name."""
    lines = []
    for name in sorted(os.listdir(folder)):
        if name.endswith(".json"):
            h = hashlib.sha256()
            with open(os.path.join(folder, name), "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            lines.append(f"{h.hexdigest()}  {name}\n")
    return "".join(lines)


def git_head(repo):
    try:
        return subprocess.check_output(["git", "-C", repo, "rev-parse", "HEAD"], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def db_snapshot(db_root, date):
    tables = sorted(f[:-4] for f in os.listdir(db_root) if f.endswith(".tsv")) if db_root else []
    return {"name": "TEST mapleroot_persist SELECT-only export", "date": date, "tables": tables}


def build(hs_dir, wz_root, db_root, server_repo):
    """-> {kind: rows} for all nine kinds."""
    scripts = scripts_index.index_scripts(server_repo)
    ents = entities_atlas.build_all(entities_atlas.load_hs(hs_dir), wz_root, db_root, server_repo)
    quests = quests_atlas.build(wz_root, script_ids=frozenset(scripts), npc_ids={int(k) for k in ents["npcs"]})
    cash = cash_ids(os.path.join(server_repo, "cashshop", "catalog.tsv"))
    sources = item_sources(scripts, quests, cash)
    before = sum(1 for r in ents["items"].values() if r["reach"] == "unobtainable")
    upgraded = fix_item_reach(ents["items"], sources)
    out = {"quests": quests, "npcs": ents["npcs"], "items": ents["items"], "mobs": ents["mobs"],
           "maps": ents["maps"], "scripts": scripts, "pq": {}, "bosses": {}}
    name_scripts(scripts, out)
    out["_dangling"] = dangling(out)
    out["_report"] = {"items_unobtainable_before": before, "items_upgraded": len(upgraded),
                      "items_unobtainable_after": before - len(upgraded), "upgraded_ids": upgraded}
    return out


def write(out_dir, built, release, server_repo, wz_root, db_root, db_date, built_at):
    os.makedirs(out_dir, exist_ok=True)
    counts = {}
    for kind in ("quests", "npcs", "items", "mobs", "maps", "scripts", "pq", "bosses"):
        counts[kind] = len(built[kind])
        with open(os.path.join(out_dir, kind + ".json"), "w", encoding="utf-8") as f:
            json.dump(built[kind], f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write("\n")
    with open(os.path.join(out_dir, "dangling.json"), "w", encoding="utf-8") as f:
        json.dump(built["_dangling"], f, indent=1, sort_keys=True)
        f.write("\n")
    meta = {"schema_version": SCHEMA_VERSION, "release": release, "server_commit": git_head(server_repo),
            "wz_sha": wz_sha(wz_root), "db_snapshot": db_snapshot(db_root, db_date), "built_at": built_at,
            "counts": counts}
    with open(os.path.join(out_dir, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")
    with open(os.path.join(out_dir, "MANIFEST.sha256"), "w", encoding="utf-8") as f:
        f.write(manifest(out_dir))
    return meta


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--hs", required=True, help="Hidden Street extractor output folder")
    ap.add_argument("--wz", required=True, help="WZ XML root")
    ap.add_argument("--db", help="folder of TSV exports")
    ap.add_argument("--db-date", default="unknown", help="date of the DB export, YYYY-MM-DD")
    ap.add_argument("--server-repo", required=True, help="checkout of the release whose scripts/ and cashshop/ are indexed")
    ap.add_argument("--release", required=True, help="release folder name, e.g. 1.6.5")
    ap.add_argument("--out", required=True, help="output folder, e.g. data/atlas/1.6.5")
    ap.add_argument("--built-at", help="override the timestamp (for reproducible output)")
    a = ap.parse_args(argv)
    built = build(a.hs, a.wz, a.db, a.server_repo)
    report = built.pop("_report")
    report["dangling"] = {k: len(v) for k, v in built["_dangling"].items()}
    built_at = a.built_at or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    meta = write(a.out, built, a.release, a.server_repo, a.wz, a.db, a.db_date, built_at)
    print(json.dumps({"counts": meta["counts"], "item_reach": {k: v for k, v in report.items() if k != "upgraded_ids"}}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
