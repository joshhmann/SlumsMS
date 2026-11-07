"""Tests for the market service (watchlist, alerts, Buy/Sell board):
python3 -m unittest discover -s tools/site -p 'test_*.py'"""
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import login_service as ls  # noqa: E402
import market_service as ms  # noqa: E402

KEY = b"k" * 48
ITEMS = [["i", 2000000, "Red Potion"], ["i", 1302000, "Sword"], ["i", 4000000, "Blue Snail Shell"],
         ["m", 100100, "Snail"]] + [["i", 3000000 + n, f"Thing {n}"] for n in range(30)]


class Clock:
    def __init__(self, t=1_800_000_000.0):
        self.t = t

    def __call__(self):
        return self.t


class ItemsMoveTest(unittest.TestCase):
    def test_a_unit_naming_the_old_database_index_reads_the_street_one(self):
        # Slums Street moved to street/ in 1.6.4 (qw01); live's unit names /srv/slums-site/database/search-index.json
        tmp = tempfile.mkdtemp()
        try:
            os.makedirs(os.path.join(tmp, "street"))
            with open(os.path.join(tmp, "street", "search-index.json"), "w") as f:
                json.dump(ITEMS, f)
            items = ms.Items(os.path.join(tmp, "database", "search-index.json"))
            self.assertEqual(items.path, os.path.join(tmp, "street", "search-index.json"))
            self.assertEqual(items.get()[2000000], "Red Potion")
            os.makedirs(os.path.join(tmp, "database"))
            with open(os.path.join(tmp, "database", "search-index.json"), "w") as f:
                json.dump(ITEMS, f)
            self.assertTrue(ms.Items(os.path.join(tmp, "database", "search-index.json")).path.endswith("database/search-index.json"),
                            "an old file that is still there is used as named")
        finally:
            shutil.rmtree(tmp)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.inbox, self.outbox = os.path.join(self.tmp, "inbox"), os.path.join(self.tmp, "outbox")
        os.makedirs(self.outbox)
        idx = os.path.join(self.tmp, "search-index.json")
        with open(idx, "w") as f:
            json.dump(ITEMS, f)
        self.clock = Clock()
        self.store = ms.Store(os.path.join(self.tmp, "m.db"), self.inbox, self.outbox, ms.Items(idx), self.clock)
        self.me = {"a": 7, "c": "Momo", "cs": ["Momo", "MomoAlt"]}
        self.other = {"a": 8, "c": "Rival"}

    def tearDown(self):
        self.store.db.close()
        shutil.rmtree(self.tmp)

    def inbox_file(self, name):
        with open(os.path.join(self.inbox, name)) as f:
            return json.load(f)

    def outbox_file(self, name, data, bump=0):
        path = os.path.join(self.outbox, name)
        with open(path, "w") as f:
            json.dump(data, f)
        st = os.stat(path)
        os.utime(path, (st.st_atime, st.st_mtime + bump))


