#!/usr/bin/env python3
"""How long each body action (pose) lasts in the v83 client, and so how long a player's attack keeps them busy:
the frame delays of Character.wz 00002000.img summed per action, for every action code the exe knows (ticket 32).

    python action_delays.py --img <client>/Data/Character/00002000.img --keystream keystream.bin \
        --exe-tsv src/test/resources/soloMapling/client-attack-exe.tsv \
        --skills src/test/resources/soloMapling/client-attack-data.tsv \
        --out src/main/resources/soloMapling/bot-action-delays.tsv \
        --skill-table src/test/resources/soloMapling/bot-skill-timing.tsv

``--xml <server wz>/Character.wz/00002000.img.xml`` reads the server's XML dump instead of the client's image (to
compare the two: ``--compare-xml`` prints every action whose frames differ).

How the client times an action (docs/real-bots/client-remote-attack-rules.md, "Attack timing"):
  - an action's length is the sum of its frames' delays; a negative delay counts by its size (the frames before the
    hit are written negative in the skill poses: brandish1, savage, fist ...);
  - a frame of a skill pose that names another action ("action", "frame") takes that frame's picture but its own
    delay; with no delay of its own it takes the named frame's;
  - the attack speed s (MapleRoot's get_attack_speed_degree hook, 0..10) stretches it: length * (s + 10) / 16
    (0x9525ed; a beginner's 800 ms sword swing at s = 3 is 650 ms, which is what a real player's client spams at).

Output rows (tab separated):
  --out          D <code> <name> <totalMs> <preMs> <frames>      (preMs: the negative-delay frames before the hit)
  --skill-table  K <skill> <route> <weaponClass> <degree> <poses> <minMs> <maxMs>
                 one row per client attack skill and weapon class it can be used with; poses are the codes the
                 player's client picks from (the skill's own, else its route's plain poses for the weapon).
"""
import argparse
import hashlib
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "clientcheck"))
import wzscan  # noqa: E402

# MapleRoot's GetAttackSpeedDegree hook (client repo mapleroot/ijl15/SkillEdits/ActiveSkill.h, hooked over 0x765066
# in MapleHook.cpp): the weapon class alone sets the degree, the booster is ignored, Speed Infusion is added.
DEGREE_3 = {30, 31, 32, 37, 38, 44, 47, 49}
DEGREE_2 = {33}


def speed_degree(weapon_class, party_booster=0):
    d = 3 if weapon_class in DEGREE_3 else 2 if weapon_class in DEGREE_2 else 4
    return max(0, d + party_booster)


def scaled(ms, degree):
    return ms * (degree + 10) // 16


# The client's attack type of a weapon class (BotClientPoses.defaultAttackType).
ATTACK_TYPE = {30: 1, 31: 1, 32: 1, 33: 1, 37: 6, 38: 6, 40: 5, 41: 5, 42: 5, 43: 2, 44: 2, 45: 3, 46: 4, 47: 7,
               48: 8, 49: 9}


def from_img(path, keystream):
    with open(keystream, "rb") as f:
        masks = wzscan.Masks(f.read())
    with open(path, "rb") as f:
        data = f.read()
    tree = wzscan.parse_img_bytes(data, masks)
    return actions_of(tree[3], lambda n: n[0], lambda n: n[3], lambda n: n[2]), hashlib.sha256(data).hexdigest()


def from_xml(path):
    with open(path, "rb") as f:
        data = f.read()
    root = ET.fromstring(data)

    def value(el):
        v = el.get("value")
        if el.tag in ("int", "short", "long"):
            return int(v)
        return v
    return actions_of(list(root), lambda e: e.get("name"), list, value), hashlib.sha256(data).hexdigest()


def actions_of(children, name, kids, value):
    """{action name: [(delay or None, ref action or None, ref frame or None), ...]} in frame order."""
    out = {}
    for a in children:
        frames = []
        for fr in kids(a):
            try:
                idx = int(name(fr))
            except (TypeError, ValueError):
                continue
            f = {name(c): value(c) for c in kids(fr) if name(c) in ("delay", "action", "frame")}
            frames.append((idx, f.get("delay"), f.get("action"), f.get("frame")))
        if frames:
            out[name(a)] = [(d, ra, rf) for _, d, ra, rf in sorted(frames)]
    return out


