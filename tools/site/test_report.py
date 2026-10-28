"""Tests for the report form receiver and page: python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402
import check  # noqa: E402
import report_export  # noqa: E402
import report_service as rs  # noqa: E402

SALT = b"test-salt-0123456789abcdef"


class Clock:
    def __init__(self):
        self.t = 1_800_000_000.0

    def __call__(self):
        return self.t


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="slums-report-test-")
        self.path = os.path.join(self.tmp, "reports.jsonl")
        self.clock = Clock()
        self.start()

    def start(self, limits=(5, 20, 200)):
        self.store = rs.Store(self.path, SALT, limits, self.clock)
        self.srv = rs.make_server(self.store, 0)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        shutil.rmtree(self.tmp)

    def post(self, payload, ip="203.0.113.7", ctype="application/json", raw=None, headers=None):
        if raw is None:
            raw = json.dumps(payload).encode() if ctype == "application/json" else urllib.parse.urlencode(payload).encode()
        h = {"Content-Type": ctype, "X-Forwarded-For": f"{ip}, 10.0.0.2"}
        h.update(headers or {})
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/api/report", data=raw, headers=h, method="POST")
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, r.read().decode(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(), e.headers

    def lines(self):
        if not os.path.exists(self.path):
            return []
        with open(self.path, encoding="utf-8") as f:
            return [json.loads(x) for x in f]

    def good(self, **kw):
        d = {"type": "bug", "title": "Maya is stuck", "details": "line1\nline2", "character": "Bob", "where": "Henesys"}
        d.update(kw)
        return d

    def test_ids_follow_the_games_appends(self):
        """Ticket 131: the game server appends @bug lines to the same file; the next site report takes the next id."""
        self.post(self.good())
        with open(self.path, "a", encoding="utf-8") as f:   # what the game writes, under its own lock
            f.write(json.dumps({"id": 2, "time": "2026-10-28T20:41:07+00:00", "type": "bug", "title": "in game",
                                "source": "ingame", "ref": "B-2"}, separators=(",", ":")) + "\n")
            f.write(json.dumps({"id": 3, "time": "2026-10-28T20:42:07+00:00", "type": "bug", "title": "in game 2",
                                "source": "ingame", "ref": "B-3"}, separators=(",", ":")) + "\n")
        st, body, _ = self.post(self.good(title="after the game's"))
        self.assertEqual(json.loads(body)["id"], 4)
        self.assertEqual([r["id"] for r in self.lines()], [1, 2, 3, 4])

    def test_the_write_waits_for_the_games_lock(self):
        """POSIX locks are per process, so the game is played by another process: it holds the lock, appends id 7."""
        import subprocess
        import sys as _sys
        game = subprocess.Popen([_sys.executable, "-c", (
            "import fcntl, os, sys, time\n"
            "fd = os.open(sys.argv[1], os.O_RDWR | os.O_APPEND | os.O_CREAT, 0o640)\n"
            "fcntl.lockf(fd, fcntl.LOCK_EX)\n"
            "print('locked', flush=True)\n"
            "time.sleep(1.0)\n"
            "os.write(fd, b'{\"id\":7,\"source\":\"ingame\",\"ref\":\"B-7\"}\\n')\n"
            "os.close(fd)\n"), self.path], stdout=subprocess.PIPE, text=True)
        self.assertEqual(game.stdout.readline().strip(), "locked")
        st, body, _ = self.post(self.good())   # blocks until the game lets go, then numbers after its line
        game.wait(5)
        self.assertEqual(json.loads(body)["id"], 8)
        self.assertEqual([r["id"] for r in self.lines()], [7, 8])

    def test_accepts_and_trims(self):
        st, body, _ = self.post(self.good(title="  Hello  ", character=" Bob "))
        self.assertEqual(st, 200)
        self.assertEqual(json.loads(body), {"ok": True, "id": 1})
        rec = self.lines()[0]
        self.assertEqual((rec["title"], rec["character"], rec["details"]), ("Hello", "Bob", "line1\nline2"))
        self.assertEqual(rec["type"], "bug")
        self.assertTrue(rec["time"].endswith("+00:00"))

    def test_validation(self):
        cases = [self.good(type="other"), self.good(title="   "), self.good(title="x" * 141),
                 self.good(details="x" * 2001), self.good(character="x" * 14), self.good(where="x" * 201),
                 self.good(title=["a"])]
        for c in cases:
            st, body, _ = self.post(c)
            self.assertEqual(st, 400, c)
            self.assertFalse(json.loads(body)["ok"])
        self.assertEqual(self.lines(), [])
        for ok in (self.good(title="x" * 140), self.good(details="x" * 2000), self.good(character="x" * 13),
                   self.good(type="feature", details="", character="", where="")):
            self.assertEqual(self.post(ok, ip=f"198.51.100.{len(ok['title'])}")[0], 200)

    def test_bad_bodies(self):
        self.assertEqual(self.post(None, raw=b"{nope")[0], 400)
        self.assertEqual(self.post(None, raw=b"[1,2]")[0], 400)
        self.assertEqual(self.post(None, raw=b"x", ctype="text/plain")[0], 415)
        self.assertEqual(self.lines(), [])

    def test_honeypot_stores_nothing(self):
        st, body, _ = self.post(self.good(website="http://spam.example"))
        self.assertEqual(st, 200)
        self.assertEqual(self.lines(), [])
        self.assertEqual(self.store.count(), 0)

    def test_size_cap(self):
        big = json.dumps(self.good(details="x" * 9000)).encode()
        st, _, _ = self.post(None, raw=big)
        self.assertEqual(st, 413)
        self.assertEqual(self.lines(), [])

    def test_ids_increment_and_survive_restart(self):
        ids = [json.loads(self.post(self.good(), ip=f"192.0.2.{i}")[1])["id"] for i in range(3)]
        self.assertEqual(ids, [1, 2, 3])
        self.srv.shutdown()
        self.srv.server_close()
        self.start()
        self.assertEqual(json.loads(self.post(self.good(), ip="192.0.2.99")[1])["id"], 4)

    def test_per_ip_limits(self):
        for _ in range(5):
            self.assertEqual(self.post(self.good())[0], 200)
        st, body, h = self.post(self.good())
        self.assertEqual(st, 429)
        self.assertTrue(int(h["Retry-After"]) > 0)
        self.assertEqual(self.post(self.good(), ip="203.0.113.8")[0], 200)  # another visitor is fine
        self.clock.t += 3601
        self.assertEqual(self.post(self.good())[0], 200)  # hour passed
        # daily cap: 20 per day
        self.clock.t += 86401
        for i in range(20):
            self.assertEqual(self.post(self.good())[0], 200, i)
            self.clock.t += 800  # stay under 5 per hour
        self.assertEqual(self.post(self.good())[0], 429)

    def test_limits_survive_restart(self):
        for _ in range(5):
            self.post(self.good())
        self.srv.shutdown()
        self.srv.server_close()
        self.start()
        self.assertEqual(self.post(self.good())[0], 429)

    def test_global_limit(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.start(limits=(5, 20, 3))
        for i in range(3):
            self.assertEqual(self.post(self.good(), ip=f"192.0.2.{i}")[0], 200)
        self.assertEqual(self.post(self.good(), ip="192.0.2.50")[0], 429)

    def test_spoofed_leftmost_xff_ignored(self):
        for i in range(5):
            self.post(self.good(), headers={"X-Forwarded-For": f"1.1.1.{i}, 203.0.113.7, 10.0.0.2"})
        st, _, _ = self.post(self.good(), headers={"X-Forwarded-For": "9.9.9.9, 203.0.113.7, 10.0.0.2"})
        self.assertEqual(st, 429)

    def test_client_ip(self):
        self.assertEqual(rs.client_ip("1.2.3.4, 10.0.0.2", "127.0.0.1"), "1.2.3.4")
        self.assertEqual(rs.client_ip("evil, 1.2.3.4, 10.0.0.2", "127.0.0.1"), "1.2.3.4")
        self.assertEqual(rs.client_ip("1.2.3.4", "127.0.0.1"), "1.2.3.4")
        self.assertEqual(rs.client_ip("", "127.0.0.1"), "127.0.0.1")

    def test_raw_ip_never_written(self):
        self.post(self.good(), ip="203.0.113.7")
        raw = open(self.path, encoding="utf-8").read()
        self.assertNotIn("203.0.113.7", raw)
        self.assertNotIn("10.0.0.2", raw)
        rec = json.loads(raw)
        self.assertEqual(rec["ip_hash"], self.store.hash_ip("203.0.113.7"))
        self.assertEqual(len(rec["ip_hash"]), 64)
        self.assertNotEqual(rec["ip_hash"], rs.Store(self.path, b"other-salt-0123456789", clock=self.clock).hash_ip("203.0.113.7"))
        if os.name == "posix":  # the report service runs on 164 (Linux); Windows has no such file modes
            self.assertEqual(oct(os.stat(self.path).st_mode & 0o777), oct(0o640))

    def test_form_post_html_escaped(self):
        evil = '<script>alert("x")</script> & <b>'
        st, body, h = self.post(self.good(title=evil), ctype="application/x-www-form-urlencoded")
        self.assertEqual(st, 200)
        self.assertIn("text/html", h["Content-Type"])
        self.assertNotIn("<script>", body)
        self.assertIn("&lt;script&gt;", body)
        self.assertIn("#1", body)
        self.assertIn("default-src 'none'", h["Content-Security-Policy"])
        self.assertEqual(self.lines()[0]["title"], evil)  # stored verbatim, as data
        st, body, _ = self.post(self.good(title="", type="<i>"), ctype="application/x-www-form-urlencoded")
        self.assertEqual(st, 400)
        self.assertNotIn("<i>", body)

    def test_json_responses_are_json(self):
        _, body, h = self.post(self.good(title=""))
        self.assertIn("application/json", h["Content-Type"])
        self.assertNotIn("<", body)

    def test_other_endpoints(self):
        base = f"http://127.0.0.1:{self.port}"
        with urllib.request.urlopen(base + "/api/report/health") as r:
            self.assertEqual(json.loads(r.read()), {"ok": True, "reports": 0})
        for path in ("/", "/etc/passwd", "/api/other", "/api/report/../x"):
            with self.assertRaises(urllib.error.HTTPError) as c:
                urllib.request.urlopen(base + path)
            self.assertEqual(c.exception.code, 404)
        with self.assertRaises(urllib.error.HTTPError) as c:
            urllib.request.urlopen(base + "/api/report")
        self.assertEqual(c.exception.code, 405)

    def test_binds_loopback_only(self):
        self.assertEqual(self.srv.server_address[0], "127.0.0.1")

    def test_salt_too_short(self):
        p = os.path.join(self.tmp, "salt")
        open(p, "w").write("abc")
        with self.assertRaises(SystemExit):
            rs.read_salt(p)

    def test_export(self):
        for i in range(3):
            self.post(self.good(title=f"t{i}"), ip=f"192.0.2.{i}")
        rows = report_export.export(self.path, 1)
        self.assertEqual([r["id"] for r in rows], [2, 3])
        self.assertNotIn("ip_hash", rows[0])
        self.assertEqual(report_export.export(self.path, 3), [])


class ReportPageTest(unittest.TestCase):
    def test_page_builds_and_checks(self):
        tmp = tempfile.mkdtemp(prefix="slums-report-site-")
        try:
            out = os.path.join(tmp, "site")
            build.build(out)
            page = os.path.join(out, "report", "index.html")
            self.assertTrue(os.path.isfile(page))
            text = open(page, encoding="utf-8").read()
            for needle in ('action="../api/report"', 'name="website"', 'maxlength="140"', 'maxlength="2000"',
                           'maxlength="13"', ">Report</a>"):
                self.assertIn(needle, text)
            self.assertTrue(os.path.isfile(os.path.join(out, "assets", "report.js")))
            self.assertEqual(check.check_all(out), [])
        finally:
            shutil.rmtree(tmp)


if __name__ == "__main__":
    unittest.main()
