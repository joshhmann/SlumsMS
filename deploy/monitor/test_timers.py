#!/usr/bin/env python3
"""Tests for the scheduled jobs check in slumsms-monitor (1.6.5 P0-8): a missed nightly backup, a failed push and a
disabled monthly restore drill must each raise the "scheduled jobs" alert. systemctl is faked: run() answers from a
table of unit properties, so nothing on the machine is read.

    python3 deploy/monitor/test_timers.py [-v]

Python 3 standard library only.
"""
import time
import unittest

import test_announce as announce

mon = announce.mon

HOUR = 3600


class FakeTarget:
    def __init__(self, timers, max_age_h="26"):
        self.conf = {"TIMERS": timers, "TIMER_MAX_AGE_H": max_age_h}

    def get(self, key):
        return self.conf.get(key, "")

    def num(self, key):
        return float(self.conf.get(key) or 0)


def unit(timer_state="active", last_ago_h=1.0, result="success", status="0", service_state="inactive"):
    last = "" if last_ago_h is None else "@%d" % int(time.time() - last_ago_h * HOUR)
    return {"timer": {"ActiveState": timer_state, "LastTriggerUSec": last},
            "service": {"Result": result, "ExecMainStatus": status, "ActiveState": service_state,
                        "ExecMainExitTimestamp": ""}}


class TimerCase(unittest.TestCase):
    def setUp(self):
        self.units = {}
        self.real_run = mon.run

        def fake_run(cmd, timeout=20, env=None):
            name = cmd[2]
            base, kind = name.rsplit(".", 1)
            props = self.units.get(base, {}).get(kind, {})
            return 0, "".join("%s=%s\n" % kv for kv in props.items()), ""

        mon.run = fake_run

    def tearDown(self):
        mon.run = self.real_run

    def status(self, timers):
        return mon.timer_status(FakeTarget(timers))


class TimerStatusTest(TimerCase):
    def test_a_missed_nightly_backup_alerts(self):
        self.units["slumsms-backup"] = unit(last_ago_h=30)
        ok, detail = self.status("slumsms-backup")
        self.assertFalse(ok)
        self.assertIn("slumsms-backup has not run for", detail)

    def test_a_fresh_backup_is_ok(self):
        self.units["slumsms-backup"] = unit(last_ago_h=3)
        ok, detail = self.status("slumsms-backup")
        self.assertTrue(ok, detail)
        self.assertIn("slumsms-backup ok", detail)

    def test_a_failed_push_alerts(self):
        self.units["slums-backup-push"] = unit(last_ago_h=2, result="exit-code", status="1")
        ok, detail = self.status("slums-backup-push")
        self.assertFalse(ok)
        self.assertIn("slums-backup-push last run failed (exit-code, exit 1)", detail)

    def test_a_disabled_drill_timer_alerts(self):
        self.units["slumsms-restore-drill"] = unit(timer_state="inactive", last_ago_h=None)
        ok, detail = self.status("slumsms-restore-drill:768")
        self.assertFalse(ok)
        self.assertIn("slumsms-restore-drill.timer is inactive", detail)

    def test_a_monthly_drill_uses_its_own_age_limit(self):
        self.units["slumsms-restore-drill"] = unit(last_ago_h=20 * 24)    # 20 days: fine for a monthly job
        ok, detail = self.status("slumsms-restore-drill:768")
        self.assertTrue(ok, detail)
        self.units["slumsms-restore-drill"] = unit(last_ago_h=40 * 24)    # a month and more: missed
        ok, detail = self.status("slumsms-restore-drill:768")
        self.assertFalse(ok)
        self.assertIn("slumsms-restore-drill has not run for", detail)

    def test_one_bad_job_among_good_ones_alerts_and_names_only_it(self):
        self.units["slumsms-backup"] = unit(last_ago_h=50)
        self.units["slums-backup-push"] = unit(last_ago_h=5)
        ok, detail = self.status("slumsms-backup slums-backup-push")
        self.assertFalse(ok)
        self.assertIn("slumsms-backup has not run for", detail)
        self.assertNotIn("slums-backup-push", detail)

    def test_a_bad_age_limit_is_reported_not_raised(self):
        ok, detail = self.status("slumsms-restore-drill:monthly")
        self.assertFalse(ok)
        self.assertIn("bad age limit", detail)


if __name__ == "__main__":
    unittest.main()
