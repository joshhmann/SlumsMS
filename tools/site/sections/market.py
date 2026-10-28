"""Market (the Slums Market, after the old Basil Market): prices with history, FM listings now, recent sales,
the NX rate and a few economy numbers.
Private until 1.6.0 is live: build.py only runs it with --private.

Data: the server's PublicMarketExport files (docs/design/track-d-plan.md, "Market export contract"), or until those
exist the placeholders from tools/site/market_sample.py (cache/market-sample). A file with "sample": true puts a
"sample data" banner on every market page. Charts are SVG drawn at build time and coloured by CSS variables, so they
follow light and dark mode with no script.

Public rules (plan §5): a day's median shows only with at least MIN_SAMPLES sales; the NX rate is null (shown as "not
enough trades") under 5 trades. Bots and players are one server (owner, 2026-10-26): no page says who is a bot, so the
export's bot/player fields (seller_kind, player_share, bot_share, bots_online_peak ...) are never shown.
"""
import datetime as dt
import glob
import json
import os
import re
import shutil

import check
import common
from common import REPO, esc, layout, strf, write
from sections import buddy
from sections.database import fallback_icon, scrub

MARKET = "market"
DEFAULT_DIR = os.environ.get("SLUMS_MARKET_DATA", os.path.join(REPO, "cache", "market-sample"))
MIN_SAMPLES = 3
NX_CARDS = {4031865: "NX Card (100 NX)", 4031866: "NX Card (250 NX)"}
HISTORY_DAYS = 30

try:
    from zoneinfo import ZoneInfo
    PT = ZoneInfo("America/Los_Angeles")
except Exception:  # no tz database: PDT until 2026-11-01, PST after
    PT = None


# ── data ────────────────────────────────────────────────────────────────────────────────────────

def _read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load(data_dir=None):
    data_dir = data_dir or DEFAULT_DIR
    need = ["listings.json", "sales.json", "nx-rate.json", "economy-public.json"]
    missing = [n for n in need if not os.path.isfile(os.path.join(data_dir, n))]
    if missing or not glob.glob(os.path.join(data_dir, "prices", "????-??-??.json")):
        raise SystemExit(f"{data_dir}: market data missing ({', '.join(missing) or 'prices/'}); "
                         "run tools/site/market_sample.py or point --market-data at the export")
    m = {n[:-5]: _read(os.path.join(data_dir, n)) for n in need}
    days = sorted(p for p in glob.glob(os.path.join(data_dir, "prices", "*.json"))
                  if re.fullmatch(r"\d{4}-\d\d-\d\d\.json", os.path.basename(p)))[-HISTORY_DAYS:]  # not index.json
    m["prices"] = [_read(p) for p in days]
    m["sample"] = any(isinstance(v, dict) and v.get("sample") for v in m.values()) or any(p.get("sample") for p in m["prices"])
    return check.scrub(m)


SCHEMA = 1
# (file, list key or None, required fields per row or per file)
SHAPES = [("listings", "listings", {"item_id": int, "qty": int, "price": int, "channel": int}),
          ("sales", "sales", {"item_id": int, "price": int, "qty": int, "source": str, "hour": str}),
          ("nx-rate", "days", {"date": str, "n_trades": int}),
          ("economy-public", None, {"as_of": str})]
# Never public (plan §5 privacy rules): a field with one of these names anywhere in an export file stops the build.
# Real personal information never goes on the site (owner, 2026-10-26); character and guild names are fine.
PRIVATE_FIELDS = {"account", "account_id", "accountid", "login", "username", "ip", "ip_address", "email", "password",
                  "hwid", "mac", "macs", "birthday", "birthdate", "dob", "pic", "pin", "character_id", "char_id",
                  "session", "whisper", "whispers", "buddy_chat", "party_chat"}


def _walk_keys(obj, path=""):
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield f"{path}.{k}" if path else k, k
            yield from _walk_keys(v, f"{path}.{k}" if path else k)
    elif isinstance(obj, list):
        for i, v in enumerate(obj[:2000]):
            yield from _walk_keys(v, f"{path}[{i}]")


def validate(m):
    """Problems with the export files, as readable lines; empty when the data is safe to build from."""
    problems = []
    for name in ["listings", "sales", "nx-rate", "economy-public"]:
        v = m[name].get("schema")
        if v not in (None, SCHEMA) and not m[name].get("sample"):
            problems.append(f"{name}.json: schema {v}, this site reads schema {SCHEMA}")
    for name, key, fields in SHAPES:
        rows = m[name].get(key) if key else [m[name]]
        if not isinstance(rows, list):
            problems.append(f"{name}.json: '{key}' should be a list")
            continue
        for i, r in enumerate(rows):
            for f, t in fields.items():
                if not isinstance(r.get(f), t) or isinstance(r.get(f), bool):
                    problems.append(f"{name}.json: row {i} needs '{f}' as {t.__name__}, has {r.get(f)!r}")
                    break
            if len(problems) > 20:
                return problems
    for p in m["prices"]:
        if not isinstance(p.get("items"), dict):
            problems.append(f"prices/{p.get('date')}.json: 'items' should be an object")
    for name in ["listings", "sales", "nx-rate", "economy-public"]:
        for path, k in _walk_keys(m[name]):
            if k.lower() in PRIVATE_FIELDS:
                problems.append(f"{name}.json: private field '{path}' must not be published")
    return problems


def item_history(m, item_id):
    key = str(item_id)
    return [(p["date"], p["items"].get(key)) for p in m["prices"]]


def traded_items(m):
    ids = set()
    for p in m["prices"]:
        ids.update(int(k) for k in p["items"])
    return ids


def when(iso):
    """'2026-10-26T07:00:00+00:00' -> '12:00 AM PT (07:00 UTC), Oct 26'"""
    t = dt.datetime.fromisoformat(iso)
    if t.tzinfo is None:
        t = t.replace(tzinfo=dt.timezone.utc)
    utc = t.astimezone(dt.timezone.utc)
    if PT:
        pt = utc.astimezone(PT)
    else:
        pt = utc - dt.timedelta(hours=7 if utc < dt.datetime(2026, 11, 1, 9, tzinfo=dt.timezone.utc) else 8)
    return f"{strf(pt, '%-I:%M %p')} PT ({utc.strftime('%H:%M')} UTC), {strf(pt, '%b %-d')}"


# how a sale happened, in the same words for bots and players
SOURCES = {"shop": "shop", "merchant": "hired merchant", "trade": "trade", "bot": "trade",
           "bot_shop": "shop", "bot_trade": "trade", "bot_seed": "shop"}
