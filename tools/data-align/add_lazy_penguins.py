#!/usr/bin/env python3
"""Add the 15 Lazy Penguins (NPCs 2985-2999, SlumsMS 1.6.1) to the server's WZ XML, with a backup and a dry run.

    python3 tools/data-align/add_lazy_penguins.py --wz <server wz dir> [--apply]

Same shape as add_slums_chan.py. Data: lazy-penguins/penguins.tsv (id, variant, frame size and origin, placement).
Two kinds of edit, each skipped when already there (a re-run changes nothing):
  1. Npc.wz/<id>.img.xml, a minimal NPC image (info + stand/0 with the frame's size and origin); the server reads
     only the name from String.wz, the file keeps the Npc.wz listing complete for tools that walk it.
  2. String.wz/Npc.img.xml gets `<id> {name "Lazy Penguin", func "<variant>"}` entries right after the opening
     <imgdir name="Npc.img"> line (the host file holds its NPC list twice; first match wins, so the top it is).
With --apply, String.wz/Npc.img.xml is first copied to
<wz>/../wz-backup-<UTC stamp, microseconds>-lazy-penguins-<pid>/String.wz/Npc.img.xml, a new folder each run (the
tool stops, writing nothing, rather than overwrite an existing backup). The server
reads the WZ at startup: restart it afterwards. Rolling back: copy the backup over String.wz/Npc.img.xml and delete
the 15 Npc.wz/29xx.img.xml files.
"""
import argparse
import datetime
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OPEN = '<imgdir name="Npc.img">'
NAME = 'Lazy Penguin'


def penguins():
    out = []
    with open(os.path.join(HERE, 'lazy-penguins', 'penguins.tsv'), encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            c = line.rstrip('\n').split('\t')
            out.append({'id': c[0], 'variant': c[1], 'w': c[2], 'h': c[3], 'ox': c[4], 'oy': c[5]})
    return out


def npc_xml(p):
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><imgdir name="%(id)s.img"><imgdir name="info">'
            '<canvas name="default" width="%(w)s" height="%(h)s"><vector name="origin" x="%(ox)s" y="%(oy)s"/>'
            '<int name="z" value="0"/></canvas></imgdir><imgdir name="stand"><canvas name="0" width="%(w)s" '
            'height="%(h)s"><vector name="origin" x="%(ox)s" y="%(oy)s"/><int name="delay" value="1000"/></canvas>'
            '</imgdir></imgdir>' % p)


def string_entry(p):
    return '<imgdir name="%s">\n<string name="name" value="%s"/>\n<string name="func" value="%s"/>\n</imgdir>\n' % (
        p['id'], NAME, p['variant'])


def read(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read()


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text)


def plan(wz):
    """The edits still to make: list of (description, path, new_text)."""
    todo = []
    for p in penguins():
        path = os.path.join(wz, 'Npc.wz', p['id'] + '.img.xml')
        if not os.path.exists(path):
            todo.append(('add Npc.wz/%s.img.xml' % p['id'], path, npc_xml(p)))
    str_path = os.path.join(wz, 'String.wz', 'Npc.img.xml')
    text = read(str_path)
    entries = ''.join(string_entry(p) for p in penguins() if '<imgdir name="%s">' % p['id'] not in text)
    if entries:
        i = text.find(OPEN)
        if i < 0:
            sys.exit('String.wz/Npc.img.xml has no %s line' % OPEN)
        j = text.index('\n', i) + 1
        todo.append(('add the missing Lazy Penguin entries to String.wz/Npc.img.xml', str_path, text[:j] + entries + text[j:]))
    return todo


def new_backup_dir(parent, tool, now=None, pid=None):
    """Create and return a fresh backup folder <parent>/wz-backup-<UTC stamp to the microsecond>-<tool>-<pid>.

    The name is unique per run (two tools run in the same second, or the same tool twice, get different folders),
    and the folder is created with exist_ok=False: if it is somehow there already, this raises FileExistsError and
    the caller stops before writing anything, so an earlier backup is never overwritten."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    name = 'wz-backup-%s-%s-%d' % (now.strftime('%Y%m%dT%H%M%S.%fZ'), tool, os.getpid() if pid is None else pid)
    path = os.path.join(parent, name)
    os.makedirs(path, exist_ok=False)
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--wz', required=True)
    ap.add_argument('--apply', action='store_true')
    args = ap.parse_args()
    wz = os.path.abspath(args.wz)
    todo = plan(wz)
    if not todo:
        print('The Lazy Penguins are already in %s: nothing to do' % wz)
        return
    backup_root = None
    if args.apply and any(os.path.exists(path) for _, path, _ in todo):
        try:
            backup_root = new_backup_dir(os.path.dirname(wz), 'lazy-penguins')
        except FileExistsError as e:
            sys.exit('refusing to overwrite an existing backup folder %s: nothing written' % e.filename)
    for what, path, new_text in todo:
        print(('' if args.apply else '[dry run] ') + what)
        if not args.apply:
            continue
        if os.path.exists(path):
            backup = os.path.join(backup_root, os.path.relpath(path, wz))
            if os.path.exists(backup):
                sys.exit('refusing to overwrite the backup ' + backup)
            os.makedirs(os.path.dirname(backup), exist_ok=True)
            shutil.copy2(path, backup)
            print('  backup: ' + backup)
        write(path, new_text)
    if not args.apply:
        print('Nothing written; add --apply.')


if __name__ == '__main__':
    main()
