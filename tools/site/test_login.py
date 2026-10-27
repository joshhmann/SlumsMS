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


class FakeGameAuth:
    """The game server's auth API (ticket 105) as the login service sees it: POST /verify and /signup."""

    def __init__(self, secret):
        from http.server import BaseHTTPRequestHandler
        self.calls, fake = [], self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fake.calls.append((self.path, self.headers.get("X-Slums-Auth"), body))
                if self.headers.get("X-Slums-Auth") != secret:
                    ans = (403, {"ok": False, "error": "FORBIDDEN"})
                elif self.path == "/verify":
                    ans = ((200, {"ok": True, "account_id": 42, "account": "momo", "gm": 0, "characters": ["Momo", "Momo2"]})
                           if (body["user"], body["pass"]) == ("momo", "hunter22") else
                           (200, {"ok": False, "error": "LOCKED"}) if body["user"] == "locked" else
                           (200, {"ok": False, "error": "WRONG_LOGIN"}))
                elif self.path == "/signup":
                    ans = ((200, {"ok": False, "error": "BAD_INPUT", "rule": "bot_name"}) if body["user"] == "Wolf4life" else
                           (200, {"ok": True, "account_id": 43, "account": body["user"], "gm": 0, "characters": []})
                           if body["code"] == "GOODCODE" else
                           (200, {"ok": False, "error": "BAD_CODE"}))
                else:
                    ans = (404, {"ok": False})
                data = json.dumps(ans[1]).encode()
                self.send_response(ans[0])
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"


