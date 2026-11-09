#!/usr/bin/env python3
"""Unobtainable-item scan for SCRIPTS: every item a portal / NPC / event / reactor / item script REQUIRES that has no source.

    python3 tools/questcheck/script_item_scan.py --wz <server wz dir> [--scripts scripts] [--db-dir <dir>]
        [--migrations database/migrations] [--known tools/questcheck/known_item_sources.tsv] [--md out.md] [--json out.json]

quest_check.py already covers items the QUEST data asks for. This tool covers the gap it cannot see: a script that
asks for an item in code (Von Leon's Prison Key 4032860, B-26, was the example: portal 211070101.1.js checked
haveItem(4032860) and nothing in the game gave it).

A REQUIRE is a script call that needs the item: haveItem / hasItem / itemQuantity / getItemQuantity /
countItem / haveItemWithId, or a gainItem(<id>, <negative>) take. A GRANT is any other use of the 7-digit id in a
script (gainItem with a positive or no quantity, a reward array, a map of rewards ...), so a script that only
checks for an item never counts as a source for it.

An item has a source when ANY of these hold: a mob drop, reactor drop, global drop, shop row (the --db-dir TSV
exports, as in quest_check.py), a drop/reactor/shop INSERT in a database migration (--migrations), a quest reward
in Act.img, a Maker recipe output, a script GRANT, or a line in the --known file (a source in Java code: the
reason is required, so the list explains itself).

Output: ERROR = required and no source at all; INFO = required, the only source is a script GRANT in another
file (check by eye). Exit code 1 when any ERROR is left, so it can run as a test. The findings are leads: an item
can also come from a Java reward table this tool cannot see (add it to the known file with the reason).
"""
import argparse
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import quest_check as qc  # noqa: E402

ITEM = r"(\d{7})"
# the first argument of the call is the item id (a number literal)
REQUIRE_CALLS = re.compile(
    r"\b(?:haveItem|hasItem|haveItemWithId|itemQuantity|getItemQuantity|countItem|getItemCount)\s*\(\s*" + ITEM)
TAKE_CALL = re.compile(r"\bgainItem\s*\(\s*" + ITEM + r"\s*,\s*-\s*\d+")
LITERAL = re.compile(r"(?<![\d.])" + ITEM + r"(?![\d.])")
COMMENT_BLOCK = re.compile(r"/\*.*?\*/", re.S)
COMMENT_LINE = re.compile(r"//[^\n]*")
MIGRATION_SOURCE = re.compile(r"\b(?:drop_data|reactordrops|shopitems|drop_data_global)\b", re.I)


def strip_comments(text):
    return COMMENT_LINE.sub("", COMMENT_BLOCK.sub("", text))


def scan_scripts(scripts):
    """-> (requires: item -> [(file, line)], grants: item -> {files})"""
    requires = collections.defaultdict(list)
    grants = collections.defaultdict(set)
    for root, _, files in os.walk(scripts):
        for f in sorted(files):
            if not f.endswith(".js"):
                continue
            path = os.path.join(root, f)
            rel = os.path.relpath(path, scripts)
            with open(path, encoding="utf-8", errors="replace") as fh:
                text = strip_comments(fh.read())
            req_spans = set()
            for rx in (REQUIRE_CALLS, TAKE_CALL):
                for m in rx.finditer(text):
                    req_spans.add(m.start(1))
                    requires[int(m.group(1))].append((rel, text.count("\n", 0, m.start()) + 1))
            for m in LITERAL.finditer(text):
                if m.start(1) not in req_spans:
                    grants[int(m.group(1))].add(rel)
    return requires, grants


