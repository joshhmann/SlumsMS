"""Tests for the public site build: python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import os
import shutil
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402
import check  # noqa: E402


class SiteBuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="slums-site-test-")
        cls.out = os.path.join(cls.tmp, "site")
        cls.tar = os.path.join(cls.tmp, "slums-site.tar.gz")
        cls.rels, cls.guide = build.build(cls.out, cls.tar)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def test_pages_exist(self):
        for rel in ["index.html", "404.html", "patch-notes/index.html", "patch-notes/1.5.0/index.html",
                    "guide/index.html", "guide/getting-started.html", "guide/leveling.html", "assets/site.css"]:
            self.assertTrue(os.path.isfile(os.path.join(self.out, rel)), rel)
        self.assertFalse(os.path.exists(os.path.join(self.out, "guide", "changelog.html")))

    def test_output_is_clean(self):
        self.assertEqual(check.check_forbidden(self.out), [])
        self.assertEqual(check.check_links(self.out), [])

    def test_home_shows_latest_release(self):
        with open(os.path.join(self.out, "index.html"), encoding="utf-8") as f:
            home = f.read()
        self.assertIn(self.rels[0]["version"], home)
        self.assertIn(f'href="patch-notes/{self.rels[0]["version"]}/"', home)
        self.assertNotIn("{{", home)

    def test_wiki_notes_are_stripped(self):
        for name in self.guide:
            with open(os.path.join(self.out, "guide", name), encoding="utf-8") as f:
                text = f.read()
            self.assertNotIn('class="src"', text, name)
            self.assertNotIn('class="owner"', text, name)
            self.assertNotIn("docs/", text, name)

    def test_tarball_matches_output(self):
        with tarfile.open(self.tar) as tf:
            names = {n for n in tf.getnames()}
        self.assertIn("slums-site/index.html", names)
        self.assertIn("slums-site/patch-notes/1.5.0/index.html", names)


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="slums-check-test-")

    def tearDown(self):
        shutil.rmtree(self.root)

    def page(self, rel, body):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"<!doctype html><html><body>{body}</body></html>")

    def test_each_forbidden_string_is_caught(self):
        for bad in ["192.168.0.5", "port 164", "host:8489", "x:7585", "Access Key", ".htpasswd", "SlumsPatch",
                    "Password", "autoqa", "type !qa"]:
            self.page("index.html", f"<p>{bad}</p>")
            self.assertTrue(check.check_forbidden(self.root), bad)

    def test_allowlist_is_exact(self):
        self.page("guide/leveling.html", "<td>164</td>")
        self.assertEqual(check.check_forbidden(self.root), [])
        self.page("guide/leveling.html", "<td>164</td><p>192.168.0.164</p>")
        self.assertTrue(check.check_forbidden(self.root))

    def test_links(self):
        self.page("index.html", '<a href="guide/">g</a><a href="#top">t</a><a href="a.html#x">a</a>'
                                '<a href="https://example.com/">e</a><a href="/abs.html">r</a>')
        self.page("a.html", '<h2 id="y">y</h2>')
        problems = check.check_links(self.root)
        text = "\n".join(problems)
        self.assertIn("'guide/'", text)
        self.assertIn("#top", text)
        self.assertIn("#x", text)
        self.assertIn("external URL", text)
        self.assertIn("root-relative", text)
        self.assertEqual(len(problems), 5)


class CleanWikiTest(unittest.TestCase):
    def test_only_empty_sections_are_dropped(self):
        body = ('<h2>Questions</h2>\n<p>Q</p>\n<h2>Rules</h2>\n<p class="owner">x</p>\n'
                '<h2>Known</h2>\n<ul><li>k <span class="owner">c</span></li></ul>\n<p class="src">s</p>')
        out = build.clean_wiki(body)
        self.assertEqual(out, "<h2>Questions</h2>\n<p>Q</p>\n<h2>Known</h2>\n<ul><li>k</li></ul>\n")


class MarkdownTest(unittest.TestCase):
    def test_release_markdown(self):
        tmp = tempfile.mkdtemp()
        try:
            path = os.path.join(tmp, "9.9.9.md")
            with open(path, "w", encoding="utf-8") as f:
                f.write("---\nversion: 9.9.9\ntitle: T\ndate: D\nsummary: S\n---\nHello `@x <y>`.\n\n"
                        "## Things {#things}\n- [Fix] **Bold** and [link](../)\n  continued\n- plain\n")
            rel = build.parse_release(path)
            html = build.render_release(rel)
            self.assertIn('<section id="things">', html)
            self.assertIn('<span class="tag fix">Fix</span><div><b>Bold</b> and <a href="../">link</a> continued',
                          html)
            self.assertIn("<code>@x &lt;y&gt;</code>", html)
            self.assertEqual(rel["counts"]["fix"], 1)
        finally:
            shutil.rmtree(tmp)


if __name__ == "__main__":
    unittest.main()
