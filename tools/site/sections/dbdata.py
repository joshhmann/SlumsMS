"""The Hidden Street dataset as the site uses it: tools/hiddenstreet/build_data.py output (items, mobs, maps, npcs,
quests .json), cut down to what a player can actually reach. Shared by the database section and tools/site/icons.py.

Reachable, for v1:
    map    has a name and something on it (monsters or NPCs)
    mob    has a name and spawns on a reachable map
    npc    has a name and stands on a reachable map
    quest  has a name and starts at a reachable NPC
    item   has a name and is dropped by a reachable mob, sold by a reachable NPC, or used by a reachable quest
Cross-links to anything outside the reachable set are dropped, so every link on the site has a page behind it.
"""
import json
import os

KINDS = ["items", "mobs", "maps", "npcs", "quests"]
DEFAULT_DIR = os.environ.get("SLUMS_HS_DATA", "/root/work/SlumsMS-152-site/data/hiddenstreet")


def _load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


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
    maps = {int(k): v for k, v in raw["maps"].items() if v.get("name") and (v.get("mobs") or v.get("npcs"))}
    mobs = {int(k): v for k, v in raw["mobs"].items()
            if v.get("name") and any(m.get("map_id") in maps for m in v.get("maps") or [])}
    npcs = {int(k): v for k, v in raw["npcs"].items()
            if v.get("name") and any(m.get("map_id") in maps for m in v.get("maps") or [])}
    quests = {int(k): v for k, v in raw["quests"].items()
              if v.get("name") and (v.get("start_npc") or {}).get("id") in npcs}
    items = {}
    for k, v in raw["items"].items():
        if not v.get("name"):
            continue
        drops = [d for d in _dedupe(v.get("drops")) if d.get("mob_id") in mobs]
        shops = [s for s in _dedupe(v.get("shops")) if s.get("npc_id") in npcs]
        rewards = [q for q in _dedupe(v.get("quests_reward")) if q.get("quest_id") in quests]
        needs = [q for q in _dedupe(v.get("quests_require")) if q.get("quest_id") in quests]
        if drops or shops or rewards or needs:
            items[int(k)] = dict(v, drops=drops, shops=shops, quests_reward=rewards, quests_require=needs)
    # trim every other cross-link to the reachable set
    for v in mobs.values():
        v["maps"] = [m for m in _dedupe(v.get("maps")) if m.get("map_id") in maps]
        v["drops"] = [d for d in _dedupe(v.get("drops")) if d.get("item_id") == 0 or d.get("item_id") in items]
    for v in maps.values():
        v["mobs"] = [m for m in _dedupe(v.get("mobs")) if m.get("mob_id") in mobs]
        v["npcs"] = [n for n in _dedupe(v.get("npcs")) if n.get("npc_id") in npcs]
        v["portals"] = [p for p in _dedupe(v.get("portals")) if p.get("target_map_id") in maps]
    for v in npcs.values():
        v["maps"] = [m for m in _dedupe(v.get("maps")) if m.get("map_id") in maps]
        v["shop_items"] = [s for s in _dedupe(v.get("shop_items")) if s.get("item_id") in items]
        v["quests_start"] = [q for q in _dedupe(v.get("quests_start")) if q.get("quest_id") in quests]
        v["quests_end"] = [q for q in _dedupe(v.get("quests_end")) if q.get("quest_id") in quests]
    return {"items": items, "mobs": mobs, "maps": maps, "npcs": npcs, "quests": quests}


def load(data_dir=None):
    data_dir = data_dir or DEFAULT_DIR
    missing = [k for k in KINDS if not os.path.isfile(os.path.join(data_dir, k + ".json"))]
    if missing:
        raise SystemExit(f"{data_dir}: no {', '.join(m + '.json' for m in missing)} "
                         "(run tools/hiddenstreet/build_data.py, or pass --hs-data)")
    return reachable({k: _load(os.path.join(data_dir, k + ".json")) for k in KINDS})
