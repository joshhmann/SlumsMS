"""Tests for the 187 orchestrator (ticket 170). Run: python3 -m unittest discover -s tools/qa-runner -p 'test_*.py'"""
import contextlib
import datetime
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qarunner  # noqa: E402

REPO = qarunner.REPO
SCRIPT = REPO / "qa-scripts/b140-bot-heal-column.yaml"


class FakeFeed:
    """The feed's semantics: lines after since, filtered by topics and chars; next = the newest seq."""

    def __init__(self):
        self.lines = []
        self.polls = []
        self.on_poll = None

    def add(self, topic, line, chars=()):
        seq = len(self.lines) + 1
        self.lines.append({"seq": seq, "utc": "2026-10-31T03:13:58.410Z", "topic": topic, "kind": topic, "map": 0,
                           "chars": list(chars), "line": line})
        return seq

    def poll(self, since, chars, topics, match, wait):
        self.polls.append((since, tuple(chars), tuple(topics), wait))
        if self.on_poll:
            self.on_poll(self, wait)
        hits = [ln for ln in self.lines if ln["seq"] > since and ln["topic"] in topics
                and (not chars or not ln["chars"] or set(ln["chars"]) & set(chars))]
        last = len(self.lines)
        return {"ok": True, "last": last, "next": max(since, last), "gap": False, "lines": hits}


class FakeWorker:
    def __init__(self, feed, missing=(), lose_world_clicks=0):
        self.feed = feed
        self.sent = []
        self.bursts = []
        self.captures = []
        self.missing = set(missing)
        self.lose_world_clicks = lose_world_clicks
        self.world_clicks = 0

    def login(self, alias, client):
        self.sent.append((alias, "login"))

    def send(self, alias, step):
        self.sent.append((alias, step["do"], step.get("line") or step.get("text") or step.get("anchor")))
        if step["do"] == "select_world":
            self.world_clicks += 1
        if step["do"] == "gm" and step["line"].startswith("!hurt"):
            # the bot heals within a few seconds: the server line comes after the hurt
            self.feed.add("COMBAT", "PARTY HEAL Bishbot -> AqClient skill 2301002 lv 30 hp 1912->4210 healed 1 x 2298"
                                    " sent SHOW_FOREIGN_EFFECT/1>map hold 900ms", [31])
        return {"result": "ok"}

    def wait_signal(self, alias, name, timeout_s, extra):
        if name in self.missing:
            return None
        if name == "channels.ready" and self.world_clicks <= self.lose_world_clicks:
            return None
        text = ""
        if name == "chat.received":
            if "BISHOP" in extra.get("regex", ""):
                text = "Spawned 1 BISHOP TrainingBot(s) lv120 pinned to this map (104040000); they start grinding here" \
                       " in ~7-10s. cids [1000000777]"
            else:
                text = "Bot Bishbot is now a FollowerBot following YOU."
        return {"name": name, "detail": "ok", "text": text, "utc": "2026-10-31T03:12:10.000Z"}

    def character(self, alias):
        return {"char_id": 31, "char_name": "AqClient", "build": 10701, "release": "1.6.2-qa", "caps": "0x80000001",
                "channel": 3}

    def burst(self, alias, fps, ms, label):
        self.bursts.append((alias, fps, ms, label))

    def capture(self, alias, kind, cap_id, spec, start):
        self.captures.append((alias, kind, cap_id, start))

    def pull(self, dest, labels=None):
        self.pulled = Path(dest)
        for cap_id in (labels or {}).values():   # the run tells the worker which label is which capture
            frames = Path(dest) / 'frames' / cap_id
            frames.mkdir(parents=True, exist_ok=True)
            (frames / '0001.png').write_bytes(b'png')

    def close(self):
        pass


def clock():
    return datetime.datetime(2026, 10, 31, 3, 12, 5, 120000, tzinfo=datetime.timezone.utc)


