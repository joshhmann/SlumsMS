#!/usr/bin/env python3
"""Generate src/main/java/soloMapling/SetEquip/set-items.yaml (ticket 18, SetEquip addon).

v83 has no Etc.wz/SetItemInfo.img, so the set table is taken from a later GMS Etc.wz (PKG1 without a version
header, version hash 777; the one used is /root/maplestory_dump/Etc.wz) and cut down to what this server can use:

  * an item is kept only when its own info/setItemID (if it has one) names this set, the server's Character.wz
    has it, it is not a cash item, its info/islot is a slot EquipSlot.java accepts, and (for weapons) its type
    is a v83 weapon (ItemInformationProvider.getWeaponType);
  * only stat keys Character#recalcEquipStats applies are kept (SetItemCatalog.APPLIED_KEYS). Defence, accuracy,
    avoid, speed, jump, % HP/MP and potential "Option" lines are dropped, so the tooltip never lists a bonus the
    server does not grant;
  * a tier is kept only when that many pieces can actually be worn at once from the kept items (one weapon,
    one item per armour slot, up to four rings), and a set is kept only when some tier survives.

Usage:
  python3 -I gen_set_items.py --etc /root/maplestory_dump/Etc.wz --wz /root/work/bake/wz \
      --native-wz <SlumsMS-Client>/tools/wz --out src/main/java/soloMapling/SetEquip/set-items.yaml
"""
import argparse
import html
import mmap
import re
import struct
import sys
from pathlib import Path

APPLIED_KEYS = ['incSTR', 'incDEX', 'incINT', 'incLUK', 'incAllStat', 'incMHP', 'incMMP', 'incPAD', 'incMAD']
# EquipSlot.java islot names (Cp, HrCp, Af, Ay, Ae, Ma, MaPn, Pn, So, GlGw, Gv (every glove here), Sr, Si, Wp, WpSi, WpSp, Ri, Pe, Me, Be, Sh).
ISLOTS = {'Cp', 'HrCp', 'Af', 'Ay', 'Ae', 'Ma', 'MaPn', 'Pn', 'So', 'GlGw', 'Gv', 'Sr', 'Si', 'Wp', 'WpSi', 'WpSp',
          'Ri', 'Pe', 'Me', 'Be', 'Sh'}
# ItemInformationProvider.getWeaponType: categories 30-49 minus 34, 35, 36, 39.
V83_WEAPONS = {30, 31, 32, 33, 37, 38, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49}
MAX_RINGS = 4


def load_sets(etc_path, native_wz):
    sys.path.insert(0, str(native_wz))
    import native_wz as n

    class Archive(n.Archive):  # modern PKG1: no version short after the header
        def __init__(self, path, version):
            self.path, self.version, self.key = Path(path), version, 'none'
            self.file = open(path, 'rb')
            self.data = mmap.mmap(self.file.fileno(), 0, access=mmap.ACCESS_READ)
            self.start = struct.unpack_from('<I', self.data, 12)[0]
            self.hash = n.version_hash(version)
            self.root = n.Entry('', 3, children=self._directory(self.start, set()))

    arc = Archive(etc_path, 777)
    root = n.parse_img(arc.raw(arc.entry('SetItemInfo.img')), 'SetItemInfo.img', 'none')[0]

    def plain(node):
        if node.children:
            return {c.name: plain(c) for c in node.children}
        return node.value
    return plain(root)


def item_info(wz, item_id):
    hits = list((Path(wz) / 'Character.wz').glob(f'*/{item_id:08d}.img.xml'))
    if not hits:
        return None
    text = hits[0].read_text(encoding='utf-8', errors='replace')
    info = text.split('<imgdir name="info">', 1)[-1].split('</imgdir>', 1)[0] if '<imgdir name="info">' in text else ''
    islot = re.search(r'<string name="islot" value="([^"]*)"', info)
    cash = re.search(r'<int name="cash" value="(\d+)"', info)
    set_id = re.search(r'<int name="setItemID" value="(-?\d+)"', info)
    return {'islot': islot.group(1) if islot else '', 'cash': bool(cash and cash.group(1) != '0'),
            'set': int(set_id.group(1)) if set_id else None}


def item_names(wz):
    names = {}
    eqp = Path(wz) / 'String.wz' / 'Eqp.img.xml'
    cur = None
    for line in eqp.read_text(encoding='utf-8', errors='replace').splitlines():
        m = re.match(r'\s*<imgdir name="(\d+)">', line)
        if m:
            cur = int(m.group(1))
            continue
        m = re.match(r'\s*<string name="name" value="([^"]*)"', line)
        if m and cur is not None:
            names.setdefault(cur, html.unescape(m.group(1)))
    return names


