#!/usr/bin/env python3
"""Validates a Cash Shop catalogue against the server WZ, the window's limits and the rules in
catalog_rules.py / overrides.yaml. Exits 1 when there is any ERROR (warnings do not fail).

    python3 tools/cashshop/lint_catalog.py [cashshop/catalog.tsv] [--wz DIR] [--fresh]

--fresh also fails when the file is not exactly what build_catalog.py would write now
(i.e. someone hand-edited it, or overrides.yaml changed without a rebuild).

Checks: the Java loader's column/range rules (CashShopCatalog.load), no duplicate ids, every id
has WZ data + icon (+ frames for worn equips) + the cash flag, a usable name (<= 40 chars,
<= 64 bytes), gender matches the item-id rule, price > 0, no rule-excluded or hidden ids,
every (tab, category) is one the client window shows (kCats), per-category counts within the
client's guard.
"""
from __future__ import annotations

import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog_rules as R  # noqa: E402
import wzindex  # noqa: E402


def lint(path, wz, ov, kcats):
    errors, warnings = [], []
    lookup = R.item_overrides(ov)
    flags = ov.get("flags", {})
    onsale = {e["ItemId"] for e in wz.commodity if e.get("OnSale") == 1}
    seen = {}
    per_cat = collections.Counter()

    def err(n, i, msg):
        errors.append(f"line {n}: {i}: {msg}")

    for n, f in R.read_catalog(path):
        if len(f) < 8:
            err(n, f[0] if f else "?", f"expected 8 columns, got {len(f)}")
            continue
        try:
            i, price, count, tab, cat, period, gender = (int(x.strip()) for x in f[:7])
        except ValueError:
            err(n, f[0], "non-numeric field")
            continue
        name = f[7]
        if i in seen:
            err(n, i, f"duplicate of line {seen[i]}")
            continue
        seen[i] = n

        # --- the Java loader's own rules (a row failing these is silently dropped at load) ---
        if price <= 0:
            err(n, i, f"price {price} must be > 0")
        if not 1 <= count <= 32767:
            err(n, i, f"count {count} out of range")
        if (R.is_equip(i) or i // 10000 == 500 or i in R.SERVICE_IDS) and count != 1:
            err(n, i, "equips, pets and services must have count 1")
        if not (0 <= tab <= 255 and 0 <= cat <= 255) or period < 0 or gender not in (0, 1, 2):
            err(n, i, "tab/category/period/gender out of range")
        if i // 1000000 == R.PACKAGE_PREFIX_MILLION and i not in R.SERVICE_IDS:
            err(n, i, "package/special id: the loader drops it (no inventory type)")

        # --- the window ---
        if (tab, cat) not in kcats:
            err(n, i, f"{tab}/{cat} is not a category the client window shows (kCats)")
        per_cat[(tab, cat)] += 1
        if not name.strip():
            err(n, i, "blank name")
        elif len(name) > R.MAX_NAME:
            err(n, i, f"name longer than {R.MAX_NAME} chars (the loader cuts it)")
        try:
            if len(name.encode("latin-1")) > R.MAX_NAME_BYTES:
                err(n, i, f"name over {R.MAX_NAME_BYTES} bytes")
        except UnicodeEncodeError:
            err(n, i, "name has characters the client charset cannot show")

        # --- WZ ---
        o = lookup(i)
        forced = o.get("hide") is False
        p = R.wz_problem(i, wz)
        if p and not forced:
            err(n, i, p)
        if not o.get("name") and i not in R.SERVICE_IDS and not forced:
            np = R.name_problem(wz.name(i), strict=i not in onsale)
            if np:
                err(n, i, np)
        want = int(o.get("gender", R.gender_of(i)))
        if gender != want:
            err(n, i, f"gender {gender}, but the item id says {want} (client/server refusal rule)")

        # --- rules and overrides ---
        if not forced:
            why = R.never_reason(i)
            if why:
                err(n, i, f"never sellable: {why}")
            if R.ring_record_needed(i, wz):
                err(n, i, "ring needs a partner ring record")
            for flag, (ids, desc) in R.FLAG_GATED.items():
                if i in ids and not flags.get(flag, False):
                    err(n, i, f"flag {flag} is off: {desc}")
        if o.get("hide") is True:
            err(n, i, "hidden in overrides.yaml" + (f" ({o['reason']})" if o.get("reason") else ""))
        if "price" in o and int(o["price"]) != price:
            err(n, i, f"price {price} but overrides.yaml says {o['price']}")
        if i // 1000 in R.RATE_COUPON_TYPES and period == 0:
            warnings.append(f"line {n}: {i}: rate coupon with period 0 is PERMANENT")
        if price < 300:
            warnings.append(f"line {n}: {i}: price {price} is unusually low")

    for (t, c), k in per_cat.items():
        if k > R.MAX_PER_CATEGORY:
            errors.append(f"category {t}/{c}: {k} rows > client guard {R.MAX_PER_CATEGORY}")
    for (t, c) in kcats:
        if (t, c) not in per_cat:
            warnings.append(f"category {t}/{c} {kcats[(t, c)]} is empty (the window shows it anyway)")
    return errors, warnings, per_cat, len(seen)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("catalog", nargs="?", default=R.DEFAULT_CATALOG)
    ap.add_argument("--wz")
    ap.add_argument("--overrides", default=R.DEFAULT_OVERRIDES)
    ap.add_argument("--client-cpp", help="cashshopwnd.cpp to read kCats from")
    ap.add_argument("--fresh", action="store_true", help="also fail if the file differs from a fresh build")
    ap.add_argument("-q", "--quiet", action="store_true", help="do not print warnings")
    a = ap.parse_args(argv)

    wz = wzindex.load(a.wz, quiet=True)
    ov = R.load_overrides(a.overrides)
    kcats, src = R.load_kcats(a.client_cpp)
    errors, warnings, per_cat, total = lint(a.catalog, wz, ov, kcats)

    if a.fresh:
        import build_catalog as B
        rows, excluded, _, removed = B.build(wz, ov)
        if open(a.catalog, encoding="utf-8").read() != B.render(rows, ov, excluded, removed):
            errors.append("catalog differs from a fresh build_catalog.py run (hand edit or stale build)")

    print(f"{a.catalog}: {total} rows in {len(per_cat)} categories (kCats from {src or 'built-in copy'})")
    for (t, c), k in sorted(per_cat.items()):
        print(f"  {t}/{c:<2} {kcats.get((t, c), '??'):18s} {k:5d}")
    if not a.quiet:
        for w in warnings:
            print("WARN  " + w)
    for e in errors[:200]:
        print("ERROR " + e)
    if len(errors) > 200:
        print(f"... and {len(errors) - 200} more errors")
    print(f"{len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