# Early on most sales are the town's own shops (source bot_seed). No page says who is a bot (owner), so the sale
# lists carry this neutral line instead while those are most of the sales.
SEEDED_NOTE = "Most prices so far come from regulars&rsquo; shops and trades."


def sales_note(sales, style=""):
    """SEEDED_NOTE as a small paragraph when over half of these sales are the town's own; '' otherwise."""
    seeded = sum(1 for x in sales if str(x.get("source", "")).startswith("bot"))
    if not sales or seeded * 2 <= len(sales):
        return ""
    return f'<p class="small mknote"{style}>{SEEDED_NOTE}</p>'


def span(m):
    """How many days of price rollups the export really has (up to HISTORY_DAYS), e.g. '5 days'."""
    n = len(m["prices"])
    return f"{n} day" if n == 1 else f"{n} days"


def day(iso_date):
    """'2026-10-25' -> 'Oct 25'"""
    return strf(dt.date.fromisoformat(iso_date[:10]), "%b %-d")


def seller(l):
    """A listing's seller cell: the name, or a muted "a shop" when the export sends none (GM and test shops come with
    seller_name "")."""
    name = l.get("seller_name")
    return esc(name) if isinstance(name, str) and name.strip() else '<span class="muted">a shop</span>'


def where(l):
    return f'ch{l["channel"]} &middot; room {l["room"]}' if l.get("room") else f'ch{l["channel"]}'


def count(v):
    return f"{v:,}" if isinstance(v, int) else "-"


def mesos(v):
    if v is None:
        return "-"
    if v >= 1e9:
        return f"{v / 1e9:.1f}b"
    if v >= 1e6:
        return f"{v / 1e6:.2f}m".replace(".00m", "m")
    if v >= 1e4:
        return f"{v / 1e3:.0f}k"
    return f"{v:,}"


# ── charts (inline SVG, CSS-coloured) ────────────────────────────────────────────────────────────

