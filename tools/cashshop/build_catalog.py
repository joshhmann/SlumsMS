#!/usr/bin/env python3
"""Regenerates cashshop/catalog.tsv, deterministically, from:

  (a) the stock v83 Etc/Commodity.img on-sale rows (their price, count and, for rate coupons,
      period);
  (b) every other cash item the server WZ has data for -- newer equips (info/cash=1), pets,
      effects, weathers, chairs (301xxxx), mounts ... -- priced by category band;
  (c) tools/cashshop/overrides.yaml, which always wins.

Rules that are facts about the server or the window (what can be delivered, what has a use
handler, which categories the window shows) live in catalog_rules.py. See README.md.

    python3 tools/cashshop/build_catalog.py [--wz DIR] [--out cashshop/catalog.tsv]
                                            [--featured FILE ...] [--report] [--check]

--check builds in memory and exits 1 if the file on disk differs (CI / pre-commit).
"""
from __future__ import annotations

import argparse
import collections
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog_rules as R  # noqa: E402
import wzindex  # noqa: E402

HEADER_FILE = os.path.join(R.HERE, "catalog_header.txt")


def pick_stock(rows):
    """One stock commodity row per item: the biggest bundle, then the lowest serial."""
    return min(rows, key=lambda e: (-e.get("Count", 1), e["SN"]))


def read_featured(paths):
    """Shortlist files -> ({itemId: 'featured'|'new'}, {itemId: suggested price}).
    First column is the item id; '#' comments and the header line are skipped. A column holding
    'new' marks the id New rather than Featured. A header column named suggested_price (or
    price) is read too, but only used when overrides.yaml sets use_shortlist_prices."""
    flags, prices = {}, {}
    for p in paths:
        if not os.path.isfile(p):
            print(f"warning: featured file {p} not found", file=sys.stderr)
            continue
        price_col = None
        for line in open(p, encoding="utf-8"):
            if line.startswith("#"):
                continue
            f = line.rstrip("\n").split("\t")
            if not f or not f[0].strip().isdigit():
                cols = [x.strip().lower() for x in f]
                for name in ("suggested_price", "price"):
                    if name in cols:
                        price_col = cols.index(name)
                        break
                continue
            i = int(f[0])
            flags[i] = "new" if any(x.strip().lower() == "new" for x in f[1:]) else "featured"
            if price_col is not None and price_col < len(f) and f[price_col].strip().isdigit():
                prices[i] = int(f[price_col])
    return flags, prices


