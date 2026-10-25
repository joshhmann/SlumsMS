"""Shop names for rate coupons (5211xxx EXP, 5360xxx drop), built from their WZ schedule.

The Cash Shop window shows nothing about an item but its NAME (the wire row is id, price,
count, tab, category, name -- CashShopWindowPackets.category), drawn in an 86px grid cell on
at most two ~12-character lines. Stock names ("2x EXP Card Type 3 - Weekdays") never say
which hours a coupon works, so the build replaces them with a compact schedule:

    2x EXP M-F 7-11am 30d       Mon-Fri, 07:00-11:00 server time, lasts 30 days
    2x EXP Sa-Su 1-5pm 30d      Sat and Sun, 13:00-17:00
    2x Drop 10am-2pm 10d        every day
    3x EXP anytime 2hrs         all day, expires 2 hours after purchase

Hours are SERVER time, exactly as WZ Item.wz/Cash/0521.img and 0536.img `info/time` give them
("MON:07-11") and as Server.updateActiveCoupons checks them (the nxcoupons table, filled from the
same WZ, against the JVM default zone = config TIMEZONE, GMT-6 on test and live). There is no
room for a zone in the cell; @coupons in game says which zone and converts to Pacific time.

The line break is the client's (the last space that fits), so the pieces are ordered to break
well: "2x EXP M-F" / "7-11am 30d". An overrides.yaml `name` still wins.
"""
from __future__ import annotations

DAYS = ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
WEEKDAYS = frozenset(range(5))
WEEKEND = frozenset({5, 6})
SHORT_DAY = ("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

# A period of 1 on these means HOURS, not a day (CashShopWindowPurchase.toItem / CashShop).
COUPON_HOURS = {5211048: 4, 5360042: 4, 5211060: 2}

# Two lines of the 86px cell (74px of text each) hold about 24 characters.
SOFT_MAX = 24


def parse_times(times):
    """WZ `time` strings -> (days, start, end), days a frozenset of 0=Mon..6=Sun.

    "HOL" (holiday) entries are ignored: the server has no holiday calendar. Returns None when
    the list is empty, malformed, or the days have different hours (no compact form)."""
    days, hours = set(), set()
    for t in times or ():
        day, sep, span = (t or "").partition(":")
        day = day.strip().upper()
        if not sep:
            return None
        if day == "HOL":
            continue
        if day not in DAYS:
            return None
        a, dash, b = span.partition("-")
        if not dash or not a.strip().isdigit() or not b.strip().isdigit():
            return None
        start, end = int(a), int(b)
        if not 0 <= start < end <= 24:
            return None
        days.add(DAYS.index(day))
        hours.add((start, end))
    if not days or len(hours) != 1:
        return None
    start, end = hours.pop()
    return frozenset(days), start, end


def days_label(days):
    """'' for every day, 'M-F', 'Sa-Su', else the days joined ('Mo/We')."""
    if len(days) == 7:
        return ""
    if days == WEEKDAYS:
        return "M-F"
    if days == WEEKEND:
        return "Sa-Su"
    return "/".join(SHORT_DAY[d] for d in sorted(days))


def _h12(h):
    h %= 24
    return (h % 12 or 12), ("am" if h < 12 else "pm")


def hours_label(start, end):
    """(7, 11) -> '7-11am', (10, 14) -> '10am-2pm', (0, 4) -> '12-4am', (0, 24) -> 'anytime'."""
    if (start, end) == (0, 24):
        return "anytime"
    sh, ss = _h12(start)
    eh, es = _h12(end)
    if ss == es and end != 12:
        return f"{sh}-{eh}{es}"
    return f"{sh}{ss}-{eh}{es}"


def duration_label(item_id, period):
    if period == 1 and item_id in COUPON_HOURS:
        return f"{COUPON_HOURS[item_id]}hrs"
    return f"{period}d" if period > 0 else ""


def shop_name(item_id, rate, times, period):
    """The catalogue name for a rate coupon, or None when its WZ schedule has no compact form."""
    sched = parse_times(times)
    if sched is None or not rate:
        return None
    days, start, end = sched
    kind = "EXP" if item_id // 1000 == 5211 else "Drop"
    parts = [f"{rate}x {kind}", days_label(days), hours_label(start, end), duration_label(item_id, period)]
    return " ".join(p for p in parts if p)
