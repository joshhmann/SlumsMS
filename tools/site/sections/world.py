"""World: the public "what's going on" pages. Live world lite, the news feed, leaderboards and the public economy.
build.py runs it with --world; nothing in it needs the sign-in.

Data:
- the server's PublicSiteExport (Server/data/site): feed.json (every 2 min), world.json (every 60 s) and
  leaderboards.json (every 15 min); schema 1, UTC ISO times, names only. Until it exists, the placeholders from
  tools/site/site_sample.py (cache/site-sample).
- the market export (sections/market.py) for the economy page: prices, the NX rate, economy-public.json.
A file with "sample": true puts a "sample data" banner on every World page.

Public rules (owner, 2026-10-26): bots and players are mixed on the boards with no label, like one server; no
richest board (a "richest" list in the export is ignored); traders show their two counts and a completion %, no grades;
the economy
page has no per-player data; drama items are about bots only (the server picks them; the site can't tell bots from
players and never tries). Fields that would identify an account, or say who is a bot, stop the build (validate()).
"""
import datetime as dt
import json
import os
import re
import shutil

import check
import common
from common import REPO, esc, layout, strf, write
from sections import market
from sections.market import area_chart, change, count, mesos, when

WORLD = "world"
DEFAULT_DIR = os.environ.get("SLUMS_SITE_DATA", os.path.join(REPO, "cache", "site-sample"))
SCHEMA = 1
FEED_KINDS = {"event": ("Events", "EVENT"), "milestone": ("Milestones", "MILESTONE"),
              "megaphone": ("Megaphones", "MEGA"), "drama": ("Drama", "DRAMA")}
FEED_MAX = 200
# never on a public page: account details (as for the market) and anything that says who is a bot
PRIVATE_FIELDS = market.PRIVATE_FIELDS | {"is_bot", "isbot", "bot", "is_player", "player_kind", "seller_kind", "kind_of"}


# ── data ────────────────────────────────────────────────────────────────────────────────────────

def load(data_dir=None):
    data_dir = data_dir or DEFAULT_DIR
    need = ["feed.json", "world.json", "leaderboards.json"]
    missing = [n for n in need if not os.path.isfile(os.path.join(data_dir, n))]
    if missing:
        raise SystemExit(f"{data_dir}: World data missing ({', '.join(missing)}); "
                         "run tools/site/site_sample.py or point --site-data at Server/data/site")
    out = {}
    for n in need:
        with open(os.path.join(data_dir, n), encoding="utf-8") as f:
            out[n[:-5]] = json.load(f)
    out["sample"] = any(v.get("sample") for v in out.values() if isinstance(v, dict))
    drop_qa(out)
    out = check.scrub(out)
    out["avatars"] = load_avatars(data_dir)
    return out


AVATAR_FILE = re.compile(r"^[0-9a-f]{16}\.png$")
AVATARS = {}  # IGN (as the boards show it) -> file name, for the build in progress; cell() reads it


def load_avatars(data_dir):
    """The ranking pictures (avatars/index.json next to leaderboards.json): {IGN: "<16 hex>.png"}, 96x96. Names are
    scrubbed like the boards' so the two match. A file that isn't there is left out (that row gets the placeholder)."""
    folder = os.path.join(data_dir, "avatars")
    try:
        with open(os.path.join(folder, "index.json"), encoding="utf-8") as f:
            idx = json.load(f)
    except (OSError, ValueError):
        return {"dir": folder, "map": {}}
    got = idx.get("avatars") if isinstance(idx, dict) else None
    m = {check.scrub(k): v for k, v in (got or {}).items()
         if isinstance(k, str) and isinstance(v, str) and AVATAR_FILE.match(v) and os.path.isfile(os.path.join(folder, v))}
    return {"dir": folder, "map": m}


# Auto-QA's pretend players (AutoQaOne, AutoQa53423) only exist on TEST, but bots gossip about them like anyone else.
# They never reach the site: feed items that name one are left out, and so are their board rows.
QA_NAME = re.compile(r"\bautoqa\w*", re.I)


def _names_qa(it):
    return isinstance(it, dict) and (any(isinstance(n, str) and QA_NAME.search(n) for n in it.get("names") or [])
                                     or QA_NAME.search(str(it.get("text", ""))) or QA_NAME.search(str(it.get("name", ""))))


def drop_qa(w):
    feed = w.get("feed")
    if isinstance(feed, dict) and isinstance(feed.get("items"), list):
        feed["items"] = [it for it in feed["items"] if not _names_qa(it)]
    def clean(v):
        if isinstance(v, list):
            return [clean(x) for x in v if not _names_qa(x)]
        if isinstance(v, dict):
            return {k: clean(x) for k, x in v.items()}
        return v
    if isinstance(w.get("leaderboards"), dict):
        w["leaderboards"] = clean(w["leaderboards"])


