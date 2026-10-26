"""Database (Hidden Street): one page per reachable item, monster, map, NPC and quest, list pages, and a search index.
Private until 1.6.0 is live: build.py only runs it with --private.

Data: sections/dbdata.py (the build_data.py dataset, cut to what a player can reach). Icons: cache/site-icons from
tools/site/icons.py; an entity without an icon gets an empty slot. Layout after the 2006 Hidden Street: a grouped
sidebar on the left (items by kind, monsters by level, maps and NPCs by area), a bordered stat table, and
"Dropped by" / "Found at" link lists, in the site's data density.
"""
import json
import os
import re
import shutil

from common import REPO, esc, layout, write
from sections import dbdata

ICONS = os.path.join(REPO, "cache", "site-icons")
DB = "database"

# ── categories ───────────────────────────────────────────────────────────────────────────────────

ITEM_GROUPS = [  # (sidebar heading, [(dataset type, list page slug, label)])
    ("Equipment", [("Weapon", "weapons", "Weapons"), ("Cap", "hats", "Hats"), ("Coat", "tops", "Tops"),
                   ("Longcoat", "overalls", "Overalls"), ("Pants", "bottoms", "Bottoms"), ("Shoes", "shoes", "Shoes"),
                   ("Glove", "gloves", "Gloves"), ("Shield", "shields", "Shields"), ("Cape", "capes", "Capes"),
                   ("Accessory", "accessories", "Accessories"), ("Ring", "rings", "Rings"),
                   ("PetEquip", "pet-equipment", "Pet equipment"), ("TamingMob", "mounts", "Mounts")]),
    ("Items", [("Consume", "use", "Use"), ("Install", "setup", "Setup"), ("Etc", "etc", "Etc"),
               ("Cash", "cash", "Cash"), ("Pet", "pets", "Pets")]),
]
TYPE_PAGE = {t: (slug, label) for _, rows in ITEM_GROUPS for t, slug, label in rows}

WEAPON_KINDS = {130: "One-handed Sword", 131: "One-handed Axe", 132: "One-handed Blunt Weapon", 133: "Dagger",
                137: "Wand", 138: "Staff", 140: "Two-handed Sword", 141: "Two-handed Axe", 142: "Two-handed Blunt Weapon",
                143: "Spear", 144: "Polearm", 145: "Bow", 146: "Crossbow", 147: "Claw", 148: "Knuckle", 149: "Gun"}
MAGIC_WEAPONS = {137, 138}
JOB_BITS = [(1, "Warrior"), (2, "Magician"), (4, "Bowman"), (8, "Thief"), (16, "Pirate")]
ATTACK_SPEED = {2: "Faster (2)", 3: "Faster (3)", 4: "Fast (4)", 5: "Fast (5)", 6: "Normal (6)", 7: "Slow (7)",
                8: "Slow (8)", 9: "Slower (9)"}
EQUIP_STATS = [("incSTR", "STR"), ("incDEX", "DEX"), ("incINT", "INT"), ("incLUK", "LUK"), ("incMHP", "HP"),
               ("incMMP", "MP"), ("incPAD", "Weapon ATT"), ("incMAD", "Magic ATT"), ("incPDD", "Weapon DEF"),
               ("incMDD", "Magic DEF"), ("incACC", "Accuracy"), ("incEVA", "Avoidability"), ("incSpeed", "Speed"),
               ("incJump", "Jump")]
USE_STATS = [("hp", "Recovers HP"), ("mp", "Recovers MP"), ("hpR", "Recovers HP %"), ("mpR", "Recovers MP %"),
             ("pad", "Weapon ATT"), ("mad", "Magic ATT"), ("pdd", "Weapon DEF"), ("mdd", "Magic DEF"),
             ("acc", "Accuracy"), ("eva", "Avoidability"), ("speed", "Speed"), ("jump", "Jump"),
             ("incPAD", "Weapon ATT"), ("incMAD", "Magic ATT"), ("incPDD", "Weapon DEF"), ("incMDD", "Magic DEF"),
             ("incSTR", "STR"), ("incDEX", "DEX"), ("incINT", "INT"), ("incLUK", "LUK"), ("incACC", "Accuracy"),
             ("incMHP", "HP"), ("success", "Success rate %"), ("cursed", "Destroy rate %")]
