#!/usr/bin/env python3
"""Slums Market for signed-in players: watchlist, price alerts and the Buy/Sell board. Python 3 standard library only.

    python3 tools/site/market_service.py [--db PATH] [--inbox DIR] [--outbox DIR] [--items FILE] [--port 8095]

Owner, 2026-10-26: nothing on the site moves items or mesos. Per game account: 20 watched items, 10 alerts (each fires
once, then the player re-arms it by hand) and 5 open board posts (WTB or WTS, posted as one of the account's
characters with the IGN shown, 3-day expiry, a take-down button; GMs can take down anything).

The site keeps its own store (SQLite, --db) and talks to the game server only through files:
- it writes the inbox (--inbox), atomically, after every change:
    posts.json   every open post: {id, account, character, kind: WTB|WTS, itemId, qty, price, createdAt, expiresAt}
    alerts.json  per account: {watch: [itemIds], alerts: [{id, itemId, dir: below|above, price, armed}]}
- it reads the outbox (--outbox, /var/lib/slums-market/outbox: the live Server folder is root-only, so the game
  writes here) whenever a file there changes:
    board.json          the validated player posts plus a few real bot posts, names only (shown as is, no labels)
    alerts-fired.json   fired alert ids per account with the time and the price: the site shows a badge and disarms
    rejected.json       posts the server refused, with a reason
    removed.json        posts a GM took down in game
The shapes are the "Site board contract" in docs/design/track-d-plan.md (release-1.6.0); the outbox is read leniently.
The game's rejected.json is the authority; the checks here (limits, the per-trade meso cap, untradeable items) only
save a round trip.

Who is asking: the signed session cookie from login_service.py (same key file, read only). A session carries the
account id, the character it signed in with, and (when the game sends them) the account's other characters and
GM flag. Writes need a JSON body, the header "X-Slums: 1" (a cross-site form can't send it) and, when the browser sends
an Origin, one that matches the Host.

Endpoints (127.0.0.1 only; nginx passes /api/market/ through):
    GET  /api/market/me                    watchlist, alerts, my posts, badge count, my characters
    GET  /api/market/board                 the board (from the server's board.json, expired posts dropped)
    POST /api/market/watch                 {itemId, on}
    POST /api/market/alerts                {itemId, dir: below|above, price}
    POST /api/market/alerts/rearm          {id}
    POST /api/market/alerts/delete         {id}
    POST /api/market/alerts/seen           {}            clears the badge
    POST /api/market/posts                 {kind, itemId, qty, price, character}
    POST /api/market/posts/close           {id}          the poster's take-down, or a GM's
    GET  /api/market/health
"""
import argparse
import datetime
import json
import os
import secrets
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from login_service import SESSION_COOKIE, Sessions, cookies  # noqa: E402

MAX_WATCH, MAX_ALERTS, MAX_OPEN_POSTS = 20, 10, 5
POST_TTL = 3 * 86400
MAX_QTY, MAX_PRICE = 32767, 2_147_483_647  # a v83 stack, and the game's meso cap
MAX_TOTAL = 2_147_483_647  # qty x price must fit one trade window (Site board contract)
WRITES_PER_MIN = 30
MAX_BODY = 2048
KINDS = ("WTB", "WTS")
DIRS = ("below", "above")

SCHEMA = """
CREATE TABLE IF NOT EXISTS watch (account INTEGER, item INTEGER, created INTEGER, PRIMARY KEY (account, item));
CREATE TABLE IF NOT EXISTS alerts (id TEXT PRIMARY KEY, account INTEGER, item INTEGER, dir TEXT, price INTEGER,
    armed INTEGER, created INTEGER, fired_at TEXT, fired_price INTEGER, seen INTEGER DEFAULT 1, armed_at INTEGER);
CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, account INTEGER, character TEXT, kind TEXT, item INTEGER,
    qty INTEGER, price INTEGER, created INTEGER, expires INTEGER, status TEXT, reason TEXT);
CREATE TABLE IF NOT EXISTS seen_files (name TEXT PRIMARY KEY, mtime REAL);
"""


