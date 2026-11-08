"""Index the server's NPC/quest/portal/reactor/event/item/map JS scripts for the content atlas.

`scripts.json` (docs/atlas/SCHEMA.md, kind "scripts") is one row per .js file under scripts/{npc,quest,portal,
reactor,event,item,map}: the header comment's meaning lines, every interesting call with its literal arguments, and
the ids those calls fix (item/quest/map/mob/skill/shop). Read-only: the repo is the only input.

python3 -m unittest discover -s tools/atlas -p 'test_*.py'
python3 tools/atlas/scripts_index.py --out data/atlas/1.5.0/scripts.json
"""
import argparse
import json
import os
import re

KINDS = ("npc", "quest", "portal", "reactor", "event", "item", "map")

# Interesting calls. The value says which ids the call fixes: (ref kind, argument positions holding that id), or None
# when the call carries no id (amounts, flags, manager lookups). Positions are 0-based, in argument order.
CALLS = {
    "gainItem": ("item", (0,)),
    "haveItem": ("item", (0,)),
    "hasItem": ("item", (0,)),
    "removeAll": ("item", (0,)),
    "canHold": ("item", (0,)),
    # ReactorActionManager.dropItems() and its overloads take meso/x/y flags and ranges, never an item id.
    "dropItems": None,
    "forceStartQuest": ("quest", (0,)),
    "forceCompleteQuest": ("quest", (0,)),
    "startQuest": ("quest", (0,)),
    "completeQuest": ("quest", (0,)),
    "isQuestStarted": ("quest", (0,)),
    "isQuestCompleted": ("quest", (0,)),
    "getQuestStatus": ("quest", (0,)),
    "warp": ("map", (0,)),
    "warpParty": ("map", (0,)),
    "spawnMonster": ("mob", (0,)),
    # event (PQ / boss) instance scripts: the em / eim / map objects
    "getInstanceMap": ("map", (0,)),
    "getMapInstance": ("map", (0,)),
    "warpEventTeam": ("map", (0,)),
    "spawnNpc": ("npc", (0,)),
    "getMonster": ("mob", (0,)),
    "getMonsterById": ("mob", (0,)),
    "spawnMonsterOnGroundBelow": None,  # its mob id is the argument of the nested getMonster call
    "teachSkill": ("skill", (0,)),
    "openShop": ("shop", (0,)),
    "gainExp": None,
    "gainMeso": None,
    "gainFame": None,
    "changeJobById": None,
    "setJob": None,
    "getEventManager": None,
    "startInstance": None,
    "getMap": None,
}

MAX_ARGS = 200  # characters of argument source text kept per call

# "NPC Name: Roger" / "Map(s): Maple Road" / "Description: Quest - Roger's Apple". A key is short and starts a line;
# the AGPL/HeavenMS licence preamble has no such line, so "keep only matching lines" is enough to drop it.
HEADER_LINE = re.compile(r"([A-Za-z][A-Za-z0-9 ()/._-]{0,24}):[ \t]+(\S.*)$")
HEADER_NOISE = {"author", "authors", "edited by", "copyright", "copyleft",
                "todo", "fixme", "note", "notes", "warning"}  # credits and work notes, not what the script is

IDENT = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
NUMBER = re.compile(r"\d+(?:\.\d+)?(?:[eE][+-]?\d+)?")
# After these a '/' starts a regular expression; after an identifier, number or closing bracket it divides.
REGEX_AFTER_WORD = {"return", "typeof", "case", "in", "of", "new", "delete", "void", "throw", "else", "do"}
REGEX_AFTER_PUNCT = set("(,=:[!&|?{};+-*%<>~^")
INT_LITERAL = re.compile(r"\d+")


def _skip_string(src, i, quote):
    """Return the index just past the string literal that opens at src[i] (unterminated: stop at its newline/end)."""
    j = i + 1
    while j < len(src):
        ch = src[j]
        if ch == "\\":
            j += 2
            continue
        if ch == quote:
            return j + 1
        if ch == "\n" and quote != "`":
            return j
        j += 1
    return j