def _keys(obj, path=""):
    if isinstance(obj, dict):
        for k, v in obj.items():
            p = f"{path}.{k}" if path else k
            yield p, k
            yield from _keys(v, p)
    elif isinstance(obj, list):
        for i, v in enumerate(obj[:2000]):
            yield from _keys(v, f"{path}[{i}]")


def _is(v, t):
    return isinstance(v, t) and not isinstance(v, bool)


def validate(w):
    """Problems with the export files, as readable lines; empty when the data is safe to publish."""
    problems = []
    for name in ["feed", "world", "leaderboards"]:
        d = w[name]
        if not isinstance(d, dict):
            problems.append(f"{name}.json: should be an object")
            continue
        if d.get("schema") not in (None, SCHEMA):
            problems.append(f"{name}.json: schema {d.get('schema')}, this site reads schema {SCHEMA}")
        for path, k in _keys(d):
            if k.lower() in PRIVATE_FIELDS:
                problems.append(f"{name}.json: private field '{path}' must not be published")
    if problems:
        return problems
    items = w["feed"].get("items")
    if not isinstance(items, list):
        problems.append("feed.json: 'items' should be a list")
    else:
        for i, it in enumerate(items):
            if not (_is(it.get("text"), str) and _is(it.get("t"), str) and it.get("kind") in FEED_KINDS
                    and isinstance(it.get("names", []), list)):
                problems.append(f"feed.json: item {i} needs text, t, names[] and kind in {sorted(FEED_KINDS)}")
                break
    wd = w["world"]
    if not isinstance(wd.get("channels"), list) or not all(
            isinstance(c, dict) and _is(c.get("channel", c.get("ch")), int) and _is(c.get("online"), int)
            for c in wd["channels"]):
        problems.append("world.json: 'channels' should be a list of {channel, online}")
    if not isinstance(wd.get("busiest_maps", wd.get("busiestMaps", [])), list):
        problems.append("world.json: 'busiest_maps' should be a list")
    if wd.get("tonight") is not None and not isinstance(wd["tonight"], dict):
        problems.append("world.json: 'tonight' should be an object or null")
    for key, rows in w["leaderboards"].items():
        if key in ("schema", "sample", "generated_at"):
            continue
        lists = rows.values() if isinstance(rows, dict) else [rows]
        for lst in lists:
            if not isinstance(lst, list) or not all(isinstance(r, dict) and _is(r.get("name"), str) for r in lst):
                problems.append(f"leaderboards.json: '{key}' rows each need a name")
                break
    return problems


def pt_day(iso):
    t = dt.datetime.fromisoformat(iso)
    if t.tzinfo is None:
        t = t.replace(tzinfo=dt.timezone.utc)
    t = t.astimezone(market.PT) if market.PT else t - dt.timedelta(hours=7)
    return t.date()


def clock(iso):
    """The time part of market.when: '8:00 PM PT (03:00 UTC)'."""
    return when(iso).rsplit(", ", 1)[0]


# ── pieces ──────────────────────────────────────────────────────────────────────────────────────