class Refused(Exception):
    """A request the player can fix: shown to them as is (status 400, or 409 at a limit)."""

    def __init__(self, msg, status=400):
        super().__init__(msg)
        self.status = status


def iso(t):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


def epoch(value, default):
    """A contract time ('2026-10-26T07:15:00Z', with or without milliseconds) as epoch seconds."""
    try:
        return datetime.datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp()
    except ValueError:
        return default


def write_atomic(path, obj):
    tmp = f"{path}.tmp-{os.getpid()}-{threading.get_ident()}"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, separators=(",", ":"), sort_keys=True)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def rows_of(data, *keys):
    """The outbox read leniently: a list, or a list under one of keys."""
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for k in keys:
            if isinstance(data.get(k), list):
                return data[k]
    return []


class Items:
    """Item ids players may post or watch, with names: the site's own search index (database/search-index.json), and the
    ones the game won't allow on the board (market/untradeable.json beside it, written by the market build)."""

    def __init__(self, path, untradeable=None):
        self.path, self.mtime, self.names = path, None, {}
        self.untradeable_path, self.u_mtime, self.blocked = untradeable, None, set()

    def untradeable(self):
        p = self.untradeable_path
        try:
            m = os.path.getmtime(p) if p else None
        except OSError:
            return self.blocked
        if m is not None and m != self.u_mtime:
            try:
                with open(p, encoding="utf-8") as f:
                    self.blocked = {int(i) for i in json.load(f)}
                self.u_mtime = m
            except (OSError, ValueError, TypeError):
                pass
        return self.blocked

    def get(self):
        try:
            m = os.path.getmtime(self.path)
        except OSError:
            return self.names
        if m != self.mtime:
            with open(self.path, encoding="utf-8") as f:
                rows = json.load(f)
            self.names = {int(r[1]): r[2] for r in rows if isinstance(r, list) and len(r) >= 3 and r[0] == "i"}
            self.mtime = m
        return self.names


