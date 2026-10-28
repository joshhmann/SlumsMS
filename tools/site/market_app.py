#!/usr/bin/env python3
"""Slums Market on Flask: the same service as market_service.py, the same URLs, cookie, JSON and status codes.

    python3 tools/site/market_app.py [--db PATH] [--inbox DIR] [--outbox DIR] [--items FILE] [--port 8095]

The first step of the 1.6.1 framework move (docs/site/STACK-PROPOSAL.md): Flask 3 from Ubuntu's python3-flask, no pip.
The rules and the store (Store, Items, the limits, the game's inbox and outbox) are market_service.py's, imported as
they are; this file only replaces the HTTP layer, so rolling back means starting the old service on the same port.

Layout, so the next areas slot in the same way:
- create_app(sessions, store) builds the app: the session check, the write checks and the error shape, shared by
  every area.
- each area is a Blueprint with its own URLs: market_routes() and the NX-meso exchange's exchange_routes() (1.6.1,
  ticket 113; the rules are in exchange_desk.py).
- a route reads the signed-in session from g.session and, for a write, its body from write_body(); a Refused becomes
  {"ok": false, "error": ...} with its status.
"""
import argparse
import json
import os
import sys
from urllib.parse import urlsplit

from flask import Blueprint, Flask, g, jsonify, request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from login_service import SESSION_COOKIE, Sessions, cookies  # noqa: E402
import market_service as ms  # noqa: E402
from exchange_desk import Desk  # noqa: E402
from market_service import MAX_BODY, Refused, characters  # noqa: E402


def reply(status, body):
    r = jsonify(body)
    r.status_code = status
    r.headers["Cache-Control"] = "no-store"
    return r


def write_body():
    """A write's JSON object. Writes need the header "X-Slums: 1" (a cross-site form can't send it) and, when the
    browser sends an Origin, one that matches the Host."""
    if request.headers.get("X-Slums") != "1":
        raise Refused("Missing header.", 403)
    origin = request.headers.get("Origin")
    if origin and urlsplit(origin).netloc != request.headers.get("Host", ""):
        raise Refused("Wrong origin.", 403)
    if (request.content_length or 0) > MAX_BODY:
        raise Refused("Too much data.", 413)
    raw = request.get_data(cache=False)
    if len(raw) > MAX_BODY:
        raise Refused("Too much data.", 413)
    try:
        data = json.loads(raw or b"{}")
    except (ValueError, UnicodeDecodeError):
        raise Refused("Not JSON.")
    if not isinstance(data, dict):
        raise Refused("Not a JSON object.")
    return data


def market_routes(store):
    bp = Blueprint("market", __name__, url_prefix="/api/market")

    def read(fn):
        with store.lock:
            store.sync()
            return reply(200, dict(fn(), ok=True))

    def write(fn):
        return reply(200, dict(fn(g.session, write_body()) or {}, ok=True))

    gets = {"me": lambda: store.me(g.session), "board": store.board, "cards": lambda: store.cards(g.session)}
    for path, fn in gets.items():
        bp.add_url_rule("/" + path, "get_" + path, lambda fn=fn: read(fn), methods=["GET"])
    posts = {
        "watch": lambda s, b: store.change(s, store.watch, b),
        "alerts": lambda s, b: {"id": store.change(s, store.add_alert, b)},
        "alerts/rearm": lambda s, b: store.change(s, store.alert, b, "rearm"),
        "alerts/delete": lambda s, b: store.change(s, store.alert, b, "delete"),
        "alerts/seen": lambda s, b: store.change(s, lambda s_: store.seen(s_)),
        "posts": lambda s, b: {"id": store.change(s, store.add_post, b)},
        "posts/close": lambda s, b: store.change(s, store.close_post, b),
        "cards": lambda s, b: store.found(s, b),
    }
    for path, fn in posts.items():
        bp.add_url_rule("/" + path, "post_" + path.replace("/", "_"), lambda fn=fn: write(fn), methods=["POST"])
    return bp


def exchange_routes(store, desk):
    """The NX-meso exchange (exchange_desk.py): requests to the game through the board's folders."""
    bp = Blueprint("exchange", __name__, url_prefix="/api/market/exchange")

    def read(fn):
        with store.lock:
            desk.sync()
            return reply(200, dict(fn(), ok=True))

    def write(fn):
        body = write_body()
        with store.lock:
            desk.sync()
            store.rate(g.session["a"])
            return reply(200, dict(fn(g.session, body), ok=True))

    bp.add_url_rule("/book", "book", lambda: read(desk.book), methods=["GET"])
    bp.add_url_rule("/me", "me", lambda: read(lambda: desk.me(g.session, characters(g.session))), methods=["GET"])
    bp.add_url_rule("/history", "history", lambda: read(lambda: desk.history(request.args.get("ticker"))),
                    methods=["GET"])
    bp.add_url_rule("/answer/<rid>", "answer", lambda rid: read(lambda: desk.answer(g.session, rid)), methods=["GET"])
    posts = {
        "preview": lambda s, b: desk.preview(s, b, characters(s)),
        "confirm": desk.confirm,
        "cancel": desk.cancel,
        "refresh": lambda s, b: desk.refresh(s),
    }
    for path, fn in posts.items():
        bp.add_url_rule("/" + path, "post_" + path, lambda fn=fn: write(fn), methods=["POST"])
    return bp


# URLs anyone may call: no session needed
OPEN = {"/api/market/health"}


def create_app(sessions, store):
    app = Flask(__name__)
    app.json.sort_keys = False

    @app.before_request
    def signed_in():
        if request.path in OPEN:
            return None
        if request.url_rule is None or request.method not in request.url_rule.methods:
            return reply(404, {"ok": False})  # the old service's answer to an unknown URL or method
        s = sessions.check(cookies(request.headers.get("Cookie")).get(SESSION_COOKIE))
        if not s or not isinstance(s.get("a"), int):
            return reply(401, {"ok": False, "error": "Sign in first."})
        g.session = s
        return None

    @app.errorhandler(Refused)
    def refused(e):
        return reply(e.status, {"ok": False, "error": str(e)})

    @app.errorhandler(404)
    @app.errorhandler(405)
    def not_found(_e):
        return reply(404, {"ok": False})

    app.add_url_rule("/api/market/health", "health", lambda: reply(200, {"ok": True}), methods=["GET"])
    app.register_blueprint(market_routes(store))
    app.register_blueprint(exchange_routes(store, Desk(store)))
    return app


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for a in ("--key-file", "--revoked", "--db", "--inbox", "--outbox", "--items", "--untradeable"):
        ap.add_argument(a)
    ap.add_argument("--port", type=int)
    args = ap.parse_args()
    setting = ms.setting
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
    store = ms.Store(db, inbox, outbox, ms.Items(items, untradeable))
    with store.lock:
        store.sync()
        store.export()
    # Werkzeug's threaded server on 127.0.0.1 behind nginx, as the old service did: plenty for a friends-only site.
    # A WSGI server (python3-gunicorn) can replace it without touching the app.
    from werkzeug.serving import WSGIRequestHandler, make_server

    class Quiet(WSGIRequestHandler):
        def log_request(self, code="-", size="-"):  # no addresses or cookies in the log
            sys.stderr.write(f"{self.command} {urlsplit(self.path).path} {code}\n")

    server = make_server("127.0.0.1", port, create_app(Sessions(key, revoked), store), threaded=True,
                         request_handler=Quiet)
    print(f"market service (Flask) on 127.0.0.1:{port}, inbox {inbox}, outbox {outbox}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
