"""Tests for the key console: python3 -m unittest discover -s tools/keys -p 'test_*.py'"""
import importlib.machinery
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
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import console  # noqa: E402
import registry  # noqa: E402

R = importlib.machinery.SourceFileLoader("remote", os.path.join(HERE, "slums-keys-remote")).load_module()


class RegistryTest(unittest.TestCase):
    def test_only_test_keys_with_a_maker_can_change_and_ids_are_unique(self):
        ids = [k["id"] for k in registry.KEYS]
        self.assertEqual(len(ids), len(set(ids)))
        for k in registry.KEYS:
            self.assertRegex(k["id"], registry.ID_RE)
            if registry.changeable(k):
                self.assertEqual(k["env"], "test", k["id"])
                self.assertTrue(k["path"].startswith("/etc/"), k["id"])
                self.assertTrue(k["health"] or not k["restart"], k["id"])
            if k["env"] == "live":
                self.assertFalse(registry.changeable(k), k["id"])
        self.assertFalse(registry.changeable(registry.BY_ID["live-signing-key"]))
        self.assertFalse(registry.changeable(registry.BY_ID["test-dashboard-login"]), "it guards live's dashboard too")


class RemoteTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "key")
        with open(self.path, "wb") as f:
            f.write(b"old-key-bytes-old-key-bytes-old!")
        self.key = dict(registry.BY_ID["test-ip-salt"], id="tmp-key", path=self.path, restart=[], health=[])
        registry.BY_ID["tmp-key"] = self.key
        R.LOG = os.path.join(self.dir, "log")
        self.checks = []
        self._rc = R.restart_and_check
        R.restart_and_check = lambda key: self.checks.pop(0) if self.checks else None

    def tearDown(self):
        R.restart_and_check = self._rc
        del registry.BY_ID["tmp-key"]
        shutil.rmtree(self.dir)

    def read(self):
        with open(self.path, "rb") as f:
            return f.read()

    def test_rotate_makes_a_new_key_keeps_a_backup_and_logs_without_it(self):
        out = R.rotate("tmp-key")
        self.assertTrue(out["ok"])
        new = self.read()
        self.assertEqual(len(new), 32)
        self.assertNotEqual(new, b"old-key-bytes-old-key-bytes-old!")
        self.assertEqual(oct(os.stat(self.path).st_mode & 0o777), oct(0o600))
        self.assertEqual(len(R.backups(self.path)), 1)
        with open(R.log.__globals__["LOG"]) as f:
            log = f.read()
        self.assertIn("tmp-key rotate ok", log)
        self.assertNotIn(new.hex(), log)
        self.assertEqual(R.restore("tmp-key")["ok"], True)
        self.assertEqual(self.read(), b"old-key-bytes-old-key-bytes-old!")

    def test_a_failed_check_puts_the_old_key_back(self):
        self.checks = ["slums-login-test is not running", None]
        out = R.rotate("tmp-key")
        self.assertFalse(out["ok"])
        self.assertIn("old key was put back", out["error"])
        self.assertEqual(self.read(), b"old-key-bytes-old-key-bytes-old!")

    def test_locked_and_unknown_ids_are_refused(self):
        for bad in ("live-login-key", "test-auth-secret", "nope", "../etc/passwd", ""):
            with self.assertRaises(SystemExit):
                R.rotate(bad)

    def test_status_never_looks_at_live_files_and_shows_no_contents(self):
        facts = R.facts(registry.BY_ID["live-login-key"])
        self.assertEqual(facts, {"id": "live-login-key", "seen": False})
        f = R.facts(self.key)
        self.assertTrue(f["exists"] and f["nonEmpty"] and f["modeOk"] is False)  # the test file is 644
        self.assertNotIn("old-key", json.dumps(f))

    def test_text_keys_are_header_safe(self):
        self.assertRegex(R.make("text").decode(), r"^[A-Za-z0-9]{40,}$")


class ConsoleTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.pass_file = os.path.join(self.dir, "owner")
        with open(self.pass_file, "w") as f:
            f.write(console.hash_pass("correct horse battery") + "\n")
        self.asked = []

        def ask(*args):
            self.asked.append(args)
            return {"ok": True, "keys": []} if args == ("status",) else {"ok": True, "message": "Done."}
        self.t = [0.0]
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), console.make_handler(
            ask, console.Gate(now=lambda: self.t[0]), self.pass_file))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def tearDown(self):
        self.srv.shutdown()
        shutil.rmtree(self.dir)

    def post(self, **form):
        data = urllib.parse.urlencode(form).encode()
        with urllib.request.urlopen(urllib.request.Request(self.base + "/change", data=data)) as r:
            return r.read().decode()

    def changes(self):
        return [a for a in self.asked if a[0] != "status"]

    def test_page_lists_every_key_and_no_buttons_for_locked_ones(self):
        with urllib.request.urlopen(self.base + "/") as r:
            page = r.read().decode()
        for k in registry.KEYS:
            self.assertIn(k["id"], page)
        self.assertEqual(page.count("Make a new key"), sum(1 for k in registry.KEYS if registry.changeable(k)))

    def test_a_change_needs_the_typed_name_and_the_passphrase(self):
        self.assertIn("Type the key", self.post(id="test-ip-salt", do="rotate", confirm="test-ip", **{"pass": "correct horse battery"}))
        self.assertIn("Wrong passphrase", self.post(id="test-ip-salt", do="rotate", confirm="test-ip-salt", **{"pass": "nope"}))
        self.assertIn("can&#x27;t be changed", self.post(id="live-login-key", do="rotate", confirm="live-login-key",
                                                         **{"pass": "correct horse battery"}))
        self.assertEqual(self.changes(), [])
        self.assertIn("test-ip-salt: Done.", self.post(id="test-ip-salt", do="rotate", confirm="test-ip-salt",
                                                       **{"pass": "correct horse battery"}))
        self.assertEqual(self.changes(), [("rotate", "test-ip-salt")])

    def test_five_wrong_passphrases_lock_changes_for_15_minutes(self):
        for _ in range(5):
            self.post(id="test-ip-salt", do="rotate", confirm="test-ip-salt", **{"pass": "nope"})
        good = dict(id="test-ip-salt", do="rotate", confirm="test-ip-salt", **{"pass": "correct horse battery"})
        self.assertIn("locked for 15 minutes", self.post(**good))
        self.t[0] += console.LOCK_S + 1
        self.assertIn("Done.", self.post(**good))

    def test_no_passphrase_file_means_no_buttons(self):
        os.remove(self.pass_file)
        with urllib.request.urlopen(self.base + "/") as r:
            page = r.read().decode()
        self.assertNotIn("Make a new key", page)
        self.assertIn("slums-keys passphrase", page)
        self.assertIn("Wrong passphrase", self.post(id="test-ip-salt", do="rotate", confirm="test-ip-salt", **{"pass": ""}))

    def test_another_sites_form_is_refused(self):
        req = urllib.request.Request(self.base + "/change", data=b"id=test-ip-salt&do=rotate",
                                     headers={"Origin": "http://evil.example"})
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req)
        self.assertEqual(e.exception.code, 403)

    def test_passphrase_hash_round_trip(self):
        h = console.hash_pass("pw-long-enough!")
        self.assertTrue(console.pass_ok("pw-long-enough!", h))
        self.assertFalse(console.pass_ok("pw-long-enough?", h))
        self.assertFalse(console.pass_ok("x", "garbage"))
        self.assertNotIn("pw-long-enough", h)


if __name__ == "__main__":
    unittest.main()
