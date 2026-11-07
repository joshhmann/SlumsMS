"""npcs.json, items.json, mobs.json and maps.json for the content atlas (docs/atlas/SCHEMA.md).

Input: the Slums Street extractor's output (tools/hiddenstreet/build_data.py, the four json files), the WZ XML copy
and the TEST DB table exports (TSV). Every row cites its WZ line, and every fact that came from a table carries a
db-export source. Refs are typed and forward only; nothing stores a used_by list.

Reach is a flag on the row, never a filter: `player` when a player can meet it, `gm`/`event` for GM-only rooms and
GM-run event maps, `unobtainable` otherwise with the reason in `reach_note`. The rules are the site's own
(tools/site/sections/dbdata.py: named(), EXCLUDE_MAPS, EXCLUDE_MAP_RANGES), copied here so the atlas can run
without the site package and without changing it.
"""
import argparse
import csv
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wzxml  # noqa: E402
from quests_atlas import HANGUL, LATIN, plain, ref  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# --- site rules, copied from tools/site/sections/dbdata.py (2026-11-07) -------------------------------------------
BRAND = re.compile(r"maple\s?story|nexon|wizet", re.I)
REAL_NAME = re.compile(r"[^\W_]")  # at least one letter or digit
# map id -> (reach, short reason). dbdata.py keeps longer reasons; the atlas only needs a one-line note.
EXCLUDE_MAPS = {
    180000000: ("gm", "GM room (its data calls it Ellinia; the GM styling NPCs stand there)"),
    706030000: ("event", "9th Anniversary event map that nothing leads to"),
    820000000: ("event", "Event Hall, a later version's event map that nothing leads to"),
    867145000: ("event", "Event Hall (Extravagameza), a later version's event map that nothing leads to"),
}
EXCLUDE_MAP_RANGES = [
    (109000000, 109999999, ("event", "GM-run event map (Find the Jewel, OX Quiz, Ola Ola, Fitness and their rooms)")),
]
# street names the data gets wrong (dbdata.STREETS)
STREETS = {910000000: "Free Market", 993910000: "Free Market"}

SNAPSHOT, SNAPSHOT_DATE = "test-1.6.5", "2026-11-07"
ITEM_DIRS = ("Consume", "Etc", "Install", "Cash", "Pet", "Special")
EQP_DIRS = ("Accessory", "Afterimage", "Cap", "Cape", "Coat", "Dragon", "Face", "Glove", "Hair", "Longcoat",
            "Pants", "PetEquip", "Ring", "Shield", "Shoes", "TamingMob", "Weapon")
RANK = {"player": 3, "gm": 2, "event": 1, "unobtainable": 0}
ON_USER_ENTER = re.compile(rb'<string name="onUserEnter" value="([^"]*)"')

# Refs in a finished build that point at an id the target kind does not have, per target kind. The 1.6.5 build has
# none (the extractor already drops rows and links it cannot resolve), so this stays empty; an entry goes here only
# with a comment saying why that id is allowed to dangle.
DANGLING_ALLOW = {"item": frozenset(), "mob": frozenset(), "map": frozenset(), "npc": frozenset()}


# --- names and reach ---------------------------------------------------------------------------------------------
def _name_problem(raw):
    """(reach, note) when the name is one the site never shows, else None. Mirrors dbdata.named()."""
    name = str(raw or "").strip()
    if not name or not REAL_NAME.search(name):
        return "unobtainable", "no name tag (empty or punctuation only)"
    if HANGUL.search(name) and not LATIN.search(name):
        return "unobtainable", "Korean-only name (untranslated KMS data)"
    if BRAND.search(name):
        return "unobtainable", "official brand name (MapleStory/Nexon/Wizet)"
    return None


def _excluded_map(map_id):
    if map_id in EXCLUDE_MAPS:
        return EXCLUDE_MAPS[map_id]
    for lo, hi, why in EXCLUDE_MAP_RANGES:
        if lo <= map_id <= hi:
            return why
    return None


