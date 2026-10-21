#!/usr/bin/env python3
"""Export the server's view of the Monster Book to monsterbook.json (ticket 19).

The client's book (String/MonsterBook.img) is baked data: which icons the Dropping tab shows, which maps
Found In lists, the Episode text. This tool derives all of that from THIS server's data, so the book shows
the drops the server really rolls, SlumsMS changes included:

  drops     drop_data (itemid > 0, chance > 0), best chance first        -> reward
  spawns    Map.wz/Map/**/<map>.img.xml life entries of type "m",
            plus scripts/event/AreaBoss*.js (field bosses have no life)   -> map
  stats     Mob.wz/<mob>.img.xml info/, following info/link              -> episode ("CATEGORY : VALUE" lines)
  HP/MP     string or oversized info/maxHP, info/maxMP                   -> defaultHp (the book prints it verbatim)
  cards     Item.wz/Consume/0238.img.xml info/mob                        -> which mobs the book can show

The client repo's tools/wz/add_monster_book.py turns the JSON into Custom.wz. The live drop CHANCES are not
baked: the server sends them when a player opens the Dropping tab (MONSTER_BOOK_QUERY 0x3734).

Drop rows come from the server database or from a dump of it, never from another server's copy:

  # straight from MySQL (credentials from an option file, never the command line)
  build_book.py --wz /path/to/Server/wz --scripts scripts/event \\
      --mysql "--defaults-extra-file=/root/db.cnf -h 127.0.0.1 -P 3309 mapleroot" -o monsterbook.json

  # or from a TSV dump: mysql -B -N -e "SELECT dropperid, itemid, chance FROM drop_data" > drops.tsv
  build_book.py --wz ... --scripts ... --drops-tsv drops.tsv -o monsterbook.json

Deterministic: the same inputs write the same bytes.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

INT_MAX = 2**31 - 1
MAP_CAP = 40

# WZ info field -> Episode label, in print order. A whitelist on purpose: info/ also holds engine flags that
# mean nothing to a player. maxHP/maxMP are left out because Basic Info already prints them.
STAT_FIELDS = [
    ("PADamage", "PAD"), ("MADamage", "MAD"), ("PDDamage", "PDD"), ("MDDamage", "MDD"),
    ("PDRate", "PDR"), ("MDRate", "MDR"), ("acc", "ACC"), ("eva", "EVA"), ("speed", "SPEED"),
    ("pushed", "PUSHED"), ("exp", "EXP"), ("hpRecovery", "HPREC"), ("mpRecovery", "MPREC"),
]
# info/elemAttr pairs ("H2F3"), with the server's own tables (server.life.Element / ElementalEffectiveness)
ELEM_CHAR = {"P": "Physical", "F": "Fire", "I": "Ice", "L": "Lightning", "S": "Poison", "H": "Holy", "D": "Dark"}
ELEM_EFFECT = {"1": "immune", "2": "resist", "3": "weak", "4": "neutral"}
LEAF_TAGS = {"int", "short", "long", "string", "float", "double"}


def parse(path):
    return ET.parse(path).getroot()


class MobInfo:
    """info/ leaves per mob, merged over the info/link target's (own wins), with a cycle guard."""

    def __init__(self, mob_dir):
        self.mob_dir = mob_dir
        self.cache = {}

    def own(self, mob):
        path = os.path.join(self.mob_dir, "%07d.img.xml" % mob)
        if not os.path.exists(path):
            return None
        info = parse(path).find("imgdir[@name='info']")
        if info is None:
            return {}
        return {c.get("name"): (c.tag, c.get("value")) for c in info if c.tag in LEAF_TAGS}

    def get(self, mob, depth=0):
        if mob in self.cache:
            return self.cache[mob]
        self.cache[mob] = {}
        own = self.own(mob)
        if own is None:
            self.cache[mob] = None
            return None
        merged = dict(own)
        link = own.get("link")
        if link and depth < 4:
            try:
                target = self.get(int(link[1]), depth + 1)
            except ValueError:
                target = None
            for k, v in (target or {}).items():
                merged.setdefault(k, v)
        self.cache[mob] = merged
        return merged


def as_int(info, key):
    v = (info or {}).get(key)
    if not v or v[1] is None:
        return None
    try:
        return int(v[1])
    except ValueError:
        try:
            return int(float(v[1]))
        except ValueError:
            return None


def stat_lines(info):
    out = ["%s : %d" % (label, v) for field, label in STAT_FIELDS
           for v in [as_int(info, field)] if v is not None]
    elem = (info or {}).get("elemAttr")
    if elem and elem[1]:
        s = elem[1]
        for i in range(0, len(s) - 1, 2):
            name, effect = ELEM_CHAR.get(s[i].upper()), ELEM_EFFECT.get(s[i + 1])
            if name and effect:
                out.append("ELEM : %s %s" % (name, effect))
    return out


def book_hp(own):
    """The defaultHP/defaultMP strings the client book should print, or {} when maxHP/maxMP read fine as ints.

    The book's loader takes info/defaultHP verbatim when it is a string and otherwise pushes info/maxHP through
    an int conversion that turns a string or an oversized number into garbage."""
    out = {}
    for src, dst in (("maxHP", "hp"), ("maxMP", "mp")):
        v = (own or {}).get(src)
        if not v or v[1] is None:
            continue
        tag, value = v
        if tag == "string" or (value.lstrip("-").isdigit() and int(value) > INT_MAX):
            out[dst] = "???" if value.startswith("?") or not value.strip() else value.strip()
    return out