class OrchestratorTest(unittest.TestCase):

    def run_b140(self, **worker_args):
        feed = FakeFeed()
        worker = FakeWorker(feed, **worker_args)
        tmp = tempfile.mkdtemp()
        run = qarunner.Run(SCRIPT, feed, worker, runs_root=tmp, commit="d4f103b0e", clock=clock, sleep=lambda s: None)
        return run, run.run(), feed, worker

    def test_b140_plays_through_and_writes_the_run_folder(self):
        run, m, feed, worker = self.run_b140()
        self.assertEqual("PASS", m["result"], json.dumps(m["steps"], indent=1))
        self.assertEqual(Path(run.folder).name, "b140-bot-heal-column-20261031T031205Z")
        self.assertTrue(str(run.folder).endswith("2026-10-31/b140-bot-heal-column-20261031T031205Z"))
        self.assertEqual("d4f103b0e", m["server"]["commit"])
        self.assertEqual("1.6.2-qa", m["clients"]["me"]["release"])
        # placeholders: the bound bot id and the learned character name went into the GM lines
        self.assertIn(("me", "gm", "!bot followbot 1000000777"), worker.sent)
        self.assertIn(("me", "gm", "!hurt AqClient 40"), worker.sent)
        heal = next(s for s in m["steps"] if s["id"] == "heal")
        self.assertEqual("server_line", heal["signal"]["type"])
        self.assertTrue(heal["signal"]["line"].startswith("PARTY HEAL Bishbot -> AqClient"))
        self.assertEqual("hurt", heal["armed_at"])
        # bursts: one on the hurt step, one on the heal signal
        labels = [b[3] for b in worker.bursts]
        # Short worker labels (stamp-index-capture, <= 40 chars); the old <run id>-<capture> was 50+ and refused.
        self.assertEqual(["20261031T031205Z-0-column-early", "20261031T031205Z-1-column-on-line"], labels)
        self.assertEqual("PASS", m["checks"][0]["result"])
        on_disk = json.loads((run.folder / "manifest.json").read_text())
        self.assertEqual(m["run_id"], on_disk["run_id"])
        self.assertTrue((run.folder / "script.yaml").exists())
        self.assertEqual(1, len((run.folder / "server/feed.jsonl").read_text().splitlines()))

    def test_the_manifest_matches_its_schema(self):
        _, m, _, _ = self.run_b140()
        schema = json.loads((REPO / "docs/qa/client-scripts/manifest.schema.json").read_text())
        errors = qarunner.qascript.SchemaChecker(schema).errors(m, schema)
        self.assertEqual([], errors)

    def test_the_topics_are_armed_before_the_first_step(self):
        _, _, feed, _ = self.run_b140()
        self.assertEqual((0, (), ("COMBAT",), 0), feed.polls[0])

    def test_a_lost_world_click_is_sent_again(self):
        _, m, _, worker = self.run_b140(lose_world_clicks=1)
        self.assertEqual("PASS", m["result"])
        self.assertEqual(2, worker.world_clicks)
        self.assertEqual(1, next(s for s in m["steps"] if s["id"] == "channels")["retries"])

    def test_a_position_answer_is_kept_in_the_step_record(self):
        # QA, lpq-stage1-walk-20261108T150439Z: the position readings were only in the worker outbox on tarkov2.
        run, m, feed, worker = self.run_b140()
        answer = '{"map_id": 922010100, "x": -120, "y": 130, "foothold": true, "rope": false, "ladder": false}'
        worker.send = lambda alias, step: {"result": "ok", "detail": answer}
        run.play(910, {"do": "position"})
        self.assertEqual(answer, run.manifest["steps"][-1]["detail"])
        run.play(911, {"do": "walk_to", "x": -117})
        self.assertEqual(answer, run.manifest["steps"][-1]["detail"])

    def test_a_counted_pickup_keeps_every_pick_and_a_short_count_follows_optional(self):
        # LPQ Part 2: pickup_item count N; the evidence per pick stays in the step record.
        run, m, feed, worker = self.run_b140()
        picks = ", ".join('{"oid": %d, "x": %d, "y": 130, "distance": %d, "ok": true}' % (500 + k, k, k)
                          for k in range(6))
        answer = '{"picked": 6, "item": 4001022, "picks": [%s]}' % picks
        worker.send = lambda alias, step: {"result": "ok", "detail": answer}
        run.play(920, {"do": "pickup_item", "item": 4001022, "count": 6})
        self.assertEqual(answer, run.manifest["steps"][-1]["detail"], "all six picks, not cut at 200 characters")
        worker.send = lambda alias, step: {"result": "refused", "detail": "picked 2 of 3, then no_drop | picks [...]"}
        run.play(921, {"do": "pickup_item", "item": 4001022, "count": 3, "optional": True})
        self.assertEqual("tolerated", run.manifest["steps"][-1]["result"])
        self.assertIn("picked 2 of 3, then no_drop", run.manifest["steps"][-1]["detail"])
        with self.assertRaises(qarunner.RunFailed):
            run.play(922, {"do": "pickup_item", "item": 4001022, "count": 3})
        self.assertEqual("refused", run.manifest["steps"][-1]["result"])

    def test_an_optional_pickup_that_is_refused_is_tolerated_and_a_required_one_fails(self):
        # 8 Nov lpq-stage1-handin-20261108T053234Z: the pickup lost the pass drop to a collector bot (taken_by_other)
        # and the whole run failed; a loop that picks up whatever has landed must go on.
        run, m, feed, worker = self.run_b140()

        def refuse(i, step, alias, entry):
            raise qarunner.RunFailed("pickup_item refused: taken_by_other (picker 1000000102)", "refused")
        run.do = refuse
        run.play(900, {"do": "pickup_item", "item": 4001022, "optional": True})
        self.assertEqual("tolerated", run.manifest["steps"][-1]["result"])
        self.assertIn("taken_by_other", run.manifest["steps"][-1]["detail"])
        before = run.manifest.get("failed_at")
        with self.assertRaises(qarunner.RunFailed):
            run.play(901, {"do": "pickup_item", "item": 4001022})
        self.assertEqual("refused", run.manifest["steps"][-1]["result"])
        self.assertEqual(901, run.manifest["failed_at"])
        self.assertNotEqual(before, 901)
        with self.assertRaises(qarunner.RunFailed):   # optional is for pickups only
            run.play(902, {"do": "gm", "line": "!heal", "optional": True})

    def test_a_retry_may_resend_a_list_of_steps_in_order(self):
        text = SCRIPT.read_text().replace("retry: {step: pick-world, every_ms: 4000, times: 4}",
                                          "retry: {steps: [pick-world, pick-world], every_ms: 4000, times: 4}")
        self.assertIn("steps: [pick-world, pick-world]", text)
        alt = Path(tempfile.mkdtemp()) / SCRIPT.name
        alt.write_text(text)
        feed = FakeFeed()
        worker = FakeWorker(feed, lose_world_clicks=1)
        run = qarunner.Run(alt, feed, worker, runs_root=tempfile.mkdtemp(), commit="d4f103b0e", clock=clock,
                           sleep=lambda s: None)
        m = run.run()
        self.assertEqual("PASS", m["result"])
        self.assertEqual(3, worker.world_clicks)   # the first click, then both listed steps once
        self.assertEqual(1, next(s for s in m["steps"] if s["id"] == "channels")["retries"])

    def test_a_resent_gm_step_has_its_placeholders_filled(self):
        text = SCRIPT.read_text().replace("retry: {step: pick-world, every_ms: 4000, times: 4}",
                                          "retry: {steps: [hurt-again], every_ms: 4000, times: 4}")
        # a gm step with ${me.name} placed before the wait, resent by it
        text = text.replace("  - {id: channels, do: wait,", "  - {id: hurt-again, do: gm, line: \"!hurt ${me.name} 1\"}\n  - {id: channels, do: wait,", 1)
        alt = Path(tempfile.mkdtemp()) / SCRIPT.name
        alt.write_text(text)
        feed = FakeFeed()
        worker = FakeWorker(feed, lose_world_clicks=1)
        run = qarunner.Run(alt, feed, worker, runs_root=tempfile.mkdtemp(), commit="d4f103b0e", clock=clock,
                           sleep=lambda s: None)
        run.run()
        lines = [e[2] for e in worker.sent if len(e) == 3 and e[1] == "gm" and e[2].startswith("!hurt")]
        self.assertGreaterEqual(len(lines), 2, lines)   # the step itself and its resend
        self.assertFalse(any("${" in l for l in lines), lines)

    def test_a_missing_signal_fails_the_run_at_that_step(self):
        feed = FakeFeed()
        worker = FakeWorker(feed, missing={"charselect.ready"})
        run = qarunner.Run(SCRIPT, feed, worker, runs_root=tempfile.mkdtemp(), commit="d4f103b0e", clock=clock,
                           sleep=lambda s: None)
        run.doc["steps"][5]["timeout_ms"] = 500   # the charselect wait
        m = run.run()
        self.assertEqual("FAIL", m["result"])
        self.assertEqual(5, m["failed_at"])
        self.assertEqual("timeout", m["steps"][5]["result"])
        self.assertEqual("not run", m["steps"][-1]["result"])

    def test_a_server_line_from_before_the_arming_step_does_not_count(self):
        feed = FakeFeed()
        feed.add("COMBAT", "PARTY HEAL Old -> AqClient from an earlier run", [31])

        class NoHealWorker(FakeWorker):
            def send(self, alias, step):
                if step["do"] == "gm" and step["line"].startswith("!hurt"):
                    self.sent.append((alias, step["do"], step.get("line")))
                    return {"result": "ok"}   # no bot heals this time
                return super().send(alias, step)

        run = qarunner.Run(SCRIPT, feed, NoHealWorker(feed), runs_root=tempfile.mkdtemp(), commit="d4f103b0e",
                           clock=clock, sleep=lambda s: None)
        heal = next(s for s in run.doc["steps"] if s.get("id") == "heal")
        heal["timeout_ms"] = 500
        m = run.run()
        self.assertEqual("FAIL", m["result"])
        self.assertIn("timed out waiting for", next(s for s in m["steps"] if s["id"] == "heal")["detail"])

    def test_a_pull_that_fails_marks_the_captures_missing_instead_of_pointing_at_nothing(self):
        feed = FakeFeed()

        class NoPull(FakeWorker):
            def pull(self, dest, labels=None):
                raise qarunner.RunFailed('the copy of frames/column-early failed', 'error')

        run = qarunner.Run(SCRIPT, feed, NoPull(feed), runs_root=tempfile.mkdtemp(), commit='d4f103b0e', clock=clock,
                           sleep=lambda s: None)
        m = run.run()
        self.assertEqual('PASS', m['result'])   # the steps did pass: only the copy failed
        self.assertIn('the copy of frames/column-early failed', m['runner']['pull'])
        paths = [cap['path'] for cap in m['captures']]
        self.assertTrue(any(path.startswith('frames/') for path in paths))
        self.assertEqual({'missing'}, {cap['result'] for cap in m['captures'] if cap['path'].startswith(('frames/',
                                                                                                        'client/'))})

    def test_a_finished_run_keeps_the_frames_the_worker_pulled(self):
        run, m, _, _ = self.run_b140()
        for cap in ('column-early', 'column-on-line'):
            self.assertTrue((run.folder / 'frames' / cap / '0001.png').exists(), cap)
        self.assertEqual(['ok', 'ok'], [cap['result'] for cap in m['captures'] if cap['kind'] == 'burst'])

    def test_the_feed_secret_is_read_on_the_test_box_by_path(self):
        cmd = qarunner.SshFeed().remote_command("since=0&topics=COMBAT")
        self.assertIn("$(cat /etc/slums-inspect-test/secret)", cmd)
        self.assertIn("-H @-", cmd)
        self.assertIn("http://127.0.0.1:8198/qa/events?since=0", cmd)

    def test_a_script_that_does_not_validate_is_refused(self):
        bad = Path(tempfile.mkdtemp()) / "bad.yaml"
        bad.write_text("id: bad\n")
        with self.assertRaises(qarunner.RunFailed):
            qarunner.Run(bad, FakeFeed(), FakeWorker(FakeFeed()), runs_root=tempfile.mkdtemp())

    def test_every_repo_script_has_a_plan(self):
        for f in sorted((REPO / "qa-scripts").glob("*.yaml")):
            self.assertIn("script ", qarunner.plan(f))