class Store:
    def __init__(self, db_path, inbox, outbox, items, now=time.time):
        self.inbox, self.outbox, self.items, self.now = inbox, outbox, items, now
        self.lock = threading.Lock()
        self.db = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)
        if "armed_at" not in {r[1] for r in self.q("PRAGMA table_info(alerts)")}:  # stores from before armedAt
            self.db.execute("ALTER TABLE alerts ADD COLUMN armed_at INTEGER")
        self.writes = {}
        os.makedirs(inbox, exist_ok=True)

    # ── helpers ──
    def q(self, sql, *args):
        return self.db.execute(sql, args).fetchall()

    def item(self, raw):
        try:
            item = int(raw)
        except (TypeError, ValueError):
            raise Refused("Pick an item from the list.")
        if item not in self.items.get():
            raise Refused("That item isn't on Slums Street, so it can't be watched or posted.")
        return item

    def tradeable(self, item):
        if item in self.items.untradeable():
            raise Refused("That item can't be traded (untradeable, account-bound or a quest item), so it can't be posted.")
        return item

    @staticmethod
    def amount(raw, what, hi):
        if not isinstance(raw, int) or isinstance(raw, bool) or not 1 <= raw <= hi:
            raise Refused(f"{what} must be a whole number from 1 to {hi:,}.")
        return raw

    def rate(self, account):
        t = self.now()
        recent = [x for x in self.writes.get(account, []) if x > t - 60]
        if len(recent) >= WRITES_PER_MIN:
            raise Refused("That's a lot of changes at once. Wait a minute and try again.", 429)
        self.writes[account] = recent + [t]

    # ── the game server's files ──
    def sync(self):
        """Read any outbox file that changed, expire old posts. Called before every read and write."""
        t = int(self.now())
        self.db.execute("UPDATE posts SET status='expired' WHERE status='open' AND expires<?", (t,))
        changed = False
        for name in ("alerts-fired.json", "rejected.json", "removed.json"):
            path = os.path.join(self.outbox, name)
            try:
                m = os.path.getmtime(path)
            except OSError:
                continue
            seen = self.q("SELECT mtime FROM seen_files WHERE name=?", name)
            if seen and seen[0][0] == m:
                continue
            try:
                with open(path, encoding="utf-8") as f:
                    data = json.load(f)
            except (OSError, json.JSONDecodeError):
                continue  # half-written or broken: try again next time
            changed |= getattr(self, "_" + name.split(".")[0].replace("-", "_"))(data)
            self.db.execute("INSERT OR REPLACE INTO seen_files VALUES (?, ?)", (name, m))
        if changed:
            self.export()

    def _alerts_fired(self, data):
        """{"<account>": [{id, t, price}]} or [{account, id, t, price}]: disarm and light the badge, once per firing."""
        fired = []
        if isinstance(data, dict) and not any(isinstance(data.get(k), list) for k in ("fired", "alerts")):
            for acct, rows in data.items():
                if str(acct).isdigit() and isinstance(rows, list):
                    fired += [dict(r, account=int(acct)) for r in rows if isinstance(r, dict)]
        else:
            fired = [r for r in rows_of(data, "fired", "alerts") if isinstance(r, dict)]
        n = 0
        for r in fired:
            cur = self.db.execute("UPDATE alerts SET armed=0, seen=0, fired_at=?, fired_price=? "
                                  "WHERE id=? AND account=? AND armed=1",
                                  (str(r.get("t") or r.get("time") or ""), r.get("price"), str(r.get("id")),
                                   int(r.get("account", -1)) if str(r.get("account", "")).lstrip("-").isdigit() else -1))
            n += cur.rowcount
        return n > 0

    def _rejected(self, data):
        n = 0
        for r in rows_of(data, "rejected", "posts"):
            if isinstance(r, dict) and r.get("id"):
                n += self.db.execute("UPDATE posts SET status='rejected', reason=? WHERE id=? AND status='open'",
                                     (str(r.get("reason") or "refused by the game")[:200], str(r["id"]))).rowcount
        return n > 0

    def _removed(self, data):
        n = 0
        for r in rows_of(data, "removed", "posts"):
            if isinstance(r, dict) and r.get("id"):
                n += self.db.execute("UPDATE posts SET status='removed', reason=? WHERE id=? AND status='open'",
                                     (str(r.get("reason") or "taken down by a GM")[:200], str(r["id"]))).rowcount
        return n > 0

    def export(self):
        """Write the inbox for the game server: every open post, and every account's watchlist and alerts."""
        posts = [{"id": i, "account": a, "character": c, "kind": k, "itemId": it, "qty": qt, "price": p,
                  "createdAt": iso(cr), "expiresAt": iso(ex)}
                 for i, a, c, k, it, qt, p, cr, ex in self.q(
                     "SELECT id, account, character, kind, item, qty, price, created, expires FROM posts "
                     "WHERE status='open' ORDER BY created")]
        accounts = {}
        for a, it in self.q("SELECT account, item FROM watch ORDER BY created"):
            accounts.setdefault(str(a), {"watch": [], "alerts": []})["watch"].append(it)
        # armedAt tells the game a re-armed alert may fire again even if it never saw it disarmed (contract)
        for i, a, it, d, p, armed, at in self.q(
                "SELECT id, account, item, dir, price, armed, COALESCE(armed_at, created) FROM alerts ORDER BY created"):
            accounts.setdefault(str(a), {"watch": [], "alerts": []})["alerts"].append(
                {"id": i, "itemId": it, "dir": d, "price": p, "armed": bool(armed), "armedAt": iso(at)})
        stamp = iso(self.now())
        write_atomic(os.path.join(self.inbox, "posts.json"), {"schema": 1, "generated_at": stamp, "posts": posts})
        write_atomic(os.path.join(self.inbox, "alerts.json"), {"schema": 1, "generated_at": stamp, "accounts": accounts})

    # ── what a player sees ──
    def me(self, s):
        a = s["a"]
        names = self.items.get()
        alerts = [{"id": i, "itemId": it, "name": names.get(it, f"Item {it}"), "dir": d, "price": p, "armed": bool(ar),
                   "firedAt": fa, "firedPrice": fp, "new": not se}
                  for i, it, d, p, ar, fa, fp, se in self.q(
                      "SELECT id, item, dir, price, armed, fired_at, fired_price, seen FROM alerts WHERE account=? "
                      "ORDER BY created", a)]
        posts = [{"id": i, "character": c, "kind": k, "itemId": it, "name": names.get(it, f"Item {it}"), "qty": qt,
                  "price": p, "createdAt": iso(cr), "expiresAt": iso(ex), "status": st, "reason": rs}
                 for i, c, k, it, qt, p, cr, ex, st, rs in self.q(
                     "SELECT id, character, kind, item, qty, price, created, expires, status, reason FROM posts "
                     "WHERE account=? AND (status='open' OR created>?) ORDER BY created DESC", a, int(self.now()) - 7 * 86400)]
        watch = [{"itemId": it, "name": names.get(it, f"Item {it}")} for (it,) in self.q(
            "SELECT item FROM watch WHERE account=? ORDER BY created", a)]
        return {"character": s.get("c"), "characters": characters(s), "gm": bool(s.get("g")), "watch": watch,
                "alerts": alerts, "posts": posts, "badge": sum(1 for x in alerts if x["new"]),
                "limits": {"watch": MAX_WATCH, "alerts": MAX_ALERTS, "posts": MAX_OPEN_POSTS}}

    def board(self):
        """The server's board.json, expired posts dropped. Bot and player posts look the same (names only)."""
        try:
            with open(os.path.join(self.outbox, "board.json"), encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, json.JSONDecodeError):
            return {"posts": [], "generated_at": None}
        names, now = self.items.get(), self.now()
        out = []
        for r in rows_of(data, "posts"):
            if not isinstance(r, dict) or r.get("kind") not in KINDS or not isinstance(r.get("itemId"), int):
                continue
            if epoch(r.get("expiresAt"), now + 1) < now:
                continue
            out.append({"id": str(r.get("id", "")), "character": str(r.get("character", ""))[:13], "kind": r["kind"],
                        "itemId": r["itemId"], "name": names.get(r["itemId"], f"Item {r['itemId']}"),
                        "qty": r.get("qty"), "price": r.get("price"), "createdAt": r.get("createdAt"),
                        "expiresAt": r.get("expiresAt")})
        return {"posts": out, "generated_at": data.get("generated_at") if isinstance(data, dict) else None}

    # ── changes ──
    def watch(self, s, body):
        a, item = s["a"], self.item(body.get("itemId"))
        if body.get("on", True):
            if not self.q("SELECT 1 FROM watch WHERE account=? AND item=?", a, item):
                if self.q("SELECT COUNT(*) FROM watch WHERE account=?", a)[0][0] >= MAX_WATCH:
                    raise Refused(f"Your watchlist is full ({MAX_WATCH} items). Remove one first.", 409)
                self.db.execute("INSERT INTO watch VALUES (?, ?, ?)", (a, item, int(self.now())))
        else:
            self.db.execute("DELETE FROM watch WHERE account=? AND item=?", (a, item))

    def add_alert(self, s, body):
        a, item = s["a"], self.item(body.get("itemId"))
        if body.get("dir") not in DIRS:
            raise Refused("Choose 'below' or 'above'.")
        price = self.amount(body.get("price"), "The price", MAX_PRICE)
        if self.q("SELECT COUNT(*) FROM alerts WHERE account=?", a)[0][0] >= MAX_ALERTS:
            raise Refused(f"You have {MAX_ALERTS} alerts already. Delete one first.", 409)
        aid = "a" + secrets.token_hex(6)
        t = int(self.now())
        self.db.execute("INSERT INTO alerts (id, account, item, dir, price, armed, created, armed_at) "
                        "VALUES (?, ?, ?, ?, ?, 1, ?, ?)", (aid, a, item, body["dir"], price, t, t))
        return aid

    def alert(self, s, body, action):
        aid = str(body.get("id", ""))
        if not self.q("SELECT 1 FROM alerts WHERE id=? AND account=?", aid, s["a"]):
            raise Refused("That alert isn't yours, or it's gone.", 404)
        if action == "rearm":
            self.db.execute("UPDATE alerts SET armed=1, seen=1, fired_at=NULL, fired_price=NULL, armed_at=? WHERE id=?",
                            (int(self.now()), aid))
        else:
            self.db.execute("DELETE FROM alerts WHERE id=?", (aid,))

    def seen(self, s):
        self.db.execute("UPDATE alerts SET seen=1 WHERE account=?", (s["a"],))

    def add_post(self, s, body):
        a = s["a"]
        if body.get("kind") not in KINDS:
            raise Refused("Choose WTB (buying) or WTS (selling).")
        item = self.tradeable(self.item(body.get("itemId")))
        qty = self.amount(body.get("qty"), "The amount", MAX_QTY)
        price = self.amount(body.get("price"), "The price", MAX_PRICE)
        if qty * price > MAX_TOTAL:
            raise Refused(f"{qty:,} × {price:,} is over {MAX_TOTAL:,} mesos, the most one trade can hold. "
                          "Post a smaller amount.")
        who = body.get("character") or s.get("c")
        if who not in characters(s):
            raise Refused("Post as one of your own characters.")
        if self.q("SELECT COUNT(*) FROM posts WHERE account=? AND status='open'", a)[0][0] >= MAX_OPEN_POSTS:
            raise Refused(f"You have {MAX_OPEN_POSTS} open posts. Take one down first.", 409)
        t = int(self.now())
        pid = "p" + secrets.token_hex(6)
        self.db.execute("INSERT INTO posts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', NULL)",
                        (pid, a, who, body["kind"], item, qty, price, t, t + POST_TTL))
        return pid

    def close_post(self, s, body):
        pid = str(body.get("id", ""))
        row = self.q("SELECT account FROM posts WHERE id=? AND status='open'", pid)
        if not row:
            raise Refused("That post is already closed.", 404)
        if row[0][0] == s["a"]:
            self.db.execute("UPDATE posts SET status='closed' WHERE id=?", (pid,))
        elif s.get("g"):
            self.db.execute("UPDATE posts SET status='removed', reason='taken down by a GM' WHERE id=?", (pid,))
        else:
            raise Refused("That post isn't yours.", 403)

    def change(self, s, fn, *args):
        """Every write: sync, rate limit, apply, then rewrite the inbox, all under one lock."""
        with self.lock:
            self.sync()
            self.rate(s["a"])
            out = fn(s, *args)
            self.export()
            return out


