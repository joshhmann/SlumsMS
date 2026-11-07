"""quests.json for the content atlas (docs/atlas/SCHEMA.md): every quest in Quest.wz with its requirements, rewards,
dialogue and chain links, each fact cited to its WZ line. Reads Check, Act, Say and QuestInfo.img.xml.
"""
import os
import re

import wzxml

HANGUL = re.compile(r"[가-힯ᄀ-ᇿ㄰-㆏]")
LATIN = re.compile(r"[A-Za-z]")
# client text codes: colours/bold (#b #k #r #d #g #e #n #c), choice markers (#L0# ... #l), name/item/map/mob lookups
LOOKUP = {"p": "NPC", "t": "item", "z": "item", "i": "item", "v": "item", "o": "mob", "m": "map", "h": "player"}
CODE = re.compile(r"#(?:L\d+#|B\d+#|[blkrdgenc])")
LOOK = re.compile(r"#([ptzivomh])(\d*)#")


def plain(text):
    """Dialogue as plain words: formatting codes dropped, lookups like #p2100# written as "<NPC 2100>"."""
    t = str(text).replace("\\r\\n", " ").replace("\r\n", " ").replace("\\n", " ").replace("\n", " ")
    t = LOOK.sub(lambda m: "<player>" if m.group(1) == "h" else f"<{LOOKUP[m.group(1)]} {int(m.group(2))}>" if m.group(2) else "<you>", t)
    t = CODE.sub("", t)
    return re.sub(r"\s+", " ", t).replace("#", "").strip()


def ref(kind, ident):
    return {"k": kind, "id": ident}


def _list(node, key, fields):
    """node's <imgdir key> children as [{field: value}] in index order, for the named fields."""
    out = []
    d = (node or {}).get("dirs", {}).get(key)
    if not d:
        return out
    for name in sorted(d["dirs"], key=lambda s: (not s.isdigit(), int(s) if s.isdigit() else 0, s)):
        props = d["dirs"][name]["props"]
        out.append({f: props[f] for f in fields if f in props})
    return out


def _props(node, key):
    """The plain properties of node's <imgdir key> child ({} when missing)."""
    return ((node or {}).get("dirs", {}).get(key) or {"props": {}})["props"]


def _texts(node):
    """All dialogue strings under node, in order: its numbered lines, then yes/no/stop answers."""
    out = []
    if not node:
        return out
    for k in sorted((k for k in node["props"] if k.isdigit()), key=int):
        out.append(plain(node["props"][k]))
    for sub in ("yes", "no", "stop"):
        child = node["dirs"].get(sub)
        if child:
            out += _texts(child)
            for d in child["dirs"].values():
                out += _texts(d)
    return [t for t in out if t]