class TestHostTest(unittest.TestCase):
    """o03: the orchestrator talks to TEST on its own box (mapletest), never to live, through testhost.py."""

    def setting(self, value):
        environ = {k: v for k, v in os.environ.items() if k != 'SLUMS_TEST_HOST'}
        if value is not None:
            environ['SLUMS_TEST_HOST'] = value
        return mock.patch.dict(os.environ, environ, clear=True)

    def ssh(self, env):
        name = env.get('SLUMS_TEST_HOST')
        with self.setting(name):
            return qarunner.test_ssh('cmd')

    def test_the_default_box_is_mapletest_since_the_cutover(self):
        # o03: TEST moved to mapletest (192.168.0.111) on 2026-11-01
        argv = self.ssh({})
        self.assertEqual(argv[-2:], ['root@192.168.0.111', 'cmd'])

    def test_mapletest_is_reached_with_the_test_key(self):
        argv = self.ssh({'SLUMS_TEST_HOST': '192.168.0.111'})
        self.assertEqual(argv[-2:], ['root@192.168.0.111', 'cmd'])
        self.assertIn(os.path.expanduser('~/.ssh/id_slums_lxc'), argv)

    def test_the_old_shared_box_keeps_ssh_default_key(self):
        argv = self.ssh({'SLUMS_TEST_HOST': '192.168.0.164'})
        self.assertEqual(argv[-2:], ['root@192.168.0.164', 'cmd'])
        self.assertNotIn('-i', argv)

    def test_a_box_outside_the_allowlist_stops_every_ssh_path(self):
        for refused in ('mapletest', 'slumsms.asslorde.com', '10.0.0.9', ''):
            with self.setting(refused):
                with self.assertRaises(qarunner.TestHostRefused, msg=refused) as caught:
                    qarunner.test_host()
                self.assertIn(repr(refused), str(caught.exception), refused)
                with self.assertRaises(qarunner.TestHostRefused, msg=refused):
                    qarunner.test_ssh('cmd')
                with self.assertRaises(qarunner.TestHostRefused, msg=refused):
                    qarunner.SshFeed()
                with self.assertRaises(qarunner.TestHostRefused, msg=refused):
                    qarunner.read_deployed_commit(runner=lambda *a, **k: None)

    def test_a_refused_box_stops_the_run_before_it_touches_anything(self):
        with self.setting('slumsms.asslorde.com'), contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(2, qarunner.main([str(SCRIPT), '--dry-run']))
        self.assertIn('slumsms.asslorde.com', err.getvalue())

    def test_the_deployed_commit_is_read_on_the_test_box(self):
        calls = []

        class Runner:
            def __call__(self, argv, **kwargs):
                calls.append(argv)

                class Done:
                    returncode = 0
                    stdout = 'e3050a1d9\n'
                    stderr = ''
                return Done()

        with self.setting('192.168.0.111'):
            commit = qarunner.read_deployed_commit(runner=Runner())
        self.assertEqual(commit, 'e3050a1d9')
        self.assertEqual(calls[-1][-2], 'root@192.168.0.111')
        self.assertEqual(calls[-1][-1], 'cat /opt/mapleroot-dev/persistence-test/Server/DEPLOYED_COMMIT')

    def test_the_feed_polls_on_the_test_box(self):
        calls = []

        class Runner:
            def __call__(self, argv, **kwargs):
                calls.append(argv)

                class Done:
                    returncode = 0
                    stdout = '{"ok": true}'
                    stderr = ''
                return Done()

        with self.setting('192.168.0.111'):
            feed = qarunner.SshFeed(runner=Runner())
            feed.poll(0, [], ['COMBAT'], wait=1)
        self.assertEqual('192.168.0.111', feed.host)
        self.assertIn('root@192.168.0.111', calls[-1])
        self.assertIn(os.path.expanduser('~/.ssh/id_slums_lxc'), calls[-1])


