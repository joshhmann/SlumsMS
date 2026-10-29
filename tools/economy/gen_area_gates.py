#!/usr/bin/env python3
"""Lower Grandis: the Grandis maps a level-120 character may enter without rebirths (ticket 81 round 3, owner
2026-10-28). Deterministic, from Map.wz alone:

  - start from the lower-band towns (SEEDS);
  - walk portals (info `tm`) inside maps 400000000-419999999, skipping the battleground instance maps;
  - a map with mobs is lower when its strongest placed mob is level LOWER_MAX or less (Grandis has no mobs between
    lv 143 and lv 174, so the split is clean); a map with no mobs (town, interior, door) is lower unless it sits
    next to an upper mob map and next to no lower mob map or seed, so the upper towns (Heliseum HQ, Fox Tree Top,
    the Glooger Habitat) stay closed;
  - the deeper parts the player notes name stay closed even where the walk reaches them (1.6.1 b039): any map whose
    name says Heliseum, the Fox Tree or Cernium, and the rougher back streets of Savage Terminal (the hidden Back
    Alley, Desolate Black Market and Desolate Scrapyard, DEEPER_IDS). The walk does not go through them.

The boundary portals it prints must all be unscripted (GenericPortal checks AreaGates); a scripted one is reported
as UNCHECKED so the review can add the check to its script.

Writes src/main/resources/soloMapling/lower-grandis-maps.txt (one map id per line, with its name and mob levels as a
comment) and prints the boundary portals (lower -> not lower) so the review can list them.

    python3 tools/economy/gen_area_gates.py [--wz wz]
"""
import argparse
import re
import xml.etree.ElementTree as ET
from collections import deque
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gen_bb_drops import info_text  # noqa: E402  (the info node without its skill/attack blocks)

SEEDS = [400000000, 402000000, 402000500, 402000600, 410000000, 410000200, 410000300, 410000416]
LOWER_MAX = 142
# player notes (1.6.1, "Training after 120"): "The deeper parts of Grandis (Heliseum, the Fox Tree, Cernium and the
# rougher back streets of Savage Terminal) and Arcane River still need 3 rebirths." Owner 2026-10-29: the maps this list
# leaves out (deeper Grandis) open at the 1st rebirth and Arcane River at the 2nd (server.maps.AreaGates); the split is
# unchanged.
DEEPER_NAMES = ("heliseum", "fox tree", "cernium")
DEEPER_IDS = {402000400, 402000401, 402000402}   # Back Alley, Desolate Black Market, Desolate Scrapyard
OUT = Path("src/main/resources/soloMapling/lower-grandis-maps.txt")


def in_grandis(m):
    return 400000000 <= m < 420000000 and not (400050000 <= m < 400060000 or 401070000 <= m < 401080000)


def mob_level(wz, mobid, cache, depth=0):
    if mobid in cache:
        return cache[mobid]
    f = wz / "Mob.wz" / f"{mobid:07d}.img.xml"
    lv = None
    if f.exists():
        t = info_text(f.read_text(errors="ignore"))
        link = re.search(r'name="link" value="(\d+)"', t)
        if link and depth < 3:
            lv = mob_level(wz, int(link.group(1)), cache, depth + 1)
        if lv is None:
            m = re.search(r'name="level" value="(\d+)"', t)
            lv = int(m.group(1)) if m else None
    cache[mobid] = lv
    return lv


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wz", default="wz")
    a = ap.parse_args()
    wz = Path(a.wz)
    names = {}
    for el in ET.parse(wz / "String.wz" / "Map.img.xml").getroot().iter("imgdir"):
        n = el.get("name", "")
        if n.isdigit():
            mn, sn = el.find("string[@name='mapName']"), el.find("string[@name='streetName']")
            if mn is not None:
                names[int(n)] = ((sn.get("value") + " : ") if sn is not None else "") + mn.get("value")
    cache = {}
    maps = {}   # id -> (max mob level or None, [(portal name, target, script)])
    for d in ("Map4",):
        for f in sorted((wz / "Map.wz" / "Map" / d).glob("*.img.xml")):
            s = f.name.split(".")[0]
            if not s.isdigit() or not in_grandis(int(s)):
                continue
            root = ET.parse(f).getroot()
            levels, portals = [], []
            for node in root:
                if node.get("name") == "life":
                    for e in node:
                        v = {c.get("name"): c.get("value") for c in e}
                        if v.get("type") == "m":
                            lv = mob_level(wz, int(v["id"]), cache)
                            if lv:
                                levels.append(lv)
                elif node.get("name") == "portal":
                    for e in node:
                        v = {c.get("name"): c.get("value") for c in e}
                        tm = int(v.get("tm") or 999999999)
                        if tm != 999999999:
                            script = (v.get("script") or "").strip()
                            # PortalFactory's fallback: scripts/portal/<map>.<portal index>.js
                            if not script and Path(f"scripts/portal/{s}.{e.get('name')}.js").exists():
                                script = f"{s}.{e.get('name')}"
                            portals.append((v.get("pn", ""), tm, script))
            maps[int(s)] = (max(levels) if levels else None, portals)

    def deeper(m):
        return m in DEEPER_IDS or any(w in names.get(m, "").lower() for w in DEEPER_NAMES)

    def mob_lower(m):
        return m in maps and maps[m][0] is not None and maps[m][0] <= LOWER_MAX

    lower, queue = set(), deque(s for s in SEEDS if s in maps)
    lower.update(queue)
    while queue:
        m = queue.popleft()
        for _, tm, _ in maps[m][1]:
            if tm in lower or tm not in maps or deeper(tm):
                continue
            top = maps[tm][0]
            if top is not None:
                ok = top <= LOWER_MAX
            else:
                neighbours = {t for _, t, _ in maps[tm][1]} | {x for x in maps if any(t == tm for _, t, _ in maps[x][1])}
                upper_next = any(n in maps and maps[n][0] is not None and maps[n][0] > LOWER_MAX for n in neighbours)
                ok = not upper_next or any(mob_lower(n) or n in SEEDS for n in neighbours)
            if ok:
                lower.add(tm)
                queue.append(tm)

    lines = ["# Lower Grandis (ticket 81 round 3, owner 2026-10-28): open at level 120 without rebirths. One map id per",
             "# line. Generated by tools/economy/gen_area_gates.py from Map.wz; read by server.maps.AreaGates."]
    for m in sorted(lower):
        top = maps[m][0]
        lines.append(f"{m}\t# {names.get(m, '?')}" + (f" (mobs up to lv {top})" if top else ""))
    OUT.write_text("\n".join(lines) + "\n")
    print(f"{len(lower)} lower Grandis maps")
    for m in sorted(lower):
        for pn, tm, script in maps[m][1]:
            if tm in maps and tm not in lower:
                print(f"boundary {m} -> {tm} portal {pn} script={script or '-'}: {names.get(tm, '?')} (lv {maps[tm][0]})"
                      + (" UNCHECKED: scripted portal" if script else ""))


if __name__ == "__main__":
    main()
