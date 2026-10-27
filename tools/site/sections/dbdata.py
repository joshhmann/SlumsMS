"""The Hidden Street dataset as the site uses it: tools/hiddenstreet/build_data.py output (items, mobs, maps, npcs,
quests .json), cut down to what a player can actually reach. Shared by the database section and tools/site/icons.py.

Reachable, for v1:
    map    has a name and something on it (monsters or NPCs) and is not GM-only or event-only (EXCLUDE_MAPS);
           "a name" never means a Korean-only one, which is untranslated, unreleased content in this data, nor a
           placeholder like "?"
    mob    has a name and spawns on a reachable map
    npc    has a name and stands on a reachable map
    quest  has a name and starts at a reachable NPC
    item   has a name and is dropped by a reachable mob, sold by a reachable NPC, or used by a reachable quest
Cross-links to anything outside the reachable set are dropped, so every link on the site has a page behind it.
"""
import json
import os
import re

KINDS = ["items", "mobs", "maps", "npcs", "quests"]
# the cleaned 1.6.0 dataset (dedupe, event-variant names, Korean names left out, drop-rate rules in meta.json), kept in
# the repo so the whole site builds on any machine; refresh it by copying a new build_data.py output over it
DEFAULT_DIR = os.environ.get("SLUMS_HS_DATA", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
                                                           "data", "site", "hiddenstreet"))


def _load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


HANGUL = re.compile(r"[\uac00-\ud7af\u1100-\u11ff\u3130-\u318f]")
# Official names the public site never shows (owner, 2026-10-26): GM gear, game cards and official event content.
BRAND = re.compile(r"maple\s?story|nexon|wizet", re.I)
REAL_NAME = re.compile(r"[^\W_]")  # at least one letter or digit


def named(v):
    """Has a name a player would see. Korean-only names are untranslated, unreleased content in this data; names
    with the official brand in them are GM, game-card or official event content."""
    name = (v.get("name") or "").strip()
    # "?", "???", "-" or "." is a placeholder, not a name (NPC 9102100 is "?"): unnamed, like an empty one
    return bool(REAL_NAME.search(name)) and not HANGUL.search(name) and not BRAND.search(name)


# Maps a player cannot walk into on SlumsMS, left out with everything that only lives there (release lead's audit,
# 2026-10-27). The rule: GM-only rooms, and event maps that only open while a GM runs the event or that no script or
# portal leads to. Party quest, internet cafe and job-test maps stay: players get in through an NPC or a portal.
EXCLUDE_MAPS = {
    180000000: "GM Room (its data calls it Ellinia; the GM styling NPCs stand there)",
    706030000: "9th Anniversary Event: an official event map that nothing leads to",
    820000000: "Event Hall: a later version's event map that nothing leads to",
    867145000: "Event Hall (Extravagameza!): a later version's event map that nothing leads to",
}
EXCLUDE_MAP_RANGES = [
    (109000000, 109999999, "GM events (Find the Jewel, Coconut Harvest, Sheep Ranch, OX Quiz, Ola Ola, Fitness and "
                           "their reward and exit rooms): open only while a GM runs one"),
]


# street names the data gets wrong for the site's grouping: the Free Market Entrance's and Base of Roots' data street
# is "Hidden Street"; both go under "Free Market", next to each other
STREETS = {910000000: "Free Market", 993910000: "Free Market"}


def excluded_map(map_id):
    return map_id in EXCLUDE_MAPS or any(lo <= map_id <= hi for lo, hi, _ in EXCLUDE_MAP_RANGES)


# NPC placements a server migration adds that the dataset's DB export (plife) predates: (map, npc). Once
# build_data.py runs against a DB with the migration applied, the dataset has them and this list changes nothing.
EXTRA_NPC_PLACEMENTS = [
    (910000000, 1092017),  # 034-site-board-npc: the Buy/Sell board (Anonymous Merchant) at the Free Market Entrance
]


def _place_extra_npcs(raw):
    for map_id, npc_id in EXTRA_NPC_PLACEMENTS:
        mp, npc = raw["maps"].get(str(map_id)), raw["npcs"].get(str(npc_id))
        if mp is None or npc is None:
            continue
        if not any(n.get("npc_id") == npc_id for n in mp.get("npcs") or []):
            mp["npcs"] = list(mp.get("npcs") or []) + [{"npc_id": npc_id, "name": npc.get("name"), "count": 1}]
        if not any(m.get("map_id") == map_id for m in npc.get("maps") or []):
            npc["maps"] = list(npc.get("maps") or []) + [
                {"map_id": map_id, "name": mp.get("name"), "street": mp.get("street")}]


# Items with no Slums Street page (no drop, shop or quest in the dataset: gachapon, bot gear, old event items) that
# still turn up on the market. Their icons are fetched and published anyway (icons.py, database.copy_icons), so the
# market can show them. One id per line; # starts a comment.
EXTRA_ICON_ITEMS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "site",
                                "extra-icon-items.txt")


def extra_icon_items(path=EXTRA_ICON_ITEMS):
    if not os.path.isfile(path):
        return set()
    with open(path, encoding="utf-8") as f:
        return {int(t) for line in f for t in line.split("#", 1)[0].split() if t.isdigit()}


