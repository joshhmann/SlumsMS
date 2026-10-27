"""Tests for the public site build: python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import json
import re
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
        for bad in ["MapleStory", "Maple Story", "Nexon", "GMS v83", "Wizet", "MapleRoot", "Maple Root", "MAPLEROOT's",
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

    def test_section_swap_leaves_no_staging_and_rolls_back(self):
        build.build(self.out)
        build.rebuild_section("notes", self.out)
        self.assertEqual([n for n in os.listdir(self.out) if n.startswith(".")], [])
        self.assertTrue(os.path.isfile(os.path.join(self.out, "patch-notes", "index.html")))
        # a section that fails the check is swapped back out: the old pages stay
        orig = build.notes.build
        try:
            def broken(out):
                orig(out)
                with open(os.path.join(out, "patch-notes", "index.html"), "a") as f:
                    f.write('<a href="nowhere.html">x</a>')
            build.notes.build = broken
            with self.assertRaises(SystemExit):
                build.rebuild_section("notes", self.out)
        finally:
            build.notes.build = orig
        with open(os.path.join(self.out, "patch-notes", "index.html"), encoding="utf-8") as f:
            self.assertNotIn("nowhere.html", f.read())
        self.assertEqual([n for n in os.listdir(self.out) if n.startswith(".")], [])


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
        self.assertEqual(database.scrub("MapleRoot's custom currency!"), "A custom currency!")
        self.assertNotIn("root", database.scrub("Made by Maple Root.").lower())
        from sections import guide
        self.assertEqual(guide.public_wording("SlumsMS is a classic world built on MapleRoot: custom jobs"),
                         "SlumsMS is a classic world: custom jobs")
        self.assertEqual(guide.public_wording("MapleRoot's skill changes"), "SlumsMS's skill changes")
        for text in ["SlumsMS is a classic world built on MapleRoot: custom jobs", "MapleRoot's skill changes",
                     "maple root's 5th tier"]:
            self.assertNotRegex(guide.public_wording(text), r"(?i)maple ?root", text)
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

    def test_median_names_its_day_and_charts_say_lowest_and_highest(self):
        from sections import market
        hist = [("2026-10-24", {"median": 100, "low": 90, "high": 110, "count": 5}),
                ("2026-10-25", {"median": 120, "low": 100, "high": 130, "count": 4}),
                ("2026-10-26", {"median": 999, "low": 1, "high": 2000, "count": 2})]
        self.assertEqual(market.item_stats(hist)["date"], "2026-10-25")   # the last day with 3+ sales, not today
        self.assertIsNone(market.item_stats([("2026-10-26", {"median": 9, "count": 1})])["date"])
        svg = market.area_chart([(d, r["median"], r["low"], r["high"]) for d, r in hist], "x")
        self.assertIn("Lowest and highest sale that day", svg)
        self.assertNotIn("Low to high", svg)
        gap = market.area_chart([("2026-10-01", 5, 5, 5), ("2026-10-02", None, None, None), ("2026-10-03", 6, 6, 6)],
                                "x", "Mesos", gap_text="no total yet")
        self.assertIn("Oct 2: no total yet", gap)
        self.assertNotIn("under 3 sales", gap)

    def test_nameless_seller_reads_a_shop(self):
        from sections import market
        self.assertEqual(market.seller({"seller_name": ""}), '<span class="muted">a shop</span>')
        self.assertEqual(market.seller({}), '<span class="muted">a shop</span>')
        self.assertEqual(market.seller({"seller_name": "Mo<b>"}), "Mo&lt;b&gt;")

    def test_span_counts_the_days_present(self):
        from sections import market
        self.assertEqual(market.span({"prices": [{}] * 5}), "5 days")
        self.assertEqual(market.span({"prices": [{}]}), "1 day")
        self.assertEqual(market.day("2026-10-25"), "Oct 25")

    def test_seeded_sales_get_a_neutral_note(self):
        from sections import market
        seeded = [{"source": "bot_seed"}, {"source": "bot_seed"}, {"source": "trade"}]
        note = market.sales_note(seeded)
        self.assertIn("regulars", note)
        self.assertNotIn("bot", note.lower())
        self.assertEqual(market.sales_note([{"source": "bot_seed"}, {"source": "trade"}]), "")
        self.assertEqual(market.sales_note([]), "")

    def test_market_pages_from_the_contract_example(self):
        from sections import market
        m = market.load(os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata", "market-contract"))
        m["sales"]["sales"].append(dict(m["sales"]["sales"][0]))   # 2 of 3 sales seeded: the note shows
        tmp = tempfile.mkdtemp()
        build.common.BUILT.update({"market", "world"})
        try:
            mk = market.Market(m, {"items": {}}, tmp)
            priced = market.build_prices(mk)
            econ = market.build_lists(mk, priced)
            market.build_home(mk, priced, econ)
            def read(rel):
                with open(os.path.join(tmp, "market", rel), encoding="utf-8") as f:
                    return f.read()
            price = read("prices/2000000.html")
            self.assertIn("Median, Oct 25", price)
            self.assertIn("Lowest and highest sale, Oct 25", price)
            self.assertNotIn("Median today", price)
            self.assertNotIn("Low to high", price)
            self.assertIn('<th class="r">4 days</th>', read("prices/index.html"))
            self.assertIn("last 4 days", read("prices/index.html"))
            self.assertNotIn("30 days", read("prices/index.html"))
            home = read("index.html")
            self.assertIn("12:00 AM PT (07:00 UTC), Oct 26", home)       # Just sold keeps the UTC
            self.assertIn("regulars&rsquo; shops and trades", home)
            self.assertIn("regulars&rsquo; shops and trades", read("sales.html"))
            self.assertIn("some regulars sell a 10,000 NX code for 50m mesos", read("nx.html"))
            self.assertIn('href="../world/economy.html"', read("economy.html"))
        finally:
            build.common.BUILT.clear()
            shutil.rmtree(tmp)

    def test_market_js_shows_pacific_and_utc(self):
        import subprocess
        js = os.path.join(build.REPO, "docs", "site", "assets", "market.js")
        src = open(js, encoding="utf-8").read()
        self.assertIn('timeZone: "America/Los_Angeles"', src)
        self.assertNotIn('d.toLocaleString("en-US", { month', src)   # the old browser-local time
        if shutil.which("node"):
            start, end = src.index("  var PT_CLOCK"), src.index("  function priceLink")
            code = src[start:end] + 'console.log(when("2026-10-27T13:00:00Z"));'
            got = subprocess.run(["node", "-e", code], capture_output=True, text=True, env=dict(os.environ, TZ="Asia/Tokyo"))
            self.assertEqual(got.stdout.strip(), "6:00 AM PT (13:00 UTC), Oct 27")


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

    def test_placeholder_names_are_unnamed(self):
        from sections import dbdata
        for name in ("?", "???", " - ", ".", "????", ""):
            self.assertFalse(dbdata.named({"name": name}), name)
        for name in ("Rina", "B-2", "Sign #1"):
            self.assertTrue(dbdata.named({"name": name}), name)
        raw = self.raw()
        raw["npcs"]["12"] = {"name": "?", "maps": [{"map_id": 1}]}  # NPC 9102100 in the real data
        raw["npcs"]["14"] = {"name": "????", "maps": [{"map_id": 1}]}  # 2032000, Orbis: gives real quests
        raw["quests"]["42"] = {"name": "Hella is Found!", "start_npc": {"id": 14, "name": "????"}}
        d = dbdata.reachable(raw)
        self.assertNotIn(12, d["npcs"])
        self.assertNotIn(14, d["npcs"])
        self.assertIn(42, d["quests"])  # the quest stays; its page names no NPC
        from sections.database import npc_name, NAMELESS_NPC
        self.assertEqual(npc_name({"id": 14, "name": "????"}), NAMELESS_NPC)
        self.assertEqual(npc_name({"id": 10, "name": "Rina"}), "Rina")

    def test_gm_and_event_maps_are_left_out(self):
        from sections import dbdata
        raw = self.raw()
        raw["maps"]["180000000"] = {"name": "Ellinia", "street": "Victoria Road", "npcs": [{"npc_id": 13}]}
        raw["maps"]["109010000"] = {"name": "Find the Jewel", "street": "Hidden Street", "npcs": [{"npc_id": 13}]}
        # the GM styling NPC also stands somewhere real: it keeps that place and loses the GM room
        raw["npcs"]["13"] = {"name": "Cotton Stylist", "maps": [{"map_id": 180000000}, {"map_id": 2}]}
        d = dbdata.reachable(raw)
        self.assertNotIn(180000000, d["maps"])
        self.assertNotIn(109010000, d["maps"])
        self.assertEqual([m["map_id"] for m in d["npcs"][13]["maps"]], [2])
        for i in (180000000, 109050000, 109090300, 820000000):
            self.assertTrue(dbdata.excluded_map(i), i)
        for i in (100000000, 910000000, 193000000, 108000500, 110000000):
            self.assertFalse(dbdata.excluded_map(i), i)

    def test_board_npc_from_migration_034_and_the_fm_street(self):
        from sections import dbdata
        raw = self.raw()
        raw["maps"]["910000000"] = {"name": "Free Market Entrance", "street": "Hidden Street",
                                    "npcs": [{"npc_id": 10}]}
        raw["npcs"]["1092017"] = {"name": "Anonymous Merchant", "maps": []}
        d = dbdata.reachable(raw)
        self.assertEqual([m["map_id"] for m in d["npcs"][1092017]["maps"]], [910000000])
        self.assertIn(1092017, [n["npc_id"] for n in d["maps"][910000000]["npcs"]])
        self.assertEqual(d["maps"][910000000]["street"], "Free Market")
        from sections import database
        self.assertEqual(dbdata.STREETS[993910000], "Free Market")   # Base of Roots, the main hub, next to the FM
        self.assertEqual(database.REGION_OF[993910000], database.REGION_OF[910000000])
        # a dataset that already has the placement is left as it is (no second row)
        raw = self.raw()
        raw["maps"]["910000000"] = {"name": "Free Market Entrance", "npcs": [{"npc_id": 1092017, "count": 1}]}
        raw["npcs"]["1092017"] = {"name": "Anonymous Merchant", "maps": [{"map_id": 910000000}]}
        d = dbdata.reachable(raw)
        self.assertEqual(len(d["maps"][910000000]["npcs"]), 1)
        self.assertEqual(len(d["npcs"][1092017]["maps"]), 1)

    def test_extra_icon_items_file(self):
        from sections import dbdata
        import icons
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "x.txt")
            with open(p, "w") as f:
                f.write("# header 123\n2040012  # Dark Scroll\n\n1102034\n")
            self.assertEqual(dbdata.extra_icon_items(p), {2040012, 1102034})
            self.assertEqual(dbdata.extra_icon_items(os.path.join(tmp, "none.txt")), set())
        jobs = icons.plan({"items": {2000000: {}}, "mobs": {}, "npcs": {}}, {"items"}, {2040012})
        self.assertEqual([j[1] for j in jobs], [2000000, 2040012])
        self.assertIn(2040012, dbdata.extra_icon_items())  # the committed list


class DatabaseTextTest(unittest.TestCase):
    def test_descriptions_lose_the_game_markup(self):
        from sections.database import clean_desc
        cases = {
            "You can learn #cMaple Warrior# with this book.\\r\\nJob: All 4th jobs":
                ["You can learn Maple Warrior with this book.", "Job: All 4th jobs"],
            "A card that features Chirppy.n#cIf hunting at Eos TowernSpeed +1#":
                ["A card that features Chirppy.", "If hunting at Eos Tower", "Speed +1"],
            "A card that features Jar.n#cIf hunting at Herb Townn소비Item drop rates are increased#":
                ["A card that features Jar.", "If hunting at Herb Town", "Item drop rates are increased"],
            "served with sugar & curry sauces.n Improves Magic Attack +8":
                ["served with sugar & curry sauces.", "Improves Magic Attack +8"],
            "Recovers HP 30.nn#cTo eat it, double-click on it#.": ["Recovers HP 30.", "To eat it, double-click on it."],
            "used up. rnAttack + 30": ["used up.", "Attack + 30"],
            "served with KanKung (water spinach)": ["served with KanKung (water spinach)"],
            "A red #1 to decorate the tree.": ["A red #1 to decorate the tree."],
            "equipped by a #Yeti#.": ["equipped by a Yeti."],
            "Talk to #bShanks#k.": ["Talk to Shanks."],
            "a #cblack pig# only": ["a black pig only"],
            "": [],
        }
        for raw, want in cases.items():
            self.assertEqual(clean_desc(raw), want, raw)

    def test_regions_do_not_overlap_and_the_fm_is_with_the_towns(self):
        from sections.database import REGIONS, region
        prefixes = {}
        for lo, hi, name in REGIONS:
            for p in range(lo, hi + 1):
                self.assertNotIn(p, prefixes, f"{p} is in {prefixes.get(p)} and {name}")
                prefixes[p] = name
        self.assertEqual(region(310000000), "Edelstein and later areas")
        self.assertEqual(region(600000000), "Masteria")
        self.assertEqual(region(610010000), "Masteria")
        self.assertEqual(region(800000000), "Zipangu")
        self.assertEqual(region(550000000), "Singapore and Malaysia")
        self.assertEqual(region(910000000), "Victoria Island")
        self.assertEqual(region(910000001), "Events, party quests and hidden maps")
        self.assertEqual(region(40000), "Maple Island")

    def test_medals_without_an_icon_get_a_stand_in(self):
        from sections.database import Site
        s = Site({"items": {1142004: {"name": "Veteran Hunter Medal"}, 2000000: {"name": "Red Potion"}},
                  "mobs": {}, "maps": {}, "npcs": {}, "quests": {}}, "/nonexistent")
        s.icons = {}
        medal = s.icon("items", 1142004, "../../")
        self.assertIn('src="../../assets/icons/1142107.png"', medal)
        self.assertIn("stand-in", medal)
        self.assertTrue(os.path.isfile(os.path.join(build.SRC, "assets", "icons", "1142107.png")))
        self.assertEqual(s.icon("items", 2000000, "../../"), '<span class="slotc"></span>')



class WorldTest(unittest.TestCase):
    """World: the public live world, feed, rankings and economy pages, from the site export (here: its samples)."""
    HERE = os.path.dirname(os.path.abspath(__file__))

    @classmethod
    def setUpClass(cls):
        import subprocess
        cls.tmp = tempfile.mkdtemp(prefix="slums-world-test-")
        cls.data = os.path.join(cls.tmp, "site-data")
        subprocess.run([sys.executable, os.path.join(cls.HERE, "site_sample.py"), "--out", cls.data],
                       check=True, capture_output=True)
        cls.market = os.path.join(cls.HERE, "testdata", "market-contract")
        cls.out = os.path.join(cls.tmp, "site")
        build.SITE_DATA, build.MARKET_DATA = cls.data, cls.market
        try:
            build.build(cls.out, live=True)
        finally:
            build.SITE_DATA = build.MARKET_DATA = None
            build.common.BUILT.clear()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def read(self, rel):
        with open(os.path.join(self.out, rel), encoding="utf-8") as f:
            return f.read()

    def test_pages_and_nav(self):
        for rel in ["world/index.html", "world/feed/index.html", "world/feed/drama.html", "world/rankings/index.html",
                    "world/rankings/traders.html", "world/rankings/level.html", "world/economy.html"]:
            self.assertTrue(os.path.isfile(os.path.join(self.out, rel)), rel)
        home = self.read("index.html")
        self.assertIn('href="world/"', home)
        self.assertNotIn('href="database/"', home)  # still a public build
        self.assertIn("Sample data", self.read("world/index.html"))

    def test_public_world_never_links_the_signed_in_sections(self):
        self.assertEqual([p for p in check.check_all(self.out) if "private" in p], [])
        self.assertIn("login/index.html?next=/market/", self.read("world/economy.html"))

    def test_no_richest_board_and_traders_show_counts_and_percent(self):
        self.assertFalse(os.path.exists(os.path.join(self.out, "world", "rankings", "richest.html")))
        self.assertNotIn("richest", self.read("world/rankings/index.html").lower())
        from sections import world
        rows = world.board_rows({"traders": [{"name": "A", "completed": 9, "fellThrough": 1},
                                             {"name": "B", "completed": 5}]}, "traders")
        self.assertEqual([r["pct"] for r in rows], [90, 100])
        page = self.read("world/rankings/traders.html")
        self.assertIn("Fell through", page)
        self.assertRegex(page, r">\d{1,3}%<")

    def test_cards_match_the_service_and_every_picture_exists(self):
        import market_service
        with open(os.path.join(build.SRC, "assets", "theme.js"), encoding="utf-8") as f:
            js = f.read()
        block = js[js.index("var SECRETS = {"):js.index("var IDS = ")]
        ids = re.findall(r"^    (\w+): \{ src", block, re.M)
        self.assertEqual(tuple(ids), market_service.SECRET_CARDS)  # the same cards, in the same order
        for src in re.findall(r'src: "([\w-]+\.webp)"', js):
            for sub in ("", "cards"):
                self.assertTrue(os.path.isfile(os.path.join(build.SRC, "secret", sub, src)), (sub, src))
        rewards = js[js.index("var REWARDS = {"):]
        self.assertEqual(re.findall(r"^    (\w+): \{ src", rewards[:rewards.index("};")], re.M),
                         list(market_service.REWARD_CARDS))

    def test_market_sidebar_groups_and_my_trades(self):
        from sections import market
        mk = market.Market(market.load(self.market), {}, self.out)
        side = mk.sidebar("../", "board")
        side = side[:side.index('class="in"')]
        self.assertRegex(side, r"Market home.*<h3>My stuff</h3>.*My market.*My trades.*My cards.*<h3>Market</h3>.*Recent sales"
                               r".*FM now.*Buy/Sell board.*Price check.*NX rate.*Economy")
        self.assertIn('board.html" aria-current=page', side)
        self.assertIn('href="../market/mine.html#trades"', side)
        self.assertIn('href="../market/cards.html"', side)
        with open(os.path.join(self.out, "world", "traders.json"), encoding="utf-8") as f:
            traders = json.load(f)
        self.assertTrue(traders)
        board = self.read("world/rankings/traders.html")
        for name, (done, fell) in traders.items():  # the same rows as the public Traders board
            self.assertIn(name, board)
            self.assertIsInstance(done + fell, int)

    def test_no_bot_labels_anywhere(self):
        for rel in ["world/rankings/level.html", "world/rankings/index.html", "world/index.html"]:
            text = self.read(rel).lower()
            self.assertNotIn("is_bot", text)
            self.assertNotRegex(text, r">\s*(bot|player)\s*<", rel)

    def test_validate_refuses_private_or_bad_data(self):
        from sections import world
        w = world.load(self.data)
        self.assertEqual(world.validate(w), [])
        w["leaderboards"]["fame"][0]["is_bot"] = True
        self.assertTrue(any("private field" in p for p in world.validate(w)))
        w = world.load(self.data)
        w["feed"]["items"][0]["account_id"] = 5
        self.assertTrue(any("private field" in p for p in world.validate(w)))
        w = world.load(self.data)
        w["feed"]["items"][0]["kind"] = "gm_log"
        self.assertTrue(any("kind" in p for p in world.validate(w)))
        w = world.load(self.data)
        w["world"]["schema"] = 2
        self.assertTrue(any("schema 2" in p for p in world.validate(w)))

    def test_feed_text_is_escaped_and_names_bold(self):
        from sections import world
        wo = world.World(world.load(self.data), {"sample": False}, self.out)
        html = wo.text({"text": "<script>x</script> Momo met Momoko", "names": ["Momo", "<script>"]})
        self.assertNotIn("<script>", html)
        self.assertIn("<b>Momo</b> met Momoko", html)

    def test_rebuild_world_only(self):
        build.SITE_DATA, build.MARKET_DATA = self.data, self.market
        try:
            build.rebuild_section("world", self.out)
        finally:
            build.SITE_DATA = build.MARKET_DATA = None
            build.common.BUILT.clear()
        self.assertTrue(os.path.isfile(os.path.join(self.out, "world", "index.html")))
        self.assertEqual([n for n in os.listdir(self.out) if n.startswith(".")], [])

    def test_link_check_ignores_query_strings(self):
        page = os.path.join(self.out, "world", "q.html")
        with open(page, "w", encoding="utf-8") as f:
            f.write('<meta name="robots" content="noindex"><a href="../login/index.html?next=/x/">a</a>'
                    '<a href="../login/nope.html?next=/x/">b</a>')
        try:
            probs = [p for p in check.check_links(self.out) if p.startswith("world/q.html")]
        finally:
            os.remove(page)
        self.assertEqual(len(probs), 1)
        self.assertIn("nope.html", probs[0])

    def test_dojo_board_only_with_rows(self):
        from sections import world
        page = self.read("world/rankings/index.html")
        self.assertEqual(world.load(self.data)["leaderboards"]["dojo"], [])   # the sample has no Dojo rows
        self.assertFalse(os.path.exists(os.path.join(self.out, "world", "rankings", "dojo.html")))
        self.assertNotIn("Dojo", page.split('<main id="main">')[1])   # (the megaphone rail may name a Dojo release)
        self.assertIn("Level, fame, guild, trader and boss rankings on Bera.", page)
        lb = {"dojo": [{"name": "Momo", "floor": 38, "points": 900, "time_s": 754}]}
        shown = world.boards(lb)
        self.assertIn("dojo", [k for k, _, _ in shown])
        self.assertEqual(world.boards_description(shown), "Level, fame, guild, trader, Dojo and boss rankings on Bera.")
        self.assertIn('<td class="r num">12:34</td>', world.table("dojo", lb["dojo"]))

    def test_free_market_rooms_read_as_rooms(self):
        from sections import world
        self.assertEqual(world.fm_room(910000012, "Free Market<12>"), 12)
        self.assertEqual(world.fm_room(None, "Free Market <3>"), 3)
        self.assertEqual(world.fm_room(910000007), 7)
        self.assertIsNone(world.fm_room(910000000, "Free Market Entrance"))
        self.assertIsNone(world.fm_room(100000000, "Henesys"))
        db = {"maps": {910000000: {"name": "Free Market Entrance"}}}
        build.common.BUILT.add("database")
        try:
            wo = world.World(world.load(self.data), {"sample": False}, self.out, db)
        finally:
            build.common.BUILT.clear()
        self.assertEqual(wo.map_name(910000012, "Free Market<12>", "../"),
                         '<a href="../database/maps/910000000.html">Free Market room 12</a>')
        wo = world.World(world.load(self.data), {"sample": False}, self.out, None)
        self.assertEqual(wo.map_name(910000012, "Free Market<12>", "../"), "Free Market room 12")

    def test_economy_page_wording(self):
        from sections import market, world
        page = self.read("world/economy.html")
        self.assertIn('<th class="r">4 days</th>', page)          # the contract example has 4 days of prices
        self.assertIn("login/index.html?next=/market/economy.html", page)
        self.assertIn("Slums Market economy page", page)
        m = market.load(self.market)
        m["economy-public"]["as_of"] = "2026-10-27T13:00:00Z"
        m["economy-public"]["days"] = [
            {"date": "2026-10-25", "mesos_total": 100, "mesos_made": 5, "mesos_spent": 4},
            {"date": "2026-10-26", "mesos_total": None, "mesos_made": 6, "mesos_spent": 5},
            {"date": "2026-10-27", "mesos_total": 120, "mesos_made": 7, "mesos_spent": 6}]
        tmp = tempfile.mkdtemp()
        try:
            world.build_economy(world.World(world.load(self.data), m, tmp))
            with open(os.path.join(tmp, "world", "economy.html"), encoding="utf-8") as f:
                page = f.read()
            self.assertIn("Made today so far (UTC day Oct 27)", page)
            self.assertIn("Spent today so far (UTC day Oct 27)", page)
            self.assertNotIn("2026-10-27</small>", page)
            self.assertIn("Oct 26: no total yet", page)
            m["economy-public"]["as_of"] = "2026-10-28T01:00:00Z"
            world.build_economy(world.World(world.load(self.data), m, tmp))
            with open(os.path.join(tmp, "world", "economy.html"), encoding="utf-8") as f:
                page = f.read()
            self.assertIn("Made on UTC day Oct 27", page)
        finally:
            shutil.rmtree(tmp)


if __name__ == "__main__":
    unittest.main()


class ScrubTest(unittest.TestCase):
    """Game-written text (names, megaphones) can't put forbidden words on the site or make a rebuild fail."""

    def test_masks_words_emails_and_addresses_but_not_game_text(self):
        import check
        got = check.scrub({"items": [{"name": "NexonPl0x", "text": "mail bob@x.com from 10.0.0.5", "qty": 164},
                                     {"name": "The Cave's Password", "text": "who remembers MapleStory?"}]})
        self.assertEqual(got["items"][0], {"name": "N***nPl0x", "text": "mail [hidden] from [hidden]", "qty": 164})
        self.assertEqual(got["items"][1]["name"], "The Cave's Password")
        self.assertEqual(got["items"][1]["text"], "who remembers M********y?")


