import json, os, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import feed_check


class FeedCheckTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        for v in ("1.6.4", "1.6.5", "1.6.10"):
            open(os.path.join(self.d, v + ".md"), "w").close()

    def feed(self, version, title):
        p = os.path.join(self.d, "feed.json")
        with open(p, "w") as f:
            json.dump({"version": version, "news": [{"title": title}]}, f)
        return p

    def test_newest_is_numeric_not_text_order(self):
        self.assertEqual(feed_check.newest_release(self.d), "1.6.10")

    def test_matching_feed_passes(self):
        self.assertEqual(feed_check.main(["--feed", self.feed("1.6.10", "SlumsMS 1.6.10"), "--releases", self.d]), 0)

    def test_old_version_or_news_warns(self):
        self.assertEqual(feed_check.main(["--feed", self.feed("1.6.5", "SlumsMS 1.6.10"), "--releases", self.d]), 1)
        self.assertEqual(feed_check.main(["--feed", self.feed("1.6.10", "SlumsMS 1.6.5"), "--releases", self.d]), 1)

    def test_expect_overrides_the_release_pages(self):
        feed = self.feed("1.6.6", "SlumsMS 1.6.6")
        self.assertEqual(feed_check.main(["--feed", feed, "--expect", "1.6.6"]), 0)
        self.assertEqual(feed_check.main(["--feed", feed, "--expect", "1.6.7"]), 1)

    def test_unreadable_feed_is_not_a_pass(self):
        self.assertEqual(feed_check.main(["--feed", os.path.join(self.d, "nope.json"), "--releases", self.d]), 2)


if __name__ == "__main__":
    unittest.main()
