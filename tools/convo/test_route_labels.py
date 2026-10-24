"""Offline tests for route_labels.py (no network): python3 -m unittest discover -s tools/convo -p 'test_*.py'"""
import io
import json
import pathlib
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

import route_labels as rl

REC = {"at": 1, "map": 100000000, "player": "ab12", "line": "wait who wiped?", "step": "3-reply-thread",
       "chosen": "thread:9", "features": {"question": True, "len": 15, "entities": []},
       "candidates": [{"key": "Kimbap", "dist": 150, "said": "selling ilbis", "bubble": True},
                      {"key": "thread:9", "dist": 220, "said": "gossip", "bubble": True}],
       "context": [{"who": "Zephyrr", "bot": True, "ago": 12, "text": "we wiped at zakum lol"}]}


class RouteLabelsTest(unittest.TestCase):

    def test_labels_outside_the_candidates_are_dropped(self):
        batch = [(1, REC), (2, dict(REC, at=2))]
        got = rl.parse_labels('{"i": 1, "label": "thread:9"}\n{"i": 2, "label": "Someone"}', batch)
        self.assertEqual({1: "thread:9"}, got)

    def test_score_compares_the_servers_choice_with_the_labels(self):
        other = dict(REC, at=2, chosen="Kimbap", step="4-closest")
        labels = {rl.record_id(REC): "thread:9", rl.record_id(other): "thread:9"}
        hit, total, by_step = rl.score([REC, other], labels)
        self.assertEqual((1, 2), (hit, total))
        self.assertEqual([0, 1], by_step["4-closest"])

    def test_the_budget_stops_the_run_before_any_call_it_cannot_afford(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "2026-10-23.jsonl"
            f.write_text("\n".join(json.dumps(dict(REC, at=i)) for i in range(30)) + "\n")
            calls = []

            def fake(*a, **k):
                calls.append(1)
                return '{"i": 1, "label": "thread:9"}', {"prompt_tokens": 1000, "completion_tokens": 50}

            out = io.StringIO()
            with mock.patch.object(rl, "call", fake), mock.patch.object(rl.time, "sleep", lambda s: None), \
                    redirect_stdout(out):
                rl.main([str(f), "--base", "http://x", "--model", "m", "--budget", "0.004", "--batch", "5"])
            self.assertEqual(2, len(calls), out.getvalue())   # each call: ~$0.0021 estimated, $0.0012 real
            self.assertIn("budget: stopping", out.getvalue())
            self.assertIn("current rule matches 2/2", out.getvalue())
            self.assertTrue((pathlib.Path(d) / "labels.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