def _map_reach(map_id, rec):
    """(reach, note) for one map row: GM rooms/events first, then the site's named() and placement rules."""
    ex = _excluded_map(map_id)
    if ex:
        return ex
    bad = _name_problem(rec.get("name"))
    if bad:
        return bad
    if not rec.get("mobs") and not rec.get("npcs"):
        return "unobtainable", "no mobs or NPCs placed (a page-less map on the site)"
    return "player", None


def _map_reaches(maps):
    return {int(k): _map_reach(int(k), v) for k, v in maps.items()}


def _pick(reaches):
    best = None
    for r in reaches:
        if r and (best is None or RANK[r] > RANK[best]):
            best = r
    return best


def _placement_reach(map_reach, map_ids):
    """(reach, note) from the maps an entity stands in; (None, None) when it is in none of them."""
    rs = [map_reach[m][0] for m in map_ids if m in map_reach]
    best = _pick(rs)
    if best == "player":
        return "player", None
    if best == "gm":
        return "gm", "only stands in a GM-only room"
    if best == "event":
        return "event", "only stands in maps that open while a GM runs an event"
    return None, None


def _mob_reaches(mobs, map_reach):
    out = {}
    for k, v in mobs.items():
        bad = _name_problem(v.get("name"))
        if bad:
            out[int(k)] = bad
            continue
        reach = _placement_reach(map_reach, [m.get("map_id") for m in v.get("maps") or []])
        if reach[0]:
            out[int(k)] = reach
        elif v.get("boss"):
            # bosses are summoned by an event script, so they are never placed in a map: still encounterable
            out[int(k)] = ("player", "boss: summoned by an event script rather than placed in a map")
        else:
            out[int(k)] = ("unobtainable", "not placed in any player-reachable map (script-only or unused)")
    return out


def _npc_reaches(npcs, map_reach):
    out = {}
    for k, v in npcs.items():
        bad = _name_problem(v.get("name"))
        if bad:
            out[int(k)] = bad
            continue
        reach = _placement_reach(map_reach, [m.get("map_id") for m in v.get("maps") or []])
        out[int(k)] = reach if reach[0] else ("unobtainable", "not placed in any player-reachable map")
    return out


def _item_reaches(items, mob_reach, npc_reach, db):
    reactor = _by(db.get("reactordrops"), "itemid")
    out = {}
    for k, v in items.items():
        iid = int(k)
        bad = _name_problem(v.get("name"))
        if bad:
            out[iid] = bad
            continue
        reaches = [mob_reach.get(d.get("mob_id"), (None,))[0] for d in v.get("drops") or []]
        reaches += [npc_reach.get(s.get("npc_id"), (None,))[0] for s in v.get("shops") or []]
        if v.get("quests_reward") or iid in reactor:
            reaches.append("player")
        best = _pick(reaches)
        if best == "unobtainable":
            out[iid] = ("unobtainable", "its only drops/shops are themselves unobtainable")
        else:
            out[iid] = (best, None) if best else (
                "unobtainable", "no player-reachable drop, shop, quest reward or reactor")
    return out


# --- DB exports --------------------------------------------------------------------------------------------------
def _int(v, default=None):
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def _load_db(db_root):
    """{table: [row dict]} from the TSV exports; missing files are simply absent."""
    out = {}
    if not db_root:
        return out
    for table in ("drop_data", "drop_data_global", "plife", "reactordrops", "shopitems", "shops"):
        path = os.path.join(db_root, table + ".tsv")
        if not os.path.isfile(path):
            continue
        with open(path, newline="", encoding="utf-8") as f:
            out[table] = list(csv.DictReader(f, delimiter="\t"))
    return out


def _by(rows, field):
    out = {}
    for r in rows or []:
        k = _int(r.get(field))
        if k is not None:
            out.setdefault(k, []).append(r)
    return out


def _db_src(table, key):
    return {"kind": "db-export", "snapshot": SNAPSHOT, "date": SNAPSHOT_DATE, "table": table, "key": key}