def placeholder(v):
    """A name that is only punctuation ("?", "????", "..."): a record that exists but has no name tag."""
    name = (v.get("name") or "").strip()
    return bool(name) and not REAL_NAME.search(name)


def _by(rows, key):
    """One row per key (the first, which build_data.py writes for the main spawn or listing)."""
    seen, out = set(), []
    for r in rows:
        if r.get(key) not in seen:
            seen.add(r.get(key))
            out.append(r)
    return out


def _dedupe(rows):
    seen, out = set(), []
    for r in rows or []:
        key = json.dumps(r, sort_keys=True)
        if key not in seen:
            seen.add(key)
            out.append(r)
    return out


def reachable(raw):
    """raw: {kind: {id: record}} as build_data.py writes it -> the same shape, filtered, with ids as ints."""
    _place_extra_npcs(raw)
    maps = {int(k): v for k, v in raw["maps"].items()
            if named(v) and (v.get("mobs") or v.get("npcs")) and not excluded_map(int(k))}
    for map_id, street in STREETS.items():
        if map_id in maps:
            maps[map_id] = dict(maps[map_id], street=street)
    mobs = {int(k): v for k, v in raw["mobs"].items()
            if named(v) and any(m.get("map_id") in maps for m in v.get("maps") or [])}
    npcs = {int(k): v for k, v in raw["npcs"].items()
            if named(v) and any(m.get("map_id") in maps for m in v.get("maps") or [])}
    # an NPC whose name is only "?" or "????" gets no page, but it can still give real quests (2032000 in Orbis
    # starts five): those quests stay, and their pages say the NPC has no name tag
    nameless = {int(k) for k, v in raw["npcs"].items()
                if placeholder(v) and any(m.get("map_id") in maps for m in v.get("maps") or [])}
    givers = set(npcs) | nameless
    quests = {int(k): v for k, v in raw["quests"].items()
              if named(v) and (v.get("start_npc") or {}).get("id") in givers}
    items = {}
    for k, v in raw["items"].items():
        if not named(v):
            continue
        drops = [d for d in _dedupe(v.get("drops")) if d.get("mob_id") in mobs]
        shops = [s for s in _dedupe(v.get("shops")) if s.get("npc_id") in npcs]
        rewards = [q for q in _dedupe(v.get("quests_reward")) if q.get("quest_id") in quests]
        needs = [q for q in _dedupe(v.get("quests_require")) if q.get("quest_id") in quests]
        if drops or shops or rewards or needs:
            items[int(k)] = dict(v, drops=drops, shops=shops, quests_reward=rewards, quests_require=needs)
    # trim every other cross-link to the reachable set
    for v in mobs.values():
        v["maps"] = _by([m for m in _dedupe(v.get("maps")) if m.get("map_id") in maps], "map_id")
        v["drops"] = [d for d in _dedupe(v.get("drops")) if d.get("item_id") == 0 or d.get("item_id") in items]
    for v in maps.values():
        v["mobs"] = [m for m in _dedupe(v.get("mobs")) if m.get("mob_id") in mobs]
        v["npcs"] = [n for n in _dedupe(v.get("npcs")) if n.get("npc_id") in npcs]
        v["portals"] = [p for p in _dedupe(v.get("portals")) if p.get("target_map_id") in maps]
    for v in npcs.values():
        # several copies of one town (same name and street, different ids): list it once, as the lowest id
        here = sorted((m for m in _dedupe(v.get("maps")) if m.get("map_id") in maps), key=lambda m: m["map_id"])
        v["maps"] = _by([dict(m, place=(maps[m["map_id"]].get("street"), maps[m["map_id"]]["name"])) for m in here],
                        "place")
        v["shop_items"] = [s for s in _dedupe(v.get("shop_items")) if s.get("item_id") in items]
        v["quests_start"] = [q for q in _dedupe(v.get("quests_start")) if q.get("quest_id") in quests]
        v["quests_end"] = [q for q in _dedupe(v.get("quests_end")) if q.get("quest_id") in quests]
    out = {"items": items, "mobs": mobs, "maps": maps, "npcs": npcs, "quests": quests}
    # show the generator's display name ("Stirge (event)") where it has one; filters above used the plain name
    for kind in out.values():
        for v in kind.values():
            if v.get("display_name"):
                v["name"] = v["display_name"]
    return out


_CACHE = {}


def load(data_dir=None):
    """The reachable dataset; loaded once per build (the guide's cross-links and the database share it)."""
    data_dir = data_dir or DEFAULT_DIR
    if data_dir not in _CACHE:
        _CACHE[data_dir] = _load_dir(data_dir)
    return _CACHE[data_dir]


def _load_dir(data_dir):
    missing = [k for k in KINDS if not os.path.isfile(os.path.join(data_dir, k + ".json"))]
    if missing:
        raise SystemExit(f"{data_dir}: no {', '.join(m + '.json' for m in missing)} "
                         "(run tools/hiddenstreet/build_data.py, or pass --hs-data)")
    return reachable({k: _load(os.path.join(data_dir, k + ".json")) for k in KINDS})
