"""Validator for a data/atlas/<release>/ folder (docs/atlas/SCHEMA.md): meta.json, the nine kind files and the
optional dangling.json allow-list. Walks every row generically, so a field added by a later builder is checked too.
Prints problems grouped by file. "--release-diff OLD NEW" compares two releases and flags a major schema change.
"""
import argparse
import json
import os
import re
import sys

KINDS = ("quests", "npcs", "items", "mobs", "maps", "scripts", "pq", "bosses")
NUMERIC = {"quests", "npcs", "items", "mobs", "maps"}
# ref kind -> the kind file it must resolve in (skill has no file in v1)
SHIPPED = {"item": "items", "mob": "mobs", "map": "maps", "npc": "npcs", "quest": "quests",
           "script": "scripts", "pq": "pq", "boss": "bosses"}
REF_KINDS = set(SHIPPED) | {"skill"}
REACH = ("player", "gm", "event", "unobtainable")
SRC_KINDS = ("wz", "script", "yaml", "java", "doc", "db-export")
META_STRINGS = ("release", "server_commit", "wz_sha", "built_at")
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
HANGUL = re.compile(r"[가-힯ᄀ-ᇿ㄰-㆏]")
# client text codes: colours/bold (#b #k #r #d #g #e #n #c), choices (#L0# ... #l), lookups (#p2100#, #t..., #B0#)
CODE = re.compile(r"#(?:L\d+#|B\d+#|[blkrdgenc]|[ptzivomh]\d+#)")
FORBIDDEN = ("password", "passwd", "secret", "token", "hash", "ip", "account_name")
TEXT_KEYS = ("name", "desc", "dialogue", "text")


def _p(file, ident, field, message, warning=False):
    p = {"file": file, "id": ident, "field": field, "message": message}
    if warning:
        p["warning"] = True
    return p


def _key_id(ident):
    """ids for sorting: numbers first, then names like "quest/1021" and "zakumpq"."""
    s = str(ident)
    return (0, int(s), "") if s.lstrip("-").isdigit() else (1, 0, s)


def _norm(kind, ident):
    """An id as the key it must match in kind's file."""
    if kind != "script":
        if isinstance(ident, int) and not isinstance(ident, bool):
            return str(ident)
        if isinstance(ident, str) and ident.strip().lstrip("-").isdigit():
            return str(int(ident))
    return str(ident)


def _load_json(path, problems, label):
    """The parsed file, or None after recording why it could not be read."""
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        problems.append(_p(label, None, None, "file is missing"))
    except (OSError, ValueError) as e:
        problems.append(_p(label, None, None, f"file is not readable JSON: {e}"))
    return None


def _meta_problems(meta, counts, problems):
    if not isinstance(meta, dict):
        problems.append(_p("meta.json", None, None, "meta.json is not a JSON object"))
        return
    v = meta.get("schema_version")
    if v is None:
        problems.append(_p("meta.json", None, "schema_version", "missing"))
    elif not isinstance(v, str) or not SEMVER.match(v):
        problems.append(_p("meta.json", None, "schema_version", f"{v!r} is not a semver string"))
    for field in META_STRINGS:
        if field not in meta:
            problems.append(_p("meta.json", None, field, "missing"))
        elif not isinstance(meta[field], str) or not meta[field].strip():
            problems.append(_p("meta.json", None, field, f"{meta[field]!r} is not a non-empty string"))
    mc = meta.get("counts")
    if not isinstance(mc, dict):
        problems.append(_p("meta.json", None, "counts", "missing or not an object"))
        return
    for kind in KINDS:
        if kind not in mc:
            problems.append(_p("meta.json", None, f"counts.{kind}", "missing count"))
        elif mc[kind] != counts.get(kind):
            problems.append(_p("meta.json", None, f"counts.{kind}",
                               f"meta says {mc[kind]!r} but {kind}.json has {counts.get(kind)!r} rows"))
    for kind in mc:
        if kind not in KINDS:
            problems.append(_p("meta.json", None, f"counts.{kind}", "unknown kind in counts"))


