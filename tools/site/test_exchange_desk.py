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


if __name__ == "__main__":
    unittest.main()
