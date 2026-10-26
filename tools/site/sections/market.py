"""Market (Basil Market): prices with history, FM listings now, recent sales, the NX rate and a few economy numbers.
Private until 1.6.0 is live: build.py only runs it with --private.

Data: the server's PublicMarketExport files (docs/design/track-d-plan.md, "Market export contract"), or until those
exist the placeholders from tools/site/market_sample.py (cache/market-sample). A file with "sample": true puts a
"sample data" banner on every market page. Charts are SVG drawn at build time and coloured by CSS variables, so they
follow light and dark mode with no script.

Public rules (plan §5): a day's median shows only with at least MIN_SAMPLES sales; a price is "mostly bot-made" when
players were on fewer than half of its sales; the NX rate is null (shown as "not enough trades") under 5 trades.
"""
import datetime as dt
import glob
import json
import os
import shutil

from common import REPO, esc, layout, write

MARKET = "market"
DEFAULT_DIR = os.environ.get("SLUMS_MARKET_DATA", os.path.join(REPO, "cache", "market-sample"))
MIN_SAMPLES = 3
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
    if missing or not glob.glob(os.path.join(data_dir, "prices", "*.json")):
        raise SystemExit(f"{data_dir}: market data missing ({', '.join(missing) or 'prices/'}); "
                         "run tools/site/market_sample.py or point --market-data at the export")
    m = {n[:-5]: _read(os.path.join(data_dir, n)) for n in need}
    days = sorted(glob.glob(os.path.join(data_dir, "prices", "*.json")))[-HISTORY_DAYS:]
    m["prices"] = [_read(p) for p in days]
    m["sample"] = any(isinstance(v, dict) and v.get("sample") for v in m.values()) or any(p.get("sample") for p in m["prices"])
    return m


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
    return f"{pt.strftime('%-I:%M %p')} PT ({utc.strftime('%H:%M')} UTC), {pt.strftime('%b %-d')}"


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


