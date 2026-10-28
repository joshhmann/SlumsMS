#!/usr/bin/env python3
"""Inventory of the mobs placed on the post-Big-Bang maps (ticket 81, owner 2026-10-28).

Reads Map.wz life entries for the four regions, each mob's Mob.wz info (following info/link), its String.wz name,
and a tab-separated export of drop_data (dropperid, itemid, min, max, questid, chance; e.g. a SELECT on TEST), and
writes docs/economy/bb-drops/mobs.csv. Deterministic: same inputs, same file.

    python3 tools/economy/bb_inventory.py --wz wz --drops drop_data.tsv --out docs/economy/bb-drops/mobs.csv
"""
import argparse
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET

# region key, label, map-id prefixes (first 3 digits)
REGIONS = [
    ("arcane", "Arcane River 450-459", range(450, 460)),
    ("grandis", "Grandis 400-419", range(400, 420)),
    ("future", "Temple of Time / Future / Twilight Perion 270-273", range(270, 274)),
    ("mirror", "Mirror World 321-328", range(321, 329)),
]


def region_of(mapid):
    p = mapid // 1000000
    for key, _, prefixes in REGIONS:
        if p in prefixes:
            return key
    return None


def is_card(itemid):
    return 2380000 <= itemid < 2390000


def map_life(path):
    """Mob ids placed on one map, with placement counts."""
    out = defaultdict(int)
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return out
    for node in root:
        if node.get("name") != "life":
            continue
        for entry in node:
            vals = {c.get("name"): c.get("value") for c in entry}
            if vals.get("type") == "m" and vals.get("id"):
                out[int(vals["id"])] += 1
    return out


def mob_info(wz, mobid, depth=0):
    path = wz / "Mob.wz" / f"{mobid:07d}.img.xml"
    if not path.exists():
        return None
    root = ET.parse(path).getroot()
    info = next((n for n in root if n.get("name") == "info"), None)
    if info is None:
        return None
    vals = {c.get("name"): c.get("value") for c in info if c.tag in ("int", "string", "short", "float")}
    if "link" in vals and depth < 3:
        linked = mob_info(wz, int(vals["link"]), depth + 1)
        if linked:
            merged = dict(linked)
            merged.update({k: v for k, v in vals.items() if k != "link"})
            merged["_link"] = vals["link"]
            return merged
    return vals


def mob_names(wz):
    names = {}
    root = ET.parse(wz / "String.wz" / "Mob.img.xml").getroot()
    for node in root:
        n = node.find("string[@name='name']")
        if n is not None and node.get("name", "").isdigit():
            names[int(node.get("name"))] = n.get("value")
    return names


def read_drops(path):
    rows = defaultdict(list)
    with open(path) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 6 or not parts[0].lstrip("-").isdigit():
                continue
            rows[int(parts[0])].append(tuple(int(x) for x in parts[1:6]))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wz", default="wz")
    ap.add_argument("--drops", required=True, help="TSV export of drop_data")
    ap.add_argument("--out", default="docs/economy/bb-drops/mobs.csv")
    a = ap.parse_args()
    wz = Path(a.wz)

    placements = defaultdict(lambda: defaultdict(int))  # mob -> region -> count
    maps = defaultdict(set)
    for folder in sorted((wz / "Map.wz" / "Map").glob("Map*")):
        for f in sorted(folder.glob("*.img.xml")):
            stem = f.name.split(".")[0]
            if not stem.isdigit():
                continue
            mapid = int(stem)
            reg = region_of(mapid)
            if reg is None:
                continue
            for mob, n in map_life(f).items():
                placements[mob][reg] += n
                maps[mob].add(mapid)

    names = mob_names(wz)
    drops = read_drops(a.drops)
    order = {k: i for i, (k, _, _) in enumerate(REGIONS)}
    out_rows = []
    for mob in sorted(placements):
        regs = placements[mob]
        # main region: most placements, ties by REGIONS order
        main = sorted(regs, key=lambda r: (-regs[r], order[r]))[0]
        info = mob_info(wz, mob) or {}
        rows = drops.get(mob, [])
        cards = [r for r in rows if is_card(r[0])]
        mesos = [r for r in rows if r[0] == 0]
        status = "none" if not rows else ("card-only" if len(cards) == len(rows) else "has-table")
        out_rows.append({
            "mobid": mob,
            "name": names.get(mob, ""),
            "region": main,
            "regions": "|".join(sorted(regs, key=order.get)),
            "placements": sum(regs.values()),
            "maps": len(maps[mob]),
            "first_map": min(maps[mob]),
            "level": info.get("level", ""),
            "maxHP": info.get("maxHP", ""),
            "exp": info.get("exp", ""),
            "boss": 1 if info.get("boss") == "1" else 0,
            "link": info.get("_link", ""),
            "in_mob_wz": 1 if info else 0,
            "drop_rows": len(rows),
            "card_rows": len(cards),
            "card_itemid": cards[0][0] if cards else "",
            "meso_rows": len(mesos),
            "status": status,
        })

    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out_rows[0].keys()), lineterminator="\n")
        w.writeheader()
        w.writerows(out_rows)

    summary = defaultdict(lambda: defaultdict(int))
    for r in out_rows:
        summary[r["region"]][r["status"]] += 1
    for key, label, _ in REGIONS:
        s = summary[key]
        print(f"{label}: {sum(s.values())} mobs, " + ", ".join(f"{k} {v}" for k, v in sorted(s.items())), file=sys.stderr)


if __name__ == "__main__":
    main()