class WrapperTest(unittest.TestCase):
    """tools/qa-runner/run is how the lane starts a script: `python3 -I`, which drops the script's own directory."""

    def wrapper(self, setting):
        environ = {k: v for k, v in os.environ.items() if k != 'SLUMS_TEST_HOST'}
        environ['SLUMS_TEST_HOST'] = setting
        return subprocess.run([str(REPO / 'tools/qa-runner/run'), str(SCRIPT), '--dry-run'],
                              capture_output=True, text=True, timeout=120, env=environ)

    def test_the_wrapper_plans_a_run_on_the_test_box(self):
        done = self.wrapper('192.168.0.111')
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn('script b140-bot-heal-column', done.stdout)

    def test_the_wrapper_refuses_a_box_outside_the_allowlist(self):
        done = self.wrapper('slumsms.asslorde.com')
        self.assertEqual(2, done.returncode, done.stdout)
        self.assertIn("SLUMS_TEST_HOST='slumsms.asslorde.com'", done.stderr)


if __name__ == "__main__":
    unittest.main()


class BurstLabelTest(unittest.TestCase):
    """The worker takes burst labels of 1-40 [A-Za-z0-9_-]; long script ids broke every burst (b008, 2026-11-01)."""

    def test_a_long_script_id_still_gives_a_valid_unique_label(self):
        import re
        ok = re.compile(r'^[A-Za-z0-9_-]{1,40}$')
        run_id = "b008-will-spawns-on-the-ground-20261101T174418Z"
        labels = [qarunner.burst_label(run_id, i, cap) for i, cap in
                  enumerate(["boss-appears", "boss-standing", "after", "a-very-long-capture-id-that-goes-on-and-on"])]
        for label in labels:
            self.assertRegex(label, ok)
        self.assertEqual(len(labels), len(set(labels)))
        self.assertIn("20261101T174418Z", labels[0])