def area_chart(points, label):
    """points: [(date 'YYYY-MM-DD', median or None, low, high)]. Gaps (None) break the line."""
    W, H, L, R, T, B = 680, 240, 54, 60, 14, 30
    vals = [v for _, v, lo, hi in points if v is not None] + [x for _, v, lo, hi in points if v is not None for x in (lo, hi)]
    if len(points) < 2 or not vals:
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
    for run in runs:
        band = " ".join(f"{X(i):.1f},{Y(hi):.1f}" for i, _, _, hi in run) + " " + \
               " ".join(f"{X(i):.1f},{Y(lo):.1f}" for i, _, lo, _ in reversed(run))
        line = " ".join(f"{X(i):.1f},{Y(v):.1f}" for i, v, _, _ in run)
        s.append(f'<polygon class="ch-band" points="{band}"/>')
        s.append(f'<polyline class="ch-line" fill="none" points="{line}"/>')
    for i in sorted({0, len(points) // 2, len(points) - 1}):
        d = dt.date.fromisoformat(points[i][0])
        s.append(f'<text class="ch-label" x="{X(i):.1f}" y="{H - 8}" text-anchor="middle">{d.strftime("%b %-d")}</text>')
    last = next(((i, v) for i, (_, v, _, _) in reversed(list(enumerate(points))) if v is not None), None)
    if last:
        s.append(f'<circle class="ch-dot" cx="{X(last[0]):.1f}" cy="{Y(last[1]):.1f}" r="4.5"/>'
                 f'<text class="ch-end" x="{X(last[0]) + 9:.1f}" y="{Y(last[1]) + 4:.1f}">{mesos(last[1])}</text>')
    return (f'<svg class="chart" viewBox="0 0 {W} {H}" role="img" aria-label="{esc(label)}">{"".join(s)}</svg>'
            '<p class="legend"><span><i></i>Median sale</span><span><i class="band"></i>Low to high that day</span></p>')


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
    cls, sign = ("up", "+") if c >= 0 else ("down", "−")
    return f'<span class="chg {cls}">{sign}{abs(c):.1f}%</span>'


# ── pages ───────────────────────────────────────────────────────────────────────────────────────

class Market:
    def __init__(self, m, db, out):
        self.m, self.db, self.out = m, db, out

    def name(self, item_id):
        it = self.db["items"].get(item_id)
        return it["name"] if it else f"Item {item_id}"

    def icon(self, item_id, root, big=False):
        p = os.path.join(REPO, "cache", "site-icons", "items", f"{item_id}.png")
        if item_id in self.db["items"] and os.path.isfile(p):  # the database section copies icons of its own items
            return (f'<span class="slotc{" big" if big else ""}"><img class="px" src="{root}database/icons/items/{item_id}.png" '
                    f'alt="" loading="lazy"></span>')
        return f'<span class="slotc{" big" if big else ""}"></span>'

    def price_link(self, item_id, root):
        return f'<a href="{root}{MARKET}/prices/{item_id}.html">{esc(self.name(item_id))}</a>'

    def nx_card(self, root, wide=False):
        nx = self.m["nx-rate"]
        days = nx.get("days") or []
        vals = [d.get("mesos_per_1000nx") for d in days]
        today = nx.get("today") or (days[-1] if days else {})
        rate = today.get("mesos_per_1000nx")
        first = next((v for v in vals if v), None)
        bots = today.get("bot_share")
        note = " &middot; mostly bot-made" if bots is not None and bots >= 0.5 else ""
        value = mesos(rate) if rate else "not enough trades"
        return (f'<a class="nxcard{" wide" if wide else ""}" href="{root}{MARKET}/nx.html"><span class="k">NX rate</span>'
                f'<b>{value}</b><span class="u">mesos per 1,000 NX today{note}</span>'
                f'{sparkline(vals, "NX rate over 30 days")}<span class="k">30 days {change(first, rate)}</span></a>')

    def sidebar(self, root, here):
        links = [("index.html", "Market home", "index"), ("prices/index.html", "Price check", "prices"),
                 ("listings.html", "FM now", "listings"), ("sales.html", "Recent sales", "sales"),
                 ("nx.html", "NX rate", "nx"), ("economy.html", "Economy", "economy")]
        lis = "".join(f'<li><a href="{root}{MARKET}/{h}"{" aria-current=page" if k == here else ""}>{esc(t)}</a></li>'
                      for h, t, k in links)
        return (f'<nav class="panel dbside" aria-label="Market"><p class="bar">Basil Market</p><ul class="mk">{lis}</ul>'
                f'<div class="in">{self.nx_card(root)}</div></nav>')

    def page(self, rel, title, body, here, description=""):
        root = "../" * rel.count("/")
        banner = ('<p class="samplebar">Sample data: these prices, names and numbers are placeholders until the server '
                  'export is live.</p>') if self.m["sample"] else ""
        html = (f'<div class="db market">{self.sidebar(root, here)}<div class="dbmain data">{banner}'
                f'{body.replace("{root}", root)}</div></div>')
        write(self.out, rel, layout(title, html, root, "market", description))


def item_stats(hist):
    """30 days of one item's rollups -> the numbers its price page shows."""
    rows = [(d, r) for d, r in hist if r]
    if not rows:
        return None
    shown = [(d, r) for d, r in rows if r.get("count", 0) >= MIN_SAMPLES]
    last = shown[-1][1] if shown else None
    sold = sum(r.get("count", 0) for _, r in rows)
    share = sum(r.get("player_share", 0) * r.get("count", 0) for _, r in rows) / (sold or 1)
    first = shown[0][1]["median"] if shown else None
    return {"median": last and last["median"], "low": last and last["low"], "high": last and last["high"],
            "first": first, "sold": sold, "player_share": share, "days": len(rows)}


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
        bot = '<span class="pill">mostly bot-made</span>' if st["player_share"] < 0.5 else ""
        listings = sorted((l for l in m["listings"]["listings"] if l["item_id"] == item_id), key=lambda l: l["price"])
        lrows = "".join(f'<tr><td><span class="sb s">S&gt;</span></td><td class="r num">{l["price"]:,}</td><td class="r num">{l["qty"]}</td>'
                        f'<td>{esc(l.get("seller_name") or ("a player" if l["seller_kind"] == "player" else "a bot"))}'
                        f' <span class="muted">{esc(l["seller_kind"])}</span></td><td>ch{l["channel"]} &middot; room {l["room"]}</td></tr>'
                        for l in listings)
        sales = [x for x in m["sales"]["sales"] if x["item_id"] == item_id][:15]
        srows = "".join(f'<tr><td class="r num">{x["price"]:,}</td><td class="r num">{x["qty"]}</td><td>{esc(x["source"])}</td>'
                        f'<td class="muted">{esc(when(x["hour"]))}</td></tr>' for x in sales)
        median = f'{st["median"]:,}' if st["median"] else "not enough sales"
        rng = f'{mesos(st["low"])} to {mesos(st["high"])}' if st["median"] else "-"
        body = (f'<p class="crumbs"><a href="{{root}}{MARKET}/index.html">Market</a> &raquo; '
                f'<a href="{{root}}{MARKET}/prices/index.html">Price check</a> &raquo; {esc(name)}</p>'
                f'<div class="ent">{mk.icon(item_id, "{root}", big=True)}<div><h1>{esc(name)}</h1>'
                f'<p class="id">Price check &middot; last {len(hist)} days'
                + (f' &middot; <a href="{{root}}database/items/{item_id}.html">Database page</a>' if item_id in mk.db["items"] else "")
                + f' {bot}</p></div></div>'
                f'<div class="mkstats"><div class="hot"><small>Median today</small><b>{median}</b><span>mesos</span></div>'
                f'<div><small>Low to high today</small><b>{rng}</b><span>what most paid</span></div>'
                f'<div><small>30-day change</small><b>{change(st["first"], st["median"]) or "-"}</b><span>median vs 30 days ago</span></div>'
                f'<div><small>Sold</small><b>{st["sold"]:,}</b><span>in {len(hist)} days</span></div></div>'
                f'<section class="panel"><h2 class="bar">Price history <small>median per day; days under {MIN_SAMPLES} sales are left out</small></h2>'
                f'<div class="in">{area_chart(pts, f"Median price of {name} per day")}</div></section>'
                + (f'<section class="panel"><h2 class="bar">FM now <small>whisper or visit to buy</small></h2><div class="tbl"><table class="t">'
                   f'<thead><tr><th></th><th class="r">Price</th><th class="r">Qty</th><th>Seller</th><th>Where</th></tr></thead>'
                   f'<tbody>{lrows}</tbody></table></div></section>' if lrows else "")
                + (f'<section class="panel"><h2 class="bar">Recent sales <small>rounded to the hour</small></h2><div class="tbl"><table class="t">'
                   f'<thead><tr><th class="r">Price</th><th class="r">Qty</th><th>How</th><th>When</th></tr></thead>'
                   f'<tbody>{srows}</tbody></table></div></section>' if srows else ""))
        mk.page(f"{MARKET}/prices/{item_id}.html", f"{name} price", body, "prices", f"{name}: price history and FM listings on SlumsMS.")
    # the list of every priced item
    out.sort(key=lambda x: -x[1]["sold"])
    rows = "".join(f'<tr><td>{mk.icon(i, "{root}")}</td><td>{mk.price_link(i, "{root}")}</td>'
                   f'<td class="r num">{mesos(st["median"]) if st["median"] else "-"}</td><td class="r">{change(st["first"], st["median"])}</td>'
                   f'<td class="r num">{st["sold"]:,}</td></tr>' for i, st in out)
    body = (f'<h1>Price check</h1><p class="id">{len(out)} items sold in the last {HISTORY_DAYS} days, most traded first</p>'
            f'<section class="panel"><h2 class="bar">All prices</h2><div class="tbl"><table class="t icons"><thead><tr><th></th><th>Item</th>'
            f'<th class="r">Median</th><th class="r">30 days</th><th class="r">Sold</th></tr></thead><tbody>{rows}</tbody></table></div></section>')
    mk.page(f"{MARKET}/prices/index.html", "Price check", body, "prices")
    return out


def build_lists(mk, priced):
    m = mk.m
    priced_ids = {i for i, _ in priced}
    lst = sorted(m["listings"]["listings"], key=lambda l: (mk.name(l["item_id"]), l["price"]))
    rows = "".join(f'<tr><td><span class="sb s">S&gt;</span></td><td>{mk.icon(l["item_id"], "{root}")}</td>'
                   f'<td>{mk.price_link(l["item_id"], "{root}") if l["item_id"] in priced_ids else esc(mk.name(l["item_id"]))}</td>'
                   f'<td class="r num">{l["price"]:,}</td><td class="r num">{l["qty"]}</td>'
                   f'<td>{esc(l.get("seller_name") or "")} <span class="muted">{esc(l["seller_kind"])}</span></td>'
                   f'<td>ch{l["channel"]} &middot; {l["room"]}</td><td class="muted">{esc(l.get("shop_title") or "")}</td></tr>' for l in lst)
    body = (f'<h1>FM now</h1><p class="id">{len(lst)} items for sale &middot; as of {esc(when(m["listings"]["generated_at"]))}</p>'
            f'<section class="panel"><h2 class="bar">Trading board <small>S&gt; selling</small></h2><div class="tbl"><table class="t">'
            f'<thead><tr><th></th><th></th><th>Item</th><th class="r">Price</th><th class="r">Qty</th><th>Seller</th><th>Where</th><th>Shop</th></tr></thead>'
            f'<tbody>{rows}</tbody></table></div></section>')
    mk.page(f"{MARKET}/listings.html", "FM now", body, "listings")

    sales = m["sales"]["sales"][:200]
    rows = "".join(f'<tr><td>{mk.icon(x["item_id"], "{root}")}</td><td>{mk.price_link(x["item_id"], "{root}") if x["item_id"] in priced_ids else esc(mk.name(x["item_id"]))}</td>'
                   f'<td class="r num">{x["price"]:,}</td><td class="r num">{x["qty"]}</td><td>{esc(x["source"])}</td>'
                   f'<td class="muted">{esc(when(x["hour"]))}</td></tr>' for x in sales)
    body = (f'<h1>Recent sales</h1><p class="id">The last {len(sales)} sales, newest first, rounded to the hour</p>'
            f'<section class="panel"><h2 class="bar">Sales</h2><div class="tbl"><table class="t icons"><thead><tr><th></th><th>Item</th>'
            f'<th class="r">Price</th><th class="r">Qty</th><th>How</th><th>When</th></tr></thead><tbody>{rows}</tbody></table></div></section>')
    mk.page(f"{MARKET}/sales.html", "Recent sales", body, "sales")

    nx = m["nx-rate"]
    days = nx.get("days") or []
    pts = [(d["date"], d.get("mesos_per_1000nx"), d.get("mesos_per_1000nx"), d.get("mesos_per_1000nx")) for d in days]
    rows = "".join(f'<tr><td class="num">{esc(d["date"])}</td><td class="r num">{mesos(d["mesos_per_1000nx"]) if d.get("mesos_per_1000nx") else "not enough trades"}</td>'
                   f'<td class="r num">{d.get("n_trades", 0)}</td><td class="r num">{d.get("n_cash_resales", 0)}</td>'
                   f'<td class="r num">{d.get("n_code_trades", 0)}</td><td class="r num">{round((d.get("bot_share") or 0) * 100)}%</td></tr>'
                   for d in reversed(days))
    body = (f'<h1>NX rate</h1><p class="id">Mesos per 1,000 NX, from resales of cash-shop items and NX code trades. '
            f'A day with under 5 trades shows no rate.</p>{mk.nx_card("{root}", wide=True)}'
            f'<section class="panel"><h2 class="bar">History</h2><div class="in">{area_chart(pts, "NX rate per day")}</div></section>'
            f'<section class="panel"><h2 class="bar">By day</h2><div class="tbl"><table class="t"><thead><tr><th>Day (UTC)</th>'
            f'<th class="r">Mesos per 1,000 NX</th><th class="r">Trades</th><th class="r">Cash resales</th><th class="r">NX codes</th>'
            f'<th class="r">Bots</th></tr></thead><tbody>{rows}</tbody></table></div></section>')
    mk.page(f"{MARKET}/nx.html", "NX rate", body, "nx")

    e = m["economy-public"]
    facts = [("Mesos held, players and bots", mesos(e.get("mesos_total"))),
             ("Share held by players", f'{round((e.get("players_share") or 0) * 100)}%'),
             ("Trades, last 24 hours", f'{e.get("trades_24h", 0):,}'), ("FM shops open now", f'{e.get("shops_open", 0):,}'),
             ("Mesos removed, last 7 days (tax, fees)", mesos(e.get("mesos_removed_7d"))),
             ("Bots online at the evening peak", f'{e.get("bots_online_peak", 0):,}')]
    econ = "".join(f"<div><dt>{esc(k)}</dt><dd>{v}</dd></div>" for k, v in facts)
    body = (f'<h1>Bera economy</h1><p class="id">As of {esc(when(e["as_of"]))}</p>'
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
                    f'<span class="muted">{esc(when(x["hour"]).split(" (")[0])}</span></li>' for x in sales)
    body = ('<h1>Basil Market</h1><p class="lede">What things really sell for on Bera: prices from finished trades and FM shop '
            'sales, the shops open right now, and the NX rate. Deals happen in game; whisper the seller or visit their shop.</p>'
            f'{mk.nx_card("{root}", wide=True)}'
            f'<div class="mkhome"><section class="panel"><h2 class="bar">Most traded <a href="{{root}}{MARKET}/prices/index.html">All prices &raquo;</a></h2>'
            f'<ul class="mklist">{rows}</ul></section>'
            f'<section class="panel"><h2 class="bar">Just sold <a href="{{root}}{MARKET}/sales.html">More &raquo;</a></h2><ul class="mklist">{srows}</ul></section></div>'
            f'<section class="panel"><h2 class="bar">Bera economy <a href="{{root}}{MARKET}/economy.html">More &raquo;</a></h2><dl class="stats">{econ}</dl></section>')
    mk.page(f"{MARKET}/index.html", "Basil Market", body, "index", "Prices, FM shops and the NX rate on SlumsMS.")


def rail_lines(m, db, count=4):
    """A few megaphone-rail lines from the market: the NX rate and the latest sales."""
    out = []
    today = (m["nx-rate"].get("today") or {}).get("mesos_per_1000nx")
    if today:
        out.append((f"<em>NX RATE</em> 1,000 NX = {mesos(today)} mesos", f"{MARKET}/nx.html"))
    for x in m["sales"]["sales"][:count]:
        it = db["items"].get(x["item_id"])
        if it:
            out.append((f'<em>SOLD</em> {esc(it["name"])} for {mesos(x["price"])}', f'{MARKET}/prices/{x["item_id"]}.html'))
    return out


def build(out, db, data_dir=None):
    m = load(data_dir)
    shutil.rmtree(os.path.join(out, MARKET), ignore_errors=True)
    mk = Market(m, db, out)
    priced = build_prices(mk)
    econ = build_lists(mk, priced)
    build_home(mk, priced, econ)
    return {"priced": len(priced), "listings": len(m["listings"]["listings"]), "sample": m["sample"]}
