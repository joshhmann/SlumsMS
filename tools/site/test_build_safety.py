"""Release-night safety of the site build (1.6.2 website audit): python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402
from sections import market, world  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CONTRACT = os.path.join(HERE, "testdata", "market-contract")


class FullBuildTest(unittest.TestCase):
    """b175 / W2: a failed full build leaves the published site and never leaves an old tarball behind."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="slums-safety-")
        self.out, self.tar = os.path.join(self.tmp, "site"), os.path.join(self.tmp, "site.tar.gz")
        build.build(self.out, self.tar)
        with open(os.path.join(self.out, "marker.txt"), "w") as f:
            f.write("the published site")

    def tearDown(self):
        build.check.check_all = self._check if hasattr(self, "_check") else build.check.check_all
        shutil.rmtree(self.tmp)

    def test_a_failed_check_keeps_the_published_site_and_removes_the_old_tarball(self):
        self._check = build.check.check_all
        build.check.check_all = lambda *a, **k: ["index.html: something bad"]
        with self.assertRaises(SystemExit) as e:
            build.build(self.out, self.tar)
        self.assertTrue(os.path.isfile(os.path.join(self.out, "marker.txt")), "the old site is still there")
        self.assertIn("left as it was", str(e.exception))
        self.assertTrue(os.path.isfile(os.path.join(self.out, "index.html")))
        self.assertFalse(os.path.exists(self.tar), "no stale tarball to ship by mistake")
        self.assertEqual(sorted(os.listdir(self.tmp)), ["site"], "no half-built folder left beside it")

    def test_a_good_build_replaces_the_site(self):
        build.build(self.out, self.tar)
        self.assertFalse(os.path.exists(os.path.join(self.out, "marker.txt")))
        self.assertTrue(os.path.isfile(self.tar))


class BadExportTest(unittest.TestCase):
    """b177 / W4 and b178 / W5: a broken export is refused with a message, never a crash."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="slums-export-")
        self.m = os.path.join(self.tmp, "market")
        shutil.copytree(CONTRACT, self.m)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def edit(self, name, fn):
        path = os.path.join(self.m, name)
        with open(path) as f:
            data = json.load(f)
        fn(data)
        with open(path, "w") as f:
            json.dump(data, f)

    def refused(self, fn):
        try:
            m = fn()
        except SystemExit as e:
            return str(e)
        return "; ".join(market.validate(m))

    def test_a_truncated_or_odd_sales_file_is_refused(self):
        with open(os.path.join(self.m, "sales.json"), "w") as f:
            f.write('{"sales": [{"item_id": 1, "pri')
        self.assertIn("refused", self.refused(lambda: market.load(self.m)))
        with open(os.path.join(self.m, "sales.json"), "w") as f:
            f.write('[]')
        self.assertIn("should be an object", self.refused(lambda: market.load(self.m)))
        self.edit_sales_rows([{"item_id": 1}])  # no player_side or source: validate() names the row
        self.assertIn("row 0", self.refused(lambda: market.load(self.m)))

    def edit_sales_rows(self, rows):
        with open(os.path.join(self.m, "sales.json"), "w") as f:
            json.dump({"sales": rows}, f)

    def test_a_bad_time_is_refused_not_a_crash(self):
        self.edit("sales.json", lambda d: [r.update(hour="yesterday-ish") for r in d["sales"]])
        self.assertIn("isn't a time", self.refused(lambda: market.load(self.m)))
        self.assertEqual(market.when("garbage"), "")

    def test_world_refuses_a_broken_file_and_a_bad_feed_time(self):
        import subprocess
        data = os.path.join(self.tmp, "site-data")
        subprocess.run([sys.executable, os.path.join(HERE, "site_sample.py"), "--out", data], check=True, capture_output=True)
        path = os.path.join(data, "feed.json")
        with open(path) as f:
            feed = json.load(f)
        feed["items"][0]["t"] = "not a time"
        with open(path, "w") as f:
            json.dump(feed, f)
        self.assertTrue(any("feed.json" in p for p in world.validate(world.load(data))))
        with open(os.path.join(data, "world.json"), "w") as f:
            f.write('{"channels": [')
        with self.assertRaises(SystemExit) as e:
            world.load(data)
        self.assertIn("refused", str(e.exception))


if __name__ == "__main__":
    unittest.main()
