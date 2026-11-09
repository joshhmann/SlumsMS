#!/usr/bin/env python3
"""Tests for the opt-in auto-QA health check in slumsms-monitor (1.6.7 P2-8): HEALTH_FILE names the
data/autoqa/health-latest.json that soloMapling.autoqa.HealthSweeper writes every few minutes (TEST); the monitor
alerts when it is missing, older than HEALTH_MAX_AGE_MIN (default 20) or its status is FAIL. Age is made by
back-dating the file's mtime. Nothing leaves the machine: the webhook is mock-webhook.py.

    python3 deploy/monitor/test_health.py [-v]

Python 3 standard library only.
"""
import json
import os
import time
import unittest

import test_announce as announce

mon = announce.mon


class HealthCase(unittest.TestCase):
    def setUp(self):
        self.e = announce.Env()
        self.file = os.path.join(self.e.root, "health-latest.json")
        self.e.secrets(WEBHOOK_URL=self.e.url("private"))
        self.write({"status": "OK", "checks": {"foothold": {"status": "OK"}, "clump": {"status": "OK"}}})
        self.conf()

    def conf(self, **extra):
        values = {"UNIT": "", "LOGIN_PORT": "0", "SERVER_DIR": "", "DB_CONTAINER": "", "DISK_PATHS": self.e.root,
                  "LLM_URL": "", "DECIDE_URL": "", "LOG_SILENCE_MIN": "0", "HEALTH_FILE": self.file,
                  "FAIL_AFTER_health": "1"}
        values.update(extra)
        self.e.conf(**{k: v for k, v in values.items() if v is not None})

    def write(self, doc, age_s=60):
        with open(self.file, "w") as f:
            json.dump(doc, f)
        stamp = time.time() - age_s
        os.utime(self.file, (stamp, stamp))

    def check(self):
        code, out = self.e.run("check", "live", "--verbose")
        self.assertEqual(code, 0, out)
        return out

    def alerts(self):
        return [p["content"] for p in self.e.posts() if "ALERT" in p.get("content", "")]

    def recoveries(self):
        return [p["content"] for p in self.e.posts() if "RECOVERED" in p.get("content", "")]

    def tearDown(self):
        self.e.close()


class HealthTest(HealthCase):
    def test_fresh_ok_file_passes(self):
        out = self.check()
        self.assertIn("ok     auto-QA health", out)
        self.assertEqual(self.alerts(), [])

    def test_a_stale_file_alerts_then_recovers(self):
        self.write({"status": "OK", "checks": {}}, age_s=45 * 60)
        out = self.check()
        self.assertIn("FAIL   auto-QA health", out)
        alerts = self.alerts()
        self.assertEqual(len(alerts), 1, alerts)
        self.assertIn("**ALERT** auto-QA health", alerts[0])
        self.assertIn("45m old", alerts[0])
        self.assertIn("limit 20m", alerts[0])
        self.write({"status": "OK", "checks": {}})
        self.check()
        self.assertEqual(len(self.recoveries()), 1, self.e.posts())
        self.assertIn("auto-QA health", self.recoveries()[0])

    def test_the_age_limit_is_configurable(self):
        self.write({"status": "OK", "checks": {}}, age_s=45 * 60)
        self.conf(HEALTH_MAX_AGE_MIN="60")
        self.assertIn("ok     auto-QA health", self.check())
        self.assertEqual(self.alerts(), [])

    def test_status_fail_alerts_and_names_the_failing_checks(self):
        self.write({"status": "FAIL", "checks": {"foothold": {"status": "FAIL", "summary": "bot 1 on nothing"},
                                                  "clump": {"status": "OK"}}})
        out = self.check()
        self.assertIn("FAIL   auto-QA health", out)
        alerts = self.alerts()
        self.assertEqual(len(alerts), 1, alerts)
        self.assertIn("status FAIL", alerts[0])
        self.assertIn("foothold", alerts[0])
        self.assertNotIn("bot 1 on nothing", alerts[0])   # names only, never the sweep's own text
        self.assertNotIn("clump", alerts[0])

    def test_status_warn_is_not_an_alert(self):
        self.write({"status": "WARN", "checks": {"clump": {"status": "WARN"}}})
        self.assertIn("ok     auto-QA health", self.check())
        self.assertEqual(self.alerts(), [])

    def test_a_missing_file_alerts(self):
        os.remove(self.file)
        out = self.check()
        self.assertIn("FAIL   auto-QA health", out)
        alerts = self.alerts()
        self.assertEqual(len(alerts), 1, alerts)
        self.assertIn("missing", alerts[0])
        self.assertIn(self.file, alerts[0])

    def test_an_unreadable_file_alerts(self):
        with open(self.file, "w") as f:
            f.write("{not json")
        self.assertIn("FAIL   auto-QA health", self.check())
        self.assertIn("unreadable", self.alerts()[0])

    def test_unset_is_skipped_entirely(self):
        self.conf(HEALTH_FILE="")
        os.remove(self.file)
        out = self.check()
        self.assertNotIn("auto-QA health", out)
        self.assertEqual(self.alerts(), [])

    def test_a_still_failing_file_is_not_repeated_every_minute(self):
        os.remove(self.file)
        self.check()
        self.check()
        self.check()
        self.assertEqual(len(self.alerts()), 1, self.alerts())

    def test_maintenance_keeps_the_alert_out_of_discord(self):
        open(self.e.maint, "w").close()
        os.remove(self.file)
        self.check()
        self.assertEqual(self.alerts(), [])

    def test_default_needs_three_runs(self):
        self.conf(FAIL_AFTER_health=None)   # the shipped default
        os.remove(self.file)
        self.check()
        self.check()
        self.assertEqual(self.alerts(), [])
        self.check()
        self.assertEqual(len(self.alerts()), 1)

    def test_a_down_server_is_left_to_the_unit_check(self):
        saved = mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR, mon.unit_info
        mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR = self.e.conf_dir, self.e.state_dir, self.e.log_dir
        try:
            self.conf(UNIT="slums-fake.service")
            os.remove(self.file)
            t = mon.Target("live")
            mon.unit_info = lambda unit: {"active": "inactive", "sub": "dead", "load": "loaded", "pid": 0,
                                          "up_seconds": 0}
            r = [x for x in mon.run_checks(t, None, {}) if x.key == "health"][0]
            self.assertTrue(r.skipped, r.detail)
            mon.unit_info = lambda unit: {"active": "active", "sub": "running", "load": "loaded", "pid": 0,
                                          "up_seconds": 120}   # just booted: the first sweep is not due yet
            r = [x for x in mon.run_checks(t, None, {}) if x.key == "health"][0]
            self.assertTrue(r.skipped, r.detail)
            mon.unit_info = lambda unit: {"active": "active", "sub": "running", "load": "loaded", "pid": 0,
                                          "up_seconds": 7200}
            r = [x for x in mon.run_checks(t, None, {}) if x.key == "health"][0]
            self.assertFalse(r.ok, r.detail)
        finally:
            mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR, mon.unit_info = saved


