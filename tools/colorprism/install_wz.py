#!/usr/bin/env python3
"""Add the Coloring Prism item (5782000) to a server WZ XML directory. Dry run unless --apply.

    python3 tools/colorprism/install_wz.py --wz <server wz dir> [--apply]

The stock v83 data has no 5782000, and without it ItemInformationProvider gives the item no stack size, so
the Cash Shop refuses to sell it (BUY_UNKNOWN_ITEM, nothing charged) and nothing can create one. This adds:

  Item.wz/Cash/0578.img.xml          a new file (copied from tools/colorprism/wz/), info/cash 1, slotMax 100
  String.wz/Cash.img.xml             a 5782000 name/desc entry, inserted before the closing </imgdir>

Idempotent: an existing 0578.img.xml or 5782000 string entry is left alone. With --apply every file it
changes is first copied to <wz>/../wz-backup-<UTC stamp>/<file>. The server reads the WZ at startup.
The client half (icon, window art) ships in slums.dll's Custom.wz; the item name cannot be added there.
"""
import argparse
import datetime
import os
import re
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ITEM_SRC = HERE / 'wz' / 'Item.wz' / 'Cash' / '0578.img.xml'
ITEM_REL = Path('Item.wz/Cash/0578.img.xml')
STRING_REL = Path('String.wz/Cash.img.xml')
ITEM_ID = '5782000'
NAME = 'Coloring Prism'
DESC = ('Double-click to open the Coloring Prism and dye a Cash equip, its glow, your hair or your eyes. '
        'Each confirmed dye or restore uses one prism.')
ENTRY = (f'<imgdir name="{ITEM_ID}">\n<string name="name" value="{NAME}"/>\n'
         f'<string name="desc" value="{DESC}"/>\n</imgdir>\n')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--wz', required=True, type=Path)
    ap.add_argument('--apply', action='store_true')
    a = ap.parse_args()
    wz = a.wz
    if not (wz / 'Item.wz').is_dir() or not (wz / STRING_REL).is_file():
        sys.exit(f'{wz} does not look like a server WZ XML directory')

    changes = []
    if (wz / ITEM_REL).exists():
        print(f'{ITEM_REL} already present, left alone')
    else:
        changes.append(ITEM_REL)
    text = (wz / STRING_REL).read_text(encoding='utf-8')
    if re.search(rf'<imgdir name="{ITEM_ID}"', text):
        print(f'{STRING_REL} already has {ITEM_ID}, left alone')
    else:
        changes.append(STRING_REL)
    if not changes:
        print('nothing to do')
        return 0
    if not a.apply:
        print('would change: ' + ', '.join(map(str, changes)) + ' (dry run; pass --apply)')
        return 0

    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup = wz.parent / f'wz-backup-{stamp}'
    if STRING_REL in changes:
        (backup / STRING_REL).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(wz / STRING_REL, backup / STRING_REL)
        end = text.rstrip().rfind('</imgdir>')
        if end < 0:
            sys.exit(f'{STRING_REL}: no closing </imgdir>')
        tmp = wz / (str(STRING_REL) + '.tmp')
        tmp.write_text(text[:end] + ENTRY + text[end:], encoding='utf-8')
        os.replace(tmp, wz / STRING_REL)
        print(f'added {ITEM_ID} to {STRING_REL} (backup in {backup})')
    if ITEM_REL in changes:
        shutil.copy2(ITEM_SRC, wz / ITEM_REL)
        print(f'added {ITEM_REL}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
