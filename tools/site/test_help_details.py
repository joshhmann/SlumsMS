"""b263: the exchange's and the staff pages' explanations fold behind a click, in a native details/summary.

The ticket (owner, 2026-10-31: "could prob hide the explanations and such and only show if you need help, same with
fees and limits, could be a drop down to click") wants the words that explain a page behind a summary that starts
closed, while the prices, the forms, the books, the chart and every banner stay on the page. Native <details> is the
whole mechanism: no script, so keyboard and screen readers already work.
Run: python3 -m unittest discover -s tools/site -p 'test_*.py'
"""
import os
import shutil
import sys
import tempfile
import unittest
from html.parser import HTMLParser

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sections import admin, market  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "testdata", "market-contract")
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}


class Doc(HTMLParser):
    """Every element with the elements it sits inside, so a test can ask which text is folded and which is not."""

    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.nodes = []   # (tag, attrs, ancestor indices, outermost first)
        self.ids = []     # indices of the open elements, outermost first
        self.texts = []   # (open indices at the time, the text)
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        index = len(self.nodes)
        self.nodes.append((tag, dict(attrs), tuple(self.ids)))
        if tag not in VOID:
            self.ids.append(index)

    def handle_startendtag(self, tag, attrs):
        self.nodes.append((tag, dict(attrs), tuple(self.ids)))

    def handle_endtag(self, tag):
        for i in range(len(self.ids) - 1, -1, -1):
            if self.nodes[self.ids[i]][0] == tag:
                del self.ids[i:]
                return
        raise AssertionError("</%s> closes nothing" % tag)   # the built pages must be well formed

    def handle_data(self, data):
        self.texts.append((tuple(self.ids), data))

    def find(self, tag=None, cls=None):
        """The indices of every element with this tag and/or in this class."""
        return [i for i, (t, a, _) in enumerate(self.nodes)
                if (tag is None or t == tag) and (cls is None or cls in a.get("class", "").split())]

    def text(self, index):
        return " ".join("".join(t for ids, t in self.texts if index in ids).split())

    def inside(self, index, tag):
        return [j for j in self.nodes[index][2] if self.nodes[j][0] == tag]

    def summary_text(self, details):
        """The text of the details' own summary."""
        for j in range(details + 1, len(self.nodes)):
            if self.nodes[j][0] == "summary" and self.nodes[j][2] and self.nodes[j][2][-1] == details:
                return self.text(j)
        return None

    def summary_of(self, index):
        """The summary text of the details the element sits in (None when it isn't inside a details)."""
        details = self.inside(index, "details")
        return self.summary_text(details[-1]) if details else None


class Folded:
    """Assertions shared by the exchange and the staff pages."""

    def assert_closed_help(self, doc, index, summary):
        """The element sits inside a details that starts closed, and that details' summary reads `summary`."""
        details = doc.inside(index, "details")
        self.assertTrue(details, "%s is not inside a details" % (doc.nodes[index],))
        self.assertNotIn("open", doc.nodes[details[-1]][1], "the help must start closed")
        self.assertEqual(summary, doc.summary_of(index))

    def assert_visible(self, doc, index, what):
        """Prices, forms, books, charts and banners stay on the page: never inside a details."""
        self.assertEqual([], doc.inside(index, "details"), "%s must stay visible" % what)

    def assert_not_in_help(self, doc, index, what):
        """Not inside a folded help block (the staff pages' own details, like a report's logs, don't count)."""
        help = [j for j in doc.inside(index, "details") if "help" in doc.nodes[j][1].get("class", "").split()]
        self.assertEqual([], help, "%s must stay visible" % what)


