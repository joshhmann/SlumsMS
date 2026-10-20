#!/usr/bin/env python3
"""Build the SlumsMS player wiki (ticket 40): plain static HTML, no server, no build tools beyond Python + PyYAML.

    python3 docs/wiki/build.py                    # rebuild docs/wiki/*.html
    python3 docs/wiki/build.py --names-from WZ    # also refresh data/names.json from a server wz/ tree
    python3 -m http.server -d docs/wiki 8000      # preview (or just open docs/wiki/index.html)

Hand-written pages live in src/<page>.html. The first line is a comment holding the title and nav order:
    <!-- title: Rates | nav: 30 | blurb: one line for the home page -->
Generated pages come straight from the server's data, so they don't go stale:
    leveling.html   <- src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/LevelingPath.yaml
    travel.html     <- src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.yaml
    guidebook.html  <- docs/guidebook/guidebook.yaml (the in-game book's text)
Map and NPC names for travel come from data/names.json (made with --names-from; WZ is not in this repo).
"""
import argparse
import html
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import OrderedDict

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
BOTS = os.path.join(REPO, "src", "main", "java", "soloMapling", "ArtificialPlayer")
LEVELING = os.path.join(BOTS, "BotGrindSystem", "LevelingPath.yaml")
TRANSIT = os.path.join(BOTS, "BotTravelSystem", "BotTransit.yaml")
GUIDE = os.path.join(REPO, "docs", "guidebook", "guidebook.yaml")
NAMES = os.path.join(HERE, "data", "names.json")
SITE = "SlumsMS Player Guide"

esc = html.escape


# ── layout ───────────────────────────────────────────────────────────────────────────────────────

def layout(title, body, nav, current):
    links = "".join(
        f'<li><a href="{f}"{" aria-current=page" if f == current else ""}>{esc(t)}</a></li>' for f, t in nav)
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(title)} - {SITE}</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<header class="top">
  <a class="logo" href="index.html"><span class="leaf">&#127809;</span> SlumsMS <small>player guide</small></a>
  <label class="menu-btn" for="menu">Menu</label>
</header>
<input type="checkbox" id="menu" hidden>
<div class="wrap">
<nav class="side"><div class="box"><div class="box-h">Pages</div><ul>{links}</ul></div></nav>
<main>
<div class="box"><div class="box-h"><h1>{esc(title)}</h1></div>
<div class="box-b">
{body}
</div></div>
</main>
</div>
<footer>SlumsMS - a private v83 world on MapleRoot. Fan project, not affiliated with Nexon.<br>
Built from the server repo by docs/wiki/build.py. Notes marked <span class="owner">owner check</span> need the owner to confirm.</footer>
</body>
</html>
"""


def read_src():
    pages = []
    for name in sorted(os.listdir(os.path.join(HERE, "src"))):
        if not name.endswith(".html"):
            continue
        with open(os.path.join(HERE, "src", name), encoding="utf-8") as f:
            text = f.read()
        m = re.match(r"<!--(.*?)-->\s*", text, re.S)
        meta = {}
        if m:
            for part in m.group(1).split("|"):
                if ":" in part:
                    k, v = part.split(":", 1)
                    meta[k.strip()] = v.strip()
            text = text[m.end():]
        pages.append({"file": name, "title": meta.get("title", name), "nav": int(meta.get("nav", 500)),
                      "blurb": meta.get("blurb", ""), "body": text})
    return pages


# ── names ────────────────────────────────────────────────────────────────────────────────────────

def refresh_names(wz):
    maps, npcs = {}, {}
    for region in ET.parse(os.path.join(wz, "String.wz", "Map.img.xml")).getroot():
        for m in region:
            d = {c.get("name"): c.get("value") for c in m}
            if d.get("mapName"):
                maps[m.get("name")] = {"street": d.get("streetName") or "", "map": d["mapName"]}
    for n in ET.parse(os.path.join(wz, "String.wz", "Npc.img.xml")).getroot():
        d = {c.get("name"): c.get("value") for c in n}
        if d.get("name"):
            npcs[n.get("name")] = d["name"]
    # keep only what the generated pages use
    with open(TRANSIT, encoding="utf-8") as f:
        edges = yaml.safe_load(f)["edges"]
    want_maps = {str(e[k]) for e in edges for k in ("from", "to")}
    want_npcs = {str(e[k]) for e in edges for k in ("npc", "seller") if k in e}
    out = {"source": "String.wz Map.img / Npc.img of the server WZ",
           "maps": {k: maps[k] for k in sorted(want_maps, key=int) if k in maps},
           "npcs": {k: npcs[k] for k in sorted(want_npcs, key=int) if k in npcs}}
    os.makedirs(os.path.dirname(NAMES), exist_ok=True)
    with open(NAMES, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    missing = (want_maps - set(out["maps"])) | (want_npcs - set(out["npcs"]))
    print(f"names.json: {len(out['maps'])} maps, {len(out['npcs'])} npcs, missing {sorted(missing) or 'none'}")


def load_names():
    if not os.path.exists(NAMES):
        return {"maps": {}, "npcs": {}}
    with open(NAMES, encoding="utf-8") as f:
        return json.load(f)


# ── generated: leveling ──────────────────────────────────────────────────────────────────────────

GROUP_LABEL = OrderedDict([
    ("general", "Any job"), ("warrior", "Warrior"), ("mage_fp", "Fire/Poison mage"), ("mage_il", "Ice/Lightning mage"),
    ("mage_cleric", "Cleric line"), ("archer", "Bowman"), ("thief", "Thief"), ("pirate_brawler", "Brawler line"),
    ("pirate_gunslinger", "Gunslinger line"), ("aran", "Aran"), ("cygnus", "Noblesse / Cygnus (starter)"),
])


def leveling_page():
    with open(LEVELING, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    out = ["""<p>Where people train, by level and job. This is the same list the town's regulars use to pick their
