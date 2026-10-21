#!/usr/bin/env python3
"""Check the gachapon pool file and print its odds.

    python3 tools/gachapon/gachapon_pools.py validate [--wz DIR] [--file F]
    python3 tools/gachapon/gachapon_pools.py odds [TOWN ...] [--markdown] [--top N] [--items]

``validate`` checks the structure and every item id against the server WZ (exists, has a name,
not a quest item, not an expiring item, not a cash effect) and the rules the server enforces
(tiers known, weights positive, a quantity within the item's stack size). Exit 1 on an error.

``odds`` prints, per machine, each tier's chance per ticket and the most likely items, or with
``--markdown`` the short table that goes into the release notes.

The server reads the same file (soloMapling/Gachapon/GachaPools.java) and computes the same
odds; ``!gachasim <town> <n>`` checks them in game. Standard library + PyYAML.
"""
from __future__ import annotations

import argparse
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import wzitems  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_FILE = os.path.join(REPO, "src", "main", "java", "soloMapling", "Gachapon", "gachapon-pools.yaml")
TIERS = ("common", "uncommon", "rare", "jackpot")
ENTRY = re.compile(r"^(\d{7})(?:\s+x(\d+))?(?:\s+w(\d+))?$")


class Pools:
    def __init__(self, doc):
        self.errors = []
        self.tiers = {}
        tiers = (doc or {}).get("tiers") or {}
        for name in TIERS:
            t = tiers.get(name)
            if t is None:
                self.errors.append(f"tiers: '{name}' is missing")
                continue
            w = int(t.get("weight", 0))
            if w <= 0:
                self.errors.append(f"tiers.{name}: weight must be > 0")
            self.tiers[name] = {"weight": w, "announce": bool(t.get("announce", False))}
        for extra in set(tiers) - set(TIERS):
            self.errors.append(f"tiers: unknown tier '{extra}'")
        self.shared = self._lists("shared", (doc or {}).get("shared") or {})
        self.towns = {}
        for key, town in ((doc or {}).get("towns") or {}).items():
            if not isinstance(town, dict):
                self.errors.append(f"towns.{key}: not a map")
                continue
            self.towns[key] = {
                "name": town.get("name") or key,
                "npc": int(town.get("npc", 0) or 0),
                "maps": [int(m) for m in (town.get("maps") or [])],
                "lists": self._lists(f"towns.{key}", town),
            }
            if not self.towns[key]["npc"]:
                self.errors.append(f"towns.{key}: no npc id")

    def _lists(self, where, node):
        out = {}
        for name in TIERS:
            entries = []
            for raw in node.get(name) or []:
                m = ENTRY.match(str(raw).strip())
                if not m:
                    self.errors.append(f"{where}.{name}: bad entry '{raw}'")
                    continue
                q, w = int(m.group(2) or 1), int(m.group(3) or 1)
                if q <= 0 or w <= 0:
                    self.errors.append(f"{where}.{name}: '{raw}' needs a quantity and weight > 0")
                    continue
                entries.append((int(m.group(1)), q, w))
            out[name] = entries
        for extra in set(node) - set(TIERS) - {"name", "npc", "maps"}:
            self.errors.append(f"{where}: unknown key '{extra}'")
        return out

    def machine(self, key):
        """{tier: [(id, qty, weight)]} for a town: its own lists plus the shared ones."""
        lists = self.towns[key]["lists"]
        return {t: lists[t] + self.shared[t] for t in TIERS}

    def odds(self, key):
        """[(tier, tier chance, [(id, qty, chance)])] per ticket, empty tiers dropped and renormalised."""
        m = self.machine(key)
        live = [t for t in TIERS if m[t]]
        total = sum(self.tiers[t]["weight"] for t in live)
        out = []
        for t in live:
            tp = self.tiers[t]["weight"] / total
            wsum = sum(w for _, _, w in m[t])
            out.append((t, tp, [(i, q, tp * w / wsum) for i, q, w in m[t]]))
        return out


def load(path):
    with open(path, encoding="utf-8") as fh:
        return Pools(yaml.safe_load(fh))


