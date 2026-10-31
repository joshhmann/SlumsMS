"""Tests for the run-folder verdict judge (ticket 166, b222 rules). Run: python3 -m unittest discover -s tools/qa-runner"""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qaverdict  # noqa: E402
import qarunner  # noqa: E402
import test_qarunner  # noqa: E402

SCRIPT = """id: t-cl-1-rope-no-box
covers: [t-cl-1]
status: draft
steps:
  - {do: login}
expect:
  reviewer:
    - see: "No rope or ladder has a box drawn around it"
      in: [arrive]
  checks:
    - {check: client_alive}
"""

MANIFEST = {
    "format": 1,
    "run_id": "t-cl-1-rope-no-box-20261031T031205Z",
    "script": {"id": "t-cl-1-rope-no-box", "status": "draft", "covers": ["t-cl-1"]},
    "server": {"host": "TEST", "commit": "5e5c0ab1d", "commit_source": "Server/DEPLOYED_COMMIT"},
    "clients": {"me": {"build": 10701, "char_id": 31}},
    "runner": {"orchestrator": "tools/qa-runner 1", "host": "187"},
    "trigger": "manual by a test", "started_utc": "2026-10-31T03:12:05.120Z",
    "finished_utc": "2026-10-31T03:14:40.900Z", "result": "PASS", "failed_at": None,
    "steps": [{"index": 0, "id": None, "do": "login", "result": "ok"}],
    "captures": [{"id": "arrive", "kind": "burst", "path": "frames/arrive/", "frames": 6}],
    "checks": [{"index": 0, "check": "client_alive", "result": "SKIP", "detail": "judged from the client files"}],
    "findings": [],
}


