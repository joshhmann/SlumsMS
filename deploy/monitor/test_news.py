#!/usr/bin/env python3
"""Tests for the launcher news seed in slumsms-monitor (2026-11-01): `install.sh --news` runs `news <target> --seed`
against NEWS_FILE, the published manifest.json, and the publisher leaves the "news" key out when a release carries no
news file (the 1.6.2 night did: card b270). That is a normal manifest, so the seed must count 0 entries and exit 0
instead of stopping the install with "no news array".

    python3 deploy/monitor/test_news.py [-v]

Python 3 standard library only (the monitor's PyYAML import is lazy and not reached here).
"""
import json
import os
import shutil
import tempfile
import unittest

import test_announce as announce

mon = announce.mon


class NewsCase(unittest.TestCase):
    """A throwaway conf/state tree and a manifest file written per test."""

    def setUp(self):
        self.e = announce.Env()
        self.e.secrets(NEWS_WEBHOOK_URL=self.e.url("news"))
        self.path = os.path.join(self.e.root, "manifest.json")
        self.e.conf(NEWS_FILE=self.path)

    def tearDown(self):
        self.e.close()

    def manifest(self, data):
        with open(self.path, "w") as f:
            json.dump(data, f)

    def raw(self, text):
        with open(self.path, "w") as f:
            f.write(text)

    def news(self, *args):
        return self.e.run("news", "live", *args)

    def posted(self):
        return [p for p in self.e.posts() if p["method"] == "POST"]

    def state(self):
        path = os.path.join(self.e.state_dir, "live", "news.json")
        with open(path) as f:
            return json.load(f)


ENTRY = {"title": "Party quests are back", "date": "2026-11-01", "body": "Kerning PQ reopens tonight."}


class ManifestShapeTest(NewsCase):
    def test_a_manifest_without_news_seeds_nothing(self):
        """The 1.6.2 night: publishLayeredRelease passed no news, so validateManifest wrote no news key."""
        self.manifest({"schemaVersion": 2, "version": "1.6.2", "launch": {"executable": "SlumsMS.exe"}})
        code, out = self.news("--seed")
        self.assertEqual(0, code, out)
        self.assertIn("no news entries in %s (the manifest has no news array): seeded 0" % self.path, out)
        self.assertEqual({"posted": []}, self.state())
        self.assertEqual([], self.posted())

    def test_a_null_news_key_seeds_nothing(self):
        self.manifest({"schemaVersion": 2, "version": "1.6.2", "news": None})
        code, out = self.news("--seed")
        self.assertEqual(0, code, out)
        self.assertIn("seeded 0", out)
        self.assertEqual({"posted": []}, self.state())

    def test_a_news_key_that_is_not_a_list_is_still_an_error(self):
        self.manifest({"schemaVersion": 2, "version": "1.6.2", "news": "oops"})
        code, out = self.news("--seed")
        self.assertNotEqual(0, code, out)
        self.assertIn("news is a str, not an array", out)
        self.assertEqual([], self.posted())
        self.assertFalse(os.path.exists(os.path.join(self.e.state_dir, "live", "news.json")))

    def test_a_bare_array_is_the_news(self):
        self.manifest([ENTRY])
        code, out = self.news("--seed")
        self.assertEqual(0, code, out)
        self.assertIn("seeded 1 news entry", out)
        self.assertEqual(len(self.state()["posted"]), 1)

    def test_a_manifest_with_news_seeds_them(self):
        self.manifest({"schemaVersion": 2, "version": "1.6.2", "news": [ENTRY, dict(ENTRY, title="Second")]})
        code, out = self.news("--seed")
        self.assertEqual(0, code, out)
        self.assertIn("seeded 2 news entries", out)
        self.assertEqual(2, len(self.state()["posted"]))


