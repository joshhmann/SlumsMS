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

    def test_theme_and_fonts(self):
        for rel in ["assets/tokens.css", "assets/theme.js", "assets/fonts/OFL.txt", "assets/fonts/bricolage-grotesque.woff2",
                    "assets/fonts/instrument-sans.woff2", "assets/fonts/jetbrains-mono.woff2"]:
            self.assertTrue(os.path.isfile(os.path.join(self.out, rel)), rel)
        with open(os.path.join(self.out, "assets", "tokens.css"), encoding="utf-8") as f:
            tokens = f.read()
        # three theme states: system (media query, guarded), and both explicit choices
        self.assertIn(':root:not([data-theme="light"])', tokens)
        self.assertIn(':root[data-theme="dark"]', tokens)
        for page in ["index.html", "patch-notes/index.html", "guide/leveling.html", "report/index.html", "404.html"]:
            with open(os.path.join(self.out, page), encoding="utf-8") as f:
                text = f.read()
            # the switch only appears with JavaScript; the saved choice is applied before the page paints
            self.assertIn("data-theme-switch hidden", text, page)
            self.assertLess(text.index("slums-theme"), text.index("assets/site.css"), page)
            self.assertIn('assets/tokens.css"', text, page)

    def test_megaphone_rail(self):
        with open(os.path.join(self.out, "guide", "rates.html"), encoding="utf-8") as f:
            text = f.read()
        rail = text[text.index('class="rail"'):text.index("<main")]
        latest = self.rels[0]["version"]
        self.assertIn(f'href="../patch-notes/{latest}/"', rail)
        # the loop copy is hidden from screen readers and from the tab order
        self.assertIn('aria-hidden="true" tabindex="-1"', rail)

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
        for bad in ["192.168.0.5", "port 164", "10.0.0.164", "ssh root@164", "on 164", "the 164 box", "host:8489", "x:7585", "Access Key", ".htpasswd", "SlumsPatch",
                    "Password", "autoqa", "type !qa"]:
            self.page("index.html", f"<p>{bad}</p>")
            self.assertTrue(check.check_forbidden(self.root), bad)

    def test_164_as_game_data_is_fine(self):
        self.page("guide/leveling.html", "<td>164</td><p>Lv. 164, 1,164 HP, item 1640001, 16400 mesos</p>")
        self.assertEqual(check.check_forbidden(self.root), [])
        self.page("guide/leveling.html", "<td>164</td><p>192.168.0.164</p>")
        self.assertTrue(check.check_forbidden(self.root))

    def test_allowlist_is_exact(self):
        old = check.ALLOW
        check.ALLOW = [("guide/a.html", "<td>slums-patch</td>")]
        try:
            self.page("guide/a.html", "<td>slums-patch</td>")
            self.assertEqual(check.check_forbidden(self.root), [])
            self.page("guide/a.html", "<td>slums-patch</td><p>slums-patch</p>")
            self.assertTrue(check.check_forbidden(self.root))
        finally:
            check.ALLOW = old

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


class SectionsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="slums-sections-test-")
        self.out = os.path.join(self.tmp, "site")

    def tearDown(self):
        shutil.rmtree(self.tmp)
        build.common.BUILT.clear()

    def test_public_nav_hides_private_sections(self):
        build.build(self.out)
        with open(os.path.join(self.out, "index.html"), encoding="utf-8") as f:
            home = f.read()
        self.assertNotIn('href="database/"', home)
        self.assertNotIn('href="market/"', home)
        self.assertIn('href="guide/"', home)

    def test_public_build_refuses_private_pages(self):
        build.build(self.out)
        os.makedirs(os.path.join(self.out, "market"))
        self.assertTrue(any("private section" in p for p in check.check_all(self.out)))
        self.assertFalse(any("private section" in p for p in check.check_all(self.out, private=True)))

    def test_private_nav_lists_built_sections(self):
        build.common.BUILT.update({"market"})
        page = build.layout("T", "<p>x</p>", "", "home")
        self.assertIn('href="market/"', page)
        self.assertNotIn('href="database/"', page)

    def test_rebuild_one_section(self):
        build.build(self.out)
        path = os.path.join(self.out, "patch-notes", "index.html")
        with open(path, encoding="utf-8") as f:
            before = f.read()
        os.remove(path)
        build.rebuild_section("notes", self.out)
        with open(path, encoding="utf-8") as f:
            self.assertEqual(f.read(), before)
        with self.assertRaises(SystemExit):
            build.rebuild_section("notes", os.path.join(self.tmp, "empty"))


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
