#!/usr/bin/env python3
"""Compare this server tree against an upstream Cosmic checkout.

Finds what the MapleRoot repack (or we) may have stripped from Cosmic:

  * files present in Cosmic but missing here (java, scripts, resources)
  * files much shorter here than in Cosmic
  * Java methods whose body here is empty / a bare `return` / `return
    null|false|true|0|-1` while Cosmic's has real logic, and methods that kept
    only a small fraction of Cosmic's statements
  * commented-out code blocks here whose lines are live code in Cosmic
  * scripts (npc/quest/portal/reactor/event/item/map) that are stubs here, and
    script functions emptied out

Each finding gets a stable id. Classifications live in classifications.json
next to this file ({id: {"class": DELIBERATE|BROKEN|UNSURE|RESTORED,
"why": "..."}}) and are merged into the report, so the tool can be rerun after
upstream or local changes and only new findings show up unclassified.

Usage:
  python3 tools/cosmicdiff/cosmic_diff.py --cosmic /root/workspace/cosmicms \
      [--ours .] [--baseline 03d13cb21] [--out docs/release/cosmic-diff.md]
"""
import argparse
import difflib
import json
import os
import re
import subprocess
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))

SHORT_RATIO = 0.5          # file is "much shorter" below this fraction of Cosmic's code lines
SHORT_MIN_LINES = 40       # ...and Cosmic's file has at least this many code lines
METHOD_SHRINK_RATIO = 0.45 # method kept below this fraction of Cosmic's statements
METHOD_SHRINK_MIN = 4      # ...and Cosmic's method has at least this many statements
REWRITE_RATIO = 0.35       # script shares less than this fraction of lines with Cosmic
COMMENT_BLOCK_MIN = 3      # commented-out block length worth reporting

DELEGATION = re.compile(r"^(?:return\s+)?(?:this\.)?([A-Za-z_$][\w$]*)\s*\([^;]*\)\s*;$")
TRIVIAL_BODY = re.compile(r"^(return\s*(null|false|true|0|-1|0L|\"\"|Collections\.empty\w*\(\)|new \w+(<>)?\(\))?\s*;)?$")


# ---------------------------------------------------------------- utilities