def _walk_strings(node, path=""):
    """(path, string) for every string in node."""
    if isinstance(node, dict):
        for k, v in node.items():
            yield from _walk_strings(v, f"{path}.{k}" if path else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _walk_strings(v, f"{path}[{i}]")
    elif isinstance(node, str):
        yield path, node


def _walk_dicts(node, path=""):
    """(path, dict) for every dict in node."""
    if isinstance(node, dict):
        yield path, node
        for k, v in node.items():
            yield from _walk_dicts(v, f"{path}.{k}" if path else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _walk_dicts(v, f"{path}[{i}]")


def _walk_refs(node, path=""):
    """(path, dict) for every {k, id} shaped object in node."""
    if isinstance(node, dict):
        if "k" in node:
            yield path, node
            return
        for k, v in node.items():
            yield from _walk_refs(v, f"{path}.{k}" if path else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _walk_refs(v, f"{path}[{i}]")


def _is_text(path):
    """True when the last key on path names a text field (or sits under "text"/"dialogue")."""
    parts = path.replace("[", ".").split(".")
    return any(p in TEXT_KEYS for p in parts)


def _src_problems(fname, rid, src, problems):
    if not isinstance(src, list) or not src:
        problems.append(_p(fname, rid, "src", "must be a non-empty list"))
        return
    for i, s in enumerate(src):
        field = f"src[{i}]"
        if not isinstance(s, dict):
            problems.append(_p(fname, rid, field, "source is not an object"))
            continue
        kind = s.get("kind")
        if kind not in SRC_KINDS:
            problems.append(_p(fname, rid, f"{field}.kind", f"{kind!r} is not one of {', '.join(SRC_KINDS)}"))
            continue
        if kind == "db-export":
            for k in ("snapshot", "date", "table", "key"):
                if k not in s or s[k] in (None, ""):
                    problems.append(_p(fname, rid, f"{field}.{k}", "db-export source needs this"))
            continue
        path = s.get("path")
        if not isinstance(path, str) or not path.strip():
            problems.append(_p(fname, rid, f"{field}.path", "missing or not a non-empty string"))
        if "line" not in s:
            problems.append(_p(fname, rid, f"{field}.line", "missing (use null when it cannot be placed)"))
        else:
            line = s["line"]
            if line is not None and (isinstance(line, bool) or not isinstance(line, int) or line < 1):
                problems.append(_p(fname, rid, f"{field}.line", f"{line!r} is not a positive integer or null"))


def _forbidden_problems(fname, ident, node, problems):
    """No player data or secrets under any key name, at any depth."""
    for dpath, d in _walk_dicts(node):
        for k in d:
            if str(k).lower() in FORBIDDEN:
                problems.append(_p(fname, ident, f"{dpath}.{k}" if dpath else str(k),
                                   f"forbidden key {k!r}: no player data or secrets in the atlas"))


def _row_problems(fname, kind, key, row, problems):
    if not isinstance(row, dict):
        problems.append(_p(fname, key, None, "row is not an object"))
        return
    if kind in NUMERIC:
        if not isinstance(key, str) or not key.isdigit():
            problems.append(_p(fname, key, "id", "key is not a numeric id"))
        elif row.get("id") != int(key):
            problems.append(_p(fname, key, "id", f"row id {row.get('id')!r} is not the key {int(key)}"))
    elif row.get("id") != key:
        problems.append(_p(fname, key, "id", f"row id {row.get('id')!r} is not the key {key!r}"))
    if not isinstance(row.get("name"), str):
        problems.append(_p(fname, key, "name", f"{row.get('name')!r} is not a string"))
    if row.get("reach") not in REACH:
        problems.append(_p(fname, key, "reach", f"{row.get('reach')!r} is not one of {', '.join(REACH)}"))
    _src_problems(fname, key, row.get("src"), problems)
    _forbidden_problems(fname, key, row, problems)
    if row.get("lang") != "ko":
        for spath, text in _walk_strings(row):
            if HANGUL.search(text):
                problems.append(_p(fname, key, spath, "Hangul text without lang \"ko\" on the row"))
    for spath, text in _walk_strings(row):
        if _is_text(spath) and CODE.search(text):
            problems.append(_p(fname, key, spath, f"client formatting code in text: {text!r}"))


def validate(folder):
    """-> list of problems, each {file, id, field, message}. Dangling refs the folder allows carry warning=True."""
    problems = []
    meta = _load_json(os.path.join(folder, "meta.json"), problems, "meta.json")
    docs = {}
    counts = {}
    for kind in KINDS:
        fname = kind + ".json"
        rows = _load_json(os.path.join(folder, fname), problems, fname)
        if rows is not None and not isinstance(rows, dict):
            problems.append(_p(fname, None, None, "kind file is not a JSON object"))
            rows = None
        docs[kind] = rows
        counts[kind] = len(rows) if rows is not None else None
        if rows is None:
            continue
        keys = list(rows)
        if keys != sorted(keys):
            first = next((keys[i] for i in range(1, len(keys)) if keys[i] < keys[i - 1]), None)
            problems.append(_p(fname, first, None, "ids are not sorted"))
        for key in keys:
            if not isinstance(key, str):
                problems.append(_p(fname, key, "id", "key is not a string"))
                continue
            _row_problems(fname, kind, key, rows[key], problems)
    if meta is not None:
        _meta_problems(meta, counts, problems)
        _forbidden_problems("meta.json", None, meta, problems)

    allow = {}
    dpath = os.path.join(folder, "dangling.json")
    if os.path.exists(dpath):
        dangling = _load_json(dpath, problems, "dangling.json")
        if isinstance(dangling, dict):
            _forbidden_problems("dangling.json", None, dangling, problems)
            for kind, ids in dangling.items():
                if isinstance(ids, list):
                    allow[kind] = {_norm(kind, i) for i in ids}

    index = {kind: set(rows or {}) for kind, rows in docs.items()}
    warnings_seen = set()
    for kind in KINDS:
        rows = docs[kind]
        if not rows:
            continue
        for key in rows:
            if not isinstance(rows[key], dict):
                continue
            for path, ref in _walk_refs(rows[key]):
                rk = ref.get("k")
                field = path if path else "ref"
                if rk not in REF_KINDS:
                    problems.append(_p(kind + ".json", key, f"{field}.k", f"unknown ref kind {rk!r}"))
                    continue
                if "id" not in ref:
                    problems.append(_p(kind + ".json", key, f"{field}.id", "ref has no id"))
                    continue
                ident = ref["id"]
                if rk == "script":
                    if not isinstance(ident, str) or not ident.strip():
                        problems.append(_p(kind + ".json", key, f"{field}.id",
                                           f"script ref id {ident!r} is not a non-empty string"))
                        continue
                elif isinstance(ident, bool) or not isinstance(ident, int):
                    problems.append(_p(kind + ".json", key, f"{field}.id",
                                       f"{rk} ref id {ident!r} is not a number"))
                    continue
                if rk not in SHIPPED:
                    continue
                target = SHIPPED[rk]
                if _norm(rk, ident) in index[target]:
                    continue
                if _norm(rk, ident) in allow.get(rk, set()):
                    warnings_seen.add((rk, _norm(rk, ident)))
                    problems.append(_p(kind + ".json", key, field,
                                       f"dangling {rk} {ident} (allowed by dangling.json)", warning=True))
                else:
                    problems.append(_p(kind + ".json", key, field,
                                       f"{rk} {ident} does not resolve in {target}.json"))
    del warnings_seen
    return problems


def _canon(row):
    return json.dumps(row, sort_keys=True, ensure_ascii=False)


def _common_fields(rows):
    sets = [set(r) for r in rows.values() if isinstance(r, dict)]
    return set.intersection(*sets) if sets else set()


def release_diff(old, new):
    """-> lines of text: ids added/removed/changed per kind (counts, first 20) and any major schema change."""
    lines = []
    ometa = _load_json(os.path.join(old, "meta.json"), [], "meta.json") or {}
    nmeta = _load_json(os.path.join(new, "meta.json"), [], "meta.json") or {}
    for kind in KINDS:
        o = _load_json(os.path.join(old, kind + ".json"), [], kind + ".json")
        n = _load_json(os.path.join(new, kind + ".json"), [], kind + ".json")
        if not isinstance(o, dict) or not isinstance(n, dict):
            lines.append(f"{kind}: file missing or unreadable in one release")
            continue
        added = sorted(set(n) - set(o), key=_key_id)
        removed = sorted(set(o) - set(n), key=_key_id)
        changed = sorted((k for k in set(o) & set(n) if _canon(o[k]) != _canon(n[k])), key=_key_id)
        lines.append(f"{kind}: {len(added)} added, {len(removed)} removed, {len(changed)} changed "
                     f"({len(o)} -> {len(n)} rows)")
        for label, ids in (("added", added), ("removed", removed), ("changed", changed)):
            if ids:
                more = f" (+{len(ids) - 20} more)" if len(ids) > 20 else ""
                lines.append(f"  {label}: {', '.join(ids[:20])}{more}")
        dropped = _common_fields(o) - _common_fields(n)
        if dropped:
            lines.append(f"  MAJOR schema change: every {kind} row had field(s) "
                         f"{', '.join(sorted(dropped))} before and does not now")
    ov, nv = str(ometa.get("schema_version", "")), str(nmeta.get("schema_version", ""))
    if ov and nv and ov.split(".")[0] != nv.split(".")[0]:
        lines.append(f"MAJOR schema change: schema_version {ov} -> {nv}")
    return lines


def main(argv=None):
    ap = argparse.ArgumentParser(description="Validate a data/atlas/<release>/ folder, or diff two releases.")
    ap.add_argument("folder", nargs="?", help="data/atlas/<release> folder to validate")
    ap.add_argument("--release-diff", nargs=2, metavar=("OLD", "NEW"), help="compare two release folders")
    a = ap.parse_args(argv)
    if a.release_diff:
        for line in release_diff(*a.release_diff):
            print(line)
        return 0
    if not a.folder:
        ap.error("give a folder, or --release-diff OLD NEW")
    problems = validate(a.folder)
    hard = [p for p in problems if not p.get("warning")]
    warn = [p for p in problems if p.get("warning")]
    for p in sorted(hard, key=lambda p: (p["file"], _key_id(p["id"]), p["field"] or "")):
        print(f"{p['file']}: {p['id']}  {p['field'] or '-'}  {p['message']}")
    if warn:
        print(f"{len(warn)} dangling reference warning(s) allowed by dangling.json:")
        for p in sorted(warn, key=lambda p: (p["file"], _key_id(p["id"]), p["field"] or "")):
            print(f"  {p['file']}: {p['id']}  {p['field'] or '-'}  {p['message']}")
    if hard:
        print(f"{len(hard)} problem(s) in {a.folder}")
        return 1
    print(f"{a.folder}: ok" + (f" ({len(warn)} dangling warning(s))" if warn else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