maps, so you will usually find company there. Maps are in rough order of popularity in each group
(bigger weight first). Monster levels are the range on the map.</p>
<p class="note">Cygnus Knights follow the matching explorer route after the Ereve starter band (Dawn Warrior &rarr;
warrior, Blaze Wizard &rarr; Fire/Poison, Wind Archer &rarr; bowman, Night Walker &rarr; thief, Thunder Breaker &rarr;
brawler). A job with no list of its own in a band uses the "Any job" list.</p>
<p>Some late maps need rebirths before you can enter (see <a href="rebirths.html">Rebirths</a>); they are marked
<span class="tag">R1</span>/<span class="tag">R2</span>/<span class="tag">R3</span>.</p>"""]
    out.append('<h2>Party quests</h2><div class="scroll"><table><tr><th>Party quest</th><th>Levels</th></tr>')
    for pq in data.get("party_quests", []):
        lo, hi = pq["levels"]
        out.append(f"<tr><td>{esc(pq['name'])}</td><td>{lo}-{hi}</td></tr>")
    out.append("</table></div>")
    out.append("<h2>Training spots by level</h2>")
    for band in data["bands"]:
        lo, hi = band["levels"]
        hi_txt = "200+" if lo >= 200 else f"{lo}-{hi - 1}"
        groups = band.get("groups", {})
        out.append(f'<details><summary><b>Level {hi_txt}</b> &mdash; {esc(band.get("label", ""))}</summary>')
        keys = [g for g in GROUP_LABEL if g in groups] + [g for g in groups if g not in GROUP_LABEL]
        for g in keys:
            entries = sorted(groups[g] or [], key=lambda e: -e.get("w", 1))
            if not entries:
                continue
            out.append(f'<h3>{esc(GROUP_LABEL.get(g, g))}</h3><div class="scroll"><table>'
                       '<tr><th>Map</th><th>Mob lv</th><th>Play</th></tr>')
            for e in entries:
                ml = e.get("mob_levels") or []
                mobs = f"{ml[0]}-{ml[1]}" if len(ml) == 2 and ml[0] != ml[1] else (str(ml[0]) if ml else "")
                tag = f' <span class="tag">R{e["rebirths"]}</span>' if e.get("rebirths") else ""
                out.append(f"<tr><td>{esc(e.get('name') or str(e['map']))}{tag}</td><td>{mobs}</td>"
                           f"<td>{esc(e.get('mode', 'solo'))}</td></tr>")
            out.append("</table></div>")
        out.append("</details>")
    out.append('<p class="src">Source: <code>LevelingPath.yaml</code> (ticket 31), built from '
               '<code>docs/real-bots/leveling-path-research.md</code>.</p>')
    return "\n".join(out)


# ── generated: travel ────────────────────────────────────────────────────────────────────────────

def travel_page(names):
    with open(TRANSIT, encoding="utf-8") as f:
        edges = yaml.safe_load(f)["edges"]
    maps, npcs = names.get("maps", {}), names.get("npcs", {})

    def mname(i):
        m = maps.get(str(i))
        if not m:
            return f"map {i}"
        street, name = m["street"].strip(), m["map"].strip()
        return name if not street or street in name or street == "Hidden Street" else f"{street}: {name}"

    def nname(i):
        return npcs.get(str(i), f"NPC {i}") if i is not None else ""

    def money(v):
        return "free" if not v else f"{v:,} mesos"

    # Group consecutive legs of one named route: a boarding NPC leg, then ride legs.
    routes = OrderedDict()
    for e in edges:
        if e["name"].startswith("world tour") or e["name"] == "platform":
            continue
        routes.setdefault(e["name"], []).append(e)

    out = ["""<p>Ways across the world that are not a walk through a portal. Fares and timings are the ones the
