"""The Silvia-local entry point (silvia_local.py): a client-only script plays with no TEST feed, and a script that needs
the feed is refused up front. Fakes only: no ssh, no Silvia."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qarunner  # noqa: E402
import silvia_local  # noqa: E402
from test_qaworker import FakeTransport  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
LOCAL = REPO / "qa-scripts" / "qa-b286-batched-ready-local.yaml"
WITH_FEED = REPO / "qa-scripts" / "qa-b286-batched-ready.yaml"


class SilviaLocalTest(unittest.TestCase):
    def test_null_feed_has_no_lines_and_keeps_the_position(self):
        answer = silvia_local.NullFeed().poll(7, [], ["QA_RUN"], None, 1)
        self.assertEqual([], answer["lines"])
        self.assertEqual(7, answer["next"])

    def test_client_only_script_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = silvia_local.build_run(str(LOCAL), tmp, "unused", transport=FakeTransport())
            self.assertEqual("qa-b286-batched-ready-local", run.doc["id"])
            self.assertIn("silvia", run.manifest["runner"]["host"])

    def test_script_that_needs_the_server_feed_is_refused_and_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(qarunner.RunFailed) as ctx:
                silvia_local.build_run(str(WITH_FEED), tmp, "unused", transport=FakeTransport())
            self.assertIn("on-map", str(ctx.exception))

    def test_local_variant_has_no_server_dependency(self):
        import yaml
        doc = yaml.safe_load(LOCAL.read_text(encoding="utf-8"))
        self.assertEqual([], silvia_local.server_dependencies(doc))

    def test_default_transport_is_the_local_queue(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = silvia_local.build_run(str(LOCAL), tmp, r"Z:\x\client-mcp")
            transport = run.worker.transport
            self.assertFalse(transport.wrap)
            self.assertEqual(r"Z:\x\client-mcp", transport.root)
            self.assertEqual(sys.executable, transport.remote()[0])


if __name__ == "__main__":
    unittest.main()
