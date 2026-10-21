#!/usr/bin/env python3
"""Client <-> server consistency checker for SlumsMS. See README.md next to this file.

Exit status: 0 when nothing FAILs, 1 when something does, 2 on a usage/setup error.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import wzscan  # noqa: E402

LEVELS = ("PASS", "INFO", "WARN", "FAIL")
CUSTOM_OPCODE_MIN = 0x1000          # every stock v83 opcode is below this
CUSTOM_OPCODE_MAX = 0xFF00          # 0xFFFE/0xFFFF are stock "unused" placeholders (MAPLETV ...)
MAX_EXAMPLES = 8

# Which server switch turns each custom feature on, and which client build define builds it.
FEATURES = {
    "cashshop": {"opcodes": {0x3730, 0x3731}, "server_flag": "cashshop.window.enabled",
                 "client_define": "MAPLEROOT_CASHSHOP_WINDOW"},
    "dailycheckin": {"opcodes": {0x3732, 0x3733}, "server_flag": "DAILY_CHECKIN_ENABLED",
                     "client_define": "MAPLEROOT_DAILY_CHECKIN"},
    "weather": {"opcodes": {0x373D, 0x373F}, "server_flag": "weather.enabled",
                "client_define": "MAPLEROOT_WEATHER"},
}

# Packet layouts compared field by field (check 2). Paths are relative to the repo roots.
LAYOUTS = [
    {"name": "Cash Shop window LP 0x3731", "dir": "s2c", "opcode": 0x3731,
     "server_file": "src/main/java/server/cashshop/CashShopWindowPackets.java", "server_send": "CASHSHOP_WINDOW_SYNC",
     "client_file": "src/cashshopwnd.cpp", "client_switch": "CashShopWnd_HandleSync"},
    {"name": "Cash Shop window CP 0x3730", "dir": "c2s", "opcode": 0x3730,
     "server_file": "src/main/java/net/server/channel/handlers/CashShopWindowHandler.java",
     "server_consts": ["src/main/java/server/cashshop/CashShopWindowPackets.java"],
     "client_file": "src/cashshopwnd.cpp", "client_opcode_const": "kCashShopActionOpcode"},
    {"name": "Daily Check-In LP 0x3733", "dir": "s2c", "opcode": 0x3733,
     "server_file": "src/main/java/server/checkin/DailyCheckinPackets.java", "server_send": "DAILY_CHECKIN_SYNC",
     "client_file": "src/dailycheckin.cpp", "client_functions": {"kResp_Snapshot": "HandleSnapshot"}},
    {"name": "Daily Check-In CP 0x3732", "dir": "c2s", "opcode": 0x3732, "whole": True,
     "server_file": "src/main/java/net/server/channel/handlers/DailyCheckinHandler.java",
     "client_file": "src/dailycheckin.cpp", "client_opcode_const": "kOpcode_Send"},
    {"name": "Weather LP 0x373D", "dir": "s2c", "opcode": 0x373D, "whole": True, "prefix_ok": True,
     "server_file": "src/main/java/server/weather/WeatherPackets.java", "server_send": "WEATHER_SYNC",
     "client_file": "src/weather.cpp", "client_function": "Weather_HandleWorldState"},
    {"name": "Lamp preview LP 0x373F", "dir": "s2c", "opcode": 0x373F, "whole": True,
     "server_file": "src/main/java/server/lamp/LampPackets.java", "server_send": "LAMP_PREVIEW",
     "client_file": "src/lamps.cpp", "client_function": "Lamp_HandlePreviewPacket"},
]

# Resources the SERVER relies on the client having, beyond what the client source names itself.
SERVER_EXPECTED_RESOURCES = [
    ("dailycheckin", "UI/UIWindowEx.img/DailyCheckin/backgrnd", "Daily Check-In window art"),
    ("always", "UI/UIWindowEx.img/MobHPRatioInfo/mob/0/backgrnd", "mob HP tag (mobhptag.cpp)"),
    ("always", "UI/UIWindowEx.img/MobHPRatioInfo/num", "mob HP tag digits"),
]

DATA_DIRS = ["Base", "Character", "Effect", "Etc", "Item", "Map", "Mob", "Morph", "Npc", "Quest", "Reactor",
             "Skill", "Sound", "String", "TamingMob", "UI"]
CORE_DIRS = ["Map", "Mob", "Npc", "Skill", "Item"]
# Server-only images that are server data by design (never sent to or loaded by the client).
SERVER_ONLY_OK = ("Etc/MapNeighbors.img", "Mob/QuestCountGroup/")
# Known, deliberate per-image differences: {image: ({top-level children allowed to differ}, why)}.
# An image whose ONLY differing top-level children are listed counts as identical (reported as INFO).
KNOWN_DIFFS = {
    "Item/Cash/0512.img": ({"05120995", "05120996", "05120997", "05120998", "05120999"},
                           "SlumsMS weather item art pointers, client-only by design (weather_profiles.inc)"),
    "String/Pet.img": ({"5002103", "5002104"},
                       "server 'DBlue/LBlue Yoshi' fits pets.name varchar(13); the client keeps the full names"),
}
NAV_KEYS = {"foothold", "ladderRope", "portal", "info", "swimArea", "seat"}
REMOTE_EXTRA = ["Custom.wz", "List.wz", "slums.dll", "SlumsMS.exe", "MapleStory.exe", "ijl15.dll"]


# =============================================================================================
# Report
# =============================================================================================
class Report:
    def __init__(self):
        self.items = []

    def add(self, check, level, title, details=None):
        assert level in LEVELS
        self.items.append({"check": check, "level": level, "title": title, "details": list(details or [])})

    def worst(self):
        return max((LEVELS.index(i["level"]) for i in self.items), default=0)

    def counts(self):
        c = Counter(i["level"] for i in self.items)
        return {lv: c.get(lv, 0) for lv in LEVELS}

    def text(self, verbose=False):
        out = []
        checks = []
        for i in self.items:
            if i["check"] not in checks:
                checks.append(i["check"])
        for ch in checks:
            out.append(f"\n== {ch}")
            for i in self.items:
                if i["check"] != ch:
                    continue
                if i["level"] == "PASS" and not verbose:
                    out.append(f"  [PASS] {i['title']}")
                    continue
                out.append(f"  [{i['level']}] {i['title']}")
                for d in i["details"][: (None if verbose else 12)]:
                    out.append(f"         - {d}")
                if not verbose and len(i["details"]) > 12:
                    out.append(f"         ... {len(i['details']) - 12} more (use --verbose or --json)")
        c = self.counts()
        out.append(f"\nSummary: {c['FAIL']} FAIL, {c['WARN']} WARN, {c['INFO']} INFO, {c['PASS']} PASS")
        return "\n".join(out)


def ex(items, n=MAX_EXAMPLES):
    items = list(items)
    s = ", ".join(str(x) for x in items[:n])
    return s + (f", ... (+{len(items) - n})" if len(items) > n else "")


# =============================================================================================
# Source helpers
# =============================================================================================
def git(repo, *args, check=True):
    r = subprocess.run(["git", "-C", repo, *args], capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout


def safe_name(name):
    """A branch name as a single file-name component (origin/test-rc4 -> origin_test-rc4)."""
    return re.sub(r"[^\w.-]+", "_", name).strip("._") or "_"


class Branch:
    """Read-only view of one client branch, straight from git objects (no checkout needed)."""

    def __init__(self, repo, name):
        self.repo, self.name = repo, name
        ref = name
        if git(repo, "rev-parse", "--verify", "-q", name, check=False).strip() == b"":
            ref = f"origin/{name}"
        self.ref = ref
        self.sha = git(repo, "rev-parse", "--short", ref).decode().strip()
        self.files = set(git(repo, "ls-tree", "-r", "--name-only", ref).decode().split("\n")) - {""}
        self._cache = {}

    def has(self, path):
        return path in self.files

    def read(self, path):
        if path not in self._cache:
            self._cache[path] = git(self.repo, "show", f"{self.ref}:{path}").decode("utf-8", "replace") \
                if path in self.files else None
        return self._cache[path]

    def read_bytes(self, path):
        return git(self.repo, "show", f"{self.ref}:{path}") if path in self.files else None

    def custom_wz_cache(self, cache_dir):
        """Local cache file for this branch's src/Custom.wz. Branch names may contain '/'
        (origin/test-rc4), so the name is flattened to one path component."""
        return os.path.join(cache_dir, f"Custom-{safe_name(self.name)}-{self.sha}.wz")

    def custom_images(self, cache_dir, masks):
        """{image path: [kind:node path,...]} of this branch's src/Custom.wz, or None if it has none."""
        if "_custom" not in self.__dict__:
            blob = self.read_bytes("src/Custom.wz")
            self._custom = None
            if blob is not None:
                tmp = self.custom_wz_cache(cache_dir)
                with open(tmp, "wb") as f:
                    f.write(blob)
                self._custom = (hashlib.sha256(blob).hexdigest(), custom_paths(tmp, masks))
        return self._custom

    def sources(self):
        return sorted(p for p in self.files if p.startswith("src/") and p.endswith((".cpp", ".h", ".inc"))
                      and "/wvs/" not in p and "/ztl/" not in p and not p.endswith(".inc"))


_STRIP = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S)


def strip_code(s):
    """Comments and string literals blanked out, offsets preserved, so brace matching is safe."""
    def rep(m):
        t = m.group(0)
        if t.startswith(("//", "/*")):
            return re.sub(r"[^\n]", " ", t)
        return t[0] + " " * (len(t) - 2) + t[-1]
    return _STRIP.sub(rep, s)


