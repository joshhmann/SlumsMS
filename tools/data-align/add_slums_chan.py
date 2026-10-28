#!/usr/bin/env python3
"""Add Slums-chan (NPC 9990000, SlumsMS 1.6.1) to the server's WZ XML, with a backup and a dry run.

    python3 tools/data-align/add_slums_chan.py --wz <server wz dir> [--apply]

Two edits, both skipped when already there (a re-run changes nothing):
  1. Npc.wz/9990000.img.xml, a minimal NPC image (the server reads only its name from String.wz, but the file keeps
     the Npc.wz listing complete for tools that walk it); copied from slums-chan/Npc.wz-9990000.img.xml.
  2. String.wz/Npc.img.xml gets the entry `9990000 {name "Slums-chan", func "SlumsMS Guide"}` right after the opening
     <imgdir name="Npc.img"> line (slums-chan/String.wz-Npc-entry.xml). The host file holds its NPC list twice; the
     entry is added once, at the top, which is where a first-match lookup finds it.
With --apply, String.wz/Npc.img.xml is first copied to
<wz>/../wz-backup-<UTC stamp, microseconds>-slums-chan-<pid>/String.wz/Npc.img.xml, a new folder each run (the
tool stops, writing nothing, rather than overwrite an existing backup; the new
Npc.wz file has nothing to back up). The server reads the WZ at startup: restart it afterwards. Without --apply
nothing is written. Rolling back: copy the backed-up file over String.wz/Npc.img.xml and delete Npc.wz/9990000.img.xml.
"""
import argparse
import datetime
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
NPC_ID = '9990000'
OPEN = '<imgdir name="Npc.img">'


def read(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read()


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text)


def plan(wz):
    """The edits still to make: list of (description, path, new_text)."""
    todo = []
    npc_path = os.path.join(wz, 'Npc.wz', NPC_ID + '.img.xml')
    if not os.path.exists(npc_path):
        todo.append(('add Npc.wz/%s.img.xml' % NPC_ID, npc_path,
                     read(os.path.join(HERE, 'slums-chan', 'Npc.wz-9990000.img.xml'))))
    str_path = os.path.join(wz, 'String.wz', 'Npc.img.xml')
    text = read(str_path)
    if '<imgdir name="%s">' % NPC_ID not in text:
        i = text.find(OPEN)
        if i < 0:
            sys.exit('String.wz/Npc.img.xml has no %s line' % OPEN)
        j = text.index('\n', i) + 1
        entry = read(os.path.join(HERE, 'slums-chan', 'String.wz-Npc-entry.xml'))
        todo.append(('add the %s entry to String.wz/Npc.img.xml' % NPC_ID, str_path, text[:j] + entry + text[j:]))
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
        print('Slums-chan is already in %s: nothing to do' % wz)
        return
    backup_root = None
    if args.apply and any(os.path.exists(path) for _, path, _ in todo):
        try:
            backup_root = new_backup_dir(os.path.dirname(wz), 'slums-chan')
        except FileExistsError as e:
            sys.exit('refusing to overwrite an existing backup folder %s: nothing written' % e.filename)
    for what, path, new_text in todo:
        print(('' if args.apply else '[dry run] ') + what)
        if not args.apply:
            continue
        if os.path.exists(path):
            rel = os.path.relpath(path, wz)
            backup = os.path.join(backup_root, rel)
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