class AccountSignInTest(unittest.TestCase):
    """Ticket 105: sign in with the game username and password, or sign up with an invite code."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        for name, data in (("secret", b"s3cret-for-tests"), ("salt", b"x" * 32)):
            with open(os.path.join(self.dir, name), "wb") as f:
                f.write(data)
        self.game = FakeGameAuth("s3cret-for-tests")
        self.auth = ls.GameAuth(self.game.url, os.path.join(self.dir, "secret"), os.path.join(self.dir, "salt"))
        self.revoked = os.path.join(self.dir, "revoked.txt")
        self.sessions = ls.Sessions(KEY, self.revoked)
        handler = ls.make_handler(self.sessions, ls.Pending(), self.dir, secure=False, auth=self.auth,
                                  limiter=ls.Limiter(ls.AUTH_PER_IP_MIN))
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"
        self.jar = {}

    def tearDown(self):
        self.srv.shutdown()
        self.game.srv.shutdown()
        shutil.rmtree(self.dir)

    def call(self, method, path, body=None, headers=None):
        hdrs = {"X-Slums": "1", "Content-Type": "application/json"} if headers is None else headers
        req = urllib.request.Request(self.base + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None))
        for k, v in hdrs.items():
            req.add_header(k, v)
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
        data = r.read()
        return r.status, (json.loads(data) if data else None)

    def test_password_sign_in_gives_the_same_session(self):
        status, body = self.call("POST", "/api/login/password", {"user": "momo", "pass": "hunter22"})
        self.assertEqual((status, body), (200, {"ok": True, "account": "momo", "character": "Momo"}))
        self.assertEqual(self.call("GET", "/api/auth/check")[0], 204)
        me = self.call("GET", "/api/login/me")[1]
        self.assertEqual((me["character"], me["account"]), ("Momo", "momo"))
        path, secret, sent = self.game.calls[-1]
        self.assertEqual((path, secret), ("/verify", "s3cret-for-tests"))
        self.assertEqual(set(sent), {"user", "pass", "ipHash"})
        self.assertNotIn("127.0.0.1", json.dumps(sent))          # only the salted hash of the address
        self.assertRegex(sent["ipHash"], r"^[0-9a-f]{32}$")

    def test_wrong_password_and_locked_are_generic(self):
        self.assertEqual(self.call("POST", "/api/login/password", {"user": "momo", "pass": "wrong12"}),
                         (401, {"ok": False, "error": "Wrong username or password."}))
        self.assertEqual(self.call("POST", "/api/login/password", {"user": "locked", "pass": "hunter22"})[0], 423)
        self.assertEqual(self.call("GET", "/api/auth/check")[0], 401)

    def test_sign_up_with_an_invite_code(self):
        status, body = self.call("POST", "/api/login/signup", {"code": "good-code", "user": "NewPal", "pass": "abc123!"})
        self.assertEqual((status, body), (200, {"ok": True, "account": "NewPal", "character": None}))
        self.assertEqual(self.game.calls[-1][2]["code"], "GOODCODE")   # dashes, spaces and case don't matter
        self.assertEqual(self.call("GET", "/api/login/me")[1]["account"], "NewPal")
        self.assertEqual(self.call("POST", "/api/login/signup", {"code": "USEDCODE", "user": "Pal2", "pass": "abc123"}),
                         (403, {"ok": False, "error": "That invite code doesn't work."}))
        self.assertEqual(self.call("POST", "/api/login/signup", {"code": "GOODCODE", "user": "Wolf4life", "pass": "abc123"}),
                         (400, {"ok": False, "error": "That username is taken.", "rule": "bot_name"}))

    def test_rules_are_checked_before_anything_is_sent(self):
        for user, pw, rule in (("abc", "abc123", "user_length"), ("bad name", "abc123", "user_chars"),
                               ("goodname", "short", "pass_length"), ("goodname", "a" * 13, "pass_length"),
                               ("goodname", "has space", "pass_chars")):
            status, body = self.call("POST", "/api/login/password", {"user": user, "pass": pw})
            self.assertEqual((status, body["rule"]), (400, rule), user)
        self.assertEqual(self.game.calls, [])

    def test_only_this_site_or_the_launcher_may_post(self):
        good = {"user": "momo", "pass": "hunter22"}
        self.assertEqual(self.call("POST", "/api/login/password", good, {"Content-Type": "application/json"})[0], 403)
        self.assertEqual(self.call("POST", "/api/login/password", good,
                                   {"X-Slums": "1", "Origin": "https://evil.example"})[0], 403)
        self.assertEqual(self.call("POST", "/api/login/password", good,
                                   {"X-Slums": "1", "User-Agent": "SlumsMS-Launcher/1.6.0"})[0], 200)  # no Origin: fine
        self.assertEqual(self.game.calls[-1][0], "/verify")
        self.assertEqual(len(self.game.calls), 1)

    def test_too_many_tries_from_one_address(self):
        for _ in range(ls.AUTH_PER_IP_MIN):
            self.call("POST", "/api/login/password", {"user": "momo", "pass": "wrong12"})
        self.assertEqual(self.call("POST", "/api/login/password", {"user": "momo", "pass": "hunter22"})[0], 429)

    def test_auth_api_down_or_not_set_up(self):
        self.game.srv.shutdown()
        self.game.srv.server_close()
        self.assertEqual(self.call("POST", "/api/login/password", {"user": "momo", "pass": "hunter22"}),
                         (503, {"ok": False, "error": "Sign-in is down right now. Try again in a bit."}))
        os.remove(os.path.join(self.dir, "salt"))   # no salt: never send anything, rather than an unsalted hash
        self.assertIsNone(self.auth.call("/verify", {"user": "momo", "pass": "x"}, "1.2.3.4"))

    def test_sign_out_ends_the_session_everywhere(self):
        self.call("POST", "/api/login/password", {"user": "momo", "pass": "hunter22"})
        token = self.jar["slums_session"]
        self.call("POST", "/api/login/logout")
        self.assertIsNone(self.sessions.check(token))            # a copy of the cookie no longer works
        self.assertIsNone(ls.Sessions(KEY, self.revoked).check(token))   # nor in the market service, same files
        self.jar["slums_session"] = token
        self.assertEqual(self.call("GET", "/api/auth/check")[0], 401)


if __name__ == "__main__":
    unittest.main()
