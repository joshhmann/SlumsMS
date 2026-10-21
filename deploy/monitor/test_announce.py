#!/usr/bin/env python3
"""Tests for the public announcements in slumsms-monitor (announce, maintenance, announce-feed, Cody's lines), run
against mock-webhook.py. Nothing leaves the machine: every webhook URL points at the mock on 127.0.0.1.

    python3 deploy/monitor/test_announce.py [-v]

Python 3 standard library only (the monitor's PyYAML import is lazy and not reached here).
"""
import importlib.machinery
import importlib.util
import json
import os
import random
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "slumsms-monitor")
MOCK = os.path.join(HERE, "mock-webhook.py")
REPO = os.path.dirname(os.path.dirname(HERE))


def load_monitor():
    loader = importlib.machinery.SourceFileLoader("slumsms_monitor", SCRIPT)
    spec = importlib.util.spec_from_loader("slumsms_monitor", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


mon = load_monitor()


def read(path):
    with open(path) as f:
        return f.read()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Env:
    """A throwaway conf/state/log tree plus a running mock webhook."""

    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="announce-test-")
        self.conf_dir = os.path.join(self.root, "etc")
        self.state_dir = os.path.join(self.root, "state")
        self.log_dir = os.path.join(self.root, "log")
        for d in (self.conf_dir, self.state_dir, self.log_dir):
            os.makedirs(d)
        self.port = free_port()
        self.out = os.path.join(self.root, "mock.jsonl")
        self.mock = subprocess.Popen([sys.executable, MOCK, "--port", str(self.port), "--out", self.out])
        for _ in range(100):
            try:
                urllib.request.urlopen("http://127.0.0.1:%d/" % self.port, timeout=1).read()
                break
            except OSError:
                time.sleep(0.05)
        self.maint = os.path.join(self.root, "live.maintenance")
        self.feed = os.path.join(self.root, "feed.jsonl")
        self.conf()
        self.secrets()

    def url(self, name):
        return "http://127.0.0.1:%d/api/webhooks/%s/tok3n" % (self.port, name)

    def conf(self, **extra):
        values = {"LABEL": "live", "ANNOUNCE_NAME": "Bera", "MAINTENANCE_FILE": self.maint,
                  "ANNOUNCE_FILE": self.feed, "ANNOUNCE_MIN_GAP_SEC": "0", "ANNOUNCE_MAX_PER_HOUR": "100"}
        values.update(extra)
        with open(os.path.join(self.conf_dir, "live.conf"), "w") as f:
            f.write("".join("%s=%s\n" % kv for kv in values.items()))

    def secrets(self, **values):
        with open(os.path.join(self.conf_dir, "live.env"), "w") as f:
            f.write("".join("%s=%s\n" % kv for kv in values.items()))

    def run(self, *args):
        env = dict(os.environ, SLUMSMS_MONITOR_CONF_DIR=self.conf_dir, SLUMSMS_MONITOR_STATE_DIR=self.state_dir,
                   SLUMSMS_MONITOR_LOG_DIR=self.log_dir)
        p = subprocess.run([sys.executable, SCRIPT] + list(args), capture_output=True, text=True, env=env, timeout=60)
        return p.returncode, p.stdout + p.stderr

    def posts(self):
        if not os.path.exists(self.out):
            return []
        with open(self.out) as f:
            return [json.loads(line) for line in f if line.strip()]

    def log(self):
        path = os.path.join(self.log_dir, "live.log")
        return read(path) if os.path.exists(path) else ""

    def add_feed(self, **entry):
        entry.setdefault("at", int(time.time()))
        entry.setdefault("ch", 1)
        entry.setdefault("by", "scheduler")
        with open(self.feed, "a") as f:
            f.write(json.dumps(entry) + "\n")

    def close(self):
        self.mock.terminate()
        self.mock.wait(5)
        shutil.rmtree(self.root, ignore_errors=True)


