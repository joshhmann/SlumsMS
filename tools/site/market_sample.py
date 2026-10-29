#!/usr/bin/env python3
"""Placeholder market data in the export's shape, until the server's PublicMarketExport is live.

    python3 tools/site/market_sample.py [--out cache/market-sample] [--days 30]

Writes, like the export will (docs/design/track-d-plan.md §5 and the nx-rate contract):
    listings.json             FM shops now: item, qty, price per unit, channel, room, seller kind and name, shop title
    sales.json                recent sales, newest first: item, unit price, qty, source, player side, hour (UTC)
    prices/<YYYY-MM-DD>.json  one file per UTC day: per item open/high/low/close/median, sample count, player share
    nx-rate.json              days: mesos per 1,000 NX (null under 5 trades) plus counts and bot share; today
    economy-public.json       a few public economy numbers, plus days: total mesos, made and spent per UTC day
Every file carries "sample": true, and the market pages show a banner while it does. Item ids are real reachable items
(sections/dbdata.py); prices, names and counts are made up and seeded, so each run gives the same files.
"""
import argparse
import datetime as dt
import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
from sections import dbdata  # noqa: E402

SELLERS = ["Wolf4life", "MapleMomo", "kerningcat", "LilBoogie", "xXHenesysXx", "DrJrNecki", "ShroomKing", "PerionPapi",
           "ellinia_elf", "OrbisOtter", "slimeylimey", "NautilusNate"]
SHOP_TITLES = ["cheap pots!!", "scrolls & stuff", "~ clean gears ~", "ETC for quests", "everything must go", "B> zak helm"]
EXPLICIT = [1082002, 2000000, 2000001, 2000002, 2000003, 2000006, 4000020, 4000021, 4001126, 2040804, 2040805,
            2044702, 1472032, 1452022, 1402006, 1002064, 4010003, 4020004]


def pick_items(data, rnd, n=120):
    ids = [i for i in EXPLICIT if i in data["items"]]
    pool = sorted(i for i, v in data["items"].items() if v["type"] in ("Weapon", "Consume", "Etc", "Glove", "Cap", "Shoes"))
    rnd.shuffle(pool)
    for i in pool:
        if len(ids) >= n:
            break
        if i not in ids:
            ids.append(i)
    return ids


def base_price(item, rnd):
    t = item["type"]
    shop = (item.get("stats") or {}).get("price") or 100
    if t == "Consume":
        return max(30, int(shop * rnd.uniform(0.8, 3)))
    if t == "Etc":
        return max(10, int(shop * rnd.uniform(2, 40)))
    return int(max(shop, 2000) * rnd.uniform(5, 60))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(REPO, "cache", "market-sample"))
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--hs-data")
    ap.add_argument("--today", default="2026-10-26")
    args = ap.parse_args()
    rnd = random.Random(83)
    data = dbdata.load(args.hs_data)
    today = dt.date.fromisoformat(args.today)
    now = dt.datetime.combine(today, dt.time(7, 0), dt.timezone.utc)
    items = pick_items(data, rnd)
    os.makedirs(os.path.join(args.out, "prices"), exist_ok=True)

    def dump(rel, obj):
        with open(os.path.join(args.out, rel), "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=1, sort_keys=True)

    # daily price rollups
    level = {i: base_price(data["items"][i], rnd) for i in items}
    for back in range(args.days - 1, -1, -1):
        day = today - dt.timedelta(days=back)
        rows = {}
        for i in items:
            prev = level[i]
            level[i] = max(1, int(prev * (1 + rnd.uniform(-0.07, 0.06))))
            n = rnd.choice([0, 1, 2, 3, 4, 6, 9, 14, 20])
            if n == 0:
                continue
            hi, lo = int(max(prev, level[i]) * rnd.uniform(1.02, 1.12)), int(min(prev, level[i]) * rnd.uniform(0.88, 0.98))
            rows[str(i)] = {"open": prev, "close": level[i], "high": hi, "low": max(1, lo),
                            "median": (prev + level[i]) // 2, "count": n, "player_share": round(rnd.choice([0, 0, 0, 0.1, 0.3, 0.6]), 2)}
            # the real trades among them (no restocks), which the pages show: here simply all of them
            rows[str(i)]["player_count"] = n
        dump(f"prices/{day.isoformat()}.json", {"sample": True, "date": day.isoformat(), "items": rows})

    # listings now
    listings = []
    for i in items:
        for _ in range(rnd.choice([0, 0, 1, 1, 2, 3])):
            bot = rnd.random() < 0.85
            listings.append({"item_id": i, "qty": rnd.choice([1, 1, 1, 5, 50, 100]),
                             "price": int(level[i] * rnd.uniform(0.9, 1.25)), "channel": rnd.randint(1, 5),
                             "room": rnd.randint(1, 22),
                             "seller_name": rnd.choice(SELLERS), "shop_title": rnd.choice(SHOP_TITLES)})
    dump("listings.json", {"sample": True, "generated_at": now.isoformat(), "listings": listings})

    # recent sales, newest first
    sales = []
    for k in range(300):
        i = rnd.choice(items)
        hour = now - dt.timedelta(hours=1 + k // 12)
        sales.append({"item_id": i, "price": int(level[i] * rnd.uniform(0.85, 1.15)), "qty": rnd.choice([1, 1, 1, 10, 100]),
                      "source": rnd.choice(["shop", "merchant", "trade", "bot"]), "player_side": rnd.random() < 0.15,
                      "hour": hour.replace(minute=0, second=0).isoformat()})
    dump("sales.json", {"sample": True, "generated_at": now.isoformat(), "sales": sales})

    # NX rate: mesos per 1,000 NX
    days, v = [], 4.05e6
    for back in range(args.days - 1, -1, -1):
        day = today - dt.timedelta(days=back)
        v *= 1 + rnd.uniform(-0.03, 0.035)
        n = rnd.choice([3, 6, 8, 11, 15, 22])
        days.append({"date": day.isoformat(), "mesos_per_1000nx": int(round(v, -4)) if n >= 5 else None, "n_trades": n,
                     "n_cash_resales": n - n // 3, "n_code_trades": n // 3, "bot_share": round(rnd.uniform(0.5, 0.95), 2)})
    dump("nx-rate.json", {"sample": True, "days": days,
                          "today": dict(days[-1], as_of=now.isoformat())})

    # daily totals for the public economy page (proposed: economy-public.json "days")
    eco_days, total = [], 380_000_000_000
    for back in range(args.days - 1, -1, -1):
        day = today - dt.timedelta(days=back)
        made = int(rnd.uniform(6e9, 9e9))
        spent = int(made * rnd.uniform(0.7, 1.05))
        total += made - spent
        eco_days.append({"date": day.isoformat(), "mesos_total": total, "mesos_made": made, "mesos_spent": spent})
    dump("economy-public.json", {"sample": True, "as_of": now.isoformat(), "mesos_total": total, "days": eco_days,
                                 "players_share": 0.07, "trades_24h": 1284, "shops_open": len({(l["channel"], l["room"]) for l in listings}),
                                 "mesos_removed_7d": 2_100_000_000, "bots_online_peak": 2947})
    print(f"sample market data for {len(items)} items, {args.days} days -> {args.out}")


if __name__ == "__main__":
    main()
