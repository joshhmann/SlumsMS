#!/usr/bin/env python3
"""Tests for the game log silence check in slumsms-monitor (card 268): the log stops growing while the login port
still answers, which is what a hung world loop looks like from outside. Nothing leaves the machine: the login port
is a local socket that sends a v83 hello, the webhook points at mock-webhook.py, and the "log has been quiet for
10 minutes" case is made by back-dating the file's mtime instead of sleeping.

    python3 deploy/monitor/test_log.py [-v]

Python 3 standard library only (the monitor's PyYAML import is lazy and not reached here).
"""
import json
import os
import socket
import threading
import time
import unittest

import test_announce as announce

mon = announce.mon

LOG_LINES = "".join("[2026-10-31 20:4%d:%02d]: bot %d says something in the world\n" % (i, i, i)
                    for i in range(10)) + "[2026-10-31 20:47:37]: last line before the hang\n"


class FakeLogin:
    """A listening socket that answers like the v83 login server: 2-byte length, then version 83."""

    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(8)
        self.port = self.sock.getsockname()[1]
        self.closed = False
        threading.Thread(target=self.serve, daemon=True).start()

    def serve(self):
        while not self.closed:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            try:
                conn.sendall(b"\x02\x00\x53\x00")   # length 2, version 83
            except OSError:
                pass
            finally:
                conn.close()

    def close(self):
        self.closed = True
        try:
            self.sock.close()
        except OSError:
            pass


def closed_port():
    """A port nothing listens on any more: bind it, note the number, close it."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class LogCase(unittest.TestCase):
    """A throwaway conf/state/log tree, a game log file, and a fake login port."""

    def setUp(self):
        self.e = announce.Env()
        self.login = FakeLogin()
        self.log = os.path.join(self.e.root, "BotLog.txt")
        with open(self.log, "w") as f:
            f.write(LOG_LINES)
        self.age(600)   # the log stopped growing 10 minutes ago
        self.e.secrets(WEBHOOK_URL=self.e.url("private"))   # a mock, so the alert has somewhere to go
        self.conf()

    def conf(self, **extra):
        values = {"UNIT": "", "LOGIN_PORT": str(self.login.port), "GAME_LOG_FILE": self.log,
                  "SERVER_DIR": "", "DB_CONTAINER": "", "DISK_PATHS": self.e.root,
                  "LLM_URL": "", "DECIDE_URL": ""}
        values.update(extra)
        self.e.conf(**values)

    def age(self, seconds):
        stamp = time.time() - seconds
        os.utime(self.log, (stamp, stamp))

    def check(self, *args):
        code, out = self.e.run("check", "live", *args)
        self.assertEqual(code, 0, out)
        return out

    def state(self):
        with open(os.path.join(self.e.state_dir, "live", "checks.json")) as f:
            return json.load(f)["checks"]

    def alerts(self):
        return [p["content"] for p in self.e.posts() if "ALERT" in p.get("content", "")]

    def tearDown(self):
        self.login.close()
        self.e.close()


class SilenceTest(LogCase):
    def test_silent_log_while_login_answers_alerts_after_two_samples(self):
        self.check("--verbose")                                   # first bad sample: counted, not alerted
        self.assertEqual(self.alerts(), [])
        self.assertEqual(self.state()["log"]["fails"], 1)

        out = self.check("--verbose")                             # second bad sample: alert
        self.assertIn("FAIL   game log", out)
        alerts = self.alerts()
        self.assertEqual(len(alerts), 1, alerts)
        text = alerts[0]
        self.assertIn("**ALERT** game log: game log silent since ", text)
        self.assertIn("(10m)", text)
        self.assertIn("while login:%d answers — world loop likely hung" % self.login.port, text)
        self.assertIn("log %s" % self.log, text)
        self.assertIn("last line 2026-10-31 20:47:37 server time", text)
        self.assertNotIn("server/host down", text)

    def test_growing_log_is_healthy_even_with_an_old_mtime(self):
        self.check("--verbose")                                   # baseline
        with open(self.log, "a") as f:
            f.write("x" * 200000 + "\n")                          # bots still talking: 200 KB in one minute
        self.age(600)                                             # a stalled-looking mtime must not matter
        out = self.check("--verbose")
        self.assertIn("ok     game log", out)
        self.assertEqual(self.alerts(), [])
        self.assertEqual(self.state()["log"]["fails"], 0)

    def test_a_rotated_log_restarts_the_timer(self):
        self.check("--verbose")
        self.assertGreater(self.state()["log"]["fails"], 0)
        os.remove(self.log)                                       # log4j rolls BotLog.txt at midnight / 100 MB
        with open(self.log, "w") as f:
            f.write(LOG_LINES)
        out = self.check("--verbose")
        self.assertIn("ok     game log", out)
        self.assertEqual(self.alerts(), [])
        self.assertLess(time.time() - self.state()["log"]["log_quiet_since"], 60)

    def test_silent_log_with_the_login_port_down_is_left_to_the_login_check(self):
        self.login.close()
        port = closed_port()
        self.conf(LOGIN_PORT=str(port))
        self.check("--verbose")
        out = self.check("--verbose")
        self.assertIn("skip   game log", out)
        self.assertIn("and login:%d also down — server/host down" % port, out)
        alerts = self.alerts()
        self.assertEqual([a for a in alerts if "game log" in a], [],
                         "the login check reports the down port; the log check must not double up")
        self.assertTrue(any("login port" in a for a in alerts), alerts)

    def test_check_can_be_switched_off(self):
        self.conf(LOG_SILENCE_MIN="0")
        out = self.check("--verbose")
        self.assertNotIn("game log", out)
        self.assertEqual(self.alerts(), [])

    def test_grace_after_a_restart(self):
        """A freshly restarted server has an empty log and no alerts: the timer starts when it settles."""
        saved = mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR, mon.unit_info
        mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR = self.e.conf_dir, self.e.state_dir, self.e.log_dir
        mon.unit_info = lambda unit: {"active": "active", "sub": "running", "load": "loaded", "pid": 0,
                                      "up_seconds": 120}
        try:
            t = mon.Target("live")
            checks = {}
            out = [r for r in mon.run_checks(t, None, checks) if r.key == "log"][0]
            self.assertTrue(out.ok, out.detail)
            self.assertIn("server restarted 2m ago", out.detail)
            self.assertNotIn("hung", out.detail)

            mon.unit_info = lambda unit: {"active": "active", "sub": "running", "load": "loaded", "pid": 0,
                                          "up_seconds": 3600}
            out = [r for r in mon.run_checks(t, None, checks) if r.key == "log"][0]
            self.assertFalse(out.ok, out.detail)
            self.assertIn("world loop likely hung", out.detail)
        finally:
            mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR, mon.unit_info = saved


if __name__ == "__main__":
    unittest.main()