class Base(unittest.TestCase):
    def setUp(self):
        self.e = Env()

    def tearDown(self):
        self.e.close()


class FallbackTest(Base):
    def test_announce_never_uses_webhook_url(self):
        self.e.secrets(WEBHOOK_URL=self.e.url("private"))
        code, out = self.e.run("announce", "live", "Hello Maplers")
        self.assertEqual(0, code, out)
        self.assertEqual([], self.e.posts(), "WEBHOOK_URL is the private channel; ANNOUNCE must not use it")
        self.assertIn("[announce, no webhook configured] Hello Maplers", self.e.log())

    def test_alerts_still_use_webhook_url(self):
        self.e.secrets(WEBHOOK_URL=self.e.url("private"))
        t = self._target()
        self.assertEqual(self.e.url("private"), t.webhook("ALERT"))
        self.assertEqual(self.e.url("private"), t.webhook("NEWS"))
        self.assertEqual("", t.webhook("ANNOUNCE"))

    def test_falls_back_to_news(self):
        self.e.secrets(WEBHOOK_URL=self.e.url("private"), NEWS_WEBHOOK_URL=self.e.url("news"))
        self.e.run("announce", "live", "Hello")
        self.assertEqual(["/api/webhooks/news/<token>"], [p["path"] for p in self.e.posts()])

    def test_own_hook_wins(self):
        self.e.secrets(WEBHOOK_URL=self.e.url("private"), NEWS_WEBHOOK_URL=self.e.url("news"),
                       ANNOUNCE_WEBHOOK_URL=self.e.url("announce"))
        self.e.run("announce", "live", "Hello")
        self.assertEqual(["/api/webhooks/announce/<token>"], [p["path"] for p in self.e.posts()])

    def test_posts_as_cody_with_optional_avatar(self):
        self.e.secrets(NEWS_WEBHOOK_URL=self.e.url("news"))
        self.e.run("announce", "live", "one")
        self.e.conf(ANNOUNCE_AVATAR_URL="https://example.org/cody.png")
        self.e.run("announce", "live", "two")
        a, b = self.e.posts()
        self.assertEqual(("Cody", None), (a["username"], a["avatar_url"]))
        self.assertEqual(("Cody", "https://example.org/cody.png"), (b["username"], b["avatar_url"]))
        self.assertEqual(["one", "two"], [a["content"], b["content"]], "free text goes out as written")

    def _target(self):
        mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR = self.e.conf_dir, self.e.state_dir, self.e.log_dir
        return mon.Target("live")


class RateCapTest(Base):
    def setUp(self):
        super().setUp()
        self.e.secrets(NEWS_WEBHOOK_URL=self.e.url("news"))

    def test_min_gap(self):
        self.e.conf(ANNOUNCE_MIN_GAP_SEC="120")
        self.e.run("announce", "live", "first")
        code, out = self.e.run("announce", "live", "second")
        self.assertEqual(0, code)
        self.assertIn("limited", out)
        self.assertEqual(["first"], [p["content"] for p in self.e.posts()])
        self.assertRegex(self.e.log(), r"\[announce, rate limited: last post \d+s ago \(min gap 120s\)\] second")

    def test_hourly_cap(self):
        self.e.conf(ANNOUNCE_MAX_PER_HOUR="3")
        for i in range(5):
            self.e.run("announce", "live", "post %d" % i)
        self.assertEqual(["post 0", "post 1", "post 2"], [p["content"] for p in self.e.posts()])
        self.assertEqual(2, self.e.log().count("rate limited: 3 posts in the last hour"))

    def test_maintenance_skips_the_gap_not_the_cap(self):
        self.e.conf(ANNOUNCE_MIN_GAP_SEC="120", ANNOUNCE_MAX_PER_HOUR="2")
        self.e.run("maintenance", "live", "start")
        self.e.run("maintenance", "live", "end")  # seconds later: a quick deploy still gets its "back up"
        self.assertEqual(2, len(self.e.posts()))
        self.e.run("maintenance", "live", "start")
        self.assertEqual(2, len(self.e.posts()), "the hourly cap still holds")
        self.assertTrue(os.path.exists(self.e.maint), "the file is switched even when the post is capped")

    def test_dry_run_changes_nothing(self):
        code, out = self.e.run("announce", "live", "hello", "--dry-run")
        self.assertEqual(0, code)
        self.assertIn("within the cap", out)
        self.assertEqual([], self.e.posts())
        self.assertFalse(os.path.exists(os.path.join(self.e.state_dir, "live", "announce.json")))


