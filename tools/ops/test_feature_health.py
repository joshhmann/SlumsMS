#!/usr/bin/env python3
"""Tests for tools/ops/feature-health. Local only: fixture JSON, no ssh.

    python3 -m unittest tools/ops/test_feature_health.py -v      # or: python3 tools/ops/test_feature_health.py
"""
import importlib.machinery, importlib.util, json, os, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_l = importlib.machinery.SourceFileLoader("feature_health", os.path.join(HERE, "feature-health"))
fh = importlib.util.module_from_spec(importlib.util.spec_from_loader("feature_health", _l))
_l.exec_module(fh)

DOC = {"health": {"uptimeS": 7200, "heapUsedMb": 100, "heapMaxMb": 1000},
       "social": {"acts": {"counters": {"INVITE.ok": 10, "INVITE.failed": 2,
                                        "INVITE.failed: nothing to invite them to": 2, "decision.acted": 400}}},
       "switches": {"slums.x": "true"}}

def status(doc, rule, path="-"):
    return fh.check(doc, path, rule, doc["health"]["uptimeS"])[0]

class T(unittest.TestCase):
    def test_pass(self):
        self.assertEqual(status(DOC, "fail_rate(social/acts/counters/INVITE.ok, social/acts/counters/INVITE.failed) <= 0.3"), "OK")
        self.assertEqual(status(DOC, "ratio(health/heapUsedMb, health/heapMaxMb) <= 0.9"), "OK")
        self.assertEqual(status(DOC, "per_hour(social/acts/counters/decision.acted) >= 100"), "OK")
        self.assertEqual(status(DOC, "== true", "switches/slums.x"), "OK")

    def test_missing_is_silent_unless_optional(self):
        self.assertEqual(status(DOC, ">= 1", "nope/x"), "SILENT")
        self.assertEqual(status(DOC, "optional >= 1", "nope/x"), "OK")
        self.assertEqual(status(DOC, "per_hour(nope) >= 1"), "SILENT")

    def test_broken_ratio_fails(self):
        r = "fail_rate(social/acts/counters/INVITE.ok, social/acts/counters/INVITE.failed) <= 0.1"
        self.assertEqual(status(DOC, r), "FAILING")
        self.assertEqual(status(DOC, "slow: " + r), "SLOW")
        self.assertEqual(status(DOC, "silent: " + r), "SILENT")

    def test_keys_with_spaces_and_colons(self):
        self.assertEqual(fh.get(DOC, "social/acts/counters/INVITE.failed: nothing to invite them to"), (True, 2))
        r = "fail_rate(social/acts/counters/INVITE.ok, social/acts/counters/INVITE.failed: nothing to invite them to) <= 0.1"
        self.assertEqual(status(DOC, r), "FAILING")

    def test_groups_worst_first_and_exit_code(self):
        rows = [("a", "health/uptimeS", ">= 1", "", 1), ("b", "zip", ">= 1", "", 2), ("c", "health/uptimeS", "<= 1", "", 3)]
        g, up = fh.run(DOC, rows)
        self.assertEqual([x["status"] for x in g], ["FAILING", "SILENT", "OK"])
        self.assertEqual(up, 7200)

    def _main(self, args, doc=DOC):
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "live.json"); json.dump(doc, open(f, "w"))
            return fh.main(["--file", f] + args)

    def test_main_exit_codes(self):
        self.assertEqual(self._main(["--only", "server"]), 0)
        self.assertEqual(self._main(["--only", "bots"]), 0)

    def test_main_failing_exits_1(self):
        with tempfile.TemporaryDirectory() as d:
            r = os.path.join(d, "r.tsv"); open(r, "w").write("x\thealth/uptimeS\t<= 1\tn\\n".replace("\\\\n", "\n"))
            f = os.path.join(d, "live.json"); json.dump(DOC, open(f, "w"))
            self.assertEqual(fh.main(["--file", f, "--rules", r]), 1)

    def test_live_host_refused(self):
        for h in ("192.168.0.164", "live"):
            self.assertEqual(fh.main(["--host", h]), 2)
        self.assertTrue(fh.is_live("root@192.168.0.164"))
        self.assertFalse(fh.is_live("mapletest"))

if __name__ == "__main__":
    unittest.main()
