#!/usr/bin/env python3
"""Extract what a v83 client checks in another character's attack from the CLIENT's own data: every skill's
Skill.wz pose/weapon/count/keydown data and every weapon's attack type, attack speed and afterimage.

Runs next to the client's ``Data`` folder (stdlib only; uses ../clientcheck/wzscan.py's binary .img parser and the
GMS key stream file clientcheck already makes). The output is the fixture ``BotAttackClientValidityTest`` reads
(src/test/resources/soloMapling/client-attack-data.tsv); the rules it is checked against are in
docs/real-bots/client-remote-attack-rules.md.

    python client_attack_fixture.py --data <client>\\Data --keystream keystream.bin > client-attack-data.tsv

Rows (tab separated):
    # header lines: the source and a sha256 over the Skill/*.img and Character/Weapon/*.img files read
    S <skill> <skillType> <weapon> <actions,...> <lvl=action;...> <maxLevel> <flags> <lvl:mob/attack/bullet;...>
      <prepare action>
    W <item> <attack> <attackSpeed> <afterImage>
flags: comma list of prepare, keydown, keydownend, ball, finish, special, afterimage (the skill nodes present).
"""
import argparse
import hashlib
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "clientcheck"))
import wzscan  # noqa: E402


def child(node, name):
    for c in node[3]:
        if c[0] == name:
            return c
    return None


def val(node, name, default=None):
    c = child(node, name)
    return c[2] if c is not None and c[1] in ("I", "S", "F") else default


FLAG_NODES = ("prepare", "keydown", "keydownend", "ball", "finish", "special", "afterimage")


def skill_rows(tree):
    skills = child(tree, "skill")
    if skills is None:
        return
    for s in skills[3]:
        try:
            sid = int(s[0])
        except ValueError:
            continue
        action = child(s, "action")
        actions = [a[2] for a in action[3] if a[1] == "S"] if action is not None else []
        levels = child(s, "level")
        common = child(s, "common")
        level_actions, counts, max_level = [], [], 0
        if levels is not None:
            for lv in levels[3]:
                try:
                    n = int(lv[0])
                except ValueError:
                    continue
                max_level = max(max_level, n)
                a = val(lv, "action")
                if a:
                    level_actions.append((n, a))
                counts.append((n, val(lv, "mobCount", ""), val(lv, "attackCount", ""), val(lv, "bulletCount", "")))
        if common is not None:
            max_level = max(max_level, int(val(common, "maxLevel", 0) or 0))
            counts.append((0, val(common, "mobCount", ""), val(common, "attackCount", ""), val(common, "bulletCount", "")))
        flags = [f for f in FLAG_NODES if child(s, f) is not None]
        prepare = child(s, "prepare")
        prepare_action = (val(prepare, "action", "") or "") if prepare is not None else ""
        # keep the counts compact: only levels where a count changes
        kept, last = [], None
        for n, m, a, b in sorted(counts):
            key = (m, a, b)
            if key != last:
                kept.append(f"{n}:{m}/{a}/{b}")
                last = key
        yield "\t".join(["S", str(sid), str(val(s, "skillType", 0) or 0), str(val(s, "weapon", 0) or 0),
                         ",".join(actions), ";".join(f"{n}={a}" for n, a in sorted(level_actions)),
                         str(max_level), ",".join(flags), ";".join(kept), prepare_action])


def weapon_row(item, tree):
    info = child(tree, "info")
    if info is None:
        return None
    return "\t".join(["W", str(item), str(val(info, "attack", 0) or 0), str(val(info, "attackSpeed", 0) or 0),
                      str(val(info, "afterImage", "") or "")])


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--data", required=True)
    ap.add_argument("--keystream", required=True)
    a = ap.parse_args()
    with open(a.keystream, "rb") as f:
        masks = wzscan.Masks(f.read())
    rows, digests = [], {}
    for sub, kind in (("Skill", "S"), (os.path.join("Character", "Weapon"), "W")):
        h = hashlib.sha256()
        folder = os.path.join(a.data, sub)
        for name in sorted(os.listdir(folder)):
            if not name.endswith(".img"):
                continue
            with open(os.path.join(folder, name), "rb") as f:
                data = f.read()
            h.update(name.encode() + b"\0" + hashlib.sha256(data).digest())
            if kind == "S" and not name[:-4].isdigit():
                continue
            try:
                tree = wzscan.parse_img_bytes(data, masks)
            except Exception as e:  # a broken image is reported, not fatal
                sys.stderr.write(f"skip {sub}/{name}: {e}\n")
                continue
            if kind == "S":
                rows.extend(skill_rows(tree))
            else:
                r = weapon_row(int(name[:-4]), tree)
                if r:
                    rows.append(r)
        digests[sub.replace(os.sep, "/")] = h.hexdigest()
    out = sys.stdout
    out.write("# client attack data (tools/bot-attack-rules/client_attack_fixture.py) from the client's Data\n")
    for sub, d in digests.items():
        out.write(f"# sha256 {sub} {d}\n")
    out.write("# S skill skillType weapon actions levelActions maxLevel flags counts(level:mob/attack/bullet) "
              "prepareAction\n")
    out.write("# W item attack attackSpeed afterImage\n")
    rows.sort(key=lambda r: (r[0], int(r.split("\t")[1])))
    out.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    main()