class ExchangeHelpTest(unittest.TestCase, Folded):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="slums-help-")
        cls.pages = {}
        saved = market.NX_DESK
        try:
            for mode in ("off", "on"):           # 1.6.2's page, then 1.6.3's NX desk
                market.NX_DESK = mode
                market.build_exchange(market.Market(market.load(DATA), {}, cls.tmp))
                with open(os.path.join(cls.tmp, "market", "exchange.html"), encoding="utf-8") as f:
                    cls.pages[mode] = f.read()
        finally:
            market.NX_DESK = saved

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def page(self, mode="off"):
        doc = Doc(self.pages[mode])
        self.assertEqual([], doc.ids, "every element is closed")
        return doc

    def test_the_rules_fold_under_fees_and_limits(self):
        """The fee, the limits, expiry, safety and which-character rules start folded, under one summary."""
        for mode in ("off", "on"):
            doc = self.page(mode)
            rules, = doc.find("section", "xc-rules")
            lis = [i for i in doc.find("li") if rules in doc.nodes[i][2]]
            self.assertGreaterEqual(len(lis), 4, mode)
            for i in lis:
                self.assert_closed_help(doc, i, "Fees and limits")
            words = " ".join(doc.text(i) for i in lis)
            fee = "Fee (items):" if mode == "on" else "Fee:"
            for line in (fee, "Limits:", "Expiry:", "Safety:", "Which character:"):
                self.assertIn(line, words, mode)

    def test_the_prices_forms_book_and_chart_stay_out_of_the_folded_help(self):
        for mode in ("off", "on"):
            doc = self.page(mode)
            for cls in ("xc-last", "xc-stats", "xc-chart", "xc-depth", "xc-book", "xc-alerts", "xc-tabsbar"):
                for i in doc.find(cls=cls):
                    self.assert_visible(doc, i, cls)
            for i in doc.find("form"):
                self.assert_visible(doc, i, "an order form")
            for i in doc.find(cls="xc-alert"):
                self.assert_visible(doc, i, "a banner")
            for i in doc.find(cls="xc-preview"):
                self.assert_visible(doc, i, "the order preview")

    def test_the_intro_and_the_chart_notes_fold(self):
        for mode in ("off", "on"):
            doc = self.page(mode)
            lede, = doc.find("p", "lede")
            self.assert_closed_help(doc, lede, "How it works")
            self.assertIn("The stock market for NX", doc.text(lede))
            helps = [doc.summary_text(i) for i in doc.find("details", "help")]
            self.assertEqual(2 if mode == "on" else 1, helps.count("About the chart"), mode)  # candles, and the desk's
            self.assertEqual(1, helps.count("About this chart"), mode)  # the depth legend
            self.assertEqual(1 if mode == "on" else 0, helps.count("How the desk works"), mode)

    def test_the_nx_desk_keeps_its_prices_and_form_and_folds_the_explaining(self):
        doc = self.page("on")
        rate, = doc.find("p", "xc-deskrate")
        self.assert_visible(doc, rate, "the NX rate line")
        for cls in ("xc-deskone", "xc-deskform", "xc-deskbal"):
            for i in doc.find(cls=cls):
                self.assert_visible(doc, i, cls)
        fee = [i for i in doc.find("p", "xc-note") if "No fee: the gap between the buy and sell price" in doc.text(i)]
        self.assertEqual(1, len(fee), "the fee line is on the page, folded")
        self.assert_closed_help(doc, fee[0], "How the desk works")


class AdminHelpTest(unittest.TestCase, Folded):
    """The staff pages (sections/admin.py): the first line stays, the long explaining folds."""

    CSS = (":root { --bg: #fff; --surface-2: #eee; --surface: #fafafa; --s1: #2a78d6; }\n"
           "button, .card h3 { background: var(--surface-2); color: var(--s1); }\n")

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="slums-help-admin-")
        web, out = os.path.join(cls.tmp, "web"), os.path.join(cls.tmp, "out")
        os.makedirs(web)
        files = {"style.css": cls.CSS, "charts.js": "css('--surface-2'); css('--s1');", "mapview.js": "",
                 "app.js": "",
                 "index.html": '<html><head><title>SlumsMS Admin</title><link rel="stylesheet" href="style.css">'
                               '</head><body><main id="m">y</main><script src="app.js"></script></body></html>'}
        for name, text in files.items():
            with open(os.path.join(web, name), "w") as f:
                f.write(text)
        admin.build(out, web)
        cls.pages = {}
        for name in ("bots", "reports", "invites"):
            with open(os.path.join(out, "admin", name + ".html"), encoding="utf-8") as f:
                cls.pages[name] = f.read()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def page(self, name):
        doc = Doc(self.pages[name])
        self.assertEqual([], doc.ids, "every element is closed")
        return doc

    def test_the_bots_page_keeps_its_first_line_and_folds_the_rest(self):
        doc = self.page("bots")
        first = doc.find("p", "id")[0]
        self.assertEqual("What the bots are doing right now, read from the game.", doc.text(first))
        self.assert_visible(doc, first, "the page's first line")
        ro = [i for i in doc.find("p", "id") if "Read only: nothing here changes the game" in doc.text(i)]
        self.assertEqual(1, len(ro))
        self.assert_closed_help(doc, ro[0], "What this page shows")
        cal = [i for i in doc.find("p", "small") if "Near 1 is right" in doc.text(i)]
        self.assertEqual(1, len(cal))
        self.assert_closed_help(doc, cal[0], "How to read it")
        for i in doc.find("form") + doc.find("table"):
            self.assert_not_in_help(doc, i, "a form or table on the bots page")

    def test_the_reports_page_keeps_its_first_line_and_folds_the_privacy_note(self):
        doc = self.page("reports")
        first = doc.find("p", "id")[0]
        self.assertEqual("Bug reports and ideas from the website's Report page and from @bug / @idea in game.",
                         doc.text(first))
        self.assert_visible(doc, first, "the page's first line")
        priv = [i for i in doc.find("p", "id") if "No IP hashes" in doc.text(i)]
        self.assertEqual(1, len(priv))
        self.assert_closed_help(doc, priv[0], "What isn't shown")
        for i in doc.find("form"):
            self.assert_not_in_help(doc, i, "the search form")

    def test_the_help_is_a_native_disclosure_with_a_summary(self):
        """No script opens it: a details and its summary are the whole mechanism, and no help starts open."""
        for name in ("bots", "reports", "invites"):
            doc = self.page(name)
            helps = doc.find("details", "help")
            if name == "invites":
                self.assertEqual([], helps, "the invite codes page has no long explaining left to fold")
            for i in helps:
                self.assertTrue(doc.summary_text(i), "a help details has its summary")
                self.assertNotIn("open", doc.nodes[i][1])
                self.assertNotIn("x-data", doc.nodes[i][1], "native, no script")
                self.assertNotIn("onclick", doc.nodes[i][1])


if __name__ == "__main__":
    unittest.main()