def characters(s):
    """The characters a session may post as: the game's list when the sign-in carried one, else the one that signed in."""
    cs = [c for c in s.get("cs") or [] if isinstance(c, str)]
    return cs or ([s["c"]] if s.get("c") else [])


def make_handler(sessions, store):
    class Handler(BaseHTTPRequestHandler):
        server_version = "slums-market"
        sys_version = ""

        def log_message(self, fmt, *args):  # no addresses or cookies in the log
            sys.stderr.write("%s %s %s\n" % (self.command, urlsplit(self.path).path, args[1] if len(args) > 1 else ""))

        def send(self, status, body):
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def session(self):
            s = sessions.check(cookies(self.headers.get("Cookie")).get(SESSION_COOKIE))
            if s is not None and not isinstance(s.get("a"), int):
                return None
            return s

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == "/api/market/health":
                return self.send(200, {"ok": True})
            s = self.session()
            if not s:
                return self.send(401, {"ok": False, "error": "Sign in first."})
            with store.lock:
                store.sync()
                if path == "/api/market/me":
                    return self.send(200, dict(store.me(s), ok=True))
                if path == "/api/market/board":
                    return self.send(200, dict(store.board(), ok=True))
            return self.send(404, {"ok": False})

        def body(self):
            if self.headers.get("X-Slums") != "1":
                raise Refused("Missing header.", 403)
            origin = self.headers.get("Origin")
            if origin and urlsplit(origin).netloc != self.headers.get("Host", ""):
                raise Refused("Wrong origin.", 403)
            n = int(self.headers.get("Content-Length") or 0)
            if n > MAX_BODY:
                raise Refused("Too much data.", 413)
            try:
                data = json.loads(self.rfile.read(n) or b"{}")
            except (ValueError, UnicodeDecodeError):
                raise Refused("Not JSON.")
            if not isinstance(data, dict):
                raise Refused("Not a JSON object.")
            return data

        def do_POST(self):
            path = urlsplit(self.path).path
            routes = {"/api/market/watch": lambda s, b: store.change(s, store.watch, b),
                      "/api/market/alerts": lambda s, b: {"id": store.change(s, store.add_alert, b)},
                      "/api/market/alerts/rearm": lambda s, b: store.change(s, store.alert, b, "rearm"),
                      "/api/market/alerts/delete": lambda s, b: store.change(s, store.alert, b, "delete"),
                      "/api/market/alerts/seen": lambda s, b: store.change(s, lambda s_: store.seen(s_)),
                      "/api/market/posts": lambda s, b: {"id": store.change(s, store.add_post, b)},
                      "/api/market/posts/close": lambda s, b: store.change(s, store.close_post, b)}
            if path not in routes:
                return self.send(404, {"ok": False})
            s = self.session()
            if not s:
                return self.send(401, {"ok": False, "error": "Sign in first."})
            try:
                out = routes[path](s, self.body())
            except Refused as e:
                return self.send(e.status, {"ok": False, "error": str(e)})
            return self.send(200, dict(out or {}, ok=True))

    return Handler