MOB_BANDS = [(1, 10), (11, 20), (21, 30), (31, 40), (41, 50), (51, 60), (61, 70), (71, 80), (81, 90), (91, 100),
             (101, 120), (121, 150), (151, 200), (201, 300)]
REGIONS = {0: "Maple Island", 1: "Victoria Island", 2: "Ossyria and beyond", 3: "Edelstein and Masteria",
           5: "World Tour", 6: "Masteria", 7: "Zipangu", 8: "Zipangu and Malaysia", 9: "Events and hidden maps"}
EXP_RATE = 3  # Bera's EXP rate, shown next to the base EXP (guide: rates.html)


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-") or "x"


def band(level):
    for lo, hi in MOB_BANDS:
        if lo <= (level or 0) <= hi:
            return lo, hi
    return MOB_BANDS[-1]


def pct(chance):
    """drop chance out of 1,000,000 -> a short percent string"""
    p = chance / 10000
    return f"{p:.0f}%" if p >= 10 else f"{p:.1f}%" if p >= 1 else f"{p:.2f}%" if p >= 0.01 else f"{p:.3f}%"


def num(v):
    return f"{v:,}" if isinstance(v, int) else esc(str(v))


# ── shared html ─────────────────────────────────────────────────────────────────────────────────

class Site:
    def __init__(self, data, out):
        self.d, self.out = data, out
        self.icons = {}
        manifest = os.path.join(ICONS, "manifest.json")
        if os.path.isfile(manifest):
            with open(manifest, encoding="utf-8") as f:
                self.icons = {k: set(v) for k, v in json.load(f).get("icons", {}).items()}

    def url(self, kind, ident, root):
        return f"{root}{DB}/{kind}/{ident}.html"

    def icon(self, kind, ident, root, big=False, cls="slotc"):
        if ident in self.d[kind] and str(ident) in self.icons.get(kind, ()):  # icons are copied for kept entities only
            return (f'<span class="{cls}{" big" if big else ""}"><img class="px" src="{root}{DB}/icons/{kind}/{ident}.png" '
                    f'alt="" loading="lazy"></span>')
        return f'<span class="{cls}{" big" if big else ""}"></span>'

    def link(self, kind, ident, root, name=None):
        rec = self.d[kind].get(ident)
        if rec is None:  # not in the database (unreachable or left out): the plain name, in the site's own words
            return esc(scrub(name or str(ident)))
        return f'<a href="{self.url(kind, ident, root)}">{esc(rec["name"])}</a>'

    def sidebar(self, root, here):
        def li(href, label, key):
            cur = ' aria-current="page"' if key == here else ""
            return f'<li><a href="{root}{DB}/{href}"{cur}>{esc(label)}</a></li>'
        parts = [f'<p class="bar">Database</p>',
                 f'<form class="dbsearch" role="search" data-root="{root}" hidden><input type="search" '
                 f'placeholder="Search..." aria-label="Search the database" autocomplete="off"><ul class="dbhits"></ul></form>']
        for head, rows in ITEM_GROUPS:
            parts.append(f'<h3>{esc(head)}</h3><ul>' + "".join(
                li(f"items/{slug_}.html", lab, f"items/{slug_}") for _, slug_, lab in rows) + "</ul>")
        parts.append("<h3>Monsters</h3><ul>" + "".join(
            li(f"mobs/lv-{lo}-{hi}.html", f"Level {lo}-{hi}", f"mobs/lv-{lo}-{hi}") for lo, hi in self.bands()) + "</ul>")
        parts.append("<h3>World</h3><ul>" + li("maps/index.html", "Maps", "maps") + li("npcs/index.html", "NPCs", "npcs")
                     + li("quests/index.html", "Quests", "quests") + "</ul>")
        return f'<nav class="panel dbside" aria-label="Database">{"".join(parts)}</nav>'

    def bands(self):
        used = {band(m.get("level")) for m in self.d["mobs"].values()}
        return [b for b in MOB_BANDS if b in used]

    def page(self, rel, title, crumbs, body, here, description=""):
        depth = rel.count("/")
        root = "../" * depth
        trail = " &raquo; ".join([f'<a href="{root}{DB}/index.html">Database</a>'] +
                                 [f'<a href="{root}{DB}/{h}">{esc(t)}</a>' if h else esc(t) for t, h in crumbs])
        html = (f'<div class="db">{self.sidebar(root, here)}<div class="dbmain data">'
                f'<p class="crumbs">{trail}</p>{body.replace("{root}", root)}</div></div>'
                f'<script src="{root}assets/dbsearch.js" defer></script>')
        write(self.out, rel, layout(title, html, root, "database", description))


