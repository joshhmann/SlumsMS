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


class FakeGameAuth:
    """The game server's auth API (ticket 105) as the login service sees it: POST /verify and /signup."""

    def __init__(self, secret):
        from http.server import BaseHTTPRequestHandler
        self.calls, fake = [], self
        self.reset_state = "pending"  # what /reset/status says; the test plays the admin
        self.invite = {}  # what /invite/<action> says, by action (ticket 105 invites)

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fake.calls.append((self.path, self.headers.get("X-Slums-Auth"), body))
                if self.headers.get("X-Slums-Auth") != secret:
                    ans = (403, {"ok": False, "error": "FORBIDDEN"})
                elif self.path == "/verify":
                    ans = ((200, {"ok": True, "account_id": 42, "account": "momo", "gm": False, "gm_level": 0,
                                  "characters": ["Momo", "Momo2"]})
                           if (body["user"], body["pass"]) == ("momo", "hunter22") else
                           (200, {"ok": True, "account_id": 7, "account": "gm", "gm": True, "gm_level": 3, "characters": ["Boss"]})
                           if (body["user"], body["pass"]) == ("gm", "pw") else   # an old account: breaks the sign-up rules
                           (200, {"ok": False, "error": "LOCKED"}) if body["user"] == "locked" else
                           (200, {"ok": False, "error": "UNAVAILABLE"}) if body["user"] == "dbdown" else
                           (429, {"ok": False, "error": "RATE_LIMITED"}) if body["user"] == "busy" else
                           (200, {"ok": False, "error": "WRONG_LOGIN"}))
                elif self.path.startswith("/invite/"):
                    ans = fake.invite.get(self.path.rsplit("/", 1)[1], (200, {"ok": True}))
                elif self.path == "/reset/request":   # the same answer for any name
                    ans = (200, {"ok": True, "request_id": "r123", "secret": "s" * 32})
                elif self.path == "/reset/status":
                    ans = ((200, {"ok": True, "status": fake.reset_state}) if (body["request_id"], body["secret"]) == ("r123", "s" * 32)
                           else (200, {"ok": True, "status": "expired"}))
                elif self.path == "/reset/complete" and fake.reset_state in ("USED", "EXPIRED"):
                    ans = (200, {"ok": False, "error": fake.reset_state})
                elif self.path == "/reset/complete":
                    ans = ((200, {"ok": True, "account_id": 42, "account": "momo", "gm": False, "gm_level": 0,
                                  "characters": ["Momo"], "revoke_sessions": True})
                           if fake.reset_state == "approved" else (200, {"ok": False, "error": "NOT_APPROVED"}))
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


class PasswordLengthTest(unittest.TestCase):
    """The game's login box takes 23 characters (owner, 2026-10-28): new passwords up to 23; sign-in sends any up to 64."""

    def test_new_passwords_up_to_23(self):
        self.assertIsNone(ls.rule_broken("goodname", "a" * 23))
        self.assertEqual(ls.rule_broken("goodname", "a" * 24), "pass_length")
        self.assertIsNone(ls.sign_in_broken("Sourpatch", "a" * 13), "a 13-character password reaches the game's check")
        self.assertIn("6 to 23", ls.RULES["pass_length"])


