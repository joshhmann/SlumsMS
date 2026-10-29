"""The NX-meso exchange's site side (1.6.1, ticket 113): the market app's /api/market/exchange/ URLs.

The site never touches the game's database. It talks to the game server through the board's folders, like the Buy/Sell
board (the "Site interface" in the server's docs/tickets/113-nx-exchange.md, and ExchangeSite.java):
- it writes the inbox file exchange-requests.json: every request not yet answered and less than 10 minutes old
  ({id, account, type, createdAt, ...}). The game reads it when it changes and answers each id once.
- it reads the outbox: exchange-responses.json (the last 500 answers, matched by id and account),
  exchange-tickers.json (every ticker: NX, LEAF, CHAOS, WS, ELIXIR; its rules, book, 24 h numbers and last trades),
  exchange-history.json (hourly candles per ticker, 30 days) and, from a game without tickers, exchange-book.json
  (NX only). None of them has names.

So every write is two steps for the browser: POST a request (the answer is {id}), then GET answer/<id> until the game
has answered (a couple of seconds, at most one poll of the game's, 15 s). Placing is preview, then confirm with the
preview's token within 60 s. The game checks everything (funds, limits, the character); the checks here only save a
round trip.

A stolen cookie must not empty an account (owner, 2026-10-29, b005): placing, confirming, cancelling and asking for my
orders need a sign-in at most 12 h old, like /admin/. Older, the site answers 403 {"code": "REAUTH"} and asks nothing;
every request also carries "siteAuthAt", the session's sign-in time in epoch ms, so the game refuses it too (REAUTH).

    GET  /api/market/exchange/book          every ticker: its rules, book, rate, 24 h numbers and last trades
    GET  /api/market/exchange/history       ?ticker=LEAF  that ticker's hourly candles (30 days)
    GET  /api/market/exchange/me            my characters, my orders (the game's last "mine" answer), my open requests
    GET  /api/market/exchange/answer/<id>   {pending: true} or {answer: {ok, code, message, data}}
    POST /api/market/exchange/preview       {ticker, character, side: BUY|SELL, price, qty}
                                            price: mesos per 1,000 NX (NX) or per item; qty: NX or items
    POST /api/market/exchange/confirm       {token}
    POST /api/market/exchange/cancel        {orderId}
    POST /api/market/exchange/refresh       {}  ask the game for my orders again
"""
import json
import os
import secrets

from market_service import Refused, iso, rows_of, write_atomic

REQUESTS, RESPONSES, BOOK = "exchange-requests.json", "exchange-responses.json", "exchange-book.json"
TICKERS, HISTORY = "exchange-tickers.json", "exchange-history.json"
MAX_AGE = 10 * 60          # the game answers older requests STALE, so they leave the inbox then
MINE_FRESH = 60            # a "mine" answer older than this is asked for again
MAX_WAITING = 5            # requests per account the game hasn't answered yet
SIGN_IN_TTL = 12 * 3600   # a sign-in older than this can't trade (the same 12 h as login_service.ADMIN_TTL)
REAUTH = "For safety, trading needs a sign-in from the last 12 hours. Sign in again."
# The game's defaults (ExchangeConfig; the owner's decisions of 2026-10-28). The game is the authority.
RULES = {"lotNx": 100, "minNx": 100, "maxNx": 50_000, "minPrice": 1_000, "maxPrice": 100_000_000,
         "feeBpsEachSide": 100, "maxOpen": 5, "perDay": 20, "cooldownSec": 10, "expiryHours": 72, "confirmSec": 60,
         "maxMesos": 2_147_483_647}
SIDES = ("BUY", "SELL")
# The exchange-wide limits exchange-tickers.json may carry at its top level, and today's values
LIMITS = ("fee_bps_each_side", "max_open_per_ticker", "per_day", "per_day_scope", "cooldown_sec", "expiry_hours")
DEFAULT_LIMITS = {"fee_bps_each_side": 100, "max_open_per_ticker": 5, "per_day": 20, "per_day_scope": "all",
                  "cooldown_sec": 10, "expiry_hours": 72}
# NX as a game without tickers has it (exchange-book.json): the same keys as a ticker in exchange-tickers.json
NX = {"id": "NX", "name": "NX", "item_id": None, "unit": "1000nx", "unit_label": "mesos per 1,000 NX", "per": 1000,
      "qty_label": "NX", "min_qty": 100, "max_qty": 50_000, "lot": 100, "min_price": 1_000, "max_price": 100_000_000}