class AvatarTest(unittest.TestCase):
    """Ranking pictures: the server's avatars/index.json maps IGNs to 96x96 PNGs; ranked names get theirs."""

    def test_ranked_names_get_their_picture_and_others_a_blank(self):
        import json as _json, struct, zlib
        import site_sample
        from sections import world
        tmp = tempfile.mkdtemp()
        try:
            data = os.path.join(tmp, "site")
            argv, sys.argv = sys.argv, ["site_sample.py", "--out", data]
            try:
                site_sample.main()
            finally:
                sys.argv = argv
            lb = _json.load(open(os.path.join(data, "leaderboards.json")))
            first = lb["level"][0]["name"]
            os.makedirs(os.path.join(data, "avatars"))
            png = b"\x89PNG\r\n\x1a\n" + b"".join(struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
                                                  for t, d in ((b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)),
                                                               (b"IDAT", zlib.compress(b"\0\0\0\0\0")), (b"IEND", b"")))
            open(os.path.join(data, "avatars", "0123456789abcdef.png"), "wb").write(png)
            _json.dump({"schema": 1, "size": 96, "avatars": {first: "0123456789abcdef.png", "Nobody": "ffffffffffffffff.png",
                                                             "Evil": "../../etc/passwd"}},
                       open(os.path.join(data, "avatars", "index.json"), "w"))
            w = world.load(data)
            self.assertEqual(w["avatars"]["map"], {first: "0123456789abcdef.png"})   # missing file and bad names dropped
            out = os.path.join(tmp, "out")
            build.set_context(build.notes.releases(), ["notes", "guide", "world"])
            world.build(out, data)
            html = open(os.path.join(out, "world", "rankings", "level.html"), encoding="utf-8").read()
            self.assertIn('src="../../world/avatars/0123456789abcdef.png"', html)
            self.assertIn('class="ava none"', html)
            self.assertTrue(os.path.isfile(os.path.join(out, "world", "avatars", "0123456789abcdef.png")))
        finally:
            shutil.rmtree(tmp)
