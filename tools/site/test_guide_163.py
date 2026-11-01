"""1.6.3 in the player guide: the NX desk, the alts' lanes order, the slow return after a restart, Moren's Maker test,
and the changelog row. The numbers are read from release-1.6.3's code (DeskConfig's set B, BotCommandParser LANES,
CompanionLanes, MakerService) and must match the built pages.
Run: python3 -m unittest discover -s tools/site -p 'test_*.py'
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WIKI = os.path.join(REPO, "docs", "wiki")
ASSETS = os.path.join(REPO, "docs", "site", "assets")
sys.path.insert(0, HERE)


def flat(page):
    """The built page as one line of text, tags dropped."""
    with open(os.path.join(WIKI, page), encoding="utf-8") as f:
        text = f.read()
    return " ".join(re.sub(r"<[^>]+>", " ", text).split())


def source(page):
    with open(os.path.join(WIKI, page), encoding="utf-8") as f:
        return f.read()


class NxDeskGuideTest(unittest.TestCase):
    """DeskConfig.defaults() = spreadBps 200, driftBpsPerPct 7, maxStepBps 50, dayCapPct 20, feeBps 0, minQty 100;
    NxGuard.NX_LOWEST/NX_HIGHEST = 2,500,000/10,000,000 per 1,000 NX; ExchangeConfig.confirmSec = 60; step-up free
    10,000,000 mesos in 12 h."""

    def test_the_desk_replaces_the_nx_order_book_and_says_the_owner_s_line(self):
        text = flat("money.html")
        self.assertIn("SlumsBank sets buy and sell prices around a rate that drifts slowly with what players and bots "
                      "buy and sell (capped per day).", text)
        for gone in ("Someone with NX leaves a sell order", "post a buy or sell order for NX",
                     "leave 10 seconds between orders", "72 hours"):
            self.assertNotIn(gone, text)
        # the 1% is the item books' fee, and it may not be sold as NX's: the NX part of the page says "no fee"
        nx = text[:text.index("Other things on the exchange")]
        self.assertNotIn("1%", nx)
        self.assertIn("no fee", nx)

    def test_the_trade_is_instant_at_the_quote_with_no_price_to_type(self):
        text = flat("money.html")
        for phrase in ("instant at the quote", "whole NX from 100", "@nx buy 1000", "@nx confirm"):
            self.assertIn(phrase, text)
        self.assertIn("no price to type", text)

    def test_the_spread_is_the_cost_and_there_is_no_fee(self):
        text = flat("money.html")
        self.assertIn("2% each side", text)
        self.assertIn("no fee", text.lower())

    def test_the_rate_s_steps_the_day_cap_and_the_hard_window(self):
        text = flat("money.html")
        for phrase in ("at most 0.5% an hour and 20% a day", "2,500 and 10,000 mesos per NX"):
            self.assertIn(phrase, text)

    def test_a_quote_is_held_for_60_seconds_and_a_move_needs_another_confirm(self):
        text = flat("money.html")
        self.assertIn("60 seconds", text)
        self.assertIn("confirm", text)

    def test_the_site_asks_for_the_password_on_a_sale_or_a_big_buy(self):
        text = flat("money.html")
        self.assertIn("10,000,000", text)
        self.assertIn("password", text)

    def test_the_house_running_out_of_one_side_is_explained(self):
        text = flat("money.html")
        self.assertIn("SlumsBank is buying NX but has none to sell yet", text)
        self.assertIn("no mesos to buy with right now", text)
        self.assertIn("closed", text)

    def test_the_item_tickers_keep_their_books_and_the_nx_book_is_gone(self):
        text = flat("money.html")
        self.assertIn("order book", text)  # the item tickers'
        self.assertIn("Maple Leaves", text)
        self.assertIn("cancelled", text)
        self.assertIn("refund", text.lower())
        self.assertIn("no more", text.lower())

    def test_the_page_says_what_the_game_still_shows(self):
        text = flat("money.html")
        self.assertIn("@nx tickers", text)
        self.assertIn("until the next update", text)
class LanesGuideTest(unittest.TestCase):
    """BotCommandParser: "lanes", "lanes map", "lanes screen"; CompanionLanes AUTO seats a screen first and the extras
    across the map."""

    def test_the_regulars_page_lists_the_lanes_order(self):
        text = flat("regulars.html")
        self.assertIn("lanes", text)
        self.assertIn("lanes map", text)
        self.assertIn("lanes screen", text)
        self.assertIn("a spot of its own", text)

    def test_the_commands_page_offers_lanes(self):
        self.assertIn("lanes", flat("commands.html"))
class RestartGuideTest(unittest.TestCase):
    def test_the_regulars_page_says_about_25_minutes(self):
        self.assertIn("about 25 minutes", flat("regulars.html"))

    def test_the_faq_says_about_25_minutes(self):
        self.assertIn("about 25 minutes", flat("faq.html"))

    def test_no_per_second_number_leaks_into_the_guide(self):
        for page in ("regulars.html", "faq.html"):
            self.assertNotIn("per second", flat(page))
class MorenMakerGuideTest(unittest.TestCase):
    """MakerService.madeMaker2TestCrystal: item 4260003, the crystal from monster leftovers or from disassembling, once
    quest 6033 is started."""

    def test_the_maker_section_names_the_crystal_and_both_paths(self):
        text = flat("jobs.html")
        self.assertIn("Intermediate Monster Crystal 1", text)
        self.assertIn("a monster's leftovers", text)      # Maker 1 path
        self.assertIn("taking an item apart", text)        # disassembly path (the 163-maker2 fix)
        self.assertIn("before the quest started", text)    # only while quest 6033 is running
class ChangelogGuideTest(unittest.TestCase):
    def test_the_guide_changelog_has_1_6_3(self):
        page = source("changelog.html")
        self.assertIn("<td>1.6.3</td>", page)
        text = flat("changelog.html")
        for phrase in ("NX desk", "lanes"):
            self.assertIn(phrase, text)
class PelixirLabelTest(unittest.TestCase):
    """Owner, 2026-11-01: the Power Elixir ticker shows as PELIXIR, so nobody takes it for a plain Elixir. One display
    map (TICKER_LABEL here, LABEL in the rail); the DATA id stays ELIXIR: the game's file, ?ticker=ELIXIR, an order's
    ticker, the database."""

    @classmethod
    def setUpClass(cls):
        from sections import market
        cls.tmp = tempfile.mkdtemp(prefix="slums-pelixir-test-")
        mk = market.Market(market.load(os.path.join(HERE, "testdata", "market-contract")), {}, cls.tmp)
        market.build_exchange(mk)
        with open(os.path.join(cls.tmp, "market", "exchange.html"), encoding="utf-8") as f:
            cls.page = f.read()
        with open(os.path.join(ASSETS, "exchange.js"), encoding="utf-8") as f:
            cls.exchange = f.read()
        with open(os.path.join(ASSETS, "ticker.js"), encoding="utf-8") as f:
            cls.rail = f.read()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def test_the_tabs_header_and_ticker_labels_read_the_display_map(self):
        self.assertIn('<b x-text="label(t)"></b>', self.page)       # the tab strip
        self.assertIn('<b x-text="label(cur)"></b>', self.page)     # the ticker header
        self.assertNotIn('x-text="t.id"', self.page)                # no bare id on screen
        self.assertNotIn('x-text="cur && cur.id"', self.page)
        self.assertIn("'New ' + label(meta) + ' order'", self.page)
        self.assertIn("'Buy ' + label(meta)", self.page)
        self.assertIn("'Sell ' + label(meta)", self.page)
        self.assertIn("label(o.ticker || 'NX')", self.page)         # my orders

    def test_the_rail_calls_it_pelixir_and_still_links_by_id(self):
        self.assertIn('a.querySelector("em").textContent = LABEL[t.id] || t.id;', self.rail)
        self.assertIn('#" + t.id.toLowerCase()', self.rail)         # #elixir stays the id

    def test_the_map_is_one_and_keeps_the_data_id(self):
        self.assertEqual(re.findall(r'ELIXIR: "([A-Z]+)"', self.exchange), ["PELIXIR"])
        self.assertEqual(re.findall(r'ELIXIR: "([A-Z]+)"', self.rail), ["PELIXIR"])
        # requests carry the game's id, never the label
        self.assertIn('fetch(API + "history?ticker=" + encodeURIComponent(id)', self.exchange)
        self.assertIn("ticker: this.meta.id", self.exchange)
        self.assertNotIn('"PELIXIR"', re.sub(r'ELIXIR: "PELIXIR"', "", self.exchange))   # only the map says it

    def test_label_falls_back_to_the_id(self):
        """Runs the page's own label() against a ticker, a bare id, an unknown id and nothing."""
        block = self.exchange[self.exchange.index("var TICKER_LABEL"):]
        block = block[:block.index("function fmt(")]
        js = block + """
          var out = [label({id: "ELIXIR"}), label("ELIXIR"), label({id: "LEAF"}), label("NX"), label(null), label("GOLD")];
          console.log(JSON.stringify(out));"""
        run = subprocess.run(["node", "-e", js], capture_output=True, text=True)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout.strip(), '["PELIXIR","PELIXIR","LEAF","NX","","GOLD"]')


if __name__ == "__main__":
    unittest.main()

if __name__ == "__main__":
    unittest.main()


class DeskReviewTest(unittest.TestCase):
    """Review of w23 (2026-11-01): the desk takes any whole NX from 100 (API.md MIN_QTY), and the guide doesn't say a
    regular can never move the rate (the owner's sentence says the rate follows players and bots)."""

    def test_no_steps_of_100_and_no_never(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        for rel in ("docs/site/assets/exchange.js", "tools/site/sections/market.py", "docs/wiki/src/money.html"):
            with open(os.path.join(root, rel), encoding="utf-8") as f:
                text = " ".join(f.read().split())
            self.assertNotIn("in steps of 100", text, rel)
            self.assertNotIn("never moves the rate", text, rel)

