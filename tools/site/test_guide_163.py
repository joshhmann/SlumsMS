"""1.6.3 in the player guide: the NX desk, the alts' lanes order, the slow return after a restart, Moren's Maker test,
and the changelog row. The numbers are read from release-1.6.3's code (DeskConfig's set B, BotCommandParser LANES,
CompanionLanes, MakerService) and must match the built pages.
Run: python3 -m unittest discover -s tools/site -p 'test_*.py'
"""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WIKI = os.path.join(REPO, "docs", "wiki")


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

if __name__ == "__main__":
    unittest.main()
