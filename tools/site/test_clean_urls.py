"""Clean URLs (common.clean_links): python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402
import common  # noqa: E402


class CleanUrlsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir)

    def write(self, rel, text):
        path = os.path.join(self.dir, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)

    def test_links_lose_html_and_index(self):
        self.write("market/index.html", '<a href="exchange.html#leaf">x</a><a href="../index.html">h</a>'
                   '<a href="prices/index.html">p</a><a href="index.html?x=1">i</a><iframe src="app.html"></iframe>'
                   '<a href="https://example.com/a.html">e</a><link href="../assets/site.css">')
        common.clean_links(self.dir)
        with open(os.path.join(self.dir, "market/index.html")) as f:
            got = f.read()
        self.assertEqual(got, '<a href="exchange#leaf">x</a><a href="../">h</a><a href="prices/">p</a>'
                              '<a href="./?x=1">i</a><iframe src="app"></iframe>'
                              '<a href="https://example.com/a.html">e</a><link href="../assets/site.css">')

    def test_the_checker_follows_clean_links(self):
        self.write("market/exchange.html", '<p id="leaf">x</p>')
        self.write("market/index.html", '<a href="exchange#leaf">x</a><a href="nowhere">n</a><a href="exchange#gone">g</a>')
        problems = check.check_links(self.dir)
        self.assertEqual(sorted(p.split(": ", 1)[1] for p in problems),
                         ["broken link 'nowhere'", "link 'exchange#gone' points at a missing #gone"])


if __name__ == "__main__":
    unittest.main()
