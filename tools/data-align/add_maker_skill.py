#!/usr/bin/env python3
"""Put the Maker skill back into the server's Skill.wz XML (ticket 112, SlumsMS 1.6.2), with a backup and a dry run.

    python3 tools/data-align/add_maker_skill.py --wz <server wz dir> [--apply]

MapleRoot deleted Maker from the beginner skill files; the owner brought it back on 2026-10-30. Four nodes, each
skipped when its id is already in the file (a re-run changes nothing):

  Skill.wz/000.img.xml   0001007   Explorers         } copied byte for byte from the stock Cosmic dump on 164
  Skill.wz/1000.img.xml  10001007  Cygnus Knights    } (/opt/cosmicms/wz/Skill.wz/<file>), see maker-skill/
  Skill.wz/2000.img.xml  20001007  Aran              }
  Skill.wz/2001.img.xml  20011007  Evan              no stock node exists (v83 has no Evan data): Aran's node under
                                                      Evan's id. 2001.img.xml is created when missing (the server
                                                      loads every file in Skill.wz, and only reads its "skill" folder).

Each node goes inside the file's <imgdir name="skill"> folder, before the first skill with a higher id (the stock
order). A file that has one tag per line (the TEST/live dump) gets the node in the same layout; a one-line file gets
it on its line. Every edited file is parsed again before it is written; a file that would not parse is not written.

With --apply, each file that exists is first copied to
<wz>/../wz-backup-<UTC stamp, microseconds>-maker-skill-<pid>/Skill.wz/<file>, a new folder each run (the tool stops,
writing nothing, rather than overwrite an existing backup). The server reads Skill.wz at startup: restart it
afterwards. Without --apply nothing is written. Rolling back: copy the backed-up files over Skill.wz/ and delete
Skill.wz/2001.img.xml if the run created it (it says so).
"""
import argparse
import datetime
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
NODES_DIR = os.path.join(HERE, 'maker-skill')
# (file in Skill.wz, skill id as the file spells it)
NODES = (('000.img.xml', '0001007'), ('1000.img.xml', '10001007'), ('2000.img.xml', '20001007'),
         ('2001.img.xml', '20011007'))
XML_HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
TAG = re.compile(r'<(/?)imgdir\b[^>]*?(/?)>')
NAME = re.compile(r'<imgdir name="([^"]*)"')


def read(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read()


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text)


def node_xml(file_name, skill_id):
    return read(os.path.join(NODES_DIR, '%s-%s.xml' % (file_name[:-len('.xml')], skill_id))).strip()


def one_tag_per_line(text):
    return '>\n<' in text[:2000]


def layout(node, multiline):
    return node.replace('><', '>\n<') if multiline else node


def new_file(file_name, node):
    """A Skill.wz file holding only the node (2001.img.xml: Evan has no stock file)."""
    img = file_name[:-len('.xml')]
    return '\n'.join([XML_HEAD, '<imgdir name="%s">' % img, '<imgdir name="skill">', layout(node, True),
                      '</imgdir>', '</imgdir>', ''])


def has_skill(text, skill_id):
    return ('<imgdir name="%s">' % skill_id) in text


def insert(text, skill_id, node):
    """The file with the node added to its skill folder, in id order. Raises ValueError when there is no skill folder."""
    start = text.find('<imgdir name="skill">')
    if start < 0:
        raise ValueError('no <imgdir name="skill"> folder')
    multiline = one_tag_per_line(text)
    depth = 0
    want = int(skill_id)
    for m in TAG.finditer(text, start):
        closing, empty = m.group(1), m.group(2)
        if closing:
            depth -= 1
            if depth == 0:  # the skill folder's own closing tag: the node goes last
                at = m.start()
                piece = layout(node, multiline) + ('\n' if multiline else '')
                return text[:at] + piece + text[at:]
            continue
        if empty:
            continue
        depth += 1
        if depth == 2:  # a direct child of the skill folder: one skill
            name = NAME.match(m.group(0))
            if name and name.group(1).isdigit() and int(name.group(1)) > want:
                at = m.start()
                piece = layout(node, multiline) + ('\n' if multiline else '')
                return text[:at] + piece + text[at:]
    raise ValueError('the skill folder never closes')


def check(text, skill_id):
    """Parses the edited file and returns how many skill nodes have this id directly under the skill folder."""
    root = ET.fromstring(text.encode('utf-8'))
    skill = root.find("imgdir[@name='skill']")
    if skill is None:
        raise ValueError('no skill folder after the edit')
    return len(skill.findall("imgdir[@name='%s']" % skill_id))


def plan(wz):
    """The edits still to make: list of (description, path, new_text, created)."""
    skill_dir = os.path.join(wz, 'Skill.wz')
    if not os.path.isdir(skill_dir):
        sys.exit('%s has no Skill.wz folder' % wz)
    todo = []
    for file_name, skill_id in NODES:
        path = os.path.join(skill_dir, file_name)
        node = node_xml(file_name, skill_id)
        if not os.path.exists(path):
            text = new_file(file_name, node)
            created = True
        else:
            old = read(path)
            if has_skill(old, skill_id):
                continue
            try:
                text = insert(old, skill_id, node)
            except ValueError as e:
                sys.exit('Skill.wz/%s: %s; nothing written' % (file_name, e))
            created = False
        try:
            n = check(text, skill_id)
        except (ET.ParseError, ValueError) as e:
            sys.exit('Skill.wz/%s would not parse after adding %s (%s); nothing written' % (file_name, skill_id, e))
        if n != 1:
            sys.exit('Skill.wz/%s would hold %d nodes %s; nothing written' % (file_name, n, skill_id))
        what = ('create Skill.wz/%s with %s' % (file_name, skill_id) if created
                else 'add %s to Skill.wz/%s' % (skill_id, file_name))
        todo.append((what, path, text, created))
    return todo


def new_backup_dir(parent, tool, now=None, pid=None):
    """Create and return a fresh backup folder <parent>/wz-backup-<UTC stamp to the microsecond>-<tool>-<pid>
    (exist_ok=False: an earlier backup is never overwritten; same rule as add_slums_chan.py)."""
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
        print('Maker is already in %s/Skill.wz: nothing to do' % wz)
        return
    backup_root = None
    if args.apply and any(not created for _, _, _, created in todo):
        try:
            backup_root = new_backup_dir(os.path.dirname(wz), 'maker-skill')
        except FileExistsError as e:
            sys.exit('refusing to overwrite an existing backup folder %s: nothing written' % e.filename)
    for what, path, new_text, created in todo:
        print(('' if args.apply else '[dry run] ') + what)
        if not args.apply:
            continue
        if not created:
            backup = os.path.join(backup_root, os.path.relpath(path, wz))
            if os.path.exists(backup):
                sys.exit('refusing to overwrite the backup ' + backup)
            os.makedirs(os.path.dirname(backup), exist_ok=True)
            shutil.copy2(path, backup)
            print('  backup: ' + backup)
        else:
            print('  (new file: delete it to roll back)')
        write(path, new_text)
    if not args.apply:
        print('Nothing written; add --apply.')


if __name__ == '__main__':
    main()