def build(wz, ov, featured_extra=(), repo=R.REPO):
    lookup = R.item_overrides(ov)
    flags = ov.get("flags", {})
    stylist_ids = R.script_ids(repo)

    by_item = collections.defaultdict(list)
    for e in wz.commodity:
        by_item[e["ItemId"]].append(e)
    onsale = {i: pick_stock([e for e in es if e.get("OnSale") == 1])
              for i, es in by_item.items() if any(e.get("OnSale") == 1 for e in es)}
    offsale = {i: pick_stock(es) for i, es in by_item.items() if i not in onsale}

    feat_paths = [p if os.path.isabs(p) else os.path.join(R.HERE, p) for p in ov.get("featured_files", [])]
    featured, shortlist_prices = read_featured(feat_paths + list(featured_extra))
    use_sl_prices = bool(ov.get("use_shortlist_prices", False))

    candidates = set(onsale) | set(wz.items) | set(lookup.single_ids)
    excluded = collections.Counter()
    excluded_ids = collections.defaultdict(list)
    removed_stock = []          # stock on-sale rows that are not sold, for the header
    rows = []

    def exclude(i, why):
        excluded[why] += 1
        excluded_ids[why].append(i)
        if i in onsale:
            removed_stock.append((i, why))

    for i in sorted(candidates):
        o = lookup(i)
        forced = o.get("hide") is False          # overrides always win, both ways
        if o.get("hide") is True:
            exclude(i, "hidden in overrides.yaml" + (f": {o['reason']}" if o.get("reason") else ""))
            continue

        why = None
        prefix = i // 10000
        cat_key = o.get("category") or R.category_key(i, ov)
        if i // 1000000 == R.PACKAGE_PREFIX_MILLION and i not in R.SERVICE_IDS:
            why = "package / special (no inventory type; nothing unpacks it)"
        else:
            why = R.never_reason(i)
        if why is None and cat_key is None:
            why = "no shop category for this item type"
        if why is None and R.ring_record_needed(i, wz):
            why = "ring needs a partner ring record (not one the window's partner flow sells)"
        if why is None:
            for flag, (ids, desc) in R.FLAG_GATED.items():
                if i in ids and not flags.get(flag, False):
                    why = f"flag {flag} is off ({desc})"
        if why is None and i not in onsale:
            pol = R.NEWER_POLICY.get(prefix, "stock")
            if pol == "stock":
                why = "newer/off-sale variant of a type sold only from stock rows"
            elif pol == "script" and i not in stylist_ids:
                why = "beauty coupon no NPC script reads"
            elif pol == "ids" and i not in R.NEWER_IDS.get(prefix, ()):
                why = "newer variant without a verified handler"
        if why is None and R.is_equip(i) and 130 <= prefix < 170:
            why = "real weapon, not a cash cover"
        if why is None:
            why = R.wz_problem(i, wz)
        name = o.get("name") or wz.name(i)
        if why is None and not o.get("name"):
            why = R.name_problem(name, strict=i not in onsale)
        if why is not None and not forced:
            exclude(i, why)
            continue
        if name is None:
            exclude(i, "forced in overrides.yaml but has no name")
            continue
        if cat_key is None:
            exclude(i, "forced in overrides.yaml but has no category")
            continue

        tab, cat, pending = R.resolve_category(cat_key, ov)
        st = onsale.get(i)
        count = int(o.get("count", st["Count"] if st else 1))
        period = 0
        if st and i // 1000 in R.RATE_COUPON_TYPES:
            period = int(st.get("Period", 0))
        period = int(o.get("period", period))
        flag = o.get("flag") or ("featured" if o.get("featured") else "new" if o.get("new") else featured.get(i))
        price = o.get("price")
        if price is None and use_sl_prices and i not in onsale:
            price = shortlist_prices.get(i)
        rows.append({
            "id": i, "price": price, "count": count, "tab": tab, "cat": cat,
            "period": period, "gender": int(o.get("gender", R.gender_of(i))),
            "name": R.clean_name(name), "pending": pending, "flag": flag,
            "tier": o.get("tier"), "source": "stock" if st else "offsale" if i in offsale else "newer",
            "stock_price": st.get("Price") if st else None,
            "offsale_price": offsale[i].get("Price") if i in offsale else None,
        })

    price_rows(rows, ov)
    sold = {r["id"] for r in rows}
    unsold = sorted(i for i in featured if i not in sold)
    if unsold:
        print(f"warning: {len(unsold)} shortlisted ids are not sold (excluded by a rule): "
              + " ".join(f"{i}({next((w for w, ids in excluded_ids.items() if i in ids), 'no data')})" for i in unsold[:20]),
              file=sys.stderr)
    flag_rank = {"featured": 0, "new": 1}
    rows.sort(key=lambda r: (r["tab"], r["cat"], flag_rank.get(r["flag"], 2), r["id"]))
    return rows, excluded, excluded_ids, removed_stock


