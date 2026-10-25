"""Tests for coupon_schedule: rate coupon shop names from real WZ `info/time` strings.

    python3 -m unittest discover -s tools/cashshop -p 'test_*.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import coupon_schedule as C  # noqa: E402

WEEK = ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")


def wz(days, span):
    """The strings as Item.wz/Cash/0521.img lists them, e.g. ["MON:07-11", "TUE:07-11", ...]."""
    return [f"{d}:{span}" for d in days]


# Copied from Item.wz/Cash/0521.img.xml and 0536.img.xml (v83 server WZ).
WZ_5211004 = wz(WEEK[:5], "07-11")                         # Type 1 - Weekdays
WZ_5211010 = wz(("SAT", "SUN"), "10-14")                    # Type 2 - Weekends
WZ_5211016 = wz(WEEK, "13-17")                              # Type 3 - Everyday
WZ_5211037 = wz(WEEK[:5], "00-04")                          # Type 6 - Weekdays
WZ_5211060 = wz(WEEK, "00-24") + ["HOL:00-24"]              # 2 hour 3x
WZ_5360000 = wz(WEEK, "00-24")                              # 1day 2x drop
WZ_5360005 = wz(WEEK, "19-23")                              # Drop Type 5


def cell_lines(name, per_line=12):
    """The client's grid break (cashshopwnd.cpp DrawGrid): the last space that still fits on line
    one, the rest on line two, clipped. Width approximated as ~6px a character of 74px."""
    if len(name) <= per_line:
        return [name]
    cut = -1
    for k, ch in enumerate(name):
        if ch == " ":
            if k > per_line:
                break
            cut = k
    if cut <= 0:
        return [name[:per_line], name[per_line:]]
    return [name[:cut], name[cut + 1:]]


class ParseTimes(unittest.TestCase):
    def test_weekdays(self):
        self.assertEqual(C.parse_times(WZ_5211004), (frozenset(range(5)), 7, 11))

    def test_holiday_entry_is_ignored(self):
        self.assertEqual(C.parse_times(WZ_5211060), (frozenset(range(7)), 0, 24))

    def test_no_compact_form(self):
        self.assertIsNone(C.parse_times([]))
        self.assertIsNone(C.parse_times(None))
        self.assertIsNone(C.parse_times(["MON:07-11", "TUE:10-14"]))   # different hours per day
        self.assertIsNone(C.parse_times(["MONDAY:07-11"]))
        self.assertIsNone(C.parse_times(["MON:11-07"]))
        self.assertIsNone(C.parse_times(["MON0711"]))


class Labels(unittest.TestCase):
    def test_hours(self):
        self.assertEqual(C.hours_label(7, 11), "7-11am")
        self.assertEqual(C.hours_label(10, 14), "10am-2pm")
        self.assertEqual(C.hours_label(13, 17), "1-5pm")
        self.assertEqual(C.hours_label(0, 4), "12-4am")
        self.assertEqual(C.hours_label(19, 23), "7-11pm")
        self.assertEqual(C.hours_label(8, 12), "8am-12pm")
        self.assertEqual(C.hours_label(20, 24), "8pm-12am")
        self.assertEqual(C.hours_label(0, 24), "anytime")

    def test_days(self):
        self.assertEqual(C.days_label(frozenset(range(7))), "")
        self.assertEqual(C.days_label(frozenset(range(5))), "M-F")
        self.assertEqual(C.days_label(frozenset({5, 6})), "Sa-Su")
        self.assertEqual(C.days_label(frozenset({0, 2})), "Mo/We")

    def test_duration(self):
        self.assertEqual(C.duration_label(5211004, 30), "30d")
        self.assertEqual(C.duration_label(5211048, 1), "4hrs")
        self.assertEqual(C.duration_label(5211060, 1), "2hrs")
        self.assertEqual(C.duration_label(5360042, 1), "4hrs")
        self.assertEqual(C.duration_label(5360000, 1), "1d")
        self.assertEqual(C.duration_label(5211004, 0), "")


class ShopName(unittest.TestCase):
    CASES = [
        (5211004, 2, WZ_5211004, 30, "2x EXP M-F 7-11am 30d"),
        (5211010, 2, WZ_5211010, 30, "2x EXP Sa-Su 10am-2pm 30d"),
        (5211016, 2, WZ_5211016, 10, "2x EXP 1-5pm 10d"),
        (5211037, 2, WZ_5211037, 30, "2x EXP M-F 12-4am 30d"),
        (5211060, 3, WZ_5211060, 1, "3x EXP anytime 2hrs"),
        (5360000, 2, WZ_5360000, 1, "2x Drop anytime 1d"),
        (5360005, 2, WZ_5360005, 10, "2x Drop 7-11pm 10d"),
    ]

    def test_real_wz_strings(self):
        for item_id, rate, times, period, want in self.CASES:
            with self.subTest(item_id=item_id):
                self.assertEqual(C.shop_name(item_id, rate, times, period), want)

    def test_fits_the_grid_cell(self):
        # Two lines, each a whole piece: multiplier+kind(+days) on top, hours+duration below.
        for item_id, rate, times, period, _ in self.CASES:
            name = C.shop_name(item_id, rate, times, period)
            lines = cell_lines(name)
            with self.subTest(name=name):
                self.assertLessEqual(len(lines), 2)
                self.assertTrue(all(len(x) <= 12 for x in lines), lines)
        self.assertEqual(cell_lines("2x EXP Sa-Su 10am-2pm 30d"), ["2x EXP Sa-Su", "10am-2pm 30d"])

    def test_no_schedule_keeps_the_wz_name(self):
        self.assertIsNone(C.shop_name(5211004, 2, [], 30))
        self.assertIsNone(C.shop_name(5211004, None, WZ_5211004, 30))


@unittest.skipUnless(os.path.isdir(os.environ.get("WZ_PATH", "/root/work/bake/wz")), "no server WZ")
class AgainstServerWz(unittest.TestCase):
    """Every sold rate coupon gets a schedule name from the real WZ, and it fits the cell."""

    def test_every_catalogue_coupon(self):
        import catalog_rules as R
        import wzindex
        w = wzindex.load(os.environ.get("WZ_PATH", "/root/work/bake/wz"), quiet=True)
        with open(R.DEFAULT_CATALOG, encoding="utf-8") as fh:
            rows = [line.split("\t") for line in fh if line[:1].isdigit()]
        coupons = [r for r in rows if int(r[0]) // 1000 in R.RATE_COUPON_TYPES]
        self.assertGreater(len(coupons), 30)
        for r in coupons:
            i, period, name = int(r[0]), int(r[5]), r[7].strip()
            info = w.items[i]
            with self.subTest(item_id=i):
                self.assertEqual(C.shop_name(i, info["rate"], info["time"], period), name)
                self.assertTrue(all(len(x) <= 12 for x in cell_lines(name)), cell_lines(name))


if __name__ == "__main__":
    unittest.main()
