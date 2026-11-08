#!/usr/bin/env python3
"""Tests for the bot population check in slumsms-monitor (2026-11-01): after a SIGKILL the bots never write a logout,
so their botroster rows stay "online" and the count read 2,446 when 1,186 bots were in the world. The check now
splits the open rows by the unit's current start: a row whose LastLoginAt predates this boot is a crash leftover.
Rows are only rewritten by the hourly autosave and by a despawn, so inside the first BOTS_STALE_MIN minutes after a
restart a bot that came back still carries the previous run's login time; until then the count says so and judges the
sum instead of alerting on the split.

Nothing leaves the machine: the DB is a fake answering from a row list, systemctl/journalctl are stubbed, and the
conf/state tree is throwaway (test_announce.Env).

    python3 deploy/monitor/test_bots.py [-v]

Python 3 standard library only (the monitor's PyYAML import is lazy and not reached here).
"""
import io
import time
import unittest
from contextlib import redirect_stdout

import test_announce as announce

mon = announce.mon

DIRECTOR_LINE = "[INFO] [Director] Population director started: peak 1600, curve flat, tick 1000 ms\n"


class FakeDb:
    """A stand-in for Db: rows are [LastLoginAt, LastLogoutAt] in ms, the shape the roster query returns.

    It answers both shapes the monitor has used, so one fixture shows the old count (every open row) and the new
    one (open rows split by this boot): COUNT(*) is the number of rows, since the fixture only holds open ones.
    """

    def __init__(self, rows=None):
        self.rows = rows or []
        self.sql = []

    def usable(self):
        return True

    def query(self, sql):
        self.sql.append(sql)
        if "accounts" in sql:
            return [["3"]]                       # the status message's player count
        if "COUNT(*)" in sql:                    # the pre-fix shape: one number for every open row
            return [[str(len(self.rows))]]
        return self.rows


class BotsCase(unittest.TestCase):
    """A throwaway conf/state tree, a stubbed unit and journal, and a fake roster."""

    UP = 120 * 60          # two hours up: past BOTS_STALE_MIN (75), so the split is trusted

    def setUp(self):
        self.e = announce.Env()
        self.e.secrets(WEBHOOK_URL=self.e.url("private"))
        self.e.conf(UNIT="slumsms.service", DB_CONTAINER="db", SERVER_DIR="", DISK_PATHS=self.e.root,
                    LOGIN_PORT="0", GAME_LOG_FILE="", TIMERS="", STATUS_SHOW_BOTS="1",
                    LLM_URL="", DECIDE_URL="")   # the defaults point at the live bot-host ports
        self.db = FakeDb()
        self.patched = {}

    def patch(self, **values):
        """Monkeypatch module globals; the originals come back in tearDown."""
        for name, value in values.items():
            self.patched.setdefault(name, getattr(mon, name))
            setattr(mon, name, value)
        self.patched.setdefault("CONF_DIR", mon.CONF_DIR)
        self.patched.setdefault("STATE_ROOT", mon.STATE_ROOT)
        self.patched.setdefault("LOG_DIR", mon.LOG_DIR)
        mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR = self.e.conf_dir, self.e.state_dir, self.e.log_dir

    def boot(self, up_seconds=None):
        """Stub the unit, the process, the journal and the DB for a server that started up_seconds ago."""
        up = self.UP if up_seconds is None else up_seconds
        self.up = up
        self.patch(unit_info=lambda unit: {"active": "active", "sub": "running", "load": "loaded", "pid": 4321,
                                           "up_seconds": up},
                   proc_args=lambda pid: ["-Dsolomapling.enabled=true", "-Dsolomapling.autopopulate=true"],
                   run=lambda *a, **k: (0, DIRECTOR_LINE, ""),
                   container_health=lambda container: ("running", "none"),
                   Db=lambda target: self.db,
                   proc_xmx_mb=lambda pid: None,
                   proc_rss_mb=lambda pid: None,
                   heap_used_mb=lambda pid: (None, None))
        return up

    def roster(self, counted, stale, up_seconds=None):
        """counted rows logged in after this boot, stale rows logged in a while before it (crash leftovers)."""
        boot_ms = int((time.time() - (self.up if up_seconds is None else up_seconds)) * 1000)
        rows = [[str(boot_ms + 60_000), "0"] for _ in range(counted)]
        rows += [[str(boot_ms - 3_600_000), "0"] for _ in range(stale)]
        self.db.rows = rows
        return boot_ms

    def bots(self):
        results = mon.run_checks(mon.Target("live"), None, {})
        return [r for r in results if r.key == "bots"][0]

    def tearDown(self):
        for name, value in self.patched.items():
            setattr(mon, name, value)
        self.e.close()