server's own NPC and event scripts use; the regulars ride the same boats on the same schedule.</p>
<p class="note">"Trip" is the longest it takes from boarding to arrival, waiting room included, at the normal
travel rate. Scheduled rides let you board for most of each cycle (every 5 minutes; the subway every minute).</p>"""]

    # Victoria cabs: one compact table
    cabs = routes.pop("cab", [])
    vic = [e for e in cabs if e.get("npc") in (1002007, 1012000, 1022001, 1032000, 1052016, 1092014)]
    if vic:
        out.append("<h2>Victoria Island regular cabs</h2>"
                   "<p>Every Victoria town (and Nautilus) has a cab to the others. "
                   f"Fare: {money(1000)} to most towns, {money(800)} to Ellinia and Nautilus; Beginners pay a tenth.</p>")
        out.append('<div class="scroll"><table><tr><th>From</th><th>To</th><th>Fare</th><th>Beginner</th></tr>')
        for e in vic:
            out.append(f"<tr><td>{esc(mname(e['from']))}</td><td>{esc(mname(e['to']))}</td>"
                       f"<td>{money(e.get('fare'))}</td><td>{money(e.get('beginner_fare'))}</td></tr>")
        out.append("</table></div>")
    rest = [e for e in cabs if e not in vic]
    if rest:
        routes["cab"] = rest

    out.append("<h2>Boats, trains, flights and other rides</h2>")
    out.append('<div class="scroll"><table><tr><th>Route</th><th>Board at</th><th>Talk to</th><th>Fare</th>'
               '<th>Arrive</th><th>Trip</th></tr>')
    seen = set()
    for name, legs in routes.items():
        board = [e for e in legs if e["via"] in ("npc", "portal")]
        rides = [e for e in legs if e["via"] == "ride"]
        for b in board:
            key = (name, b["from"])
            if key in seen:
                continue
            seen.add(key)
            # follow the chain of rides from this boarding leg
            dest, t, cur = b["to"], 0, b["to"]
            for _ in range(5):
                nxt = [r for r in rides if r["from"] == cur]
                if not nxt:
                    break
                t += nxt[0].get("sec", 0)
                cur = dest = nxt[0]["to"]
            talk = nname(b.get("npc")) if b["via"] == "npc" else f"portal {b.get('portal', '')}"
            if b.get("seller"):
                talk += f" (ticket from {nname(b['seller'])})"
            sched = ""
            if b.get("event"):
                sched = " &middot; boards every min" if b["event"] == "Subway" else " &middot; boards every 5 min"
            ride = f"up to {t // 60} min" if t >= 60 else (f"{t} s" if t else "short")
            out.append(f"<tr><td>{esc(name)}</td><td>{esc(mname(b['from']))}</td><td>{esc(talk)}</td>"
                       f"<td>{money(b.get('fare'))}</td><td>{esc(mname(dest))}</td><td>{ride}{sched}</td></tr>")
    out.append("</table></div>")
    out.append("""<h2>World Tour</h2>