# Players see and type NX prices per 1 NX (owner, 2026-10-29: "show prices per 1 NX"); the game keeps them per 1,000 NX
# (unit "1000nx"), so the desk converts at its edge: every NX price to the browser is divided by PER_NX (rounded to a
# whole meso), and a price from the browser is multiplied by it before it goes to the game. Totals stay as they are.
PER_NX = 1000
NX_VIEW = {"unit": "nx", "unit_label": "mesos per NX", "per": 1}
# price fields, per 1,000 NX from the game: in a ticker, a book level or trade, a candle, an order and an answer
PRICE_KEYS = ("min_price", "max_price", "fair", "bid", "ask", "last", "change_24h", "high_24h", "low_24h", "rate",
              "price", "minPrice", "maxPrice", "o", "h", "l", "c")


def per_nx(v):
    """One NX price per 1,000 NX as players see it: per NX, a whole meso (bools and non-numbers pass through)."""
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return v
    return int(round(v / PER_NX))


def nx_prices(d):
    """A copy of one row (ticker, level, trade, candle, order or answer data) with its NX prices per NX."""
    return {k: per_nx(v) if k in PRICE_KEYS else v for k, v in d.items()}


def nx_levels(rows):
    """Book levels per NX: two game prices that round to the same meso become one level (qty and orders added)."""
    out = []
    for r in rows or []:
        r = nx_prices(r)
        if out and out[-1].get("price") == r.get("price"):
            prev = out[-1]
            for k in ("qty", "orders"):
                if isinstance(prev.get(k), int) and isinstance(r.get(k), int):
                    prev[k] += r[k]
            continue
        out.append(r)
    return out


def nx_view(t):
    """A ticker as the browser gets it: NX per NX (book, trades and every price), other tickers as they are."""
    if t.get("id") != "NX" or t.get("unit") != "1000nx":
        return t
    v = dict(nx_prices(t), **NX_VIEW)
    for k in ("bids", "asks"):
        if isinstance(t.get(k), list):
            v[k] = nx_levels([r for r in t[k] if isinstance(r, dict)])
    if isinstance(t.get("trades"), list):
        v["trades"] = [nx_prices(r) for r in t["trades"] if isinstance(r, dict)]
    return v


def nx_order(o):
    """An order (the game's "mine" answer, or a confirm answer) with its price per NX when it's an NX order."""
    return nx_prices(o) if isinstance(o, dict) and (o.get("ticker") or "NX") == "NX" else o
META = tuple(NX) + ("max_open", "per_day", "expiry_hours", "fair", "bid", "ask", "last", "change_24h", "change_24h_pct", "high_24h", "low_24h", "volume_24h",
                    "turnover_24h", "fills_24h", "rate", "bids", "asks", "trades")

SCHEMA = """
CREATE TABLE IF NOT EXISTS xreq (id TEXT PRIMARY KEY, account INTEGER NOT NULL, type TEXT NOT NULL, body TEXT NOT NULL,
    created INTEGER NOT NULL, answered INTEGER, answer TEXT);
CREATE INDEX IF NOT EXISTS xreq_open ON xreq (answered, created);
CREATE TABLE IF NOT EXISTS xmine (account INTEGER PRIMARY KEY, at INTEGER NOT NULL, data TEXT NOT NULL,
    stale INTEGER NOT NULL DEFAULT 0);
"""


def whole(raw, what, lo, hi, step=1):
    if not isinstance(raw, int) or isinstance(raw, bool) or not lo <= raw <= hi or raw % step:
        steps = f" in steps of {step:,}" if step > 1 else ""
        raise Refused(f"{what} must be a whole number from {lo:,} to {hi:,}{steps}.")
    return raw


