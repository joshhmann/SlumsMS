"""The Flask market service (market_app.py) against the same HTTP tests as market_service.py, through Flask's test
client: python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import flask  # noqa: F401
except ImportError:  # python3-flask isn't installed here: the old service's tests still run
    flask = None
import login_service as ls  # noqa: E402
import market_service as ms  # noqa: E402
import test_market_service as old  # noqa: E402  (a module, so its classes don't run twice)

if flask:
    import market_app  # noqa: E402


@unittest.skipUnless(flask, "python3-flask is not installed")
class FlaskHttpTest(old.HttpTest):
    """Every HttpTest test, run against the Flask app."""

    def setUp(self):
        old.Base.setUp(self)
        self.sessions = ls.Sessions(old.KEY)
        self.client = market_app.create_app(self.sessions, self.store).test_client()
        self.token = self.sessions.issue({"account_id": 7, "character": "Momo", "characters": ["Momo", "MomoAlt"]})

    def tearDown(self):
        old.Base.tearDown(self)

    def call(self, method, path, body=None, token=True, headers=None):
        h = dict(headers if headers is not None else {"X-Slums": "1"})
        # the test client sends its own cookie jar, not a Cookie header
        if token:
            self.client.set_cookie(ls.SESSION_COOKIE, self.token)
        else:
            self.client.delete_cookie(ls.SESSION_COOKIE)
        data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
        r = self.client.open(path, method=method, data=data, headers=h)
        self.assertEqual(r.headers["Cache-Control"], "no-store")
        return r.status_code, json.loads(r.get_data() or b"null")

    def test_the_same_urls_as_the_old_service(self):
        """Every endpoint in market_service.py's and exchange_desk.py's docstrings, and nothing else under /api/."""
        import exchange_desk
        doc = {tuple(line.split()[:2]) for line in (ms.__doc__ + exchange_desk.__doc__).splitlines()
               if line.strip().startswith(("GET  /api/", "POST /api/"))}
        app = self.client.application
        have = {(m, r.rule.replace("<rid>", "<id>")) for r in app.url_map.iter_rules() if r.rule.startswith("/api/")
                for m in r.methods - {"HEAD", "OPTIONS"}}
        self.assertEqual(have, doc)

    def test_unknown_urls_methods_and_big_bodies(self):
        self.assertEqual(self.call("GET", "/api/market/nope"), (404, {"ok": False}))
        self.assertEqual(self.call("GET", "/api/market/watch")[0], 404)
        self.assertEqual(self.call("POST", "/api/market/watch", {"itemId": 2000000, "pad": "x" * ms.MAX_BODY})[0], 413)
        st, body = self.call("POST", "/api/market/watch", ["not", "an", "object"])
        self.assertEqual((st, body["error"]), (400, "Not a JSON object."))

    def test_the_old_service_answers_the_same(self):
        """A few answers side by side: status and body match the stdlib service."""
        stdlib = old.HttpTest("test_a_player_session")
        stdlib.setUp()
        try:
            stdlib.token = self.token
            for args in (("GET", "/api/market/me"), ("GET", "/api/market/cards"), ("GET", "/api/market/me", None, False),
                         ("POST", "/api/market/watch", {"itemId": "x"}), ("POST", "/api/market/cards", {"ids": "x"}),
                         ("GET", "/api/market/health", None, False)):
                self.assertEqual(self.call(*args), stdlib.call(*args), args)
        finally:
            stdlib.tearDown()


@unittest.skipUnless(flask, "python3-flask is not installed")
class NxHttpTest(old.Base):
    """The NX desk's URLs (1.6.3, ticket 162) as a mock: the same routes, answered from testdata/nx-desk/."""

    def setUp(self):
        old.Base.setUp(self)
        self.sessions = ls.Sessions(old.KEY, now=self.clock)
        self.app = market_app.create_app(self.sessions, self.store, exchange=True, nx="mock")
        self.client = self.app.test_client()
        self.token = self.sessions.issue({"account_id": 7, "character": "Momo",
                                          "characters": ["Momo", "MomoAlt"]})

    def tearDown(self):
        old.Base.tearDown(self)

    def call(self, method, path, body=None, token=True, client=None):
        h = {"X-Slums": "1"}
        client = client or self.client
        if token:
            client.set_cookie(ls.SESSION_COOKIE, self.token)
        else:
            client.delete_cookie(ls.SESSION_COOKIE)
        r = client.open("/api/market/exchange/" + path, method=method,
                        data=json.dumps(body).encode() if body is not None else None, headers=h)
        self.assertEqual(r.headers["Cache-Control"], "no-store")
        return r.status_code, json.loads(r.get_data() or b"null")

    def test_with_the_desk_off_the_nx_urls_are_404(self):
        # 1.6.2 (the default): the NX tab is the order book and these URLs don't exist
        offline = market_app.create_app(self.sessions, self.store, exchange=True).test_client()
        for method, path in (("GET", "nx/quote"), ("GET", "nx/history"), ("GET", "nx/me"), ("POST", "nx/preview"),
                             ("POST", "nx/confirm")):
            self.assertEqual(self.call(method, path, {} if method == "POST" else None, client=offline),
                             (404, {"ok": False}), path)

    def test_the_quote_the_history_and_my_nx_answer_the_contracts_fields(self):
        st, q = self.call("GET", "nx/quote")
        self.assertEqual(st, 200)
        self.assertEqual({k: q[k] for k in ("open", "mid", "buy", "sell", "spread_bps", "fee_bps", "desk_nx",
                                            "selling", "min_qty", "day_cap_pct", "generated_at")},
                         {"open": True, "mid": 5_000, "buy": 4_900, "sell": 5_100, "spread_bps": 200, "fee_bps": 0,
                          "desk_nx": 250_000, "selling": True, "min_qty": 100, "day_cap_pct": 20,
                          "generated_at": ms.iso(int(self.clock.t))})
        self.assertNotIn("history", q, "the quote leaves the hours to nx/history")
        st, h = self.call("GET", "nx/history?hours=3")
        self.assertEqual((st, h["hours"], len(h["history"])), (200, 3, 3))
        self.assertEqual(self.call("GET", "nx/history?hours=0")[0], 400)
        st, me = self.call("GET", "nx/me")
        self.assertEqual((st, me["nx"], me["stepUpFreeMesos"], len(me["fills"])), (200, 250_000, 20_000_000, 3))
        self.assertEqual(me["fills"][0]["price"], 5_120)  # per NX, like the page shows it

    def test_each_state_of_the_desk_is_a_fixture(self):
        self.assertEqual(self.call("GET", "nx/quote?variant=closed")[1]["open"], False)
        self.assertEqual(self.call("GET", "nx/quote?variant=closed")[1]["closed_reason"], "no house account")
        self.assertEqual(self.call("GET", "nx/quote?variant=noselling")[1]["selling"], False)
        self.assertEqual(self.call("GET", "nx/quote?variant=halted")[1]["closed_reason"], "halted")
        st, q = self.call("GET", "nx/quote?variant=stale")
        self.assertEqual((st, q["code"]), (503, "PRICES_STALE"))
        # a variant only ever names a file in the fixtures' folder: letters and dashes, nothing that climbs out
        st, q = self.call("GET", "nx/quote?variant=../../etc/passwd")
        self.assertEqual((st, q["code"]), (503, "PRICES_STALE"))

    def test_trades_go_through_the_fixtures(self):
        st, got = self.call("POST", "nx/preview", {"character": "Momo", "side": "BUY", "nx": 1000})
        self.assertEqual(st, 200)
        st, ans = self.call("GET", "answer/" + got["id"])
        self.assertEqual((st, ans["answer"]["data"]["price"], ans["answer"]["data"]["mid"]), (200, 5_100, 5_000))
        st, got = self.call("POST", "nx/confirm", {"token": "ab" * 12})
        st, ans = self.call("GET", "answer/" + got["id"])
        self.assertEqual(ans["answer"]["data"]["fill"]["mesos"], 5_100_000)
        self.assertEqual(os.listdir(self.inbox), [], "mock mode never writes the inbox")

    def test_the_new_error_codes_come_through_with_their_data(self):
        for variant, code, data in (("moved", "QUOTE_MOVED", "price"), ("pw", "PASSWORD_REQUIRED", "stepUpFreeMesos"),
                                    ("empty", "DESK_EMPTY", "desk_nx"), ("minqty", "MIN_QTY", "min_qty"),
                                    ("closed", "DESK_CLOSED", None)):
            st, got = self.call("POST", "nx/preview?variant=" + variant,
                                {"character": "Momo", "side": "SELL", "nx": 1000})
            self.assertEqual(st, 200, variant)
            st, ans = self.call("GET", "answer/" + got["id"])
            self.assertEqual(ans["answer"]["code"], code, variant)
            if data:
                self.assertIn(data, ans["answer"]["data"], variant)

    def test_the_nx_writes_need_a_session(self):
        self.assertEqual(self.call("GET", "nx/quote", token=False)[0], 401)
        self.assertEqual(self.call("POST", "nx/preview", {"character": "Momo", "side": "BUY", "nx": 1000},
                                   token=False)[0], 401)
        self.assertEqual(self.call("POST", "nx/preview", {"character": "Momo", "side": "BUY", "nx": 1000,
                                                          "price": 5_100})[1]["error"],
                         "The NX desk trades at its quote: a trade sends no price.")


if __name__ == "__main__":
    unittest.main()
