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
                 "index.html": '<html><body><header class="top">x</header><main id="m">y</main>'
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

    def test_rules_are_scoped_to_the_dashboard(self):
        css = self.read("dashboard.css")
        self.assertIn(".dash { margin: 0; }", css)
        self.assertIn(".dash .dmain {", css)
        self.assertIn(".dash button, .dash .card h3 {", css)
        page = self.read("index.html")
        self.assertIn('<div class="dash"><header class="top">x</header><div class="dmain" id="m">y</div>', page)
        self.assertEqual(page.count('src="app.js"'), 1, "the dashboard's own script tags are replaced by the build's")


if __name__ == "__main__":
    unittest.main()
