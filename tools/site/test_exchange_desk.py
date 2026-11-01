"""The exchange's site side (exchange_desk.py, the market app's /api/market/exchange/ URLs), with a stand-in for the
game that answers the inbox the way ExchangeSite.java does: python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import exchange_desk as xd  # noqa: E402
import market_service as ms  # noqa: E402
import test_market_service as old  # noqa: E402

try:
    import flask  # noqa: F401
    import login_service as ls
    import market_app
except ImportError:
    flask = None


class Game:
    """Answers every new request in exchange-requests.json, like the game's poll."""

    def __init__(self, base):
        self.base, self.answers, self.seen = base, [], set()

    def requests(self):
        try:
            return self.base.inbox_file(xd.REQUESTS)["requests"]
        except FileNotFoundError:
            return []

    def poll(self, reply=None):
        for r in self.requests():
            if r["id"] in self.seen:
                continue
            self.seen.add(r["id"])
            ok, code, data = (reply or self.default)(r)
            self.answers.append({"id": r["id"], "account": r["account"], "ok": ok, "code": code, "message": "",
                                 "data": data})
        ms.write_atomic(os.path.join(self.base.outbox, xd.RESPONSES), {"schema": 1, "responses": self.answers})

    @staticmethod
    def default(r):
        if r["type"] == "preview":
            return True, "OK", {"token": "ab" * 12, "expiresAt": "2026-10-28T07:01:00Z", "side": r["side"],
                                "price": r["price"], "nx": r["nx"], "restsNx": r["nx"]}
        if r["type"] == "mine":
            return True, "OK", {"orders": [{"orderId": 77, "side": "SELL", "price": 1_200_000, "nx": 1000,
                                            "nxLeft": 1000, "status": "OPEN"}]}
        return True, "OK", {"orderId": 77}


TICKERS = {"schema": 1, "halted": False, "max_open_per_ticker": 10, "per_day_scope": "ticker",
           "events": [{"id": "rush-1", "name": "Leaf Rush", "ticker": "LEAF", "ends_at": "2026-10-29T01:00:00Z",
                       "secret_effect": "dropped"}], "tickers": [
    dict(xd.NX, bid=None, ask=None, last=None, bids=[], asks=[], trades=[]),
    {"id": "LEAF", "name": "Maple Leaf", "item_id": 4001126, "unit": "item", "unit_label": "mesos per Maple Leaf",
     "per": 1, "qty_label": "items", "min_qty": 1, "max_qty": 1000, "lot": 1, "min_price": 1,
     "max_price": 1_000_000_000, "bids": [], "asks": [], "trades": [], "secret": "dropped"}]}


