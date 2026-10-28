"""The NX-meso exchange's site side (1.6.1, ticket 113): the market app's /api/market/exchange/ URLs.

The site never touches the game's database. It talks to the game server through the board's folders, like the Buy/Sell
board (the "Site interface" in the server's docs/tickets/113-nx-exchange.md, and ExchangeSite.java):
- it writes the inbox file exchange-requests.json: every request not yet answered and less than 10 minutes old
  ({id, account, type, createdAt, ...}). The game reads it when it changes and answers each id once.
- it reads the outbox: exchange-responses.json (the last 500 answers, matched by id and account) and
  exchange-book.json (the book, the official rate and the last trades; no names).

So every write is two steps for the browser: POST a request (the answer is {id}), then GET answer/<id> until the game
has answered (a couple of seconds, at most one poll of the game's, 15 s). Placing is preview, then confirm with the
preview's token within 60 s. The game checks everything (funds, limits, the character); the checks here only save a
round trip.

    GET  /api/market/exchange/book          the book, the rate, the last trades, the rules
    GET  /api/market/exchange/me            my characters, my orders (the game's last "mine" answer), my open requests
    GET  /api/market/exchange/answer/<id>   {pending: true} or {answer: {ok, code, message, data}}
    POST /api/market/exchange/preview       {character, side: BUY|SELL, price, nx}   price: mesos per 1,000 NX
    POST /api/market/exchange/confirm       {token}
    POST /api/market/exchange/cancel        {orderId}
    POST /api/market/exchange/refresh       {}  ask the game for my orders again
"""
import json
import os
import secrets

from market_service import Refused, iso, rows_of, write_atomic

REQUESTS, RESPONSES, BOOK = "exchange-requests.json", "exchange-responses.json", "exchange-book.json"
MAX_AGE = 10 * 60          # the game answers older requests STALE, so they leave the inbox then
MINE_FRESH = 60            # a "mine" answer older than this is asked for again
MAX_WAITING = 5            # requests per account the game hasn't answered yet
# The game's defaults (ExchangeConfig; the owner's decisions of 2026-10-28). The game is the authority.
RULES = {"lotNx": 100, "minNx": 100, "maxNx": 50_000, "minPrice": 1_000, "maxPrice": 100_000_000,
         "feeBpsEachSide": 100, "maxOpen": 5, "perDay": 20, "cooldownSec": 10, "expiryHours": 72, "confirmSec": 60,
         "maxMesos": 2_147_483_647}
SIDES = ("BUY", "SELL")

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

    def ask(self, account, kind, **fields):
        """Queue one request for the game; its id."""
        waiting = self.q("SELECT type FROM xreq WHERE account=? AND answered IS NULL AND created>=?",
                         account, int(self.store.now()) - MAX_AGE)
        if kind == "mine" and ("mine",) in waiting:
            return None
        if len(waiting) >= MAX_WAITING:
            raise Refused("The game hasn't answered your last few requests yet. Wait a moment.", 429)
        rid = "w" + secrets.token_hex(12)
        t = int(self.store.now())
        body = dict(id=rid, account=account, type=kind, createdAt=iso(t), **fields)
        self.q("INSERT INTO xreq (id, account, type, body, created) VALUES (?, ?, ?, ?, ?)",
               rid, account, kind, json.dumps(body, separators=(",", ":")), t)
        self.export()
        return rid

    # ── what the URLs do (the caller holds store.lock and has synced) ──
    def book(self):
        b = self._read(BOOK)
        if not isinstance(b, dict):
            return {"book": None, "rules": RULES}
        keep = ("generated_at", "halted", "rate", "bids", "asks", "trades")
        return {"book": {k: b.get(k) for k in keep}, "rules": RULES}

    def me(self, s, names):
        a = s["a"]
        mine = self.q("SELECT at, data, stale FROM xmine WHERE account=?", a)
        if not mine or mine[0][2] or mine[0][0] < int(self.store.now()) - MINE_FRESH:
            self.ask(a, "mine")
        waiting = [{"id": i, "type": k, "createdAt": iso(c)} for i, k, c in self.q(
            "SELECT id, type, created FROM xreq WHERE account=? AND answered IS NULL ORDER BY created", a)]
        orders = json.loads(mine[0][1]).get("orders") if mine else None
        return {"characters": names, "orders": orders if isinstance(orders, list) else None,
                "ordersAt": iso(mine[0][0]) if mine else None, "waiting": waiting, "rules": RULES}

    def answer(self, s, rid):
        row = self.q("SELECT answered, answer FROM xreq WHERE id=? AND account=?", rid, s["a"])
        if not row:
            raise Refused("No such request.", 404)
        return {"pending": True} if row[0][0] is None else {"pending": False, "answer": json.loads(row[0][1])}

    def preview(self, s, body, names):
        name = body.get("character")
        if name not in names:
            raise Refused("Pick one of your characters.")
        side = body.get("side")
        if side not in SIDES:
            raise Refused("Pick buy or sell.")
        nx = whole(body.get("nx"), "NX", RULES["minNx"], RULES["maxNx"], RULES["lotNx"])
        price = whole(body.get("price"), "The price", RULES["minPrice"], RULES["maxPrice"])
        cost = nx * price // 1000
        if side == "BUY" and cost + cost * RULES["feeBpsEachSide"] // 10_000 > RULES["maxMesos"]:
            raise Refused("That costs more mesos than a character can hold.")
        return {"id": self.ask(s["a"], "preview", characterName=name, side=side, price=price, nx=nx)}

    def confirm(self, s, body):
        token = body.get("token")
        if not isinstance(token, str) or not 8 <= len(token) <= 64 or any(c not in "0123456789abcdef" for c in token):
            raise Refused("That preview expired. Preview the order again.")
        return {"id": self.ask(s["a"], "confirm", token=token)}

    def cancel(self, s, body):
        order = body.get("orderId")
        if not isinstance(order, int) or isinstance(order, bool) or order <= 0:
            raise Refused("No such order.")
        return {"id": self.ask(s["a"], "cancel", orderId=order)}

    def refresh(self, s):
        return {"id": self.ask(s["a"], "mine")}