def match_brace(s, i):
    depth = 0
    for j in range(i, len(s)):
        c = s[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return j
    return len(s)


def match_paren(s, i):
    depth = 0
    for j in range(i, len(s)):
        if s[j] == "(":
            depth += 1
        elif s[j] == ")":
            depth -= 1
            if depth == 0:
                return j
    return len(s)


def enclosing_block_end(s, pos):
    depth = 0
    for j in range(pos, -1, -1):
        c = s[j]
        if c == "}":
            depth += 1
        elif c == "{":
            if depth == 0:
                return match_brace(s, j)
            depth -= 1
    return len(s)


def func_body(s, name):
    """(start, end) of the body of the function/method `name` defined in stripped source `s`."""
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", s):
        k = m.start() - 1
        while k >= 0 and s[k] in " \t\r\n":
            k -= 1
        if k >= 0 and s[k] in "(=.,!&|?+-/%<[" and not s[k - 1:k + 1] == "::":
            continue
        close = match_paren(s, m.end() - 1)
        rest = s[close + 1:close + 200]
        mm = re.match(r"\s*(?:const\s*)?(?:noexcept\s*)?(?:throws\s+[\w.,\s]+)?\{", rest)
        if mm:
            b = close + 1 + mm.end() - 1
            return b, match_brace(s, b)
    return None


def int_consts(s):
    out = {}
    for m in re.finditer(r"\b([A-Za-z_]\w*)\s*=\s*(0x[0-9A-Fa-f]+|-?\d+)\b", s):
        out.setdefault(m.group(1), int(m.group(2), 0))
    return out


# --- packet field sequences ---------------------------------------------------------------
JAVA_TOK = re.compile(r"\.(write|read)(Byte|Bool|Boolean|Short|Int|Long|String|AsciiString|Pos)\s*\(")
JAVA_W = {"Byte": "1", "Bool": "1", "Boolean": "1", "Short": "2", "Int": "4", "Long": "8", "String": "S",
          "AsciiString": "S", "Pos": "2 2"}
CPP_TOK = re.compile(r"\b(?:Encode|Decode|dec|enc)(1|2|4|8|Str)\s*\(|\bDecode\s*<\s*([\w\s]+?)\s*>\s*\(")
CPP_T = {"char": "1", "unsigned char": "1", "bool": "1", "BYTE": "1", "short": "2", "unsigned short": "2",
         "WORD": "2", "int": "4", "unsigned int": "4", "long": "4", "unsigned long": "4", "DWORD": "4",
         "long long": "8", "__int64": "8", "unsigned long long": "8", "float": "4", "double": "8"}


def _loops(s, lo, hi):
    out = []
    for m in re.finditer(r"\b(?:for|while)\s*\(", s[lo:hi]):
        p = lo + m.end() - 1
        q = match_paren(s, p)
        k = q + 1
        while k < hi and s[k] in " \t\r\n":
            k += 1
        if k < hi and s[k] == "{":
            out.append((k, match_brace(s, k)))
        else:
            e = s.find(";", k)
            out.append((k, e if e != -1 else hi))
    return out


def field_seq(s, lo, hi, lang, inline=None, depth=0):
    """Nested list of field codes ('1','2','4','8','S', or sub-lists for loop bodies)."""
    toks = []
    if lang == "java":
        for m in JAVA_TOK.finditer(s, lo, hi):
            for code in JAVA_W[m.group(2)].split():
                toks.append((m.start(), code, m.end()))
        if inline and depth < 4:
            for m in re.finditer(r"\b([a-z]\w*)\s*\(([^;(){}]*)\)", s[lo:hi]):
                name, args = m.group(1), m.group(2)
                if name in inline and re.search(r"\bp\b", args):
                    b = inline[name]
                    sub = field_seq(s, b[0], b[1], lang, inline, depth + 1)
                    toks.append((lo + m.start(), sub, lo + m.end()))
    else:
        for m in CPP_TOK.finditer(s, lo, hi):
            if m.group(1):
                code = "S" if m.group(1) == "Str" else m.group(1)
            else:
                code = CPP_T.get(" ".join(m.group(2).split()), "?")
            toks.append((m.start(), code, m.end()))
    toks.sort(key=lambda t: t[0])
    loops = [l for l in _loops(s, lo, hi)]

    def build(a, b):
        res = []
        inner = [l for l in loops if a <= l[0] and l[1] <= b and (l[0], l[1]) != (a, b)]
        top = [l for l in inner if not any(o != l and o[0] <= l[0] and l[1] <= o[1] for o in inner)]
        events = [(t[0], "t", t) for t in toks if a <= t[0] < b and not any(l[0] <= t[0] < l[1] for l in top)]
        events += [(l[0], "l", l) for l in top]
        for _, kind, obj in sorted(events, key=lambda e: e[0]):
            if kind == "t":
                if isinstance(obj[1], list):
                    res.extend(obj[1])
                else:
                    res.append(obj[1])
            else:
                sub = build(obj[0], obj[1])
                if sub:
                    res.append(sub)
        return res
    return build(lo, hi)


def render(seq):
    return " ".join(f"[{render(x)}]" if isinstance(x, list) else x for x in seq) or "(empty)"


def first_tok_arg(s, lo, hi, lang):
    m = (JAVA_TOK if lang == "java" else CPP_TOK).search(s, lo, hi)
    if not m:
        return None
    q = match_paren(s, m.end() - 1)
    arg = s[m.end():q].strip()
    arg = re.sub(r"^static_cast<[^>]*>\(|^\([^)]*\)", "", arg).strip("() ")
    return arg.split(".")[-1]


# =============================================================================================
# Check 1: opcodes
# =============================================================================================
def is_custom(v):
    return CUSTOM_OPCODE_MIN <= v < CUSTOM_OPCODE_MAX


def parse_server_opcodes(server):
    def enum(path):
        txt = open(os.path.join(server, path), encoding="utf-8").read()
        return [(m.group(1), int(m.group(2), 0))
                for m in re.finditer(r"^\s*([A-Z0-9_]+)\((0x[0-9A-Fa-f]+|\d+)\)", txt, re.M)]
    recv = enum("src/main/java/net/opcodes/RecvOpcode.java")
    send = enum("src/main/java/net/opcodes/SendOpcode.java")
    pp = open(os.path.join(server, "src/main/java/net/PacketProcessor.java"), encoding="utf-8").read()
    handled = set(re.findall(r"registerHandler\(\s*RecvOpcode\.(\w+)", pp))
    used = set()
    for dp, _, fns in os.walk(os.path.join(server, "src/main/java")):
        if dp.endswith(os.path.join("net", "opcodes")):
            continue
        for fn in fns:
            if fn.endswith(".java"):
                used.update(re.findall(r"SendOpcode\.([A-Z0-9_]+)", open(os.path.join(dp, fn), encoding="utf-8",
                                                                          errors="replace").read()))
    return recv, send, handled, used


def parse_client_opcodes(br: Branch):
    consts = {}       # name -> (value, file, direction-hint)
    docs = []         # (name, value, file)
    sent = {}         # value -> [file]
    routed = {}       # value -> feature define
    srcs = {p: br.read(p) for p in br.sources()}
    for path, txt in srcs.items():
        for m in re.finditer(r"\b(\w+)\s*=\s*(0x[0-9A-Fa-f]{4})\s*;([^\n]*)", txt):
            v = int(m.group(2), 16)
            if not is_custom(v) or not re.search(r"opcode|kOp|_Send|_Recv", m.group(1), re.I):
                continue
            hint = m.group(1) + " " + m.group(3)
            d = "c2s" if re.search(r"client\s*->\s*server|Send|Action|Request|CP_", hint) else \
                "s2c" if re.search(r"server\s*->\s*client|Recv|Sync|Reply|LP_", hint) else "?"
            consts[m.group(1)] = (v, path, d)
        for m in re.finditer(r"(?://|\*)[^\n]*", txt):
            for d in re.finditer(r"\b([A-Z][A-Z_]{2,})\s+(0x[0-9A-Fa-f]{4})(?:/([0-9A-Fa-f]))?", m.group(0)):
                base = int(d.group(2), 16)
                if not is_custom(base):
                    continue
                docs.append((d.group(1), base, path))
                if d.group(3):
                    docs.append((d.group(1), (base & ~0xF) | int(d.group(3), 16), path))
    # local aliases of a named opcode (constexpr uint16_t kOp_X = kXOpcode;), used only to resolve sends
    alias = {}
    for path, txt in srcs.items():
        for m in re.finditer(r"\b(\w+)\s*=\s*([A-Za-z_]\w*)\s*;", strip_code(txt)):
            if m.group(2) in consts and m.group(1) not in consts:
                alias[m.group(1)] = consts[m.group(2)]
    for path, txt in srcs.items():
        code = strip_code(txt)
        for m in re.finditer(r"\bCOutPacket\s+\w+\s*\(\s*(\w+)\s*\)", code):
            a = m.group(1)
            v = int(a, 0) if re.match(r"0x|\d", a) else consts.get(a, alias.get(a, (None,)))[0]
            if v is not None and is_custom(v):
                sent.setdefault(v, []).append(path)
    disp = br.read("src/packetdispatcher.cpp") or ""
    code = strip_code(disp)
    ifdefs = [(m.start(), m.group(1)) for m in re.finditer(r"#ifdef\s+(\w+)", disp)]
    for m in re.finditer(r"\bopcode\s*==\s*(\w+)", code):
        a = m.group(1)
        v = int(a, 0) if re.match(r"0x|\d", a) else consts.get(a, (None,))[0]
        if v is None:
            continue
        guard = [g for p, g in ifdefs if p < m.start()]
        routed[v] = guard[-1] if guard else "(always)"
    return consts, docs, sent, routed


def check_opcodes(rep, server, branches, server_flags):
    C = "1. Custom opcodes"
    recv, send, handled, used = parse_server_opcodes(server)
    crecv = {n: v for n, v in recv if is_custom(v)}
    csend = {n: v for n, v in send if is_custom(v)}
    rep.add(C, "INFO", f"server custom opcodes: Recv {ex(f'{n}={v:#06x}' for n, v in crecv.items())}; "
                       f"Send {ex(f'{n}={v:#06x}' for n, v in csend.items())}")
    for label, lst in (("RecvOpcode", recv), ("SendOpcode", send)):
        byv = defaultdict(list)
        for n, v in lst:
            byv[v].append(n)
        dup = {v: ns for v, ns in byv.items() if len(ns) > 1}
        cdup = {v: ns for v, ns in dup.items() if is_custom(v)}
        if cdup:
            rep.add(C, "FAIL", f"server {label}: custom opcodes share a value",
                    [f"{v:#06x}: {', '.join(ns)}" for v, ns in cdup.items()])
        else:
            rep.add(C, "PASS", f"server {label}: no custom opcode collisions"
                    + (f" ({len(dup)} stock duplicate value(s), pre-existing)" if dup else ""))
    rs = {v: n for n, v in crecv.items()}
    ss = {v: n for n, v in csend.items()}
    both = set(rs) & set(ss)
    if both:
        rep.add(C, "WARN", "same custom value used in both directions",
                [f"{v:#06x}: Recv {rs[v]} / Send {ss[v]}" for v in sorted(both)])
    for n, v in crecv.items():
        if n not in handled:
            rep.add(C, "WARN", f"server RecvOpcode.{n} ({v:#06x}) has no registered handler")
    for n, v in csend.items():
        if n not in used:
            rep.add(C, "WARN", f"server SendOpcode.{n} ({v:#06x}) is never sent")
    odd = [f"Recv {n}={v:#06x} (odd)" for n, v in crecv.items() if v & 1] + \
          [f"Send {n}={v:#06x} (even)" for n, v in csend.items() if not v & 1]
    if odd:
        rep.add(C, "WARN", "custom opcodes breaking the even=request / odd=reply convention", odd)

    all_docs = {}
    for br in branches:
        consts, docs, sent, routed = parse_client_opcodes(br)
        tag = f"[{br.name}@{br.sha}]"
        # collisions inside the client: one value claimed by two different feature files
        byv = defaultdict(set)
        for n, (v, path, d) in consts.items():
            byv[v].add(os.path.basename(path).split(".")[0])
        coll = {v: f for v, f in byv.items() if len(f) > 1}
        if coll:
            rep.add(C, "FAIL", f"{tag} client opcode constants collide across features",
                    [f"{v:#06x}: {', '.join(sorted(f))}" for v, f in coll.items()])
        live = {v for v, _, _ in consts.values()} | set(sent) | set(routed)
        # A comment that names the feature which now owns the value (SET_ITEM 0x3726 -> SET_ITEM_ACTION,
        # DAILY_CHECKIN 0x3732 -> DAILY_CHECKIN) documents that feature; only another name is a reuse.
        def owner_matches(n, v):
            owner = rs.get(v) or ss.get(v) or ""
            return bool(owner) and (owner == n or owner.startswith(n + "_") or n.startswith(owner + "_"))
        dcoll = [f"{n} {v:#06x} ({p}) vs live client opcode {rs.get(v) or ss.get(v) or '?'}"
                 for n, v, p in docs if v in live and not owner_matches(n, v)]
        for n, v, p in docs:
            all_docs[(n, v)] = p
        if dcoll:
            rep.add(C, "FAIL", f"{tag} reserved/documented opcode reused by a live feature", dcoll)
        # client -> server
        for v, files in sorted(sent.items()):
            if v in rs and rs[v] in handled:
                rep.add(C, "PASS", f"{tag} client sends {v:#06x} ({', '.join(sorted(set(files)))}) -> server {rs[v]}")
            elif v in rs:
                rep.add(C, "FAIL", f"{tag} client sends {v:#06x} but server RecvOpcode.{rs[v]} has no handler")
            else:
                rep.add(C, "FAIL", f"{tag} client sends {v:#06x} ({', '.join(sorted(set(files)))}); "
                                   f"server has no RecvOpcode with that value")
        # server -> client
        for v, guard in sorted(routed.items()):
            if v in ss:
                rep.add(C, "PASS", f"{tag} client routes {v:#06x} (under {guard}) <- server {ss[v]}")
            else:
                rep.add(C, "WARN", f"{tag} client routes {v:#06x} (under {guard}); server never defines it")
        for n, v in crecv.items():
            if v not in sent:
                rep.add(C, "WARN", f"{tag} server RecvOpcode.{n} ({v:#06x}) has no client sender in this branch")
        for n, v in csend.items():
            if v in routed:
                continue
            feat = next((f for f, d in FEATURES.items() if v in d["opcodes"]), None)
            flag = FEATURES[feat]["server_flag"] if feat else None
            on = server_flags.get(flag) if flag else None
            lvl = "FAIL" if on else "WARN"
            why = (f"server flag {flag}={on}" if flag else "no server flag gates it")
            rep.add(C, lvl, f"{tag} server SendOpcode.{n} ({v:#06x}) has no client handler in this branch ({why})")
    if all_docs:
        notimpl = [f"{n} {v:#06x}" for (n, v) in all_docs if v not in rs and v not in ss]
        if notimpl:
            files = sorted(set(all_docs.values()))
            rep.add(C, "WARN", "opcodes documented in client comments but implemented by neither side "
                               f"({', '.join(files)}): keep this range reserved or fix the comment", notimpl)


# =============================================================================================
# Check 2: packet layouts
# =============================================================================================
def java_inline_map(s):
    out = {}
    for m in re.finditer(r"\b(?:private|public|protected|static|final|\s)+[\w<>\[\],.? ]+\s+([a-z]\w*)\s*\(([^)]*)\)"
                         r"\s*(?:throws [\w.,\s]+)?\{", s):
        if re.search(r"\b(?:InPacket|OutPacket)\s+p\b", m.group(2)):
            b = m.end() - 1
            out[m.group(1)] = (b, match_brace(s, b))
    return out


def server_layouts(server, spec):
    path = os.path.join(server, spec["server_file"])
    if not os.path.exists(path):
        return None, f"missing {spec['server_file']}"
    raw = open(path, encoding="utf-8").read()
    s = strip_code(raw)
    consts = int_consts(s)
    for extra in spec.get("server_consts", []):
        consts.update(int_consts(strip_code(open(os.path.join(server, extra), encoding="utf-8").read())))
    inline = java_inline_map(s)
    out = {}
    if spec["dir"] == "s2c":
        for m in re.finditer(r"OutPacket\.create\(\s*SendOpcode\." + spec["server_send"] + r"\s*\)\s*;", s):
            lo, hi = m.end(), enclosing_block_end(s, m.start())
            seq = field_seq(s, lo, hi, "java", inline)
            if spec.get("whole"):
                out.setdefault(None, []).append(seq)
                continue
            arg = first_tok_arg(s, lo, hi, "java")
            key = consts.get(arg, arg)
            out.setdefault(key, []).append(seq[1:])
    else:
        b = func_body(s, "handlePacket")
        if not b:
            return None, "no handlePacket"
        sw = re.search(r"\bswitch\s*\(", s[b[0]:b[1]])
        if spec.get("whole") or not sw:
            out[None] = [field_seq(s, b[0], b[1], "java", inline)]
        else:
            for m in re.finditer(r"\bcase\s+([\w.]+)\s*->\s*([^;]*);", s[b[0]:b[1]]):
                key = consts.get(m.group(1).split(".")[-1], m.group(1))
                lo = b[0] + m.start(2)
                out.setdefault(key, []).append(field_seq(s, lo, b[0] + m.end(2), "java", inline))
    return out, None


def client_layouts(br: Branch, spec):
    txt = br.read(spec["client_file"])
    if txt is None:
        return None, f"{spec['client_file']} not in this branch"
    s = strip_code(txt)
    consts = int_consts(s)
    out = {}
    if spec["dir"] == "s2c":
        if "client_switch" in spec:
            b = func_body(s, spec["client_switch"])
            if not b:
                return None, f"no {spec['client_switch']}"
            for m in re.finditer(r"\bcase\s+(\w+)\s*:", s[b[0]:b[1]]):
                k = b[0] + m.end()
                while s[k] in " \t\r\n":
                    k += 1
                end = match_brace(s, k) if s[k] == "{" else s.find("break", k)
                out[consts.get(m.group(1), m.group(1))] = [field_seq(s, k, end, "cpp")]
        elif "client_functions" in spec:
            for cname, fn in spec["client_functions"].items():
                b = func_body(s, fn)
                if b:
                    out[consts.get(cname, cname)] = [field_seq(s, b[0], b[1], "cpp")]
        else:
            b = func_body(s, spec["client_function"])
            if not b:
                return None, f"no {spec['client_function']}"
            out[None] = [field_seq(s, b[0], b[1], "cpp")]
    else:
        for m in re.finditer(r"\bCOutPacket\s+\w+\s*\(\s*" + spec["client_opcode_const"] + r"\s*\)\s*;", s):
            lo, hi = m.end(), enclosing_block_end(s, m.start())
            seq = field_seq(s, lo, hi, "cpp")
            if spec.get("whole"):
                out.setdefault(None, []).append(seq)
            else:
                arg = first_tok_arg(s, lo, hi, "cpp")
                out.setdefault(consts.get(arg, arg), []).append(seq[1:])
    return out, None


def check_layouts(rep, server, branches):
    C = "2. Packet layouts"
    for spec in LAYOUTS:
        sl, err = server_layouts(server, spec)
        if err:
            rep.add(C, "WARN", f"{spec['name']}: server side not parsed ({err})")
            continue
        for br in branches:
            tag = f"[{br.name}@{br.sha}] {spec['name']}"
            cl, err = client_layouts(br, spec)
            if err:
                rep.add(C, "INFO", f"{tag}: skipped ({err})")
                continue
            bad, good = [], []
            keys = sorted(set(sl) | set(cl), key=lambda k: (k is None, str(k)))
            for k in keys:
                lab = "packet" if k is None else f"subtype {k}"
                if spec["dir"] == "s2c" and k not in cl:
                    bad.append(f"{lab}: server writes it, client has no decoder")
                    continue
                if spec["dir"] == "c2s" and k not in sl:
                    bad.append(f"{lab}: client sends it, server has no case")
                    continue
                if k not in sl or k not in cl:
                    # server reads a subtype the client never sends: harmless
                    good.append(f"{lab}: {'server-only' if k in sl else 'client-only'} (not sent)")
                    continue
                svs, cvs = {render(x) for x in sl[k]}, {render(x) for x in cl[k]}
                for sv in svs:
                    for cv in cvs:
                        w = sv if spec["dir"] == "s2c" else cv      # writer
                        r = cv if spec["dir"] == "s2c" else sv      # reader
                        if w == r:
                            good.append(f"{lab}: {w}")
                        elif spec.get("prefix_ok") and w.startswith(r + " "):
                            good.append(f"{lab}: reader takes prefix {r} of {w} (trailing fields ignored)")
                        else:
                            bad.append(f"{lab}: writer {w} != reader {r}")
            if bad:
                rep.add(C, "FAIL", f"{tag}: layout mismatch", bad + [f"ok {g}" for g in good])
            else:
                rep.add(C, "PASS", f"{tag}: {len(good)} layout(s) match", good)


def check_layout_constants(rep, server, branches):
    """Limits the two sides agree on by convention only."""
    C = "2. Packet layouts"
    p = os.path.join(server, "src/main/java/server/cashshop/CashShopWindowPackets.java")
    sv = int_consts(strip_code(open(p, encoding="utf-8").read())) if os.path.exists(p) else {}
    s2 = os.path.join(server, "src/main/java/server/checkin/DailyCheckinStreak.java")
    sd = int_consts(strip_code(open(s2, encoding="utf-8").read())) if os.path.exists(s2) else {}
    buy_codes = {k: v for k, v in sv.items() if k.startswith("BUY_")}
    for br in branches:
        tag = f"[{br.name}@{br.sha}]"
        cs = br.read("src/cashshopwnd.cpp")
        if cs and sv:
            cc = int_consts(strip_code(cs))
            if "MAX_NAME" in sv and "kMaxNameLen" in cc:
                lvl = "PASS" if sv["MAX_NAME"] < cc["kMaxNameLen"] else "FAIL"
                rep.add(C, lvl, f"{tag} cash shop name cap: server MAX_NAME {sv['MAX_NAME']} < client kMaxNameLen "
                                f"{cc['kMaxNameLen']} (bytes; multi-byte names need headroom)")
            if "CHUNK" in sv and "kMaxEntriesPerChunk" in cc:
                lvl = "PASS" if sv["CHUNK"] <= cc["kMaxEntriesPerChunk"] else "FAIL"
                rep.add(C, lvl, f"{tag} cash shop chunk: server CHUNK {sv['CHUNK']} <= client kMaxEntriesPerChunk "
                                f"{cc['kMaxEntriesPerChunk']}")
            m = re.search(r"kBuyMsg\[\]\s*=\s*\{(.*?)\};", cs, re.S)
            if m and buy_codes:
                n = len(re.findall(r'"(?:\\.|[^"\\])*"', m.group(1)))
                extra = sorted({f"{k}={v}" for k, v in buy_codes.items() if v >= n})
                if extra:
                    rep.add(C, "WARN", f"{tag} cash shop buy codes without client text (shown as 'Purchase "
                                       f"failed.'; server also sends a popup)", extra)
                else:
                    rep.add(C, "PASS", f"{tag} every server BUY_* code has client text")
        dc = br.read("src/dailycheckin.cpp")
        if dc and "CYCLE_DAYS" in sd:
            cc = int_consts(strip_code(dc))
            lvl = "PASS" if cc.get("kDays") == sd["CYCLE_DAYS"] else "FAIL"
            rep.add(C, lvl, f"{tag} daily check-in days: server CYCLE_DAYS {sd['CYCLE_DAYS']} vs client kDays "
                            f"{cc.get('kDays')} (client clamps the count; a longer server cycle desyncs the strings)")


# =============================================================================================
# Remote client data + server WZ scans
# =============================================================================================
class Remote:
    def __init__(self, a):
        self.a = a
        self.ssh = ["ssh", "-i", os.path.expanduser(a.ssh_key), "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes",
                    "-o", "ConnectTimeout=15", a.remote]
        self.scp = ["scp", "-q", "-i", os.path.expanduser(a.ssh_key), "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes"]

    def run(self, cmd, timeout=1800):
        r = subprocess.run(self.ssh + [cmd], capture_output=True, timeout=timeout)
        if r.returncode != 0:
            raise RuntimeError(f"remote: {cmd}\n{r.stdout.decode(errors='replace')}{r.stderr.decode(errors='replace')}")
        return r.stdout.decode(errors="replace")

    def put(self, local, remote_dir):
        subprocess.run(self.scp + [local, f"{self.a.remote}:{remote_dir.replace(chr(92), '/')}/"], check=True)

    def get(self, remote_path, local):
        subprocess.run(self.scp + [f"{self.a.remote}:{remote_path.replace(chr(92), '/')}", local], check=True)


def win_join(*parts):
    return "\\".join(p.strip("\\") for p in parts)


def remote_scan(a, dumps, log):
    os.makedirs(a.cache_dir, exist_ok=True)
    local = os.path.join(a.cache_dir, "client-scan.json.gz")
    fresh = os.path.exists(local) and time.time() - os.path.getmtime(local) < a.max_age * 60
    if a.offline or (fresh and not a.refresh):
        if not os.path.exists(local):
            raise RuntimeError("no cached client scan; run once without --offline")
        with gzip.open(local, "rt", encoding="utf-8") as f:
            d = json.load(f)
        if set(dumps) - {k for k, v in d["files"].items() if "paths" in v or "err" in v} and not a.offline:
            pass  # a new dump request: fall through to a real scan below
        else:
            log(f"client scan: cached {local} ({(time.time() - os.path.getmtime(local)) / 60:.0f} min old)")
            return d
    r = Remote(a)
    ks = os.path.join(a.cache_dir, "keystream.bin")
    if not os.path.exists(ks):
        with open(ks, "wb") as f:
            f.write(wzscan.make_keystream())
    work = a.remote_work
    r.run(f'if not exist "{work}" mkdir "{work}"')
    r.put(os.path.join(HERE, "wzscan.py"), work)
    r.put(ks, work)
    data = win_join(a.remote_client, "Data")
    extra = " ".join(f'"{win_join(a.remote_client, x)}"' for x in REMOTE_EXTRA)
    cmd = (f'cd /d "{work}" && "{a.remote_python}" wzscan.py scan --data "{data}" --keystream keystream.bin '
           f'--cache scan.json.gz --dirs {" ".join(DATA_DIRS)} --hash-file {extra}'
           + (f' --dump {" ".join(sorted(dumps))}' if dumps else ""))
    t = time.time()
    out = r.run(cmd)
    log(f"client scan: remote {out.strip().splitlines()[-1]} ({time.time() - t:.0f}s)")
    r.get(win_join(work, "scan.json.gz"), local)
    with gzip.open(local, "rt", encoding="utf-8") as f:
        return json.load(f)


def fetch_remote_file(a, name, sha, log):
    """A small client file (Custom.wz, slums.dll ...) copied once per content hash."""
    d = os.path.join(a.cache_dir, "files")
    os.makedirs(d, exist_ok=True)
    local = os.path.join(d, f"{sha[:16]}-{name}")
    if not os.path.exists(local):
        if a.offline:
            return None
        Remote(a).get(win_join(a.remote_client, name), local)
        log(f"fetched {name} ({sha[:12]})")
    return local


def _scan_xml(args):
    full, rel = args
    try:
        return rel, wzscan.summarize(wzscan.parse_xml_file(full), rel)
    except Exception as e:  # noqa
        return rel, {"err": f"{type(e).__name__}: {e}"[:300]}


def server_scan(a, log):
    path = os.path.join(a.cache_dir, "server-scan.json.gz")
    old = {}
    if os.path.exists(path):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            d = json.load(f)
        if d.get("version") == wzscan.SCAN_VERSION and d.get("wz") == a.server_wz:
            old = d["files"]
    files, todo = {}, []
    for top in sorted(os.listdir(a.server_wz)):
        if not top.endswith(".wz"):
            continue
        for dp, _, fns in os.walk(os.path.join(a.server_wz, top)):
            for fn in fns:
                if not fn.endswith(".img.xml"):
                    continue
                full = os.path.join(dp, fn)
                rel = top[:-3] + "/" + os.path.relpath(full, os.path.join(a.server_wz, top))[:-4].replace(os.sep, "/")
                st = os.stat(full)
                k = [st.st_size, int(st.st_mtime)]
                if rel in old and old[rel].get("k") == k:
                    files[rel] = old[rel]
                else:
                    todo.append((full, rel, k))
    if todo:
        t = time.time()
        keys = {rel: k for _, rel, k in todo}
        with ProcessPoolExecutor(max_workers=a.jobs) as pool:
            for rel, rec in pool.map(_scan_xml, [(f, r) for f, r, _ in todo], chunksize=32):
                rec["k"] = keys[rel]
                files[rel] = rec
        log(f"server scan: parsed {len(todo)} XML images ({time.time() - t:.0f}s)")
        with gzip.open(path + ".tmp", "wt", encoding="utf-8") as f:
            json.dump({"version": wzscan.SCAN_VERSION, "wz": a.server_wz, "files": files}, f, separators=(",", ":"))
        os.replace(path + ".tmp", path)
    else:
        log(f"server scan: cached ({len(files)} images)")
    return files


# =============================================================================================
# Check 3: Cash Shop
# =============================================================================================
def parse_kcats(src):
    m = re.search(r"kCats\[\]\s*=\s*\{(.*?)\n\};", src, re.S)
    if not m:
        return None
    raw = m.group(1)
    out = {}
    for mm in re.finditer(r"\{\s*(\d+)\s*,\s*(\d+)\s*,\s*\"([^\"]*)\"\s*\}", raw):
        out[(int(mm.group(1)), int(mm.group(2)))] = mm.group(3)
    return out


def item_location(iid, char_index):
    """(img rel path, child name or '' for whole-image items, String img) for an item id."""
    t = iid // 1000000
    if t == 1:
        return char_index.get(f"{iid:08d}.img"), "", "String/Eqp.img"
    if 5000000 <= iid < 5010000:
        return f"Item/Pet/{iid}.img", "", "String/Pet.img"
    sub = {2: ("Consume", "Consume"), 3: ("Install", "Ins"), 4: ("Etc", "Etc"), 5: ("Cash", "Cash")}.get(t)
    if not sub:
        return None, "", None
    return f"Item/{sub[0]}/{iid // 10000:04d}.img", f"{iid:08d}", f"String/{sub[1]}.img"


def char_index(files):
    idx = {}
    for rel in files:
        if rel.startswith("Character/") and rel.count("/") == 2:
            idx.setdefault(rel.rsplit("/", 1)[1], rel)
    return idx


def item_status(iid, files, cidx, names):
    """What one side knows about an item: (exists, has_icon, name, hash)."""
    img, child, simg = item_location(iid, cidx)
    rec = files.get(img) if img else None
    nm = names.get(simg, {}).get(str(iid)) if simg else None
    if not rec or "err" in rec:
        return False, False, nm, None
    if child:
        if child not in rec.get("top", {}):
            return False, False, nm, None
        return True, child in rec.get("icons", []), nm, rec["top"][child]
    return True, "" in rec.get("icons", []), nm, rec["h"]


def string_names(files):
    return {rel: rec.get("names", {}) for rel, rec in files.items() if rel.startswith("String/")}


def read_catalog(server):
    rows = []
    p = os.path.join(server, "cashshop/catalog.tsv")
    for ln, line in enumerate(open(p, encoding="utf-8"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        f = line.rstrip("\n").split("\t")
        try:
            rows.append({"id": int(f[0]), "tab": int(f[3]), "cat": int(f[4]), "name": f[7] if len(f) > 7 else "",
                         "line": ln})
        except (ValueError, IndexError):
            rows.append({"bad": line.strip()[:80], "line": ln})
    return rows


def custom_item_status(iid, cfiles, cidx, cimgs):
    """Whether a branch's Custom.wz supplies an item the stock client Data lacks: (exists, has_icon).
    Custom.wz serves an image the client does not have whole; for a stock image it only merges leaves,
    so a new item child under a stock image is NOT visible (resman.cpp) and does not count."""
    img, child, _ = item_location(iid, cidx)
    if not img or img not in cimgs or (cfiles.get(img) is not None and "err" not in cfiles[img]):
        return False, False
    nodes = {p.split(":", 1)[1] for p in cimgs[img]}
    if child:
        if child not in nodes:
            return False, False
        return True, f"{child}/info/icon" in nodes
    return True, "info/icon" in nodes


def source_item_names(br):
    """Item names the client source supplies itself because Custom.wz cannot add them to a stock
    String image (e.g. coloringprism_text.h: kColorPrismTextItemId / kColorPrismName)."""
    out = {}
    for p in br.files:
        if not (p.startswith("src/") and p.endswith("_text.h")):
            continue
        s = br.read(p) or ""
        ids = re.findall(r"\bk\w*ItemId\s*=\s*(\d+)", s)
        names = re.findall(r"\bk\w*Name\s*=\s*\"([^\"]*)\"", s)
        if len(ids) == 1 and names:
            out[int(ids[0])] = (names[0], p)
    return out


def check_cashshop(rep, server, branches, cfiles, sfiles, customs=None):
    C = "3. Cash Shop"
    rows = read_catalog(server)
    bad = [f"line {r['line']}: {r['bad']}" for r in rows if "bad" in r]
    if bad:
        rep.add(C, "FAIL", "catalog.tsv rows that do not parse", bad)
    rows = [r for r in rows if "bad" not in r]
    pairs = Counter((r["tab"], r["cat"]) for r in rows)
    for br in branches:
        src = br.read("src/cashshopwnd.cpp")
        kc = parse_kcats(src) if src else None
        tag = f"[{br.name}@{br.sha}]"
        if kc is None:
            rep.add(C, "WARN", f"{tag} kCats not found in src/cashshopwnd.cpp")
            continue
        missing = {p: n for p, n in pairs.items() if p not in kc}
        if missing:
            rep.add(C, "FAIL", f"{tag} catalog tab/category pairs the client window cannot show "
                               f"({sum(missing.values())} items invisible)",
                    [f"tab {t} cat {c}: {n} item(s), e.g. "
                     + ex([r['id'] for r in rows if (r['tab'], r['cat']) == (t, c)], 4)
                     for (t, c), n in sorted(missing.items())])
        else:
            rep.add(C, "PASS", f"{tag} all {len(pairs)} catalog tab/category pairs are in kCats "
                               f"({len(rows)} rows)")
        empty = sorted(p for p in kc if p not in pairs)
        if empty:
            rep.add(C, "INFO", f"{tag} client categories with no catalog rows (shown empty or hidden)",
                    [f"{t}/{c} {kc[(t, c)]}" for t, c in empty])

    if cfiles is None:
        rep.add(C, "WARN", "client data not scanned: item/icon/String checks skipped")
        return
    customs = customs or {}
    ccidx, scidx = char_index(cfiles), char_index(sfiles)
    cnames, snames = string_names(cfiles), string_names(sfiles)
    no_data, no_icon, no_name, differ, name_diff, server_missing = [], [], [], [], [], []
    name_known = []
    # items missing from the stock client Data, resolved per branch against that branch's Custom.wz
    per_branch = {br.name: {"no_data": [], "no_icon": [], "no_name": [], "custom": [], "src_name": []}
                  for br in branches}
    src_names = {br.name: source_item_names(br) for br in branches}
    for r in rows:
        iid = r["id"]
        if 9110000 <= iid < 9120000:      # service rows: slot grants, not items
            continue
        ce, ci, cn, ch = item_status(iid, cfiles, ccidx, cnames)
        se, si, sn, sh = item_status(iid, sfiles, scidx, snames)
        if not se:
            server_missing.append(iid)
        if not ce:
            no_data.append(iid)
            for br in branches:
                pb = per_branch[br.name]
                xe, xi = custom_item_status(iid, cfiles, ccidx, customs.get(br.name, {}))
                if not xe:
                    pb["no_data"].append(iid)
                    continue
                pb["custom"].append(iid)
                if not xi:
                    pb["no_icon"].append(iid)
                if cn is None:
                    if iid in src_names[br.name]:
                        nm, f = src_names[br.name][iid]
                        pb["src_name"].append(f"{iid} '{nm}' ({f})")
                    else:
                        pb["no_name"].append(iid)
            continue
        if not ci and si:
            no_icon.append(iid)
        if cn is None:
            no_name.append(iid)
        if se and ch != sh:
            differ.append(iid)
        if sn is not None and cn is not None and sn != cn:
            if any(f"{iid:07d}" in keys or str(iid) in keys for img, (keys, _) in KNOWN_DIFFS.items()
                   if img.startswith("String/")):
                name_known.append(f"{iid}: server '{sn}' / client '{cn}' (known, allowlisted)")
            else:
                name_diff.append(f"{iid}: server '{sn}' / client '{cn}'")
    n = len(rows)
    if not no_data:
        rep.add(C, "PASS", f"catalog items with no client data: 0/{n}")
    for br in branches:
        if not no_data:
            break
        tag = f"[{br.name}@{br.sha}]"
        pb = per_branch[br.name]
        rep.add(C, "FAIL" if pb["no_data"] else "PASS",
                f"{tag} catalog items with no client data (stock Data + this branch's Custom.wz): "
                f"{len(pb['no_data'])}/{n}", [ex(pb["no_data"], 40)] if pb["no_data"] else [])
        if pb["custom"]:
            rep.add(C, "INFO", f"{tag} catalog items served from this branch's Custom.wz: {len(pb['custom'])}",
                    [ex(pb["custom"], 40)])
        if pb["no_icon"]:
            rep.add(C, "FAIL", f"{tag} Custom.wz catalog items with no info/icon: {len(pb['no_icon'])}",
                    [ex(pb["no_icon"], 40)])
        if pb["no_name"]:
            rep.add(C, "FAIL", f"{tag} Custom.wz catalog items with no client String name: {len(pb['no_name'])}",
                    [ex(pb["no_name"], 40)])
        if pb["src_name"]:
            rep.add(C, "INFO", f"{tag} Custom.wz catalog items named by the client source "
                               f"(stock String image cannot take new entries)", pb["src_name"])
    rep.add(C, "FAIL" if no_icon else "PASS",
            f"catalog items whose client data has no icon (server has one): {len(no_icon)}",
            [ex(no_icon, 40)] if no_icon else [])
    rep.add(C, "FAIL" if no_name else "PASS", f"catalog items with no client String name: {len(no_name)}",
            [ex(no_name, 40)] if no_name else [])
    rep.add(C, "WARN" if server_missing else "PASS",
            f"catalog items missing from the server WZ itself: {len(server_missing)}",
            [ex(server_missing, 40)] if server_missing else [])
    rep.add(C, "WARN" if differ else "PASS",
            f"catalog items whose client data differs from the server WZ (stats/strings; art ignored): "
            f"{len(differ)}", [ex(differ, 40)] if differ else [])
    rep.add(C, "WARN" if name_diff else "PASS", f"catalog items named differently server vs client: "
                                                f"{len(name_diff)}", name_diff[:40] + name_known)
    # whole-image comparison of the cash-relevant images
    cm = compare_dir(cfiles, sfiles, lambda r: r.startswith(("Item/Cash/", "Item/Pet/", "Etc/Commodity.img",
                                                              "String/")))
    det = [f"differ: {ex(cm['differ'], 12)}"] if cm["differ"] else []
    det += [f"server-only: {ex(cm['server_only'], 12)}"] if cm["server_only"] else []
    det += [f"known deliberate differences (allowlisted): {ex(cm['known'], 4)}"] if cm["known"] else []
    rep.add(C, "WARN" if cm["differ"] or cm["server_only"] else "PASS",
            f"Item/Cash, Item/Pet, String/*, Etc/Commodity.img: {cm['same']} same, {len(cm['differ'])} differ, "
            f"{len(cm['server_only'])} server-only, {len(cm['client_only'])} client-only", det)
    com_c, com_s = cfiles.get("Etc/Commodity.img"), sfiles.get("Etc/Commodity.img")
    if com_c and com_s and "top" in com_c and "top" in com_s:
        d = [k for k in com_s["top"] if k in com_c["top"] and com_c["top"][k] != com_s["top"][k]]
        so = [k for k in com_s["top"] if k not in com_c["top"]]
        co = [k for k in com_c["top"] if k not in com_s["top"]]
        rep.add(C, "WARN" if d or so else "PASS",
                f"Etc/Commodity.img SN rows: {len(d)} differ, {len(so)} server-only, {len(co)} client-only "
                f"(stock CS stage only; the window keys on item id)",
                ([f"differ SNs: {ex(d)}"] if d else []) + ([f"server-only SNs: {ex(so)}"] if so else []))
    # Character/* whole images, by equip slot directory
    chs = compare_dir(cfiles, sfiles, lambda r: r.startswith("Character/"))
    by = Counter(x.split("/")[1] if x.count("/") == 2 else "(root)" for x in chs["differ"])
    rep.add(C, "WARN" if chs["differ"] or chs["server_only"] else "PASS",
            f"Character/*: {chs['same']} same, {len(chs['differ'])} differ, {len(chs['server_only'])} server-only, "
            f"{len(chs['client_only'])} client-only",
            ([f"differ by dir: {dict(by.most_common())}", f"e.g. {ex(chs['differ'])}"] if chs["differ"] else [])
            + ([f"server-only: {ex(chs['server_only'])}"] if chs["server_only"] else []))


# =============================================================================================
# Check 5: whole-image comparison
# =============================================================================================
def compare_dir(cfiles, sfiles, pred):
    res = {"same": 0, "differ": [], "server_only": [], "client_only": [], "client_err": [], "server_err": [],
           "art_differ": [], "known": []}
    for rel in sorted(k for k in sfiles if pred(k)):
        s = sfiles[rel]
        c = cfiles.get(rel)
        if "err" in s:
            res["server_err"].append(f"{rel}: {s['err']}")
            continue
        if c is None:
            res["server_only"].append(rel)
        elif "err" in c:
            res["client_err"].append(f"{rel}: {c['err']}")
        elif c["h"] == s["h"]:
            res["same"] += 1
            if c.get("a") != s.get("a"):
                res["art_differ"].append(rel)
        elif rel in KNOWN_DIFFS and set(top_diff(c, s)) <= KNOWN_DIFFS[rel][0]:
            res["same"] += 1
            res["known"].append(f"{rel}[{','.join(top_diff(c, s))}]: {KNOWN_DIFFS[rel][1]}")
        else:
            res["differ"].append(rel)
    res["client_only"] = sorted(k for k in cfiles if pred(k) and k not in sfiles)
    return res


def misfiled_map(rel):
    m = re.match(r"Map/Map/Map(\d)/(\d{9})\.img$", rel)
    return bool(m) and int(m.group(1)) != int(m.group(2)) // 100000000


def top_diff(c, s):
    keys = set(c.get("top", {})) | set(s.get("top", {}))
    return sorted(k for k in keys if c.get("top", {}).get(k) != s.get("top", {}).get(k))


def check_wz(rep, cfiles, sfiles, scope):
    C = "5. Server WZ vs client Data"
    for top in scope:
        r = compare_dir(cfiles, sfiles, lambda k, t=top: k.startswith(t + "/"))
        total = r["same"] + len(r["differ"]) + len(r["server_only"])
        det = []
        lvl = "PASS"
        if top == "Map":
            mp = [x for x in r["differ"] if x.startswith("Map/Map/")]
            nav, cosmetic = [], []
            keyc = Counter()
            for x in mp:
                d = top_diff(cfiles[x], sfiles[x])
                keyc.update(k if k in NAV_KEYS or not k.isdigit() else "layer" for k in d)
                (nav if NAV_KEYS & set(d) else cosmetic).append(f"{x.rsplit('/', 1)[1][:-4]}[{','.join(d[:5])}]")
            det.append(f"maps: {len(mp)} differ; {len(nav)} in nav data (foothold/ladderRope/portal/info/seat/"
                       f"swimArea), {len(cosmetic)} elsewhere; differing sections: {dict(keyc.most_common(10))}")
            if nav:
                det.append(f"nav-relevant e.g. {ex(nav, 10)}")
            if cosmetic:
                det.append(f"other e.g. {ex(cosmetic, 6)}")
            other = [x for x in r["differ"] if not x.startswith("Map/Map/")]
            if other:
                det.append(f"Back/Obj/Tile/etc differ: {len(other)} e.g. {ex(other, 6)}")
            if nav:
                lvl = "WARN"
        elif r["differ"]:
            secs = Counter()
            for x in r["differ"]:
                secs.update("#" if k.isdigit() else k for k in top_diff(cfiles[x], sfiles[x]))
            det.append(f"differing top-level sections ('#' = numbered entries, e.g. item/skill ids): "
                       f"{dict(secs.most_common(8))}")
            ex_ = []
            for x in r["differ"][:MAX_EXAMPLES]:
                ex_.append(f"{x}[{','.join(top_diff(cfiles[x], sfiles[x])[:4])}]")
            det.append(f"differ e.g. {', '.join(ex_)}" + (f" ... (+{len(r['differ']) - MAX_EXAMPLES})"
                                                          if len(r["differ"]) > MAX_EXAMPLES else ""))
            if top == "Mob":
                stats = [x.rsplit("/", 1)[1][:-4] for x in r["differ"] if "info" in top_diff(cfiles[x], sfiles[x])]
                det.append(f"mobs whose info (stats: PADamage, HP, speed ...) differ: {len(stats)} e.g. "
                           f"{ex(stats)} -- the client computes touch damage from ITS copy")
        if r["differ"] and top in CORE_DIRS and top != "Map":
            lvl = "WARN"
        so = [x for x in r["server_only"] if not any(x.startswith(p) for p in SERVER_ONLY_OK)]
        misfiled = [x for x in so if misfiled_map(x)]
        so = [x for x in so if x not in misfiled]
        if misfiled:
            det.append(f"server images in the wrong MapN folder (the server never loads them; the correctly "
                       f"filed copy is compared instead): {ex(misfiled)}")
        known = [x for x in r["server_only"] if x not in so and x not in misfiled]
        if known:
            det.append(f"server-only by design: {ex(known)}")
        r["server_only"] = so
        if so:
            hard = [x for x in so if top in CORE_DIRS and not x.startswith("Map/WorldMap/")]
            det.append(f"server-only (client lacks the image): {ex(so)}")
            if top in ("Mob", "Npc"):
                named = sfiles.get(f"String/{top}.img", {}).get("names", {})
                soft = [x for x in hard if str(int(re.sub(r"\D", "", x.rsplit("/", 1)[1]) or 0)) not in named]
                if soft:
                    det.append(f"of those, no server String/{top}.img name (likely unused, so WARN not FAIL): "
                               f"{ex(soft)}")
                hard = [x for x in hard if x not in soft]
            lvl = "FAIL" if hard else max(lvl, "WARN", key=LEVELS.index)
        if r["client_err"]:
            det.append(f"client images that do not parse: {ex(r['client_err'], 4)}")
            lvl = max(lvl, "WARN", key=LEVELS.index)
        if r["server_err"]:
            det.append(f"server XML that does not parse: {ex(r['server_err'], 4)}")
            lvl = max(lvl, "WARN", key=LEVELS.index)
        if r["client_only"]:
            det.append(f"client-only (server has no data; unreachable from the server): {len(r['client_only'])} "
                       f"e.g. {ex(r['client_only'], 4)}")
        if r["known"]:
            det.append(f"known deliberate differences (allowlisted, counted as identical): {ex(r['known'], 4)}")
        if r["art_differ"]:
            det.append(f"same data but different art metadata (canvas sizes/origins/links; the server dump is "
                       f"lossy here, so informational): {len(r['art_differ'])} e.g. {ex(r['art_differ'], 4)}")
        if top not in CORE_DIRS and r["differ"] and lvl == "PASS":
            lvl = "INFO"
        if top not in CORE_DIRS and lvl == "WARN" and not r["server_only"] and not r["client_err"]:
            lvl = "INFO"
        rep.add(C, lvl, f"{top}: {total} server images: {r['same']} identical, {len(r['differ'])} differ, "
                        f"{len(r['server_only'])} missing on client; {len(r['client_only'])} client-only", det)


# =============================================================================================
# Check 4: Custom.wz
# =============================================================================================
def custom_paths(blob_or_path, masks):
    """{image path: [kind:node path,...]} for a Custom.wz."""
    out = {}
    path = blob_or_path
    for img, data in wzscan.pkg1_images(path, masks):
        tree = wzscan.parse_img_bytes(data, masks)
        out[img] = wzscan.summarize(tree, img, dump=True)["paths"]
    return out


def resource_refs(br: Branch):
    refs = defaultdict(set)
    for p in br.sources():
        for m in re.finditer(r'L"((?:UI|Effect|Sound|Map|Item|Character|Mob|Npc|Etc|String|Skill)/[\w.%/-]+\.img'
                             r'(?:/[^"]*)?)"', br.read(p) or ""):
            refs[m.group(1)].add(os.path.basename(p))
    return refs


def ref_split(ref):
    m = re.match(r"(.*?\.img)(?:/(.*))?$", ref)
    return m.group(1), (m.group(2) or "")


def path_exists(paths, node):
    """node may contain %d/%s/%ls segments (wildcards)."""
    if node == "":
        return True
    pat = re.compile("^" + "/".join(".+?" if "%" in seg else re.escape(seg) for seg in node.split("/")) + "$")
    return any(pat.match(p.split(":", 1)[1]) for p in paths)


def check_custom(rep, a, branches, cfiles, extra, masks, server_flags, log):
    C = "4. Custom.wz"
    customs = {}
    for br in branches:
        c = br.custom_images(a.cache_dir, masks)
        if c is None:
            rep.add(C, "WARN", f"[{br.name}] src/Custom.wz missing")
            continue
        customs[br.name] = c
    dep = None
    if extra and extra.get("Custom.wz"):
        sha = extra["Custom.wz"]["sha256"]
        lp = fetch_remote_file(a, "Custom.wz", sha, log)
        if lp:
            dep = (sha, custom_paths(lp, masks))
            match = [b for b, (h, _) in customs.items() if h == sha]
            rep.add(C, "PASS" if match else "WARN",
                    f"deployed test-client Custom.wz {sha[:12]} "
                    + (f"== branch {', '.join(match)}" if match else "matches no client branch's src/Custom.wz"))
    for name, (sha, imgs) in list(customs.items()) + ([("deployed", dep)] if dep else []):
        br = next((b for b in branches if b.name == name), None)
        tag = f"[{name}{'@' + br.sha if br else ''}]"
        lines = []
        for img, paths in sorted(imgs.items()):
            crec = cfiles.get(img) if cfiles else None
            if cfiles is None:
                lines.append(f"{img}: {len(paths)} nodes (client data not scanned)")
            elif crec is None:
                lines.append(f"{img}: NEW image ({len(paths)} nodes), served whole from Custom.wz")
            else:
                stock = crec.get("paths")
                if stock is None:
                    lines.append(f"{img}: overlays a stock image ({len(paths)} nodes; stock tree not dumped)")
                    continue
                sset = {p.split(":", 1)[1] for p in stock}
                newsub, merged, shadow = [], [], []
                for p in paths:
                    k, node = p.split(":", 1)
                    parent = node.rsplit("/", 1)[0] if "/" in node else ""
                    if node in sset:
                        if k != "D":
                            shadow.append(node)
                    elif parent == "" or parent in sset:
                        (newsub if k == "D" else merged).append(node)
                tops = sorted({n.split("/")[0] for n in newsub if n.split("/")[0] not in sset})
                lines.append(f"{img}: overlays stock image; {len(merged)} leaf node(s) merged, "
                             f"{len(newsub)} new sub-propert(y/ies)"
                             + (f" (new top-level {ex(tops, 4)}: NOT merged into the stock image)" if tops else "")
                             + (f", {len(shadow)} node(s) shadowing stock values (overwrite unverified)"
                                if shadow else ""))
        rep.add(C, "INFO", f"{tag} Custom.wz {sha[:12]} contents", lines)
    # every resource the client source names must resolve, and so must what the server expects
    for br in branches:
        tag = f"[{br.name}@{br.sha}]"
        cimgs = customs.get(br.name, (None, {}))[1]
        refs = resource_refs(br)
        expect = {}
        for feat, path, why in SERVER_EXPECTED_RESOURCES:
            if feat == "always" or (feat == "dailycheckin" and br.has("src/dailycheckin.cpp")):
                expect[path] = why
        for path in expect:
            refs.setdefault(path, set()).add("server")
        bad, warn, ok = [], [], []
        for ref, who in sorted(refs.items()):
            img, node = ref_split(ref)
            where = []
            if img in cimgs and path_exists(cimgs[img], node):
                where.append("Custom.wz")
            if cfiles is not None:
                crec = cfiles.get(img)
                if crec is not None:
                    if node == "" or (crec.get("paths") is not None and path_exists(crec["paths"], node)):
                        where.append("client Data")
                    elif crec.get("paths") is None:
                        where.append("client Data (image only; tree not dumped)")
            label = f"{ref} ({', '.join(sorted(who))})"
            if "%" in img and not where:
                ok.append(f"{label} -> dynamic image name, not checked")
                continue
            if not where and any(r2 != ref and ref_split(r2)[1] == node and who & w2
                                 for r2, w2 in refs.items()):
                ok.append(f"{label} -> fallback for another path with the same node (unresolved)")
                continue
            if where:
                ok.append(f"{label} -> {' + '.join(where)}")
            elif ref in expect:
                bad.append(f"{label}: server expects it ({expect[ref]}) but neither Custom.wz nor client Data has it")
            elif cfiles is None:
                warn.append(f"{label}: not in Custom.wz (client data not scanned)")
            else:
                warn.append(f"{label}: resolves nowhere (fallback path or dead reference?)")
        if bad:
            rep.add(C, "FAIL", f"{tag} resources missing", bad)
        if warn:
            rep.add(C, "WARN", f"{tag} client source resource paths that resolve nowhere", warn)
        rep.add(C, "PASS", f"{tag} {len(ok)} resource path(s) resolve", ok)
    if dep and cfiles is not None:
        # the deployed Custom.wz must carry whatever the server needs for features the server has ON
        dimgs = dep[1]
        for feat, path, why in SERVER_EXPECTED_RESOURCES:
            if feat != "always" and not server_flags.get(FEATURES[feat]["server_flag"]):
                continue
            img, node = ref_split(path)
            ok = (img in dimgs and path_exists(dimgs[img], node)) or (
                cfiles.get(img) and cfiles[img].get("paths") and path_exists(cfiles[img]["paths"], node))
            rep.add(C, "PASS" if ok else "FAIL", f"[deployed] {path} ({why}) "
                                                 f"{'present' if ok else 'MISSING'}")
    if extra and extra.get("List.wz"):
        rep.add(C, "INFO", f"deployed List.wz sha256 {extra['List.wz']['sha256'][:12]} "
                           f"({extra['List.wz']['size']} bytes)")


# =============================================================================================
# Check 6: versions and feature flags
# =============================================================================================
def server_flag_values(a):
    flags = {}
    svc = os.path.join(a.server, "deploy/slumsms.service.example")
    src = []
    if os.path.exists(svc):
        for m in re.finditer(r"-D([\w.-]+)=(\w+)", open(svc, encoding="utf-8").read()):
            flags[m.group(1)] = m.group(2).lower() == "true"
        src.append("deploy/slumsms.service.example")
    cfg = a.server_config or next((p for p in (os.path.join(a.server, "config.yaml"),
                                                os.path.join(a.server, "config.example.yaml"))
                                   if os.path.exists(p)), None)
    sc = open(os.path.join(a.server, "src/main/java/config/ServerConfig.java"), encoding="utf-8").read()
    m = re.search(r"DAILY_CHECKIN_ENABLED\s*=\s*(true|false)", sc)
    flags["DAILY_CHECKIN_ENABLED"] = bool(m and m.group(1) == "true")
    if cfg and os.path.exists(cfg):
        m = re.search(r"^\s*DAILY_CHECKIN_ENABLED:\s*(\w+)", open(cfg, encoding="utf-8").read(), re.M)
        if m:
            flags["DAILY_CHECKIN_ENABLED"] = m.group(1).lower() == "true"
        src.append(cfg)
    # --server-flag goes last so it overrides the unit file, ServerConfig.java and config.yaml alike
    for k, v in parse_flag_overrides(a.server_flag):
        flags[k] = v
        src.append(f"--server-flag {k}={'true' if v else 'false'}")
    return flags, src


def parse_flag_overrides(items):
    """--server-flag values as [(key, bool)]. Accepts KEY=VALUE, server.KEY=VALUE (the config.yaml
    section spelling) and -DKEY=VALUE; a bare KEY means true."""
    out = []
    for raw in items or []:
        k, eq, v = raw.strip().partition("=")
        k = k.strip()
        if k.startswith("-D"):
            k = k[2:]
        if k.startswith("server."):
            k = k[len("server."):]
        if not k:
            raise ValueError(f"--server-flag {raw!r}: empty key")
        out.append((k, v.strip().lower() in ("1", "true", "yes", "on") if eq else True))
    return out


def client_defines(br: Branch):
    ps = br.read("scripts/build.ps1") or ""
    on = set(re.findall(r"-D(MAPLEROOT_\w+)=ON", ps))
    off = set(re.findall(r"-D(MAPLEROOT_\w+)=OFF", ps))
    return on, off


def dll_features(dll_bytes, br: Branch):
    """Which MAPLEROOT_* features a built slums.dll contains, judged by wide-string resource paths that only the
    feature's own source files use."""
    cm = br.read("src/CMakeLists.txt") or ""
    per = {}
    for m in re.finditer(r"if\((MAPLEROOT_\w+)\)\s*\n\s*target_sources\(injector PRIVATE ([^)]*)\)", cm):
        per[m.group(1)] = m.group(2).split()
    res = {}
    for define, files in per.items():
        lits = set()
        for f in files:
            txt = br.read(f"src/{f}") or ""
            lits.update(re.findall(r'L"([A-Za-z]+/[\w./%-]{8,})"', txt))
        lits = {l for l in lits if "%" not in l}
        other = set()
        for d2, f2 in per.items():
            if d2 != define:
                for f in f2:
                    other.update(re.findall(r'L"([^"]+)"', br.read(f"src/{f}") or ""))
        lits -= other
        if not lits:
            continue
        hits = sum(1 for l in lits if l.encode("utf-16le") in dll_bytes)
        res[define] = (hits, len(lits))
    return res


def check_flags(rep, a, branches, flags, flag_src, extra, log):
    C = "6. Version / feature flags"
    sc = open(os.path.join(a.server, "src/main/java/constants/net/ServerConstants.java"), encoding="utf-8").read()
    ver = re.search(r"short VERSION\s*=\s*(\d+)", sc)
    sv = re.search(r'SLUMSMS_VERSION\s*=\s*"([^"]+)"', sc)
    rep.add(C, "INFO", f"server: MapleStory v{ver.group(1) if ver else '?'}, SlumsMS {sv.group(1) if sv else '?'}; "
                       f"flags {json.dumps(flags, sort_keys=True)} from {', '.join(flag_src)}")
    for br in branches:
        res = br.read("src/resman.cpp") or ""
        m = re.search(r'Init\(L"(\d+)"', res)
        if m and ver and m.group(1) != ver.group(1):
            rep.add(C, "FAIL", f"[{br.name}] Custom.wz package version {m.group(1)} != server VERSION {ver.group(1)}")
    sb = os.path.join(a.server, "release/baseline.json")
    base = json.load(open(sb, encoding="utf-8")) if os.path.exists(sb) else {}
    for br in branches:
        tag = f"[{br.name}@{br.sha}]"
        on, off = client_defines(br)
        rep.add(C, "INFO", f"{tag} build.ps1 defines ON: {', '.join(sorted(on))}")
        for feat, d in FEATURES.items():
            c_on = d["client_define"] in on
            s_on = bool(flags.get(d["server_flag"]))
            if c_on == s_on:
                rep.add(C, "PASS", f"{tag} {feat}: client {d['client_define']}={'ON' if c_on else 'OFF'}, server "
                                   f"{d['server_flag']}={s_on}")
            elif s_on and not c_on:
                rep.add(C, "WARN", f"{tag} {feat}: server {d['server_flag']}=true but this client build lacks "
                                   f"{d['client_define']} (server packets/NPC entry points go unanswered)")
            else:
                rep.add(C, "WARN", f"{tag} {feat}: client builds {d['client_define']} but server "
                                   f"{d['server_flag']}=false (feature dead until the server enables it)")
        cb = br.read("release/baseline.json")
        if cb:
            cbj = json.loads(cb)
            if base and cbj.get("baseline") == base.get("baseline"):
                if set(cbj.get("client_flags", [])) != set(base.get("client_flags", [])):
                    rep.add(C, "WARN", f"{tag} release/baseline.json client_flags differ from the server's copy")
            if set(cbj.get("client_flags", [])) - on:
                rep.add(C, "INFO", f"{tag} baseline {cbj.get('baseline')} lists flags build.ps1 no longer turns on: "
                                   f"{sorted(set(cbj.get('client_flags', [])) - on)}")
            if on - set(cbj.get("client_flags", [])) - {"MAPLEROOT_INTEGRATION"}:
                rep.add(C, "INFO", f"{tag} build.ps1 turns on flags newer than baseline {cbj.get('baseline')}: "
                                   f"{sorted(on - set(cbj.get('client_flags', [])))}")
    if base:
        for k, v in base.get("server_flags", {}).items():
            if k in flags and flags[k] != v:
                rep.add(C, "WARN", f"server baseline {base.get('baseline')} says {k}={v}, current source says "
                                   f"{flags[k]}")
    if extra and extra.get("slums.dll"):
        sha = extra["slums.dll"]["sha256"]
        lp = fetch_remote_file(a, "slums.dll", sha, log)
        if lp:
            dll = open(lp, "rb").read()
            br = branches[-1]
            feats = dll_features(dll, br)
            have = {d for d, (h, n) in feats.items() if h * 2 >= n}
            rep.add(C, "INFO", f"deployed slums.dll {sha[:12]}: features detected "
                               f"{', '.join(f'{d} {h}/{n}' for d, (h, n) in sorted(feats.items()))}")
            for feat, d in FEATURES.items():
                if d["client_define"] not in feats:
                    continue
                c_on = d["client_define"] in have
                s_on = bool(flags.get(d["server_flag"]))
                if c_on != s_on:
                    rep.add(C, "WARN", f"[deployed] {feat}: slums.dll {'has' if c_on else 'lacks'} it, server "
                                       f"{d['server_flag']}={s_on}")
                else:
                    rep.add(C, "PASS", f"[deployed] {feat}: slums.dll and server agree ({'on' if c_on else 'off'})")


# =============================================================================================
# --diff: field-level explanation of one differing image
# =============================================================================================
def diff_images(a, rels, masks, limit=40):
    for rel in rels:
        rel = rel.replace("\\", "/").lstrip("/")
        local = os.path.join(a.cache_dir, "diff", rel)
        os.makedirs(os.path.dirname(local), exist_ok=True)
        if not os.path.exists(local) or a.refresh:
            Remote(a).get(win_join(a.remote_client, "Data", rel.replace("/", "\\")), local)
        top, rest = rel.split("/", 1)
        xml = os.path.join(a.server_wz, top + ".wz", rest + ".xml")
        with open(local, "rb") as f:
            c = wzscan.flatten(wzscan.normalize(wzscan.parse_img_bytes(f.read(), masks), rel), a.art)
        s = (wzscan.flatten(wzscan.normalize(wzscan.parse_xml_file(xml), rel), a.art)
             if os.path.exists(xml) else {})
        oc = sorted(k for k in c if k not in s)
        os_ = sorted(k for k in s if k not in c)
        ch = sorted(k for k in c if k in s and c[k] != s[k])
        print(f"== {rel}: {len(ch)} changed, {len(oc)} client-only, {len(os_)} server-only node(s)")
        for k in ch[:limit]:
            print(f"  ~ {k}: server {s[k]!r} / client {c[k]!r}")
        for k in oc[:limit]:
            print(f"  + client {k}: {c[k]!r}")
        for k in os_[:limit]:
            print(f"  - server {k}: {s[k]!r}")


# =============================================================================================
# main
# =============================================================================================
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    root = os.path.abspath(os.path.join(HERE, "..", ".."))
    ap.add_argument("--server", default=root, help="server repo (default: this checkout)")
    ap.add_argument("--server-wz", default=os.environ.get("CLIENTCHECK_SERVER_WZ", "/root/work/bake/wz"))
    ap.add_argument("--server-config", help="config.yaml to read DAILY_CHECKIN_ENABLED from")
    ap.add_argument("--server-flag", action="append", help="override a server flag, e.g. weather.enabled=true")
    ap.add_argument("--client-repo", default=os.environ.get("CLIENTCHECK_CLIENT_REPO", "/root/work/client/src"))
    ap.add_argument("--client-branches", nargs="+", default=["main", "daily-checkin"])
    ap.add_argument("--remote", default="crims@192.168.0.186")
    ap.add_argument("--ssh-key", default="~/.ssh/id_rsprox_desktop")
    ap.add_argument("--remote-client", default=r"Z:\MSv83\SlumsMS-dev\test-client")
    ap.add_argument("--remote-work", default=r"Z:\MSv83\SlumsMS-dev\clientcheck",
                    help="remote scratch dir (must be inside Z:\\MSv83\\SlumsMS-dev)")
    ap.add_argument("--remote-python", default=r"C:\Users\CRIMS\AppData\Local\Programs\Python\Python313\python.exe")
    ap.add_argument("--cache-dir", default=os.environ.get("CLIENTCHECK_CACHE",
                                                          os.path.expanduser("~/.cache/slumsms-clientcheck")))
    ap.add_argument("--max-age", type=float, default=60, help="minutes a cached client scan stays fresh")
    ap.add_argument("--refresh", action="store_true", help="rescan the client even if the cache is fresh")
    ap.add_argument("--offline", action="store_true", help="never contact the desktop; use cached scans")
    ap.add_argument("--no-data", action="store_true", help="source-only checks (1, 2, 6 and the source half of 3/4)")
    ap.add_argument("--only", nargs="+", type=int, choices=range(1, 7), help="run only these checks")
    ap.add_argument("--jobs", type=int, default=min(16, os.cpu_count() or 4))
    ap.add_argument("--json", help="also write the findings as JSON here")
    ap.add_argument("--verbose", "-v", action="store_true")
    ap.add_argument("--diff", nargs="+", metavar="REL",
                    help="explain differing images field by field, e.g. --diff Mob/0000420.img (copies just those "
                         "files from the desktop)")
    ap.add_argument("--art", action="store_true", help="with --diff: also show canvas/UOL differences")
    a = ap.parse_args()
    if not re.match(r"(?i)^Z:\\MSv83\\SlumsMS-dev(\\|$)", a.remote_work):
        ap.error("--remote-work must be inside Z:\\MSv83\\SlumsMS-dev (the only writable place on the desktop)")
    os.makedirs(a.cache_dir, exist_ok=True)

    def log(msg):
        print(f"[clientcheck] {msg}", file=sys.stderr, flush=True)

    if a.diff:
        ks = os.path.join(a.cache_dir, "keystream.bin")
        if not os.path.exists(ks):
            with open(ks, "wb") as f:
                f.write(wzscan.make_keystream())
        diff_images(a, a.diff, wzscan.Masks(open(ks, "rb").read()))
        return 0

    only = set(a.only or range(1, 7))
    rep = Report()
    t0 = time.time()
    branches = [Branch(a.client_repo, b) for b in a.client_branches]
    log("client branches: " + ", ".join(f"{b.name}@{b.sha}" for b in branches))
    flags, flag_src = server_flag_values(a)

    if 1 in only:
        check_opcodes(rep, a.server, branches, flags)
    if 2 in only:
        check_layouts(rep, a.server, branches)
        check_layout_constants(rep, a.server, branches)

    cfiles = sfiles = extra = None
    ks = os.path.join(a.cache_dir, "keystream.bin")
    if not os.path.exists(ks):
        with open(ks, "wb") as f:
            f.write(wzscan.make_keystream())
    masks = wzscan.Masks(open(ks, "rb").read())
    if not a.no_data and only & {3, 4, 5, 6}:
        dumps = set()
        for br in branches:
            for ref in resource_refs(br):
                dumps.add(ref_split(ref)[0])
            c = br.custom_images(a.cache_dir, masks)
            if c:
                dumps.update(c[1])
        for _, path, _ in SERVER_EXPECTED_RESOURCES:
            dumps.add(ref_split(path)[0])
        try:
            scan = remote_scan(a, sorted(dumps), log)
            cfiles, extra = scan["files"], scan.get("extra", {})
            nerr = sum(1 for v in cfiles.values() if "err" in v)
            rep.add("0. Inputs", "INFO", f"client Data {a.remote_client}: {len(cfiles)} images scanned"
                                         f"{f', {nerr} unreadable' if nerr else ''}; "
                                         f"scan from {time.strftime('%Y-%m-%d %H:%M', time.localtime(scan['scanned_at']))}")
        except Exception as e:  # noqa
            rep.add("0. Inputs", "WARN", f"client data unavailable: {str(e).splitlines()[0][:200]}")
        if cfiles is not None:
            sfiles = server_scan(a, log)
            rep.add("0. Inputs", "INFO", f"server WZ {a.server_wz}: {len(sfiles)} images")
            for k, v in sorted((extra or {}).items()):
                rep.add("0. Inputs", "INFO", f"deployed {k}: " + (f"sha256 {v['sha256'][:16]} ({v['size']} bytes)"
                                                                 if v else "absent"))
    if 3 in only:
        check_cashshop(rep, a.server, branches, cfiles, sfiles,
                       {br.name: (br.custom_images(a.cache_dir, masks) or (None, {}))[1] for br in branches})
    if 4 in only:
        check_custom(rep, a, branches, cfiles, extra, masks, flags, log)
    if 5 in only and cfiles is not None:
        check_wz(rep, cfiles, sfiles, CORE_DIRS + [d for d in DATA_DIRS if d not in CORE_DIRS])
    if 6 in only:
        check_flags(rep, a, branches, flags, flag_src, extra, log)

    print(rep.text(a.verbose))
    log(f"done in {time.time() - t0:.0f}s")
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump({"counts": rep.counts(), "items": rep.items}, f, indent=1)
    return 1 if rep.worst() == LEVELS.index("FAIL") else 0


if __name__ == "__main__":
    sys.exit(main())
