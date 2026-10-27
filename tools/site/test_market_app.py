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
        """Every endpoint in market_service.py's docstring, and nothing else under /api/market/."""
        doc = {tuple(line.split()[:2]) for line in ms.__doc__.splitlines()
               if line.strip().startswith(("GET  /api/", "POST /api/"))}
        app = self.client.application
        have = {(m, r.rule) for r in app.url_map.iter_rules() if r.rule.startswith("/api/")
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


if __name__ == "__main__":
    unittest.main()