def card_mobs(wz):
    cards = {}
    for card in parse(os.path.join(wz, "Item.wz", "Consume", "0238.img.xml")):
        mob = card.find("imgdir[@name='info']/int[@name='mob']")
        if mob is not None:
            cards[int(card.get("name"))] = int(mob.get("value"))
    return cards


def names(path):
    out = {}
    for e in parse(path):
        n = e.find("string[@name='name']")
        try:
            out[int(e.get("name"))] = n.get("value") if n is not None else ""
        except (TypeError, ValueError):
            pass
    return out


def named_maps(wz):
    ids = set()
    for region in parse(os.path.join(wz, "String.wz", "Map.img.xml")):
        for m in region:
            try:
                ids.add(int(m.get("name")))
            except (TypeError, ValueError):
                pass
    return ids


def spawns(wz, scripts):
    mob_maps = defaultdict(set)
    root = os.path.join(wz, "Map.wz", "Map")
    for sub in sorted(os.listdir(root)):
        d = os.path.join(root, sub)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".img.xml"):
                continue
            mapid = int(fn.split(".")[0])
            # cheap pre-check: most maps are parsed only when they carry life
            with open(os.path.join(d, fn), "rb") as fh:
                data = fh.read()
            if b'name="life"' not in data:
                continue
            life = ET.fromstring(data).find("imgdir[@name='life']")
            for entry in life if life is not None else []:
                t = entry.find("string[@name='type']")
                i = entry.find("string[@name='id']")
                if t is not None and t.get("value") == "m" and i is not None and i.get("value", "").isdigit():
                    mob_maps[int(i.get("value"))].add(mapid)
    if scripts and os.path.isdir(scripts):
        for fn in sorted(os.listdir(scripts)):
            if fn.startswith("AreaBoss") and fn.endswith(".js"):
                s = open(os.path.join(scripts, fn), encoding="utf-8", errors="replace").read()
                maps = {int(x) for x in re.findall(r"getMap\((\d{3,9})\)", s)}
                for boss in {int(x) for x in re.findall(r"getMonster\((\d{4,9})\)", s)}:
                    mob_maps[boss] |= maps
    return mob_maps


def read_drops(args):
    if args.drops_tsv:
        lines = open(args.drops_tsv, encoding="utf-8").read().splitlines()
    else:
        cmd = ["mysql", "--batch", "--skip-column-names"] + shlex.split(args.mysql) + [
            "-e", "SELECT dropperid, itemid, chance FROM drop_data WHERE itemid > 0 AND chance > 0"]
        lines = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.splitlines()
    drops = defaultdict(dict)
    rows = 0
    for line in lines:
        parts = line.split("\t")
        if len(parts) < 3 or not parts[0].strip().lstrip("-").isdigit():
            continue  # a header row or blank line
        mob, item, chance = (int(p) for p in parts[:3])
        if item <= 0 or chance <= 0:
            continue
        rows += 1
        drops[mob][item] = max(chance, drops[mob].get(item, 0))   # one entry per item: its best quest row
    return drops, rows


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wz", required=True, help="the server's XML wz directory")
    ap.add_argument("--scripts", help="scripts/event (AreaBoss*.js field bosses)")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--drops-tsv", help="TSV of dropperid, itemid, chance")
    src.add_argument("--mysql", help="mysql client arguments ending with the database name")
    ap.add_argument("-o", "--out", default="monsterbook.json")
    args = ap.parse_args(argv)

    drops, rows = read_drops(args)
    if not rows:
        sys.exit("drop_data came back empty; refusing to export an empty book")
    cards = card_mobs(args.wz)
    mob_names = names(os.path.join(args.wz, "String.wz", "Mob.img.xml"))
    maps_named = named_maps(args.wz)
    mob_maps = spawns(args.wz, args.scripts)
    info = MobInfo(os.path.join(args.wz, "Mob.wz"))

    wanted = sorted(set(cards.values()) | set(drops))
    mobs = {}
    for mob in wanted:
        merged = info.get(mob)
        if merged is None:
            continue   # no Mob.wz entry: nothing the book could show
        reward = [item for item, _ in sorted(drops.get(mob, {}).items(), key=lambda t: (-t[1], t[0]))]
        maps = sorted(m for m in mob_maps.get(mob, ()) if m in maps_named)[:MAP_CAP]
        entry = {"name": mob_names.get(mob, ""), "level": as_int(merged, "level") or 0,
                 "boss": bool(as_int(merged, "boss")), "stats": stat_lines(merged),
                 "map": maps, "reward": reward}
        hp = book_hp(info.own(mob))
        if hp:
            entry["defaultHp"] = hp
        mobs[str(mob)] = entry

    out = {"format": 1, "drop_rows": rows,
           "cards": {str(c): m for c, m in sorted(cards.items())},
           "mobs": mobs}
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, sort_keys=True, indent=1)
        fh.write("\n")
    carded = [m for m in set(cards.values()) if str(m) in mobs]
    print(f"wrote {args.out}: {len(mobs)} mobs ({len(carded)} with a card), {rows} drop rows, "
          f"{sum(1 for m in carded if mobs[str(m)]['reward'])} carded mobs with drops, "
          f"{sum(1 for m in carded if mobs[str(m)]['map'])} with spawn maps, "
          f"{sum(1 for e in mobs.values() if 'defaultHp' in e)} with a book HP string")
    return 0


if __name__ == "__main__":
    sys.exit(main())