class BotPopulationTest(BotsCase):
    def test_rows_logged_in_before_this_boot_are_not_counted(self):
        self.boot()
        self.roster(counted=1186, stale=1255)
        check = self.bots()
        self.assertTrue(check.ok, check.detail)
        self.assertIn("1186 online", check.detail)
        self.assertIn("(1255 rows logged in before this boot are stale)", check.detail)
        self.assertIn("director target 1600", check.detail)

    def test_the_crash_count_is_gone(self):
        """The 2026-11-01 03:22 shape: 2,441 open rows, 1,186 of them bots that were in the world."""
        self.boot()
        self.roster(counted=1186, stale=1255)
        check = self.bots()
        self.assertNotIn("2441", check.detail)
        self.assertIn("1186 online", check.detail)

    def test_a_post_boot_row_is_counted_and_a_low_population_still_fails(self):
        self.boot()
        self.roster(counted=200, stale=1255)
        check = self.bots()
        self.assertFalse(check.ok, check.detail)
        self.assertIn("200 online", check.detail)
        self.assertIn("1255 rows logged in before this boot are stale", check.detail)
        self.assertIn("(below 60%)", check.detail)

    def test_one_stale_row_is_singular(self):
        self.boot()
        self.roster(counted=1600, stale=1)
        check = self.bots()
        self.assertIn("1 row logged in before this boot is stale", check.detail)

    def test_no_stale_rows_keeps_the_plain_detail(self):
        self.boot()
        self.roster(counted=1600, stale=0)
        check = self.bots()
        self.assertEqual("1600 online, director target 1600", check.detail)
        self.assertTrue(check.ok)

    def test_before_the_first_autosave_the_rows_cannot_be_told_apart(self):
        """A row is rewritten at the hourly autosave or at a despawn, so a bot that logged in minutes ago still
        carries the previous run's login time. Judge the sum and say so instead of alerting on the split."""
        self.boot(up_seconds=30 * 60)
        self.roster(counted=1186, stale=1255)
        check = self.bots()
        self.assertTrue(check.ok, check.detail)
        self.assertTrue(check.skipped, check.detail)
        self.assertEqual("2441 open rows: 1186 logged in since this boot, 1255 from before it (the roster "
                         "rewrites them hourly), director target 1600", check.detail)

    def test_before_the_first_autosave_a_real_drop_still_fails(self):
        """Two hundred open rows in total is low whichever side of the boot they fall on."""
        self.boot(up_seconds=30 * 60)
        self.roster(counted=150, stale=50)
        check = self.bots()
        self.assertFalse(check.ok, check.detail)
        self.assertFalse(check.skipped, check.detail)
        self.assertIn("200 open rows: 150 logged in since this boot, 50 from before it", check.detail)
        self.assertIn("(below 60%)", check.detail)

    def test_no_boot_time_counts_every_open_row_as_before(self):
        self.boot()
        self.roster(counted=1186, stale=1255)
        self.patch(unit_info=lambda unit: {"active": "active", "sub": "running", "load": "loaded", "pid": 4321,
                                           "up_seconds": None})
        check = self.bots()
        self.assertIn("2441 online", check.detail)
        self.assertNotIn("stale", check.detail)

    def test_the_roster_query_is_select_only_and_reads_two_columns(self):
        self.boot()
        self.roster(counted=10, stale=2)
        self.bots()
        roster = [sql for sql in self.db.sql if "botroster" in sql]
        self.assertEqual(1, len(roster), roster)
        self.assertTrue(roster[0].strip().upper().startswith("SELECT"), roster[0])
        self.assertIn("Status='ACTIVE'", roster[0])
        self.assertIn("LastLoginAt > LastLogoutAt", roster[0])
        self.assertIn("LastLoginAt, LastLogoutAt", roster[0])

    def test_an_unavailable_count_is_skipped_not_failed(self):
        self.boot()
        self.roster(counted=10, stale=2)

        real = self.db.query

        def boom(sql):
            if "botroster" in sql:
                raise RuntimeError("query failed (exit 1)")
            return real(sql)
        self.db.query = boom
        check = self.bots()
        self.assertTrue(check.ok, check.detail)
        self.assertTrue(check.skipped, check.detail)
        self.assertIn("bot count unavailable", check.detail)


class StatusBotLineTest(BotsCase):
    def status(self):
        self.patch(login_answers=lambda host, port, timeout=5.0: (True, "hello v83"),
                   read_version=lambda t: "1.6.3")
        out = io.StringIO()
        with redirect_stdout(out):
            mon.cmd_status(mon.Target("live"), type("A", (), {"dry_run": True})())
        return out.getvalue()

    def test_status_names_the_stale_rows(self):
        self.boot()
        self.roster(counted=1186, stale=1255)
        text = self.status()
        self.assertIn("Regulars online: 1186 (1255 stale rows from before this boot)", text)

    def test_status_before_the_first_autosave_shows_the_open_total(self):
        self.boot(up_seconds=20 * 60)
        self.roster(counted=1186, stale=1255)
        text = self.status()
        self.assertIn("Regulars online: 2441 (1255 open rows from before this boot, not split yet)", text)

    def test_status_without_stale_rows_is_unchanged(self):
        self.boot()
        self.roster(counted=1600, stale=0)
        text = self.status()
        self.assertIn("Regulars online: 1600\n", text)
        self.assertNotIn("stale", text)

    def test_status_text_keeps_the_name_rule(self):
        # the status message is player-facing: same rule as Cody's lines (test_announce NAME_RULE, cody-wizet-lines)
        from test_announce import CodyTest
        for up, stale in ((3600, 1255), (20 * 60, 1255), (3600, 0)):
            self.boot(up_seconds=up)
            self.roster(counted=1186, stale=stale)
            self.assertNotRegex(self.status().lower(), CodyTest.NAME_RULE)


if __name__ == "__main__":
    unittest.main()