def _plife(db):
    """world 1 plife rows -> ({map id: [row]}, {(map id, npc id): (x, y)})."""
    mobs, npc_xy = {}, {}
    for r in db.get("plife") or []:
        if _int(r.get("world")) != 1:
            continue
        mid, life = _int(r.get("map")), _int(r.get("life"))
        if mid is None or life is None:
            continue
        kind = (r.get("type") or "").strip().lower()
        if kind == "m":
            mobs.setdefault(mid, []).append(r)
        elif kind == "n":
            x, y = _int(r.get("x")), _int(r.get("y"))
            if x is not None and y is not None:
                npc_xy.setdefault((mid, life), (x, y))
    return mobs, npc_xy


# --- WZ access ---------------------------------------------------------------------------------------------------
class Wz:
    """Lazy index over the WZ XML copy: file per entity, item imgdir lines, String.wz names, map onUserEnter."""

    def __init__(self, root):
        self.root = root
        self._files, self._indices, self._parsed, self._strings = {}, {}, {}, {}

    def listdir(self, rel):
        if rel not in self._files:
            try:
                names = os.listdir(os.path.join(self.root, rel))
            except OSError:
                names = []
            self._files[rel] = set(names)
        return self._files[rel]

    def digits_index(self, rel):
        """{id int: WZ-root-relative path} over a folder of "<digits>.img.xml" files."""
        if rel not in self._indices:
            out = {}
            for n in self.listdir(rel):
                if n.endswith(".img.xml") and n[:-8].isdigit():
                    out[int(n[:-8])] = rel + "/" + n
            self._indices[rel] = out
        return self._indices[rel]

    def char_files(self):
        if "Character.wz" not in self._indices:
            out = {}
            for d in EQP_DIRS:
                out.update(self.digits_index("Character.wz/" + d))
            self._indices["Character.wz"] = out
        return self._indices["Character.wz"]

    def parse(self, rel):
        if rel not in self._parsed:
            try:
                self._parsed[rel] = wzxml.parse(os.path.join(self.root, rel))
            except OSError:
                self._parsed[rel] = {}
        return self._parsed[rel]

    def strings(self, rel):
        """{id int: line of its imgdir} for a String.wz file (numeric imgdirs may sit under category dirs)."""
        if rel not in self._strings:
            out = {}

            def walk(node):
                for name, child in node["dirs"].items():
                    if name.isdigit():
                        out.setdefault(int(name), child["line"])
                    else:
                        walk(child)

            walk({"dirs": self.parse(rel)})
            self._strings[rel] = out
        return self._strings[rel]

    def mob_file(self, mob_id):
        return self.digits_index("Mob.wz").get(mob_id)

    def npc_file(self, npc_id):
        return self.digits_index("Npc.wz").get(npc_id)

    def map_file(self, map_id):
        rel = "Map.wz/Map/Map%d/%09d.img.xml" % (map_id // 100000000, map_id)
        return rel if rel.rsplit("/", 1)[1] in self.listdir("Map.wz/Map/Map%d" % (map_id // 100000000)) else None

    def item_def(self, item_id, hint):
        """(source, node) for an item's definition. Equips: Character.wz/<dir>/<8-digit>.img.xml line 1.
        Everything else: the Item.wz file holding its imgdir, with the imgdir's line."""
        rel = self.char_files().get(item_id)
        if rel:
            return {"kind": "wz", "path": rel, "line": 1}, None
        dirs = ([hint] if hint in ITEM_DIRS else []) + [d for d in ITEM_DIRS if d != hint]
        for d in dirs:
            for fn in ("%08d.img.xml" % item_id, "%04d.img.xml" % (item_id // 10000)):
                if fn not in self.listdir("Item.wz/" + d):
                    continue
                rel = "Item.wz/%s/%s" % (d, fn)
                node = self.parse(rel).get("%08d" % item_id)
                if node:
                    return {"kind": "wz", "path": rel, "line": node["line"]}, node
        return None, None

    def on_user_enter(self, map_id):
        """The map's info/onUserEnter script name, or None."""
        rel = self.map_file(map_id)
        if not rel:
            return None
        try:
            with open(os.path.join(self.root, rel), "rb") as f:
                data = f.read()
        except OSError:
            return None
        m = ON_USER_ENTER.search(data)
        return m.group(1).decode("utf-8", "replace") or None if m else None


_WZ = {}


def _wz(root):
    if root not in _WZ:
        _WZ[root] = Wz(root)
    return _WZ[root]


def _scripts(repo_root, kind):
    try:
        return os.listdir(os.path.join(repo_root, "scripts", kind))
    except OSError:
        return []


def _srcs(wz, string_rel, ident, extra=()):
    """The WZ sources for one row: the first String.wz line that names the entity, after the caller's files."""
    out = list(extra)
    for rel in ([string_rel] if isinstance(string_rel, str) else string_rel):
        line = wz.strings(rel).get(ident)
        if line:
            out.append({"kind": "wz", "path": rel, "line": line})
            break
    return out


# --- builders ----------------------------------------------------------------------------------------------------
def build_npcs(hs, wz_root, db_root=None, repo_root=None):
    """-> {npc id string: row}."""
    wz, repo_root = _wz(wz_root), repo_root or ROOT
    db = _load_db(db_root)
    map_reach = _map_reaches(hs.get("maps") or {})
    npc_reach = _npc_reaches(hs.get("npcs") or {}, map_reach)
    npc_xy = _plife(db)[1]
    scripts = set(_scripts(repo_root, "npc"))
    rows = {}
    for k, v in (hs.get("npcs") or {}).items():
        nid = int(k)
        reach, note = npc_reach[nid]
        maps = []
        for m in v.get("maps") or []:
            mid = m.get("map_id")
            if mid in map_reach and not any(x["ref"]["id"] == mid for x in maps):
                entry = {"ref": ref("map", mid)}
                xy = npc_xy.get((mid, nid))
                if xy:
                    entry["x"], entry["y"] = xy
                maps.append(entry)
        maps.sort(key=lambda e: e["ref"]["id"])
        shop, seen = [], set()
        for s in v.get("shop_items") or []:
            pair = (s.get("item_id"), s.get("price"))
            if pair[0] is None or pair in seen:
                continue
            seen.add(pair)
            shop.append({"ref": ref("item", s["item_id"]), "price": s.get("price")})
        shop.sort(key=lambda e: (e["ref"]["id"], e["price"] if e["price"] is not None else -1))
        extra = []
        rel = wz.npc_file(nid)
        if rel:
            extra.append({"kind": "wz", "path": rel, "line": 1})
        if shop:
            extra.append(_db_src("shopitems", nid))
        if any("x" in m for m in maps):
            extra.append(_db_src("plife", nid))
        src = _srcs(wz, "String.wz/Npc.img.xml", nid, extra)
        row = {
            "id": nid, "name": plain(v.get("name")), "reach": reach,
            "maps": maps, "shop": shop,
            "quests_start": _refs("quest", [q.get("quest_id") for q in v.get("quests_start") or []]),
            "quests_end": _refs("quest", [q.get("quest_id") for q in v.get("quests_end") or []]),
            "script": ref("script", f"npc/{nid}") if f"{nid}.js" in scripts else None,
            "src": src or [{"kind": "wz", "path": "String.wz/Npc.img.xml", "line": None}],
        }
        _finish(row, v, reach, note)
        rows[k] = row
    return rows


def build_items(hs, wz_root, db_root=None, repo_root=None):
    """-> {item id string: row}."""
    wz, repo_root = _wz(wz_root), repo_root or ROOT
    db = _load_db(db_root)
    map_reach = _map_reaches(hs.get("maps") or {})
    mob_reach = _mob_reaches(hs.get("mobs") or {}, map_reach)
    npc_reach = _npc_reaches(hs.get("npcs") or {}, map_reach)
    item_reach = _item_reaches(hs.get("items") or {}, mob_reach, npc_reach, db)
    reactor = _by(db.get("reactordrops"), "itemid")
    item_scripts = set(_scripts(repo_root, "item"))
    rows = {}
    for k, v in (hs.get("items") or {}).items():
        iid = int(k)
        reach, note = item_reach[iid]
        stats = dict(v.get("stats") or {})
        price = stats.pop("price", None) if isinstance(stats.get("price"), int) else None
        drops, seen = [], set()
        for d in v.get("drops") or []:
            if d.get("mob_id") in (None, 0):
                continue
            row = {"ref": ref("mob", d["mob_id"]), "chance": d.get("chance"), "min": d.get("min_qty"),
                   "max": d.get("max_qty")}
            if d.get("quest_id"):
                row["quest"] = ref("quest", d["quest_id"])
            key = json.dumps(row, sort_keys=True)
            if key not in seen:
                seen.add(key)
                drops.append(row)
        drops.sort(key=lambda e: (e["ref"]["id"], e.get("chance") or 0, e.get("min") or 0))
        shops, seen = [], set()
        for s in v.get("shops") or []:
            if s.get("npc_id") is None:
                continue
            pair = (s["npc_id"], s.get("price"))
            if pair in seen:
                continue
            seen.add(pair)
            shops.append({"ref": ref("npc", s["npc_id"]), "price": s.get("price")})
        shops.sort(key=lambda e: e["ref"]["id"])
        global_drops = [{"continent": d.get("continent"), "chance": d.get("chance"), "min": d.get("min_qty"),
                         "max": d.get("max_qty")} for d in v.get("global_drops") or []]
        global_drops.sort(key=lambda d: (d["continent"] if d["continent"] is not None else -99, d["chance"] or 0))
        reactor_drops = [{"reactor": _int(r.get("reactorid")), "chance": _int(r.get("chance")),
                          "quest": ref("quest", _int(r["questid"])) if (_int(r.get("questid")) or 0) > 0 else None}
                         for r in reactor.get(iid, [])]
        reactor_drops.sort(key=lambda d: (d["reactor"] or 0, d["chance"] or 0))
        source, node = wz.item_def(iid, v.get("type"))
        extra = [source] if source else []
        scripts = []
        script_name = ((node or {}).get("dirs", {}).get("info") or {}).get("props", {}).get("script")
        if script_name and f"{script_name}.js" in item_scripts:
            scripts.append(ref("script", f"item/{script_name}"))
        if drops:
            extra.append(_db_src("drop_data", iid))
        if global_drops:
            extra.append(_db_src("drop_data_global", iid))
        if shops:
            extra.append(_db_src("shopitems", iid))
        if reactor_drops:
            extra.append(_db_src("reactordrops", iid))
        if v.get("quests_reward") or v.get("quests_require"):
            extra.append({"kind": "wz", "path": "Quest.wz/Act.img.xml", "line": None})
        string_files = _item_string_files(v.get("type"))
        src = _srcs(wz, string_files, iid, extra)
        if not src:
            src = [{"kind": "wz", "path": string_files[0], "line": None}]
        row = {
            "id": iid, "name": plain(v.get("name")), "desc": plain(v.get("desc")), "type": v.get("type"),
            "req_level": v.get("req_level"), "req_job": v.get("req_job"), "stats": stats, "price": price,
            "drops": drops, "global_drops": global_drops, "shops": shops,
            "quest_reward": _refs("quest", [q.get("quest_id") for q in v.get("quests_reward") or []]),
            "quest_require": _refs("quest", [q.get("quest_id") for q in v.get("quests_require") or []]),
            "scripts": scripts, "reactor_drops": reactor_drops, "reach": reach, "src": src,
        }
        if "slums_changed" in v:
            row["slums_changed"] = v["slums_changed"]
        _finish(row, v, reach, note)
        rows[k] = row
    return rows


def build_mobs(hs, wz_root, db_root=None, repo_root=None):
    """-> {mob id string: row}."""
    wz = _wz(wz_root)
    db = _load_db(db_root)
    map_reach = _map_reaches(hs.get("maps") or {})
    mob_reach = _mob_reaches(hs.get("mobs") or {}, map_reach)
    rows = {}
    for k, v in (hs.get("mobs") or {}).items():
        mid = int(k)
        reach, note = mob_reach[mid]
        maps, seen = [], set()
        for m in v.get("maps") or []:
            if m.get("map_id") in map_reach and m["map_id"] not in seen:
                seen.add(m["map_id"])
                maps.append({"ref": ref("map", m["map_id"]), "count": m.get("count")})
        maps.sort(key=lambda e: e["ref"]["id"])
        drops, seen = [], set()
        for d in v.get("drops") or []:
            if d.get("item_id") in (None, 0):
                continue
            row = {"ref": ref("item", d["item_id"]), "chance": d.get("chance"), "min": d.get("min_qty"),
                   "max": d.get("max_qty")}
            if d.get("quest_id"):
                row["quest"] = ref("quest", d["quest_id"])
            key = json.dumps(row, sort_keys=True)
            if key not in seen:
                seen.add(key)
                drops.append(row)
        drops.sort(key=lambda e: (e["ref"]["id"], e.get("chance") or 0, e.get("min") or 0))
        extra = []
        rel = wz.mob_file(mid)
        if rel:
            extra.append({"kind": "wz", "path": rel, "line": 1})
        if drops:
            extra.append(_db_src("drop_data", mid))
        row = {
            "id": mid, "name": plain(v.get("name")), "level": v.get("level"), "hp": v.get("hp"),
            "mp": v.get("mp"), "exp": v.get("exp"), "boss": bool(v.get("boss")),
            "maps": maps, "drops": drops, "reach": reach,
            "src": _srcs(wz, "String.wz/Mob.img.xml", mid, extra)
                   or [{"kind": "wz", "path": "String.wz/Mob.img.xml", "line": None}],
        }
        _finish(row, v, reach, note)
        rows[k] = row
    return rows


def build_maps(hs, wz_root, db_root=None, repo_root=None):
    """-> {map id string: row}."""
    wz, repo_root = _wz(wz_root), repo_root or ROOT
    db = _load_db(db_root)
    map_reach = _map_reaches(hs.get("maps") or {})
    plife_mobs = _plife(db)[0]
    portal_scripts = set(_scripts(repo_root, "portal"))
    enter_scripts = set(_scripts(repo_root, "map/onUserEnter"))
    rows = {}
    for k, v in (hs.get("maps") or {}).items():
        mid = int(k)
        reach, note = map_reach[mid]
        mobs, seen = [], set()
        for m in v.get("mobs") or []:
            if m.get("mob_id") in (None, 0) or m["mob_id"] in seen:
                continue
            seen.add(m["mob_id"])
            mobs.append({"ref": ref("mob", m["mob_id"]), "count": m.get("count")})
        mobs.sort(key=lambda e: e["ref"]["id"])
        npcs, seen = [], set()
        for n in v.get("npcs") or []:
            if n.get("npc_id") in (None, 0) or n["npc_id"] in seen:
                continue
            seen.add(n["npc_id"])
            npcs.append({"ref": ref("npc", n["npc_id"]), "count": n.get("count")})
        npcs.sort(key=lambda e: e["ref"]["id"])
        portals, seen = [], set()
        for p in v.get("portals") or []:
            target = p.get("target_map_id")
            entry = {
                "name": p.get("name"), "type": p.get("type"),
                "target_map": ref("map", target) if target is not None and 0 <= target != 999999999 else None,
                "target_portal": p.get("target_portal") or None,
                "script": ref("script", f"portal/{p['name']}")
                if p.get("type") == 2 and f"{p.get('name')}.js" in portal_scripts else None,
            }
            key = json.dumps(entry, sort_keys=True)
            if key not in seen:
                seen.add(key)
                portals.append(entry)
        portals.sort(key=lambda e: (e["name"] or "", e["type"] if e["type"] is not None else -1))
        plife, seen = [], set()
        for r in plife_mobs.get(mid, []):
            life = _int(r.get("life"))
            entry = {"ref": ref("mob", life), "x": _int(r.get("x")), "y": _int(r.get("y")),
                     "mobtime": _int(r.get("mobtime"), 0)}
            key = json.dumps(entry, sort_keys=True)
            if life is not None and key not in seen:
                seen.add(key)
                plife.append(entry)
        plife.sort(key=lambda e: (e["ref"]["id"], e["x"] if e["x"] is not None else 0))
        enter = wz.on_user_enter(mid)
        extra = []
        rel = wz.map_file(mid)
        if rel:
            extra.append({"kind": "wz", "path": rel, "line": 1})
        if plife:
            extra.append(_db_src("plife", mid))
        row = {
            "id": mid, "name": plain(v.get("name")), "street": STREETS.get(mid) or plain(v.get("street")),
            "mobs": mobs, "npcs": npcs, "portals": portals,
            "on_user_enter": ref("script", f"map/onUserEnter/{enter}")
            if enter and f"{enter}.js" in enter_scripts else None,
            "plife": plife, "reach": reach,
            "src": _srcs(wz, "String.wz/Map.img.xml", mid, extra)
                   or [{"kind": "wz", "path": "String.wz/Map.img.xml", "line": None}],
        }
        _finish(row, v, reach, note)
        rows[k] = row
    return rows


# --- shared row bits ---------------------------------------------------------------------------------------------
def _refs(kind, ids):
    return [ref(kind, i) for i in sorted({i for i in ids if isinstance(i, int)})]


_STRING_FILES = {d: "String.wz/Eqp.img.xml" for d in EQP_DIRS}
_STRING_FILES.update({
    "Consume": "String.wz/Consume.img.xml", "Etc": "String.wz/Etc.img.xml",
    "Install": "String.wz/Ins.img.xml", "Cash": "String.wz/Cash.img.xml",
    "Pet": "String.wz/Pet.img.xml", "Special": "String.wz/Special.img.xml",
})


def _string_file(item_type):
    """The String.wz file that names an item of this type (equips are named in Eqp.img.xml)."""
    return _STRING_FILES.get(item_type, "String.wz/Eqp.img.xml")


def _item_string_files(item_type):
    """The guessed String.wz file first, then the others: an item whose type the extractor could not place may be
    named in any of them (its definition is missing, so nothing else says which)."""
    first = _string_file(item_type)
    return [first] + [f for f in dict.fromkeys(_STRING_FILES.values()) if f != first]


def _finish(row, rec, reach, note):
    """lang / name_note / reach_note, common to every kind."""
    name = str(rec.get("name") or "")
    for text in (name, rec.get("desc"), rec.get("name_qualifier"), (rec.get("stats") or {}).get("name")):
        if isinstance(text, str) and HANGUL.search(text):
            row["lang"] = "ko"
    if rec.get("name_qualifier"):
        row["name_note"] = plain(rec["name_qualifier"])
    if note:
        row["reach_note"] = note


def build_all(hs, wz_root, db_root=None, repo_root=None):
    return {
        "npcs": build_npcs(hs, wz_root, db_root, repo_root),
        "items": build_items(hs, wz_root, db_root, repo_root),
        "mobs": build_mobs(hs, wz_root, db_root, repo_root),
        "maps": build_maps(hs, wz_root, db_root, repo_root),
    }


def load_hs(hs_dir):
    out = {}
    for kind in ("npcs", "items", "mobs", "maps"):
        with open(os.path.join(hs_dir, kind + ".json"), encoding="utf-8") as f:
            out[kind] = json.load(f)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description="npcs/items/mobs/maps atlas files (docs/atlas/SCHEMA.md)")
    ap.add_argument("--hs", required=True, help="folder holding the extractor's items.json, mobs.json, maps.json, npcs.json")
    ap.add_argument("--wz", required=True, help="WZ XML root (holds Quest.wz/, Map.wz/, ...)")
    ap.add_argument("--db", help="folder of TSV table exports (drop_data, shopitems, plife, ...)")
    ap.add_argument("--out", required=True, help="output folder for npcs.json, items.json, mobs.json, maps.json")
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    for kind, rows in build_all(load_hs(args.hs), args.wz, args.db).items():
        path = os.path.join(args.out, kind + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(rows, f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write("\n")
        print(f"{kind}: {len(rows)} rows -> {path}")


if __name__ == "__main__":
    main()
