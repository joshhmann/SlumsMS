#!/usr/bin/env python3
"""Quest data integrity checker: every quest in the server's Quest.wz against the rest of the game data.

    python3 tools/questcheck/quest_check.py --wz <server wz dir> [--scripts scripts] \
        [--db-dir <dir with drop_data.tsv, reactordrops.tsv, shopitems.tsv, drop_global.tsv, plife.tsv>] \
        [--cosmic-wz <stock Cosmic wz dir>] [--character-list <file>] [--json out.json] [--md out.md]

What it checks, for every quest in Check.img / Act.img:

  * references that do not exist: items, NPCs, mobs, maps, skills, jobs, prerequisite / next quests
  * quests that can never start: lvmin above the level cap, lvmin > lvmax, a start NPC that is on no map,
    a prerequisite quest that does not exist or itself can never be completed, an end date in the past
    (expired event quests are reported separately, they are expected)
  * quests that can never be completed: a required item that nothing gives (no mob or reactor drop, no
    shop, no quest reward, no script literal, no Maker recipe), a required mob that spawns nowhere, a
    complete NPC that is on no map, a start/end script that has no scripts/quest/<id>.js
  * rewards that point at missing items / skills / quests
  * with --cosmic-wz, which quests' data differs from stock Cosmic (a lead for MapleRoot edits)

The DB exports are plain `mysql -N -B` output (tab separated, no header), read-only SELECTs:

    drop_data.tsv      SELECT dropperid,itemid,questid,chance FROM drop_data
    reactordrops.tsv   SELECT reactorid,itemid,questid,chance FROM reactordrops
    drop_global.tsv    SELECT itemid,questid,chance FROM drop_data_global
    shopitems.tsv      SELECT s.npcid,i.itemid FROM shopitems i JOIN shops s ON s.shopid=i.shopid
    plife.tsv          SELECT life,type,map FROM plife

Equips live in Character.wz, which is large; --character-list is a file of its *.img.xml basenames, used when
the wz dir has no Character.wz. The findings are leads, not verdicts: an item can also come from a code path
(a Java reward table, a gachapon) that this tool cannot see.
"""
import argparse
import collections
import datetime
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

LEVEL_CAP = 200
ITEM_DIRS = ("Consume", "Install", "Etc", "Cash")
SCRIPT_LITERAL = re.compile(r"(?<![\d.])(\d{7})(?![\d.])")
QUEST_LITERAL = re.compile(r"(?<![\d.])(\d{4,5})(?![\d.])")
PROGRESS_CALL = re.compile(r"setQuestProgress\s*\(([^)]*)\)")

# Quest-only mob ids that exist only in String.wz: Character.raiseQuestMobCount credits them when the real mobs die.
# 9101003 (Lord Balrog) is credited by scripts/event/BalrogBattle*.js when the Balrog fight is cleared.
QUEST_MOB_ALIASES = {9101000: (1110100, 1110130), 9101001: (2230101, 2230131), 9101002: (1140100, 1140130),
                     9101003: (8830003, 8830010)}

# severities: ERROR = a player can be stuck or lose a reward; WARN = suspicious; INFO = expected / FYI
ERROR, WARN, INFO = "ERROR", "WARN", "INFO"


def ival(text, default=None):
    try:
        return int(str(text).strip())
    except (TypeError, ValueError):
        return default


def children(el):
    return {c.get("name"): c for c in el} if el is not None else {}


def leaf(el, name, default=None):
    for c in el if el is not None else ():
        if c.get("name") == name:
            return c.get("value", default)
    return default


def parse(path):
    return ET.parse(path).getroot()


def map_life(path):
    """[(type, id)] of the NPCs ('n') and mobs ('m') placed in one map's XML."""
    out = []
    for c in parse(path):
        if c.get("name") == "life":
            for e in c:
                t, i = leaf(e, "type"), ival(leaf(e, "id"))
                if t in ("n", "m") and i is not None:
                    out.append((t, i))
    return out