<p>Spinel stands in the main towns of Victoria, Orbis, Ludibrium, Leafre, Mu Lung and Ariant. She takes you to
Mushroom Shrine (Japan) for 3,000 mesos and brings you back, free, to the town you left from.</p>
<h2>Orbis Station platforms</h2>
<p>In Orbis Station, Isa the Station Guide sends you to the right platform (Ellinia boat, Ludibrium train, Leafre
flight, Mu Lung crane, Ariant genie, Ereve ferry) for free.</p>
<p class="owner">The Cotton Warper in Base of Roots also warps you to boss maps (and, with 3 rebirths, Grandis and
Arcane River towns). Whether it has other menus on live is not checked here.</p>
<p class="src">Source: <code>BotTransit.yaml</code> (ticket 03), which mirrors <code>scripts/npc</code> and
<code>scripts/event</code>. Names: <code>docs/wiki/data/names.json</code>.</p>""")
    return "\n".join(out)


# ── generated: guidebook text ────────────────────────────────────────────────────────────────────

def guidebook_page():
    sys.path.insert(0, os.path.dirname(GUIDE))
    import compile_guide  # noqa: E402  (same folder as guidebook.yaml)
    doc, pages = compile_guide.load()
    out = ["<p>The text of the guide book every new character gets (ETC tab, double-click). "
           "Two pages show at a time in game.</p>"]
    for i in range(0, len(pages), 2):
        out.append('<div class="spread">')
        for j in (i, i + 1):
            if j < len(pages):
                p = pages[j]
                out.append(f'<div class="bookpage" id="p{j}"><div>{compile_guide.render(p["heading"])}</div>'
                           f'<div>{compile_guide.render(p["body"])}</div></div>')
        out.append("</div>")
    out.append('<p class="src">Source: <code>docs/guidebook/guidebook.yaml</code>. It reaches the game with a client '
               'release (see <code>docs/guidebook/CLIENT-STEP.md</code>).</p>')
    return "\n".join(out)


# ── main ─────────────────────────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--names-from", metavar="WZ", help="server wz/ dir to refresh data/names.json from")
    args = ap.parse_args()
    if args.names_from:
        refresh_names(args.names_from)
    names = load_names()
    pages = read_src()
    pages += [
        {"file": "leveling.html", "title": "Where to train", "nav": 45, "body": leveling_page(),
         "blurb": "Training maps and party quests for every level band and job."},
        {"file": "travel.html", "title": "Travel routes", "nav": 47, "body": travel_page(names),
         "blurb": "Cabs, boats, trains, flights: where to board, who to talk to, fares."},
        {"file": "guidebook.html", "title": "In-game guidebook", "nav": 90, "body": guidebook_page(),
         "blurb": "The text of the guide book new characters get."},
    ]
    pages.sort(key=lambda p: (p["nav"], p["file"]))
    nav = [(p["file"], p["title"]) for p in pages]
    cards = "\n".join(f'<li><a href="{p["file"]}">{esc(p["title"])}</a> &mdash; {esc(p["blurb"])}</li>'
                      for p in pages if p["file"] != "index.html" and p["blurb"])
    for p in pages:
        body = p["body"].replace("{{pages}}", f'<ul class="cards">{cards}</ul>')
        with open(os.path.join(HERE, p["file"]), "w", encoding="utf-8") as f:
            f.write(layout(p["title"], body, nav, p["file"]))
    print(f"wrote {len(pages)} pages to {os.path.relpath(HERE, os.getcwd())}/")


if __name__ == "__main__":
    main()