def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def strip_comments_and_strings(src, keep_strings=False):
    """Replace comments (and optionally string contents) with spaces, keeping newlines."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and nxt == "*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", src[i:j]))
            i = j
        elif c in "\"'`":
            q = c
            j = i + 1
            while j < n and src[j] != q:
                if src[j] == "\\":
                    j += 1
                elif src[j] == "\n" and q != "`":
                    break
                j += 1
            j = min(j + 1, n)
            if keep_strings:
                out.append(src[i:j])
            else:
                out.append(q + re.sub(r"[^\n]", " ", src[i + 1:j - 1]) + q if j - i >= 2 else src[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def code_lines(src):
    s = strip_comments_and_strings(src, keep_strings=True)
    return [l.strip() for l in s.splitlines() if l.strip()]


def match_brace(s, open_idx):
    depth = 0
    for k in range(open_idx, len(s)):
        if s[k] == "{":
            depth += 1
        elif s[k] == "}":
            depth -= 1
            if depth == 0:
                return k
    return -1


def statements(body):
    b = body.strip()
    if not b:
        return 0
    return b.count(";") + len(re.findall(r"\b(if|for|while|switch|try)\b", b))


def norm(s):
    return re.sub(r"\s+", " ", s).strip()


JAVA_METHOD = re.compile(r"([A-Za-z_$][\w$]*)\s*\(([^()]*)\)\s*(?:throws\s+[\w$.,\s]+?)?\{")
JAVA_KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "do"}
CLASS_DECL = re.compile(r"\b(?:class|enum|interface|record)\s+(\w+)")


def java_methods(src):
    """Return {key: (name, body_text, line)} for methods; key = Class.name/arity."""
    s = strip_comments_and_strings(src)
    s_str = strip_comments_and_strings(src, keep_strings=True)
    classes = []  # (body_start, body_end, name)
    for cm in CLASS_DECL.finditer(s):
        ob = s.find("{", cm.end())
        if ob >= 0:
            classes.append((ob, match_brace(s, ob), cm.group(1)))
    res = {}
    for m in JAVA_METHOD.finditer(s):
        name = m.group(1)
        if name in JAVA_KEYWORDS:
            continue
        # text of the declaration before the name, back to the previous statement boundary
        k = m.start() - 1
        while k >= 0 and s[k] not in ";{}":
            k -= 1
        before = re.sub(r"@[\w$.]+(\s*\([^()]*\))?", " ", s[k + 1:m.start()]).strip()
        if not before or "=" in before or "(" in before or re.search(r"\b(return|new|else|throw)\s*$", before):
            continue
        if not re.search(r"[\w$>\]]$", before):
            continue
        params = m.group(2).strip()
        # drop generics before counting params
        p = re.sub(r"<[^<>]*>", "", re.sub(r"<[^<>]*>", "", params))
        arity = 0 if not p else p.count(",") + 1
        ob = m.end() - 1
        cb = match_brace(s, ob)
        if cb < 0:
            continue
        body = s_str[ob + 1:cb]
        # nearest enclosing class name
        cls, best = None, -1
        for cs, ce, cname in classes:  # innermost enclosing class
            if cs < m.start() and (ce < 0 or m.start() < ce) and cs > best:
                cls, best = cname, cs
        key = f"{cls}.{name}/{arity}"
        if key in res:
            key += f"#{m.start()}"
        res[key] = (name, body, src.count("\n", 0, m.start()) + 1)
    return res


JS_FUNC = re.compile(r"function\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)\s*\{")


def js_functions(src):
    s = strip_comments_and_strings(src)
    s_str = strip_comments_and_strings(src, keep_strings=True)
    res = {}
    for m in JS_FUNC.finditer(s):
        ob = m.end() - 1
        cb = match_brace(s, ob)
        if cb < 0:
            continue
        res.setdefault(m.group(1), s_str[ob + 1:cb])
    return res


COMMENTED_CODE = re.compile(r"^\s*//\s?(.*)$")
CODE_LIKE = re.compile(r"(;\s*$|\{\s*$|^\s*\}|^\s*(if|for|while|return|case|else)\b)")


# boilerplate lines that match Cosmic by coincidence, not because Cosmic's code was commented out here
GENERIC_LINE = re.compile(r"^(.{0,8}|\}?\s*(catch|finally|else|try|do)\b.*|(break|continue|return\s*\w*)\s*;"
                          r"|e\.printStackTrace\(\);|\}\s*\)*;?|cm\.dispose\(\);|qm\.dispose\(\);)$")


def comment_lines(src):
    """{line_no: text} for every line that is (part of) a comment: `//` lines and the inside of
    non-javadoc `/* */` blocks, with comment markers stripped."""
    out = {}
    lines = src.splitlines()
    in_block = javadoc = False
    for i, raw in enumerate(lines, 1):
        line = raw
        if in_block:
            end = line.find("*/")
            text = line if end < 0 else line[:end]
            if not javadoc:
                out[i] = re.sub(r"^\s*\*(?!/)\s?", "", text).strip()
            if end >= 0:
                in_block = False
            continue
        m = COMMENTED_CODE.match(line)
        if m:
            out[i] = m.group(1).strip()
            continue
        st = line.find("/*")
        if st >= 0 and not line[:st].strip() and "*/" not in line[st + 2:]:
            javadoc = line[st:].startswith("/**")
            in_block = True
            if not javadoc and line[st + 2:].strip():
                out[i] = line[st + 2:].strip()
    return out


def commented_runs(src):
    """Consecutive commented lines grouped: yields (start_line, [texts])."""
    cl = comment_lines(src)
    run, start, prev = [], None, None
    for i in sorted(cl):
        if prev is not None and i != prev + 1:
            yield start, run
            run = []
        if not run:
            start = i
        run.append(cl[i])
        prev = i
    if run:
        yield start, run


def commented_live_code(o_src, c_src, c_live):
    """Commented-out code here that is live (and not also commented) in Cosmic.
    Returns (blocks, singles): blocks = [(start, end, hits, n_code)], singles = [line numbers]."""
    c_dead = set(norm(t) for t in comment_lines(c_src).values())
    blocks, singles = [], []
    for start, run in commented_runs(o_src):
        code = [norm(b) for b in run if CODE_LIKE.search(b) and len(norm(b)) > 3]
        if not code or sum(1 for b in code if b in c_dead) >= len(code) / 2:
            continue  # prose, or Cosmic has the same code commented out too
        hit_idx = [k for k, b in enumerate(run) if CODE_LIKE.search(b) and norm(b) in c_live
                   and not GENERIC_LINE.match(norm(b))]
        if len(hit_idx) >= COMMENT_BLOCK_MIN and len(hit_idx) >= len(code) / 2:
            blocks.append((start, start + len(run) - 1, len(hit_idx), len(code)))
        else:
            singles += [start + k for k in hit_idx]
    return blocks, singles


def walk(root, sub, exts):
    base = os.path.join(root, sub)
    out = {}
    for dp, dn, fn in os.walk(base):
        for f in fn:
            if exts and not f.endswith(exts):
                continue
            p = os.path.join(dp, f)
            out[os.path.relpath(p, root)] = p
    return out


# ---------------------------------------------------------------- git provenance

class Git:
    def __init__(self, repo, baseline):
        self.repo, self.baseline = repo, baseline
        self.cache = {}

    def run(self, *args):
        try:
            return subprocess.run(["git", "-C", self.repo, *args], capture_output=True, text=True,
                                  check=False).stdout
        except OSError:
            return ""

    def provenance(self, rel):
        if rel in self.cache:
            return self.cache[rel]
        if not self.baseline:
            return ""
        in_base = bool(self.run("ls-tree", "--name-only", self.baseline, "--", rel).strip())
        log = self.run("log", "--format=%h %s", f"{self.baseline}..HEAD", "--", rel).strip().splitlines()
        if not in_base and not log:
            p = "absent at baseline and never added"
        elif not log:
            p = "repack-shipped (unchanged since baseline)"
        else:
            p = ("in baseline; " if in_base else "added later; ") + f"{len(log)} later commit(s): " + \
                "; ".join(log[:3]) + (" ..." if len(log) > 3 else "")
        self.cache[rel] = p
        return p


# ---------------------------------------------------------------- analysis

def report_commented(add, lang, rel, o_src, c_src):
    c_live = set(norm(l) for l in code_lines(c_src))
    blocks, singles = commented_live_code(o_src, c_src, c_live)
    for start, end, hits, n in blocks:
        add(f"{lang}-commented", rel, f"lines {start}-{end}: {hits}/{n} commented-out code lines are live "
            f"in Cosmic", f"L{start}")
    if singles:
        shown = ", ".join(map(str, singles[:12])) + (" ..." if len(singles) > 12 else "")
        add(f"{lang}-commented-lines", rel, f"{len(singles)} single commented-out line(s) live in Cosmic: "
            f"lines {shown}", None)


def analyse(ours, cosmic):
    findings = []

    def add(kind, rel, detail, key=None):
        fid = f"{kind}:{rel}" + (f"::{key}" if key else "")
        findings.append({"id": fid, "kind": kind, "file": rel, "detail": detail})

    # --- Java
    o_java = walk(ours, "src/main/java", (".java",))
    c_java = walk(cosmic, "src/main/java", (".java",))
    # index ours by simple filename too, to spot moved classes
    o_by_name = defaultdict(list)
    for rel in o_java:
        o_by_name[os.path.basename(rel)].append(rel)
    name_index = defaultdict(list)  # method name -> our files defining it
    for rel, path in o_java.items():
        for name, _, _ in java_methods(read(path)).values():
            name_index[name].append(rel)
    for rel in sorted(c_java):
        if rel not in o_java:
            moved = o_by_name.get(os.path.basename(rel))
            if moved:
                add("java-moved", rel, f"same file name exists at {', '.join(moved)}")
            else:
                n = len(code_lines(read(c_java[rel])))
                add("java-missing", rel, f"missing here ({n} code lines in Cosmic)")
            continue
        o_src, c_src = read(o_java[rel]), read(c_java[rel])
        on, cn = len(code_lines(o_src)), len(code_lines(c_src))
        if cn >= SHORT_MIN_LINES and on < cn * SHORT_RATIO:
            add("java-short", rel, f"{on} code lines here vs {cn} in Cosmic")
        om, cm = java_methods(o_src), java_methods(c_src)
        own_names = {v[0] for v in om.values()} | {"this", "super"}
        for key, (name, cbody, cline) in cm.items():
            if key not in om:
                continue
            _, obody, oline = om[key]
            cstat, ostat = statements(cbody), statements(obody)
            on_body = norm(obody)
            if DELEGATION.match(on_body) and DELEGATION.match(on_body).group(1) in own_names:
                continue  # body just forwards to another method of this class (an overload or a refactor)
            if TRIVIAL_BODY.match(on_body) and cstat >= 1 and not TRIVIAL_BODY.match(norm(cbody)):
                add("java-gutted", rel, f"`{key}` (line {oline}) is `{on_body or '{}'}` here; "
                    f"Cosmic has {cstat} statements (line {cline})", key)
            elif cstat >= METHOD_SHRINK_MIN and ostat < cstat * METHOD_SHRINK_RATIO:
                add("java-shrunk", rel, f"`{key}` (line {oline}) has {ostat} statements here vs "
                    f"{cstat} in Cosmic (line {cline})", key)
        # methods in Cosmic missing from ours (only report if the class otherwise matches)
        missing = [k for k in cm if k not in om and "#" not in k and
                   not any(k2.split("/")[0] == k.split("/")[0] for k2 in om)]
        for k in missing:
            if statements(cm[k][1]) >= 2:
                elsewhere = [f for f in name_index.get(cm[k][0], []) if f != rel]
                hint = f"; a method of that name exists in {', '.join(os.path.basename(f) for f in elsewhere[:3])}" \
                    if elsewhere else ""
                add("java-method-missing", rel, f"`{k}` (Cosmic line {cm[k][2]}, "
                    f"{statements(cm[k][1])} statements) has no counterpart here{hint}", k)
        # commented-out code that is live in Cosmic
        report_commented(add, "java", rel, o_src, c_src)

    # --- scripts
    o_sc = walk(ours, "scripts", (".js",))
    c_sc = walk(cosmic, "scripts", (".js",))
    for rel in sorted(c_sc):
        c_src = read(c_sc[rel])
        cn = len(code_lines(c_src))
        if rel not in o_sc:
            add("script-missing", rel, f"missing here ({cn} code lines in Cosmic)")
            continue
        o_src = read(o_sc[rel])
        on = len(code_lines(o_src))
        if (cn >= 10 and on < cn * SHORT_RATIO) or (cn >= 3 and on == 0):
            add("script-short", rel, f"{on} code lines here vs {cn} in Cosmic")
        of, cf = js_functions(o_src), js_functions(c_src)
        for name, cbody in cf.items():
            if name in of:
                ob = norm(of[name])
                stub = (not ob or ob in ("cm.dispose();", "qm.dispose();", "return;", "return false;",
                                         "return true;", "im.dispose();", "rm.dispose();"))
                cst, ost = statements(cbody), statements(of[name])
                if stub and cst >= 1 and norm(cbody) != ob:
                    add("script-gutted", rel, f"function `{name}` is `{ob or '{}'}` here; Cosmic has "
                        f"{cst} statements", name)
                elif cst >= METHOD_SHRINK_MIN * 2 and ost < cst * METHOD_SHRINK_RATIO:
                    add("script-shrunk", rel, f"function `{name}` has {ost} statements here vs {cst} in "
                        f"Cosmic", name)
            elif statements(cbody) >= 2 and of:
                add("script-func-missing", rel, f"function `{name}` ({statements(cbody)} statements in "
                    f"Cosmic) is not defined here", name)
        if on:
            report_commented(add, "script", rel, o_src, c_src)

    # --- registrations: packet handlers and chat commands Cosmic wires up that we do not
    for rel, pat, label in (("src/main/java/net/PacketProcessor.java", r"registerHandler\(RecvOpcode\.(\w+)", "handler"),
                            ("src/main/java/client/command/CommandsExecutor.java", r"addCommand\((\"[^\"]+\"|new String\[\]\s*\{[^}]*\})", "command")):
        o_reg = set(re.findall(pat, strip_comments_and_strings(read(os.path.join(ours, rel)), keep_strings=True)))
        c_reg = set(re.findall(pat, strip_comments_and_strings(read(os.path.join(cosmic, rel)), keep_strings=True)))
        if label == "command":  # compare individual command names, aliases included
            names = lambda regs: {n for r in regs for n in re.findall(r'"([^"]+)"', r)}
            o_reg, c_reg = names(o_reg), names(c_reg)
        for name in sorted(c_reg - o_reg):
            add(f"{label}-unregistered", rel, f"Cosmic registers {label} `{name}`; we do not", name)

    # --- scripts rewritten wholesale (same file, little shared code)
    for rel in sorted(c_sc):
        if rel not in o_sc:
            continue
        cl, ol = code_lines(read(c_sc[rel])), code_lines(read(o_sc[rel]))
        if len(cl) >= 15 and ol:
            r = difflib.SequenceMatcher(None, cl, ol, autojunk=False).ratio()
            if r < REWRITE_RATIO:
                add("script-rewritten", rel, f"only {r:.0%} of lines shared with Cosmic ({len(ol)} code lines "
                    f"here vs {len(cl)})")

    # --- resources
    o_res = walk(ours, "src/main/resources", None)
    c_res = walk(cosmic, "src/main/resources", None)
    for rel in sorted(c_res):
        if rel not in o_res:
            add("resource-missing", rel, "missing here")

    return findings


CLASS_ORDER = ["RESTORED", "BROKEN", "UNSURE", "DELIBERATE", "UPSTREAM-NEW", "NOISE", "UNCLASSIFIED"]

SECTION_ORDER = ["java-missing", "java-moved", "java-short", "java-gutted", "java-shrunk",
                 "java-method-missing", "java-commented", "java-commented-lines", "script-missing",
                 "script-short", "script-gutted", "script-shrunk", "script-func-missing", "script-commented",
                 "script-commented-lines", "script-rewritten", "handler-unregistered",
                 "command-unregistered", "resource-missing"]


def group_rule(fid, rules):
    """Rules may classify by exact id or by a glob-ish prefix ending in '*'."""
    if fid in rules:
        return rules[fid]
    best = None
    for k, v in rules.items():
        if k.endswith("*") and fid.startswith(k[:-1]) and (best is None or len(k) > len(best[0])):
            best = (k, v)
    return best[1] if best else None


def report(findings, rules, git, cosmic_rev, ours_rev, out):
    counts = defaultdict(int)
    by_kind = defaultdict(list)
    for f in findings:
        r = group_rule(f["id"], rules) or {"class": "UNCLASSIFIED", "why": ""}
        f["class"], f["why"] = r["class"], r.get("why", "")
        f["prov"] = git.provenance(f["file"]) if git else ""
        counts[f["class"]] += 1
        by_kind[f["kind"]].append(f)

    L = []
    L.append("# Cosmic diff — SlumsMS 1.4.1\n")
    L.append(f"Generated by `tools/cosmicdiff/cosmic_diff.py`. Ours: `{ours_rev}`. "
             f"Cosmic: `{cosmic_rev}`. Classifications: `tools/cosmicdiff/classifications.json`.\n")
    L.append("Classes: **DELIBERATE** (SlumsMS/MapleRoot feature or rewrite), **BROKEN** "
             "(player-facing functionality gone), **RESTORED** (was BROKEN, restored on this branch), "
             "**UNSURE** (owner to decide), **UPSTREAM-NEW** (Cosmic added it after MapleRoot forked, about "
             "Aug 2023, so nothing was stripped; optional backport), **NOISE** (detector false positive).\n")
    L.append("Provenance: `repack-shipped` = the file is as the MapleRoot baseline commit `03d13cb21` "
             "brought it; otherwise the later SlumsMS commits touching the file are listed.\n")
    L.append("## Summary\n")
    L.append("| Class | Findings |\n|---|---|")
    for c in CLASS_ORDER:
        if counts.get(c):
            L.append(f"| {c} | {counts[c]} |")
    ids = {f["id"] for f in findings}
    restored = [(k, v) for k, v in rules.items() if v.get("class") == "RESTORED" and k not in ids
                and not k.endswith("*")]
    L.append(f"| **total open findings** | {len(findings)} |")
    L.append(f"| RESTORED (fixed, no longer detected) | {len(restored)} |\n")
    if restored:
        L.append(f"## RESTORED on this branch ({len(restored)})\n")
        L.append("Found by an earlier run and restored from Cosmic; the detector no longer reports them.\n")
        for k, v in restored:
            L.append(f"- `{k.split(':', 1)[1].split('::')[0]}` — {v.get('why', '')}")
            if v.get("qa"):
                L.append(f"  - In-game QA: {v['qa']}")
        L.append("")
    for c in ["RESTORED", "BROKEN", "UNSURE", "UNCLASSIFIED"]:
        sel = [f for f in findings if f["class"] == c]
        if sel:
            L.append(f"## {c}\n")
            for f in sel:
                L.append(f"- `{f['file']}` — {f['detail']}. {f['why']}")
            L.append("")
    L.append("## All findings by kind\n")
    for kind in SECTION_ORDER:
        sel = by_kind.get(kind)
        if not sel:
            continue
        L.append(f"### {kind} ({len(sel)})\n")
        L.append("| Class | File | Detail | Why | Provenance |\n|---|---|---|---|---|")
        for f in sel:
            esc = lambda s: s.replace("|", "\\|")
            L.append(f"| {f['class']} | `{f['file']}` | {esc(f['detail'])} | {esc(f['why'])} | {esc(f['prov'])} |")
        L.append("")
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L) + "\n")
    return counts


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cosmic", required=True)
    ap.add_argument("--ours", default=".")
    ap.add_argument("--baseline", default="03d13cb21", help="MapleRoot baseline commit for provenance")
    ap.add_argument("--rules", default=os.path.join(HERE, "classifications.json"))
    ap.add_argument("--out", default="docs/release/cosmic-diff-1.4.1.md")
    ap.add_argument("--json", help="also dump raw findings as JSON")
    a = ap.parse_args()

    findings = analyse(a.ours, a.cosmic)
    rules = json.load(open(a.rules)) if os.path.exists(a.rules) else {}
    rules = {k: v for k, v in rules.items() if not k.startswith("_")}
    git = Git(a.ours, a.baseline)
    rev = lambda r: subprocess.run(["git", "-C", r, "rev-parse", "--short", "HEAD"], capture_output=True,
                                   text=True).stdout.strip() or "?"
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    counts = report(findings, rules, git, rev(a.cosmic), rev(a.ours), a.out)
    if a.json:
        json.dump(findings, open(a.json, "w"), indent=1)
    print(f"{len(findings)} findings -> {a.out}: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    stale = [k for k in rules if not k.endswith("*") and rules[k].get("class") != "RESTORED"
             and k not in {f['id'] for f in findings}]
    if stale:
        print(f"note: {len(stale)} classification(s) no longer match a finding", file=sys.stderr)


if __name__ == "__main__":
    main()