class AdminTest(unittest.TestCase):
    """The staff dashboard (/admin/): GM level 3 and up, within 12 h of signing in; nginx asks /api/auth/admin."""

    def setUp(self):
        self.t = [1_000_000]
        self.sessions = ls.Sessions(KEY, now=lambda: self.t[0])
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), ls.make_handler(self.sessions, secure=False))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def tearDown(self):
        self.srv.shutdown()

    def get(self, path, gm=None):
        req = urllib.request.Request(self.base + path)
        if gm is not None:
            req.add_header("Cookie", ls.SESSION_COOKIE + "=" + self.sessions.issue(
                {"account_id": 7, "account": "momo", "character": "Momo", "gm": gm}))
        try:
            r = urllib.request.urlopen(req)
        except urllib.error.HTTPError as e:
            r = e
        data = r.read()
        return r.status, (json.loads(data) if data else None)

    def test_only_gm_level_3_and_up(self):
        self.assertEqual(self.get("/api/auth/admin")[0], 401)
        for gm, want in ((0, 403), (1, 403), (2, 403), (3, 204), (6, 204)):
            self.assertEqual(self.get("/api/auth/admin", gm)[0], want, gm)
        self.assertEqual((self.get("/api/login/me", 3)[1]["admin"], self.get("/api/login/me", 2)[1]["admin"],
                          self.get("/api/login/me")[1]["admin"]), (True, False, False))

    def test_an_admin_session_older_than_12_hours_is_refused(self):
        token = self.sessions.issue({"account_id": 7, "account": "momo", "character": "Momo", "gm": 4})
        s = self.sessions.check(token)
        self.t[0] += ls.ADMIN_TTL
        self.assertTrue(self.sessions.admin(self.sessions.check(token)))
        self.t[0] += 1
        self.assertFalse(self.sessions.admin(self.sessions.check(token)))
        self.assertIsNotNone(self.sessions.check(token), "still signed in as a player")
        self.assertEqual(s["gl"], 4)

    def test_an_old_admin_sign_in_asks_for_a_new_one_and_a_player_still_gets_403(self):
        old_gm = ls.SESSION_COOKIE + "=" + self.sessions.issue({"account_id": 7, "character": "Momo", "gm": 3})
        old_player = ls.SESSION_COOKIE + "=" + self.sessions.issue({"account_id": 8, "character": "Rival", "gm": 0})
        self.t[0] += ls.ADMIN_TTL + 1
        for cookie, want in ((old_gm, 401), (old_player, 403)):
            req = urllib.request.Request(self.base + "/api/auth/admin", headers={"Cookie": cookie})
            try:
                st = urllib.request.urlopen(req).status
            except urllib.error.HTTPError as e:
                st = e.code
            self.assertEqual(st, want)


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
        handler = ls.make_handler(self.sessions, secure=False, auth=self.auth,
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

    def test_invites_go_to_the_game_with_the_sessions_account(self):
        self.assertEqual(self.call("POST", "/api/login/invites/list", {})[0], 401)
        self.call("POST", "/api/login/password", {"user": "momo", "pass": "hunter22"})
        self.assertEqual(self.call("POST", "/api/login/invites/list", {}, headers={})[0], 403)
        self.game.invite["list"] = (200, {"ok": True, "codes": [{"code": "ABCD1234", "usedBy": None}], "left": 1})
        st, body = self.call("POST", "/api/login/invites/list", {"page": 2, "account_id": 1})
        self.assertEqual((st, body), (200, {"codes": [{"code": "ABCD1234", "usedBy": None}], "left": 1, "ok": True}))
        self.assertEqual(self.game.calls[-1][2], {"account_id": 42, "page": 2, "ipHash": self.game.calls[-1][2]["ipHash"]},
                         "the session's account, never one from the body")
        self.call("POST", "/api/login/invites/create", {"life": 30, "note": "for Bob"})
        self.assertEqual(set(self.game.calls[-1][2]), {"account_id", "ipHash"}, "a player can't set life or note")
        self.call("POST", "/api/login/invites/cancel", {"code": "abcd-1234"})
        self.assertEqual(self.game.calls[-1][2]["code"], "ABCD1234")
        self.assertEqual(self.call("POST", "/api/login/invites/cancel", {"code": "x"})[0], 400)
        self.game.invite["cancel"] = (200, {"ok": False, "error": "ALREADY_USED"})
        self.assertEqual(self.call("POST", "/api/login/invites/cancel", {"code": "ABCD1234"})[0], 409)
        for err, want in (("NOT_ELIGIBLE", 403), ("WEEKLY_LIMIT", 429), ("REFUSED", 403), ("UNAVAILABLE", 503), ("ODD", 503),
                          ("BAD_INPUT", 400)):
            self.game.invite["create"] = (200, {"ok": False, "error": err})
            st, body = self.call("POST", "/api/login/invites/create", {})
            self.assertEqual((st, body["code"]), (want, err), err)

    def test_staff_may_set_an_invites_life_and_note(self):
        self.call("POST", "/api/login/password", {"user": "gm", "pass": "pw"})   # GM level 3
        self.call("POST", "/api/login/invites/create", {"life": 30, "note": " for Bob "})
        self.assertEqual({k: v for k, v in self.game.calls[-1][2].items() if k != "ipHash"},
                         {"account_id": 7, "life": 30, "note": "for Bob"})
        self.call("POST", "/api/login/invites/create", {"life": "forever"})
        self.assertEqual(self.game.calls[-1][2]["life"], "forever")
        n = len(self.game.calls)
        for bad in (True, 0, 91, 999, "never"):
            self.assertEqual(self.call("POST", "/api/login/invites/create", {"life": bad})[0], 400, bad)
        self.assertEqual(len(self.game.calls), n, "a bad life never reaches the game (no default-life code)")

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

    def test_an_older_account_signs_in_even_if_it_breaks_the_sign_up_rules(self):
        status, body = self.call("POST", "/api/login/password", {"user": "gm", "pass": "pw"})
        self.assertEqual((status, body["character"]), (200, "Boss"))
        self.assertEqual(self.sessions.check(self.jar["slums_session"]).get("g"), 1)   # gm_level 3: a GM session

    def test_database_down_and_rate_limited(self):
        self.assertEqual(self.call("POST", "/api/login/password", {"user": "dbdown", "pass": "hunter22"})[0], 503)
        self.assertEqual(self.call("POST", "/api/login/password", {"user": "busy", "pass": "hunter22"}),
                         (429, {"ok": False, "error": "Too many tries. Wait a minute and try again."}))

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

    def test_sign_up_rules_are_checked_before_anything_is_sent(self):
        for user, pw, rule in (("abc", "abc123", "user_length"), ("bad name", "abc123", "user_chars"),
                               ("goodname", "short", "pass_length"), ("goodname", "a" * 24, "pass_length"),
                               ("goodname", "has space", "pass_chars")):
            status, body = self.call("POST", "/api/login/signup", {"code": "GOODCODE", "user": user, "pass": pw})
            self.assertEqual((status, body["rule"]), (400, rule), user)
        for user, pw in (("", "x"), ("a" * 65, "x"), ("momo", ""), ("has space", "x")):   # sign-in: only the impossible
            self.assertEqual(self.call("POST", "/api/login/password", {"user": user, "pass": pw})[0], 400, user)
        self.assertEqual(self.game.calls, [])

    def test_the_code_flow_is_gone(self):
        self.assertEqual(self.call("POST", "/api/login/start", headers={})[0], 404)
        self.assertEqual(self.call("GET", "/api/login/poll", headers={})[0], 404)

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

    def test_password_reset_approved_in_game(self):
        self.call("POST", "/api/login/password", {"user": "momo", "pass": "hunter22"})
        old = self.jar.pop("slums_session")                       # momo is signed in somewhere else too
        self.assertEqual(self.call("POST", "/api/login/reset/status", {}), (200, {"ok": True, "status": "none"}))
        self.assertEqual(self.call("POST", "/api/login/reset/request", {"user": "momo", "contact": "x"})[0], 400)
        self.assertEqual(self.call("POST", "/api/login/reset/request", {"user": "momo", "contact": "momo#1234"}),
                         (200, {"ok": True, "status": "pending"}))
        self.assertNotIn("contact", json.dumps(self.game.calls[-1][2]["user"]))
        self.assertIn("ipHash", self.game.calls[-1][2])
        self.assertEqual(self.call("POST", "/api/login/reset/status", {})[1]["status"], "pending")
        self.assertEqual(self.call("POST", "/api/login/reset/complete", {"pass": "newpass1"})[0], 409)   # not approved yet
        self.game.reset_state = "approved"                          # !resetpw approve momo
        self.assertEqual(self.call("POST", "/api/login/reset/status", {})[1]["status"], "approved")
        self.assertEqual(self.call("POST", "/api/login/reset/complete", {"pass": "short"})[1]["rule"], "pass_length")
        status, body = self.call("POST", "/api/login/reset/complete", {"pass": "newpass1"})
        self.assertEqual((status, body["account"]), (200, "momo"))
        self.assertEqual(self.game.calls[-1][2]["pass"], "newpass1")
        self.assertNotIn("slums_reset", self.jar)                   # the request is done
        self.assertIsNone(self.sessions.check(old))                 # the old session is signed out
        self.assertIsNone(ls.Sessions(KEY, self.revoked).check(old))   # in the market service too
        self.assertEqual(self.call("GET", "/api/auth/check")[0], 204)  # this browser is signed in afresh

    def test_a_denied_reset_forgets_the_request(self):
        self.call("POST", "/api/login/reset/request", {"user": "nobody99", "contact": "on discord"})
        self.game.reset_state = "denied"
        self.assertEqual(self.call("POST", "/api/login/reset/status", {})[1]["status"], "denied")
        self.assertNotIn("slums_reset", self.jar)
        self.assertEqual(self.call("POST", "/api/login/reset/status", {})[1]["status"], "none")

    def test_a_used_or_expired_reset_says_so_and_is_forgotten(self):
        for state, text in (("USED", "already used"), ("EXPIRED", "ran out")):
            self.call("POST", "/api/login/reset/request", {"user": "momo", "contact": "momo#1234"})
            self.game.reset_state = state
            status, body = self.call("POST", "/api/login/reset/complete", {"pass": "newpass1"})
            self.assertEqual(status, 410)
            self.assertIn(text, body["error"])
            self.assertNotIn("slums_reset", self.jar)

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