class SeedTest(NewsCase):
    def test_seed_posts_nothing(self):
        self.manifest({"news": [ENTRY]})
        code, out = self.news("--seed")
        self.assertEqual(0, code, out)
        self.assertEqual([], self.posted())

    def test_a_new_entry_is_posted_after_the_seed(self):
        self.manifest({"news": [ENTRY]})
        self.assertEqual(0, self.news("--seed")[0])
        self.manifest({"news": [ENTRY, dict(ENTRY, title="New one")]})
        code, out = self.news()
        self.assertEqual(0, code, out)
        (post,) = self.posted()
        self.assertIn("New one", post["content"])

    def test_seed_on_an_empty_entry_list_writes_an_empty_state(self):
        """Entries that are there but carry no title are no entries: seed tracks none, posts none, exit 0."""
        self.manifest({"news": [{"body": "untitled"}, {"title": ""}]})
        code, out = self.news("--seed")
        self.assertEqual(0, code, out)
        self.assertIn("seeded 0", out)
        self.assertEqual({"posted": []}, self.state())
        self.assertEqual([], self.posted())

    def test_nothing_new_is_said_out_loud(self):
        self.manifest({"news": [ENTRY]})
        self.assertEqual(0, self.news("--seed")[0])
        code, out = self.news()
        self.assertEqual(0, code, out)
        self.assertIn("no new news entries", out)


class MissingFileTest(NewsCase):
    def test_a_missing_file_is_an_error_for_a_normal_run(self):
        """The news .path unit only fires on a change; a vanished NEWS_FILE is a broken config, not "no news"."""
        code, out = self.news()
        self.assertNotEqual(0, code, out)
        self.assertIn("no news file at %s" % self.path, out)
        self.assertFalse(os.path.exists(os.path.join(self.e.state_dir, "live", "news.json")))

    def test_a_missing_file_seeds_nothing(self):
        """install.sh checks -f NEWS_FILE first; a race (the path vanished) must not stop an install that is done."""
        code, out = self.news("--seed")
        self.assertEqual(0, code, out)
        self.assertIn("no news file at %s: seeded 0" % self.path, out)
        self.assertEqual({"posted": []}, self.state())
        self.assertEqual([], self.posted())

    def test_an_unreadable_manifest_is_an_error(self):
        self.raw("{ not json")
        code, out = self.news("--seed")
        self.assertNotEqual(0, code, out)
        self.assertIn("unreadable news file %s" % self.path, out)


class NewsEntriesUnitTest(unittest.TestCase):
    """news_entries keeps its own contract, for the install and for --file."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="news-test-")
        self.path = os.path.join(self.root, "manifest.json")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write(self, data):
        with open(self.path, "w") as f:
            json.dump(data, f)

    def test_missing_key_is_no_entries(self):
        self.write({"schemaVersion": 2})
        self.assertEqual([], mon.news_entries(self.path))

    def test_null_is_no_entries(self):
        self.write({"news": None})
        self.assertEqual([], mon.news_entries(self.path))

    def test_not_a_list_raises(self):
        self.write({"news": "oops"})
        with self.assertRaises(SystemExit) as caught:
            mon.news_entries(self.path)
        self.assertIn("news is a str, not an array", str(caught.exception))

    def test_a_dict_is_not_a_list(self):
        self.write({"news": {"title": "one"}})
        with self.assertRaises(SystemExit) as caught:
            mon.news_entries(self.path)
        self.assertIn("news is a dict, not an array", str(caught.exception))

    def test_bare_array(self):
        self.write([ENTRY])
        self.assertEqual([ENTRY], mon.news_entries(self.path))

    def test_untitled_entries_are_dropped(self):
        self.write({"news": [ENTRY, {"body": "no title"}, "oops", None]})
        self.assertEqual([ENTRY], mon.news_entries(self.path))

    def test_missing_file_raises_for_the_normal_path(self):
        with self.assertRaises(SystemExit) as caught:
            mon.news_entries(self.path)
        self.assertIn("no news file at %s" % self.path, str(caught.exception))

    def test_missing_file_is_no_entries_when_seeding(self):
        self.assertEqual([], mon.news_entries(self.path, seed=True))


if __name__ == "__main__":
    unittest.main()