class ScrubTest(Base):
    def test_secret_looking_text_is_scrubbed(self):
        self.e.secrets(NEWS_WEBHOOK_URL=self.e.url("news"))
        text = ("Down for a bit https://discord.com/api/webhooks/123/abcDEF password=hunter2 db at 192.168.0.164:3306 "
                "key aGVsbG8gd29ybGQgdGhpcyBpcyBhIGxvbmcgdG9rZW4gc3RyaW5n api_key=xyz @everyone")
        self.e.run("announce", "live", text)
        sent = self.e.posts()[0]["content"]
        for leak in ("discord.com/api", "hunter2", "192.168", "aGVsbG8", "xyz", "@everyone"):
            self.assertNotIn(leak, sent)
        self.assertTrue(sent.startswith("Down for a bit"))
        self.assertNotIn("hunter2", self.e.log())

    def test_plain_text_is_untouched(self):
        for text in ("Bera is going down at 18:00 UTC for patch 1.3.2!", "Hot spot at Ant Tunnel I, +50% EXP~"):
            self.assertEqual(text, mon.scrub_public(text))


class MaintenanceTest(Base):
    def setUp(self):
        super().setUp()
        self.e.secrets(NEWS_WEBHOOK_URL=self.e.url("news"))

    def test_start_and_end(self):
        code, out = self.e.run("maintenance", "live", "start", "--minutes", "7", "--reason", "patch 1.3.2")
        self.assertEqual(0, code, out)
        self.assertTrue(os.path.exists(self.e.maint))
        start = self.e.posts()[0]["content"]
        self.assertIn("Bera", start)
        self.assertIn("7 min", start)
        self.assertIn("patch 1.3.2", start)
        code, out = self.e.run("maintenance", "live", "end")
        self.assertEqual(0, code, out)
        self.assertFalse(os.path.exists(self.e.maint))
        end = self.e.posts()[1]["content"]
        self.assertIn("Bera", end)
        self.assertTrue(any(end == v.replace("{name}", "Bera") for v in mon.CODY["maintenance_end"]), end)

    def test_default_minutes_from_conf(self):
        self.e.conf(MAINTENANCE_MINUTES="12")
        self.e.run("maintenance", "live", "start")
        self.assertIn("12 min", self.e.posts()[0]["content"])

    def test_second_start_does_not_post_again(self):
        self.e.run("maintenance", "live", "start")
        code, out = self.e.run("maintenance", "live", "start")
        self.assertEqual(0, code)
        self.assertIn("already in maintenance", out)
        self.assertEqual(1, len(self.e.posts()))

    def test_end_without_start_posts_nothing(self):
        code, out = self.e.run("maintenance", "live", "end")
        self.assertEqual(0, code)
        self.assertIn("nothing announced", out)
        self.assertEqual([], self.e.posts())

    def test_a_hand_made_maintenance_file_still_gets_its_back_up(self):
        open(self.e.maint, "w").close()
        self.e.run("maintenance", "live", "end")
        self.assertFalse(os.path.exists(self.e.maint))
        self.assertEqual(1, len(self.e.posts()))

    def test_no_announce(self):
        self.e.run("maintenance", "live", "start", "--no-announce")
        self.assertTrue(os.path.exists(self.e.maint))
        self.e.run("maintenance", "live", "end", "--no-announce")
        self.assertFalse(os.path.exists(self.e.maint))
        self.assertEqual([], self.e.posts())

    def test_dry_run_touches_nothing(self):
        code, out = self.e.run("maintenance", "live", "start", "--dry-run")
        self.assertEqual(0, code)
        self.assertFalse(os.path.exists(self.e.maint))
        self.assertIn("[dry-run announce", out)
        self.assertEqual([], self.e.posts())