def build(wz_root, script_ids=frozenset(), npc_ids=None):
    """-> {quest id: row}. wz_root is the folder holding Quest.wz/. script_ids: ids like "quest/1021" that exist."""
    qd = os.path.join(wz_root, "Quest.wz")
    check = wzxml.parse(os.path.join(qd, "Check.img.xml"))
    act = wzxml.parse(os.path.join(qd, "Act.img.xml"))
    say = wzxml.parse(os.path.join(qd, "Say.img.xml"))
    info = wzxml.parse(os.path.join(qd, "QuestInfo.img.xml"))
    ids = sorted({int(k) for src in (check, act, say, info) for k in src if k.isdigit()})
    rows = {}
    for qid in ids:
        k = str(qid)
        c, a, s, i = check.get(k), act.get(k), say.get(k), info.get(k)
        c0, c1 = (c or {}).get("dirs", {}).get("0"), (c or {}).get("dirs", {}).get("1")
        a0, a1 = (a or {}).get("dirs", {}).get("0"), (a or {}).get("dirs", {}).get("1")
        p0, p1 = (c0 or {}).get("props", {}), (c1 or {}).get("props", {})
        ip = (i or {}).get("props", {})
        name = str(ip.get("name", ""))
        req_items = _list(c1, "item", ("id", "count")) + _list(c0, "item", ("id", "count"))
        gives, takes = [], []
        for it in _list(a1, "item", ("id", "count", "prop", "job", "gender", "period", "dateExpire")) \
                + _list(a0, "item", ("id", "count", "prop", "job", "gender", "period", "dateExpire")):
            if "id" not in it:
                continue
            row = {"ref": ref("item", it["id"]), "count": abs(it.get("count", 1))}
            for extra in ("prop", "job", "gender", "period", "dateExpire"):
                if extra in it:
                    row[extra] = it[extra]
            (takes if it.get("count", 1) < 0 else gives).append(row)
        a1p = (a1 or {}).get("props", {})
        row = {
            "id": qid, "name": name, "reach": "unobtainable" if HANGUL.search(name) and not LATIN.search(name) else "player",
            "start_npc": ref("npc", p0["npc"]) if "npc" in p0 else None,
            "end_npc": ref("npc", p1["npc"]) if "npc" in p1 else None,
            "area": ip.get("area"), "parent": ip.get("parent"),
            "requirements": {
                "min_level": p0.get("lvmin"), "max_level": p0.get("lvmax"),
                "jobs": sorted(v for v in _props(c0, "job").values() if isinstance(v, int)),
                "quests": [{"ref": ref("quest", x["id"]), "state": x.get("state")}
                           for x in _list(c0, "quest", ("id", "state")) + _list(c1, "quest", ("id", "state"))
                           if "id" in x],
                "items": [{"ref": ref("item", x["id"]), "count": x.get("count", 1)} for x in req_items if "id" in x],
                "mobs": [{"ref": ref("mob", x["id"]), "count": x.get("count", 1)}
                         for x in _list(c1, "mob", ("id", "count")) if "id" in x],
                "field_enter": [ref("map", v) for v in _props(c0, "fieldEnter").values() if isinstance(v, int)],
                "meso": p1.get("endmeso", 0),
                "start_date": p0.get("start"), "end_date": p0.get("end"), "interval": p0.get("interval"),
            },
            "rewards": {
                "exp": a1p.get("exp", 0), "meso": a1p.get("money", 0), "fame": a1p.get("pop", 0),
                "items": gives, "take": takes,
                "skills": _list(a1, "skill", ("id", "skillLevel", "masterLevel")),
                "next_quest": ref("quest", a1p["nextQuest"]) if "nextQuest" in a1p else None,
                "quest_states": [{"ref": ref("quest", x["id"]), "state": x.get("state")}
                                 for x in _list(a1, "quest", ("id", "state")) if "id" in x],
            },
            "start_script": p0.get("startscript"), "end_script": p1.get("endscript"),
            "text": {
                "summary": [plain(ip[x]) for x in ("0", "1", "2") if x in ip],
                "start": _texts((s or {}).get("dirs", {}).get("0")),
                "end": _texts((s or {}).get("dirs", {}).get("1")),
            },
            "src": [],
        }
        if f"quest/{qid}" in script_ids:
            row["script"] = ref("script", f"quest/{qid}")
        for fname, node in (("Check", c), ("Act", a), ("Say", s), ("QuestInfo", i)):
            if node:
                row["src"].append({"kind": "wz", "path": f"Quest.wz/{fname}.img.xml", "line": node["line"]})
        if HANGUL.search(" ".join([name] + row["text"]["summary"] + row["text"]["start"] + row["text"]["end"])):
            row["lang"] = "ko"
        rows[str(qid)] = row
    # chain links: next_quest forward, and quests that list another as a prerequisite are its "next"
    nxt = {}
    for qid, r in rows.items():
        for p in r["requirements"]["quests"]:
            nxt.setdefault(str(p["ref"]["id"]), []).append(int(qid))
        if r["rewards"]["next_quest"]:
            nxt.setdefault(qid, []).append(r["rewards"]["next_quest"]["id"])
    for qid, r in rows.items():
        r["chain"] = {"prev": [p["ref"] for p in r["requirements"]["quests"]],
                      "next": [ref("quest", n) for n in sorted(set(nxt.get(qid, [])))]}
    return rows
