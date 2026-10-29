#!/usr/bin/env python3
"""The 120+ training ladder (ticket 81, docs/economy/bb-drops/REVIEW.md): EXP an hour per area for one model
character, read from Map.wz placements, Mob.wz and the stat table the server ships.

Model (the same as gen_bb_drops.py): the character deals 141M damage an hour (1,000 Chief Oblivion Guardians), a map
feeds at most 1,500 kills an hour, world EXP 3x. A map's EXP an hour is the mean over its mob placements of
kills_per_hour(hp) x exp x 3; an area's figure is the median over its training maps (maps whose strongest non-boss
mob is level TRAINING_MIN or more, so event and filler maps do not count).

    python3 tools/economy/ladder_table.py --wz /root/work/bake/wz [--stats <mob-stat-overrides.tsv>]
"""
import argparse
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_bb_drops as g  # noqa: E402

TRAINING_MIN = 90
LOWER = Path("src/main/resources/soloMapling/lower-grandis-maps.txt")


def lower_grandis(path=LOWER):
    out = set()
    for line in path.read_text().splitlines():
        i = line.split("#", 1)[0].strip()
        if i:
            out.add(int(i))
    return out


def areas(lower):
    def rng(lo, hi):
        return lambda m: lo <= m <= hi
    return [
        ("Ulu City (Singapore)", rng(541020000, 541029999)),
        ("Leafre / Minar Forest", rng(240010000, 240049999)),
        ("Temple of Time, Road of Regrets", rng(270020000, 270029999)),
        ("Temple of Time, Road to Oblivion", rng(270030000, 270039999)),
        ("Lion King's Castle", rng(211060000, 211061999)),
        ("Rose Garden", rng(211080000, 211080999)),
        ("Lower Grandis", lambda m: m in lower),
        ("Future / Twilight Perion", lambda m: 271000000 <= m <= 273999999),
        ("Upper Grandis", lambda m: 400000000 <= m < 420000000 and m not in lower
            and not (400050000 <= m < 400060000 or 401070000 <= m < 401080000)),
        ("Arcane River", rng(450000000, 459999999)),
    ]


def read_stats(path):
    out = {}
    for line in Path(path).read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        mob, hp, exp = line.split("\t")[:3]
        out[int(mob)] = (None if hp == "-" else int(hp), None if exp == "-" else int(exp))
    return out


def server_stats(wz, mob, stats, cache):
    if mob not in cache:
        info = g.full_mob_info(wz, mob)
        if not info:
            cache[mob] = None
        else:
            hp = g.server_hp({"mobid": mob, "level": info["level"], "maxHP": info["maxHP"], "boss": info["boss"]})
            exp = info["exp"]
            o_hp, o_exp = stats.get(mob, (None, None))
            cache[mob] = (info["level"], o_hp or hp, exp if o_exp is None else o_exp, info["boss"])
    return cache[mob]


def map_placements(wz, folders):
    for d in folders:
        for f in sorted((wz / "Map.wz" / "Map" / d).glob("*.img.xml")):
            s = f.name.split(".")[0]
            if not s.isdigit():
                continue
            mobs = []
            for node in ET.parse(f).getroot():
                if node.get("name") == "life":
                    for e in node:
                        v = {c.get("name"): c.get("value") for c in e}
                        if v.get("type") == "m":
                            mobs.append(int(v["id"]))
            if mobs:
                yield int(s), mobs


def ladder(wz, stats_path, lower_path=LOWER):
    stats = read_stats(stats_path)
    lower = lower_grandis(lower_path)
    defs = areas(lower)
    per_area = {name: [] for name, _ in defs}
    levels = {name: [] for name, _ in defs}
    cache = {}
    for mapid, mobs in map_placements(wz, ("Map2", "Map4", "Map5")):
        area = next((name for name, test in defs if test(mapid)), None)
        if area is None:
            continue
        vals, lv = [], []
        for mob in mobs:
            s = server_stats(wz, mob, stats, cache)
            if not s or s[3]:          # bosses are not training
                continue
            level, hp, exp, _ = s
            if hp <= 0:
                continue
            vals.append(g.kills_per_hour(hp) * exp * 3)
            lv.append(level)
        if vals and max(lv) >= TRAINING_MIN:
            per_area[area].append(sum(vals) / len(vals))
            levels[area].append(max(lv))   # the map's strongest mob
    return [(name, len(per_area[name]), statistics.median(per_area[name]) if per_area[name] else 0,
             (min(levels[name]), max(levels[name])) if levels[name] else None) for name, _ in defs]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wz", default="wz")
    ap.add_argument("--stats", default=str(g.STAT_TSV))
    ap.add_argument("--lower", default=str(LOWER))
    a = ap.parse_args()
    rows = ladder(Path(a.wz), a.stats, Path(a.lower))
    tot = max(v for n, _, v, _ in rows if n.startswith("Temple of Time"))
    print("| Area | Strongest mob per map | Maps | EXP/hour | x buffed Temple of Time (Road to Oblivion) |")
    print("|---|---|---|---|---|")
    for name, n, v, lv in rows:
        lvs = f"{lv[0]}-{lv[1]}" if lv else "-"
        print(f"| {name} | {lvs} | {n} | {v / 1e6:.1f}M | {v / tot:.2f}x |")


if __name__ == "__main__":
    main()
