"""b259: the player guide says where Spell Traces come from.

A player asked where Spell Traces come from: Glowing Treasure (Base of Roots) charges them for star force, but no page
said where to get them. The answer is in the server's scripts: the boss-exit reward NPC (Plunk, 3004006) and the Maker's
star force slot, never monster drops (owner, 2026-11-01). The numbers below are read from those scripts on
release-1.6.3; this test keeps the built guide telling players the same numbers.
Run: python3 -m unittest discover -s tools/site -p 'test_*.py'
"""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
WIKI = os.path.join(os.path.dirname(os.path.dirname(HERE)), "docs", "wiki")


def flat(page):
    """The built page as one line of text, tags dropped: "4,000" and "Spell Traces" can then be found simply."""
    with open(os.path.join(WIKI, page), encoding="utf-8") as f:
        text = f.read()
    return " ".join(re.sub(r"<[^>]+>", " ", text).split())


class SpellTraceGuideTest(unittest.TestCase):
    def test_the_hub_page_explains_star_force_and_names_the_two_sources(self):
        text = flat("hubs.html")
        for phrase in ("Star force and Spell Traces", "Glowing Treasure", "Spell Traces",
                       "Monsters don't drop them", "Plunk"):
            self.assertIn(phrase, text)

    def test_the_boss_amounts_match_the_scripts(self):
        """scripts/npc/3004006.js: Cygnus 500, Von Leon 200 on a 20% roll, Will/Lucid/Verus/Darknell 800 normal and
        2,000 hard, Magnus 4,000, Zakum and Horntail none."""
        text = flat("hubs.html")
        for phrase in ("Cygnus", "500 every time", "Von Leon", "1-in-5", "800 on normal", "2,000 on hard",
                       "Magnus", "4,000", "Zakum and Horntail give none"):
            self.assertIn(phrase, text)

    def test_the_maker_refund_and_the_star_force_rules_are_stated(self):
        """getStarForceEquipSpellTraceBonus: 30% of the stars' cost, rounded up (122 for a level 120+ item at 10
        stars). getSpellTraceCost: 25/45/95 traces for that item at 0/5/10 stars."""
        text = flat("hubs.html")
        for phrase in ("30%", "122", "25 traces", "45", "95", "never destroys"):
            self.assertIn(phrase, text)

    def test_the_faq_answers_the_question(self):
        text = flat("faq.html")
        self.assertIn("Where do Spell Traces come from?", text)
        self.assertIn("Boss rewards and the Maker, never monsters", text)


if __name__ == "__main__":
    unittest.main()