class Desk:
    """Shares the market store's database, lock and folders (store: market_service.Store)."""

    def __init__(self, store):
        self.store = store
        store.db.executescript(SCHEMA)
        self.seen = None  # (mtime_ns, size) of the last exchange-responses.json read
        self.limits, self.events = {}, []

    def q(self, sql, *args):
        return self.store.db.execute(sql, args).fetchall()

    def _read(self, name):
        try:
            with open(os.path.join(self.store.outbox, name), encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    # ── the game's files ──
    def sync(self):
        """Take in new answers, drop what the game will no longer answer, rewrite the inbox if anything changed."""
        t = int(self.store.now())
        changed = False
        try:
            st = os.stat(os.path.join(self.store.outbox, RESPONSES))
            stamp = (st.st_mtime_ns, st.st_size)
        except OSError:
            stamp = None
        if stamp and stamp != self.seen:
            data = self._read(RESPONSES)
            if data is not None:  # half-written or broken: try again next time
                self.seen = stamp
                for r in rows_of(data, "responses"):
                    changed |= self._answer(r, t)
        for (rid,) in self.q("SELECT id FROM xreq WHERE answered IS NULL AND created<?", t - MAX_AGE):
            self._set(rid, t, {"ok": False, "code": "NO_ANSWER", "message": "the game didn't answer", "data": {}})
            changed = True
        self.q("DELETE FROM xreq WHERE created<?", t - 86400)
        if changed:
            self.export()

    def _answer(self, r, t):
        if not isinstance(r, dict) or not isinstance(r.get("id"), str):
            return False
        row = self.q("SELECT account, type FROM xreq WHERE id=? AND answered IS NULL", r["id"])
        if not row or r.get("account") != row[0][0]:
            return False
        account, kind = row[0]
        answer = {"ok": r.get("ok") is True, "code": str(r.get("code") or ""), "message": str(r.get("message") or ""),
                  "data": r.get("data") if isinstance(r.get("data"), dict) else {}}
        self._set(r["id"], t, answer)
        if kind == "mine" and answer["ok"]:
            self.q("INSERT OR REPLACE INTO xmine (account, at, data, stale) VALUES (?, ?, ?, 0)",
                   account, t, json.dumps(answer["data"]))
        elif kind in ("confirm", "cancel"):
            self.q("UPDATE xmine SET stale=1 WHERE account=?", account)  # the orders changed: ask again
        return True

    def _set(self, rid, t, answer):
        self.q("UPDATE xreq SET answered=?, answer=? WHERE id=?", t, json.dumps(answer), rid)

    def export(self):
        rows = self.q("SELECT body FROM xreq WHERE answered IS NULL AND created>=? ORDER BY created",
                      int(self.store.now()) - MAX_AGE)
        write_atomic(os.path.join(self.store.inbox, REQUESTS),
                     {"schema": 1, "requests": [json.loads(b) for (b,) in rows]})

    def signed_in_at(self, s):
        iat = s.get("iat")
        return iat if isinstance(iat, int) and not isinstance(iat, bool) else 0

    def fresh(self, s):
        return int(self.store.now()) - self.signed_in_at(s) <= SIGN_IN_TTL

    def need_fresh(self, s):
        if not self.fresh(s):
            raise Refused(REAUTH, 403, "REAUTH")

    def ask(self, s, kind, **fields):
        """Queue one request for the game; its id. The session must be fresh (need_fresh)."""
        account = s["a"]
        waiting = self.q("SELECT type FROM xreq WHERE account=? AND answered IS NULL AND created>=?",
                         account, int(self.store.now()) - MAX_AGE)
        if kind == "mine" and ("mine",) in waiting:
            return None
        if len(waiting) >= MAX_WAITING:
            raise Refused("The game hasn't answered your last few requests yet. Wait a moment.", 429)
        rid = "w" + secrets.token_hex(12)
        t = int(self.store.now())
        body = dict(id=rid, account=account, type=kind, createdAt=iso(t), siteAuthAt=self.signed_in_at(s) * 1000,
                    **fields)
        self.q("INSERT INTO xreq (id, account, type, body, created) VALUES (?, ?, ?, ?, ?)",
               rid, account, kind, json.dumps(body, separators=(",", ":")), t)
        self.export()
        return rid

    # ── what the URLs do (the caller holds store.lock and has synced) ──
    def tickers(self):
        """(tickers, halted, generated_at) from exchange-tickers.json, else NX alone from exchange-book.json."""
        t = self._read(TICKERS)
        if isinstance(t, dict) and isinstance(t.get("tickers"), list):
            rows = [nx_view({k: x.get(k) for k in META}) for x in t["tickers"]
                    if isinstance(x, dict) and isinstance(x.get("id"), str)]
            self.limits = {k: t[k] for k in LIMITS if isinstance(t.get(k), (int, str))}
            # announced market events only (Leaf Rush ...; the secret ones never reach the export)
            self.events = [{k: e.get(k) for k in ("id", "name", "ticker", "ends_at")} for e in t.get("events") or []
                           if isinstance(e, dict) and isinstance(e.get("name"), str)]
            return rows, bool(t.get("halted")), t.get("generated_at")
        self.limits, self.events = {}, []
        b = self._read(BOOK)
        if not isinstance(b, dict):
            return [], False, None
        lv = lambda rows: [{"price": r.get("price"), "qty": r.get("nx"), "orders": r.get("orders")}
                           for r in rows or [] if isinstance(r, dict)]
        trades = [{"t": r.get("t"), "price": r.get("price"), "qty": r.get("nx")} for r in b.get("trades") or []
                  if isinstance(r, dict)]
        bids, asks = lv(b.get("bids")), lv(b.get("asks"))
        nx = dict(NX, bids=bids, asks=asks, trades=trades, rate=(b.get("rate") or {}).get("mesos_per_1000nx"),
                  bid=bids[0]["price"] if bids else None, ask=asks[0]["price"] if asks else None,
                  last=trades[-1]["price"] if trades else None)
        return [nx_view(nx)], bool(b.get("halted")), b.get("generated_at")

    def ticker(self, raw):
        rows = self.tickers()[0] or [nx_view(NX)]
        want = raw.strip().upper() if isinstance(raw, str) else "NX"
        for t in rows:
            if t["id"] == want:
                return t
        raise Refused("Pick a ticker from the list.")

    def book(self):
        rows, halted, at = self.tickers()
        # the game's limits when it publishes them, else the defaults (the owner may raise them: 2026-10-28)
        limits = dict(DEFAULT_LIMITS, **self.limits)
        return {"tickers": rows, "halted": halted, "generated_at": at, "limits": limits, "events": self.events,
                "rules": RULES}

    def history(self, raw):
        t = self.ticker(raw)
        h = self._read(HISTORY)
        got = (h.get("tickers") or {}).get(t["id"]) if isinstance(h, dict) else None
        candles = [c for c in (got.get("candles") if isinstance(got, dict) else None) or [] if isinstance(c, dict)]
        if t["id"] == "NX":
            candles = [nx_prices(c) for c in candles]
        return {"ticker": t["id"], "interval": (h or {}).get("interval", "1h") if isinstance(h, dict) else "1h",
                "candles": candles}

    def me(self, s, names):
        a, fresh = s["a"], self.fresh(s)
        mine = self.q("SELECT at, data, stale FROM xmine WHERE account=?", a)
        if fresh and (not mine or mine[0][2] or mine[0][0] < int(self.store.now()) - MINE_FRESH):
            self.ask(s, "mine")
        waiting = [{"id": i, "type": k, "createdAt": iso(c)} for i, k, c in self.q(
            "SELECT id, type, created FROM xreq WHERE account=? AND answered IS NULL ORDER BY created", a)]
        orders = json.loads(mine[0][1]).get("orders") if mine else None
        return {"characters": names, "orders": [nx_order(o) for o in orders] if isinstance(orders, list) else None,
                "ordersAt": iso(mine[0][0]) if mine else None, "waiting": waiting, "rules": RULES,
                "reauth": not fresh, "signedInAt": iso(self.signed_in_at(s)),
                "tradeUntil": iso(self.signed_in_at(s) + SIGN_IN_TTL)}

    def answer(self, s, rid):
        row = self.q("SELECT answered, answer FROM xreq WHERE id=? AND account=?", rid, s["a"])
        if not row:
            raise Refused("No such request.", 404)
        if row[0][0] is None:
            return {"pending": True}
        a = json.loads(row[0][1])
        # NX prices in the answer per NX too: a preview's or an order's price, PRICE_BAND's range (NX only)
        d = a.get("data") if isinstance(a, dict) else None
        if isinstance(d, dict):
            if isinstance(d.get("orders"), list):  # a "mine" answer
                d = dict(d, orders=[nx_order(o) for o in d["orders"]])
            elif (d.get("ticker") or "NX") == "NX":
                d = nx_prices(d)
            a = dict(a, data=d)
        return {"pending": False, "answer": a}

    def preview(self, s, body, names):
        self.need_fresh(s)
        t = self.ticker(body.get("ticker"))
        name = body.get("character")
        if name not in names:
            raise Refused("Pick one of your characters.")
        side = body.get("side")
        if side not in SIDES:
            raise Refused("Pick buy or sell.")
        qty = body.get("qty", body.get("nx"))
        num = lambda k, d: t.get(k) if isinstance(t.get(k), int) else nx_view(NX)[k] if t["id"] == "NX" else d
        what = "NX" if t["id"] == "NX" else "The number of items"
        qty = whole(qty, what, num("min_qty", 1), num("max_qty", 1), num("lot", 1))
        price = whole(body.get("price"), "The price", num("min_price", 1), num("max_price", 1))
        cost = qty * price // (t.get("per") or 1)
        if side == "BUY" and cost + cost * RULES["feeBpsEachSide"] // 10_000 > RULES["maxMesos"]:
            raise Refused("That costs more mesos than a character can hold.")
        # the browser's NX price is per NX (nx_view); the game's is per 1,000 NX
        fields = dict(characterName=name, side=side, price=price * PER_NX if t.get("unit") == "nx" else price)
        if t["id"] == "NX":
            fields["nx"] = qty  # a game without tickers reads only this
        else:
            fields.update(ticker=t["id"], qty=qty)
        return {"id": self.ask(s, "preview", **fields)}

    def confirm(self, s, body):
        self.need_fresh(s)
        token = body.get("token")
        if not isinstance(token, str) or not 8 <= len(token) <= 64 or any(c not in "0123456789abcdef" for c in token):
            raise Refused("That preview expired. Preview the order again.")
        return {"id": self.ask(s, "confirm", token=token)}

    def cancel(self, s, body):
        self.need_fresh(s)
        order = body.get("orderId")
        if not isinstance(order, int) or isinstance(order, bool) or order <= 0:
            raise Refused("No such order.")
        return {"id": self.ask(s, "cancel", orderId=order)}

    def refresh(self, s):
        self.need_fresh(s)
        return {"id": self.ask(s, "mine")}