class World:
    def __init__(self, w, m, out, db=None):
        self.w, self.m, self.out, self.db = w, m, out, db
        self.links_db = db is not None and "database" in common.BUILT

    def map_name(self, map_id, fallback, root):
        """A map's name, linked to its Slums Street page when this build has the database."""
        name = fallback if isinstance(fallback, str) else None
        if not _is(map_id, int):
            return esc(name or "somewhere")
        if self.db and map_id in self.db.get("maps", {}):
            name = name or self.db["maps"][map_id].get("name")
            if self.links_db:
                return f'<a href="{root}database/maps/{map_id}.html">{esc(name)}</a>'
        return esc(name or f"Map {map_id}")

    def text(self, it):
        """Feed text with the names in bold; everything escaped."""
        s = esc(it["text"])
        for n in sorted({n for n in it.get("names") or [] if isinstance(n, str) and n}, key=len, reverse=True):
            s = re.sub(rf"(?<![\w>]){re.escape(esc(n))}(?![\w<])", f"<b>{esc(n)}</b>", s)
        return s

    def feed_rows(self, items, root):
        rows = []
        for it in items:
            label, tag = FEED_KINDS[it["kind"]]
            where = []
            if it.get("map"):
                where.append(self.map_name(it["map"], None, root))
            if it.get("channel"):
                where.append(f'ch{int(it["channel"])}')
            top = ""
            if it.get("top"):
                top = ('<p class="topdmg">Top damage: ' + " &middot; ".join(
                    f'<span class="muted num">{i}.</span> <b>{esc(x["name"])}</b> <span class="num">{mesos(x.get("damage"))}</span>'
                    for i, x in enumerate([x for x in it["top"] if isinstance(x, dict) and x.get("name")][:3], 1)) + "</p>")
            rows.append(f'<li class="fk-{it["kind"]}"><span class="ntag {it["kind"]}">{tag}</span>'
                        f'<div><p>{self.text(it)}</p>{top}'
                        + (f'<span class="muted small">{" &middot; ".join(where)}</span>' if where else "")
                        + f'</div><time datetime="{esc(it["t"])}">{esc(clock(it["t"]))}</time></li>')
        return "".join(rows)

    def sidebar(self, root, here):
        links = [("index.html", "Live world", "index"), ("feed/index.html", "What's happening", "feed"),
                 ("rankings/index.html", "Rankings", "rankings"), ("economy.html", "Economy", "economy")]
        lis = "".join(f'<li><a href="{root}{WORLD}/{h}"{" aria-current=page" if k == here else ""}>{esc(t)}</a></li>'
                      for h, t, k in links)
        if "market" in common.BUILT:
            more = f'<li><a href="{root}market/index.html">Slums Market</a></li>'
        else:  # the market is for signed-in players
            more = f'<li><a href="{root}login/index.html?next=/market/">Slums Market <span class="muted">(sign in)</span></a></li>'
        return f'<nav class="panel dbside" aria-label="World"><p class="bar">World</p><ul class="mk">{lis}{more}</ul></nav>'

    def page(self, rel, title, body, here, description=""):
        root = "../" * rel.count("/")
        banner = ('<p class="samplebar">Sample data: these names and numbers are placeholders until the server export '
                  'is live.</p>') if self.w["sample"] or self.m["sample"] else ""
        html = (f'<div class="db world">{self.sidebar(root, here)}<div class="dbmain data">{banner}'
                f'{body.replace("{root}", root)}</div></div>')
        write(self.out, rel, layout(title, html, root, WORLD, description))