class StoreTest(Base):
    def test_watchlist_limit_and_unknown_items(self):
        for n in range(ms.MAX_WATCH):
            self.store.change(self.me, self.store.watch, {"itemId": 3000000 + n})
        with self.assertRaises(ms.Refused) as e:
            self.store.change(self.me, self.store.watch, {"itemId": 2000000})
        self.assertEqual(e.exception.status, 409)
        self.store.change(self.me, self.store.watch, {"itemId": 3000000})  # already there: no error
        self.store.change(self.me, self.store.watch, {"itemId": 3000000, "on": False})
        self.store.change(self.me, self.store.watch, {"itemId": 2000000})
        with self.assertRaises(ms.Refused):
            self.store.change(self.me, self.store.watch, {"itemId": 100100})  # a monster id, not an item
        with self.assertRaises(ms.Refused):
            self.store.change(self.me, self.store.watch, {"itemId": "red potion"})
        self.assertEqual(len(self.inbox_file("alerts.json")["accounts"]["7"]["watch"]), ms.MAX_WATCH)

    def test_board_offers_reach_the_bell_and_are_read_once(self):
        pid = self.store.change(self.me, self.store.add_post, {"kind": "WTS", "itemId": 2000000, "qty": 1, "price": 50})
        other = self.store.change(self.other, self.store.add_post, {"kind": "WTS", "itemId": 2000000, "qty": 1, "price": 60})
        self.outbox_file("offers.json", {"schema": 1, "offers": [
            {"postId": pid, "bot": "Shroomy", "price": 45, "t": ms.iso(self.clock.t)},
            {"postId": other, "bot": "Nobody", "price": 55, "t": ms.iso(self.clock.t)}]})   # not Momo's post
        me = self.store.me(self.me)
        self.assertEqual([(o["from"], o["price"], o["new"]) for o in me["offers"]], [("Shroomy", 45, True)])
        self.assertNotIn("bot", me["offers"][0])          # an IGN under "from", never a bot/player label
        self.assertEqual(me["offers_new"], 1)
        self.clock.t += 5
        self.store.change(self.me, lambda s_: self.store.seen(s_))
        self.assertEqual(self.store.me(self.me)["offers_new"], 0)
        self.assertEqual(self.store.me(self.other)["offers"][0]["from"], "Nobody")

    def test_my_trades_are_the_accounts_own_characters_only(self):
        self.assertIsNone(self.store.me(self.me)["trades"])      # no file yet: the page uses the public traders.json
        mine = ms.characters(self.me)
        self.outbox_file("trade-ratings.json", {"generated": ms.iso(self.clock.t), "ratings": {
            mine[0]: {"completed": 3, "fellThrough": 1}, "Stranger": {"completed": 9, "fellThrough": 0},
            "Broken": {"completed": "x"}}})
        self.assertEqual(self.store.me(self.me)["trades"], {mine[0]: {"completed": 3, "fellThrough": 1}})
        self.assertNotIn("Stranger", self.store.me(self.other)["trades"])

    def test_alert_fires_once_badge_and_rearm(self):
        aid = self.store.change(self.me, self.store.add_alert, {"itemId": 2000000, "dir": "below", "price": 40})
        self.assertEqual(self.inbox_file("alerts.json")["accounts"]["7"]["alerts"][0],
                         {"id": aid, "itemId": 2000000, "dir": "below", "price": 40, "armed": True,
                          "armedAt": ms.iso(self.clock.t)})
        # the game fires it; a stranger's account id in the file can't touch it
        self.outbox_file("alerts-fired.json", {"7": [{"id": aid, "t": "2027-01-15T08:00:00Z", "price": 35}],
                                               "8": [{"id": aid, "t": "x", "price": 1}]})
        self.store.sync()
        me = self.store.me(self.me)
        self.assertEqual(me["badge"], 1)
        self.assertFalse(me["alerts"][0]["armed"])
        self.assertEqual(me["alerts"][0]["firedPrice"], 35)
        self.assertFalse(self.inbox_file("alerts.json")["accounts"]["7"]["alerts"][0]["armed"])
        # the same file again changes nothing; seen clears the badge; re-arming by hand
        self.store.sync()
        self.store.change(self.me, lambda s: self.store.seen(s))
        self.assertEqual(self.store.me(self.me)["badge"], 0)
        self.clock.t += 600
        self.store.change(self.me, self.store.alert, {"id": aid}, "rearm")
        self.assertTrue(self.store.me(self.me)["alerts"][0]["armed"])
        # the game may never see armed:false; armedAt after the firing tells it the alert is live again
        self.assertEqual(self.inbox_file("alerts.json")["accounts"]["7"]["alerts"][0]["armedAt"], ms.iso(self.clock.t))
        with self.assertRaises(ms.Refused):
            self.store.change(self.other, self.store.alert, {"id": aid}, "delete")

    def test_alert_rules(self):
        for bad in [{"itemId": 2000000, "dir": "sideways", "price": 5}, {"itemId": 2000000, "dir": "above", "price": 0},
                    {"itemId": 2000000, "dir": "above", "price": True}, {"itemId": 2000000, "dir": "above", "price": 2 ** 40}]:
            with self.assertRaises(ms.Refused):
                self.store.change(self.me, self.store.add_alert, bad)
        for n in range(ms.MAX_ALERTS):
            self.store.change(self.me, self.store.add_alert, {"itemId": 2000000, "dir": "above", "price": n + 1})
        with self.assertRaises(ms.Refused):
            self.store.change(self.me, self.store.add_alert, {"itemId": 2000000, "dir": "above", "price": 99})

    def test_posts_limits_characters_and_expiry(self):
        body = {"kind": "WTS", "itemId": 1302000, "qty": 1, "price": 5_000_000, "character": "MomoAlt"}
        pid = self.store.change(self.me, self.store.add_post, body)
        post = self.inbox_file("posts.json")["posts"][0]
        self.assertEqual((post["id"], post["account"], post["character"], post["kind"]), (pid, 7, "MomoAlt", "WTS"))
        with self.assertRaises(ms.Refused):
            self.store.change(self.me, self.store.add_post, dict(body, character="SomeoneElse"))
        with self.assertRaises(ms.Refused):
            self.store.change(self.me, self.store.add_post, dict(body, kind="SELL"))
        for _ in range(ms.MAX_OPEN_POSTS - 1):
            self.store.change(self.me, self.store.add_post, body)
        with self.assertRaises(ms.Refused) as e:
            self.store.change(self.me, self.store.add_post, body)
        self.assertEqual(e.exception.status, 409)
        self.clock.t += ms.POST_TTL + 1
        self.store.change(self.me, self.store.add_post, body)  # the old ones expired, so there's room
        self.assertEqual(len(self.inbox_file("posts.json")["posts"]), 1)

    def test_post_must_fit_one_trade_and_be_tradeable(self):
        with self.assertRaises(ms.Refused) as e:
            self.store.change(self.me, self.store.add_post, {"kind": "WTB", "itemId": 2000000, "qty": 1000, "price": 3_000_000})
        self.assertIn("one trade", str(e.exception))
        with open(os.path.join(self.tmp, "untradeable.json"), "w") as f:
            json.dump([4000000], f)
        self.store.items.untradeable_path = os.path.join(self.tmp, "untradeable.json")
        with self.assertRaises(ms.Refused) as e:
            self.store.change(self.me, self.store.add_post, {"kind": "WTS", "itemId": 4000000, "qty": 1, "price": 5})
        self.assertIn("can't be traded", str(e.exception))
        self.store.change(self.me, self.store.watch, {"itemId": 4000000})  # watching it is still fine

    def test_take_down_rejected_and_removed(self):
        body = {"kind": "WTB", "itemId": 2000000, "qty": 100, "price": 30}
        a, b, c = (self.store.change(self.me, self.store.add_post, body) for _ in range(3))
        with self.assertRaises(ms.Refused):
            self.store.change(self.other, self.store.close_post, {"id": a})      # not theirs
        self.store.change(self.me, self.store.close_post, {"id": a})            # the poster's take-down
        self.store.change({"a": 99, "c": "GM", "g": 1, "iat": int(self.clock())}, self.store.close_post, {"id": b})  # a GM's
        self.outbox_file("rejected.json", [{"id": c, "reason": "price over the cap"}])
        self.store.sync()
        st = {p["id"]: (p["status"], p["reason"]) for p in self.store.me(self.me)["posts"]}
        self.assertEqual(st[a][0], "closed")
        self.assertEqual(st[b], ("removed", "taken down by a GM"))
        self.assertEqual(st[c], ("rejected", "price over the cap"))
        self.assertEqual(self.inbox_file("posts.json")["posts"], [])

    def test_a_gm_take_down_needs_a_sign_in_from_the_last_12_hours(self):
        """Audit W13 (owner 2026-10-30): like /admin/, an old copied GM cookie takes nothing down."""
        pid = self.store.change(self.me, self.store.add_post, {"kind": "WTB", "itemId": 2000000, "qty": 100, "price": 30})
        gm = {"a": 99, "c": "GM", "g": 1, "iat": int(self.clock())}
        self.clock.t += ms.GM_FRESH + 1
        with self.assertRaises(ms.Refused) as e:
            self.store.change(gm, self.store.close_post, {"id": pid})
        self.assertEqual(e.exception.status, 401)
        self.assertFalse(self.store.me(gm)["gm"], "no take-down buttons for an old sign-in")
        gm["iat"] = int(self.clock())
        self.store.change(gm, self.store.close_post, {"id": pid})
        self.assertTrue(self.store.me(gm)["gm"])

    def test_board_drops_expired_and_unknown_shapes(self):
        self.outbox_file("board.json", {"generated_at": "2027-01-15T08:00:00Z", "posts": [
            {"id": "b1", "character": "Wolf4life", "kind": "WTS", "itemId": 1302000, "qty": 1, "price": 9,
             "expiresAt": "2099-01-01T00:00:00.536Z"},
            {"id": "b2", "character": "Old", "kind": "WTB", "itemId": 1302000, "qty": 1, "price": 9,
             "expiresAt": "2000-01-01T00:00:00Z"},
            {"id": "b3", "character": "Odd", "kind": "TRADE", "itemId": 1302000}]})
        board = self.store.board()
        self.assertEqual([p["id"] for p in board["posts"]], ["b1"])
        self.assertEqual(board["posts"][0]["name"], "Sword")
        self.assertNotIn("account", board["posts"][0])

    def test_write_rate_limit(self):
        with self.assertRaises(ms.Refused) as e:
            for _ in range(ms.WRITES_PER_MIN + 1):
                self.store.change(self.me, self.store.watch, {"itemId": 2000000})
        self.assertEqual(e.exception.status, 429)

    def test_broken_outbox_file_is_retried_later(self):
        aid = self.store.change(self.me, self.store.add_alert, {"itemId": 2000000, "dir": "above", "price": 5})
        with open(os.path.join(self.outbox, "alerts-fired.json"), "w") as f:
            f.write('{"7": [')
        self.store.sync()
        self.outbox_file("alerts-fired.json", {"7": [{"id": aid, "t": "t", "price": 6}]}, bump=5)
        self.store.sync()
        self.assertEqual(self.store.me(self.me)["badge"], 1)

    def test_cards_are_per_account_and_rewards_follow_the_secrets(self):
        out = self.store.found(self.me, {"ids": ["konami", "slums", "made-up", 5]})
        self.assertEqual(set(out["found"]), {"konami", "slums"})
        self.assertEqual(out["total"], 68)  # 41 secrets + 3 old rewards + 24 duo polaroids
        self.clock.t += 60
        out = self.store.found(self.me, {"ids": ["clicks", "konami"]})
        self.assertIn("theme", out["found"])  # the first three unlock the theme card
        self.assertEqual(out["found"]["theme"], out["found"]["clicks"])
        self.assertEqual(out["found"]["konami"], ms.iso(self.clock.t - 60))  # found again keeps the first date
        self.assertNotIn("all", out["found"])
        self.assertEqual(self.store.cards(self.other)["found"], {})
        out = self.store.found(self.me, {"ids": list(ms.SECRET_CARDS)})
        self.assertEqual(len(out["found"]), 68)
        self.assertIn("cap12", out["found"])  # 41 secrets pass every pair's mark; rewards are never posted
        out = self.store.found(self.other, {"ids": ["cap1", "pia12"]})
        self.assertEqual(out["found"], {})  # the browser may not hand out a polaroid
        for bad in ({"ids": "konami"}, {}, {"ids": ["x"] * 99}):
            with self.assertRaises(ms.Refused):
                self.store.found(self.me, bad)
        self.assertFalse(os.path.exists(os.path.join(self.inbox, "posts.json")))  # cards never reach the game

    def test_double_peace_stays_at_the_first_fourteen_and_every_card_needs_them_all(self):
        # ticket 172: seven more secrets; Double peace still needs only the old 14, so nobody loses it, and the new
        # "every" reward needs the original 22 (with the summer one) — the 19 stickers added later never take it away
        first = ("konami", "slums", "clicks", "bustin", "sidekick", "wake",
                 "henesys", "owl", "kerning", "colours", "afk", "footer", "slime", "bubble")
        self.assertEqual(ms.SECRET_CARDS[:14], first)
        self.assertEqual(ms.SECRET_CARDS[14:22], ("maple", "peace", "heart", "shh", "rebirth", "sunday", "zzz", "summer"))
        self.assertEqual(ms.EVERY_CARD, ms.SECRET_CARDS[:22])
        out = self.store.found(self.me, {"ids": list(first)})
        self.assertIn("all", out["found"])
        self.assertNotIn("every", out["found"])
        self.clock.t += 60
        out = self.store.found(self.me, {"ids": list(ms.SECRET_CARDS[14:22])})
        self.assertEqual(out["found"]["every"], ms.iso(self.clock.t))
        self.assertEqual(out["found"]["all"], ms.iso(self.clock.t - 60))  # the old reward keeps its date
        self.clock.t += 60
        out = self.store.found(self.me, {"ids": list(ms.SECRET_CARDS[22:])})  # the 19 stickers added later
        self.assertEqual(out["found"]["every"], ms.iso(self.clock.t - 60), "All of them is not lost")
        self.assertEqual(out["found"]["all"], ms.iso(self.clock.t - 120))

    def test_each_dynamic_duo_pair_unlocks_at_three_k_secrets(self):
        for k in (1, 12):
            with self.subTest(pair=k):
                me = {"a": 100 + k, "c": f"Duo{k}"}
                secrets = list(ms.SECRET_CARDS[:3 * k - 1])
                out = self.store.found(me, {"ids": secrets})
                self.assertNotIn(f"cap{k}", out["found"])
                self.assertNotIn(f"pia{k}", out["found"])
                self.clock.t += 5
                out = self.store.found(me, {"ids": [ms.SECRET_CARDS[3 * k - 1]]})
                self.assertIn(f"cap{k}", out["found"])
                self.assertIn(f"pia{k}", out["found"])  # both cards of a pair, together
                self.assertEqual(out["found"][f"cap{k}"], out["found"][f"pia{k}"])
                self.assertEqual(out["found"][f"cap{k}"], ms.iso(self.clock.t))  # the 3k-th secret's day
                self.assertNotIn(f"cap{k + 1}", out["found"])

    def test_a_duo_unlocks_on_found_order_not_the_posted_order(self):
        # an old account finds the last stickers first: its 3rd secret, whichever card, is the one that unlocks pair 1
        self.clock.t = 1_800_000_100.0
        self.store.found(self.me, {"ids": [ms.SECRET_CARDS[30]]})
        self.clock.t += 10
        self.store.found(self.me, {"ids": [ms.SECRET_CARDS[2]]})
        self.clock.t += 10
        out = self.store.found(self.me, {"ids": [ms.SECRET_CARDS[5]]})
        self.assertEqual(out["found"]["cap1"], ms.iso(self.clock.t))
        self.assertEqual(out["found"]["pia1"], ms.iso(self.clock.t))


