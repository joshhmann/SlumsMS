"""Tests for the 187 orchestrator (ticket 170). Run: python3 -m unittest discover -s tools/qa-runner -p 'test_*.py'"""
import datetime
import json
import sys
import tempfile
import unittest
from pathlib import Path

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
        self.assertEqual(["b140-bot-heal-column-20261031T031205Z-column-early",
                          "b140-bot-heal-column-20261031T031205Z-column-on-line"], labels)
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

    def test_the_feed_secret_is_read_on_164_by_path(self):
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


if __name__ == "__main__":
    unittest.main()
