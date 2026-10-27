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
        self.store.change({"a": 99, "c": "GM", "g": 1}, self.store.close_post, {"id": b})  # a GM's
        self.outbox_file("rejected.json", [{"id": c, "reason": "price over the cap"}])
        self.store.sync()
        st = {p["id"]: (p["status"], p["reason"]) for p in self.store.me(self.me)["posts"]}
        self.assertEqual(st[a][0], "closed")
        self.assertEqual(st[b], ("removed", "taken down by a GM"))
        self.assertEqual(st[c], ("rejected", "price over the cap"))
        self.assertEqual(self.inbox_file("posts.json")["posts"], [])

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
        self.assertEqual(out["total"], 16)
        self.clock.t += 60
        out = self.store.found(self.me, {"ids": ["clicks", "konami"]})
        self.assertIn("theme", out["found"])  # the first three unlock the theme card
        self.assertEqual(out["found"]["theme"], out["found"]["clicks"])
        self.assertEqual(out["found"]["konami"], ms.iso(self.clock.t - 60))  # found again keeps the first date
        self.assertNotIn("all", out["found"])
        self.assertEqual(self.store.cards(self.other)["found"], {})
        out = self.store.found(self.me, {"ids": list(ms.SECRET_CARDS)})
        self.assertEqual(len(out["found"]), 16)
        for bad in ({"ids": "konami"}, {}, {"ids": ["x"] * 99}):
            with self.assertRaises(ms.Refused):
                self.store.found(self.me, bad)
        self.assertFalse(os.path.exists(os.path.join(self.inbox, "posts.json")))  # cards never reach the game


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

    def test_session_without_a_character_list_posts_as_its_own_character(self):
        self.token = self.sessions.issue({"account_id": 9, "character": "Solo"})
        ok = {"kind": "WTB", "itemId": 2000000, "qty": 1, "price": 1}
        self.assertEqual(self.call("POST", "/api/market/posts", ok)[0], 200)
        self.assertEqual(self.call("POST", "/api/market/posts", dict(ok, character="Other"))[0], 400)


if __name__ == "__main__":
    unittest.main()