def frame_delays(actions, name):
    out = []
    for d, ref, rf in actions.get(name, []):
        if d is None and ref is not None:
            target = actions.get(ref, [])
            i = int(rf or 0)
            d = target[i][0] if i < len(target) else None
        out.append(int(d or 0))
    return out


def read_tsv(path):
    with open(path, encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f if line.strip() and not line.startswith("#")]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--img")
    ap.add_argument("--keystream")
    ap.add_argument("--xml")
    ap.add_argument("--compare-xml")
    ap.add_argument("--exe-tsv", required=True)
    ap.add_argument("--skills", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--skill-table")
    a = ap.parse_args()
    if a.img:
        actions, digest = from_img(a.img, a.keystream)
        source = "client Data/Character/00002000.img"
    else:
        actions, digest = from_xml(a.xml)
        source = "server Character.wz/00002000.img.xml"

    exe = read_tsv(a.exe_tsv)
    codes = [(int(r[1]), r[2]) for r in exe if r[0] == "A"]
    by_name = {n: c for c, n in codes}
    tables = {}
    for r in exe:
        if r[0] == "T":
            tables[(r[1], int(r[2]), r[3])] = [int(x) for x in r[4].split(",") if x]
    routes = {int(r[1]): (r[3], [int(x) for x in r[4].split(",") if x]) for r in exe if r[0] == "K"}

    delays = {}
    with open(a.out, "w", encoding="utf-8") as out:
        out.write("# body action delays (tools/bot-attack-rules/action_delays.py) from the " + source + "\n")
        out.write(f"# sha256 {digest}\n")
        out.write("# D code name totalMs preMs frames   (length at attack speed s: totalMs * (s + 10) / 16)\n")
        for code, nm in codes:
            ds = frame_delays(actions, nm)
            if not ds:
                continue
            total = sum(abs(d) for d in ds)
            pre = sum(-d for d in ds if d < 0)
            delays[code] = total
            out.write(f"D\t{code}\t{nm}\t{total}\t{pre}\t{len(ds)}\n")

    if a.compare_xml:
        other, _ = from_xml(a.compare_xml)
        for code, nm in codes:
            x, y = frame_delays(actions, nm), frame_delays(other, nm)
            if x != y:
                print(f"differs {code} {nm}: {source} {x} server {y}")

    if a.skill_table:
        skills = {int(r[1]): r for r in read_tsv(a.skills) if r[0] == "S"}
        rows = []
        for sid, (route, weapons) in sorted(routes.items()):
            if route not in ("melee", "shoot", "magic", "keydown"):
                continue
            s = skills.get(sid)
            if s is None:
                continue
            own = [n for n in s[4].split(",") if n] + [kv.split("=", 1)[1] for kv in s[5].split(";") if "=" in kv]
            own_codes = sorted({by_name[n] for n in own if n in by_name})
            type3 = s[2] == "3"
            tname = {"melee": "T1", "shoot": "T2", "magic": "T3", "keydown": "T2"}[route]
            for wc in weapons:
                at = ATTACK_TYPE.get(wc)
                if at is None:
                    continue
                poses = own_codes if own_codes and not type3 else \
                    tables.get((tname, at, "type3" if type3 else "norm"), [])
                poses = [p for p in poses if p in delays]
                if not poses:
                    continue
                deg = speed_degree(wc)
                ms = [scaled(delays[p], deg) for p in poses]
                rows.append(f"K\t{sid}\t{route}\t{wc}\t{deg}\t{','.join(map(str, poses))}\t{min(ms)}\t{max(ms)}")
        with open(a.skill_table, "w", encoding="utf-8") as out:
            out.write("# per skill and weapon class: the poses a player's client sends and how long they keep the "
                      "player busy (tools/bot-attack-rules/action_delays.py)\n")
            out.write("# K skill route weaponClass speedDegree poses minMs maxMs\n")
            out.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    main()
