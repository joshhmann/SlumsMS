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


class DeskTest(old.Base):
    def setUp(self):
        super().setUp()
        self.desk = xd.Desk(self.store)
        self.game = Game(self)

    def step(self):
        with self.store.lock:
            self.desk.sync()

    def preview(self, **kw):
        body = dict(character="Momo", side="SELL", price=1_200_000, nx=1000) | kw
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

    def test_the_rules_are_checked_before_the_game_is_asked(self):
        for kw, msg in ((dict(character="Rival"), "your characters"), (dict(side="HOLD"), "buy or sell"),
                        (dict(nx=150), "steps of 100"), (dict(nx=50_100), "50,000"), (dict(nx="1000"), "NX"),
                        (dict(price=999), "1,000"), (dict(price=True), "price"),
                        (dict(side="BUY", nx=50_000, price=100_000_000), "can hold")):
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
        self.assertEqual(self.desk.book(), {"book": None, "rules": xd.RULES})
        ms.write_atomic(os.path.join(self.outbox, xd.BOOK), {"schema": 1, "generated_at": "x", "halted": False,
                                                              "rate": {"mesos_per_1000nx": None}, "bids": [], "asks": [],
                                                              "trades": [], "extra": "secret"})
        self.assertNotIn("extra", self.desk.book()["book"])

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
        self.sessions = ls.Sessions(old.KEY)
        self.client = market_app.create_app(self.sessions, self.store).test_client()
        self.client.set_cookie(ls.SESSION_COOKIE, self.sessions.issue(
            {"account_id": 7, "character": "Momo", "characters": ["Momo", "MomoAlt"]}))
        self.game = Game(self)

    def call(self, method, path, body=None, headers=None):
        r = self.client.open("/api/market/exchange/" + path, method=method,
                             data=json.dumps(body).encode() if body is not None else None,
                             headers=headers if headers is not None else {"X-Slums": "1"})
        return r.status_code, r.get_json()

    def test_preview_then_confirm_end_to_end(self):
        st, got = self.call("POST", "preview", {"character": "MomoAlt", "side": "BUY", "price": 1_000_000, "nx": 500})
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
        self.assertEqual(self.call("POST", "preview", {"character": "Momo", "side": "SELL", "price": 1, "nx": 100})[1]
                         ["error"], "The price must be a whole number from 1,000 to 100,000,000.")
        self.client.delete_cookie(ls.SESSION_COOKIE)
        self.assertEqual(self.call("GET", "book")[0], 401)

    def test_me_and_book(self):
        st, me = self.call("GET", "me")
        self.assertEqual((st, me["characters"], me["rules"]["maxOpen"]), (200, ["Momo", "MomoAlt"], 5))
        self.assertEqual(self.call("GET", "book")[1]["book"], None)


if __name__ == "__main__":
    unittest.main()