class RunFolderTest(unittest.TestCase):

    def folder(self, script=SCRIPT, manifest=None, review=None, files=(), **changes):
        tmp = Path(tempfile.mkdtemp())
        text = script
        (tmp / "script.yaml").write_text(text, encoding="utf-8")
        m = json.loads(json.dumps(MANIFEST))
        m["script"]["sha256"] = qaverdict.sha256_of(tmp / "script.yaml")
        for k, v in changes.items():
            if k == "checks":
                m["checks"] = v
            elif k == "covers":
                m["script"]["covers"] = v
            elif k == "commit":
                m["server"]["commit"] = v
            else:
                m[k] = v
        if review is not None:
            m["review"] = review
        for rel in files:
            p = tmp / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("x", encoding="utf-8")
        (tmp / "manifest.json").write_text(json.dumps(m), encoding="utf-8")
        return tmp

    def judge(self, deployed_commit=None, **kw):
        return qaverdict.judge(self.folder(**kw), deployed_commit)[0]

    # -- the b222 rules --

    def test_a_wait_that_ran_out_is_a_fail(self):
        r = self.judge(result="FAIL", failed_at=4, steps=[
            {"index": 3, "id": "channels", "do": "wait", "result": "ok"},
            {"index": 4, "id": "in-game", "do": "wait", "result": "timeout", "detail": "no field.ready in 60000 ms"}])
        self.assertEqual("FAIL", r["status"])
        self.assertIn("step 4 in-game", r["reason"])
        self.assertIn("no field.ready", r["reason"])

    def test_a_machine_pass_is_enough_without_a_reviewer_item(self):
        script = SCRIPT.replace("  reviewer:\n    - see: \"No rope or ladder has a box drawn around it\"\n"
                                "      in: [arrive]\n", "")
        r = self.judge(script=script,
                       checks=[{"index": 0, "check": "server_line", "result": "PASS", "detail": "1 line"}])
        self.assertEqual("PASS", r["status"])
        self.assertIn("server_line", r["reason"])

    def test_no_machine_check_and_no_reviewer_item_is_not_a_pass(self):
        script = SCRIPT.replace("  reviewer:\n    - see: \"No rope or ladder has a box drawn around it\"\n"
                                "      in: [arrive]\n", "")
        r = self.judge(script=script)
        self.assertEqual("SKIP", r["status"])
        self.assertIn("proves nothing", r["reason"])

    def test_frames_without_a_recorded_review_are_not_a_pass(self):
        r = self.judge()
        self.assertEqual("SKIP", r["status"])
        self.assertIn("no recorded review", r["reason"])

    def test_a_recorded_review_that_saw_the_item_is_a_pass(self):
        r = self.judge(review={"by": "an agent", "verdict": "PASS",
                               "reviewer_items": [{"index": 0, "result": "seen", "evidence": ["frames/arrive/0003.png"]}]})
        self.assertEqual("PASS", r["status"])
        self.assertIn("1 frame item", r["reason"])

    def test_a_review_that_missed_the_item_is_not_a_pass(self):
        r = self.judge(review={"verdict": "PASS", "reviewer_items": [{"index": 0, "result": "not seen"}]})
        self.assertEqual("SKIP", r["status"])
        self.assertIn("not marked seen", r["reason"])

    def test_a_failed_review_fails_the_run(self):
        r = self.judge(review={"verdict": "FAIL", "summary": "a box is drawn around the rope",
                               "reviewer_items": [{"index": 0, "result": "not seen"}]})
        self.assertEqual("FAIL", r["status"])
        self.assertIn("box is drawn", r["reason"])

    def test_an_inconclusive_review_is_never_a_pass(self):
        r = self.judge(review={"verdict": "INCONCLUSIVE", "reviewer_items": [{"index": 0, "result": "unclear"}]})
        self.assertEqual("SKIP", r["status"])

    def test_a_failed_machine_check_is_a_fail(self):
        r = self.judge(checks=[{"index": 0, "check": "client_alive", "result": "FAIL", "detail": "no heartbeat"}])
        self.assertEqual("FAIL", r["status"])
        self.assertIn("client_alive", r["reason"])

    def test_a_broken_run_is_not_judged(self):
        for result in ("ERROR", "ABORTED", "RUNNING"):
            r = self.judge(result=result)
            self.assertEqual("SKIP", r["status"], result)
            self.assertTrue(r["reason"], result)

    def test_a_script_edited_after_the_run_is_not_judged(self):
        folder = self.folder()
        (folder / "script.yaml").write_text(SCRIPT + "\nnotes: edited after the run\n", encoding="utf-8")
        r = qaverdict.judge(folder)[0]
        self.assertEqual("SKIP", r["status"])
        self.assertIn("sha256", r["reason"])

    # -- freshness and the record shape --

    def test_a_run_on_another_build_is_not_fresh(self):
        r = self.judge(review={"verdict": "PASS", "reviewer_items": [{"index": 0, "result": "seen"}]},
                       commit="5e5c0ab1d", deployed_commit="d4f103b0e")
        self.assertFalse(r["fresh"])
        self.assertEqual("d4f103b0e", r["deployed_commit"])

    def test_a_run_on_this_build_is_fresh_and_an_unknown_build_is_unasked(self):
        review = {"verdict": "PASS", "reviewer_items": [{"index": 0, "result": "seen"}]}
        self.assertTrue(self.judge(review=review, deployed_commit="5e5c0ab1d")["fresh"])
        self.assertIsNone(self.judge(review=review)["fresh"])

    def test_the_record_carries_the_evidence_the_release_book_needs(self):
        r = self.judge(review={"verdict": "PASS", "reviewer_items": [{"index": 0, "result": "seen"}]},
                       files=("frames/arrive/0001.png", "client/me/chat.log", "server/journal.txt",
                              "server/deployed-commit.txt", "review.md"))
        ev = r["evidence"]
        self.assertEqual(str(Path(r["run"]["folder"])), ev[0])
        for want in ("frames/arrive/", "client/me/chat.log", "server/journal.txt", "server/deployed-commit.txt",
                     "script.yaml", "review.md"):
            self.assertIn(want, ev)
        self.assertEqual("5e5c0ab1d", r["commit"])
        self.assertEqual("t-cl-1-rope-no-box", r["script"]["id"])
        self.assertEqual("t-cl-1", r["id"])

    def test_every_covered_t_id_gets_the_same_verdict(self):
        records = qaverdict.judge(self.folder(covers=["b140", "q48", "t-su-1"]))
        self.assertEqual(["t-su-1"], [r["id"] for r in records])

    def test_a_script_covering_no_release_id_is_an_error(self):
        with self.assertRaises(ValueError):
            qaverdict.judge(self.folder(covers=["b140"]))

    # -- a folder the orchestrator really wrote --

    def test_a_real_orchestrator_folder_is_judged(self):
        feed = test_qarunner.FakeFeed()
        worker = test_qarunner.FakeWorker(feed)
        tmp = tempfile.mkdtemp()
        run = qarunner.Run(test_qarunner.SCRIPT, feed, worker, runs_root=tmp, commit="d4f103b0e",
                           clock=test_qarunner.clock, sleep=lambda s: None)
        manifest = run.run()
        self.assertEqual("PASS", manifest["result"])
        # the frames still need a reviewer before the run is a verdict (b222)
        self.assertEqual("SKIP", qaverdict.judge(run.folder, "d4f103b0e")[0]["status"])
        manifest["review"] = {"by": "an agent", "verdict": "PASS",
                             "reviewer_items": [{"index": 0, "result": "seen"}, {"index": 1, "result": "seen"}]}
        (run.folder / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        r = qaverdict.judge(run.folder, deployed_commit="d4f103b0e")[0]
        # b140's covers name t-su-1; its one machine check (server_line) passed
        self.assertEqual("t-su-1", r["id"])
        self.assertEqual("PASS", r["status"])
        self.assertTrue(r["fresh"])

    def test_the_orchestrator_writes_the_verdicts_beside_the_manifest_and_publishes_them(self):
        feed = test_qarunner.FakeFeed()
        run = qarunner.Run(test_qarunner.SCRIPT, feed, test_qarunner.FakeWorker(feed),
                           runs_root=tempfile.mkdtemp(), commit="d4f103b0e",
                           clock=test_qarunner.clock, sleep=lambda s: None)
        run.run()
        publish = Path(tempfile.mkdtemp())
        with contextlib.redirect_stdout(io.StringIO()):
            records = qarunner.write_verdicts(run.folder, "d4f103b0e", publish)
        self.assertEqual(["t-su-1"], [r["id"] for r in records])
        self.assertEqual("SKIP", records[0]["status"])  # b140 has reviewer items and no review yet
        for out in (run.folder / "verdicts", publish):
            written = json.loads((out / "t-su-1.json").read_text())
            self.assertEqual("t-su-1", written["id"])
            self.assertEqual("SKIP", written["status"])

    def test_a_folder_covering_no_release_id_publishes_nothing(self):
        folder = self.folder(covers=["b140"])
        out = Path(tempfile.mkdtemp())
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.assertEqual([], qarunner.write_verdicts(folder, "5e5c0ab1d", out))
        self.assertIn("no t-id verdict", buf.getvalue())
        self.assertEqual([], list(out.iterdir()))

    # -- the command line --

    def test_the_cli_writes_one_file_per_id_and_exits_nonzero_without_a_pass(self):
        folder = self.folder()
        out = Path(tempfile.mkdtemp()) / "client-verdicts"
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = qaverdict.main([str(folder), "--out", str(out), "--deployed-commit", "5e5c0ab1d"])
        self.assertEqual(1, code)
        written = json.loads((out / "t-cl-1.json").read_text())
        self.assertEqual("SKIP", written["status"])
        self.assertEqual("t-cl-1", written["id"])
        self.assertEqual(1, len(buf.getvalue().splitlines()))

    def test_the_cli_exits_zero_on_a_pass(self):
        folder = self.folder(review={"verdict": "PASS", "reviewer_items": [{"index": 0, "result": "seen"}]},
                             files=("frames/arrive/0001.png",))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, qaverdict.main([str(folder), "--quiet"]))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, qaverdict.main([str(folder)]))


if __name__ == "__main__":
    unittest.main()