def bars(points, label, a_label, b_label):
    """Daily paired bars: points [(date, a, b)], e.g. mesos made (a) and spent (b)."""
    W, H, L, R, T, B = 680, 220, 54, 12, 14, 30
    vals = [v for _, a, b in points for v in (a, b) if v is not None]
    if len(points) < 2 or not vals:
        return '<p class="empty">Not enough days yet for a chart.</p>'
    ticks = market._ticks(0, max(vals))
    top = ticks[-1] or 1
    slot = (W - L - R) / len(points)
    bw = max(2.0, slot * 0.36)
    Y = lambda v: T + (top - v) * (H - T - B) / top  # noqa: E731
    s = [f'<line class="ch-grid" x1="{L}" x2="{W - R}" y1="{Y(t):.1f}" y2="{Y(t):.1f}"/>'
         f'<text class="ch-label" x="{L - 8}" y="{Y(t) + 4:.1f}" text-anchor="end">{mesos(int(t))}</text>' for t in ticks]
    for i, (_, a, b) in enumerate(points):
        x = L + i * slot + slot / 2
        if a is not None:
            s.append(f'<rect class="bar-a" x="{x - bw - 0.5:.1f}" y="{Y(a):.1f}" width="{bw:.1f}" height="{Y(0) - Y(a):.1f}" rx="1.5"/>')
        if b is not None:
            s.append(f'<rect class="bar-b" x="{x + 0.5:.1f}" y="{Y(b):.1f}" width="{bw:.1f}" height="{Y(0) - Y(b):.1f}" rx="1.5"/>')
    for i in sorted({0, len(points) // 2, len(points) - 1}):
        d = dt.date.fromisoformat(points[i][0])
        s.append(f'<text class="ch-label" x="{L + i * slot + slot / 2:.1f}" y="{H - 8}" text-anchor="middle">{strf(d, "%b %-d")}</text>')
    return (f'<svg class="chart" viewBox="0 0 {W} {H}" role="img" aria-label="{esc(label)}">{"".join(s)}</svg>'
            f'<p class="legend"><span><i class="a"></i>{esc(a_label)}</span><span><i class="b"></i>{esc(b_label)}</span></p>')


def countdown(iso):
    """A start time in PT and UTC; assets/world.js adds 'in 2 h 14 min' next to it."""
    return f'<time datetime="{esc(iso)}" data-countdown>{esc(when(iso))}</time>'


# ── pages ───────────────────────────────────────────────────────────────────────────────────────

def build_live(wo):
    wd = wo.w["world"]
    chans = sorted(({"ch": c.get("channel", c.get("ch")), "online": c["online"]} for c in wd["channels"]),
                   key=lambda c: c["ch"])
    total = wd["online"] if _is(wd.get("online"), int) else sum(c["online"] for c in chans)
    busiest = wd.get("busiest_maps", wd.get("busiestMaps")) or []
    peak = max([c["online"] for c in chans] + [1])
    crow = "".join(f'<li><span class="k">Ch {c["ch"]}</span><span class="meter"><i style="width:{c["online"] * 100 / peak:.0f}%"></i></span>'
                   f'<b class="num">{c["online"]:,}</b></li>' for c in chans)
    maps = "".join(f'<tr><td class="r num">{i}</td><td>{wo.map_name(r.get("map"), r.get("name"), "{root}")}</td>'
                   f'<td class="r num">{count(r.get("count"))}</td></tr>' for i, r in enumerate(busiest, 1))
    tn = wd.get("tonight") or {}
    start = tn.get("start") or tn.get("startsAt")
    # contract: map is the planned map's NAME, map_id its id (absent for a treasure hunt); name null until midnight PT
    where_ = (f' &middot; {wo.map_name(tn.get("map_id"), tn.get("map"), "{root}")}' if tn.get("map") or tn.get("map_id") else "")
    if start:
        label = "On now" if tn.get("status") == "running" else "Tonight"
        tonight = (f'<div class="eventcard"><span class="k">{label}</span>'
                   f'<b>{esc(tn["name"]) if tn.get("name") else "Picked at midnight PT"}</b>'
                   f'<span>{countdown(start)}{where_}</span></div>')
    else:
        tonight = ""
    running = "".join(f'<li><b>{esc(r.get("name") or "An event")}</b>'
                      + (f' in {wo.map_name(r.get("map_id"), r.get("map"), "{root}")}' if r.get("map") or r.get("map_id") else "")
                      + (f' &middot; ch{int(r["channel"])}' if r.get("channel") else "")
                      + (f' <span class="muted">until {esc(clock(r["endsAt"]))}</span>' if r.get("endsAt") else "")
                      + '</li>' for r in wd.get("running") or [] if isinstance(r, dict))
    feed = wo.feed_rows((wo.w["feed"].get("items") or [])[:12], "{root}")
    as_of = wd.get("generated_at")
    body = (f'<div class="headfig"><div><h1>Live world</h1><p class="id">Bera right now{" &middot; as of " + esc(when(as_of)) if as_of else ""}'
            f' &middot; refreshes every couple of minutes</p></div>'
            f'<img class="sidekick" src="{{root}}assets/brand/full-crossed-360.webp" srcset="{{root}}assets/brand/full-crossed-360.webp 360w, '
            f'{{root}}assets/brand/full-crossed-560.webp 560w" sizes="140px" alt="Slums-chan stands with her arms crossed and a smug grin" width="360" height="554"></div>'
            f'<div class="mkstats"><div class="hot"><small>Online now</small><b>{total:,}</b><span>across {len(chans)} channels</span></div>'
            + (f'<div><small>Busiest map</small><b class="sm">{wo.map_name(busiest[0].get("map"), busiest[0].get("name"), "{root}")}</b>'
               f'<span>{count(busiest[0].get("count"))} here</span></div>' if busiest else "")
            + (f'<div><small>Busiest channel</small><b>Ch {max(chans, key=lambda c: c["online"])["ch"]}</b>'
               f'<span>{max(c["online"] for c in chans):,} online</span></div>' if chans else "")
            + '</div>'
            + tonight
            + (f'<section class="panel"><h2 class="bar">Happening now</h2><ul class="running">{running}</ul></section>' if running else "")
            + f'<div class="mkhome"><section class="panel"><h2 class="bar">Channels</h2><ul class="chans">{crow}</ul></section>'
            f'<section class="panel"><h2 class="bar">Busiest maps</h2><div class="tbl"><table class="t"><thead><tr><th class="r">#</th>'
            f'<th>Map</th><th class="r">Here</th></tr></thead><tbody>{maps}</tbody></table></div></section></div>'
            f'<section class="panel"><h2 class="bar">What\'s happening <a href="{{root}}{WORLD}/feed/index.html">All &raquo;</a></h2>'
            + (f'<ul class="board feed">{feed}</ul>' if feed else '<p class="empty nap"><img src="{root}assets/brand/bubble-chin-nap-320.webp" alt="" width="160" height="143" loading="lazy">'
               '<span>Quiet so far. Events, milestones and megaphones show up here as they happen.</span></p>') + '</section>')
    wo.page(f"{WORLD}/index.html", "Live world", body, "index", "Who's online on Bera, the busiest maps and tonight's event.")
    # the home page's "Online now" reads this (theme.js); it's rebuilt with World every couple of minutes
    write(wo.out, f"{WORLD}/online.json", json.dumps({"online": total, "generated_at": as_of}))
    return total


def build_feed(wo):
    items = (wo.w["feed"].get("items") or [])[:FEED_MAX]
    tabs = [("index.html", "Everything", None)] + [(f"{k}.html", v[0], k) for k, v in FEED_KINDS.items()]
    for href, label, kind in tabs:
        rows = [it for it in items if kind is None or it["kind"] == kind]
        nav = "".join(f'<a href="{{root}}{WORLD}/feed/{h}" class="chip"{" aria-current=page" if k == kind else ""}>{esc(t)}</a>'
                      for h, t, k in tabs)
        days, cur = [], None
        for it in rows:
            d = pt_day(it["t"])
            if d != cur:
                days.append((d, []))
                cur = d
            days[-1][1].append(it)
        sections = "".join(f'<section class="panel"><h2 class="bar">{strf(d, "%A, %B %-d")} <small>Pacific</small></h2>'
                           f'<ul class="board feed">{wo.feed_rows(its, "{root}")}</ul></section>' for d, its in days)
        intro = {None: "World events with the top damage, milestones, the day's megaphones and the town's drama.",
                 "event": "World events: the 8 PM PT Henesys event and the small ones around it, with the top damage.",
                 "milestone": "4th job, level 120 and 200, rebirths, new guilds and weddings.",
                 "megaphone": "What people shouted today.",
                 "drama": "Who's feuding, who broke up, who made up."}[kind]
        body = (f'<h1>What\'s happening</h1><p class="id">{esc(intro)}</p><p class="chips">{nav}</p>'
                + (sections or '<p class="empty">Nothing yet.</p>'))
        wo.page(f"{WORLD}/feed/{href}", "What's happening" if kind is None else f"{label}: what's happening", body, "feed",
                "The SlumsMS news feed: events, milestones, megaphones and drama.")
    return len(items)


# (field, heading, how to show it); a column shows only when some row has the field
COLS = {
    "level": [("name", "Character", "char"), ("level", "Level", "int"), ("rebirths", "Rebirths", "int"),
              ("job", "Job", "job"), ("guild", "Guild", "text")],
    "fame": [("name", "Character", "char"), ("fame", "Fame", "int"), ("job", "Job", "job")],
    "guilds": [("name", "Guild", "name"), ("level", "Level", "int"), ("members", "Members", "int"),
               ("points", "Points", "int"), ("leader", "Leader", "char")],
    "traders": [("name", "Character", "char"), ("completed", "Trades done", "int"), ("fellThrough", "Fell through", "int"),
                ("pct", "Completed", "pct")],
    "dojo": [("name", "Character", "char"), ("floor", "Floor", "int"), ("points", "Points", "int"), ("time_s", "Time", "time")],
    "bosses": [("name", "Character", "char"), ("clears", "Clears", "int"), ("boss", "Boss", "text")],
}
BOARDS = [("level", "Level", "Level and rebirths: rebirths first, then level."),
          ("fame", "Fame", "The most famous characters on Bera."),
          ("guilds", "Guilds", "Guild points, then members."),
          ("traders", "Traders", "Finished trades and the ones that fell through, for everyone with at least 5 trades."),
          ("bosses", "Boss clears", "Expedition clears of Zakum, Horntail and Pink Bean.")]
# Dojo (COLS["dojo"]) waits: the server records no best floor or time yet, so 1.6.0 has no Dojo board.


# The export's job names are the server's enum names title-cased ("Fp_archmage"); these read as the game says them.
# Cygnus jobs keep their tier number out ("Blazewizard" covers all four). Anything else is shown as sent.
JOB_NAMES = {"fp_wizard": "Wizard (F/P)", "fp_mage": "Mage (F/P)", "fp_archmage": "Arch Mage (F/P)",
             "il_wizard": "Wizard (I/L)", "il_mage": "Mage (I/L)", "il_archmage": "Arch Mage (I/L)",
             "darkknight": "Dark Knight", "dragonknight": "Dragon Knight", "whiteknight": "White Knight",
             "chiefbandit": "Chief Bandit", "nightlord": "Night Lord", "dawnwarrior": "Dawn Warrior",
             "blazewizard": "Blaze Wizard", "windarcher": "Wind Archer", "nightwalker": "Night Walker",
             "thunderbreaker": "Thunder Breaker", "crossbowman": "Crossbowman", "gm": "GM", "supergm": "GM"}


def job_name(v):
    key = v.lower().rstrip("1234")
    return JOB_NAMES.get(key, v.replace("_", " "))


def cell(v, how):
    if v is None or v == "":
        return '<td class="muted">-</td>'
    if how == "int":
        return f'<td class="r num">{count(v) if _is(v, int) else esc(str(v))}</td>'
    if how == "exact":
        return f'<td class="r num">{v:,}</td>' if _is(v, int) else f'<td class="r">{esc(str(v))}</td>'
    if how == "pct":
        return f'<td class="r num">{v}%</td>' if _is(v, int) else '<td class="muted r">-</td>'
    if how == "time":
        return f'<td class="r num">{v // 60}:{v % 60:02d}</td>' if _is(v, int) else f'<td>{esc(str(v))}</td>'
    if how == "name":
        return f'<td><b>{esc(str(v))}</b></td>'
    if how == "char":  # a character: its ranking picture (cached forever, the name changes with the look) or a blank
        f = AVATARS.get(str(v))
        pic = (f'<img class="ava" src="{{root}}world/avatars/{f}" alt="" width="48" height="48" loading="lazy" decoding="async">'
               if f else '<span class="ava none" aria-hidden="true"></span>')
        return f'<td class="who-cell">{pic}<b>{esc(str(v))}</b></td>'
    if how == "job":
        return f'<td>{esc(job_name(str(v)))}</td>'
    return f'<td>{esc(str(v))}</td>'


def table(kind, rows, limit=None):
    rows = [r for r in rows if isinstance(r, dict)][:limit]
    if not rows:
        return '<p class="empty" style="padding:12px 16px;margin:0">Nobody yet.</p>'
    cols = [c for c in COLS[kind] if any(r.get(c[0]) not in (None, "") for r in rows)]
    head = '<th class="r">#</th>' + "".join(f'<th{" class=r" if h in ("int", "exact", "time") else ""}>{esc(t)}</th>' for _, t, h in cols)
    body = "".join(f'<tr{" class=podium" if i <= 3 else ""}><td class="r num">{r.get("rank", i)}</td>'
                   + "".join(cell(r.get(f), h) for f, _, h in cols) + "</tr>" for i, r in enumerate(rows, 1))
    return f'<div class="tbl"><table class="t rank"><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


def board_rows(lb, kind):
    rows = lb.get(kind) or []
    if kind == "bosses" and isinstance(rows, dict):  # per boss: {boss: [rows]}
        rows = [dict(r, boss=b) for b, rs in rows.items() for r in rs if isinstance(r, dict)]
        rows.sort(key=lambda r: -(r.get("clears") or 0))
    if not isinstance(rows, list):
        return []
    if kind == "traders":  # the completion % is worked out here, from the two counts
        out = []
        for r in rows:
            if not isinstance(r, dict):
                continue
            done, fell = r.get("completed"), r.get("fellThrough") or 0
            if _is(done, int) and _is(fell, int) and done + fell > 0:
                r = dict(r, pct=round(100 * done / (done + fell)))
            out.append(r)
        rows = out
    return rows


def build_rankings(wo):
    lb = wo.w["leaderboards"]
    # ranking pictures: only the ranked names' files are copied in; cell() shows them
    av = wo.w.get("avatars") or {"map": {}}
    names = set()
    def walk(v):
        if isinstance(v, dict):
            for k in ("name", "leader"):
                if isinstance(v.get(k), str):
                    names.add(v[k])
            for x in v.values():
                walk(x)
        elif isinstance(v, list):
            for x in v:
                walk(x)
    walk(lb)
    AVATARS.clear()
    AVATARS.update({n: f for n, f in av["map"].items() if n in names})
    if AVATARS:
        os.makedirs(os.path.join(wo.out, WORLD, "avatars"), exist_ok=True)
        for f in set(AVATARS.values()):
            shutil.copyfile(os.path.join(av["dir"], f), os.path.join(wo.out, WORLD, "avatars", f))
    tabs = "".join(f'<a href="{{root}}{WORLD}/rankings/{k}.html" class="chip">{esc(t)}</a>' for k, t, _ in BOARDS)
    for kind, title, intro in BOARDS:
        rows, limit = board_rows(lb, kind), 100
        body = (f'<h1>{esc(title)}</h1><p class="id">{esc(intro)}</p><p class="chips">{tabs}</p>'
                f'<section class="panel"><h2 class="bar">{esc(title)}</h2>{table(kind, rows, limit)}</section>')
        if kind == "bosses" and isinstance(lb.get("bosses"), dict):  # one table per boss under the overall one
            body += "".join(f'<section class="panel" id="{common.slug(b)}"><h2 class="bar">{esc(b)}</h2>'
                            f'{table("bosses", [{k: v for k, v in r.items() if k != "boss"} for r in rs if isinstance(r, dict)], 20)}</section>'
                            for b, rs in lb["bosses"].items() if isinstance(rs, list))
        if kind == "level":
            by_class = lb.get("levelByClass") or {}
            body += "".join(f'<section class="panel" id="{common.slug(c)}"><h2 class="bar">{esc(c)}</h2>{table("level", rs, 10)}</section>'
                            for c, rs in by_class.items())
            if by_class:
                body = body.replace('<section class="panel">', '<p class="chips small">By class: ' + "".join(
                    f'<a href="#{common.slug(c)}">{esc(c)}</a> ' for c in by_class) + '</p><section class="panel">', 1)
        wo.page(f"{WORLD}/rankings/{kind}.html", f"{title} rankings", body, "rankings")
    # the overview: the top five of each
    cards = "".join(f'<section class="panel"><h2 class="bar">{esc(t)} <a href="{{root}}{WORLD}/rankings/{k}.html">All &raquo;</a></h2>'
                    f'{table(k, board_rows(lb, k), 5)}</section>' for k, t, _ in BOARDS)
    as_of = f' &middot; as of {esc(when(lb["generated_at"]))}' if lb.get("generated_at") else ""
    body = (f'<div class="headfig"><div><h1>Rankings</h1><p class="id">Everyone on Bera on one board, like it should be{as_of} &middot; '
            f'refreshes every 15 minutes</p></div>'
            f'<img class="sidekick" src="{{root}}assets/brand/full-cap-tip-360.webp" srcset="{{root}}assets/brand/full-cap-tip-360.webp 360w, '
            f'{{root}}assets/brand/full-cap-tip-560.webp 560w" sizes="140px" alt="Slums-chan tips her cap with a big grin" width="360" height="559"></div>'
            f'<div class="rankgrid">{cards}</div>')
    wo.page(f"{WORLD}/rankings/index.html", "Rankings", body, "rankings", "Level, fame, guild, trader, Dojo and boss rankings on Bera.")


def build_economy(wo):
    """Public: totals and prices only, nothing about one player (owner, 2026-10-26)."""
    m = wo.m
    e = m["economy-public"]
    days = [d for d in e.get("days") or [] if isinstance(d, dict) and d.get("date")][-market.HISTORY_DAYS:]
    totals = [d["mesos_total"] for d in days if _is(d.get("mesos_total"), int)]
    first = totals[0] if len(totals) >= 2 else None  # a trend needs two days
    last = next((d for d in reversed(days) if d.get("mesos_made") is not None), days[-1] if days else {})
    tiles = (f'<div class="mkstats"><div class="hot"><small>Mesos in the game</small><b>{mesos(e.get("mesos_total"))}</b>'
             f'<span>players and bots</span></div>'
             f'<div><small>{len(days)}-day trend</small><b>{change(first, e.get("mesos_total")) or "-"}</b><span>total mesos</span></div>'
             f'<div><small>Made {esc(last.get("date", ""))}</small><b>{mesos(last.get("mesos_made"))}</b><span>drops, quests, NPC sales</span></div>'
             f'<div><small>Spent {esc(last.get("date", ""))}</small><b>{mesos(last.get("mesos_spent"))}</b><span>shops, fees, taxes</span></div></div>')
    if len(totals) >= 2:
        trend = area_chart([(d["date"], d.get("mesos_total"), d.get("mesos_total"), d.get("mesos_total")) for d in days],
                           "Total mesos per day", "Mesos held, players and bots")
        flow = bars([(d["date"], d.get("mesos_made"), d.get("mesos_spent")) for d in days], "Mesos made and spent per day",
                    "Made", "Spent")
    else:
        trend = flow = '<p class="empty">The daily totals start once the server keeps them.</p>'
    # prices: the most traded items, no sellers
    priced = []
    for item_id in market.traded_items(m):
        st = market.item_stats(market.item_history(m, item_id))
        if st:
            priced.append((item_id, st))
    priced.sort(key=lambda x: -x[1]["sold"])
    names = market.Market(m, {"items": {}}, wo.out)
    prow = "".join(f'<tr><td>{esc(names.name(i))}</td><td class="r num">{mesos(st["median"]) if st["median"] else "-"}</td>'
                   f'<td class="r">{change(st["first"], st["median"])}</td><td class="r num">{st["sold"]:,}</td></tr>'
                   for i, st in priced[:20])
    nx = m["nx-rate"].get("days") or []
    nxpts = [(d["date"], d.get("mesos_per_1000nx"), d.get("mesos_per_1000nx"), d.get("mesos_per_1000nx")) for d in nx]
    rate = next((d.get("mesos_per_1000nx") for d in reversed(nx) if d.get("mesos_per_1000nx")), None)
    market_link = ("market/index.html" if "market" in common.BUILT else "login/index.html?next=/market/")
    body = (f'<h1>Bera economy</h1><p class="id">Totals for the whole world, players and bots together &middot; as of '
            f'{esc(when(e["as_of"]))}</p>{tiles}'
            f'<section class="panel"><h2 class="bar">Mesos in the game <small>per day</small></h2><div class="in">{trend}</div></section>'
            f'<section class="panel"><h2 class="bar">Made vs spent <small>per UTC day</small></h2><div class="in">{flow}</div></section>'
            f'<section class="panel"><h2 class="bar">NX rate <small>{"1,000 NX = " + mesos(rate) + " mesos" if rate else "not enough trades yet"}</small></h2>'
            f'<div class="in">{area_chart(nxpts, "NX rate per day", "Mesos per 1,000 NX")}</div></section>'
            f'<section class="panel"><h2 class="bar">Prices <a href="{{root}}{market_link}">Slums Market &raquo;</a></h2>'
            f'<div class="tbl"><table class="t"><thead><tr><th>Item</th><th class="r">Median</th><th class="r">{market.HISTORY_DAYS} days</th>'
            f'<th class="r">Sold</th></tr></thead><tbody>{prow}</tbody></table></div>'
            f'<p class="small" style="padding:6px 16px 10px;margin:0">The {min(20, len(priced))} most traded items. Every price, '
            f'the FM shops open now and recent sales are in the Slums Market (players only: sign in with your game account).</p></section>')
    wo.page(f"{WORLD}/economy.html", "Bera economy", body, "economy", "Mesos in the game, made vs spent, the NX rate and prices on Bera.")


def build_hot(wo):
    """world/hot.json for Slums Street's "Hot right now" panel (filled by theme.js): the busiest maps, the most-sold
    items over 7 days and, once the server exports them, the most-hunted monsters. Names, ids and counts only. It's
    rebuilt with World every couple of minutes; Slums Street itself only rebuilds in a full build."""
    wd, db = wo.w["world"], wo.db or {}
    def name(kind, i, fallback=None):
        rec = db.get(kind, {}).get(i) if _is(i, int) else None
        return (fallback if isinstance(fallback, str) and fallback else None) or (rec or {}).get("name") or f"#{i}"
    maps = [{"id": r.get("map"), "name": name("maps", r.get("map"), r.get("name")), "count": r.get("count"),
             "page": r.get("map") in db.get("maps", {})}
            for r in (wd.get("busiest_maps") or wd.get("busiestMaps") or [])[:5] if isinstance(r, dict)]
    mobs = [{"id": r.get("mob"), "name": name("mobs", r.get("mob"), r.get("name")), "count": r.get("kills"),
             "page": r.get("mob") in db.get("mobs", {})}
            for r in (wd.get("top_mobs") or [])[:5] if isinstance(r, dict) and _is(r.get("kills"), int)]
    sold = []
    for i in market.traded_items(wo.m):
        n = sum((r or {}).get("count", 0) for _, r in market.item_history(wo.m, i)[-7:])
        if n:
            sold.append((n, i))
    items = [{"id": i, "name": name("items", i), "count": n} for n, i in sorted(sold, reverse=True)[:5]]
    write(wo.out, f"{WORLD}/hot.json", json.dumps({"generated_at": wd.get("generated_at"), "maps": maps, "mobs": mobs,
                                                     "items": items}))


def build_traders(wo):
    """world/traders.json for My market's "My trades" (filled by market.js): {name: [completed, fell through]}, the
    same rows as the public Traders board, so only characters with at least 5 trades are in it."""
    rows = {r["name"]: [r.get("completed") or 0, r.get("fellThrough") or 0] for r in board_rows(wo.w["leaderboards"], "traders")
            if isinstance(r.get("name"), str) and _is(r.get("completed"), int)}
    write(wo.out, f"{WORLD}/traders.json", json.dumps(rows))


def build(out, data_dir=None, market_dir=None, db=None):
    w = load(data_dir)
    problems = validate(w)
    if problems:
        raise SystemExit("World data refused:\n  " + "\n  ".join(problems))
    m = market.load(market_dir)
    problems = market.validate(m)
    if problems:
        raise SystemExit("market data refused (World economy page):\n  " + "\n  ".join(problems))
    shutil.rmtree(os.path.join(out, WORLD), ignore_errors=True)
    wo = World(w, m, out, db)
    online = build_live(wo)
    n = build_feed(wo)
    build_rankings(wo)
    build_economy(wo)
    build_hot(wo)
    build_traders(wo)
    return {"online": online, "feed": n, "sample": w["sample"] or m["sample"]}
