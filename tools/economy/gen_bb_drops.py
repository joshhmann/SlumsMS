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
NORMAL_PICKS = {"potion": 3, "crystal": 1, "mineral": 2, "rock": 1, "stimulator": 1, "scroll": 4, "equip": 5}
ELITE_PICKS = {"potion": 3, "crystal": 2, "mineral": 3, "rock": 2, "stimulator": 2, "scroll": 6, "equip": 6}
ELITE_X = 2                   # elite item chances = classic x2
BOSS_PICKS = {"scroll": 6, "equip": 8}
BOSS_SCROLL_CHANCE = 13500    # Zakum's scroll rate
BOSS_EQUIP_CHANCE = 22500     # Zakum's lowest weapon rate (conservative end of its 22.5k-40k)
BOSS_ROCKS = [(4006000, 1, 1, 7000), (4006001, 1, 1, 7000)]

SCROLL_CHANCE = {10: 300, 60: 300, 100: 300, 30: 750, 70: 750}   # classic: normal 300, dark 750
EQUIP_BAND = 20               # equips with reqLevel in [top - 20, top], top = min(mob level, pool max)
EQUIP_CHANCE_CLAMP = (500, 1287)

REGIONS = ["arcane", "grandis", "future", "mirror"]


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
    t = f.read_text(errors="ignore")[:8000]

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
        if not (1000000 <= item < 2000000 or 2040000 <= item < 2049000 or item // 1000 in (4004, 4010, 4020, 4006, 4007, 4130)):
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
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    counts = defaultdict(int)
    for r in rows:
        counts[r["category"]] += 1
    print("pool:", dict(counts), file=sys.stderr)


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
    if a.regions:
        keep = set(a.regions.split(","))
        mobs = [m for m in mobs if m["region"] in keep]

    pool = defaultdict(list)
    for r in csv.DictReader(open(a.pool)):
        pool[r["category"]].append(r)
    equips = [(int(r["reqLevel"]), int(r["itemid"]), int(r["chance"])) for r in pool["equip"]]
    eq_max = max(lv for lv, _, _ in equips)
    scrolls = {int(r["itemid"]): int(r["chance"]) for r in pool["scroll"]}
    ores = {g: sorted(int(r["itemid"]) for r in pool[g]) for g in ORE_GROUPS}

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

        if t == "boss":
            for it, mn, mx, ch in BOSS_POTIONS + BOSS_ROCKS:
                out.append((mid, it, mn, mx, 0, ch, "potion" if it < 3000000 else "rock"))
            for it in pick(seeded(mid, "scroll"), list(scrolls), BOSS_PICKS["scroll"]):
                out.append((mid, it, 1, 1, 0, BOSS_SCROLL_CHANCE, "scroll"))
            for it in pick(seeded(mid, "equip"), [it for it, _ in band], BOSS_PICKS["equip"]):
                out.append((mid, it, 1, 1, 0, BOSS_EQUIP_CHANCE, "equip"))
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
-- Future/Twilight Perion {len(regions['future'])}, Mirror World {len(regions['mirror'])} (Mirror World has no way in today).
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
    g.add_argument("--regions", default="", help="comma list to limit (arcane,grandis,future,mirror); default all")
    a = ap.parse_args()
    build_pool(a) if a.cmd == "pool" else gen(a)


if __name__ == "__main__":
    main()
