"""Download passes for the launcher (ticket 106): python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import calendar
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import login_service as ls  # noqa: E402

KEY, PASS_KEY = b"k" * 48, b"p" * 48


class Clock:
    def __init__(self, t=1_800_000_000.0):
        self.t = t

    def __call__(self):
        return self.t


class PassesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.revoked = os.path.join(self.tmp, "revoked.txt")
        self.clock = Clock()
        self.sessions = ls.Sessions(KEY, self.revoked, self.clock)
        self.passes = ls.Passes(PASS_KEY, self.sessions, self.clock)
        self.s = {"a": 7, "exp": self.clock.t + 30 * 86400}

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def write(self, path, text):
        with open(path, "w") as f:
            f.write(text)
        st = os.stat(path)
        os.utime(path, ns=(st.st_atime_ns, st.st_mtime_ns + 1_000_000))  # a new mtime even within one clock tick

    def test_a_pass_lasts_12_hours_and_never_past_its_session(self):
        p, exp = self.passes.issue(self.s)
        self.assertEqual((self.passes.check(p), exp), (7, self.clock.t + 12 * 3600))
        self.clock.t += 12 * 3600 + 1
        self.assertIsNone(self.passes.check(p))
        _, exp = self.passes.issue({"a": 7, "exp": self.clock.t + 60})
        self.assertEqual(exp, self.clock.t + 60)

    def test_forged_other_key_and_session_tokens_fail(self):
        p, _ = self.passes.issue(self.s)
        body, mac = p[len(ls.PASS_PREFIX):].split(".")
        self.assertIsNone(self.passes.check(ls.PASS_PREFIX + body + "." + mac[:-2] + "AA"))
        other = ls.Passes(b"q" * 48, self.sessions, self.clock)            # TEST's key never opens Live
        self.assertIsNone(other.check(p))
        self.assertIsNone(self.passes.check(self.sessions.issue({"account_id": 7})))  # a session cookie isn't a pass
        self.assertIsNone(self.sessions.check(p))                                  # and a pass isn't a session
        for junk in (None, "", "dp1.", "dp1.a.b.c", "dp1." + "x" * 600):
            self.assertIsNone(self.passes.check(junk))

    def test_revoked_and_reset_accounts_fail_as_soon_as_the_file_changes(self):
        p, _ = self.passes.issue(self.s)
        self.assertEqual(self.passes.check(p), 7)
        self.write(self.revoked, "7\n")
        self.assertIsNone(self.passes.check(p))
        self.write(self.revoked, "")
        self.assertEqual(self.passes.check(p), 7)
        self.clock.t += 5
        self.sessions.end_all(7)                       # a password reset: older passes stop too
        self.assertIsNone(self.passes.check(p))
        self.clock.t += 5
        self.assertEqual(self.passes.check(self.passes.issue(self.s)[0]), 7)

    def test_the_check_reads_the_lists_only_when_they_change(self):
        self.write(self.revoked, "")
        p, _ = self.passes.issue(self.s)
        reads = []
        real = self.sessions.revoked
        self.sessions.revoked = lambda: reads.append(1) or real()
        for _ in range(50):
            self.passes.check(p)
        self.assertLessEqual(len(reads), 1)


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.revoked = os.path.join(self.tmp, "revoked.txt")
        self.sessions = ls.Sessions(KEY, self.revoked)
        self.passes = ls.Passes(PASS_KEY, self.sessions)
        self.start(self.passes)
        self.token = self.sessions.issue({"account_id": 7, "character": "Momo"})

    def start(self, passes):
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), ls.make_handler(self.sessions, False, passes=passes))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        shutil.rmtree(self.tmp)

    def call(self, method, path, headers=None, cookie=True):
        req = urllib.request.Request(self.base + path, method=method, data=b"" if method == "POST" else None)
        for k, v in ({"X-Slums": "1"} if headers is None else headers).items():
            req.add_header(k, v)
        if cookie:
            req.add_header("Cookie", f"{ls.SESSION_COOKIE}={self.token}")
        try:
            r = urllib.request.urlopen(req)
        except urllib.error.HTTPError as e:
            r = e
        with r:
            data = r.read()
            return r.status, (json.loads(data) if data else None), r.headers

    def test_sign_in_get_a_pass_and_nginx_accepts_it(self):
        st, body, _ = self.call("POST", "/api/login/download-pass")
        self.assertEqual((st, body["ok"]), (200, True))
        self.assertTrue(body["pass"].startswith("dp1."))
        left = calendar.timegm(time.strptime(body["expiresAt"], "%Y-%m-%dT%H:%M:%SZ")) - time.time()
        self.assertAlmostEqual(left, 12 * 3600, delta=120)
        st, _, h = self.call("GET", "/api/auth/pass", {"X-Slums-Pass": body["pass"]}, cookie=False)
        self.assertEqual((st, h["X-Slums-Account"]), (204, "7"))
        st, _, h = self.call("GET", "/api/auth/pass", {"X-Slums-Pass": body["pass"] + "x"}, cookie=False)
        self.assertEqual((st, h.get("X-Slums-Account")), (401, None))
        self.assertEqual(self.call("GET", "/api/auth/pass", {}, cookie=False)[0], 401)

    def test_signed_out_banned_and_cross_site(self):
        self.assertEqual(self.call("POST", "/api/login/download-pass", cookie=False)[0], 401)
        self.assertEqual(self.call("POST", "/api/login/download-pass", headers={})[0], 403)
        self.assertEqual(self.call("POST", "/api/login/download-pass",
                                   headers={"X-Slums": "1", "Origin": "https://evil.example"})[0], 403)
        with open(self.revoked, "w") as f:
            f.write("7\n")
        st, body, _ = self.call("POST", "/api/login/download-pass")
        self.assertEqual(st, 403)
        self.token = "x.y"
        self.assertEqual(self.call("POST", "/api/login/download-pass")[0], 401)

    def test_no_pass_key_means_503_and_every_check_fails(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.start(None)
        self.assertEqual(self.call("POST", "/api/login/download-pass")[0], 503)
        self.assertEqual(self.call("GET", "/api/auth/pass", {"X-Slums-Pass": "dp1.a.b"}, cookie=False)[0], 401)


if __name__ == "__main__":
    unittest.main()