class HintTest(Base):
    """Free card hints: one step per call, the word never printed, per account, rate limited in sqlite."""

    CAP = [c for c, h in ms.HINTS.items() if h["who"] == "cap"]
    PIA = [c for c, h in ms.HINTS.items() if h["who"] == "pia"]

    def restart(self):
        """A restarted service: a new Store on the same db file (the rate limits must survive it)."""
        self.store.db.close()
        self.store = ms.Store(os.path.join(self.tmp, "m.db"), self.inbox, self.outbox, self.store.items, self.clock)

    def hint(self, who="cap", s=None):
        return self.store.hint(self.me if s is None else s, {"who": who})

    def test_the_steps_go_clue_then_page_then_letters(self):
        card = self.CAP[0]
        out = self.hint()
        self.assertEqual((out["card"], out["step"], out["line"]), (card, 1, ms.HINTS[card]["clue"]))
        self.assertNotIn("done", out)
        self.clock.t += ms.HINT_GAP
        out = self.hint()
        self.assertEqual((out["card"], out["step"]), (card, 2))
        self.assertEqual(out["line"], f"Try it on {ms.HINTS[card]['page']}.")
        self.clock.t += ms.HINT_GAP
        out = self.hint()
        self.assertEqual((out["card"], out["step"]), (card, 3))
        self.assertIn(ms.hidden(ms.HINTS[card]["word"], 1), out["line"])  # the first letter, the rest hidden
        self.clock.t += ms.HINT_GAP
        out = self.hint()
        self.assertEqual((out["card"], out["step"]), (card, 4))
        self.assertIn(ms.hidden(ms.HINTS[card]["word"], 2), out["line"])

    def test_no_step_ever_hands_out_the_whole_word(self):
        for n, (card, h) in enumerate(ms.HINTS.items()):
            with self.subTest(card=card):
                me = {"a": 600 + n, "c": f"Hinter{n}"}
                self.store.found(me, {"ids": [c for c, x in ms.HINTS.items() if x["who"] == h["who"] and c != card]})
                for step in range(1, 21):
                    self.clock.t += ms.HINT_GAP
                    out = self.store.hint(me, {"who": h["who"]})
                    self.assertEqual((out["card"], out["step"]), (card, step))
                    self.assertNotIn("word", out)                  # the key never ships, only the masked text
                    self.assertNotIn(h["word"], out["line"])       # the clue, page and mask never spell it
                    if step == 1:
                        self.assertEqual(out["line"], h["clue"])
                    elif step == 2:
                        self.assertEqual(out["line"], f"Try it on {h['page']}.")
                    else:
                        mask = ms.hidden(h["word"], step - 2)
                        self.assertIn(mask, out["line"])
                        self.assertTrue(mask.endswith("_"), mask)  # the last letter is always hidden
                        self.assertLess(mask.count("_"), len(h["word"]))

    def test_the_letters_step_offers_the_word_only_for_typed_cards(self):
        me = {"a": 700, "c": "Typer"}
        self.store.found(me, {"ids": [c for c in ms.HINTS if c not in ("slums", "clicks")]})
        for card in ("slums", "clicks"):
            for _ in range(2):  # clue, then page
                self.clock.t += ms.HINT_GAP
                self.store.hint(me, {"who": "cap"})
            self.clock.t += ms.HINT_GAP
            out = self.store.hint(me, {"who": "cap"})
            self.assertEqual((out["card"], out["step"]), (card, 3))
            if card in ms.TYPED_CARDS:
                self.assertIn(f"Try typing this: {ms.hidden(card, 1)}", out["line"])
            else:
                self.assertNotIn("typing", out["line"])  # clicks, not letters: no false typing advice
            self.store.found(me, {"ids": [card]})        # done with this one: the next card starts at its clue

    def test_hidden_never_shows_the_last_letter(self):
        self.assertEqual(ms.hidden("huh", 1), "h _ _")
        self.assertEqual(ms.hidden("ghost", 3), "g h o _ _")
        self.assertEqual(ms.hidden("ghost", 99), "g h o s _")   # capped at len - 1
        self.assertEqual(ms.hidden("afk", 0), "_ _ _")

    def test_a_found_card_is_skipped_and_the_next_one_is_offered(self):
        self.store.found(self.me, {"ids": [self.CAP[0], self.CAP[2]]})
        self.clock.t += ms.HINT_GAP
        self.assertEqual(self.hint()["card"], self.CAP[1])
        self.clock.t += ms.HINT_GAP
        self.assertEqual(self.hint()["card"], self.CAP[1], "it carries on with the card it already hinted")

    def test_all_found_answers_done(self):
        self.store.found(self.me, {"ids": list(ms.HINTS)})
        self.clock.t += ms.HINT_GAP
        out = self.hint("pia")
        self.assertEqual(out, {"done": True, "line": ms.HINT_DONE["pia"]})
        self.clock.t += ms.HINT_GAP
        self.assertEqual(self.hint("cap")["line"], ms.HINT_DONE["cap"])

    def test_who_picks_the_character(self):
        self.assertIn(self.hint("pia")["card"], self.PIA)
        self.clock.t += ms.HINT_GAP
        self.assertEqual(self.hint("pia")["card"], self.PIA[0], "the first pia card in table order, carried on")
        self.clock.t += ms.HINT_GAP
        self.assertIn(self.hint("cap", self.other)["card"], self.CAP)
        self.clock.t += ms.HINT_GAP
        self.assertEqual(self.hint("cap", self.other)["card"], self.CAP[0])
        self.assertEqual({c for c, h in ms.HINTS.items() if h["who"] == "pia"},
                         {"news", "idea", "book", "meso", "bug", "huh", "mail", "music", "gasp", "hugs", "hello"})
        self.assertEqual(len(self.CAP) + len(self.PIA), len(ms.SECRET_CARDS))

    def test_an_unknown_or_missing_who_is_refused(self):
        for bad in ({}, {"who": "slums-chan"}, {"who": None}, {"who": ["cap"]}, {"who": "CAP"}):
            with self.subTest(body=bad):
                with self.assertRaises(ms.Refused) as e:
                    self.store.hint(self.me, bad)
                self.assertEqual(e.exception.status, 400)
        self.assertEqual(self.store.q("SELECT COUNT(*) FROM hint_calls")[0][0], 0, "a bad request uses no calls")

    def test_the_rate_limit_and_the_daily_cap_survive_a_restart(self):
        self.assertEqual(self.hint()["step"], 1)
        self.clock.t += 1
        with self.assertRaises(ms.Refused) as e:
            self.hint()
        self.assertEqual(e.exception.status, 429)
        self.restart()  # a restarted service, same db file
        with self.assertRaises(ms.Refused):
            self.hint()
        self.clock.t += 3  # four seconds after the first call
        with self.assertRaises(ms.Refused):
            self.hint()
        self.clock.t += 1
        self.assertEqual(self.hint()["step"], 2, "five seconds later it answers again")
        self.restart()
        calls = 0
        while True:  # up to the daily cap
            self.clock.t += ms.HINT_GAP
            try:
                self.hint()
            except ms.Refused:
                break
            calls += 1
            self.assertLess(calls, ms.HINTS_PER_DAY + 5)
        self.assertEqual(calls, ms.HINTS_PER_DAY - 2)
        self.restart()
        self.clock.t += ms.HINT_GAP
        with self.assertRaises(ms.Refused):
            self.hint()
        self.clock.t += 86400  # tomorrow
        self.assertEqual(self.hint()["step"], ms.HINTS_PER_DAY + 1)

    def test_accounts_are_independent(self):
        self.hint()
        self.assertEqual(self.hint(s=self.other)["step"], 1)
        with self.assertRaises(ms.Refused):
            self.hint()
        self.clock.t += ms.HINT_GAP
        self.assertEqual(self.hint(s=self.other)["step"], 2)
        self.store.found(self.me, {"ids": self.CAP[:3]})
        self.clock.t += ms.HINT_GAP
        self.assertEqual(self.hint(s=self.other)["card"], self.CAP[0], "the other account has found nothing")


