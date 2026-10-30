"""The staff dashboard in the site (sections/admin.py): python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sections import admin  # noqa: E402

CSS = """:root { --bg: #fff; --surface-2: #eee; --surface: #fafafa; --s1: #2a78d6; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --bg: #000; --s1: #3987e5; } }
html, body { margin: 0; }
main { flex: 1; }
button, .card h3 { background: var(--surface-2); color: var(--s1); }
"""


class AdminTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.web, self.out = os.path.join(self.dir, "web"), os.path.join(self.dir, "out")
        os.makedirs(self.web)
        files = {"style.css": CSS, "charts.js": "css('--surface-2'); css('--s1');", "mapview.js": "", "app.js": "",
                 "index.html": '<html><head><title>SlumsMS Admin</title><link rel="stylesheet" href="style.css"></head><body><header class="top">x</header><main id="m">y</main>'
                               '<script src="app.js"></script></body></html>'}
        for name, text in files.items():
            with open(os.path.join(self.web, name), "w") as f:
                f.write(text)
        admin.build(self.out, self.web)

    def tearDown(self):
        shutil.rmtree(self.dir)

    def read(self, rel):
        with open(os.path.join(self.out, "admin", rel)) as f:
            return f.read()

    def test_colours_are_renamed_and_follow_the_site(self):
        css = self.read("dashboard.css")
        self.assertNotRegex(css, r"--(bg|surface|surface-2|s1)\b(?!-)\s*:\s*#", "no dashboard colour under a site name")
        self.assertIn("--d-s1: #2a78d6", css)                      # series colours stay the dashboard's
        self.assertIn("--d-surface-2: var(--surface-2)", css)      # surfaces follow the site's theme
        self.assertNotIn("--d-bg: #000", css)                      # ... in dark mode too
        self.assertIn("css('--d-surface-2'); css('--d-s1');", self.read("charts.js"))

    def test_the_site_page_frames_the_dashboards_own_page(self):
        page, app = self.read("index.html"), self.read("app.html")
        self.assertIn('<iframe class="adminframe" src="app.html"', page)
        self.assertIn('href="../assets/tokens.css"><link rel="stylesheet" href="dashboard.css"', app)
        self.assertIn('<main id="m">y</main>', app, "the dashboard's page is left as it is")
        self.assertIn("attributeFilter", app, "it follows the framing page's light/dark and accent")


    def test_reports_page_is_a_staff_tab_and_never_renders_html(self):
        page = self.read("reports.html")
        self.assertIn('x-data="xreports"', page)
        self.assertIn('href="reports" aria-current=page>Reports</a>', page)
        self.assertIn('../assets/reports.js', page)
        self.assertNotIn("x-html", page, "report text is the players' own: shown as text only")
        self.assertIn('href="reports">Reports</a>', self.read("index.html"))

    def test_bots_page_reads_through_the_staff_proxy_and_never_renders_html(self):
        page = self.read("bots.html")
        self.assertIn('x-data="xbots"', page)
        self.assertIn('href="bots" aria-current=page>Bots</a>', page)
        self.assertIn('../assets/bots.js', page)
        self.assertNotIn("x-html", page, "bot names and chat-made goals are shown as text only")
        self.assertIn('href="bots">Bots</a>', self.read("index.html"))
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "site", "assets", "bots.js")) as f:
            js = f.read()
        self.assertIn('"api/login/staff/inspect/"', js)
        self.assertNotIn("X-Slums-Inspect", js, "the game's secret stays on the server")

if __name__ == "__main__":
    unittest.main()
