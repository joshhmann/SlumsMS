#!/usr/bin/env python3
"""Drop tables for the post-Big-Bang mobs (ticket 81, owner 2026-10-28). Draft for the owner's review.

Two steps, both deterministic (same inputs, same output; no randomness outside a seed made from the mob id):

  pool   reads a TSV export of drop_data (dropperid, itemid, min, max, questid, chance) plus Mob.wz/Item.wz and
         writes docs/economy/bb-drops/classic-pool.csv: the items CLASSIC non-boss mobs already drop (the
         "obtainable tier"), with the rate classic mobs drop them at.
  gen    reads docs/economy/bb-drops/mobs.csv (tools/economy/bb_inventory.py) + classic-pool.csv + WZ and writes
         docs/economy/bb-drops/rows.csv, database/migrations/050-bb-drop-tables.sql and its rollback.

    python3 tools/economy/gen_bb_drops.py pool --drops drop_data.tsv
    python3 tools/economy/gen_bb_drops.py gen

The rules are written out in docs/economy/bb-drops/RULES.md; keep the two in step.
"""
import argparse
import csv
import hashlib
import math
import random
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from wzitems import WzItems  # noqa: E402

DOCS = Path("docs/economy/bb-drops")
MIGRATION_ID = "050-bb-drop-tables"
LEDGER = "bb_drops_050_rows"

# ---- rules (RULES.md) ------------------------------------------------------------------------------------------
MESO_CHANCE = 400000          # every classic normal mob's meso row
BOSS_MESO_CHANCE = 700000     # Zakum's
MESO_K = 0.085                # classic median meso midpoint ~= 0.085 * level^2 up to lv 94 (TEST drop_data fit) ...
MESO_PER_LEVEL = 8            # ... and ~= 8 x level from lv 95 (Temple of Time, Leafre, Wyverns: 750-1070 at lv 91-131)
MESO_KNEE = 94
MESO_SPREAD = 0.2             # min/max = mid -/+ 20%, as classic rows
EXP_FACTOR = (0.8, 1.25)      # within one region and level, mesos follow sqrt(exp / median exp), clamped
ELITE_MESO_X = 4              # mini-bosses (boss flag, HP < 1e9): classic mini-bosses pay ~4-5x a normal mob
BOSS_MESO_PER_LEVEL = 90      # big bosses: Zakum lv 140 pays 10k-15k (~90 x level)
BOSS_HP = 1_000_000_000
LOW_LEVEL = 30                # below this: mesos and one potion only

POTIONS_HIGH = [2000005, 2000006, 2022003, 2020013, 2020015, 2000004]   # what classic lv90+ mobs drop, 20000 each
POTIONS_LOW = [2000002, 2000003, 2002001]
POTION_CHANCE = 20000
BOSS_POTIONS = [(2000004, 1, 1, 999999), (2000005, 1, 1, 999999), (2020013, 1, 1, 999999),
                (2020015, 1, 1, 999999), (2000006, 1, 4, 40000)]           # Zakum's potion rows

ORE_GROUPS = {   # group: (item ids, classic chance)
    "crystal": (list(range(4004000, 4004005)), 10000),
    "mineral": (list(range(4010000, 4010008)) + list(range(4020000, 4020009)), 9000),
    "rock": ([4006000, 4006001] + list(range(4007000, 4007008)), 10000),
    "stimulator": (list(range(4130000, 4130023)), 6000),
}
NORMAL_PICKS = {"potion": 3, "crystal": 1, "mineral": 2, "rock": 1, "stimulator": 1, "scroll": 4, "equip": 5,
                "book": 2, "bbgear": 2, "bbrare": 1}
ELITE_PICKS = {"potion": 3, "crystal": 2, "mineral": 3, "rock": 2, "stimulator": 2, "scroll": 6, "equip": 6,
               "book": 3, "bbgear": 3, "bbrare": 1}
ELITE_X = 2                   # elite item chances = classic x2
BOSS_PICKS = {"scroll": 6, "equip": 8, "book": 4, "bbgear": 3, "bbrare": 1}
BOSS_SCROLL_CHANCE = 13500    # Zakum's scroll rate
BOSS_EQUIP_CHANCE = 22500     # Zakum's lowest weapon rate (conservative end of its 22.5k-40k)
BOSS_ROCKS = [(4006000, 1, 1, 7000), (4006001, 1, 1, 7000)]

SCROLL_CHANCE = {10: 300, 60: 300, 100: 300, 30: 750, 70: 750}   # classic: normal 300, dark 750
EQUIP_BAND = 20               # equips with reqLevel in [top - 20, top], top = min(mob level, pool max)
EQUIP_CHANCE_CLAMP = (500, 1287)

REGIONS = ["arcane", "grandis", "future", "mirror", "elnath"]
# owner 2026-10-28: Mirror World stays out until it has a way in; round 4 adds Lion King's Castle and Rose Garden
# ("elnath"), classic lv 117-134 spots whose mobs had huge HP and no meso drops
DEFAULT_REGIONS = "arcane,grandis,future,elnath"
CLASSIC_REGIONS = {"elnath"}   # classic areas: drop tables from the classic pool only (no post-Big-Bang gear)

# mastery books (owner 2026-10-28: yes, at classic rates)
BOOK_RANGE = (2290000, 2291000)
BOOK_CHANCE_CLAMP = (500, 1500)
BOSS_BOOK_CHANCE = 20000      # Zakum's book rate