def _ticks(lo, hi, n=4):
    if hi <= lo:
        hi = lo + 1
    raw = (hi - lo) / n
    mag = 10 ** (len(str(int(raw))) - 1) if raw >= 1 else 1
    step = next(s * mag for s in (1, 2, 2.5, 5, 10) if s * mag >= raw)
    start = int(lo // step) * step
    out, v = [], start
    while v <= hi + step * 0.001:
        out.append(v)
        v += step
    if out[-1] < hi:
        out.append(out[-1] + step)
    return out


def area_chart(points, label, line_label="Median sale", gap_text=f"under {MIN_SAMPLES} sales"):
    """points: [(date 'YYYY-MM-DD', median or None, low, high)]. Days without a value (None) are bridged with a dashed
    line. Every day has a hover target with its numbers (theme.js shows them; <title> is the fallback). A series with
    no low/high band (low == high every day) gets a one-entry legend: line_label."""
    W, H, L, R, T, B = 680, 240, 54, 60, 14, 30
    vals = [v for _, v, lo, hi in points if v is not None] + [x for _, v, lo, hi in points if v is not None for x in (lo, hi)]
    if len(points) < 2 or sum(1 for _, v, _, _ in points if v is not None) < 2:  # one dot is not a chart
        return '<p class="empty">Not enough sales yet for a chart.</p>'
    ticks = _ticks(min(vals), max(vals))
    y0, y1 = ticks[0], ticks[-1]
    X = lambda i: L + i * (W - L - R) / (len(points) - 1)  # noqa: E731
    Y = lambda v: T + (y1 - v) * (H - T - B) / (y1 - y0 or 1)  # noqa: E731
    s = [f'<line class="ch-grid" x1="{L}" x2="{W - R}" y1="{Y(t):.1f}" y2="{Y(t):.1f}"/>'
         f'<text class="ch-label" x="{L - 8}" y="{Y(t) + 4:.1f}" text-anchor="end">{mesos(int(t))}</text>' for t in ticks]
    runs, cur = [], []
    for i, (_, v, lo, hi) in enumerate(points):
        if v is None:
            if cur:
                runs.append(cur)
            cur = []
        else:
            cur.append((i, v, lo, hi))
    if cur:
        runs.append(cur)
    for a, b in zip(runs, runs[1:]):  # the days between two runs had no value: a dashed bridge, not a hole
        s.append(f'<line class="ch-gap" x1="{X(a[-1][0]):.1f}" y1="{Y(a[-1][1]):.1f}" x2="{X(b[0][0]):.1f}" y2="{Y(b[0][1]):.1f}"/>')
    for run in runs:
        band = " ".join(f"{X(i):.1f},{Y(hi):.1f}" for i, _, _, hi in run) + " " + \
               " ".join(f"{X(i):.1f},{Y(lo):.1f}" for i, _, lo, _ in reversed(run))
        line = " ".join(f"{X(i):.1f},{Y(v):.1f}" for i, v, _, _ in run)
        s.append(f'<polygon class="ch-band" points="{band}"/>')
        s.append(f'<polyline class="ch-line" fill="none" points="{line}"/>')
        s.extend(f'<circle class="ch-pt" cx="{X(i):.1f}" cy="{Y(v):.1f}" r="2.5"/>' for i, v, _, _ in run)
    for i in range(len(points) - 1, -1, -7):  # a date every week, counted back from the newest day
        d = dt.date.fromisoformat(points[i][0])
        s.append(f'<text class="ch-label" x="{X(i):.1f}" y="{H - 8}" text-anchor="middle">{strf(d, "%b %-d")}</text>')
    last = next(((i, v) for i, (_, v, _, _) in reversed(list(enumerate(points))) if v is not None), None)
    if last:
        s.append(f'<circle class="ch-dot" cx="{X(last[0]):.1f}" cy="{Y(last[1]):.1f}" r="4.5"/>'
                 f'<text class="ch-end" x="{X(last[0]) + 9:.1f}" y="{Y(last[1]) + 4:.1f}">{mesos(last[1])}</text>')
    band = any(lo != hi for _, v, lo, hi in points if v is not None)
    step = (W - L - R) / (len(points) - 1)
    for i, (day, v, lo, hi) in enumerate(points):
        d = strf(dt.date.fromisoformat(day), "%b %-d")
        tip = (f"{d}: {mesos(v)}" + (f" (lowest sale {mesos(lo)}, highest {mesos(hi)})" if band and lo != hi else "")) if v is not None \
            else f"{d}: {gap_text}"
        x = max(L, X(i) - step / 2)
        s.append(f'<rect class="ch-hit" x="{x:.1f}" y="{T}" width="{min(X(i) + step / 2, W - R) - x:.1f}" height="{H - T - B}" '
                 f'data-x="{X(i):.1f}" data-tip="{esc(tip)}"><title>{esc(tip)}</title></rect>')
    return (f'<div class="chart-box"><svg class="chart" viewBox="0 0 {W} {H}" role="img" aria-label="{esc(label)}">{"".join(s)}'
            f'<line class="ch-guide" x1="0" x2="0" y1="{T}" y2="{H - B}" hidden/></svg><div class="ch-tip" hidden></div></div>'
            f'<p class="legend"><span><i></i>{esc(line_label)}</span>'
            + ('<span><i class="band"></i>Lowest and highest sale that day</span>' if band else "")
            + ('<span><i class="gap"></i>No figure that day</span>' if len(runs) > 1 else "") + '</p>')


PAD = ' style="padding:6px 16px 10px;margin:0"'  # a small note under a panel's table or list
RANGES = [(7, "7 days"), (14, "14 days"), (30, "30 days")]


def ranged_chart(points, label, *args):
    """area_chart with 7 / 14 / 30 day buttons: one chart per range, the longest shown; theme.js switches between
    them (without script only the longest shows). Ranges that would show the same days, or fewer than 2, are left out."""
    views = []
    for n, name in RANGES:
        pts = points[-n:]
        if len(pts) >= 2 and (not views or len(pts) > len(views[-1][1])):
            views.append((name, pts))
    if len(views) < 2:
        return area_chart(points, label, *args)
    chips = "".join(f'<button type="button" class="chip" data-range="{i}" aria-pressed="{str(i == len(views) - 1).lower()}">'
                    f'{esc(name)}</button>' for i, (name, _) in enumerate(views))
    boxes = "".join(f'<div class="range-view" data-view="{i}"{"" if i == len(views) - 1 else " hidden"}>'
                    f'{area_chart(pts, f"{label}, last {name}", *args)}</div>' for i, (name, pts) in enumerate(views))
    return f'<div class="ranged"><p class="chips range-chips" hidden>{chips}</p>{boxes}</div>'


def sparkline(values, label):
    pts = [(i, v) for i, v in enumerate(values) if v is not None]
    if len(pts) < 2:
        return ""
    W, H, P = 140, 38, 3
    lo, hi = min(v for _, v in pts), max(v for _, v in pts)
    X = lambda i: P + i * (W - 2 * P) / (len(values) - 1)  # noqa: E731
    Y = lambda v: H - P - (v - lo) * (H - 2 * P) / ((hi - lo) or 1)  # noqa: E731
    line = " ".join(f"{X(i):.1f},{Y(v):.1f}" for i, v in pts)
    area = f"{X(pts[0][0]):.1f},{H - P} {line} {X(pts[-1][0]):.1f},{H - P}"
    i, v = pts[-1]
    return (f'<svg class="spark" viewBox="0 0 {W} {H}" role="img" aria-label="{esc(label)}"><polygon class="sp-area" points="{area}"/>'
            f'<polyline class="sp-line" fill="none" points="{line}"/><circle class="sp-dot" cx="{X(i):.1f}" cy="{Y(v):.1f}" r="2.6"/></svg>')


def change(first, last):
    if not first or not last:
        return ""
    c = (last - first) / first * 100
    if abs(c) < 0.05:
        return '<span class="chg">&plusmn;0%</span>'
    cls, sign = ("up", "+") if c > 0 else ("down", "−")
    return f'<span class="chg {cls}">{sign}{abs(c):.1f}%</span>'


# ── pages ───────────────────────────────────────────────────────────────────────────────────────

class Market:
    def __init__(self, m, db, out, site_root=None):
        self.m, self.db, self.out = m, db, out
        self.site_root = site_root or out
        # the export carries the server's name for every item (contract: `name`); used when the database has none
        self.names = {}
        for row in m["listings"]["listings"] + m["sales"]["sales"]:
            if row.get("name"):
                self.names[row["item_id"]] = row["name"]
        for p in m["prices"]:
            for k, r in p["items"].items():
                if r.get("name"):
                    self.names[int(k)] = r["name"]

    def name(self, item_id):
        it = self.db["items"].get(item_id)
        # NX cards are left out of the database (their official name); the market still prices them
        if it:
            return it["name"]
        return NX_CARDS.get(item_id) or scrub(self.names.get(item_id) or f"Item {item_id}")

    def icon(self, item_id, root, big=False):
        # the database section publishes icons of its own items; on 164 the market timer finds them already there
        # (with the icons of items that have no page but turn up here: data/site/extra-icon-items.txt)
        p = os.path.join(self.site_root, "database", "icons", "items", f"{item_id}.png")
        if os.path.isfile(p):
            return (f'<span class="slotc{" big" if big else ""}"><img class="px" src="{root}database/icons/items/{item_id}.png" '
                    f'alt="" loading="lazy"></span>')
        return fallback_icon("items", item_id, root, big)

    def price_link(self, item_id, root):
        return f'<a href="{root}{MARKET}/prices/{item_id}.html">{esc(self.name(item_id))}</a>'

    def nx_card(self, root, wide=False):
        nx = self.m["nx-rate"]
        days = nx.get("days") or []
        vals = [d.get("mesos_per_1000nx") for d in days]
        today = nx.get("today") or (days[-1] if days else {})
        row, when_ = today, "today"
        if not today.get("mesos_per_1000nx"):  # too few trades today so far: the newest day that had a rate
            last = next((d for d in reversed(days) if d.get("mesos_per_1000nx")), None)
            if last:
                row, when_ = last, strf(dt.date.fromisoformat(last["date"]), "%b %-d")
        rate = row.get("mesos_per_1000nx")
        first = next((v for v in vals if v), None)
        note = ""
        value = mesos(rate) if rate else "not enough trades"
        return (f'<a class="nxcard{" wide" if wide else ""}" href="{root}{MARKET}/nx.html"><span class="k">NX rate</span>'
                f'<b{"" if rate else " class=none"}>{value}</b><span class="u">mesos per 1,000 NX {when_}{note}</span>'
                + (sparkline(vals, "NX rate over recent days") if wide else "")  # the sidebar card is just the numbers
                + (f'<span class="k">{len(days)} days {change(first, rate)}</span>' if first and rate else '<span class="k"></span>')
                + '</a>')

    def sidebar(self, root, here):
        groups = [(None, [("index.html", "Market home", "index")]),
                  ("My stuff", [("mine.html", "My market", "mine"), ("mine.html#trades", "My trades", "trades"),
                                ("cards.html", "My cards", "cards")]),
                  ("Market", [("sales.html", "Recent sales", "sales"), ("listings.html", "FM now", "listings"),
                              ("board.html", "Buy/Sell board", "board"),
                              ("exchange.html", "NX exchange", "exchange"), ("prices/index.html", "Price check", "prices"),
                              ("nx.html", "NX rate", "nx"), ("economy.html", "Economy", "economy")])]
        # market.js fills the badge: alerts that fired since the player last looked
        def lis(links):
            return "".join(f'<li><a href="{root}{MARKET}/{h}"{" aria-current=page" if k == here else ""}>{esc(t)}'
                           + (' <span class="mkbadge" data-mk-badge hidden></span>' if k == "mine" else "") + '</a></li>'
                           for h, t, k in links)
        nav = "".join((f'<h3>{esc(g)}</h3>' if g else "") + f'<ul class="mk">{lis(links)}</ul>' for g, links in groups)
        search = (f'<form class="dbsearch" role="search" data-root="{root}" data-index="{MARKET}/search-index.json" hidden>'
                  f'<input type="search" placeholder="Find an item..." aria-label="Find an item&rsquo;s price" autocomplete="off">'
                  f'<ul class="dbhits"></ul></form>')
        return (f'<nav class="panel dbside" aria-label="Market"><p class="bar">Slums Market</p>{search}{nav}'
                f'<div class="in">{self.nx_card(root)}</div></nav>')

    def page(self, rel, title, body, here, description=""):
        root = "../" * rel.count("/")
        banner = ('<p class="samplebar">Sample data: these prices, names and numbers are placeholders until the server '
                  'export is live.</p>') if self.m["sample"] else ""
        name = rel.split("/")[1].removesuffix(".html")
        slot = "market" if name == "index" else f"market/{name}"
        body = buddy.head(body, buddy.figure(root, slot, rel))
        html = (f'<div class="db market" data-mk-root="{root}">{self.sidebar(root, here)}<div class="dbmain data">{banner}'
                f'{body.replace("{root}", root)}</div></div><script src="{root}assets/market.js" defer></script>'
                f'<script src="{root}assets/dbsearch.js" defer></script>')
        write(self.out, rel, layout(title, html, root, "market", description))


def item_stats(hist):
    """30 days of one item's rollups -> the numbers its price page shows."""
    rows = [(d, r) for d, r in hist if r]
    if not rows:
        return None
    shown = [(d, r) for d, r in rows if r.get("count", 0) >= MIN_SAMPLES]
    last = shown[-1][1] if shown else None
    last_date = shown[-1][0] if shown else None  # the newest day with enough sales, not necessarily today
    sold = sum(r.get("count", 0) for _, r in rows)
    share = sum(r.get("player_share", 0) * r.get("count", 0) for _, r in rows) / (sold or 1)
    first = shown[0][1]["median"] if shown else None
    return {"median": last and last["median"], "low": last and last["low"], "high": last and last["high"],
            "first": first, "sold": sold, "player_share": share, "days": len(rows), "date": last_date}


def build_prices(mk):
    m, out = mk.m, []
    for item_id in sorted(traded_items(m)):
        hist = item_history(m, item_id)
        st = item_stats(hist)
        if not st:
            continue
        out.append((item_id, st))
        pts = [(d, r["median"] if r and r.get("count", 0) >= MIN_SAMPLES else None,
                (r or {}).get("low"), (r or {}).get("high")) for d, r in hist]
        name = mk.name(item_id)
        listings = sorted((l for l in m["listings"]["listings"] if l["item_id"] == item_id), key=lambda l: l["price"])
        more = len(listings) - 15
        listings = listings[:15]
        lrows = "".join(f'<tr><td><span class="sb s">S&gt;</span></td><td class="r num">{l["price"]:,}</td><td class="r num">{l["qty"]}</td>'
                        f'<td>{seller(l)}</td><td>{where(l)}</td></tr>'
                        for l in listings)
        sales = [x for x in m["sales"]["sales"] if x["item_id"] == item_id][:15]
        srows = "".join(f'<tr><td class="r num">{x["price"]:,}</td><td class="r num">{x["qty"]}</td><td>{esc(SOURCES.get(x["source"], x["source"]))}</td>'
                        f'<td class="muted">{esc(when(x["hour"]))}</td></tr>' for x in sales)
        median = f'{st["median"]:,}' if st["median"] else "not enough sales"
        rng = f'{mesos(st["low"])} to {mesos(st["high"])}' if st["median"] else "-"
        on = f', {day(st["date"])}' if st["median"] else ""
        body = (f'<p class="crumbs"><a href="{{root}}{MARKET}/index.html">Market</a> &raquo; '
                f'<a href="{{root}}{MARKET}/prices/index.html">Price check</a> &raquo; {esc(name)}</p>'
                f'<div class="ent">{mk.icon(item_id, "{root}", big=True)}<div><h1>{esc(name)}</h1>'
                f'<p class="id">Price check &middot; last {len(hist)} days'
                + (f' &middot; <a href="{{root}}database/items/{item_id}.html">Slums Street page</a>' if item_id in mk.db["items"] else "")
                + '</p></div></div>'
                f'<div class="mkstats"><div class="hot"><small>Median{on}</small><b>{median}</b><span>mesos</span></div>'
                f'<div><small>Lowest and highest sale{on}</small><b>{rng}</b><span>mesos</span></div>'
                f'<div><small>{len(hist)}-day change</small><b>{change(st["first"], st["median"]) or "-"}</b><span>median vs {len(hist)} days ago</span></div>'
                f'<div><small>Sold</small><b>{st["sold"]:,}</b><span>in {len(hist)} days</span></div></div>'
                f'<section class="panel mkact" data-mk-item="{item_id}" hidden><h2 class="bar">Watch, alert, post</h2>'
                f'<div class="in"></div></section>'
                f'<section class="panel"><h2 class="bar">Price history <small>median per day; days under {MIN_SAMPLES} sales are left out</small></h2>'
                f'<div class="in">{ranged_chart(pts, f"Median price of {name} per day")}</div></section>'
                + (f'<section class="panel"><h2 class="bar">FM now <small>cheapest first; whisper or visit to buy</small></h2><div class="tbl"><table class="t">'
                   f'<thead><tr><th></th><th class="r">Price</th><th class="r">Qty</th><th>Seller</th><th>Where</th></tr></thead>'
                   f'<tbody>{lrows}</tbody></table></div>'
                   + (f'<p class="small" style="padding:6px 16px 10px;margin:0">and {more} more in the '
                      f'<a href="{{root}}{MARKET}/listings.html">FM now</a> list</p>' if more > 0 else "")
                   + '</section>' if lrows else "")
                + (f'<section class="panel"><h2 class="bar">Recent sales <small>rounded to the hour</small></h2><div class="tbl"><table class="t">'
                   f'<thead><tr><th class="r">Price</th><th class="r">Qty</th><th>How</th><th>When</th></tr></thead>'
                   f'<tbody>{srows}</tbody></table></div>{sales_note(sales, PAD)}</section>' if srows else ""))
        mk.page(f"{MARKET}/prices/{item_id}.html", f"{name} price", body, "prices", f"{name}: price history and FM listings on SlumsMS.")
    # the list of every priced item
    out.sort(key=lambda x: -x[1]["sold"])
    # the sidebar search (dbsearch.js) looks through these: every item with a price page and its median, most traded first
    write(mk.out, f"{MARKET}/search-index.json",
          json.dumps([["$", i, mk.name(i), mesos(st["median"]) if st["median"] else ""] for i, st in out], separators=(",", ":"), ensure_ascii=False))
    rows = "".join(f'<tr><td>{mk.icon(i, "{root}")}</td><td>{mk.price_link(i, "{root}")}</td>'
                   f'<td class="r num">{mesos(st["median"]) if st["median"] else "-"}</td><td class="r">{change(st["first"], st["median"])}</td>'
                   f'<td class="r num">{st["sold"]:,}</td></tr>' for i, st in out)
    body = (f'<h1>Price check</h1><p class="id">{len(out)} items sold in the last {span(m)}, most traded first</p>'
            f'<section class="panel"><h2 class="bar">All prices</h2><div class="tbl"><table class="t icons"><thead><tr><th></th><th>Item</th>'
            f'<th class="r">Median</th><th class="r">{span(m)}</th><th class="r">Sold</th></tr></thead><tbody>{rows}</tbody></table></div></section>')
    mk.page(f"{MARKET}/prices/index.html", "Price check", body, "prices")
    return out


def build_lists(mk, priced):
    m = mk.m
    priced_ids = {i for i, _ in priced}
    lst = sorted(m["listings"]["listings"], key=lambda l: (mk.name(l["item_id"]), l["price"]))
    rows = "".join(f'<tr><td><span class="sb s">S&gt;</span></td><td>{mk.icon(l["item_id"], "{root}")}</td>'
                   f'<td>{mk.price_link(l["item_id"], "{root}") if l["item_id"] in priced_ids else esc(mk.name(l["item_id"]))}</td>'
                   f'<td class="r num">{l["price"]:,}</td><td class="r num">{l["qty"]}</td>'
                   f'<td>{seller(l)}</td>'
                   f'<td>{where(l)}</td><td class="muted">{esc(l.get("shop_title") or "")}</td></tr>' for l in lst)
    body = (f'<h1>FM now</h1><p class="id">{len(lst)} items for sale &middot; as of {esc(when(m["listings"]["generated_at"]))}</p>'
            f'<section class="panel"><h2 class="bar">Trading board <small>S&gt; selling</small></h2><div class="tbl"><table class="t">'
            f'<thead><tr><th></th><th></th><th>Item</th><th class="r">Price</th><th class="r">Qty</th><th>Seller</th><th>Where</th><th>Shop</th></tr></thead>'
            f'<tbody>{rows}</tbody></table></div></section>')
    mk.page(f"{MARKET}/listings.html", "FM now", body, "listings")

    sales = m["sales"]["sales"][:200]
    rows = "".join(f'<tr><td>{mk.icon(x["item_id"], "{root}")}</td><td>{mk.price_link(x["item_id"], "{root}") if x["item_id"] in priced_ids else esc(mk.name(x["item_id"]))}</td>'
                   f'<td class="r num">{x["price"]:,}</td><td class="r num">{x["qty"]}</td><td>{esc(SOURCES.get(x["source"], x["source"]))}</td>'
                   f'<td class="muted">{esc(when(x["hour"]))}</td></tr>' for x in sales)
    body = (f'<h1>Recent sales</h1><p class="id">The last {len(sales)} sales, newest first, rounded to the hour</p>'
            f'{sales_note(sales)}<section class="panel"><h2 class="bar">Sales</h2><div class="tbl"><table class="t icons"><thead><tr><th></th><th>Item</th>'
            f'<th class="r">Price</th><th class="r">Qty</th><th>How</th><th>When</th></tr></thead><tbody>{rows}</tbody></table></div></section>')
    mk.page(f"{MARKET}/sales.html", "Recent sales", body, "sales")

    nx = m["nx-rate"]
    days = nx.get("days") or []
    pts = [(d["date"], d.get("mesos_per_1000nx"), d.get("mesos_per_1000nx"), d.get("mesos_per_1000nx")) for d in days]
    rows = "".join(f'<tr><td class="num">{esc(d["date"])}</td><td class="r num">{mesos(d["mesos_per_1000nx"]) if d.get("mesos_per_1000nx") else "not enough trades"}</td>'
                   f'<td class="r num">{d.get("n_trades", 0)}</td><td class="r num">{d.get("n_cash_resales", 0)}</td>'
                   f'<td class="r num">{d.get("n_code_trades", 0)}</td></tr>'
                   for d in reversed(days))
    body = (f'<h1>NX rate</h1><p class="id">Mesos per 1,000 NX, from resales of cash-shop items and NX code trades. '
            f'A day with under 5 trades shows no rate.</p>'
            f'<p class="small mknote">For reference: some regulars sell a 10,000 NX code for 50m mesos.</p>'
            f'{mk.nx_card("{root}", wide=True)}'
            f'<section class="panel"><h2 class="bar">History</h2><div class="in">{ranged_chart(pts, "NX rate per day", "Mesos per 1,000 NX", "not enough trades")}</div></section>'
            f'<section class="panel"><h2 class="bar">By day</h2><div class="tbl"><table class="t"><thead><tr><th>Day (UTC)</th>'
            f'<th class="r">Mesos per 1,000 NX</th><th class="r">Trades</th><th class="r">Cash resales</th><th class="r">NX codes</th>'
            f'</tr></thead><tbody>{rows}</tbody></table></div></section>')
    mk.page(f"{MARKET}/nx.html", "NX rate", body, "nx")

    e = m["economy-public"]
    facts = [("Mesos in the game", mesos(e.get("mesos_total"))),
             ("Trades, last 24 hours", count(e.get("trades_24h"))), ("FM shops open now", count(e.get("shops_open"))),
             ("Mesos removed, last 7 days (tax, fees)", mesos(e.get("mesos_removed_7d"))),
             ]
    econ = "".join(f"<div><dt>{esc(k)}</dt><dd>{v}</dd></div>" for k, v in facts)
    other = (f'<p class="small mknote">Charts of the mesos in the game, mesos made and spent per day, and the NX rate over '
             f'time are on the <a href="{{root}}world/economy.html">World economy page</a>.</p>' if "world" in common.BUILT else "")
    body = (f'<h1>Bera economy</h1><p class="id">As of {esc(when(e["as_of"]))}</p>{other}'
            f'<section class="panel"><h2 class="bar">Numbers</h2><dl class="stats">{econ}</dl></section>')
    mk.page(f"{MARKET}/economy.html", "Bera economy", body, "economy")
    return econ


def build_home(mk, priced, econ):
    m = mk.m
    top = priced[:8]
    rows = "".join(f'<li>{mk.icon(i, "{root}")}<span>{mk.price_link(i, "{root}")}</span>'
                   f'<b class="num">{mesos(st["median"]) if st["median"] else "-"}</b>{change(st["first"], st["median"])}</li>' for i, st in top)
    sales = m["sales"]["sales"][:10]
    srows = "".join(f'<li>{mk.icon(x["item_id"], "{root}")}<span>{esc(mk.name(x["item_id"]))}</span><b class="num">{mesos(x["price"])}</b>'
                    f'<span class="muted">{esc(when(x["hour"]))}</span></li>' for x in sales)
    body = ('<h1>Slums Market</h1><p class="lede">What things really sell for on Bera: prices from finished trades and FM shop '
            'sales, the shops open right now, and the NX rate. Deals happen in game; whisper the seller or visit their shop.</p>'
            f'{mk.nx_card("{root}", wide=True)}'
            f'<div class="mkhome"><section class="panel"><h2 class="bar">Most traded <a href="{{root}}{MARKET}/prices/index.html">All prices &raquo;</a></h2>'
            f'<ul class="mklist">{rows}</ul></section>'
            f'<section class="panel"><h2 class="bar">Just sold <a href="{{root}}{MARKET}/sales.html">More &raquo;</a></h2><ul class="mklist">{srows}</ul>'
            f'{sales_note(sales, PAD)}</section></div>'
            f'<section class="panel"><h2 class="bar">Bera economy <a href="{{root}}{MARKET}/economy.html">More &raquo;</a></h2><dl class="stats">{econ}</dl></section>')
    mk.page(f"{MARKET}/index.html", "Slums Market", body, "index", "Prices, FM shops and the NX rate on SlumsMS.")


def build_board(mk):
    """The Buy/Sell board and My market: static shells that market.js fills from the market service."""
    signedout = ('<p class="samplebar" id="mk-signedout" hidden>The board, watchlist and alerts need the market service, '
                 'which isn\'t answering. Try again in a bit.</p>'
                 '<noscript><p class="small">The board, watchlist and alerts need JavaScript turned on.</p></noscript>')
    body = ('<h1>Buy/Sell board</h1><p class="lede">Post what you want to buy or sell, as one of your characters. Posts '
            'also show on the board NPC at the FM entrance, and bots that have the item (or the mesos) may answer with '
            'an offer by whisper or note. Then trade in the FM as usual: nothing on this site moves items or mesos.</p>'
            f'{signedout}'
            '<form class="panel mkpost" id="post" hidden><h2 class="bar">New post <small id="mk-posts-n"></small></h2><div class="in">'
            '<fieldset class="seg"><legend>I want to</legend><label><input type="radio" name="kind" value="WTB" checked> buy (WTB)</label>'
            '<label><input type="radio" name="kind" value="WTS"> sell (WTS)</label></fieldset>'
            '<label class="pick">Item <input name="item" type="search" autocomplete="off" placeholder="type a name" required></label>'
            '<label>How many <input name="qty" type="number" min="1" step="1" value="1" required></label>'
            '<label>Price each (mesos) <input name="price" type="number" min="1" step="1" required></label>'
            '<label>Post as <select name="character"></select></label>'
            '<button class="btn primary" type="submit">Post</button>'
            '<p class="small">Up to 5 open posts per account. A post lasts 3 days; take it down any time below.</p>'
            '<p class="status" id="mk-status" role="status" aria-live="polite"></p></div></form>'
            '<section class="panel"><h2 class="bar">Your posts</h2><ul class="mkmine" id="mk-posts"></ul></section>'
            '<section class="panel"><h2 class="bar">The board <small id="mk-board-n"></small></h2>'
            '<form class="mkfilter" id="mk-filter" role="search"><select name="kind" aria-label="Kind"><option value="">All posts</option>'
            '<option value="WTB">Buying (B&gt;)</option><option value="WTS">Selling (S&gt;)</option></select>'
            '<input name="q" type="search" placeholder="Item or character" aria-label="Filter"></form>'
            '<div class="tbl"><table class="t"><thead><tr><th></th><th>Item</th><th class="r">Price each</th>'
            '<th class="r">Median</th><th class="r">Qty</th><th>Who</th><th>Ends</th></tr></thead>'
            '<tbody id="mk-board"></tbody></table></div>'
            '<p class="empty nap" id="mk-board-empty"><img src="{root}assets/brand/bubble-chin-nap-320.webp" alt="" width="160" height="143" loading="lazy">'
            '<span>No posts yet.</span></p></section>')
    mk.page(f"{MARKET}/board.html", "Buy/Sell board", body, "board", "WTB and WTS posts from players on SlumsMS.")
    body = ('<h1>My market</h1><p class="id" id="mk-who"></p>'
            f'{signedout}'
            '<div id="mk-mine" hidden>'
            '<p class="status" id="mk-status" role="status" aria-live="polite"></p>'
            '<section class="panel"><h2 class="bar">Watchlist <small id="mk-watch-n"></small></h2><ul class="mkmine" id="mk-watch"></ul></section>'
            '<section class="panel"><h2 class="bar">Price alerts <small id="mk-alerts-n"></small></h2>'
            '<p class="small" style="padding:8px 16px 0;margin:0">An alert fires once, when a sale goes below or above your '
            'price. You get an in-game note (a yellow line if you\'re online) and a badge here. Re-arm it to watch again.</p>'
            '<ul class="mkmine" id="mk-alerts"></ul></section>'
            '<section class="panel"><h2 class="bar">Your posts <a href="{root}market/board.html">Board &raquo;</a></h2>'
            '<ul class="mkmine" id="mk-posts"></ul></section>'
            '<section class="panel" id="trades"><h2 class="bar">My trades <a href="{root}world/rankings/traders.html">Traders &raquo;</a></h2>'
            '<p class="small" style="padding:8px 16px 0;margin:0">Trades your characters finished and the ones that fell '
            'through, the same counts the Traders ranking uses. The ranking lists characters with at least 5 trades.</p>'
            '<ul class="mkmine" id="mk-trades"></ul></section></div>')
    mk.page(f"{MARKET}/mine.html", "My market", body, "mine", "Your watchlist, price alerts, posts and trades.")
    # theme.js fills it: the Slums-chan secrets this account has found, saved by the market service
    body = ('<h1>My cards</h1><p class="lede">Slums-chan has hidden secrets all over the site. Every one you find '
            'is a card, saved to your account so it shows on your other devices too. Two more come as rewards: '
            'one for unlocking her theme and one for finding them all.</p>'
            '<p class="id" id="cards-n"></p>'
            '<p class="samplebar" id="cards-off" hidden>Your account&rsquo;s cards didn&rsquo;t load, so these are the '
            'ones found on this browser. Try again in a bit.</p>'
            '<noscript><p class="small">Your cards need JavaScript turned on.</p></noscript>'
            '<ul class="cards" id="cards-grid"></ul>')
    mk.page(f"{MARKET}/cards.html", "My cards", body, "cards", "The Slums-chan cards you've found.")


EXCHANGE = """<h1>NX exchange</h1>
<p class="lede">Trade NX for mesos with other players, at your own price. Post a buy or a sell in mesos per 1,000 NX;
when a buyer's price meets a seller's, they trade straight away, and the game pays both sides. Nothing on this page moves
anything by itself: the game server checks and settles every order.</p>
<noscript><p class="small">The exchange needs JavaScript turned on.</p></noscript>
<div class="xc" x-data="xbook">
<p class="samplebar" x-show="state === 'error'" x-text="error" x-cloak></p>
<p class="samplebar" x-show="halted" x-cloak>The exchange is paused by a GM: no new orders or trades for now.
You can still cancel your orders.</p>
<p class="samplebar" x-show="state === 'ok' && !book" x-cloak>The exchange isn't open on this server yet.</p>
<div class="xc-top">
<section class="panel xc-rate"><h2 class="bar">Official rate <small x-show="data" x-text="'updated ' + ago" x-cloak></small></h2>
<div class="in"><p class="xc-big"><b x-text="rate ? fmt(rate) : 'not enough trades yet'">...</b>
<span class="small" x-show="rate">mesos per 1,000 NX</span></p>
<p class="small">The average price of player trades in the last <span x-text="(book && book.rate && book.rate.window_hours) || 24"></span> hours,
weighted by size. It needs at least <span x-text="(book && book.rate && book.rate.min_fills) || 3"></span> trades.
The game's regular traders never count towards it.</p>
<svg class="xc-chart" viewBox="0 0 600 180" role="img" aria-label="Recent trade prices" x-show="trades.length" x-html="chart()"></svg>
<p class="small" x-show="trades.length" x-cloak>Each dot is a trade (bigger dots, more NX); the dashed line is the official rate.</p>
</div></section>
<section class="panel xc-rules"><h2 class="bar">Fees and limits</h2><div class="in"><ul class="small">
<li><b>Fee:</b> 1% of the mesos, paid by each side. A seller gets the mesos less 1%; a buyer pays the mesos plus 1% (set
aside when the order is placed; what isn't used comes back).</li>
<li><b>Size:</b> 100 to 50,000 NX per order, in lots of 100.</li>
<li><b>Price:</b> 1,000 to 100,000,000 mesos per 1,000 NX.</li>
<li><b>Limits:</b> 5 open orders at a time, 20 a day, 10 seconds apart.</li>
<li><b>Expiry:</b> an order that hasn't fully traded closes after 72 hours, and whatever is left comes back.</li>
<li><b>Which character:</b> every order names one of your characters. The mesos come from (and go to) that character;
NX is your account's. If you're logged in to the game, use the character you're playing (or log out first).</li>
</ul></div></section>
</div>
<div class="xc-mid">
<section class="panel"><h2 class="bar">Order book</h2><div class="tbl"><table class="t xc-book">
<thead><tr><th>Side</th><th class="r">Price (per 1,000 NX)</th><th class="r">NX</th><th class="r">Orders</th></tr></thead>
<tbody><template x-for="l in asksDown" :key="'a' + l.price"><tr class="ask" @click="pick(l.price)"><td>Selling</td><td class="r" x-text="fmt(l.price)"></td>
<td class="r" x-text="fmt(l.nx)"></td><td class="r" x-text="l.orders"></td></tr></template>
<tr class="spread" x-show="book"><td colspan="4" x-text="spread()"></td></tr>
<template x-for="l in bids" :key="'b' + l.price"><tr class="bid" @click="pick(l.price)"><td>Buying</td><td class="r" x-text="fmt(l.price)"></td>
<td class="r" x-text="fmt(l.nx)"></td><td class="r" x-text="l.orders"></td></tr></template></tbody></table></div>
<p class="small xc-pad">The best 20 prices on each side. Click a price to copy it into your order.</p></section>
<section class="panel"><h2 class="bar">Last trades</h2><div class="tbl"><table class="t">
<thead><tr><th>When</th><th class="r">Price</th><th class="r">NX</th></tr></thead>
<tbody><template x-for="(t, i) in trades.slice().reverse().slice(0, 12)" :key="i"><tr><td x-text="when(t.t)"></td>
<td class="r" x-text="fmt(t.price)"></td><td class="r" x-text="fmt(t.nx)"></td></tr></template></tbody></table></div>
<p class="empty xc-pad" x-show="book && !trades.length" x-cloak>No trades yet.</p></section>
</div>
</div>
<div class="xc" x-data="xmine">
<p class="samplebar" x-show="state === 'signedOut'" x-cloak>Sign in to trade.</p>
<form class="panel mkpost xc-form" x-show="data" x-cloak @submit.prevent="preview()" @xc-price.window="price = $event.detail">
<h2 class="bar">New order <small x-text="openCount() + ' of 5 open'"></small></h2><div class="in">
<fieldset class="seg"><legend>I want to</legend>
<label><input type="radio" value="SELL" x-model="side"> sell NX for mesos</label>
<label><input type="radio" value="BUY" x-model="side"> buy NX with mesos</label></fieldset>
<label>NX <input type="number" min="100" max="50000" step="100" x-model.number="nx" required></label>
<label>Price (mesos per 1,000 NX) <input type="number" min="1000" max="100000000" step="1" x-model.number="price" required></label>
<label>Character <select x-model="character"><template x-for="c in (data && data.characters) || []" :key="c">
<option :value="c" x-text="c"></option></template></select></label>
<button class="btn primary" type="submit" :disabled="busy">Preview</button>
<p class="small" x-text="estimate()"></p>
<p class="status" :class="msgClass" role="status" aria-live="polite" x-text="msg"></p>
<div class="xc-preview" x-show="pv" x-cloak>
<h3 x-text="pv && ((pv.side === 'SELL' ? 'Sell ' : 'Buy ') + fmt(pv.nx) + ' NX at ' + fmt(pv.price) + ' per 1,000')"></h3>
<dl>
<dt>Set aside now</dt><dd x-text="pv && (fmt(pv.takes.amount) + (pv.takes.asset === 'NX' ? ' NX' : ' mesos'))"></dd>
<dt>Trades right away</dt><dd x-text="pv && (pv.matchNow.nx ? fmt(pv.matchNow.nx) + ' NX for ' + fmt(pv.matchNow.mesos) + ' mesos' : 'nothing yet')"></dd>
<dt x-show="pv && pv.matchNow.nx">You get now</dt><dd x-show="pv && pv.matchNow.nx" x-text="pv && (fmt(pv.matchNow.youGet.amount) + (pv.matchNow.youGet.asset === 'NX' ? ' NX' : ' mesos'))"></dd>
<dt x-show="pv && pv.matchNow.fee">Your fee now</dt><dd x-show="pv && pv.matchNow.fee" x-text="pv && (fmt(pv.matchNow.fee) + ' mesos')"></dd>
<dt>Waits in the book</dt><dd x-text="pv && (pv.restsNx ? fmt(pv.restsNx) + ' NX, for up to 72 hours' : 'nothing')"></dd>
</dl>
<p class="status bad" x-show="pv && pv.cancelledBySelfTrade">This would meet one of your own orders, so it would be closed instead of trading.</p>
<p class="small" x-text="left > 0 ? 'Confirm within ' + left + ' s.' : 'This preview has expired. Preview it again.'"></p>
<button class="btn primary" type="button" @click="confirm()" :disabled="busy || left <= 0">Confirm order</button>
<button class="btn" type="button" @click="pv = null; msg = ''">Never mind</button>
</div></div></form>
<section class="panel" x-show="data" x-cloak><h2 class="bar">My orders <small x-show="data && data.ordersAt" x-text="'as of ' + when(data && data.ordersAt)"></small></h2>
<div class="tbl"><table class="t"><thead><tr><th>#</th><th>Side</th><th class="r">Price</th><th class="r">NX</th>
<th class="r">Left</th><th>Status</th><th>Ends</th><th></th></tr></thead>
<tbody><template x-for="o in orders()" :key="o.orderId"><tr><td x-text="o.orderId"></td>
<td x-text="o.side === 'SELL' ? 'Sell' : 'Buy'"></td><td class="r" x-text="fmt(o.price)"></td><td class="r" x-text="fmt(o.nx)"></td>
<td class="r" x-text="fmt(o.nxLeft)"></td><td x-text="status(o.status)"></td><td x-text="o.status === 'OPEN' ? when(o.expiresAt) : ''"></td>
<td><button class="btn" type="button" x-show="o.status === 'OPEN'" @click="cancel(o)" :disabled="busy">Cancel</button></td></tr></template></tbody></table></div>
<p class="empty xc-pad" x-show="data && data.orders && !data.orders.length">No orders yet.</p>
<p class="small xc-pad" x-show="data && !data.orders">Asking the game for your orders...</p>
<p class="small xc-pad">Trades pay out to the character on the order: straight away if you're online, else when you next log in.</p>
</section>
</div>
<script src="{root}assets/live.js"></script><script src="{root}assets/exchange.js"></script>
<script defer src="{root}assets/vendor/alpine-3.14.1.min.js"></script>"""


def build_exchange(mk):
    """The NX-meso exchange: a static shell that exchange.js (Alpine) fills from /api/market/exchange/."""
    mk.page(f"{MARKET}/exchange.html", "NX exchange", EXCHANGE, "exchange",
            "Trade NX for mesos with other players: the book, the official rate and your orders.")


def write_untradeable(mk):
    """market/untradeable.json: items the game refuses on the board (untradeable, account-bound or quest items; Site
    board contract). The board's item picker leaves them out and market_service.py refuses them up front."""
    flags = ("tradeBlock", "quest", "accountSharable")
    ids = sorted(i for i, it in mk.db["items"].items() if any((it.get("stats") or {}).get(f) for f in flags))
    with open(os.path.join(mk.out, MARKET, "untradeable.json"), "w", encoding="utf-8") as f:
        json.dump(ids, f, separators=(",", ":"))


def write_summary(mk, priced):
    """market/summary.json for market.js: name, median and change of every priced item."""
    data = {str(i): {"n": mk.name(i), "m": st["median"],
                     "c": round((st["median"] - st["first"]) / st["first"] * 100, 1) if st["median"] and st["first"] else None}
            for i, st in priced}
    with open(os.path.join(mk.out, MARKET, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"), sort_keys=True)


def rail_lines(m, db, count=4):
    """A few megaphone-rail lines from the market: the NX rate and the latest sales."""
    out = []
    today = (m["nx-rate"].get("today") or {}).get("mesos_per_1000nx")
    if today:
        out.append((f"<em>NX RATE</em> 1,000 NX = {mesos(today)} mesos", f"{MARKET}/nx.html"))
    priced = traded_items(m)
    for x in m["sales"]["sales"][:count]:
        it = db["items"].get(x["item_id"])
        if it:  # link only when the item has a price page (a sale from today has no daily rollup yet)
            out.append((f'<em>SOLD</em> {esc(it["name"])} for {mesos(x["price"])}',
                        f'{MARKET}/prices/{x["item_id"]}.html' if x["item_id"] in priced else None))
    return out


def build(out, db, data_dir=None, site_root=None):
    """out: where market/ is written (a staging folder on a one-section rebuild); site_root: the published site."""
    m = load(data_dir)
    problems = validate(m)
    if problems:
        raise SystemExit("market data refused:\n  " + "\n  ".join(problems))
    shutil.rmtree(os.path.join(out, MARKET), ignore_errors=True)
    mk = Market(m, db, out, site_root)
    priced = build_prices(mk)
    econ = build_lists(mk, priced)
    build_home(mk, priced, econ)
    build_board(mk)
    build_exchange(mk)
    write_summary(mk, priced)
    write_untradeable(mk)
    return {"priced": len(priced), "listings": len(m["listings"]["listings"]), "sample": m["sample"]}