def scrub(text):
    """Item text in the site's own words: the game is Slums Story, and the official names never appear."""
    for old, new in (("Nexon Game Card", "NX Card"), ("MapleStory", "Slums Story"), ("Maple Story", "Slums Story"),
                     ("Nexon", "the game"), ("Wizet", "GM")):
        text = text.replace(old, new).replace(old.lower(), new).replace(old.upper(), new)
    return text


def kv_table(rows):
    """Hidden Street's bordered stat table: label: value cells, two per row on wide screens."""
    cells = "".join(f"<div><dt>{esc(k)}</dt><dd>{v}</dd></div>" for k, v in rows if v not in (None, ""))
    return f'<dl class="stats">{cells}</dl>' if cells else ""


def table(head, rows, cls=""):
    if not rows:
        return ""
    th = "".join(f'<th{" class=r" if h.endswith(">") else ""}>{esc(h.rstrip(">"))}</th>' for h in head)
    body = "".join("<tr>" + "".join(c for c in r) + "</tr>" for r in rows)
    return f'<div class="tbl"><table class="t {cls}"><thead><tr>{th}</tr></thead><tbody>{body}</tbody></table></div>'


def td(v, right=False, cls=""):
    c = " ".join(x for x in (cls, "r num" if right else "") if x)
    return f'<td{f" class={chr(34)}{c}{chr(34)}" if c else ""}>{v}</td>'


def box(title, inner, extra=""):
    if not inner:
        return ""
    return f'<section class="panel"><h2 class="bar">{esc(title)}{f" <small>{extra}</small>" if extra else ""}</h2>{inner}</section>'


def jobs_text(mask):
    if not mask:
        return "All jobs"
    if mask == -1:
        return "Beginner"
    return ", ".join(n for b, n in JOB_BITS if mask & b) or "All jobs"


# ── items ────────────────────────────────────────────────────────────────────────────────────────