# ---------------------------------------------------------------- game data

class GameData:
    def __init__(self):
        self.items = set()
        self.npcs = set()
        self.mobs = set()
        self.maps = set()
        self.skills = set()
        self.jobs = set()
        self.npc_maps = collections.defaultdict(set)    # npc -> maps it is placed on
        self.mob_maps = collections.defaultdict(set)
        self.mob_revives = set()                         # mobs summoned by other mobs on death
        self.maker_outputs = set()
        self.item_names = {}
        self.npc_names = {}
        self.mob_names = {}
        self.quest_names = {}
        self.quest_info = {}
        self.check = {}
        self.act = {}
        self.has_items = self.has_npcs = self.has_mobs = self.has_maps = self.has_skills = False

    def item_name(self, i):
        return self.item_names.get(i, "?")


def load_wz(wz, character_list=None, with_maps=True):
    g = GameData()
    item_root = os.path.join(wz, "Item.wz")
    if os.path.isdir(item_root):
        g.has_items = True
        for d in ITEM_DIRS + ("Special",):
            p = os.path.join(item_root, d)
            if not os.path.isdir(p):
                continue
            for f in os.listdir(p):
                if f.endswith(".img.xml"):
                    for c in parse(os.path.join(p, f)):
                        i = ival(c.get("name"))
                        if i is not None:
                            g.items.add(i)
        pet = os.path.join(item_root, "Pet")
        if os.path.isdir(pet):
            g.items.update(ival(f.split(".")[0]) for f in os.listdir(pet) if f.endswith(".img.xml"))
    char_dir = os.path.join(wz, "Character.wz")
    if os.path.isdir(char_dir):
        for _, _, files in os.walk(char_dir):
            g.items.update(ival(f.split(".")[0]) for f in files if f.endswith(".img.xml") and f[0].isdigit())
    elif character_list:
        with open(character_list) as fh:
            g.items.update(ival(l.split(".")[0]) for l in fh if l[:1].isdigit())
    g.items.discard(None)

    for kind, attr in (("Npc.wz", "npcs"), ("Mob.wz", "mobs")):
        p = os.path.join(wz, kind)
        if os.path.isdir(p):
            setattr(g, "has_" + attr, True)
            getattr(g, attr).update(ival(f.split(".")[0]) for f in os.listdir(p) if f.endswith(".img.xml"))
            getattr(g, attr).discard(None)
    mob_dir = os.path.join(wz, "Mob.wz")
    if os.path.isdir(mob_dir):
        for f in os.listdir(mob_dir):
            if not f.endswith(".img.xml"):
                continue
            with open(os.path.join(mob_dir, f), encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            m = re.search(r'<imgdir name="revive">(.*?)</imgdir>', text, re.S)
            if m:
                g.mob_revives.update(int(v) for v in re.findall(r'value="(\d+)"', m.group(1)))

    map_root = os.path.join(wz, "Map.wz", "Map")
    if os.path.isdir(map_root):
        g.has_maps = True
        for d in os.listdir(map_root):
            p = os.path.join(map_root, d)
            if not (d.startswith("Map") and os.path.isdir(p)):
                continue
            for f in os.listdir(p):
                if not f.endswith(".img.xml"):
                    continue
                mid = ival(f.split(".")[0])
                g.maps.add(mid)
                if not with_maps:
                    continue
                for t, lid in map_life(os.path.join(p, f)):
                    (g.npc_maps if t == "n" else g.mob_maps)[lid].add(mid)

    skill_root = os.path.join(wz, "Skill.wz")
    if os.path.isdir(skill_root):
        g.has_skills = True
        for f in os.listdir(skill_root):
            stem = f.split(".")[0]
            if not (f.endswith(".img.xml") and stem.isdigit()):
                continue
            g.jobs.add(int(stem))
            root = parse(os.path.join(skill_root, f))
            for c in root:
                if c.get("name") == "skill":
                    g.skills.update(ival(s.get("name")) for s in c)
        g.skills.discard(None)

    make = os.path.join(wz, "Etc.wz", "ItemMake.img.xml")
    if os.path.isfile(make):
        for grp in parse(make):
            for rec in grp:
                i = ival(rec.get("name"))
                if i:
                    g.maker_outputs.add(i)

    strings = os.path.join(wz, "String.wz")
    for f, target in (("Consume.img.xml", "item"), ("Ins.img.xml", "item"), ("Cash.img.xml", "item"),
                      ("Pet.img.xml", "item"), ("Etc.img.xml", "item"), ("Eqp.img.xml", "item"),
                      ("Npc.img.xml", "npc"), ("Mob.img.xml", "mob")):
        p = os.path.join(strings, f)
        if not os.path.isfile(p):
            continue
        names = {"item": g.item_names, "npc": g.npc_names, "mob": g.mob_names}[target]
        for el in parse(p).iter("imgdir"):
            i = ival(el.get("name"))
            if i is not None and i > 0:
                n = leaf(el, "name")
                if n is not None:
                    names.setdefault(i, n)

    q = os.path.join(wz, "Quest.wz")
    for name, store in (("Check.img.xml", g.check), ("Act.img.xml", g.act), ("QuestInfo.img.xml", g.quest_info)):
        p = os.path.join(q, name)
        if os.path.isfile(p):
            for el in parse(p):
                qid = ival(el.get("name"))
                if qid is not None:
                    store[qid] = el
    for qid, el in g.quest_info.items():
        g.quest_names[qid] = leaf(el, "name", "")
    return g


def load_jobs(java_path):
    jobs = set()
    if java_path and os.path.isfile(java_path):
        with open(java_path) as fh:
            jobs.update(int(v) for v in re.findall(r"\b[A-Z_0-9]+\((\d+)\)", fh.read()))
    return jobs


def read_tsv(path, cols):
    rows = []
    if path and os.path.isfile(path):
        with open(path) as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= cols:
                    rows.append([ival(p, p) for p in parts[:cols]])
    return rows


class Sources:
    """Where an item / mob / npc can come from outside Quest.wz."""

    def __init__(self, db_dir=None, scripts=None):
        self.mob_drops = collections.defaultdict(set)      # item -> mobs
        self.quest_drops = []                               # (table, dropper, item, questid) with questid > 0
        self.reactor_drops = collections.defaultdict(set)  # item -> reactors
        self.global_drops = set()
        self.shop_items = collections.defaultdict(set)     # item -> npcs
        self.plife = collections.defaultdict(set)          # (type, id) -> maps
        self.script_literals = collections.defaultdict(set)  # number -> script files
        self.quest_scripts = set()
        self.progress_set = collections.defaultdict(set)  # quest / record ids named in a setQuestProgress call
        self.has_db = False
        if db_dir:
            j = lambda n: os.path.join(db_dir, n)
            for mob, item, q, _c in read_tsv(j("drop_data.tsv"), 4):
                self.mob_drops[item].add(mob)
                if isinstance(q, int) and q > 0:
                    self.quest_drops.append(("drop_data", mob, item, q))
            for r, item, q, _c in read_tsv(j("reactordrops.tsv"), 4):
                self.reactor_drops[item].add(r)
                if isinstance(q, int) and q > 0:
                    self.quest_drops.append(("reactordrops", r, item, q))
            for item, _q, _c in read_tsv(j("drop_global.tsv"), 3):
                self.global_drops.add(item)
            for npc, item in read_tsv(j("shopitems.tsv"), 2):
                self.shop_items[item].add(npc)
            for life, t, m in read_tsv(j("plife.tsv"), 3):
                self.plife[(t, life)].add(m)
            self.has_db = bool(self.mob_drops)
        if scripts and os.path.isdir(scripts):
            for root, _, files in os.walk(scripts):
                for f in files:
                    if not f.endswith(".js"):
                        continue
                    path = os.path.join(root, f)
                    rel = os.path.relpath(path, scripts)
                    if rel.startswith("quest" + os.sep):
                        self.quest_scripts.add(f[:-3])
                    with open(path, encoding="utf-8", errors="replace") as fh:
                        text = re.sub(r"/\*.*?\*/", "", fh.read(), flags=re.S)
                    for n in SCRIPT_LITERAL.findall(text):
                        self.script_literals[int(n)].add(rel)
                    for args in PROGRESS_CALL.findall(text):
                        for n in QUEST_LITERAL.findall(args):
                            self.progress_set[int(n)].add(rel)


# ---------------------------------------------------------------- the checks

Finding = collections.namedtuple("Finding", "quest severity code detail")


def items_of(el):
    out = []
    for c in el if el is not None else ():
        k = children(c)
        out.append((ival(leaf(c, "id")), ival(leaf(c, "count"), 0), k))
    return out


def quest_reqs(el):
    """[(quest, state)] from a Check `quest` node."""
    return [(ival(leaf(c, "id")), ival(leaf(c, "state"), 0)) for c in el] if el is not None else []  # QuestRequirement: missing state = 0


class Checker:
    def __init__(self, g, src, jobs, today=None, level_cap=LEVEL_CAP):
        self.g, self.src, self.jobs = g, src, jobs or g.jobs
        self.today = today or datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H")
        self.level_cap = level_cap
        self.findings = []
        self.reward_items = collections.defaultdict(set)   # item -> quests that give it
        self.unstartable = {}
        self.uncompletable = {}
        self.expired = set()

    def add(self, q, sev, code, detail):
        self.findings.append(Finding(q, sev, code, detail))

    def info(self, qid):
        return children(self.g.quest_info.get(qid))

    def is_auto_start(self, qid):
        i = self.info(qid)
        return leaf(self.g.quest_info.get(qid), "autoStart") == "1" or "autoAccept" in i

    def is_auto_complete(self, qid):
        el = self.g.quest_info.get(qid)
        return leaf(el, "autoComplete") == "1" or leaf(el, "autoPreComplete") == "1"

    # where things come from
    def item_sources(self, item):
        s = []
        if item in self.src.mob_drops:
            s.append("mob drop")
        if item in self.src.reactor_drops:
            s.append("reactor")
        if item in self.src.global_drops:
            s.append("global drop")
        if item in self.src.shop_items:
            s.append("shop")
        if item in self.reward_items:
            s.append("quest reward")
        if item in self.g.maker_outputs:
            s.append("maker")
        if item in self.src.script_literals:
            s.append("script")
        return s

    def npc_placed(self, npc):
        return bool(self.g.npc_maps.get(npc) or self.src.plife.get(("n", npc))
                    or npc in self.src.script_literals)

    def mob_spawns(self, mob):
        return bool(self.g.mob_maps.get(mob) or self.src.plife.get(("m", mob)) or mob in self.g.mob_revives
                    or mob in self.src.script_literals)

    def run(self):
        g = self.g
        # pass 1: everything quests give out
        for qid, el in g.act.items():
            for phase in el:
                for item, count, _ in items_of(children(phase).get("item")):
                    if item and count > 0:
                        self.reward_items[item].add(qid)
        for qid in sorted(set(g.check) | set(g.act)):
            self.check_quest(qid)
        self.check_drops()
        self.propagate()
        return self

    def check_drops(self):
        """A quest drop (questid > 0) is only shown to players who have that quest: a missing quest hides it forever."""
        for table, dropper, item, q in self.src.quest_drops:
            if q not in self.g.check:
                self.add(q, WARN, "drop-for-missing-quest",
                         "%s: %s drops %s (%s) only for quest %s, which does not exist, so nobody ever sees it"
                         % (table, dropper, item, self.g.item_name(item), q))

    def check_quest(self, qid):
        g, src = self.g, self.src
        el = g.check.get(qid)
        ph = children(el)
        start, end = children(ph.get("0")), children(ph.get("1"))
        if el is None:
            self.add(qid, WARN, "act-without-check", "Act.img has the quest but Check.img does not: it never loads")
            return
        if qid not in g.quest_info:
            self.add(qid, INFO, "no-questinfo", "no QuestInfo.img entry (no name, client shows nothing)")
        for k in ph:
            if k not in ("0", "1"):
                self.add(qid, WARN, "stray-node", "Check.img/%d has a node '%s' (expected only 0 and 1)" % (qid, k))
        reasons_start, reasons_end = [], []

        # --- start side
        lvmin, lvmax = ival(leaf(ph.get("0"), "lvmin")), ival(leaf(ph.get("0"), "lvmax"))
        if lvmin and lvmin > self.level_cap:
            reasons_start.append("lvmin %d above the level cap" % lvmin)
        if lvmin and lvmax and lvmin > lvmax:
            reasons_start.append("lvmin %d > lvmax %d" % (lvmin, lvmax))
        enddate = leaf(ph.get("0"), "end")
        if enddate and enddate.isdigit() and len(enddate) >= 8 and enddate[:10] < self.today:
            self.expired.add(qid)
        if "job" in start and self.jobs:
            js = [ival(c.get("value")) for c in start["job"]]
            bad = [j for j in js if j not in self.jobs]
            if bad:
                self.add(qid, WARN, "unknown-job", "start job list has unknown job ids %s" % bad)
            if js and all(j in bad for j in js):
                reasons_start.append("no valid job in %s" % js)
        for pq, state in quest_reqs(start.get("quest")):
            if pq not in g.check and pq not in g.quest_info:
                (reasons_start.append if state == 2 else lambda _: None)("prerequisite quest %s does not exist" % pq)
                self.add(qid, ERROR if state == 2 else WARN, "missing-prereq",
                         "prerequisite quest %s (state %s) does not exist" % (pq, state))
        self.check_npc(qid, "start", leaf(ph.get("0"), "npc"), self.is_auto_start(qid) or "normalAutoStart" in start,
                       reasons_start)
        for item, count, _ in items_of(start.get("item")):
            if item and g.has_items and item not in g.items:
                self.add(qid, ERROR if count > 0 else INFO, "missing-item",
                         "start requires item %s x%s that does not exist" % (item, count))
                if count > 0:
                    reasons_start.append("start item %s missing" % item)
            elif item and count > 0 and src.has_db and not self.item_sources(item):
                self.add(qid, ERROR, "unobtainable-item",
                         "start needs %s (%s) x%d and nothing gives it" % (item, g.item_name(item), count))
                reasons_start.append("start item %s unobtainable" % item)
        if "startscript" in start and str(qid) not in src.quest_scripts and src.quest_scripts:
            self.add(qid, ERROR, "missing-script", "Check has startscript but no scripts/quest/%d.js "
                                                   "(falls back to medalQuest.js)" % qid)
        if "fieldEnter" in start and g.has_maps:
            for c in start["fieldEnter"]:
                m = ival(c.get("value"))
                if m not in g.maps:
                    self.add(qid, WARN, "missing-map", "fieldEnter map %s does not exist" % m)
        for c in (start["skill"] if "skill" in start else ()):
            sid = ival(leaf(c, "id"))
            if g.has_skills and sid not in g.skills:
                self.add(qid, WARN, "missing-skill", "start requires skill %s that does not exist" % sid)

        # --- complete side
        self.check_npc(qid, "complete", leaf(ph.get("1"), "npc"), self.is_auto_complete(qid), reasons_end)
        for item, count, _ in items_of(end.get("item")):
            if not item:
                continue
            if g.has_items and item not in g.items:
                self.add(qid, ERROR, "missing-item", "complete requires item %s x%s that does not exist" % (item, count))
                if count > 0:
                    reasons_end.append("item %s missing" % item)
            elif count > 0 and src.has_db and not self.item_sources(item):
                self.add(qid, ERROR, "unobtainable-item",
                         "complete needs %s (%s) x%d and nothing gives it" % (item, g.item_name(item), count))
                reasons_end.append("item %s (%s) unobtainable" % (item, g.item_name(item)))
            elif count > 0 and src.has_db:
                mobs = src.mob_drops.get(item, set())
                if mobs and self.item_sources(item) == ["mob drop"] and not any(self.mob_spawns(m) for m in mobs):
                    self.add(qid, ERROR, "drop-from-unspawned-mob",
                             "complete needs %s (%s); only dropped by mobs that spawn nowhere: %s"
                             % (item, g.item_name(item), sorted(mobs)[:6]))
                    reasons_end.append("item %s only from unspawned mobs" % item)
        for c in (end["mob"] if "mob" in end else ()):
            mob, count = ival(leaf(c, "id")), ival(leaf(c, "count"), 0)
            if mob in QUEST_MOB_ALIASES:
                if not any(self.mob_spawns(m) for m in QUEST_MOB_ALIASES[mob]):
                    self.add(qid, ERROR, "mob-never-spawns", "quest mob %s: none of %s spawns" % (mob, QUEST_MOB_ALIASES[mob]))
                    reasons_end.append("mob %s never spawns" % mob)
                continue
            if g.has_mobs and mob not in g.mobs:
                self.add(qid, ERROR, "missing-mob", "complete requires mob %s that does not exist" % mob)
                reasons_end.append("mob %s missing" % mob)
            elif g.has_maps and not self.mob_spawns(mob):
                self.add(qid, ERROR, "mob-never-spawns",
                         "complete requires %d x mob %s (%s) that spawns on no map / script"
                         % (count, mob, g.mob_names.get(mob, "?")))
                reasons_end.append("mob %s (%s) never spawns" % (mob, g.mob_names.get(mob, "?")))
        for pq, state in quest_reqs(end.get("quest")):
            if pq not in g.check and pq not in g.quest_info:
                self.add(qid, ERROR, "missing-prereq", "complete requires quest %s that does not exist" % pq)
                reasons_end.append("required quest %s missing" % pq)
        for side, phase in (("start", start), ("complete", end)):
            if "infoex" in phase and src.quest_scripts:
                info = ival(leaf(ph.get("0" if side == "start" else "1"), "infoNumber")) or qid
                act_info = "info" in children(children(g.act.get(qid)).get("0"))
                said = info in src.progress_set or qid in src.progress_set
                if not act_info and not said:
                    self.add(qid, WARN, "infoex-never-set",
                             "%s needs quest progress %s on record %d, and no script or start action sets it "
                             "(Java may: map visits, medals)"
                             % (side, [leaf(c, "value") for c in phase["infoex"]], info))
                    (reasons_start if side == "start" else reasons_end).append("progress record %d never set" % info)
        if "endscript" in end and str(qid) not in src.quest_scripts and src.quest_scripts:
            self.add(qid, ERROR, "missing-script", "Check has endscript but no scripts/quest/%d.js "
                                                   "(falls back to medalQuest.js)" % qid)

        # --- rewards
        act = children(g.act.get(qid))
        for side, phase in (("start", act.get("0")), ("complete", act.get("1"))):
            pk = children(phase)
            for item, count, _ in items_of(pk.get("item")):
                if item and g.has_items and item not in g.items:
                    self.add(qid, ERROR if count > 0 else WARN, "missing-reward-item",
                             "%s %s item %s x%s that does not exist" % (side, "gives" if count > 0 else "takes", item, count))
            nq = ival(leaf(phase, "nextQuest"))
            if nq and nq not in g.check:
                self.add(qid, ERROR, "missing-next-quest", "%s nextQuest %s does not exist" % (side, nq))
            for c in (pk["skill"] if "skill" in pk else ()):
                sid = ival(leaf(c, "id"))
                if g.has_skills and sid and sid not in g.skills:
                    self.add(qid, ERROR, "missing-reward-skill", "%s gives skill %s that does not exist" % (side, sid))
        if reasons_start:
            self.unstartable[qid] = reasons_start
        if reasons_end:
            self.uncompletable[qid] = reasons_end

    def check_npc(self, qid, side, npc, auto, reasons):
        npc = ival(npc)
        g = self.g
        if not npc:
            return
        if g.has_npcs and npc not in g.npcs:
            self.add(qid, ERROR, "missing-npc", "%s NPC %s does not exist" % (side, npc))
            if not auto:
                reasons.append("%s NPC %s missing" % (side, npc))
        elif g.has_maps and not auto and not self.npc_placed(npc):
            self.add(qid, ERROR, "npc-not-placed", "%s NPC %s (%s) is on no map" % (side, npc, g.npc_names.get(npc, "?")))
            reasons.append("%s NPC %s (%s) on no map" % (side, npc, g.npc_names.get(npc, "?")))

    def propagate(self):
        """A quest whose required prerequisite can never be completed can never start either."""
        g = self.g
        dead = set(self.unstartable) | set(self.uncompletable)
        changed = True
        while changed:
            changed = False
            for qid, el in g.check.items():
                if qid in self.unstartable:
                    continue
                for pq, state in quest_reqs(children(children(el).get("0")).get("quest")):
                    if state == 2 and pq in dead and pq not in self.expired:
                        self.unstartable[qid] = ["needs quest %s, which is broken" % pq]
                        self.add(qid, WARN, "blocked-by-prereq", "prerequisite quest %s can never be finished" % pq)
                        dead.add(qid)
                        changed = True
                        break


def cosmic_changes(ours, cosmic_wz):
    """Quests whose Check/Act differs from stock Cosmic, as {qid: [what]}."""
    out = collections.defaultdict(list)
    for name, store in (("Check.img.xml", ours.check), ("Act.img.xml", ours.act)):
        p = os.path.join(cosmic_wz, "Quest.wz", name)
        if not os.path.isfile(p):
            continue
        theirs = {ival(e.get("name")): e for e in parse(p)}
        canon = lambda e: ET.tostring(e).replace(b"\n", b"").replace(b" ", b"") if e is not None else None
        for qid in set(theirs) | set(store):
            a, b = store.get(qid), theirs.get(qid)
            if b is None:
                out[qid].append(name.split(".")[0] + " added")
            elif a is None:
                out[qid].append(name.split(".")[0] + " removed")
            elif canon(a) != canon(b):
                out[qid].append(name.split(".")[0] + " changed")
    return out


def finding_key(f):
    """Identity of a finding across data sets: names in parentheses differ between String.wz versions."""
    return (f.quest, f.code, re.sub(r" \([^)]*\)", "", f.detail))


def load_baseline(path):
    """Finding keys of an earlier --json run (e.g. against stock Cosmic), to split new findings from stock ones."""
    with open(path) as fh:
        return {finding_key(Finding(**f)) for f in json.load(fh)["findings"]}


def report(chk, cosmic=None, baseline=None):
    g = chk.g
    by_code = collections.Counter((f.severity, f.code) for f in chk.findings)
    lines = ["# Quest data check", "",
             "%d quests in Check.img, %d in Act.img; %d findings." % (len(g.check), len(g.act), len(chk.findings)), "",
             "| severity | code | count |", "|---|---|---|"]
    for (sev, code), n in sorted(by_code.items(), key=lambda x: ({"ERROR": 0, "WARN": 1, "INFO": 2}[x[0][0]], -x[1])):
        lines.append("| %s | %s | %d |" % (sev, code, n))
    live_unstart = {q: r for q, r in chk.unstartable.items() if q not in chk.expired}
    live_unend = {q: r for q, r in chk.uncompletable.items() if q not in chk.expired}
    lines += ["", "Expired event quests (end date in the past, expected): %d" % len(chk.expired), "",
              "## Quests that can never start (%d, excluding expired)" % len(live_unstart), ""]
    for q in sorted(live_unstart):
        lines.append("- %d %s: %s" % (q, g.quest_names.get(q, ""), "; ".join(live_unstart[q])))
    lines += ["", "## Quests that can never be completed (%d, excluding expired)" % len(live_unend), ""]
    for q in sorted(live_unend):
        lines.append("- %d %s: %s" % (q, g.quest_names.get(q, ""), "; ".join(live_unend[q])))
    if baseline is not None:
        new = [f for f in chk.findings if finding_key(f) not in baseline and f.quest not in chk.expired]
        lines += ["", "## Findings not in the baseline (%d, excluding expired)" % len(new), ""]
        for f in sorted(new, key=lambda f: ({"ERROR": 0, "WARN": 1, "INFO": 2}[f.severity], f.code, f.quest)):
            lines.append("- %s %s %d %s: %s" % (f.severity, f.code, f.quest, g.quest_names.get(f.quest, ""), f.detail))
    lines += ["", "## All findings", ""]
    for f in sorted(chk.findings, key=lambda f: ({"ERROR": 0, "WARN": 1, "INFO": 2}[f.severity], f.code, f.quest)):
        exp = " (expired event)" if f.quest in chk.expired else ""
        stock = " [baseline]" if baseline is not None and finding_key(f) in baseline else ""
        lines.append("- %s %s %d %s: %s%s%s" % (f.severity, f.code, f.quest, g.quest_names.get(f.quest, ""), f.detail,
                                                exp, stock))
    if cosmic is not None:
        lines += ["", "## Quests whose data differs from stock Cosmic (%d)" % len(cosmic), ""]
        for q in sorted(cosmic):
            lines.append("- %d %s: %s" % (q, g.quest_names.get(q, ""), ", ".join(cosmic[q])))
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wz", required=True)
    ap.add_argument("--scripts", default="scripts")
    ap.add_argument("--db-dir")
    ap.add_argument("--cosmic-wz")
    ap.add_argument("--character-list")
    ap.add_argument("--baseline", help="--json output of a run against stock Cosmic; marks findings Cosmic shares")
    ap.add_argument("--job-java", default="src/main/java/client/Job.java")
    ap.add_argument("--today", help="YYYYMMDDHH used for expired-event detection (default now, UTC)")
    ap.add_argument("--json")
    ap.add_argument("--md")
    a = ap.parse_args(argv)
    g = load_wz(a.wz, a.character_list)
    src = Sources(a.db_dir, a.scripts)
    chk = Checker(g, src, load_jobs(a.job_java), today=a.today).run()
    cosmic = cosmic_changes(g, a.cosmic_wz) if a.cosmic_wz else None
    baseline = load_baseline(a.baseline) if a.baseline else None
    text = report(chk, cosmic, baseline)
    if a.md:
        with open(a.md, "w") as fh:
            fh.write(text)
    if a.json:
        with open(a.json, "w") as fh:
            json.dump({"findings": [f._asdict() for f in chk.findings],
                       "unstartable": chk.unstartable, "uncompletable": chk.uncompletable,
                       "expired": sorted(chk.expired), "cosmic_changes": cosmic}, fh, indent=1)
    if not a.md:
        sys.stdout.write(text)
    return 1 if any(f.severity == ERROR and f.quest not in chk.expired
                    and (baseline is None or finding_key(f) not in baseline) for f in chk.findings) else 0


if __name__ == "__main__":
    sys.exit(main())