class FeedTest(Base):
    def setUp(self):
        super().setUp()
        self.e.secrets(NEWS_WEBHOOK_URL=self.e.url("news"))

    def contents(self):
        return [p["content"] for p in self.e.posts()]

    def test_first_run_seeds_without_posting(self):
        self.e.add_feed(id="old", type="start", kind="hot_spot", map="Ant Tunnel I", bonus=50, minutes=30)
        code, out = self.e.run("announce-feed", "live")
        self.assertIn("seeded", out)
        self.assertEqual([], self.e.posts())
        self.e.add_feed(id="new", type="start", kind="hot_spot", map="Ant Tunnel II", bonus=50, minutes=30)
        self.e.run("announce-feed", "live")
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("Ant Tunnel II", self.contents()[0])
        self.assertIn("+50% EXP", self.contents()[0])

    def test_missing_file_then_created(self):
        code, out = self.e.run("announce-feed", "live")
        self.assertEqual(0, code)
        self.assertIn("no feed file", out)
        self.e.add_feed(id="a", type="start", kind="boss_sighting", boss="Faust", map="The Forest of Evil I")
        self.e.run("announce-feed", "live")
        self.assertIn("Faust", self.contents()[0], "a file that appears later is read from its start")

    def test_dedupe_and_rotation(self):
        self.e.run("announce-feed", "live")
        self.e.add_feed(id="a", type="start", kind="boss_sighting", boss="Faust", map="The Forest of Evil I")
        self.e.run("announce-feed", "live")
        self.e.run("announce-feed", "live")
        self.assertEqual(1, len(self.e.posts()), "nothing new, nothing posted")
        os.remove(self.e.feed)  # rotated: a new file with the same line again plus a new one
        self.e.add_feed(id="a", type="start", kind="boss_sighting", boss="Faust", map="The Forest of Evil I")
        self.e.add_feed(id="b", type="won", kind="boss_sighting", boss="Faust", map="The Forest of Evil I",
                        player="Jimbo")
        self.e.run("announce-feed", "live")
        self.assertEqual(2, len(self.e.posts()))
        self.assertIn("Jimbo", self.contents()[1])

    def test_only_public_moments(self):
        self.e.run("announce-feed", "live")
        self.e.add_feed(id="1", type="start", kind="invasion", map="Ant Tunnel I")
        self.e.add_feed(id="2", type="start", kind="wandering_npc", map="Henesys")
        self.e.add_feed(id="3", type="chatter", kind="boss_sighting", map="x")
        self.e.add_feed(id="4", type="won", kind="hot_spot", map="x", player="p")
        self.e.add_feed(id="5", type="start", kind="treasure_hunt", region="Ellinia", minutes=20)
        self.e.run("announce-feed", "live")
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("Ellinia", self.contents()[0])
        self.assertEqual(4, self.e.log().count("not posted"))

    def test_gm_events_and_winners_can_be_switched_off(self):
        self.e.conf(ANNOUNCE_GM_EVENTS="0", ANNOUNCE_EVENT_WINNERS="0")
        self.e.run("announce-feed", "live")
        self.e.add_feed(id="1", type="start", kind="hot_spot", map="A", bonus=50, by="gm")
        self.e.add_feed(id="2", type="won", kind="boss_sighting", boss="Mano", map="B", player="p")
        self.e.add_feed(id="3", type="start", kind="hot_spot", map="C", bonus=50)
        self.e.run("announce-feed", "live")
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("C", self.contents()[0])

    def test_stale_lines_are_logged_not_posted(self):
        self.e.run("announce-feed", "live")
        self.e.add_feed(id="1", type="start", kind="hot_spot", map="A", bonus=50, at=int(time.time()) - 3600)
        self.e.run("announce-feed", "live")
        self.assertEqual([], self.e.posts())
        self.assertIn("stale", self.e.log())

    def test_half_written_line_waits(self):
        self.e.run("announce-feed", "live")
        with open(self.e.feed, "a") as f:
            f.write('{"id": "1", "type": "start", "kind": "hot_spot", "map": "A", "bonus": 50, "ch": 1, '
                    '"by": "scheduler", "at": %d' % int(time.time()))
        self.e.run("announce-feed", "live")
        self.assertEqual([], self.e.posts())
        with open(self.e.feed, "a") as f:
            f.write("}\n")
        self.e.run("announce-feed", "live")
        self.assertEqual(1, len(self.e.posts()))

    def test_feed_goes_through_the_shared_cap(self):
        self.e.conf(ANNOUNCE_MIN_GAP_SEC="120")
        self.e.run("announce-feed", "live")
        self.e.add_feed(id="1", type="start", kind="hot_spot", map="A", bonus=50)
        self.e.add_feed(id="2", type="start", kind="boss_sighting", boss="Mano", map="B")
        self.e.run("announce-feed", "live")
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("rate limited", self.e.log())
        self.e.run("announce-feed", "live")
        self.assertEqual(1, len(self.e.posts()), "a capped line is dropped, not retried later")

    def test_webhook_failure_keeps_the_line(self):
        self.e.secrets(NEWS_WEBHOOK_URL="http://127.0.0.1:%d/not-a-webhook" % self.e.port)
        self.e.run("announce-feed", "live")
        self.e.add_feed(id="1", type="start", kind="hot_spot", map="A", bonus=50)
        code, _ = self.e.run("announce-feed", "live")
        self.assertEqual(1, code)
        self.e.secrets(NEWS_WEBHOOK_URL=self.e.url("news"))
        self.e.run("announce-feed", "live")
        self.assertEqual(1, len(self.e.posts()))

    def test_names_cannot_ping_or_format(self):
        self.e.run("announce-feed", "live")
        self.e.add_feed(id="1", type="won", kind="treasure_hunt", map="**Bold**", player="@everyone")
        self.e.run("announce-feed", "live")
        sent = self.contents()[0]
        self.assertNotIn("@everyone", sent)
        self.assertNotIn("**Bold**", sent)