def price_rows(rows, ov):
    """Stock rows keep the stock price. Everything else gets its band: the median stock price
    of the same item type (id // 10000), else of its shop category, else prices.band_defaults,
    else prices.default. An off-sale stock commodity keeps its own price when it is sane."""
    pc = ov.get("prices", {})
    rnd = int(pc.get("round_to", 100))
    min_samples = int(pc.get("min_samples", 3))
    floor = int(pc.get("minimum", 500))
    defaults = {int(k): int(v) for k, v in pc.get("band_defaults", {}).items()}
    tiers = {k: float(v) for k, v in ov.get("tiers", {}).items()}

    by_prefix, by_cat = collections.defaultdict(list), collections.defaultdict(list)
    for r in rows:
        if r["source"] == "stock" and r["count"] == 1 and r["id"] not in R.SERVICE_IDS:
            by_prefix[r["id"] // 10000].append(r["stock_price"])
            by_cat[(r["tab"], r["cat"])].append(r["stock_price"])

    def band(r):
        p = r["id"] // 10000
        if p in defaults:
            return defaults[p]
        if len(by_prefix[p]) >= min_samples:
            return statistics.median(by_prefix[p])
        if len(by_cat[(r["tab"], r["cat"])]) >= min_samples and not r["pending"]:
            return statistics.median(by_cat[(r["tab"], r["cat"])])
        return int(pc.get("default", 3000))

    for r in rows:
        if r["price"] is not None:          # explicit override
            r["price"] = int(r["price"])
            continue
        if r["source"] == "stock":
            price = r["stock_price"]
        else:
            b = band(r)
            op = r["offsale_price"]
            price = op if op and b / 2 <= op <= b * 3 else b
            if r["tier"]:
                price *= tiers.get(r["tier"], 1.0)
            price = max(floor, int(round(price / rnd)) * rnd)
        r["price"] = int(price)


def render(rows, ov, excluded, removed_stock):
    head = open(HEADER_FILE, encoding="utf-8").read().rstrip("\n").split("\n")
    out = list(head)
    pend = collections.Counter(r["pending"] for r in rows if r["pending"])
    if pend:
        out.append("#")
        out.append("# PENDING categories -- the window has no category for these yet, so they sit in an")
        out.append("# existing one (tools/cashshop/overrides.yaml pending_categories moves them):")
        for g, n in sorted(pend.items()):
            t, c, _ = R.resolve_category(g, ov)
            out.append(f"#   {g:9s} {n:5d} rows  {R.PENDING_GROUPS.get(g, g)} -> now in {t}/{c} {R.KCATS.get((t, c), '?')}")
    if removed_stock:
        out.append("#")
        out.append("# STOCK on-sale commodities NOT sold (reason from the tool's rules or overrides.yaml):")
        grouped = collections.defaultdict(list)
        for i, why in removed_stock:
            grouped[why].append(i)
        for why in sorted(grouped):
            ids = sorted(grouped[why])
            shown = " ".join(map(str, ids[:12])) + (f" ... (+{len(ids) - 12})" if len(ids) > 12 else "")
            out.append(f"#   {why}: {shown}")
    out.append("#")
    out.append(f"# {len(rows)} rows. itemId\tprice\tcount\ttab\tcategory\tperiod\tgender\tname")
    for r in rows:
        out.append("\t".join(str(x) for x in (r["id"], r["price"], r["count"], r["tab"], r["cat"],
                                               r["period"], r["gender"], r["name"])))
    return "\n".join(out) + "\n"


def summary(rows, excluded, kcats):
    lines = []
    per = collections.Counter((r["tab"], r["cat"]) for r in rows)
    src = collections.Counter(r["source"] for r in rows)
    lines.append(f"{len(rows)} rows  (stock {src['stock']}, off-sale stock {src['offsale']}, newer {src['newer']})")
    for (t, c), n in sorted(per.items()):
        ps = sorted(r["price"] for r in rows if (r["tab"], r["cat"]) == (t, c))
        lines.append(f"  {t}/{c:<2} {kcats.get((t, c), '??'):18s} {n:5d}   price min {ps[0]:>6} median {int(statistics.median(ps)):>6} max {ps[-1]:>6}")
    fl = collections.Counter(r["flag"] for r in rows if r["flag"])
    if fl:
        lines.append("  flags: " + ", ".join(f"{k} {v}" for k, v in sorted(fl.items())))
    lines.append(f"excluded {sum(excluded.values())}:")
    for why, n in excluded.most_common():
        lines.append(f"  {n:6d}  {why}")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wz", help="server WZ XML dir (default: $WZ_PATH, /root/work/bake/wz, ./wz)")
    ap.add_argument("--overrides", default=R.DEFAULT_OVERRIDES)
    ap.add_argument("--out", default=R.DEFAULT_CATALOG)
    ap.add_argument("--featured", action="append", default=[], help="extra shortlist TSV (first column itemId)")
    ap.add_argument("--check", action="store_true", help="do not write; exit 1 if --out is stale")
    ap.add_argument("--report", action="store_true", help="also list excluded ids per reason")
    a = ap.parse_args(argv)

    wz = wzindex.load(a.wz)
    ov = R.load_overrides(a.overrides)
    rows, excluded, excluded_ids, removed_stock = build(wz, ov, a.featured)
    text = render(rows, ov, excluded, removed_stock)
    kcats, _ = R.load_kcats()
    print(summary(rows, excluded, kcats))
    if a.report:
        for why, ids in sorted(excluded_ids.items()):
            print(f"\n== {why} ({len(ids)})")
            print(" ".join(f"{i}:{wz.name(i) or '-'}" for i in ids[:400]))

    if a.check:
        cur = open(a.out, encoding="utf-8").read() if os.path.exists(a.out) else ""
        if cur != text:
            print(f"STALE: {a.out} differs from a fresh build; run build_catalog.py", file=sys.stderr)
            return 1
        print(f"{a.out} is up to date")
        return 0
    tmp = a.out + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    os.replace(tmp, a.out)
    print(f"wrote {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