def validate(pools, items):
    errors = list(pools.errors)
    warnings = []
    seen_npc = {}
    for key, town in pools.towns.items():
        if town["npc"] in seen_npc:
            errors.append(f"towns.{key}: npc {town['npc']} is also {seen_npc[town['npc']]}")
        seen_npc[town["npc"]] = key
    groups = [("shared", pools.shared)] + [(f"towns.{k}", t["lists"]) for k, t in pools.towns.items()]
    for where, lists in groups:
        dup = {}
        for tier, entries in lists.items():
            for i, q, _ in entries:
                x = items.get(i)
                tag = f"{where}.{tier}: {i}"
                if x is None:
                    errors.append(f"{tag} is not in the WZ")
                    continue
                name = (x.get("name") or "").strip()
                if not name:
                    errors.append(f"{tag} has no name in String.wz")
                if x.get("quest"):
                    errors.append(f"{tag} ({name}) is a quest item")
                if x.get("timeLimited"):
                    errors.append(f"{tag} ({name}) expires")
                if x["tab"] == "CASH" and not 1000000 <= i < 2000000:
                    errors.append(f"{tag} ({name}) is a cash-tab item the machine cannot give")
                if name.startswith("GM "):
                    errors.append(f"{tag} ({name}) is a GM item")
                if i // 10000 == 210:
                    errors.append(f"{tag} ({name}) summons a boss")
                if x["tab"] == "EQUIP" and q != 1:
                    errors.append(f"{tag} ({name}) is an equip: quantity must be 1")
                slot_max = x.get("slotMax") or (1 if x["tab"] in ("EQUIP", "SETUP") else 100)
                if x["tab"] != "EQUIP" and q > slot_max:
                    errors.append(f"{tag} ({name}) x{q} is over its stack size {slot_max}")
                pct = re.search(r"(\d+)%", name)
                if x["tab"] == "USE" and i // 10000 == 204 and pct and int(pct.group(1)) != x.get("success"):
                    errors.append(f"{tag} ({name}) really succeeds {x.get('success')}%")
                if x.get("untradeable") and x["tab"] != "SETUP" and not x.get("cash"):
                    warnings.append(f"{tag} ({name}) is untradeable")
                if i in dup:
                    errors.append(f"{tag} ({name}) is listed twice (also in {dup[i]})")
                dup[i] = tier
    for key in pools.towns:
        for tier, entries in pools.machine(key).items():
            ids = [i for i, _, _ in entries]
            if len(ids) != len(set(ids)):
                d = sorted({i for i in ids if ids.count(i) > 1})
                errors.append(f"towns.{key}.{tier}: also in shared: {d[:6]}")
        if not any(pools.machine(key).values()):
            errors.append(f"towns.{key}: the machine gives nothing")
    return errors, warnings


def fmt_pct(p):
    if p >= 0.01:
        return f"{p * 100:.1f}%"
    if p >= 0.001:
        return f"{p * 100:.2f}%"
    return f"{p * 100:.3f}%"


def one_in(p):
    if p <= 0:
        return "never"
    n = 1 / p
    return f"1 in {n:.1f}" if n < 10 else f"1 in {round(n):,}"


def print_odds(pools, items, keys, top, show_items):
    for key in keys:
        town = pools.towns[key]
        print(f"== {town['name']} (npc {town['npc']}, {key})")
        for tier, tp, entries in pools.odds(key):
            ann = "  announced" if pools.tiers[tier]["announce"] else ""
            print(f"  {tier:<9} {fmt_pct(tp):>7}  ({one_in(tp)}), {len(entries)} items{ann}")
            if show_items or tier in ("rare", "jackpot"):
                ranked = sorted(entries, key=lambda e: -e[2])
                for i, q, p in ranked[: (len(ranked) if show_items else top)]:
                    nm = (items.get(i, {}).get("name") or "?").strip()
                    qs = f" x{q}" if q > 1 else ""
                    print(f"      {fmt_pct(p):>8}  {one_in(p):>14}  {i} {nm}{qs}")


def print_markdown(pools, items, keys):
    t = pools.tiers
    total = sum(t[x]["weight"] for x in TIERS)
    print("| Tier | Chance per ticket | Server notice |")
    print("|---|---|---|")
    for x in TIERS:
        p = t[x]["weight"] / total
        print(f"| {x.capitalize()} | {fmt_pct(p)} ({one_in(p)}) | {'yes' if t[x]['announce'] else 'no'} |")
    print()
    print("| Machine | Items | Chaos Scroll | White Scroll | A given rare item | Any jackpot chair |")
    print("|---|---|---|---|---|---|")
    for key in keys:
        odds = {tier: (tp, e) for tier, tp, e in pools.odds(key)}
        n = sum(len(e) for _, e in odds.values())

        def p_of(item_id):
            for _, e in odds.values():
                for i, _, p in e:
                    if i == item_id:
                        return p
            return 0

        rare = odds.get("rare")
        rare_each = (rare[0] / len(rare[1])) if rare and rare[1] else 0
        chairs = [p for i, _, p in (odds.get("jackpot") or (0, []))[1] if i // 10000 in (301,)]
        chair = sum(chairs)
        print(f"| {pools.towns[key]['name']} | {n} | {one_in(p_of(2049100))} | {one_in(p_of(2340000))} "
              f"| about {one_in(rare_each)} | {one_in(chair)} |")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=("validate", "odds"))
    ap.add_argument("towns", nargs="*", help="town keys for odds (default: all)")
    ap.add_argument("--file", default=DEFAULT_FILE)
    ap.add_argument("--wz", default=None)
    ap.add_argument("--markdown", action="store_true")
    ap.add_argument("--items", action="store_true", help="list every item's odds, not just rare/jackpot")
    ap.add_argument("--top", type=int, default=5)
    a = ap.parse_args(argv)

    pools = load(a.file)
    items = wzitems.load(a.wz, quiet=a.command == "odds")
    if a.command == "validate":
        errors, warnings = validate(pools, items)
        for w in warnings:
            print("warning:", w)
        for e in errors:
            print("ERROR:", e)
        n = sum(len(v) for v in pools.shared.values()) + sum(
            len(v) for t in pools.towns.values() for v in t["lists"].values())
        print(f"{len(pools.towns)} machines, {n} entries: {len(errors)} errors, {len(warnings)} warnings")
        return 1 if errors else 0
    keys = a.towns or list(pools.towns)
    for k in keys:
        if k not in pools.towns:
            print(f"unknown town '{k}'; towns: {', '.join(pools.towns)}")
            return 2
    if a.markdown:
        print_markdown(pools, items, keys)
    else:
        print_odds(pools, items, keys, a.top, a.items)
    return 0


if __name__ == "__main__":
    sys.exit(main())