class CodyTest(unittest.TestCase):
    SAMPLE = {"name": "Bera", "minutes": 5, "reason": "patch", "boss": "Faust", "map": "The Forest of Evil I",
              "region": "Sleepywood", "bonus": 50, "ch": 1, "player": "Jimbo", "text": "hello"}

    def test_every_variant_fills(self):
        needed = ["maintenance_start", "maintenance_reason", "maintenance_end", "free"]
        needed += ["%s_start" % k for k in mon.FEED_KINDS] + ["boss_sighting_won", "treasure_hunt_won"]
        for key in needed:
            self.assertIn(key, mon.CODY)
            self.assertGreaterEqual(len(mon.CODY[key]), 2, key + " needs variants")
            for i, v in enumerate(mon.CODY[key]):
                text = v.format_map(mon._Fields(self.SAMPLE))
                self.assertNotIn("{", text, key)
                self.assertNotIn("?}", text, key)
                self.assertLess(len(text), 300, key + " is a short line")
                self.assertNotRegex(text.lower(), r"https?://|nexon\.|wizet\.com", "no real links")

    def test_cody_signs_and_sounds_like_cody(self):
        lines = [v for k, vs in mon.CODY.items() if k not in ("maintenance_reason", "free") for v in vs]
        for v in lines:
            self.assertIn("Cody", v, "every line is Cody's")
        voice = " ".join(lines)
        for wz in ("I'm telling you, I really AM from WIZET!", "care to help me out??", "went bananas",
                   "See you around~"):
            self.assertIn(wz, voice)

    def test_treasure_start_never_names_the_map(self):
        for v in mon.CODY["treasure_hunt_start"]:
            self.assertNotIn("{map}", v)

    def test_never_the_same_variant_twice_in_a_row(self):
        rng = random.Random(1)
        last = None
        for _ in range(50):
            _, i = mon.cody("maintenance_end", {"name": "Bera"}, last, rng)
            self.assertNotEqual(last, i)
            last = i

    def test_missing_field_is_a_question_mark(self):
        text, _ = mon.cody("boss_sighting_start", {"map": "A"}, rng=random.Random(2))
        self.assertIn("?", text)

    def test_values_are_inert(self):
        self.assertEqual("\\*\\*x\\*\\*", mon.md_safe("**x**"))
        self.assertEqual("@​everyone", mon.md_safe("@everyone"))
        self.assertEqual("a b", mon.md_safe("a\nb"))
        text, _ = mon.cody("free", {"text": "**as written**"}, rng=random.Random(3))
        self.assertIn("**as written**", text, "the owner's own free text is not escaped")