def flat_items(item_node):
    out = []
    for value in (item_node or {}).values():
        if isinstance(value, dict):
            out += [v for v in value.values() if isinstance(v, int)]
        elif isinstance(value, int):
            out.append(value)
    return list(dict.fromkeys(out))


def wearable(kept, infos):
    """How many pieces of the set one character can wear at once."""
    slots, rings, weapon = set(), 0, False
    for item in kept:
        islot = infos[item]['islot']
        if islot.startswith('Wp'):
            weapon = True
        elif islot == 'Ri':
            rings += 1
        else:
            slots.add('Ma' if islot == 'MaPn' else islot)
    return len(slots) + min(rings, MAX_RINGS) + (1 if weapon else 0)


def yaml_str(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--etc', required=True)
    ap.add_argument('--wz', required=True)
    ap.add_argument('--native-wz', required=True)
    ap.add_argument('--out', required=True)
    args = ap.parse_args()

    sets = load_sets(args.etc, args.native_wz)
    names = item_names(args.wz)
    infos = {}
    kept_sets, report = [], []
    for sid in sorted(sets, key=lambda s: int(s) if s.lstrip('-').isdigit() else 1 << 30):
        if not sid.isdigit():
            continue
        s = sets[sid]
        items = flat_items(s.get('ItemID'))
        kept = []
        for item in items:
            if item not in infos:
                infos[item] = item_info(args.wz, item)
            info = infos[item]
            if not info or info['cash'] or info['islot'] not in ISLOTS:
                continue
            if info['set'] is not None and info['set'] != int(sid):
                continue  # the item's own info/setItemID names another set; an item belongs to one set only
            if info['islot'].startswith('Wp') and (item // 10000) % 100 not in V83_WEAPONS:
                continue
            kept.append(item)
        if not kept:
            continue
        can_wear = wearable(kept, infos)
        tiers = {}
        for tier, stats in (s.get('Effect') or {}).items():
            if not tier.isdigit() or not isinstance(stats, dict) or int(tier) > can_wear:
                continue
            applied = {k: stats[k] for k in APPLIED_KEYS if isinstance(stats.get(k), int) and stats[k] != 0}
            if applied:
                tiers[int(tier)] = applied
        if not tiers:
            continue
        name = ' '.join(str(s.get('setItemName', '')).split()) or f'Set {sid}'
        if not name.isascii() or name == 'String':
            continue
        complete = min(int(s.get('completeCount') or max(tiers)), can_wear)
        kept_sets.append((int(sid), name, complete, kept, tiers))
        report.append(f'{sid:>4} {name} ({len(kept)}/{len(items)} items, wear {can_wear}, tiers {sorted(tiers)})')

    out = [
        '# SetEquip set table (SlumsMS ticket 18). GENERATED by tools/setequip/gen_set_items.py; see that script for',
        '# the source and the filters. Read once, the first time the server works out a character\'s stats; restart',
        '# to apply an edit. A set with a mistake (unknown key or stat, not an equip id, item in two sets) is skipped',
        '# and logged. Item ids are not re-checked at load: keep to items the server\'s Character.wz has.',
        '#',
        '# id: GMS SetItemInfo id   complete: pieces for the full set   items: every piece (any one weapon counts)',
        '# tiers: <pieces>: stats gained at that many pieces; tiers stack (5 pieces = tier 2 + tier 3 + ... + tier 5).',
        '# Stat keys: incSTR incDEX incINT incLUK incAllStat (all four) incMHP incMMP incPAD (weapon att) incMAD.',
        'sets:',
    ]
    for sid, name, complete, kept, tiers in kept_sets:
        out.append(f'  - id: {sid}')
        out.append(f'    name: {yaml_str(name)}')
        out.append(f'    complete: {complete}')
        out.append('    items:')
        for item in kept:
            out.append(f'      - {item}   # {names.get(item, "?")}')
        out.append('    tiers:')
        for tier in sorted(tiers):
            stats = ', '.join(f'{k}: {v}' for k, v in tiers[tier].items())
            out.append(f'      {tier}: {{{stats}}}')
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text('\n'.join(out) + '\n', encoding='utf-8')
    print('\n'.join(report))
    print(f'{len(kept_sets)} sets written to {args.out}')


if __name__ == '__main__':
    main()