def _skip_regex(src, i):
    """Return the index just past the /regex/flags literal that opens at src[i]."""
    j = i + 1
    in_class = False
    while j < len(src):
        ch = src[j]
        if ch == "\\":
            j += 2
            continue
        if ch == "\n":
            return j  # not a regex after all; don't run past the line
        if ch == "[":
            in_class = True
        elif ch == "]":
            in_class = False
        elif ch == "/" and not in_class:
            j += 1
            while j < len(src) and (src[j].isalpha()):
                j += 1
            return j
        j += 1
    return j


def scan(src):
    """Return (tokens, comments): tokens are ("ident"|"number"|"punct", text, offset) for code only; comments are
    (offset, body) for every /* */ block comment. Strings, template literals, regex literals and // comments are
    stepped over, so a call written inside one is never seen as code."""
    tokens, comments = [], []
    i, n, prev = 0, len(src), None
    while i < n:
        ch = src[i]
        if ch in " \t\r\n\f\v\ufeff":
            i += 1
            continue
        if src.startswith("//", i):
            nl = src.find("\n", i)
            i = n if nl < 0 else nl
            continue
        if src.startswith("/*", i):
            end = src.find("*/", i + 2)
            end = n if end < 0 else end
            comments.append((i, src[i + 2:end]))
            i = min(n, end + 2)
            continue
        if ch in "\"'`":
            i = _skip_string(src, i, ch)
            prev = ("string", "", i)
            continue
        if ch == "/" and _regex_allowed(prev):
            i = _skip_regex(src, i)
            prev = ("regex", "", i)
            continue
        m = IDENT.match(src, i)
        if m:
            tokens.append(("ident", m.group(), i))
            prev = tokens[-1]
            i = m.end()
            continue
        m = NUMBER.match(src, i)
        if m:
            tokens.append(("number", m.group(), i))
            prev = tokens[-1]
            i = m.end()
            continue
        tokens.append(("punct", ch, i))
        prev = tokens[-1]
        i += 1
    return tokens, comments


def _regex_allowed(prev):
    if prev is None:
        return True
    kind, text = prev[0], prev[1]
    if kind == "ident":
        return text in REGEX_AFTER_WORD
    if kind == "punct":
        return text in REGEX_AFTER_PUNCT
    return False


def header_of(src, tokens, comments):
    """The meaning lines of the first leading block comment that has any: label lines such as "NPC Name: Roger",
    "Map(s):", "Description:" with their leading '*' and whitespace trimmed. Licence preambles and credits drop out."""
    first_code = tokens[0][2] if tokens else len(src)
    for offset, body in comments:
        if offset >= first_code:
            break
        lines = []
        for raw in body.splitlines():
            line = raw.strip()
            if line.startswith("*"):
                line = line[1:].strip()
            m = HEADER_LINE.match(line)
            if not m or m.group(1).strip().lower() in HEADER_NOISE:
                continue
            lines.append("%s: %s" % (m.group(1).strip(), m.group(2).strip()))
        if lines:
            return lines
    return []


def _collapse(text):
    return re.sub(r"\s+", " ", text).strip()[:MAX_ARGS]


def find_calls(tokens, src):
    """Every call to a CALLS function as {fn, args, line}, in file order. `args` is the source text inside the
    parentheses with whitespace collapsed; `line` is 1-based and points at the function name."""
    out = []
    for idx, (kind, name, pos) in enumerate(tokens):
        if kind != "ident" or name not in CALLS:
            continue
        if idx and tokens[idx - 1][0] == "ident" and tokens[idx - 1][1] == "function":
            continue  # a declaration, not a call
        if idx + 1 >= len(tokens) or tokens[idx + 1][1] != "(":
            continue  # a property key or something else
        close = _match_paren(tokens, idx + 1)
        if close is None:
            continue
        line = src.count("\n", 0, pos) + 1
        inner = src[tokens[idx + 1][2] + 1:tokens[close][2]]
        out.append({"fn": name, "args": _collapse(inner), "line": line,
                    "_args": _split_args(tokens, idx + 1, close, src),
                    "_line": line})
    return out