# the mob's own ETC items (owner 2026-10-28: yes): name starts with the mob's name, or the item's description names it
OWN_ETC_CHANCES = [400000, 200000]   # classic own-ETC rows are 400000-600000; a second one at half
REGION_ETC = {"arcane": [(4001878, 10000)]}   # Arcane River Droplet Stone: the one region material Item.wz has
# coordinator 2026-10-28: an ETC item goes in only when it has a use here (its id appears in scripts/: a quest, an NPC
# exchange, a craft) or plain NPC sell value, and never when it belongs to a post-Big-Bang system v83 lacks
POST_BB_SYSTEM = re.compile(r"spell trace|symbol|star ?force|flame|nodestone|node stone|v ?matrix|\bcore\b|"
                            r"mileage|coin|sol erda|erda fragment|cube|potential|soul (shard|piece)", re.I)
SCRIPTS = Path("scripts")

# post-Big-Bang gear (owner 2026-10-28: yes, level-appropriate, outclassing gear rare)
V83_WEAPON_TYPES = {130, 131, 132, 133, 137, 138, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149}
BB_TOP, BB_BAND = 160, 30     # gear with reqLevel in [min(level, 160) - 30, min(level, 160)]
BB_PAR_CHANCE = {"Weapon": 700}, 800          # classic equip rates (weapon / anything else)
BB_RARE_CHANCE = [(150, 15), (0, 60)]         # outclassing gear: reqLevel >= 150 at 15, else 60
BB_BOSS_CHANCE, BB_BOSS_RARE_CHANCE = 22500, [(150, 1000), (0, 3000)]
OUTCLASS_ATT_X, OUTCLASS_STAT_PLUS = 1.10, 5  # outclasses classic: ATT > best x1.1 or stats > best + 5 (same slot)

# HP (owner 2026-10-28: bring these mobs onto a classic-like HP curve, keep EXP)
HP_K, HP_B = 0.0064595, 3.4309          # classic non-boss max HP ~= HP_K * level ^ HP_B (TEST/WZ fit, lv 30-131)
HP_SPREAD = (0.75, 1.5)                 # within a region and level: x sqrt(server HP / median), clamped
HP_OFF_CURVE = 1.25                     # a mob is changed only when its HP is over 1.25x its new value
ELITE_HP_X = 30                         # mini-bosses: 30x the curve (Manon is ~55x)
BOSS_HP_ANCHORS = ((140, 110_000_000), (180, 2_100_000_000))   # Zakum's body, Pink Bean
BOSS_HP_CAP = 2_100_000_000
# LifeFactory.getMonsterStats: the HP the server really uses before any override (keep in step with it)
LIFEFACTORY_HP = {8840000: 7_500_000_000, 8850011: 15_000_000_000, 8850012: 15_000_000_000,
                  9001007: 100_000_000_000_000_000, 8880000: 6_000_000_000, 8880002: 200_000_000_000,
                  8880010: 20_000_000_000, 8240098: 10_000_000_000, 8240099: 15_000_000_000,
                  8645009: 130_000_000_000, 8880344: 25_000_000_000, 8880301: 25_000_000_000,
                  8880150: 100_000_000_000, 8880302: 95_000_000_000, 8880415: 175_000_000_000,
                  9990301: 65_000_000_000, 9995009: 75_000_000_000, 9990415: 95_000_000_000,
                  9990150: 50_000_000_000}


_TAG = re.compile(r'<imgdir\b[^>]*?(/?)>|</imgdir>')


def info_text(t):
    """The Mob.wz info node's own fields, without its nested attack/skill/... blocks, so a skill's `level` is never
    read as the mob's (Corrupted Skelegon's skill is level 4, the mob 124)."""
    start = t.find('<imgdir name="info">')
    if start < 0:
        return t
    pos, depth, out = start + len('<imgdir name="info">'), 0, []
    for m in _TAG.finditer(t, pos):
        if depth == 0:
            out.append(t[pos:m.start()])
        if m.group(0) == "</imgdir>":
            if depth == 0:
                break
            depth -= 1
        elif not m.group(1):
            depth += 1
        pos = m.end()
    return "".join(out)


def seeded(*parts):
    h = hashlib.sha256("|".join(str(p) for p in parts).encode()).hexdigest()
    return random.Random(int(h[:16], 16))


def is_card(itemid):
    return 2380000 <= itemid < 2390000


# ---- pool --------------------------------------------------------------------------------------------------------
def mob_info(wz, mobid, depth=0):
    f = wz / "Mob.wz" / f"{mobid:07d}.img.xml"
    if not f.exists():
        return None
    t = info_text(f.read_text(errors="ignore"))

    def g(k):
        m = re.search(r'name="%s" value="([^"]*)"' % k, t)
        return m.group(1) if m else None
    if g("link") and depth < 3:
        linked = mob_info(wz, int(g("link")), depth + 1)
        if linked:
            return linked
    return {"level": int(g("level") or 0), "boss": g("boss") == "1"}


