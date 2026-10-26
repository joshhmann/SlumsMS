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
        # Henesys green is the default accent; blue and orange are picked, each designed for dark mode too
        self.assertIn("--accent: #3cbf4a", tokens.split("@media")[0])
        for accent in ("blue", "orange"):
            self.assertIn(f':root[data-accent="{accent}"][data-theme="dark"]', tokens)
            self.assertIn(f':root[data-accent="{accent}"]:not([data-theme="light"])', tokens)
        for page in ["index.html", "patch-notes/index.html", "guide/leveling.html", "report/index.html", "404.html"]:
            with open(os.path.join(self.out, page), encoding="utf-8") as f:
                text = f.read()
            # the switch only appears with JavaScript; the saved choice is applied before the page paints
            self.assertIn("data-theme-switch hidden", text, page)
            self.assertLess(text.index("slums-theme"), text.index("assets/site.css"), page)
            self.assertIn('assets/tokens.css"', text, page)
            self.assertIn("data-accent-switch hidden", text, page)
            self.assertIn('assets/brand/wordmark.webp', text, page)

    def test_search_engines_are_turned_away(self):
        with open(os.path.join(self.out, "robots.txt"), encoding="utf-8") as f:
            self.assertIn("Disallow: /", f.read().splitlines())
        self.assertEqual(check.check_noindex(self.out), [])
        with open(os.path.join(self.out, "index.html"), encoding="utf-8") as f:
            self.assertIn('content="noindex, nofollow', f.read())

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
        for bad in ["MapleStory", "Maple Story", "Nexon", "GMS v83", "Wizet",
                    "192.168.0.5", "port 164", "10.0.0.164", "ssh root@164", "on 164", "the 164 box", "host:8489", "x:7585", "Access Key", ".htpasswd", "SlumsPatch",
                    "Password", "autoqa", "type !qa"]:
            self.page("index.html", f"<p>{bad}</p>")
            self.assertTrue(check.check_forbidden(self.root), bad)

    def test_164_as_game_data_is_fine(self):
        self.page("guide/leveling.html", "<td>164</td><p>Lv. 164, 1,164 HP, item 1640001, 16400 mesos</p>")
        self.assertEqual(check.check_forbidden(self.root), [])
        self.page("guide/leveling.html", "<td>164</td><p>192.168.0.164</p>")
        self.assertTrue(check.check_forbidden(self.root))

    def test_game_names_with_forbidden_words(self):
        self.page("database/quests/3360.html", "<h1>Verifying the Password</h1><p>The Cave&#x27;s Password</p>")
        self.assertEqual(check.check_forbidden(self.root), [])
        self.page("database/quests/3360.html", "<h1>Verifying the Password</h1><p>my password is</p>")
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


class GuideLinksTest(unittest.TestCase):
    def test_public_wording(self):
        from sections import guide, database
        self.assertEqual(guide.public_wording("A small, friends-only MapleStory v83 world. Faster than GMS v83."),
                         "A small, friends-only Slums Story world. Faster than the original game.")
        self.assertEqual(database.scrub("Nexon Game Card - 100 points"), "NX Card - 100 points")
        self.assertEqual(database.scrub("Wizet Plain Suit"), "GM Plain Suit")

    def test_cells_link_into_the_database(self):
        from sections import guide
        data = {"maps": {1: {"name": "Henesys", "street": "Henesys"}, 2: {"name": "Henesys", "street": "Henesys"},
                         5: {"name": "Dragon Forest II", "street": "Minar Forest"}},
                "mobs": {9: {"name": "Snail"}}}
        links = guide.db_links(data)
        body = guide.link_cells("<td>Henesys: Henesys</td><td>Leafre: Dragon Forest II</td><td>Snail</td>"
                                "<td>Nowhere: Lost</td><td>2-7</td>", links)
        self.assertIn('<a href="../database/maps/1.html">Henesys: Henesys</a>', body)
        self.assertIn('<a href="../database/maps/5.html">Leafre: Dragon Forest II</a>', body)
        self.assertIn('<a href="../database/mobs/9.html">Snail</a>', body)
        self.assertIn("<td>Nowhere: Lost</td><td>2-7</td>", body)


