"""Catalog the skills a server's Skill.wz holds, per job, optionally against a second (base) Skill.wz.

Reads the XML form of Skill.wz (one ``<job>.img.xml`` per job, as the server's ``wz/`` directory holds it) and the
matching ``String.wz/Skill.img.xml`` for names. For every player-job skill it reports the id, name, kind, maximum
level and - when a base tree is given - how it compares with the base:

* kind: ``buff`` when the max-level entry has ``time`` and the skill has no ``hit`` or ``ball`` node (nothing that
  lands on a mob - ``SkillFactory`` tells a buff from an attack the same way); ``attack`` when the max-level entry has
  ``damage``, ``fixdamage`` or ``mad`` and the skill has a ``hit``, ``ball`` or ``action`` node; otherwise
  ``passive``. Read naively (``damage``/``mad`` = attack), Meditation, Bless, Infinity and the warrior charges -
  ``mad``/``damage`` stat boosts - and passives such as Critical Shot would all count as attacks.
* status: ``stock`` (same id and name in the base), ``renamed`` (same id, different name), ``added`` (not in the
  base) or ``removed`` (in the base only).

Usage:

    python3 tools/skill-catalog/skill_catalog.py --wz <Server>/wz [--base-wz <Cosmic>/wz] \
        [--markdown out.md [--preface preface.md] [--title TITLE]] [--tsv out.tsv]

``docs/real-bots/mapleroot-skills.md`` is ``--wz`` MapleRoot's server ``wz/``, ``--base-wz`` upstream Cosmic's, with
``--preface tools/skill-catalog/preface.md --title "MapleRoot skills"``.

``--wz`` is a directory holding ``Skill.wz/`` and ``String.wz/Skill.img.xml``. The TSV (id, job, max level, kind,
name) is the fixture the bot skill-plan tests check their ids against.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

# Player jobs, in the order they are listed. GM jobs (800, 900, 910) and the non-job images (MobSkill, ...) are
# left out.
JOBS = {
    0: "Beginner", 100: "Warrior", 110: "Fighter", 111: "Crusader", 112: "Hero", 113: "Hero (5th)",
    120: "Page", 121: "White Knight", 122: "Paladin", 123: "Paladin (5th)",
    130: "Spearman", 131: "Dragon Knight", 132: "Dark Knight", 133: "Dark Knight (5th)",
    200: "Magician", 210: "F/P Wizard", 211: "F/P Mage", 212: "F/P Arch Mage", 213: "F/P Arch Mage (5th)",
    220: "I/L Wizard", 221: "I/L Mage", 222: "I/L Arch Mage", 223: "I/L Arch Mage (5th)",
    230: "Cleric", 231: "Priest", 232: "Bishop", 233: "Bishop (5th)",
    300: "Bowman", 310: "Hunter", 311: "Ranger", 312: "Bowmaster", 313: "Bowmaster (5th)",
    320: "Crossbowman", 321: "Sniper", 322: "Marksman", 323: "Marksman (5th)",
    400: "Thief", 410: "Assassin", 411: "Hermit", 412: "Night Lord", 413: "Night Lord (5th)",
    420: "Bandit", 421: "Chief Bandit", 422: "Shadower", 423: "Shadower (5th)",
    500: "Pirate", 510: "Brawler", 511: "Marauder", 512: "Buccaneer", 513: "Buccaneer (5th)",
    520: "Gunslinger", 521: "Outlaw", 522: "Corsair", 523: "Corsair (5th)",
    700: "Super Beginner", 710: "Synth Master",
    1000: "Noblesse",
    1100: "Dawn Warrior 1", 1110: "Dawn Warrior 2", 1111: "Dawn Warrior 3", 1112: "Dawn Warrior 4",
    1113: "Dawn Warrior (5th)",
    1200: "Blaze Wizard 1", 1210: "Blaze Wizard 2", 1211: "Blaze Wizard 3", 1212: "Blaze Wizard 4",
    1213: "Blaze Wizard (5th)",
    1300: "Wind Archer 1", 1310: "Wind Archer 2", 1311: "Wind Archer 3", 1312: "Wind Archer 4",
    1313: "Wind Archer (5th)",
    1400: "Night Walker 1", 1410: "Night Walker 2", 1411: "Night Walker 3", 1412: "Night Walker 4",
    1413: "Night Walker (5th)",
    1500: "Thunder Breaker 1", 1510: "Thunder Breaker 2", 1511: "Thunder Breaker 3", 1512: "Thunder Breaker 4",
    1513: "Thunder Breaker (5th)",
    2000: "Legend", 2100: "Aran 1", 2110: "Aran 2", 2111: "Aran 3", 2112: "Aran 4", 2113: "Aran (5th)",
}

ATTACK_KEYS = ("damage", "fixdamage", "mad")


def read_names(path):
    names = {}
    if not os.path.exists(path):
        return names
    for node in ET.parse(path).getroot():
        key = node.get("name")
        if key and key.isdigit():
            for child in node:
                if child.tag == "string" and child.get("name") == "name":
                    names[int(key)] = child.get("value")
    return names


def read_skills(wz_dir):
    """Skill id -> (job, max level, max-level stats, name) for every player-job image of a wz directory."""
    skill_dir = os.path.join(wz_dir, "Skill.wz")
    names = read_names(os.path.join(wz_dir, "String.wz", "Skill.img.xml"))
    out = {}
    for file in sorted(os.listdir(skill_dir)):
        match = re.match(r"^(\d+)\.img\.xml$", file)
        if not match or int(match.group(1)) not in JOBS:
            continue
        job = int(match.group(1))
        root = ET.parse(os.path.join(skill_dir, file)).getroot()
        skills = root.find("imgdir[@name='skill']")
        if skills is None:
            continue
        for node in skills:
            sid = int(node.get("name"))
            levels = node.find("imgdir[@name='level']")
            stats = {}
            max_level = 0
            if levels is not None and len(levels):
                max_level = len(levels)
                stats = {c.get("name"): c.get("value") for c in levels[-1] if c.get("value") is not None}
            common = node.find("imgdir[@name='common']")
            if common is not None:
                common_stats = {c.get("name"): c.get("value") for c in common if c.get("value") is not None}
                max_level = max_level or int(common_stats.get("maxLevel", 0))
                stats = stats or common_stats
            nodes = {c.get("name") for c in node}
            out[sid] = {"job": job, "max": max_level, "stats": stats, "nodes": nodes, "name": names.get(sid) or ""}
    return out


def kind(skill):
    stats, nodes = skill["stats"], skill["nodes"]
    lands = bool(nodes & {"hit", "ball"})
    if "time" in stats and not lands:
        return "buff"
    if any(k in stats for k in ATTACK_KEYS) and (lands or "action" in nodes):
        return "attack"
    if "time" in stats:
        return "buff"
    return "passive"


def status(sid, skill, base):
    if base is None:
        return ""
    if sid not in base:
        return "added"
    if (base[sid]["name"] or "").strip().lower() != (skill["name"] or "").strip().lower():
        return "renamed"
    return "stock"


def markdown(skills, base, title, preface=""):
    lines = [f"# {title}", ""]
    if preface:
        lines += [preface.rstrip(), ""]
    lines += ["## Catalog", "", "Generated by `tools/skill-catalog/skill_catalog.py`. Kind: `attack`, `buff` or `passive` "
              "(the rule is in the tool's docstring). Max: the number of levels Skill.wz defines."]
    if base is not None:
        lines += ["Status: `stock` = same id and name as the base, `renamed` = same id with a different name "
                  "(base name in brackets), `added` = not in the base, `removed` = in the base only."]
    lines.append("")
    for job, job_name in JOBS.items():
        ids = sorted(s for s, v in skills.items() if v["job"] == job)
        gone = sorted(s for s, v in (base or {}).items() if v["job"] == job and s not in skills)
        if not ids and not gone:
            continue
        lines += [f"### {job} {job_name}", ""]
        header = "| id | name | kind | max | status |" if base is not None else "| id | name | kind | max |"
        lines += [header, header_rule(header)]
        for sid in ids:
            v = skills[sid]
            row = [str(sid), cell(v["name"]), kind(v), str(v["max"])]
            if base is not None:
                st = status(sid, v, base)
                if st == "renamed":
                    st = f"renamed ({cell(base[sid]['name'])})"
                row.append(st)
            lines.append("| " + " | ".join(row) + " |")
        for sid in gone:
            v = base[sid]
            lines.append(f"| {sid} | {cell(v['name'])} | {kind(v)} | {v['max']} | removed |")
        lines.append("")
    return "\n".join(lines)


def cell(name):
    return (name or "?").strip().replace("|", "\\|")


def header_rule(header):
    return "|" + "---|" * (header.count("|") - 1)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--wz", required=True, help="wz directory with Skill.wz/ and String.wz/Skill.img.xml")
    parser.add_argument("--base-wz", help="a second wz directory to diff against (e.g. stock Cosmic)")
    parser.add_argument("--markdown", help="write the per-job catalog here")
    parser.add_argument("--tsv", help="write id, job, max level, kind, name here")
    parser.add_argument("--title", default="Skill catalog")
    parser.add_argument("--preface", help="markdown placed between the title and the catalog")
    args = parser.parse_args(argv)

    skills = read_skills(args.wz)
    base = read_skills(args.base_wz) if args.base_wz else None
    preface = ""
    if args.preface:
        with open(args.preface, encoding="utf-8") as f:
            preface = f.read()
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as out:
            out.write(markdown(skills, base, args.title, preface))
    if args.tsv:
        with open(args.tsv, "w", encoding="utf-8") as out:
            out.write("# id\tjob\tmax\tkind\tname\n")
            for sid in sorted(skills):
                v = skills[sid]
                out.write(f"{sid}\t{v['job']}\t{v['max']}\t{kind(v)}\t{v['name']}\n")
    if not args.markdown and not args.tsv:
        print(markdown(skills, base, args.title, preface))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