def build_pool(a):
    wz = Path(a.wz)
    items = WzItems(wz)
    targets = {int(r["mobid"]) for r in csv.DictReader(open(a.mobs)) if r["status"] != "has-table"}
    seen = defaultdict(list)   # itemid -> [(chance, dropper level)]
    for line in open(a.drops):
        p = line.rstrip("\n").split("\t")
        if len(p) < 6 or not p[0].isdigit():
            continue
        mob, item, _mn, _mx, quest, chance = map(int, p[:6])
        if not (100000 <= mob < 8800000) or mob in targets or quest or item == 0:
            continue
        if not (1000000 <= item < 2000000 or 2040000 <= item < 2049000 or BOOK_RANGE[0] <= item < BOOK_RANGE[1]
                or item // 1000 in (4004, 4010, 4020, 4006, 4007, 4130)):
            continue
        mi = mob_info(wz, mob)
        if not mi or mi["boss"]:
            continue
        seen[item].append((chance, mi["level"]))

    rows = []
    for item in sorted(seen):
        why = items.droppable(item)
        if why:
            continue
        i = items.info(item)
        chances = [c for c, _ in seen[item]]
        levels = [lv for _, lv in seen[item]]
        if item < 2000000:
            cat = "equip"
            if not i["reqLevel"]:
                continue
            chance = min(max(int(statistics.median(chances)), EQUIP_CHANCE_CLAMP[0]), EQUIP_CHANCE_CLAMP[1])
        elif BOOK_RANGE[0] <= item < BOOK_RANGE[1]:
            cat = "book"
            chance = min(max(int(statistics.median(chances)), BOOK_CHANCE_CLAMP[0]), BOOK_CHANCE_CLAMP[1])
        elif item < 3000000:
            cat = "scroll"
            if i["success"] not in SCROLL_CHANCE:
                continue
            chance = SCROLL_CHANCE[i["success"]]
        else:
            cat = next(g for g, (ids, _) in ORE_GROUPS.items() if item in ids) if any(
                item in ids for ids, _ in ORE_GROUPS.values()) else None
            if cat is None:
                continue
            chance = ORE_GROUPS[cat][1]
        rows.append({"category": cat, "itemid": item, "name": i["name"], "slot": i["slot"] or "",
                     "reqLevel": i["reqLevel"] or "", "success": i["success"] or "", "chance": chance,
                     "classic_droppers": len(chances), "dropper_lv_min": min(levels), "dropper_lv_max": max(levels)})
    rows += bb_gear(items, a.drops)
    fields = ["category", "itemid", "name", "slot", "reqLevel", "success", "chance", "classic_droppers",
              "dropper_lv_min", "dropper_lv_max", "att", "stats", "classic_best_att", "classic_best_stats", "outclass"]
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n", restval="")
        w.writeheader()
        w.writerows(rows)
    counts = defaultdict(int)
    for r in rows:
        counts[r["category"]] += 1
    print("pool:", dict(counts), file=sys.stderr)


def equip_stats(items, itemid):
    d, f = items._equip_index()[itemid]
    import xml.etree.ElementTree as ET
    info = ET.parse(f).getroot().find("imgdir[@name='info']")
    v = {c.get("name"): c.get("value") for c in info if c.tag not in ("imgdir", "canvas")}

    def g(k):
        x = v.get(k) or "0"
        return int(x) if x.lstrip("-").isdigit() else 0
    return {"slot": d, "wt": itemid // 10000 if d == "Weapon" else None, "reqLevel": g("reqLevel"),
            "att": max(g("incPAD"), g("incMAD")), "stats": g("incSTR") + g("incDEX") + g("incINT") + g("incLUK"),
            "set": v.get("setItemID")}


def bb_gear(items, drops_path):
    """Post-Big-Bang set gear (setItemID, reqLevel 110+) that no mob drops today, v83 weapon types only, compared
    with the best gear any mob (bosses included) drops for the same slot or weapon type."""
    dropped = set()
    for line in open(drops_path):
        p = line.split("\t")
        if len(p) > 1 and p[1].isdigit():
            dropped.add(int(p[1]))
    best = defaultdict(lambda: [0, 0])
    for it in dropped:
        if 1000000 <= it < 2000000 and it in items._equip_index() and not items.droppable(it):
            s = equip_stats(items, it)
            k = (s["slot"], s["wt"])
            best[k][0] = max(best[k][0], s["att"])
            best[k][1] = max(best[k][1], s["stats"])
    rows = []
    for it in sorted(items._equip_index()):
        if it in dropped or items.droppable(it):
            continue
        s = equip_stats(items, it)
        if not s["set"] or s["reqLevel"] < 110:
            continue
        if s["slot"] == "Weapon" and s["wt"] not in V83_WEAPON_TYPES:
            continue
        b = best[(s["slot"], s["wt"])]
        out = s["att"] > b[0] * OUTCLASS_ATT_X or s["stats"] > b[1] + OUTCLASS_STAT_PLUS
        rows.append({"category": "bbgear", "itemid": it, "name": items.names().get(it), "slot": s["slot"],
                     "reqLevel": s["reqLevel"], "att": s["att"], "stats": s["stats"], "classic_best_att": b[0],
                     "classic_best_stats": b[1], "outclass": 1 if out else 0})
    # a family (the name's first word: Pensalir, Jaihin, AbsoLab...) is rare as a whole when any piece outclasses
    rare = {r["name"].split()[0] for r in rows if r["outclass"]}
    for r in rows:
        r["outclass"] = 1 if r["name"].split()[0] in rare else 0
    return rows


def etc_index(wz, items):
    """Etc items a mob can drop as its own: (id, name, description), droppable ones only."""
    import xml.etree.ElementTree as ET
    out = []
    for el in ET.parse(wz / "String.wz" / "Etc.img.xml").getroot().iter("imgdir"):
        nm = el.get("name", "")
        if not nm.isdigit() or not 4000000 <= int(nm) < 4100000:
            continue
        n, d = el.find("string[@name='name']"), el.find("string[@name='desc']")
        if n is None or items.droppable(int(nm)):
            continue
        out.append((int(nm), n.get("value") or "", d.get("value") if d is not None else ""))
    return sorted(out)


def own_etc_candidates(mob_name, index):
    nm = mob_name.lower()
    if len(nm) < 4:
        return []
    word = re.compile(r"\b" + re.escape(mob_name) + r"\b")
    hits = [i for i, n, _ in index if n.lower().startswith(nm + "'s ") or n.lower().startswith(nm + " ")]
    hits += [i for i, _, d in index if word.search(d) and i not in hits]
    return hits


_SCRIPT_IDS = None


def script_ids():
    """Every 7-digit number that appears anywhere under scripts/ (quests, NPC exchanges, crafting recipes)."""
    global _SCRIPT_IDS
    if _SCRIPT_IDS is None:
        _SCRIPT_IDS = set()
        for f in sorted(SCRIPTS.rglob("*")):
            if f.is_file() and f.suffix in (".js", ".json", ".yaml", ".yml", ".txt", ".tsv"):
                _SCRIPT_IDS.update(int(x) for x in re.findall(r"(?<!\d)(4\d{6})(?!\d)", f.read_text(errors="ignore")))
    return _SCRIPT_IDS


def etc_verdict(items, itemid):
    """None when the ETC item may drop, else why not."""
    i = items.info(itemid)
    if POST_BB_SYSTEM.search(i["name"] or ""):
        return "post-Big-Bang system item"
    if itemid in script_ids():
        return None
    if (i["price"] or 0) > 0:
        return None
    return "no use in scripts and no NPC price"


ETC_EXCLUDED = {}   # itemid -> (name, reason, mobs), filled by gen for the review


def own_etc(mob_name, index, items):
    out = []
    for it in own_etc_candidates(mob_name, index):
        why = etc_verdict(items, it)
        if why:
            ETC_EXCLUDED.setdefault(it, [items.names().get(it), why, set()])[2].add(mob_name)
        else:
            out.append(it)
    return out[:len(OWN_ETC_CHANCES)]


# ---- HP ------------------------------------------------------------------------------------------------------------
def server_hp(m):
    """What LifeFactory gives the mob today (no override)."""
    if m["mobid"] in LIFEFACTORY_HP:
        return LIFEFACTORY_HP[m["mobid"]]
    if m["level"] >= 160 and m["maxHP"] > 30_000_000 and not m["boss"]:
        return m["maxHP"] // 2
    return m["maxHP"]


def classic_hp(level):
    return HP_K * level ** HP_B


def boss_hp(level):
    (l0, h0), (l1, h1) = BOSS_HP_ANCHORS
    return min(BOSS_HP_CAP, h0 * (h1 / h0) ** ((level - l0) / (l1 - l0)))


def round3(x):
    """Three significant figures, so the table reads like hand-set data."""
    if x < 1000:
        return int(round(x))
    e = 10 ** (len(str(int(x))) - 3)
    return int(round(x / e) * e)


def hp_overrides(mobs):
    """(mob, HP before, HP after) for every target mob whose server HP is clearly off the classic curve."""
    by_rl = defaultdict(list)
    for m in mobs:
        if m["tier"] == "normal":
            by_rl[(m["region"], m["level"])].append(server_hp(m))
    med = {k: statistics.median(v) for k, v in by_rl.items()}
    out = []
    for m in sorted(mobs, key=lambda x: x["mobid"]):
        t, before = m["tier"], server_hp(m)
        if t == "normal":
            f = min(max(math.sqrt(before / med[(m["region"], m["level"])]), HP_SPREAD[0]), HP_SPREAD[1])
            target = classic_hp(m["level"]) * f
        elif t == "elite":
            target = classic_hp(m["level"]) * ELITE_HP_X
        elif t == "boss":
            target = boss_hp(m["level"])
        else:
            continue
        after = round3(target)
        if before > after * HP_OFF_CURVE:   # only mobs clearly off the curve; classic-like ones keep their HP
            out.append((m, before, after))
    return out


# ---- EXP (round 3, owner 2026-10-28: about 2x the classic reference EXP/h, smooth by level) --------------------
REF_DAMAGE_PER_HOUR = 141_000 * 1000    # a character killing 1,000 Chief Oblivion Guardians (141k HP) an hour
REF_EXP_PER_HOUR = 7060 * 3 * 1000      # what that pays at 3x EXP: 21.18M
KILL_CAP = 1500                         # kills an hour a map can feed, whatever the damage
EXP_TARGET_X = 2.0
CLASSIC_EXP_K, CLASSIC_EXP_B = 0.00107394, 3.08225   # classic non-boss EXP ~= K * level ^ B (lv 30-131 fit)
CLASSIC_BUFF_X = 1.4                    # classic 120+ spots: EXP x1.4
CLASSIC_BUFF_AREAS = [                  # (label, first map id, last map id)
    ("Leafre / Minar Forest", 240010000, 240049999),
    ("Temple of Time", 270010000, 270039999),
    ("Ulu City", 541020000, 541029999),
]
# 1.6.1 b040: the notes promise "Leafre and Minar Forest, the Temple of Time and Ulu City give 40% more EXP", so every
# mob placed on those maps that gives EXP gets x1.4 (round 3 only took non-boss mobs of lv 95+: 25 of them)
CLASSIC_BUFF_MIN_LEVEL = 0
CLASSIC_BUFF_BOSSES = True
# 1.6.1 b040: the notes promise lower Grandis "pays roughly twice what the Temple of Time does". With the old target
# it paid ~1.3x the BUFFED Temple (34M against 26M an hour), so the lower-Grandis mobs (the normal mobs placed on a
# map in lower-grandis-maps.txt) get their EXP-an-hour target x LOWER_GRANDIS_EXP_X, chosen so the area's median map
# pays 2.0x the buffed Temple of Time's Road to Oblivion (tools/economy/ladder_table.py). The Future, upper Grandis,
# Arcane River, Lion King's Castle and Rose Garden keep the round-3 target.
LOWER_GRANDIS_EXP_X = 1.55
LOWER_GRANDIS_MAPS = Path("src/main/resources/soloMapling/lower-grandis-maps.txt")
# owner 2026-10-29: the Future / Twilight Perion paid ~3.9x the buffed Temple of Time; trim it to about 3x. The
# Future's normal mobs get their target x FUTURE_EXP_X, chosen so the area's median training map pays 3.0x the buffed
# Road to Oblivion (tools/economy/ladder_table.py).
FUTURE_EXP_X = 0.75
# owner 2026-10-29: Leafre of the Past (272000100-272000600, the Future's lv 124 Corrupted mobs) opens as a third Gate
# of Time choice, a 120-140 spot. Its mobs are paid like lower Grandis at their level (x LOWER_GRANDIS_EXP_X), not
# the trimmed Future rate: a lv 124 map should pay what a lv 124 lower-Grandis map does.
LEAFRE_OF_THE_PAST = (272000100, 272000600)
STAT_TSV = Path("src/main/resources/soloMapling/mob-stat-overrides.tsv")


def classic_exp(level):
    return CLASSIC_EXP_K * level ** CLASSIC_EXP_B


def kills_per_hour(hp):
    return min(REF_DAMAGE_PER_HOUR / hp, KILL_CAP)


def target_exp_per_hour(level):
    """EXP_TARGET_X x the classic spot, scaled from lv 131 like classic EXP per kill grows with level."""
    return EXP_TARGET_X * REF_EXP_PER_HOUR * classic_exp(level) / classic_exp(131)


def new_exp(level, hp, x=1.0):
    return round3(max(classic_exp(level), x * target_exp_per_hour(level) / (3 * kills_per_hour(hp))))


def lower_grandis_mobs(wz):
    """Mob ids placed on a lower-Grandis map (the list AreaGates reads)."""
    import xml.etree.ElementTree as ET
    maps = set()
    for line in LOWER_GRANDIS_MAPS.read_text().splitlines():
        i = line.split("#", 1)[0].strip()
        if i:
            maps.add(int(i))
    out = set()
    for m in sorted(maps):
        f = wz / "Map.wz" / "Map" / f"Map{m // 100000000}" / f"{m:09d}.img.xml"
        if not f.exists():
            continue
        for node in ET.parse(f).getroot():
            if node.get("name") == "life":
                for e in node:
                    v = {c.get("name"): c.get("value") for c in e}
                    if v.get("type") == "m":
                        out.add(int(v["id"]))
    return out


def mobs_on_maps(wz, lo, hi):
    """Mob ids placed on Map.wz maps lo..hi."""
    import xml.etree.ElementTree as ET
    out = set()
    for f in sorted((wz / "Map.wz" / "Map" / f"Map{lo // 100000000}").glob("*.img.xml")):
        stem = f.name.split(".")[0]
        if not stem.isdigit() or not lo <= int(stem) <= hi:
            continue
        for node in ET.parse(f).getroot():
            if node.get("name") == "life":
                for e in node:
                    v = {c.get("name"): c.get("value") for c in e}
                    if v.get("type") == "m":
                        out.add(int(v["id"]))
    return out


def exp_x(m, lower, past):
    """The EXP-target multiplier for one target mob (round 3 = 1.0)."""
    if m["region"] == "grandis" and m["mobid"] in lower:
        return LOWER_GRANDIS_EXP_X
    if m["region"] == "future":
        return LOWER_GRANDIS_EXP_X if m["mobid"] in past else FUTURE_EXP_X
    return 1.0


def classic_buff_mobs(wz, targets):
    """{mob id: (level, maxHP, exp, area label)} for every EXP-giving mob placed in CLASSIC_BUFF_AREAS (event mobs
    9300000-9399999 and the target mobs aside)."""
    import xml.etree.ElementTree as ET
    out = {}
    for label, lo, hi in CLASSIC_BUFF_AREAS:
        folder = wz / "Map.wz" / "Map" / f"Map{lo // 100000000}"
        for f in sorted(folder.glob("*.img.xml")):
            stem = f.name.split(".")[0]
            if not stem.isdigit() or not lo <= int(stem) <= hi:
                continue
            for node in ET.parse(f).getroot():
                if node.get("name") != "life":
                    continue
                for e in node:
                    v = {c.get("name"): c.get("value") for c in e}
                    if v.get("type") != "m":
                        continue
                    mob = int(v["id"])
                    if mob in targets or mob in out or 9300000 <= mob < 9400000:
                        continue
                    info = full_mob_info(wz, mob)
                    if info and (CLASSIC_BUFF_BOSSES or not info["boss"]) and info["exp"] > 0 \
                            and info["level"] >= CLASSIC_BUFF_MIN_LEVEL:
                        out[mob] = (info["level"], info["maxHP"], info["exp"], label, info["name"])
    return out


def full_mob_info(wz, mobid, depth=0):
    f = wz / "Mob.wz" / f"{mobid:07d}.img.xml"
    if not f.exists():
        return None
    t = info_text(f.read_text(errors="ignore"))

    def g(k):
        m = re.search(r'name="%s" value="([^"]*)"' % k, t)
        return m.group(1) if m else None
    own_exp = g("exp")
    if g("link") and depth < 3:
        linked = full_mob_info(wz, int(g("link")), depth + 1)
        if linked:
            if own_exp is not None:
                linked = dict(linked, exp=int(own_exp))
            return linked
    return {"level": int(g("level") or 0), "maxHP": int(g("maxHP") or 0), "exp": int(own_exp or 0),
            "boss": g("boss") == "1", "name": MOB_NAMES.get(mobid, "")}


MOB_NAMES = {}


def stat_overrides(wz, mobs, all_targets):
    """Rows (mob id, name, area, tier, level, hp before, hp after or None, exp before, exp after or None)."""
    hp = {m["mobid"]: (before, after) for m, before, after in hp_overrides(mobs)}
    lower = lower_grandis_mobs(wz)
    past = mobs_on_maps(wz, *LEAFRE_OF_THE_PAST)
    rows = []
    for m in sorted(mobs, key=lambda x: x["mobid"]):
        before = server_hp(m)
        hp_after = hp.get(m["mobid"], (before, None))[1]
        exp_after = None
        if m["tier"] == "normal":
            x = exp_x(m, lower, past)
            exp_after = new_exp(m["level"], hp_after or before, x)
            if exp_after == m["exp"]:
                exp_after = None
        if hp_after is not None or exp_after is not None:
            rows.append((m["mobid"], m["name"], m["region"], m["tier"], m["level"], before, hp_after, m["exp"], exp_after))
    for mob, (level, maxhp, exp, label, name) in sorted(classic_buff_mobs(wz, all_targets).items()):
        rows.append((mob, name, "classic: " + label, "classic", level, maxhp, None, exp, int(round(exp * CLASSIC_BUFF_X))))
    return rows


def write_stats(rows):
    lines = ["# SlumsMS ticket 81 (owner 2026-10-28): mob max HP and EXP overrides. The post-Big-Bang mobs and the Lion",
             "# King's Castle / Rose Garden mobs get a classic-like HP for their level and EXP for about 2x a classic",
             "# spot's EXP per hour (lower Grandis and Leafre of the Past: about 2x the buffed Temple of Time, the Future:",
             "# about 3x); every mob of Leafre/Minar,",
             "# the Temple of Time and Ulu City gets EXP x1.4.",
             "# Read by server.life.MobStatOverrides, applied in LifeFactory after its own values. Generated by",
             "# tools/economy/gen_bb_drops.py (rules: docs/economy/bb-drops/RULES.md). Off: -Dslums.mobStatOverrides=false.",
             "# Columns: mob id, max HP ('-' = keep), EXP ('-' = keep), comment.",
             "# mobid\thp\texp\tname (level, tier): HP before / EXP before"]
    for mob, name, area, tier, level, hp_b, hp_a, exp_b, exp_a in rows:
        lines.append(f"{mob}\t{hp_a if hp_a is not None else '-'}\t{exp_a if exp_a is not None else '-'}\t"
                     f"{name} (lv {level}, {tier}): {hp_b} / {exp_b}")
    STAT_TSV.write_text("\n".join(lines) + "\n")
    with open(DOCS / "stats.csv", "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["mobid", "name", "area", "tier", "level", "server_hp_before", "hp_after", "exp_before", "exp_after",
                    "exp_per_hour_before", "exp_per_hour_after"])
        for mob, name, area, tier, level, hp_b, hp_a, exp_b, exp_a in rows:
            eb = kills_per_hour(hp_b) * exp_b * 3
            ea = kills_per_hour(hp_a or hp_b) * (exp_a if exp_a is not None else exp_b) * 3
            w.writerow([mob, name, area, tier, level, hp_b, hp_a if hp_a is not None else "", exp_b,
                        exp_a if exp_a is not None else "", int(round(eb)), int(round(ea))])


