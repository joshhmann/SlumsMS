"""Every file in samples/valid validates, every file in samples/invalid is rejected.
Needs the 'jsonschema' package; skipped when it is not installed."""
import json
import unittest
from pathlib import Path

try:
    import jsonschema
except ImportError:  # pragma: no cover
    jsonschema = None

HERE = Path(__file__).parent


@unittest.skipIf(jsonschema is None, "jsonschema is not installed")
class SchemaSamples(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = json.loads((HERE / "bot-action-event.schema.json").read_text())
        jsonschema.Draft202012Validator.check_schema(schema)
        cls.v = jsonschema.Draft202012Validator(schema)

    def test_valid_samples_pass(self):
        files = sorted((HERE / "samples" / "valid").glob("*.json"))
        self.assertTrue(files)
        for p in files:
            with self.subTest(p.name):
                errs = list(self.v.iter_errors(json.loads(p.read_text())))
                self.assertEqual([], [e.message for e in errs])

    def test_invalid_samples_fail(self):
        files = sorted((HERE / "samples" / "invalid").glob("*.json"))
        self.assertTrue(files)
        for p in files:
            with self.subTest(p.name):
                self.assertTrue(list(self.v.iter_errors(json.loads(p.read_text()))))

    def test_every_state_has_a_valid_sample(self):
        states = {json.loads(p.read_text())["state"] for p in (HERE / "samples" / "valid").glob("*.json")}
        self.assertEqual({"PROPOSED", "REJECTED", "ADMITTED", "RUNNING", "COMPENSATING", "SUCCEEDED",
                          "FAILED", "INTERRUPTED", "TIMED_OUT"}, states)


if __name__ == "__main__":
    unittest.main()
