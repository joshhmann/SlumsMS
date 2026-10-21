#!/usr/bin/env python3
"""Set leaf values in the server's WZ XML from a TSV, in place, with a backup and a dry run.

    python3 tools/data-align/wz_set_values.py --wz <server wz dir> --tsv <file> --column client_value [--apply]

The TSV has a header row (lines starting with '#' are comments) and at least the columns
`file` (relative to --wz, e.g. Mob.wz/8211000.img.xml), `node` (path inside the image, e.g.
info/PADamage), `server_value` and the column named by --column. Each row is only applied when
the file still holds `server_value`, so a re-run, or a WZ that has moved on, changes nothing it
should not. Only the value attribute of the one matching element is rewritten; the rest of the
file is byte-identical. Without --apply nothing is written.

With --apply, every file it changes is first copied to <wz>/../wz-backup-<UTC stamp>/<file>.
The server reads the WZ at startup, so restart it afterwards.
"""
import argparse
import csv
import datetime
import os
import re
import shutil
import sys

TAG = re.compile(r'<(/?)(\w+)\b([^>]*?)(/?)>')
NAME = re.compile(r'\bname="([^"]*)"')
VALUE = re.compile(r'\bvalue="([^"]*)"')


def find_leaf(text, node):
    """Span of the value attribute of the element at `node` (a/b/c), or None."""
    want = node.split('/')
    stack = []  # names of the open elements; stack[0] is the image's own <imgdir>
    for m in TAG.finditer(text):
        closing, attrs, selfclose = m.group(1), m.group(3), m.group(4)
        if closing:
            if stack:
                stack.pop()
            continue
        nm = NAME.search(attrs)
        path = stack[1:] + [nm.group(1) if nm else '']
        if stack and path == want:
            v = VALUE.search(attrs)
            if not v:
                return None
            return m.start(3) + v.start(1), m.start(3) + v.end(1)
        if not selfclose:
            stack.append(path[-1])
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--wz', required=True)
    ap.add_argument('--tsv', required=True)
    ap.add_argument('--column', required=True, help='TSV column holding the new value')
    ap.add_argument('--apply', action='store_true')
    a = ap.parse_args()

    with open(a.tsv, encoding='utf-8') as f:
        rows = list(csv.DictReader((l for l in f if not l.startswith('#')), delimiter='\t'))
    by_file = {}
    for r in rows:
        by_file.setdefault(r['file'], []).append(r)

    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup = os.path.join(os.path.dirname(os.path.abspath(a.wz)), f'wz-backup-{stamp}')
    changed = skipped = files = 0
    for rel, rs in sorted(by_file.items()):
        path = os.path.join(a.wz, rel)
        if not os.path.exists(path):
            print(f'skip {rel}: missing', file=sys.stderr)
            skipped += len(rs)
            continue
        with open(path, encoding='utf-8', newline='') as f:
            text = f.read()
        edits = []
        for r in rs:
            span = find_leaf(text, r['node'])
            if span is None:
                print(f'skip {rel} {r["node"]}: node not found', file=sys.stderr)
                skipped += 1
                continue
            cur = text[span[0]:span[1]]
            if cur == r[a.column]:
                continue
            if cur != r['server_value']:
                print(f'skip {rel} {r["node"]}: holds {cur}, expected {r["server_value"]}', file=sys.stderr)
                skipped += 1
                continue
            edits.append((span, r[a.column]))
        if not edits:
            continue
        for (lo, hi), v in sorted(edits, reverse=True):
            text = text[:lo] + v + text[hi:]
        changed += len(edits)
        files += 1
        if a.apply:
            dst = os.path.join(backup, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(path, dst)
            with open(path, 'w', encoding='utf-8', newline='') as f:
                f.write(text)
    mode = 'applied' if a.apply else 'dry run'
    print(f'{mode}: {changed} value(s) in {files} file(s), {skipped} skipped'
          + (f'; backup in {backup}' if a.apply and files else ''))
    return 1 if skipped else 0


if __name__ == '__main__':
    sys.exit(main())