def migration_sources(mig_dir):
    out = collections.defaultdict(set)
    if not mig_dir or not os.path.isdir(mig_dir):
        return out
    for f in sorted(os.listdir(mig_dir)):
        if not f.endswith(".sql"):
            continue
        with open(os.path.join(mig_dir, f), encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.lstrip().startswith("--"):
                    continue
                # a values row: (dropper, item, ...) in a migration that writes a drop / shop table
                pass
        with open(os.path.join(mig_dir, f), encoding="utf-8", errors="replace") as fh:
            text = "\n".join(l for l in fh.read().splitlines() if not l.lstrip().startswith("--"))
        if MIGRATION_SOURCE.search(text):
            for m in LITERAL.finditer(text):
                out[int(m.group(1))].add(f)
    return out


def cashshop_items(path):
    out = set()
    if path and os.path.isfile(path):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("#"):
                    continue
                for m in LITERAL.finditer(line.split("\t", 1)[0]):
                    out.add(int(m.group(1)))
    return out


def load_known(path):
    known = {}
    if path and os.path.isfile(path):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t", 1)
                if len(parts) != 2 or not parts[0].isdigit() or not parts[1].strip():
                    raise SystemExit("known file: '%s' needs <itemid><TAB><reason>" % line)
                known[int(parts[0])] = parts[1].strip()
    return known


def quest_rewards(g):
    out = set()
    for el in g.act.values():
        for phase in el:
            for item, count, _ in qc.items_of(qc.children(phase).get("item")):
                if item and count > 0:
                    out.add(item)
    return out


def scan(wz, scripts, db_dir=None, migrations=None, known=None, character_list=None, cashshop=None):
    g = qc.load_wz(wz, character_list)
    src = qc.Sources(db_dir, None)
    requires, grants = scan_scripts(scripts)
    mig = migration_sources(migrations)
    rewards = quest_rewards(g)
    cash = cashshop_items(cashshop)
    findings = []
    for item in sorted(requires):
        where = requires[item]
        have = []
        if item in src.mob_drops:
            have.append("mob drop")
        if item in src.reactor_drops:
            have.append("reactor")
        if item in src.global_drops:
            have.append("global drop")
        if item in src.shop_items:
            have.append("shop")
        if item in mig:
            have.append("migration " + sorted(mig[item])[0])
        if item in rewards:
            have.append("quest reward")
        if item in cash:
            have.append("cash shop")
        if item in g.maker_outputs:
            have.append("maker")
        if known and item in known:
            have.append("known: " + known[item])
        if grants.get(item):
            have.append("script grant in " + sorted(grants[item])[0])
        name = g.item_name(item)
        if not have:
            findings.append(("ERROR", item, name, where, "no source"))
        elif all(h.startswith("script grant") for h in have):
            findings.append(("INFO", item, name, where, "; ".join(have)))
    return findings, len(requires)


def render(findings, n_required):
    lines = ["# Script item scan", "",
             "%d distinct items are required by scripts; %d findings." % (n_required, len(findings)), ""]
    for sev, item, name, where, why in sorted(findings, key=lambda f: (f[0] != "ERROR", f[1])):
        sites = ", ".join("%s:%d" % w for w in where[:4])
        lines.append("- %s %d (%s): %s; required at %s" % (sev, item, name, why, sites))
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wz", required=True)
    ap.add_argument("--scripts", default="scripts")
    ap.add_argument("--db-dir")
    ap.add_argument("--migrations")
    ap.add_argument("--known")
    ap.add_argument("--cashshop", help="cashshop/catalog.tsv (first column = item id)")
    ap.add_argument("--character-list")
    ap.add_argument("--md")
    ap.add_argument("--json")
    a = ap.parse_args(argv)
    findings, n = scan(a.wz, a.scripts, a.db_dir, a.migrations, load_known(a.known), a.character_list, a.cashshop)
    text = render(findings, n)
    if a.md:
        with open(a.md, "w") as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)
    if a.json:
        with open(a.json, "w") as fh:
            json.dump([{"severity": s, "item": i, "name": nm, "required_at": w, "why": y}
                       for s, i, nm, w, y in findings], fh, indent=1)
    return 1 if any(f[0] == "ERROR" for f in findings) else 0


if __name__ == "__main__":
    sys.exit(main())
