"""Tests for the website login service: python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import login_service as ls  # noqa: E402

KEY = b"k" * 48


class Clock:
    def __init__(self, t=1_000_000.0):
        self.t = t

    def __call__(self):
        return self.t


class SessionsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.revoked = os.path.join(self.tmp, "revoked.txt")
        self.clock = Clock()
        self.s = ls.Sessions(KEY, self.revoked, self.clock)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_round_trip(self):
        tok = self.s.issue({"account_id": 7, "account": "momo", "character": "Momo"})
        self.assertEqual(self.s.check(tok)["c"], "Momo")

    def test_tampered_or_foreign_key_is_refused(self):
        tok = self.s.issue({"account_id": 7})
        body, mac = tok.split(".")
        forged = ls.b64(json.dumps({"a": 1, "exp": 9e12}).encode()) + "." + mac
        self.assertIsNone(self.s.check(forged))
        self.assertIsNone(ls.Sessions(b"x" * 48, None, self.clock).check(tok))
        self.assertIsNone(self.s.check("junk"))
        self.assertIsNone(self.s.check(None))

    def test_expiry_and_revocation(self):
        tok = self.s.issue({"account_id": 7})
        self.clock.t += ls.SESSION_TTL + 1
        self.assertIsNone(self.s.check(tok))
        self.clock.t -= ls.SESSION_TTL
        with open(self.revoked, "w") as f:
            f.write("3\n7\n")
        self.assertIsNone(self.s.check(tok))


class PendingTest(unittest.TestCase):
    def test_codes_and_rate_limit(self):
        clock = Clock()
        p = ls.Pending(clock)
        code, secret = p.start("1.2.3.4")
        self.assertRegex(code, r"^[A-HJ-NP-Z2-9]{6}$")
        self.assertEqual(p.code_for(secret), code)
        for _ in range(ls.START_PER_IP_MIN - 1):
            self.assertIsNotNone(p.start("1.2.3.4"))
        self.assertIsNone(p.start("1.2.3.4"))          # too many for one address
        self.assertIsNotNone(p.start("5.6.7.8"))
        clock.t += ls.CODE_TTL + 1
        self.assertIsNone(p.code_for(secret))           # ran out


class ApprovalTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir)

    def put(self, name, data):
        with open(os.path.join(self.dir, name), "w") as f:
            json.dump(data, f)

    def test_single_use_and_checked(self):
        import time
        self.put("ABCDEF.json", {"code": "ABCDEF", "account_id": 7, "character": "Momo", "time": time.time()})
        self.assertEqual(ls.take_approval(self.dir, "ABCDEF")["account_id"], 7)
        self.assertIsNone(ls.take_approval(self.dir, "ABCDEF"))        # used up
        self.put("ABCDEG.json", {"code": "WRONG1", "account_id": 7})
        self.assertIsNone(ls.take_approval(self.dir, "ABCDEG"))        # file must name its own code
        self.assertIsNone(ls.take_approval(self.dir, "../etc"))        # never a path
        self.put("ABCDEH.json", {"code": "ABCDEH", "account_id": 7, "time": 0})
        self.assertIsNone(ls.take_approval(self.dir, "ABCDEH", now=lambda: 10 ** 7))  # stale approval
        self.put("ABCDEJ.json", {"code": "ABCDEJ", "account_id": 7})
        self.assertIsNone(ls.take_approval(self.dir, "ABCDEJ"))        # unstamped approval


class EndToEndTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        handler = ls.make_handler(ls.Sessions(KEY), ls.Pending(), self.dir, secure=False)
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"
        self.jar = {}

    def tearDown(self):
        self.srv.shutdown()
        shutil.rmtree(self.dir)

    def call(self, method, path):
        req = urllib.request.Request(self.base + path, method=method, data=b"" if method == "POST" else None)
        if self.jar:
            req.add_header("Cookie", "; ".join(f"{k}={v}" for k, v in self.jar.items()))
        try:
            r = urllib.request.urlopen(req)
        except urllib.error.HTTPError as e:
            r = e
        for c in r.headers.get_all("Set-Cookie") or []:
            k, v = c.split(";")[0].split("=", 1)
            if v:
                self.jar[k] = v
            else:
                self.jar.pop(k, None)
        body = r.read()
        return r.status, (json.loads(body) if body else None)

    def test_sign_in_with_a_code_typed_in_game(self):
        self.assertEqual(self.call("GET", "/api/auth/check")[0], 401)
        status, body = self.call("POST", "/api/login/start")
        code = body["code"]
        self.assertEqual(self.call("GET", "/api/login/poll")[1], {"ok": False, "waiting": True})
        # the game server's side of `@weblogin CODE`
        with open(os.path.join(self.dir, f"{code}.json"), "w") as f:
            json.dump({"code": code, "account_id": 42, "account": "momo", "character": "Momo",
                       "time": __import__("time").time()}, f)
        status, body = self.call("GET", "/api/login/poll")
        self.assertTrue(body["ok"])
        self.assertEqual(body["character"], "Momo")
        self.assertEqual(self.call("GET", "/api/auth/check")[0], 204)
        self.assertEqual(self.call("GET", "/api/login/me")[1]["character"], "Momo")
        self.call("POST", "/api/login/logout")
        self.assertEqual(self.call("GET", "/api/auth/check")[0], 401)

    def test_unknown_paths(self):
        self.assertEqual(self.call("GET", "/etc/passwd")[0], 404)
        self.assertEqual(self.call("POST", "/api/auth/check")[0], 404)


if __name__ == "__main__":
    unittest.main()