class HttpTest(Base):
    def setUp(self):
        super().setUp()
        self.sessions = ls.Sessions(KEY)
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), ms.make_handler(self.sessions, self.store))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"
        self.token = self.sessions.issue({"account_id": 7, "character": "Momo", "characters": ["Momo", "MomoAlt"]})

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        super().tearDown()

    def call(self, method, path, body=None, token=True, headers=None):
        data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if token:
            req.add_header("Cookie", f"{ls.SESSION_COOKIE}={self.token}")
        for k, v in (headers if headers is not None else {"X-Slums": "1"}).items():
            req.add_header(k, v)
        try:
            r = urllib.request.urlopen(req)
        except urllib.error.HTTPError as e:
            r = e
        with r:
            return r.status, json.loads(r.read() or b"null")

    def test_signed_out_and_forged(self):
        self.assertEqual(self.call("GET", "/api/market/me", token=False)[0], 401)
        self.token = "x.y"
        self.assertEqual(self.call("POST", "/api/market/watch", {"itemId": 2000000})[0], 401)
        self.assertEqual(self.call("GET", "/api/market/health", token=False)[0], 200)

    def test_cross_site_writes_are_refused(self):
        self.assertEqual(self.call("POST", "/api/market/watch", {"itemId": 2000000}, headers={})[0], 403)
        st, _ = self.call("POST", "/api/market/watch", {"itemId": 2000000},
                          headers={"X-Slums": "1", "Origin": "https://evil.example"})
        self.assertEqual(st, 403)

    def test_a_player_session(self):
        st, body = self.call("POST", "/api/market/posts",
                             {"kind": "WTS", "itemId": 1302000, "qty": 1, "price": 100, "character": "MomoAlt"})
        self.assertEqual(st, 200, body)
        st, me = self.call("GET", "/api/market/me")
        self.assertEqual(me["characters"], ["Momo", "MomoAlt"])
        self.assertEqual(me["posts"][0]["character"], "MomoAlt")
        st, body = self.call("POST", "/api/market/alerts", {"itemId": 2000000, "dir": "below", "price": "cheap"})
        self.assertEqual(st, 400)
        self.assertIn("whole number", body["error"])
        self.assertEqual(self.call("GET", "/api/market/board")[1]["posts"], [])

    def test_cards_need_a_sign_in_and_the_header(self):
        self.assertEqual(self.call("GET", "/api/market/cards", token=False)[0], 401)
        self.assertEqual(self.call("POST", "/api/market/cards", {"ids": ["wake"]}, headers={})[0], 403)
        st, body = self.call("POST", "/api/market/cards", {"ids": ["wake"]})
        self.assertEqual((st, list(body["found"])), (200, ["wake"]))
        self.assertEqual(list(self.call("GET", "/api/market/cards")[1]["found"]), ["wake"])

    def test_my_stamps_shows_only_this_accounts_own_row(self):
        """b193: stamps.json (the game's private outbox file) is matched on the session's account id, never served whole."""
        self.assertEqual(self.call("GET", "/api/market/stamps", token=False)[0], 401)
        self.assertEqual(self.call("GET", "/api/market/stamps")[1], {"ok": True, "stamps": None, "generated": None})
        self.outbox_file("stamps.json", {"generated": "2026-10-30T19:48:44.255Z", "accounts": {
            "momo": {"id": 7, "stamps": 12}, "rival": {"id": 8, "stamps": 48}, "Momo": {"id": 70, "stamps": 3}}})
        st, body = self.call("GET", "/api/market/stamps")
        self.assertEqual((st, body), (200, {"ok": True, "stamps": 12, "generated": "2026-10-30T19:48:44.255Z"}))
        self.assertNotIn("rival", json.dumps(body))
        self.token = self.sessions.issue({"account_id": 9, "character": "Solo"})
        self.assertEqual(self.call("GET", "/api/market/stamps")[1]["stamps"], 0, "no row = no stamps yet")
        self.outbox_file("stamps.json", {"generated": "x", "accounts": [1, 2]})
        self.assertIsNone(self.call("GET", "/api/market/stamps")[1]["stamps"])

    def test_session_without_a_character_list_posts_as_its_own_character(self):
        self.token = self.sessions.issue({"account_id": 9, "character": "Solo"})
        ok = {"kind": "WTB", "itemId": 2000000, "qty": 1, "price": 1}
        self.assertEqual(self.call("POST", "/api/market/posts", ok)[0], 200)
        self.assertEqual(self.call("POST", "/api/market/posts", dict(ok, character="Other"))[0], 400)


    def test_hints_over_http(self):
        self.assertEqual(self.call("POST", "/api/market/hint", {"who": "cap"}, token=False)[0], 401)
        self.assertEqual(self.call("POST", "/api/market/hint", {"who": "cap"}, headers={})[0], 403)
        st, body = self.call("POST", "/api/market/hint", {"who": "pia"})
        self.assertEqual(st, 200, body)
        self.assertEqual((body["card"], body["step"]), (HintTest.PIA[0], 1))
        self.assertEqual(body["line"], ms.HINTS[body["card"]]["clue"])
        self.assertNotIn(ms.HINTS[body["card"]]["word"], body["line"])
        st, body = self.call("POST", "/api/market/hint", {"who": "nobody"})
        self.assertEqual(st, 400)
        self.assertIn("Slums-chan or as Pia", body["error"])
        st, body = self.call("POST", "/api/market/hint", {"who": "cap"})
        self.assertEqual(st, 429, "one per five seconds")
        self.assertIn("Give me a second", body["error"])


if __name__ == "__main__":
    unittest.main()