class DeskTest(old.Base):
    def setUp(self):
        super().setUp()
        self.desk = xd.Desk(self.store)
        self.game = Game(self)
        self.me["iat"] = self.other["iat"] = int(self.clock.t)  # signed in just now

    def step(self):
        with self.store.lock:
            self.desk.sync()

    def preview(self, **kw):
        body = {k: v for k, v in (dict(character="Momo", side="SELL", price=1_200, nx=1000) | kw).items()
                if v is not None}
        return self.desk.preview(self.me, body, ms.characters(self.me))

    def test_a_preview_goes_to_the_inbox_and_its_answer_comes_back(self):
        rid = self.preview()["id"]
        (r,) = self.game.requests()
        self.assertEqual({k: r[k] for k in ("id", "account", "type", "characterName", "side", "price", "nx")},
                         {"id": rid, "account": 7, "type": "preview", "characterName": "Momo", "side": "SELL",
                          "price": 1_200_000, "nx": 1000})
        self.assertRegex(r["createdAt"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")
        self.assertEqual(self.desk.answer(self.me, rid), {"pending": True})
        self.game.poll()
        self.step()
        got = self.desk.answer(self.me, rid)
        self.assertEqual((got["pending"], got["answer"]["ok"], got["answer"]["data"]["token"]), (False, True, "ab" * 12))
        self.assertEqual(self.game.requests(), [], "an answered request leaves the inbox")

    def test_every_request_says_when_the_player_signed_in(self):
        self.me["iat"] -= 3600
        self.preview()
        self.desk.refresh(self.me)
        self.assertEqual([r["siteAuthAt"] for r in self.game.requests()], [(int(self.clock.t) - 3600) * 1000] * 2)

    def test_a_sign_in_over_12_hours_old_cannot_trade(self):
        self.me["iat"] = int(self.clock.t) - xd.SIGN_IN_TTL  # exactly 12 h: still fine
        self.preview()
        self.me["iat"] -= 1
        for call in (self.preview, lambda: self.desk.confirm(self.me, {"token": "ab" * 12}),
                     lambda: self.desk.cancel(self.me, {"orderId": 77}), lambda: self.desk.refresh(self.me)):
            with self.assertRaises(ms.Refused) as e:
                call()
            self.assertEqual((e.exception.status, e.exception.code), (403, "REAUTH"))
        self.assertEqual(len(self.game.requests()), 1, "nothing more reached the game")
        me = self.desk.me(self.me, ["Momo"])
        self.assertTrue(me["reauth"])
        self.assertEqual(len(self.game.requests()), 1, "a stale session doesn't ask for its orders either")
        del self.me["iat"]  # an old cookie with no sign-in time
        with self.assertRaises(ms.Refused):
            self.preview()

    def test_nx_prices_are_per_nx_on_the_site_and_per_1000_in_the_game(self):
        # owner, 2026-10-29: players see and type NX prices per 1 NX; the game keeps them per 1,000 NX
        rid = self.preview(price=5_000)["id"]
        (r,) = self.game.requests()
        self.assertEqual(r["price"], 5_000_000)
        self.game.poll()
        self.step()
        self.assertEqual(self.desk.answer(self.me, rid)["answer"]["data"]["price"], 5_000)
        rid = self.preview(price=1)["id"]
        self.game.poll(lambda req: (False, "PRICE_BAND", {"minPrice": 2_500_000, "maxPrice": 10_000_000, "rate": None}))
        self.step()
        self.assertEqual(self.desk.answer(self.me, rid)["answer"]["data"],
                         {"minPrice": 2_500, "maxPrice": 10_000, "rate": None})
        ms.write_atomic(os.path.join(self.outbox, xd.TICKERS), dict(TICKERS, tickers=[dict(TICKERS["tickers"][0],
            rate=5_001_400, bids=[{"price": 4_999_600, "qty": 100, "orders": 1}, {"price": 5_000_400, "qty": 200,
            "orders": 2}], trades=[{"t": "x", "price": 5_000_000, "qty": 100}]), TICKERS["tickers"][1]]))
        nx, leaf = self.desk.book()["tickers"]
        self.assertEqual((nx["unit"], nx["unit_label"], nx["per"], nx["rate"], nx["min_price"], nx["max_price"]),
                         ("nx", "mesos per NX", 1, 5_001, 1, 100_000))
        self.assertEqual(nx["bids"], [{"price": 5_000, "qty": 300, "orders": 3}], "levels that round together merge")
        self.assertEqual(nx["trades"][0]["price"], 5_000)
        self.assertEqual((leaf["unit"], leaf["max_price"]), ("item", 1_000_000_000), "items stay per item")
        ms.write_atomic(os.path.join(self.outbox, xd.HISTORY), {"interval": "1h", "tickers": {
            "NX": {"candles": [{"t": "x", "o": 5_000_000, "h": 5_200_000, "l": 4_900_000, "c": 5_100_000, "v": 300,
                               "m": 1_530_000_000}]},
            "LEAF": {"candles": [{"t": "x", "o": 500_000, "h": 500_000, "l": 500_000, "c": 500_000, "v": 1, "m": 1}]}}})
        (c,) = self.desk.history("NX")["candles"]
        self.assertEqual((c["o"], c["h"], c["l"], c["c"], c["v"], c["m"]), (5_000, 5_200, 4_900, 5_100, 300, 1_530_000_000))
        self.assertEqual(self.desk.history("LEAF")["candles"][0]["c"], 500_000)

    def test_the_rules_are_checked_before_the_game_is_asked(self):
        for kw, msg in ((dict(character="Rival"), "your characters"), (dict(side="HOLD"), "buy or sell"),
                        (dict(nx=150), "steps of 100"), (dict(nx=50_100), "50,000"), (dict(nx="1000"), "NX"),
                        (dict(price=0), "from 1 to 100,000"), (dict(price=100_001), "100,000"),
                        (dict(price=True), "price"), (dict(side="BUY", nx=50_000, price=100_000), "can hold")):
            with self.assertRaises(ms.Refused, msg=kw) as e:
                self.preview(**kw)
            self.assertIn(msg, str(e.exception))
        self.assertEqual(self.game.requests(), [])
        with self.assertRaises(ms.Refused):
            self.desk.confirm(self.me, {"token": "not hex!"})
        with self.assertRaises(ms.Refused):
            self.desk.cancel(self.me, {"orderId": "77"})

    def test_another_accounts_answer_or_request_is_not_mine(self):
        rid = self.preview()["id"]
        self.game.poll(lambda r: (True, "OK", {}))
        self.game.answers[0]["account"] = 8  # a mismatched answer is ignored
        ms.write_atomic(os.path.join(self.outbox, xd.RESPONSES), {"responses": self.game.answers})
        self.step()
        self.assertEqual(self.desk.answer(self.me, rid), {"pending": True})
        with self.assertRaises(ms.Refused) as e:
            self.desk.answer(self.other, rid)
        self.assertEqual(e.exception.status, 404)

    def test_my_orders_come_from_a_mine_request_and_refresh_after_a_cancel(self):
        me = self.desk.me(self.me, ["Momo"])
        self.assertIsNone(me["orders"])
        self.assertEqual([w["type"] for w in me["waiting"]], ["mine"])
        self.desk.me(self.me, ["Momo"])
        self.assertEqual(len(self.game.requests()), 1, "one mine request at a time")
        self.game.poll()
        self.step()
        me = self.desk.me(self.me, ["Momo"])
        self.assertEqual((me["orders"][0]["orderId"], me["waiting"]), (77, []))
        self.desk.cancel(self.me, {"orderId": 77})
        self.game.poll()
        self.step()
        self.assertEqual([w["type"] for w in self.desk.me(self.me, ["Momo"])["waiting"]], ["mine"],
                         "a cancel's answer makes the orders stale")
        self.game.poll()
        self.step()
        self.clock.t += xd.MINE_FRESH + 1
        self.assertEqual(len(self.desk.me(self.me, ["Momo"])["waiting"]), 1, "an old answer is asked for again")

    def test_unanswered_requests_leave_after_ten_minutes(self):
        rid = self.preview()["id"]
        self.clock.t += xd.MAX_AGE + 1
        self.step()
        self.assertEqual(self.desk.answer(self.me, rid)["answer"]["code"], "NO_ANSWER")
        self.assertEqual(self.game.requests(), [])

    def test_at_most_five_waiting_requests(self):
        for _ in range(xd.MAX_WAITING):
            self.preview()
        with self.assertRaises(ms.Refused) as e:
            self.preview()
        self.assertEqual(e.exception.status, 429)

    def test_the_book_is_the_games_file_without_extras(self):
        self.assertEqual(self.desk.book()["tickers"], [])
        ms.write_atomic(os.path.join(self.outbox, xd.BOOK), {"schema": 1, "generated_at": "x", "halted": True,
                                                              "rate": {"mesos_per_1000nx": 1_180_000},
                                                              "bids": [{"price": 1_150_000, "nx": 300, "orders": 1}],
                                                              "asks": [], "trades": [{"t": "x", "price": 9_000, "nx": 100}]})
        b = self.desk.book()
        (nx,) = b["tickers"]
        self.assertEqual((b["halted"], nx["id"], nx["unit"], nx["rate"], nx["bid"], nx["ask"], nx["last"]),
                         (True, "NX", "nx", 1_180, 1_150, None, 9), "a game without tickers: NX alone, per NX")
        self.assertEqual(nx["bids"], [{"price": 1_150, "qty": 300, "orders": 1}])
        ms.write_atomic(os.path.join(self.outbox, xd.TICKERS), TICKERS)
        b = self.desk.book()
        self.assertEqual([t["id"] for t in b["tickers"]], ["NX", "LEAF"])
        self.assertNotIn("secret", b["tickers"][1])
        self.assertEqual(b["limits"], dict(xd.DEFAULT_LIMITS, max_open_per_ticker=10, per_day_scope="ticker"),
                         "the game's limits win, the rest stay the defaults")
        self.assertEqual(b["events"], [{"id": "rush-1", "name": "Leaf Rush", "ticker": "LEAF",
                                        "ends_at": "2026-10-29T01:00:00Z"}], "announced events, the four fields only")

    def test_item_tickers_use_their_own_rules(self):
        ms.write_atomic(os.path.join(self.outbox, xd.TICKERS), TICKERS)
        rid = self.preview(ticker="leaf", qty=10, nx=None, price=600_000)["id"]
        r = self.game.requests()[0]
        self.assertEqual((r["id"], r["ticker"], r["qty"], r["price"], "nx" in r), (rid, "LEAF", 10, 600_000, False))
        for kw, msg in ((dict(ticker="LEAF", qty=1001), "1 to 1,000"), (dict(ticker="LEAF", qty=5, price=0), "price"),
                        (dict(ticker="GOLD"), "ticker"), (dict(ticker="LEAF", side="BUY", qty=1000, price=1_000_000_000),
                                                           "can hold")):
            with self.assertRaises(ms.Refused, msg=kw) as e:
                self.preview(**kw)
            self.assertIn(msg, str(e.exception))
        self.preview(qty=200)  # NX still sends nx, for a game without tickers
        self.assertEqual((self.game.requests()[1]["nx"], "ticker" in self.game.requests()[1]), (200, False))

    def test_history_is_one_tickers_candles(self):
        self.assertEqual(self.desk.history("NX")["candles"], [])
        ms.write_atomic(os.path.join(self.outbox, xd.TICKERS), TICKERS)
        ms.write_atomic(os.path.join(self.outbox, xd.HISTORY), {"interval": "1h", "tickers": {
            "LEAF": {"candles": [{"t": "2026-10-28T17:00:00Z", "o": 1, "h": 2, "l": 1, "c": 2, "v": 3, "m": 5, "n": 2}]}}})
        self.assertEqual(self.desk.history("leaf")["candles"][0]["c"], 2)
        with self.assertRaises(ms.Refused):
            self.desk.history("GOLD")

    def test_a_broken_responses_file_is_read_again_later(self):
        rid = self.preview()["id"]
        with open(os.path.join(self.outbox, xd.RESPONSES), "w") as f:
            f.write('{"responses": [')
        self.step()
        self.game.poll()
        self.step()
        self.assertFalse(self.desk.answer(self.me, rid)["pending"])


@unittest.skipUnless(flask, "python3-flask is not installed")
class ExchangeHttpTest(old.Base):
    def setUp(self):
        super().setUp()
        self.sessions = ls.Sessions(old.KEY, now=self.clock)
        self.client = market_app.create_app(self.sessions, self.store, exchange=True).test_client()
        self.client.set_cookie(ls.SESSION_COOKIE, self.sessions.issue(
            {"account_id": 7, "character": "Momo", "characters": ["Momo", "MomoAlt"]}))
        self.game = Game(self)

    def call(self, method, path, body=None, headers=None):
        r = self.client.open("/api/market/exchange/" + path, method=method,
                             data=json.dumps(body).encode() if body is not None else None,
                             headers=headers if headers is not None else {"X-Slums": "1"})
        return r.status_code, r.get_json()

    def test_preview_then_confirm_end_to_end(self):
        st, got = self.call("POST", "preview", {"character": "MomoAlt", "side": "BUY", "price": 1_000, "nx": 500})
        self.assertEqual(st, 200)
        self.assertEqual(self.call("GET", "answer/" + got["id"]), (200, {"pending": True, "ok": True}))
        self.game.poll()
        st, ans = self.call("GET", "answer/" + got["id"])
        token = ans["answer"]["data"]["token"]
        st, got = self.call("POST", "confirm", {"token": token})
        self.game.poll()
        self.assertEqual(self.call("GET", "answer/" + got["id"])[1]["answer"]["data"], {"orderId": 77})
        self.assertEqual(len(self.game.answers), 2)

    def test_writes_need_the_header_and_a_session(self):
        self.assertEqual(self.call("POST", "refresh", {}, headers={})[0], 403)
        self.assertEqual(self.call("POST", "preview", {"character": "Momo", "side": "SELL", "price": 0, "nx": 100})[1]
                         ["error"], "The price must be a whole number from 1 to 100,000.")
        self.client.delete_cookie(ls.SESSION_COOKIE)
        self.assertEqual(self.call("GET", "book")[0], 401)

    def test_an_old_sign_in_gets_reauth(self):
        self.clock.t += xd.SIGN_IN_TTL + 1
        st, got = self.call("POST", "preview", {"character": "Momo", "side": "SELL", "price": 1_000, "nx": 500})
        self.assertEqual((st, got["code"], got["ok"]), (403, "REAUTH", False))
        self.assertIn("Sign in again", got["error"])
        self.assertTrue(self.call("GET", "me")[1]["reauth"])
        self.assertEqual(self.game.requests(), [])

    def test_switched_off_it_shows_nothing_and_asks_the_game_nothing(self):
        client = market_app.create_app(self.sessions, self.store).test_client()  # off unless asked: live (b063)
        client.set_cookie(ls.SESSION_COOKIE, self.sessions.issue({"account_id": 7, "character": "Momo"}))
        ms.write_atomic(os.path.join(self.outbox, xd.TICKERS), TICKERS)  # even with the game's files there
        book = client.get("/api/market/exchange/book").get_json()
        self.assertEqual((book["open"], book["tickers"]), (False, []))
        self.assertEqual(client.get("/api/market/exchange/me").get_json(), {"ok": True, "open": False})
        for path in ("preview", "confirm", "cancel", "refresh"):
            r = client.post("/api/market/exchange/" + path, json={}, headers={"X-Slums": "1"})
            self.assertEqual((r.status_code, r.get_json()["code"]), (503, "CLOSED"), path)
        self.assertEqual(client.get("/api/market/exchange/history?ticker=NX").status_code, 503)
        self.assertEqual(self.game.requests(), [])
        client.delete_cookie(ls.SESSION_COOKIE)
        self.assertEqual(client.get("/api/market/exchange/book").status_code, 401, "the session check still runs")

    def test_me_and_book(self):
        st, me = self.call("GET", "me")
        self.assertEqual((st, me["characters"], me["rules"]["maxOpen"]), (200, ["Momo", "MomoAlt"], 5))
        self.assertFalse(me["reauth"])
        self.assertEqual(me["tradeUntil"], ms.iso(int(self.clock.t) + xd.SIGN_IN_TTL))
        self.assertEqual(self.call("GET", "book")[1]["tickers"], [])
        self.assertEqual(self.call("GET", "history?ticker=NX")[1]["candles"], [])

    # ── the NX desk (1.6.3, ticket 162): /api/market/exchange/nx/* ──
    def nx_client(self, nx="on"):
        self.outbox_file(xd.NX_DESK, desk_json(self.clock.t))  # the game's quote, whether the site reads it or not
        client = market_app.create_app(self.sessions, self.store, exchange=True, nx=nx).test_client()
        client.set_cookie(ls.SESSION_COOKIE, self.sessions.issue(
            {"account_id": 7, "character": "Momo", "characters": ["Momo", "MomoAlt"]}))
        return client

    def nx_call(self, client, method, path, body=None):
        r = client.open("/api/market/exchange/nx/" + path, method=method,
                        data=json.dumps(body).encode() if body is not None else None,
                        headers={"X-Slums": "1"})
        return r.status_code, r.get_json()

    def test_the_nx_routes_answer_the_contracts_shape(self):
        client = self.nx_client()
        st, q = self.nx_call(client, "GET", "quote")
        self.assertEqual(st, 200)
        self.assertEqual({k: q[k] for k in ("mid", "buy", "sell", "spread_bps", "fee_bps", "desk_nx", "selling",
                                            "min_qty", "open", "day_cap_pct")},
                         {"mid": 5_000, "buy": 4_900, "sell": 5_100, "spread_bps": 200, "fee_bps": 0,
                          "desk_nx": 250_000, "selling": True, "min_qty": 100, "open": True, "day_cap_pct": 20})
        self.assertNotIn("history", q, "the quote leaves the hours to /nx/history")
        st, h = self.nx_call(client, "GET", "history?hours=2")
        self.assertEqual((st, h["hours"], len(h["history"])), (200, 2, 2))
        self.assertEqual(h["history"][-1]["mid"], 5_167)
        st, me = self.nx_call(client, "GET", "me")
        self.assertEqual((st, me["characters"]), (200, ["Momo", "MomoAlt"]))
        st, got = self.nx_call(client, "POST", "preview", {"character": "Momo", "side": "BUY", "nx": 1000})
        self.assertEqual(st, 200)
        self.game.poll(reply=nx_reply(self))
        st, ans = self.call("GET", "answer/" + got["id"])
        self.assertEqual(ans["answer"]["data"]["price"], 5_100)
        st, got = self.nx_call(client, "POST", "confirm", {"token": ans["answer"]["data"]["token"]})
        self.game.poll(reply=nx_reply(self))
        st, ans = self.call("GET", "answer/" + got["id"])
        self.assertEqual(ans["answer"]["data"], {"fill": {"nx": 1000, "price": 5_100, "mesos": 5_100_000,
                                                         "at": ms.iso(self.clock.t)}, "mid": 5_000})

    def test_the_nx_routes_are_404_with_the_desk_off(self):
        # 1.6.2: the NX tab is the book and the desk's URLs don't exist
        offline = self.nx_client("off")
        for method, path in (("GET", "quote"), ("GET", "history"), ("GET", "me"), ("POST", "preview"),
                             ("POST", "confirm")):
            self.assertEqual(self.nx_call(offline, method, path, {} if method == "POST" else None), (404, {"ok": False}))
        self.assertEqual(len(self.game.requests()), 0)


DESK_HOURS = 168


def desk_json(now, **kw):
    """A valid nx-desk.json: mid 5,000 mesos per NX, buy and sell 2% either side (the owner's balanced preset)."""
    mid = kw.pop("mid", 5_000_000)
    buy, sell = kw.pop("buy", mid * 98 // 100), kw.pop("sell", mid * 102 // 100)
    d = {"schema": 1, "generated_at": ms.iso(int(now)), "open": True, "closed_reason": None, "mid": mid, "buy": buy,
         "sell": sell, "spread_bps": 200, "fee_bps": 0, "drift_bps_last_hour": 12, "day_cap_pct": 20,
         "moved_today_pct": 3.2, "floor": 2_500_000, "ceil": 10_000_000, "desk_nx": 250_000, "selling": True,
         "min_qty": 100,
         "history": [{"hour": ms.iso(int(now) - 3600 * (DESK_HOURS - i)), "mid": mid + 1000 * i, "buy": buy + i,
                      "sell": sell + 1000 * i, "volume_nx": 1000, "fills": 2} for i in range(DESK_HOURS)]}
    d.update(kw)
    return d


def nx_reply(base, price=5_100_000):
    """The game's answers to an NX request: a held quote for a preview, a fill for a confirm (per 1,000 NX)."""
    def reply(r):
        if r["type"] == "preview":
            return True, "OK", {"token": "ab" * 12, "expiresAt": ms.iso(int(base.clock.t) + 60), "side": r["side"],
                                "nx": r["nx"], "price": price, "mid": 5_000_000, "mesos": r["nx"] * price // 1000,
                                "desk_nx": 250_000}
        return True, "OK", {"fill": {"nx": 1000, "price": price, "mesos": 5_100_000, "at": ms.iso(int(base.clock.t))},
                            "mid": 5_000_000}
    return reply


class NxDeskTest(old.Base):
    """The desk on (SLUMS_MARKET_NX_DESK=on): the game's nx-desk.json, and trades through the inbox."""

    def setUp(self):
        super().setUp()
        self.desk = xd.Desk(self.store, "on")
        self.game = Game(self)
        self.me["iat"] = int(self.clock.t)

    def file(self, **kw):
        d = desk_json(self.clock.t, **kw)
        self.outbox_file(xd.NX_DESK, d)
        return d

    def step(self):
        with self.store.lock:
            self.desk.sync()

    def trade(self, **kw):
        body = {"character": "Momo", "side": "BUY", "nx": 1000} | kw
        return self.desk.nx_preview(self.me, {k: v for k, v in body.items() if v is not None}, ["Momo", "MomoAlt"])

    def test_the_quote_is_per_nx_and_leaves_the_hours_to_the_history(self):
        self.file()
        q = self.desk.nx_quote()
        self.assertEqual({k: q[k] for k in ("open", "closed_reason", "mid", "buy", "sell", "spread_bps", "fee_bps",
                                            "day_cap_pct", "moved_today_pct", "drift_bps_last_hour", "floor", "ceil",
                                            "desk_nx", "selling", "min_qty", "age_sec", "schema")},
                         {"open": True, "closed_reason": None, "mid": 5_000, "buy": 4_900, "sell": 5_100,
                          "spread_bps": 200, "fee_bps": 0, "day_cap_pct": 20, "moved_today_pct": 3.2,
                          "drift_bps_last_hour": 12, "floor": 2_500, "ceil": 10_000, "desk_nx": 250_000,
                          "selling": True, "min_qty": 100, "age_sec": 0, "schema": 1})
        self.assertNotIn("history", q)
        self.assertEqual(q["generated_at"], ms.iso(int(self.clock.t)))

    def test_a_missing_broken_or_stale_desk_file_says_prices_are_updating(self):
        for why in ("missing", "broken"):
            if why == "broken":
                with open(os.path.join(self.outbox, xd.NX_DESK), "w") as f:
                    f.write('{"schema": 1, "mid": 5000000,')
            with self.assertRaises(ms.Refused) as e:
                self.desk.nx_quote()
            self.assertEqual((e.exception.status, e.exception.code), (503, "PRICES_STALE"), why)
            self.assertIn("prices are updating", str(e.exception))
        self.file(generated_at=ms.iso(int(self.clock.t) - xd.DESK_STALE - 1))  # 2 min 1 s: stale
        with self.assertRaises(ms.Refused):
            self.desk.nx_quote()
        self.file(generated_at=ms.iso(int(self.clock.t) - xd.DESK_STALE + 1))  # 1 min 59 s: fine
        self.assertEqual(self.desk.nx_quote()["age_sec"], xd.DESK_STALE - 1)

    def test_a_desk_file_with_a_bad_field_is_refused_not_guessed(self):
        good = desk_json(self.clock.t)
        self.assertEqual(xd.desk_problems(good), [])
        bad = {
            "a number as text": dict(good, mid="5000000"),
            "a missing field": {k: v for k, v in good.items() if k != "desk_nx"},
            "a time that isn't one": dict(good, generated_at="yesterday"),
            "a bool where a number goes": dict(good, desk_nx=True),
            "sell below mid": dict(good, sell=4_000_000),
            "buy above mid": dict(good, buy=6_000_000),
            "a zero quote": dict(good, mid=0, buy=0, sell=0),
            "open as text": dict(good, open="yes"),
            "a broken hour": dict(good, history=[{"hour": "soon", "mid": 1, "buy": 1, "sell": 1, "volume_nx": 1,
                                                  "fills": 1}]),
            "a fractional amount": dict(good, min_qty=1.5),
            "the wrong schema": dict(good, schema=2),
        }
        for why, d in bad.items():
            self.assertTrue(xd.desk_problems(d), why)
            self.outbox_file(xd.NX_DESK, d)
            with self.assertRaises(ms.Refused) as e:
                self.desk.nx_quote()
            self.assertEqual(e.exception.code, "PRICES_STALE", why)

    def test_the_history_is_the_last_hours_oldest_first(self):
        self.file()
        h = self.desk.nx_history("24")
        self.assertEqual((h["hours"], len(h["history"])), (24, 24))
        self.assertEqual(h["interval"], "1h")
        self.assertEqual(h["history"][0]["hour"], ms.iso(int(self.clock.t) - 24 * 3600))
        self.assertEqual(h["history"][-1]["hour"], ms.iso(int(self.clock.t) - 3600))
        self.assertEqual(h["history"][0]["mid"], 5_144)       # per NX, oldest first
        self.assertEqual(h["history"][-1]["mid"], 5_167)
        self.assertEqual(len(self.desk.nx_history(None)["history"]), xd.DESK_HOURS)
        self.assertEqual(len(self.desk.nx_history("")["history"]), xd.DESK_HOURS)
        for raw in ("0", "169", "-1", "abc", "1.5"):
            with self.assertRaises(ms.Refused, msg=raw) as e:
                self.desk.nx_history(raw)
            self.assertIn("hours", str(e.exception).lower())

    def test_an_nx_preview_sends_no_price_and_its_quote_comes_back_per_nx(self):
        self.file()
        rid = self.trade()["id"]
        (r,) = self.game.requests()
        self.assertNotIn("price", r, "the desk trades at its quote")
        self.assertNotIn("ticker", r)
        self.assertEqual({k: r[k] for k in ("account", "type", "characterName", "side", "nx")},
                         {"account": 7, "type": "preview", "characterName": "Momo", "side": "BUY", "nx": 1000})
        self.assertEqual(self.game.answers, [])
        self.game.poll(reply=nx_reply(self))
        self.step()
        data = self.desk.answer(self.me, rid)["answer"]["data"]
        self.assertEqual({k: data[k] for k in ("side", "nx", "price", "mid", "mesos", "desk_nx")},
                         {"side": "BUY", "nx": 1000, "price": 5_100, "mid": 5_000, "mesos": 5_100_000,
                          "desk_nx": 250_000})
        self.assertEqual(data["expiresAt"], ms.iso(int(self.clock.t) + 60))

    def test_an_nx_preview_with_a_price_or_a_bad_amount_is_refused(self):
        self.file()
        for kw, msg in ((dict(price=5_100), "no price"), (dict(nx=0), "amount"), (dict(nx="1000"), "amount"),
                        (dict(nx=1000, character="Rival"), "characters"), (dict(side="HOLD"), "buy or sell"),
                        (dict(nx=1000.5), "amount")):
            with self.assertRaises(ms.Refused, msg=str(kw)) as e:
                self.trade(**kw)
            self.assertIn(msg, str(e.exception))
        self.assertEqual(self.game.requests(), [], "nothing reached the game")

    def test_a_confirm_answers_a_fill_per_nx(self):
        self.file()
        rid = self.trade()["id"]
        self.game.poll(reply=nx_reply(self))
        self.step()
        token = self.desk.answer(self.me, rid)["answer"]["data"]["token"]
        rid = self.desk.confirm(self.me, {"token": token})["id"]
        self.game.poll(reply=nx_reply(self))
        self.step()
        self.assertEqual(self.desk.answer(self.me, rid)["answer"]["data"],
                         {"fill": {"nx": 1000, "price": 5_100, "mesos": 5_100_000, "at": ms.iso(int(self.clock.t))},
                          "mid": 5_000})

    def test_my_nx_comes_from_the_games_mine_answer_per_nx(self):
        self.file()
        def reply(r):
            return True, "OK", {"nx": 250_000, "stepUpFreeMesos": 20_000_000, "fills": [
                {"side": "SELL", "nx": 2000, "price": 4_900_000, "mesos": 9_800_000, "at": ms.iso(int(self.clock.t))}]}
        self.desk.nx_me(self.me)  # asks the game for "mine"
        self.assertEqual([r["type"] for r in self.game.requests()], ["mine"])
        self.game.poll(reply=reply)
        self.step()
        me = self.desk.nx_me(self.me)
        self.assertEqual((me["nx"], me["stepUpFreeMesos"], me["reauth"]), (250_000, 20_000_000, False))
        self.assertEqual(me["fills"], [{"side": "SELL", "nx": 2000, "price": 4_900, "mesos": 9_800_000,
                                        "at": ms.iso(int(self.clock.t))}])
        self.assertEqual(me["characters"], ["Momo", "MomoAlt"])
        self.assertEqual(len(self.game.answers), 1, "a fresh mine answer isn't asked for again")


class NxMockTest(old.Base):
    """The desk as a mock (SLUMS_MARKET_NX_DESK=mock): the fixtures in testdata/nx-desk/, never the inbox."""

    def setUp(self):
        super().setUp()
        self.desk = xd.Desk(self.store, "mock")
        self.me["iat"] = int(self.clock.t)

    def trade(self, **kw):
        return self.desk.nx_preview(self.me, dict({"character": "Momo", "side": "BUY", "nx": 1000}, **kw), ["Momo"])

    def answer(self, rid):
        return self.desk.answer(self.me, rid)["answer"]

    def test_the_mock_offers_the_same_quote_as_the_file_would(self):
        q = self.desk.nx_quote()
        self.assertEqual((q["open"], q["selling"], q["mid"], q["buy"], q["sell"], q["spread_bps"], q["fee_bps"]),
                         (True, True, 5_000, 4_900, 5_100, 200, 0))
        self.assertEqual((q["desk_nx"], q["min_qty"], q["day_cap_pct"]), (250_000, 100, 20))
        self.assertEqual(q["age_sec"], 0)
        self.assertEqual(len(self.desk.nx_history("168")["history"]), xd.DESK_HOURS)

    def test_the_mock_states_show_the_desk_closed_buying_or_updating(self):
        self.desk.mock.variant = "closed"
        self.assertEqual((self.desk.nx_quote()["open"], self.desk.nx_quote()["closed_reason"]),
                         (False, "no house account"))
        self.desk.mock.variant = "noselling"
        q = self.desk.nx_quote()
        self.assertEqual((q["open"], q["selling"], q["desk_nx"]), (True, False, 0))
        self.desk.mock.variant = "halted"
        self.assertEqual(self.desk.nx_quote()["closed_reason"], "halted")
        self.desk.mock.variant = "stale"
        with self.assertRaises(ms.Refused) as e:
            self.desk.nx_quote()
        self.assertEqual((e.exception.status, e.exception.code), (503, "PRICES_STALE"))

    def test_the_mock_trades_answer_out_of_the_fixtures_and_never_touch_the_inbox(self):
        got = self.answer(self.trade()["id"])
        self.assertEqual((got["ok"], {k: got["data"][k] for k in ("price", "mid", "mesos")}),
                         (True, {"price": 5_100, "mid": 5_000, "mesos": 5_100_000}))
        self.desk.mock.variant = "moved"
        got = self.answer(self.trade()["id"])
        self.assertEqual((got["ok"], got["code"], got["data"]["price"]), (False, "QUOTE_MOVED", 5_150))
        self.desk.mock.variant = "pw"
        got = self.answer(self.trade(side="SELL")["id"])
        self.assertEqual((got["code"], got["data"]["stepUpFreeMesos"]), ("PASSWORD_REQUIRED", 20_000_000))
        self.desk.mock.variant = "empty"
        got = self.answer(self.trade()["id"])
        self.assertEqual((got["code"], got["data"]["desk_nx"]), ("DESK_EMPTY", 300))
        self.desk.mock.variant = "minqty"
        got = self.answer(self.trade()["id"])
        self.assertEqual((got["code"], got["data"]["min_qty"]), ("MIN_QTY", 1000))
        self.desk.mock.variant = "closed"
        got = self.answer(self.trade()["id"])
        self.assertEqual((got["code"], got["ok"]), ("DESK_CLOSED", False))
        self.desk.mock.variant = "halted"
        self.assertEqual(self.answer(self.trade()["id"])["code"], "HALTED")
        self.desk.mock.variant = ""
        got = self.answer(self.desk.confirm(self.me, {"token": "ab" * 12})["id"])
        self.assertEqual(got["data"]["fill"], {"nx": 1000, "price": 5_100, "mesos": 5_100_000,
                                              "at": "2027-01-15T08:00:00Z"})
        self.assertEqual(os.listdir(self.inbox), [], "mock mode writes nothing to the inbox")

    def test_my_nx_in_the_mock_comes_from_the_mine_fixture(self):
        me = self.desk.nx_me(self.me)
        self.assertEqual((me["nx"], me["stepUpFreeMesos"]), (250_000, 20_000_000))
        self.assertEqual([(f["side"], f["nx"], f["price"]) for f in me["fills"]],
                         [("BUY", 1000, 5_120), ("SELL", 2000, 4_900), ("BUY", 500, 5_040)])
        self.assertEqual(os.listdir(self.inbox), [])


if __name__ == "__main__":
    unittest.main()