class DeployHookTest(unittest.TestCase):
    """The maint() helper in the release templates: calls the monitor, never fails the deploy."""

    def snippet(self, template):
        body = read(os.path.join(REPO, "tools", "release", "templates", template))
        m = re.search(r"^maint\(\) \{.*?^\}\n", body, re.S | re.M)
        self.assertIsNotNone(m, template)
        return m.group(0)

    def run_bash(self, template, monitor_body, reason=""):
        d = tempfile.mkdtemp()
        try:
            fake = os.path.join(d, "slumsms-monitor")
            with open(fake, "w") as f:
                f.write("#!/bin/bash\necho \"called: $*\"\n" + monitor_body + "\n")
            os.chmod(fake, 0o755)
            script = ("set -euo pipefail\nMONITOR=%s\nMONITOR_TARGET=live\nMAINT_MINUTES=7\nMAINT_REASON='%s'\n%s"
                      "maint start\nmaint end\necho still-running\n" % (fake, reason, self.snippet(template)))
            p = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=30)
            return p.returncode, p.stdout + p.stderr
        finally:
            shutil.rmtree(d)

    def test_calls_start_and_end(self):
        for template in ("deploy.body.sh", "rollback.body.sh"):
            code, out = self.run_bash(template, "exit 0", reason="patch 1.3.2")
            self.assertEqual(0, code, out)
            self.assertIn("called: maintenance live start --minutes 7 --reason patch 1.3.2", out)
            self.assertIn("called: maintenance live end", out)
            self.assertIn("still-running", out)

    def test_no_reason(self):
        code, out = self.run_bash("deploy.body.sh", "exit 0")
        self.assertEqual(0, code, out)
        self.assertIn("called: maintenance live start --minutes 7\n", out)

    def test_failing_monitor_is_soft(self):
        code, out = self.run_bash("deploy.body.sh", "exit 3")
        self.assertEqual(0, code, out)
        self.assertIn("WARN: slumsms-monitor maintenance start failed", out)
        self.assertIn("still-running", out)

    def test_missing_monitor_is_skipped(self):
        script = ("set -euo pipefail\nMONITOR=/nonexistent/slumsms-monitor\nMONITOR_TARGET=live\nMAINT_MINUTES=5\n"
                  "MAINT_REASON=\n%smaint start\nMONITOR=\nmaint end\necho still-running\n"
                  % self.snippet("deploy.body.sh"))
        p = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=30)
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertIn("maintenance start skipped", p.stdout)
        self.assertIn("maintenance end skipped", p.stdout)
        self.assertIn("still-running", p.stdout)


if __name__ == "__main__":
    unittest.main()