class VersionFileTest(unittest.TestCase):
    """P2-8 part A: read_version prefers VERSION_FILE (line 1) and falls back to VERSION."""

    class T:
        def __init__(self, **conf):
            self.conf = conf

        def get(self, key):
            return self.conf.get(key, "")

    def setUp(self):
        self.e = announce.Env()
        self.path = os.path.join(self.e.root, "SLUMSMS_VERSION")

    def tearDown(self):
        self.e.close()

    def test_the_file_wins_over_version(self):
        with open(self.path, "w") as f:
            f.write("1.6.7\nabc123def\n")
        self.assertEqual(mon.read_version(self.T(VERSION_FILE=self.path, VERSION="1.6.6.1")), "1.6.7")

    def test_missing_or_empty_file_falls_back_to_version(self):
        t = self.T(VERSION_FILE=self.path, VERSION="1.6.6.1")
        self.assertEqual(mon.read_version(t), "1.6.6.1")
        open(self.path, "w").close()
        self.assertEqual(mon.read_version(t), "1.6.6.1")

    def test_the_shipped_targets_name_the_version_file(self):
        here = os.path.dirname(os.path.abspath(__file__))
        for name in ("live.conf", "persistence-test.conf"):
            with open(os.path.join(here, "targets", name)) as f:
                lines = [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]
            self.assertTrue(any(ln.startswith("VERSION_FILE=") and ln.endswith("/SLUMSMS_VERSION") for ln in lines), name)
        with open(os.path.join(here, "targets", "live.conf")) as f:
            self.assertIn("VERSION=1.6.6.1", [ln.strip() for ln in f])
        with open(os.path.join(here, "targets", "persistence-test.conf")) as f:
            lines = [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]
        self.assertTrue(any(ln.startswith("HEALTH_FILE=") and ln.endswith("/data/autoqa/health-latest.json") for ln in lines))
        with open(os.path.join(here, "targets", "live.conf")) as f:
            self.assertFalse(any(ln.startswith("HEALTH_FILE=") for ln in f))   # live does not run the sweeper

    def test_deploy_test_writes_the_version_file_only_after_the_boot_check(self):
        here = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(here, "..", "..", "tools", "ops", "deploy-test")) as f:
            text = f.read()
        boot = text.index("deploy-test-remote.sh")        # the remote script exits 1 when the boot check fails
        write = text.index("SLUMSMS_VERSION", boot)
        self.assertLess(boot, write)
        self.assertIn("printf '%s\\n%s\\n' \"$VERSION\" \"$HEAD\"", text[boot:write + 100])
        self.assertIn("$SRV/SLUMSMS_VERSION", text[write - 100:write + 100])


if __name__ == "__main__":
    unittest.main()