def _match_paren(tokens, open_idx):
    """Index of the token closing the '(' at open_idx, or None."""
    depth = 0
    for k in range(open_idx, len(tokens)):
        kind, text = tokens[k][0], tokens[k][1]
        if kind != "punct":
            continue
        if text == "(":
            depth += 1
        elif text == ")":
            depth -= 1
            if depth == 0:
                return k
    return None


def _split_args(tokens, open_idx, close_idx, src):
    """Source text of each top-level argument between the parentheses at open_idx and close_idx."""
    parts, start, depth = [], open_idx + 1, 0
    for k in range(open_idx + 1, close_idx + 1):
        if tokens[k][0] != "punct":
            continue
        text = tokens[k][1]
        if text in "([{":
            depth += 1
        elif text in ")]}":
            depth -= 1
        elif text == "," and depth == 0:
            parts.append(src[tokens[start][2]:tokens[k][2]])
            start = k + 1
    parts.append(src[tokens[start][2]:tokens[close_idx][2]])
    return parts


def mentions_of(calls):
    """Ids fixed by the calls: a plain integer literal in an argument position the call's table entry names.
    Variables and expressions are skipped, never guessed."""
    out = []
    for call in calls:
        rule = CALLS.get(call["fn"])
        if not rule:
            continue
        ref_kind, positions = rule
        args = call["_args"]
        for pos in positions:
            if pos >= len(args):
                continue
            text = args[pos].strip()
            if not INT_LITERAL.fullmatch(text):
                continue
            out.append({"k": ref_kind, "id": int(text), "line": call["_line"]})
    return out


def index_script(rel_path, kind, src):
    """One scripts.json row for a script's source text; `rel_path` is repo-relative, e.g. scripts/quest/1021.js."""
    tokens, comments = scan(src)
    calls = find_calls(tokens, src)
    mentions = mentions_of(calls)
    for call in calls:  # drop the parse-only keys
        del call["_args"], call["_line"]
    return {
        "id": rel_path[len("scripts/"):-len(".js")],
        "path": rel_path,
        "kind": kind,
        "lines": len(src.splitlines()),
        "header": header_of(src, tokens, comments),
        "calls": calls,
        "mentions": mentions,
        "src": [{"kind": "script", "path": rel_path, "line": 1}],
    }


def index_scripts(repo_root):
    """{id: row} for every .js under scripts/{npc,quest,portal,reactor,event,item,map}, Base.js files skipped."""
    scripts_root = os.path.join(repo_root, "scripts")
    rows = {}
    for kind in KINDS:
        base = os.path.join(scripts_root, kind)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames.sort()
            for name in sorted(filenames):
                if not name.endswith(".js") or name.endswith("Base.js"):
                    continue
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, repo_root).replace(os.sep, "/")
                with open(full, "rb") as f:
                    src = f.read().decode("utf-8", "replace").lstrip("\ufeff")
                rows[rel[len("scripts/"):-len(".js")]] = index_script(rel, kind, src)
    return {key: rows[key] for key in sorted(rows)}


def write_index(repo_root, out_path):
    """Build the index and write it as scripts.json (UTF-8, sorted keys, 1-space indent). Returns the index."""
    index = index_scripts(repo_root)
    folder = os.path.dirname(os.path.abspath(out_path))
    if folder:
        os.makedirs(folder, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")
    return index


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")),
                        help="server checkout to index (default: this one)")
    parser.add_argument("--out", required=True, help="where to write scripts.json")
    args = parser.parse_args(argv)
    index = write_index(args.repo, args.out)
    per_kind, per_fn = {}, {}
    for row in index.values():
        per_kind[row["kind"]] = per_kind.get(row["kind"], 0) + 1
        for call in row["calls"]:
            per_fn[call["fn"]] = per_fn.get(call["fn"], 0) + 1
    print("%d scripts -> %s" % (len(index), args.out))
    for kind in KINDS:
        if kind in per_kind:
            print("  %-8s %d" % (kind, per_kind[kind]))
    print("calls:")
    for fn in sorted(per_fn):
        print("  %-18s %d" % (fn, per_fn[fn]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