# ---- generate ----------------------------------------------------------------------------------------------------
def tier_of(m):
    if m["exp"] <= 0:
        return "skip"
    if m["level"] < LOW_LEVEL:
        return "low"
    if m["boss"]:
        return "boss" if m["maxHP"] >= BOSS_HP else "elite"
    return "normal"


def classic_meso_mid(level):
    return MESO_K * level ** 2 if level <= MESO_KNEE else MESO_PER_LEVEL * level


def meso_range(m, factor):
    t = m["tier"]
    if t == "boss":
        mid = BOSS_MESO_PER_LEVEL * m["level"]
    else:
        mid = classic_meso_mid(m["level"]) * factor
        if t == "elite":
            mid *= ELITE_MESO_X
    lo = max(1, int(round(mid * (1 - MESO_SPREAD))))
    hi = max(lo + 1, int(round(mid * (1 + MESO_SPREAD))))
    return lo, hi


def pick(rng, pool, n):
    pool = sorted(pool)
    return sorted(rng.sample(pool, min(n, len(pool))))


def gen(a):
    wz = Path(a.wz)
    items = WzItems(wz)
    mobs = []
    for r in csv.DictReader(open(a.mobs)):
        if r["status"] == "has-table":
            continue
        m = {"mobid": int(r["mobid"]), "name": r["name"], "region": r["region"],
             "level": int(r["level"] or 0), "maxHP": int(r["maxHP"] or 0), "exp": int(r["exp"] or 0),
             "boss": r["boss"] == "1", "status": r["status"]}
        m["tier"] = tier_of(m)
        mobs.append(m)
    keep = set(REGIONS if a.regions == "all" else a.regions.split(","))
    mobs = [m for m in mobs if m["region"] in keep]

    pool = defaultdict(list)
    for r in csv.DictReader(open(a.pool)):
        pool[r["category"]].append(r)
    equips = [(int(r["reqLevel"]), int(r["itemid"]), int(r["chance"])) for r in pool["equip"]]
    eq_max = max(lv for lv, _, _ in equips)
    scrolls = {int(r["itemid"]): int(r["chance"]) for r in pool["scroll"]}
    ores = {g: sorted(int(r["itemid"]) for r in pool[g]) for g in ORE_GROUPS}
    books = {int(r["itemid"]): int(r["chance"]) for r in pool["book"]}
    bbgear = [(int(r["reqLevel"]), int(r["itemid"]), r["slot"], r["outclass"] == "1") for r in pool["bbgear"]]
    etcs = etc_index(wz, items)

    # median exp per (region, level), for the within-level meso factor
    by_rl = defaultdict(list)
    for m in mobs:
        if m["tier"] in ("normal", "low"):
            by_rl[(m["region"], m["level"])].append(m["exp"])
    med = {k: statistics.median(v) for k, v in by_rl.items()}

    out = []   # (mobid, itemid, min, max, questid, chance, category)
    for m in sorted(mobs, key=lambda x: x["mobid"]):
        t = m["tier"]
        if t == "skip":
            continue
        mid = m["mobid"]
        factor = 1.0
        if t in ("normal", "low") and med.get((m["region"], m["level"])):
            factor = min(max(math.sqrt(m["exp"] / med[(m["region"], m["level"])]), EXP_FACTOR[0]), EXP_FACTOR[1])
        lo, hi = meso_range(m, factor)
        out.append((mid, 0, lo, hi, 0, BOSS_MESO_CHANCE if t == "boss" else MESO_CHANCE, "meso"))
        if t == "low":
            out.append((mid, seeded(mid, "potion").choice(POTIONS_LOW), 1, 1, 0, POTION_CHANCE, "potion"))
            continue

        top = min(m["level"], eq_max)
        band = [(it, ch) for lv, it, ch in equips if top - EQUIP_BAND <= lv <= top]
        bbtop = min(m["level"], BB_TOP)
        bbband = [] if m["region"] in CLASSIC_REGIONS else \
            [(lv, it, sl, oc) for lv, it, sl, oc in bbgear if bbtop - BB_BAND <= lv <= bbtop]
        par = {it: (BB_PAR_CHANCE[0].get(sl, BB_PAR_CHANCE[1])) for lv, it, sl, oc in bbband if not oc}
        rare_lv = {it: lv for lv, it, sl, oc in bbband if oc}

        def rare_chance(it, table):
            return next(c for floor, c in table if rare_lv[it] >= floor)

        for k, it in enumerate(own_etc(m["name"], etcs, items)):
            out.append((mid, it, 1, 1, 0, OWN_ETC_CHANCES[k], "etc"))
        for it, ch in REGION_ETC.get(m["region"], []):
            if etc_verdict(items, it) is None:
                out.append((mid, it, 1, 1, 0, ch, "etc"))

        if t == "boss":
            for it, mn, mx, ch in BOSS_POTIONS + BOSS_ROCKS:
                out.append((mid, it, mn, mx, 0, ch, "potion" if it < 3000000 else "rock"))
            for it in pick(seeded(mid, "scroll"), list(scrolls), BOSS_PICKS["scroll"]):
                out.append((mid, it, 1, 1, 0, BOSS_SCROLL_CHANCE, "scroll"))
            for it in pick(seeded(mid, "equip"), [it for it, _ in band], BOSS_PICKS["equip"]):
                out.append((mid, it, 1, 1, 0, BOSS_EQUIP_CHANCE, "equip"))
            for it in pick(seeded(mid, "book"), list(books), BOSS_PICKS["book"]):
                out.append((mid, it, 1, 1, 0, BOSS_BOOK_CHANCE, "book"))
            for it in pick(seeded(mid, "bbgear"), list(par), BOSS_PICKS["bbgear"]):
                out.append((mid, it, 1, 1, 0, BB_BOSS_CHANCE, "bbgear"))
            for it in pick(seeded(mid, "bbrare"), list(rare_lv), BOSS_PICKS["bbrare"]):
                out.append((mid, it, 1, 1, 0, rare_chance(it, BB_BOSS_RARE_CHANCE), "bbrare"))
            continue

        picks = ELITE_PICKS if t == "elite" else NORMAL_PICKS
        x = ELITE_X if t == "elite" else 1
        for it in pick(seeded(mid, "potion"), POTIONS_HIGH, picks["potion"]):
            out.append((mid, it, 1, 1, 0, POTION_CHANCE * x, "potion"))
        for g in ("crystal", "mineral", "rock", "stimulator"):
            for it in pick(seeded(mid, g), ores[g], picks[g]):
                out.append((mid, it, 1, 1, 0, ORE_GROUPS[g][1] * x, g))
        for it in pick(seeded(mid, "scroll"), list(scrolls), picks["scroll"]):
            out.append((mid, it, 1, 1, 0, scrolls[it] * x, "scroll"))
        band_ch = dict(band)
        for it in pick(seeded(mid, "equip"), list(band_ch), picks["equip"]):
            out.append((mid, it, 1, 1, 0, band_ch[it] * x, "equip"))
        for it in pick(seeded(mid, "book"), list(books), picks["book"]):
            out.append((mid, it, 1, 1, 0, books[it] * x, "book"))
        for it in pick(seeded(mid, "bbgear"), list(par), picks["bbgear"]):
            out.append((mid, it, 1, 1, 0, par[it] * x, "bbgear"))
        for it in pick(seeded(mid, "bbrare"), list(rare_lv), picks["bbrare"]):
            out.append((mid, it, 1, 1, 0, rare_chance(it, BB_RARE_CHANCE) * x, "bbrare"))

    # every item must be droppable (exists, named, not cash/quest/untradeable) and never a card
    bad = []
    for r in out:
        if r[1] and (is_card(r[1]) or items.droppable(r[1])):
            bad.append((r, items.droppable(r[1]) or "card"))
    if bad:
        for b in bad[:20]:
            print("BAD", b, file=sys.stderr)
        sys.exit("refusing: %d rows with undroppable items" % len(bad))
    keys = [(r[0], r[1]) for r in out]
    assert len(keys) == len(set(keys)), "duplicate (mob, item)"

    names = items.names()
    tiers = {m["mobid"]: m for m in mobs}
    with open(DOCS / "rows.csv", "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["dropperid", "mob", "region", "tier", "level", "itemid", "item", "category", "minimum_quantity",
                    "maximum_quantity", "questid", "chance"])
        for r in out:
            m = tiers[r[0]]
            w.writerow([r[0], m["name"], m["region"], m["tier"], m["level"], r[1],
                        "mesos" if r[1] == 0 else names.get(r[1], ""), r[6], r[2], r[3], r[4], r[5]])
    write_sql(out, mobs)
    with open(DOCS / "etc-excluded.csv", "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["itemid", "name", "reason", "mobs"])
        for it in sorted(ETC_EXCLUDED):
            name, why, who = ETC_EXCLUDED[it]
            w.writerow([it, name, why, "|".join(sorted(who))])
    MOB_NAMES.update({int(r["mobid"]): r["name"] for r in csv.DictReader(open(a.mobs))})
    import xml.etree.ElementTree as ET
    for el in ET.parse(wz / "String.wz" / "Mob.img.xml").getroot():
        n = el.find("string[@name='name']")
        if el.get("name", "").isdigit() and n is not None:
            MOB_NAMES.setdefault(int(el.get("name")), n.get("value"))
    all_targets = {int(r["mobid"]) for r in csv.DictReader(open(a.mobs)) if r["status"] != "has-table"}
    stats = stat_overrides(wz, mobs, all_targets)
    write_stats(stats)
    print("stat overrides:", len(stats), file=sys.stderr)
    per = defaultdict(int)
    for m in mobs:
        per[(m["region"], m["tier"])] += 1
    print("rows:", len(out), "mobs with tables:", len({r[0] for r in out}), dict(sorted(per.items())), file=sys.stderr)


def write_sql(out, mobs):
    regions = defaultdict(set)
    for m in mobs:
        if m["tier"] != "skip":
            regions[m["region"]].add(m["mobid"])
    head = f"""-- SlumsMS ticket 81 (owner 2026-10-28): drop tables for the post-Big-Bang mobs. DRAFT for the owner's review
-- (docs/economy/bb-drops/REVIEW.md); do not apply to TEST or live before the owner's go.
-- Generated by tools/economy/gen_bb_drops.py from docs/economy/bb-drops/mobs.csv and classic-pool.csv; edit the
-- generator, not this file. Rules: docs/economy/bb-drops/RULES.md.
-- {len(out)} rows for {len({r[0] for r in out})} mobs: Arcane River {len(regions['arcane'])}, Grandis {len(regions['grandis'])},
-- Future/Twilight Perion {len(regions['future'])}, Lion King's Castle / Rose Garden {len(regions['elnath'])},
-- Mirror World {len(regions['mirror'])} (no way in today; the generator adds it with --regions all).
-- Mesos are itemid 0 (minimum/maximum_quantity = the base meso range, before the world meso rate). Monster Book
-- card rows (045) are never touched: no card item is inserted and an existing (mob, item) pair is skipped.
-- Only rows this migration really inserts are recorded in {LEDGER}, so the rollback deletes exactly those.
-- Applying it twice changes nothing (ledger id '{MIGRATION_ID}'). A running server reads drop_data on boot or on
-- !reloaddrops.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS {LEDGER} (
  dropperid int NOT NULL,
  itemid    int NOT NULL,
  PRIMARY KEY (dropperid, itemid)
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE bb_drops_050_new (
  dropperid int NOT NULL, itemid int NOT NULL, minimum_quantity int NOT NULL, maximum_quantity int NOT NULL,
  questid int NOT NULL, chance int NOT NULL, PRIMARY KEY (dropperid, itemid));
"""
    body = []
    for i in range(0, len(out), 500):
        chunk = out[i:i + 500]
        body.append("INSERT INTO bb_drops_050_new (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance) VALUES\n"
                    + ",\n".join(f"({r[0]}, {r[1]}, {r[2]}, {r[3]}, {r[4]}, {r[5]})" for r in chunk) + ";")
    tail = f"""
-- record only the pairs not already in drop_data (once recorded, a pair stays recorded on a second run)
INSERT IGNORE INTO {LEDGER} (dropperid, itemid)
SELECT n.dropperid, n.itemid FROM bb_drops_050_new n
 WHERE NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = n.dropperid AND d.itemid = n.itemid);
INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
SELECT n.dropperid, n.itemid, n.minimum_quantity, n.maximum_quantity, n.questid, n.chance
  FROM bb_drops_050_new n JOIN {LEDGER} r ON r.dropperid = n.dropperid AND r.itemid = n.itemid
 WHERE NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = n.dropperid AND d.itemid = n.itemid);

DROP TEMPORARY TABLE bb_drops_050_new;
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('{MIGRATION_ID}');
"""
    Path(f"database/migrations/{MIGRATION_ID}.sql").write_text(head + "\n".join(body) + "\n" + tail)
    Path(f"database/migrations/{MIGRATION_ID}.rollback.sql").write_text(f"""-- Undoes 050: deletes the drop_data rows 050 inserted (recorded in {LEDGER}; a pair that was already there before
-- 050, such as a Monster Book card row, is left alone). Applying it twice changes nothing. A running server needs
-- !reloaddrops. Generated by tools/economy/gen_bb_drops.py.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS {LEDGER} (
  dropperid int NOT NULL,
  itemid    int NOT NULL,
  PRIMARY KEY (dropperid, itemid)
) ENGINE=InnoDB;

DELETE d FROM drop_data d JOIN {LEDGER} r ON r.dropperid = d.dropperid AND r.itemid = d.itemid;
DELETE FROM {LEDGER};
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '{MIGRATION_ID}';
""")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pool")
    p.add_argument("--drops", required=True)
    p.add_argument("--mobs", default=str(DOCS / "mobs.csv"))
    p.add_argument("--wz", default="wz")
    p.add_argument("--out", default=str(DOCS / "classic-pool.csv"))
    g = sub.add_parser("gen")
    g.add_argument("--mobs", default=str(DOCS / "mobs.csv"))
    g.add_argument("--pool", default=str(DOCS / "classic-pool.csv"))
    g.add_argument("--wz", default="wz")
    g.add_argument("--regions", default=DEFAULT_REGIONS,
                   help="comma list of arcane,grandis,future,mirror, or 'all'; default %s" % DEFAULT_REGIONS)
    a = ap.parse_args()
    build_pool(a) if a.cmd == "pool" else gen(a)


if __name__ == "__main__":
    main()