class MarketTest(unittest.TestCase):
    def test_item_stats_follow_the_public_rules(self):
        from sections import market
        hist = [("2026-10-01", {"median": 100, "low": 90, "high": 110, "count": 5, "player_share": 0}),
                ("2026-10-02", None),
                ("2026-10-03", {"median": 999, "low": 1, "high": 2000, "count": 2, "player_share": 1}),
                ("2026-10-04", {"median": 120, "low": 100, "high": 130, "count": 4, "player_share": 0.5})]
        st = market.item_stats(hist)
        self.assertEqual(st["median"], 120)           # the 2-sale day is too thin to show
        self.assertEqual(st["first"], 100)
        self.assertEqual(st["sold"], 11)
        self.assertLess(st["player_share"], 0.5)      # -> "mostly bot-made"
        self.assertIsNone(market.item_stats([("2026-10-01", None)]))

    def test_chart_breaks_at_missing_days_and_scales(self):
        from sections import market
        svg = market.area_chart([("2026-10-01", 100, 90, 110), ("2026-10-02", None, None, None),
                                 ("2026-10-03", 140, 130, 150), ("2026-10-04", 150, 140, 160)], "x")
        self.assertEqual(svg.count("<polyline"), 2)   # two runs, no line across the gap
        self.assertIn("Oct 1", svg)
        self.assertIn("not enough", market.area_chart([("2026-10-01", None, None, None)] * 3, "x").lower())

    def test_times_show_pacific_and_utc(self):
        from sections import market
        self.assertEqual(market.when("2026-10-26T07:00:00+00:00"), "12:00 AM PT (07:00 UTC), Oct 26")
        self.assertEqual(market.when("2026-11-02T08:00:00+00:00"), "12:00 AM PT (08:00 UTC), Nov 2")

    def good_market(self):
        return {"listings": {"schema": 1, "listings": [{"item_id": 1, "qty": 1, "price": 5, "channel": 1, "room": 2,
                                                        "seller_kind": "bot", "seller_name": "Momo"}]},
                "sales": {"schema": 1, "sales": [{"item_id": 1, "price": 5, "qty": 1, "source": "shop", "hour": "x"}]},
                "nx-rate": {"schema": 1, "days": [{"date": "2026-10-01", "n_trades": 3, "mesos_per_1000nx": None}]},
                "economy-public": {"schema": 1, "as_of": "x"}, "prices": [{"date": "2026-10-01", "items": {}}]}

    def test_export_shape_and_privacy_lint(self):
        from sections import market
        m = self.good_market()
        self.assertEqual(market.validate(m), [])
        m["sales"]["sales"][0]["price"] = "5"
        self.assertTrue(any("'price'" in p for p in market.validate(m)))
        m = self.good_market()
        m["listings"]["listings"][0]["account_id"] = 77
        self.assertTrue(any("private field" in p for p in market.validate(m)))
        m = self.good_market()
        m["economy-public"]["schema"] = 2
        self.assertTrue(any("schema 2" in p for p in market.validate(m)))

    def test_contract_example_loads_clean(self):
        """testdata/market-contract: the examples from track-d-plan.md "Market export contract", schema 1."""
        from sections import market
        m = market.load(os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata", "market-contract"))
        self.assertEqual(market.validate(m), [])
        self.assertFalse(m["sample"])                       # no banner on real data
        self.assertEqual(len(m["prices"]), 4)               # prices/index.json is not a day
        self.assertEqual(market.where({"channel": 1, "room": None}), "ch1")
        card = market.Market(m, {"items": {}}, "/nonexistent").nx_card("")
        self.assertIn("1.90m", card)                        # today too thin: falls back to Oct 25
        self.assertIn("Oct 25", card)

    def test_change_has_sign_and_shape(self):
        from sections import market
        self.assertIn("+10.0%", market.change(100, 110))
        self.assertIn("down", market.change(100, 90))
        self.assertEqual(market.change(None, 90), "")


class DbDataTest(unittest.TestCase):
    def raw(self):
        return {
            "maps": {"1": {"name": "Henesys", "street": "Henesys", "npcs": [{"npc_id": 10}], "mobs": [{"mob_id": 20}],
                           "portals": [{"target_map_id": 2}, {"target_map_id": 99}]},
                     "2": {"name": "Henesys", "street": "Henesys", "npcs": [{"npc_id": 10}]},
                     "3": {"name": "", "mobs": [{"mob_id": 21}]},
                     "4": {"name": "\uc7a5\ub85c", "npcs": [{"npc_id": 11}]},
                     "5": {"name": "MapleStory Physical Fitness Challenge", "npcs": [{"npc_id": 11}]}},
            "mobs": {"20": {"name": "Snail", "maps": [{"map_id": 1}, {"map_id": 1}],
                            "drops": [{"item_id": 0}, {"item_id": 30}, {"item_id": 31}]},
                     "21": {"name": "Ghost", "maps": [{"map_id": 3}]}},
            "npcs": {"10": {"name": "Rina", "maps": [{"map_id": 2}, {"map_id": 1}]},
                     "11": {"name": "Hidden", "maps": [{"map_id": 4}]}},
            "quests": {"40": {"name": "Hi", "start_npc": {"id": 10}}, "41": {"name": "Nope", "start_npc": {"id": 11}}},
            "items": {"30": {"name": "Shell", "drops": [{"mob_id": 20}, {"mob_id": 20}]},
                      "31": {"name": "Orphan", "drops": [{"mob_id": 21}]}},
        }

    def test_reachable(self):
        from sections import dbdata
        d = dbdata.reachable(self.raw())
        self.assertEqual(sorted(d["maps"]), [1, 2])          # unnamed, Korean-only and officially named maps are out
        self.assertEqual(sorted(d["mobs"]), [20])             # Ghost only spawns on an unreachable map
        self.assertEqual(sorted(d["npcs"]), [10])
        self.assertEqual(sorted(d["quests"]), [40])           # starts at an unreachable NPC
        self.assertEqual(sorted(d["items"]), [30])            # dropped only by an unreachable mob
        self.assertEqual(len(d["items"][30]["drops"]), 1)     # duplicate rows collapse
        self.assertEqual([x["item_id"] for x in d["mobs"][20]["drops"]], [0, 30])
        self.assertEqual(len(d["mobs"][20]["maps"]), 1)
        self.assertEqual([p["target_map_id"] for p in d["maps"][1]["portals"]], [2])
        # two copies of one town are listed once, as the lowest id
        self.assertEqual([m["map_id"] for m in d["npcs"][10]["maps"]], [1])


if __name__ == "__main__":
    unittest.main()
