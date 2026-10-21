"""Unit tests for clientcheck helpers that need neither the desktop nor the WZ dump.

    python3 -m unittest discover -s tools/clientcheck -p 'test_*.py'
"""
import os
import sys
import types
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clientcheck as cc  # noqa: E402


class SafeName(unittest.TestCase):
    def test_remote_branch_is_one_component(self):
        self.assertEqual(cc.safe_name("origin/test-rc4"), "origin_test-rc4")
        self.assertEqual(cc.safe_name("release-1.4.0"), "release-1.4.0")
        self.assertNotIn("/", cc.safe_name("a/b/../c"))
        self.assertNotIn("..", cc.safe_name(".."))

    def test_cache_path_stays_in_cache_dir(self):
        br = types.SimpleNamespace(name="origin/test-rc4", sha="14587f7")
        p = cc.Branch.custom_wz_cache(br, "/cache")
        self.assertEqual(os.path.dirname(p), "/cache")
        self.assertEqual(os.path.basename(p), "Custom-origin_test-rc4-14587f7.wz")


class FlagOverrides(unittest.TestCase):
    def test_spellings(self):
        self.assertEqual(cc.parse_flag_overrides(["DAILY_CHECKIN_ENABLED=true"]), [("DAILY_CHECKIN_ENABLED", True)])
        self.assertEqual(cc.parse_flag_overrides(["server.DAILY_CHECKIN_ENABLED=TRUE"]),
                         [("DAILY_CHECKIN_ENABLED", True)])
        self.assertEqual(cc.parse_flag_overrides(["-Dweather.enabled=false"]), [("weather.enabled", False)])
        self.assertEqual(cc.parse_flag_overrides(["weather.enabled"]), [("weather.enabled", True)])
        self.assertEqual(cc.parse_flag_overrides(None), [])

    def test_override_beats_config(self):
        root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
        a = types.SimpleNamespace(server=root, server_config=None,
                                  server_flag=["DAILY_CHECKIN_ENABLED=true", "server.weather.enabled=false"])
        flags, src = cc.server_flag_values(a)
        self.assertIs(flags["DAILY_CHECKIN_ENABLED"], True)
        self.assertIs(flags["weather.enabled"], False)
        self.assertTrue(any("DAILY_CHECKIN_ENABLED=true" in s for s in src))


class CustomItems(unittest.TestCase):
    CIMGS = {"Item/Cash/0578.img": ["D:05782000", "D:05782000/info", "C:05782000/info/icon",
                                    "I:05782000/info/cash"],
             "Item/Cash/0501.img": ["D:05010999", "C:05010999/info/icon"]}
    CFILES = {"Item/Cash/0501.img": {"h": "x", "top": {"05010000": "y"}}}

    def test_new_image_served_whole(self):
        self.assertEqual(cc.custom_item_status(5782000, self.CFILES, {}, self.CIMGS), (True, True))

    def test_new_item_under_stock_image_not_visible(self):
        self.assertEqual(cc.custom_item_status(5010999, self.CFILES, {}, self.CIMGS), (False, False))

    def test_absent(self):
        self.assertEqual(cc.custom_item_status(5782001, self.CFILES, {}, self.CIMGS), (False, False))
        self.assertEqual(cc.custom_item_status(5782000, self.CFILES, {}, {}), (False, False))

    def test_source_names(self):
        src = ('constexpr int kColorPrismTextItemId = 5782000;\n'
               'constexpr const char* kColorPrismName = "Coloring Prism";\n')
        br = types.SimpleNamespace(files={"src/coloringprism_text.h", "src/other.cpp"},
                                   read=lambda p: src if p.endswith("_text.h") else "")
        self.assertEqual(cc.source_item_names(br), {5782000: ("Coloring Prism", "src/coloringprism_text.h")})


if __name__ == "__main__":
    unittest.main()