def setting(args, name, env, default):
    v = getattr(args, name)
    return v if v is not None else os.environ.get(env, default)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for a in ("--key-file", "--revoked", "--db", "--inbox", "--outbox", "--items", "--untradeable"):
        ap.add_argument(a)
    ap.add_argument("--port", type=int)
    args = ap.parse_args()
    key_file = setting(args, "key_file", "SLUMS_LOGIN_KEY_FILE", "/etc/slums-login/key")
    try:
        with open(key_file, "rb") as f:
            key = f.read().strip()
    except OSError:
        raise SystemExit(f"no key file at {key_file} (the sign-in's key, docs/site/LOGIN.md)")
    if len(key) < 32:
        raise SystemExit(f"{key_file} is too short")
    revoked = setting(args, "revoked", "SLUMS_LOGIN_REVOKED", "/var/lib/slums-login/revoked.txt")
    db = setting(args, "db", "SLUMS_MARKET_DB", "/var/lib/slums-market/market.db")
    inbox = setting(args, "inbox", "SLUMS_MARKET_INBOX", "/var/lib/slums-market/inbox")
    outbox = setting(args, "outbox", "SLUMS_MARKET_OUTBOX", "/var/lib/slums-market/outbox")
    items = setting(args, "items", "SLUMS_MARKET_ITEMS", "/srv/slums-site/database/search-index.json")
    untradeable = setting(args, "untradeable", "SLUMS_MARKET_UNTRADEABLE", "/srv/slums-site/market/untradeable.json")
    port = int(setting(args, "port", "SLUMS_MARKET_PORT", 8095))
    store = Store(db, inbox, outbox, Items(items, untradeable))
    with store.lock:
        store.sync()
        store.export()
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(Sessions(key, revoked), store))
    print(f"market service on 127.0.0.1:{port}, inbox {inbox}, outbox {outbox}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