def item_kind(it, ident):
    if it["type"] == "Weapon":
        return WEAPON_KINDS.get(ident // 10000, "Weapon")
    return TYPE_PAGE.get(it["type"], (None, it["type"]))[1]


def item_stats(it, ident):
    st = it.get("stats") or {}
    rows = []
    if ident < 2000000:
        rows.append(("Req. level", num(it.get("req_level") or 0)))
        rows.append(("Job", esc(jobs_text(it.get("req_job") or 0))))
        for k in ("reqSTR", "reqDEX", "reqINT", "reqLUK"):
            if st.get(k):
                rows.append((f"Req. {k[3:]}", num(st[k])))
        for k, label in EQUIP_STATS:
            v = st.get(k)
            # the server data gives most physical weapons a Magic ATT equal to their Weapon ATT; hidden until checked
            if k == "incMAD" and it["type"] == "Weapon" and ident // 10000 not in MAGIC_WEAPONS:
                continue
            if v:
                rows.append((label, f"+{num(v)}"))
        if st.get("attackSpeed"):
            rows.append(("Attack speed", esc(ATTACK_SPEED.get(st["attackSpeed"], str(st["attackSpeed"])))))
        if st.get("tuc") is not None:
            rows.append(("Upgrade slots", num(st.get("tuc") or 0)))
    else:
        seen = set()
        for k, label in USE_STATS:
            if st.get(k) and label not in seen:
                seen.add(label)
                rows.append((label, num(st[k])))
        if st.get("time"):
            rows.append(("Lasts", f"{st['time'] // 1000:,} s" if st["time"] > 1000 else f"{st['time']:,} s"))
        if st.get("slotMax"):
            rows.append(("Stacks to", num(st["slotMax"])))
    if st.get("price"):
        rows.append(("Shop value", f"{num(st['price'])} mesos"))
    flags = [label for k, label in (("only", "One of a kind"), ("tradeBlock", "Untradeable"), ("quest", "Quest item"),
                                    ("cash", "Cash item")) if st.get(k)]
    if flags:
        rows.append(("Notes", esc(", ".join(flags))))
    return rows


def build_items(s):
    d = s.d
    for ident, it in d["items"].items():
        rel = f"{DB}/items/{ident}.html"
        root = "../../"
        slug_, label = TYPE_PAGE.get(it["type"], ("etc", it["type"]))
        head = (f'<div class="ent">{s.icon("items", ident, "{root}", big=True)}<div><h1>{esc(it["name"])}</h1>'
                f'<p class="id">Item {ident} &middot; {esc(item_kind(it, ident))}</p></div></div>')
        desc = scrub((it.get("desc") or "").replace("\\n", " ").strip())
        intro = f'<p class="desc">{esc(desc)}</p>' if desc else ""
        drops = sorted(it["drops"], key=lambda x: -x.get("chance", 0))
        drop_rows = [[td(s.icon("mobs", x["mob_id"], "{root}")), td(s.link("mobs", x["mob_id"], "{root}")),
                      td(num(d["mobs"][x["mob_id"]].get("level") or 0), True),
                      td(f'{x["min_qty"]}-{x["max_qty"]}' if x.get("max_qty", 1) > 1 else "1", True),
                      td(pct(x.get("chance", 0)), True)] for x in drops]
        shop_rows = [[td(s.icon("npcs", x["npc_id"], "{root}")), td(s.link("npcs", x["npc_id"], "{root}")),
                      td(f'{num(x["price"])}', True)] for x in sorted(it["shops"], key=lambda x: x.get("price", 0))]
        quest_rows = ([[td(s.link("quests", q["quest_id"], "{root}")), td("Reward" + (f' ({q["prop"]}% pool)' if q.get("prop") else "")),
                        td(num(q.get("count", 1)), True)] for q in it["quests_reward"]] +
                      [[td(s.link("quests", q["quest_id"], "{root}")), td("Needed"), td(num(q.get("count", 1)), True)]
                       for q in it["quests_require"]])
        body = (head + intro + box("Stats", kv_table(item_stats(it, ident))) +
                box("Dropped by", table(["", "Monster", "Level>", "Qty>", "Chance>"], drop_rows, "icons"),
                    "base chance; Bera drops at 4x") +
                box("Sold by", table(["", "NPC", "Price>"], shop_rows, "icons")) +
                box("Quests", table(["Quest", "Role", "Count>"], quest_rows)))
        s.page(rel, it["name"], [("Items", None), (label, f"items/{slug_}.html"), (it["name"], None)], body,
               f"items/{slug_}", f"{it['name']}: stats, drops, shops and quests on SlumsMS.")
    # list pages
    by_type = {}
    for ident, it in d["items"].items():
        by_type.setdefault(it["type"], []).append(ident)
    for _, rows in ITEM_GROUPS:
        for t, slug_, label in rows:
            ids = by_type.get(t, [])
            ids.sort(key=lambda i: ((i // 10000) if t == "Weapon" else 0, d["items"][i].get("req_level") or 0,
                                    d["items"][i]["name"]))
            trs = [[td(s.icon("items", i, "{root}")), td(s.link("items", i, "{root}")),
                    td(esc(item_kind(d["items"][i], i))), td(num(d["items"][i].get("req_level") or 0), True)] for i in ids]
            body = (f'<h1>{esc(label)}</h1><p class="id">{len(ids):,} items</p>' +
                    (table(["", "Item", "Kind", "Req. level>"], trs, "icons") or "<p>None yet.</p>"))
            s.page(f"{DB}/items/{slug_}.html", label, [("Items", None), (label, None)], body, f"items/{slug_}")


# ── monsters ─────────────────────────────────────────────────────────────────────────────────────

def build_mobs(s):
    d = s.d
    for ident, m in d["mobs"].items():
        st = m.get("stats") or {}
        lo, hi = band(m.get("level"))
        exp = m.get("exp") or 0
        stats = [("Level", num(m.get("level") or 0)), ("HP", num(m.get("hp") or 0)), ("MP", num(m.get("mp") or 0)),
                 ("EXP", f"{num(exp * EXP_RATE)} <span class=muted>({num(exp)} base)</span>" if exp else "0"),
                 ("Weapon ATT", num(st.get("PADamage") or 0)), ("Magic ATT", num(st.get("MADamage") or 0)),
                 ("Weapon DEF", num(st.get("PDDamage") or 0)), ("Magic DEF", num(st.get("MDDamage") or 0)),
                 ("Accuracy", num(st.get("acc") or 0)), ("Avoidability", num(st.get("eva") or 0)),
                 ("Touch damage", "Yes" if st.get("bodyAttack") else "No"),
                 ("Knockback", "Yes" if st.get("pushed") else "No")]
        if st.get("boss"):
            stats.append(("Boss", "Yes"))
        if st.get("undead"):
            stats.append(("Undead", "Yes (heal hurts it)"))
        groups = {}
        for x in sorted(m["drops"], key=lambda x: -x.get("chance", 0)):
            if x["item_id"] == 0:
                groups.setdefault("Mesos", []).append(f'{x["min_qty"]:,}-{x["max_qty"]:,} mesos ({pct(x["chance"])})')
                continue
            it = d["items"][x["item_id"]]
            g = "Equipment" if x["item_id"] < 2000000 else {"Consume": "Use", "Install": "Setup"}.get(it["type"], it["type"])
            quest = " (quest)" if x.get("quest_id") else ""
            groups.setdefault(g, []).append(
                f'{s.icon("items", x["item_id"], "{root}", cls="ico")}{s.link("items", x["item_id"], "{root}")} '
                f'<span class="muted">{pct(x["chance"])}{quest}</span>')
        order = ["Mesos", "Etc", "Use", "Equipment", "Setup", "Cash"]
        drops = "".join(f'<div class="droplist"><h3>{esc(g)}</h3><p>{", ".join(groups[g]) if g == "Mesos" else "".join(f"<span>{x}</span>" for x in groups[g])}</p></div>'
                        for g in sorted(groups, key=lambda g: order.index(g) if g in order else 99))
        maps = sorted(m["maps"], key=lambda x: -x.get("count", 0))
        found = "".join(f'<li>{esc(d["maps"][x["map_id"]].get("street") or "")}: {s.link("maps", x["map_id"], "{root}")} '
                        f'<span class="muted">({x.get("count", 0)})</span></li>' for x in maps)
        body = (f'<div class="ent">{s.icon("mobs", ident, "{root}", big=True, cls="slotc mob")}<div><h1>{esc(m["name"])}</h1>'
                f'<p class="id">Monster {ident} &middot; Level {m.get("level") or 0}</p></div></div>' +
                box("Stats", kv_table(stats)) +
                box("Found at", f'<ul class="found">{found}</ul>' if found else "", "spawns per map") +
                box("Drops", f'<div class="in">{drops}</div>' if drops else "", "base chance; Bera drops at 4x"))
        s.page(f"{DB}/mobs/{ident}.html", m["name"], [("Monsters", None), (f"Level {lo}-{hi}", f"mobs/lv-{lo}-{hi}.html"),
               (m["name"], None)], body, f"mobs/lv-{lo}-{hi}", f"{m['name']}: level {m.get('level')}, drops and maps on SlumsMS.")
    for lo, hi in s.bands():
        ids = sorted((i for i, m in d["mobs"].items() if band(m.get("level")) == (lo, hi)),
                     key=lambda i: (d["mobs"][i].get("level") or 0, d["mobs"][i]["name"]))
        trs = [[td(s.icon("mobs", i, "{root}")), td(s.link("mobs", i, "{root}")), td(num(d["mobs"][i].get("level") or 0), True),
                td(num(d["mobs"][i].get("hp") or 0), True), td(num((d["mobs"][i].get("exp") or 0) * EXP_RATE), True)] for i in ids]
        body = (f"<h1>Monsters, level {lo}-{hi}</h1><p class=id>{len(ids):,} monsters &middot; EXP at Bera's 3x</p>" +
                table(["", "Monster", "Level>", "HP>", "EXP>"], trs, "icons"))
        s.page(f"{DB}/mobs/lv-{lo}-{hi}.html", f"Monsters level {lo}-{hi}", [("Monsters", None), (f"Level {lo}-{hi}", None)],
               body, f"mobs/lv-{lo}-{hi}")


# ── maps, NPCs, quests ──────────────────────────────────────────────────────────────────────────

def region(map_id):
    return REGIONS.get(map_id // 100000000, "Other")


def build_maps(s):
    d = s.d
    for ident, mp in d["maps"].items():
        mobs = [[td(s.icon("mobs", x["mob_id"], "{root}")), td(s.link("mobs", x["mob_id"], "{root}")),
                 td(num(d["mobs"][x["mob_id"]].get("level") or 0), True), td(num(x.get("count", 0)), True)]
                for x in sorted(mp["mobs"], key=lambda x: d["mobs"][x["mob_id"]].get("level") or 0)]
        npcs = "".join(f'<li>{s.icon("npcs", x["npc_id"], "{root}", cls="ico")}{s.link("npcs", x["npc_id"], "{root}")}</li>'
                       for x in mp["npcs"])
        targets = []
        for p in mp["portals"]:
            if p["target_map_id"] not in targets and p["target_map_id"] != ident:
                targets.append(p["target_map_id"])
        portals = "".join(f'<li>{esc(d["maps"][t].get("street") or "")}: {s.link("maps", t, "{root}")}</li>' for t in targets)
        body = (f'<h1>{esc(mp["name"])}</h1><p class="id">{esc(mp.get("street") or "")} &middot; {esc(region(ident))} &middot; Map {ident}</p>' +
                box("Monsters", table(["", "Monster", "Level>", "Spawns>"], mobs, "icons")) +
                box("NPCs", f'<ul class="found">{npcs}</ul>' if npcs else "") +
                box("Portals lead to", f'<ul class="found">{portals}</ul>' if portals else ""))
        s.page(f"{DB}/maps/{ident}.html", mp["name"], [("Maps", "maps/index.html"), (region(ident), None), (mp["name"], None)],
               body, "maps")
    by_region = {}
    for ident, mp in d["maps"].items():
        by_region.setdefault(region(ident), {}).setdefault(mp.get("street") or "Other", []).append(ident)
    parts = ["<h1>Maps</h1>", f"<p class=id>{len(d['maps']):,} maps with monsters or NPCs on them</p>"]
    for reg in sorted(by_region, key=lambda r: list(REGIONS.values()).index(r) if r in REGIONS.values() else 99):
        streets = by_region[reg]
        inner = "".join(f'<details><summary>{esc(st)} <span class="muted">({len(ids)})</span></summary><ul class="found">' +
                        "".join(f"<li>{s.link('maps', i, '{root}')}</li>" for i in sorted(ids, key=lambda i: d["maps"][i]["name"])) +
                        "</ul></details>" for st, ids in sorted(streets.items()))
        parts.append(box(reg, f'<div class="in">{inner}</div>'))
    s.page(f"{DB}/maps/index.html", "Maps", [("Maps", None)], "".join(parts), "maps")


def build_npcs(s):
    d = s.d
    for ident, n in d["npcs"].items():
        where = "".join(f'<li>{esc(d["maps"][x["map_id"]].get("street") or "")}: {s.link("maps", x["map_id"], "{root}")}</li>'
                        for x in n["maps"])
        shop = [[td(s.icon("items", x["item_id"], "{root}")), td(s.link("items", x["item_id"], "{root}")),
                 td(num(x.get("price", 0)), True)] for x in sorted(n["shop_items"], key=lambda x: x.get("position", 0))]
        quests = ([[td(s.link("quests", q["quest_id"], "{root}")), td("Starts here")] for q in n["quests_start"]] +
                  [[td(s.link("quests", q["quest_id"], "{root}")), td("Ends here")] for q in n["quests_end"]])
        body = (f'<div class="ent">{s.icon("npcs", ident, "{root}", big=True, cls="slotc mob")}<div><h1>{esc(n["name"])}</h1>'
                f'<p class="id">NPC {ident}</p></div></div>' + box("Found at", f'<ul class="found">{where}</ul>' if where else "") +
                box("Shop", table(["", "Item", "Price>"], shop, "icons"), "mesos") + box("Quests", table(["Quest", ""], quests)))
        s.page(f"{DB}/npcs/{ident}.html", n["name"], [("NPCs", "npcs/index.html"), (n["name"], None)], body, "npcs")
    by_town = {}
    for ident, n in d["npcs"].items():
        town = d["maps"][n["maps"][0]["map_id"]].get("street") if n["maps"] else "Other"
        by_town.setdefault(town or "Other", []).append(ident)
    inner = "".join(f'<details><summary>{esc(t)} <span class="muted">({len(ids)})</span></summary><ul class="found">' +
                    "".join(f'<li>{s.icon("npcs", i, "{root}", cls="ico")}{s.link("npcs", i, "{root}")}</li>'
                            for i in sorted(ids, key=lambda i: d["npcs"][i]["name"])) + "</ul></details>"
                    for t, ids in sorted(by_town.items()))
    s.page(f"{DB}/npcs/index.html", "NPCs", [("NPCs", None)],
           f"<h1>NPCs</h1><p class=id>{len(d['npcs']):,} NPCs, by the area they stand in</p>" + box("By area", f'<div class="in">{inner}</div>'),
           "npcs")


def build_quests(s):
    d = s.d
    for ident, q in d["quests"].items():
        req, rew = q.get("requirements") or {}, q.get("rewards") or {}
        info = [("Starts at", s.link("npcs", (q.get("start_npc") or {}).get("id"), "{root}")),
                ("Ends at", s.link("npcs", (q.get("end_npc") or {}).get("id"), "{root}") if q.get("end_npc") else ""),
                ("Level", f'{req.get("min_level") or 1}' + (f'-{req["max_level"]}' if req.get("max_level") and req["max_level"] < 200 else "+")),
                ("After", ", ".join(s.link("quests", p, "{root}") for p in req.get("quests") or [] if p in d["quests"]))]
        need = [[td(s.icon("items", x["item_id"], "{root}")), td(s.link("items", x["item_id"], "{root}", x.get("item_name"))),
                 td(num(x.get("count", 1)), True)] for x in req.get("items") or [] if x.get("state") == 1 and x.get("count", 0) > 0]
        hunt = [[td(s.icon("mobs", x["mob_id"], "{root}")), td(s.link("mobs", x["mob_id"], "{root}", x.get("mob_name"))),
                 td(num(x.get("count", 1)), True)] for x in req.get("mobs") or []]
        gets = [("EXP", num(rew["exp"]) if rew.get("exp") else ""), ("Mesos", num(rew["meso"]) if rew.get("meso") else ""),
                ("Fame", num(rew["fame"]) if rew.get("fame") else ""),
                ("Next quest", s.link("quests", rew["next_quest"], "{root}") if rew.get("next_quest") in d["quests"] else "")]
        items = [[td(s.icon("items", x["item_id"], "{root}")), td(s.link("items", x["item_id"], "{root}", x.get("item_name"))),
                  td(num(x.get("count", 1)), True), td(f'{x["prop"]}%' if x.get("prop") else "", True)]
                 for x in rew.get("items") or [] if x.get("count", 0) > 0]
        body = (f'<h1>{esc(q["name"])}</h1><p class="id">Quest {ident}</p>' + box("Quest", kv_table(info)) +
                box("Bring", table(["", "Item", "Count>"], need, "icons")) +
                box("Hunt", table(["", "Monster", "Count>"], hunt, "icons")) +
                box("Rewards", kv_table(gets) + table(["", "Item", "Count>", "Chance>"], items, "icons"), "base amounts"))
        s.page(f"{DB}/quests/{ident}.html", q["name"], [("Quests", "quests/index.html"), (q["name"], None)], body, "quests")
    by_lv = {}
    for ident, q in d["quests"].items():
        lv = (q.get("requirements") or {}).get("min_level") or 0
        by_lv.setdefault(band(lv), []).append(ident)
    inner = "".join(f'<details><summary>Level {lo}-{hi} <span class="muted">({len(ids)})</span></summary><ul class="found">' +
                    "".join(f"<li>{s.link('quests', i, '{root}')}</li>" for i in sorted(ids, key=lambda i: d["quests"][i]["name"])) +
                    "</ul></details>" for (lo, hi), ids in sorted(by_lv.items()))
    s.page(f"{DB}/quests/index.html", "Quests", [("Quests", None)],
           f"<h1>Quests</h1><p class=id>{len(d['quests']):,} quests, by starting level</p>" + box("By level", f'<div class="in">{inner}</div>'),
           "quests")


# ── index, search, icons ────────────────────────────────────────────────────────────────────────

def build_index(s):
    d = s.d
    counts = [("Items", sum(1 for _ in d["items"]), "items/weapons.html", 1302000),
              ("Monsters", len(d["mobs"]), f"mobs/lv-{MOB_BANDS[0][0]}-{MOB_BANDS[0][1]}.html", None),
              ("Maps", len(d["maps"]), "maps/index.html", None), ("NPCs", len(d["npcs"]), "npcs/index.html", None),
              ("Quests", len(d["quests"]), "quests/index.html", None)]
    tiles = "".join(f'<a class="dbtile" href="{{root}}{DB}/{href}"><b>{n:,}</b><span>{esc(label)}</span></a>'
                    for label, n, href, _ in counts)
    body = ('<h1>Database</h1><p class="lede">Every item, monster, map, NPC and quest you can reach on Bera, from the '
            "server's own data. Drop chances are base rates; Bera drops at 4x.</p>"
            f'<div class="dbtiles">{tiles}</div>')
    s.page(f"{DB}/index.html", "Database", [], body, "", "The SlumsMS item, monster, map, NPC and quest database.")


def build_search(s):
    d = s.d
    rows = [["i", i, v["name"]] for i, v in d["items"].items()] + [["m", i, v["name"]] for i, v in d["mobs"].items()] + \
           [["p", i, v["name"]] for i, v in d["maps"].items()] + [["n", i, v["name"]] for i, v in d["npcs"].items()] + \
           [["q", i, v["name"]] for i, v in d["quests"].items()]
    write(s.out, f"{DB}/search-index.json", json.dumps(rows, separators=(",", ":"), ensure_ascii=False))


def copy_icons(s):
    n = 0
    for kind in ("items", "mobs", "npcs"):
        src = os.path.join(ICONS, kind)
        dst = os.path.join(s.out, DB, "icons", kind)
        os.makedirs(dst, exist_ok=True)
        for ident in s.d[kind]:
            p = os.path.join(src, f"{ident}.png")
            if os.path.isfile(p):
                shutil.copyfile(p, os.path.join(dst, f"{ident}.png"))
                n += 1
    return n


def build(out, data_dir=None):
    s = Site(dbdata.load(data_dir), out)
    shutil.rmtree(os.path.join(out, DB), ignore_errors=True)
    n_icons = copy_icons(s)
    build_index(s)
    build_items(s)
    build_mobs(s)
    build_maps(s)
    build_npcs(s)
    build_quests(s)
    build_search(s)
    return {k: len(v) for k, v in s.d.items()} | {"icons": n_icons}
