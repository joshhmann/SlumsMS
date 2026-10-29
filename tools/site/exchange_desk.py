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
            rows = [{k: x.get(k) for k in META} for x in t["tickers"]
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
        return [nx], bool(b.get("halted")), b.get("generated_at")

    def ticker(self, raw):
        rows = self.tickers()[0] or [NX]
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
        candles = got.get("candles") if isinstance(got, dict) else None
        return {"ticker": t["id"], "interval": (h or {}).get("interval", "1h") if isinstance(h, dict) else "1h",
                "candles": [c for c in candles or [] if isinstance(c, dict)]}

    def me(self, s, names):
        a, fresh = s["a"], self.fresh(s)
        mine = self.q("SELECT at, data, stale FROM xmine WHERE account=?", a)
        if fresh and (not mine or mine[0][2] or mine[0][0] < int(self.store.now()) - MINE_FRESH):
            self.ask(s, "mine")
        waiting = [{"id": i, "type": k, "createdAt": iso(c)} for i, k, c in self.q(
            "SELECT id, type, created FROM xreq WHERE account=? AND answered IS NULL ORDER BY created", a)]
        orders = json.loads(mine[0][1]).get("orders") if mine else None
        return {"characters": names, "orders": orders if isinstance(orders, list) else None,
                "ordersAt": iso(mine[0][0]) if mine else None, "waiting": waiting, "rules": RULES,
                "reauth": not fresh, "signedInAt": iso(self.signed_in_at(s)),
                "tradeUntil": iso(self.signed_in_at(s) + SIGN_IN_TTL)}

    def answer(self, s, rid):
        row = self.q("SELECT answered, answer FROM xreq WHERE id=? AND account=?", rid, s["a"])
        if not row:
            raise Refused("No such request.", 404)
        return {"pending": True} if row[0][0] is None else {"pending": False, "answer": json.loads(row[0][1])}

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
        num = lambda k, d: t.get(k) if isinstance(t.get(k), int) else NX[k] if t["id"] == "NX" else d
        what = "NX" if t["unit"] == "1000nx" else "The number of items"
        qty = whole(qty, what, num("min_qty", 1), num("max_qty", 1), num("lot", 1))
        price = whole(body.get("price"), "The price", num("min_price", 1), num("max_price", 1))
        cost = qty * price // (t.get("per") or 1)
        if side == "BUY" and cost + cost * RULES["feeBpsEachSide"] // 10_000 > RULES["maxMesos"]:
            raise Refused("That costs more mesos than a character can hold.")
        fields = dict(characterName=name, side=side, price=price)
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
