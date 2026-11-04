#!/usr/bin/env python3
"""Offline RB0 gear candidates and upgrade gaps; never writes game data or SQL.

Presence/eligibility is not proof of acquisition, client support or stronger combat stats.
Coverage counts raw level options, not DPS, equip feasibility or completed loadouts.
"""
import argparse
import csv
import json
import re
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

from wzitems import WzItems

FAMILIES = {1: 'Warrior', 2: 'Magician', 4: 'Bowman', 8: 'Thief', 16: 'Pirate'}
WEAPONS = {1: (130, 131, 132, 140, 141, 142, 143, 144), 2: (137, 138),
           4: (145, 146), 8: (133, 147), 16: (148, 149)}
SUPPORTED_WEAPONS = {p for group in WEAPONS.values() for p in group}
ARMOUR_SLOTS = ('Cap', 'Coat', 'Pants', 'Longcoat', 'Glove', 'Shoes', 'Cape', 'Shield')
NUMBERS = ('reqLevel', 'reqJob', 'reqSTR', 'reqDEX', 'reqINT', 'reqLUK', 'incPAD', 'incMAD',
           'incSTR', 'incDEX', 'incINT', 'incLUK', 'incMHP', 'incMMP', 'incPDD', 'incMDD',
           'incACC', 'incEVA', 'attackSpeed', 'tuc', 'setItemID', 'cash', 'quest', 'timeLimited',
           'tradeBlock', 'equipTradeBlock', 'accountSharable', 'only', 'bossReward')


def read_rebirths(path):
    source = re.sub(r'/\*.*?\*/|//[^\n]*', '', Path(path).read_text(), flags=re.S)
    result = {}
    for count in (1, 2, 3):
        block = re.search(r'rebirth' + str(count) + r'\s*=\s*new HashSet<>\(Arrays.asList\((.*?)\)\);', source, re.S)
        if not block:
            raise ValueError(f'Missing rebirth{count} declaration in {path}')
        for item in map(int, re.findall(r'\b\d{7}\b', block.group(1))):
            if item in result and result[item] != count:
                raise ValueError(f'Overlapping rebirth restrictions for {item}')
            result[item] = count
    return result


def forge_outputs(root):
    result = set()
    def visit(node):
        if isinstance(node, dict):
            if 'itemId' in node and 'materials' in node:
                result.add(int(node['itemId']))
            else:
                for value in node.values():
                    visit(value)
        elif isinstance(node, list):
            for value in node:
                visit(value)
    for file in ('gearSets.json', 'weapons.json', 'otherAccessories.json'):
        visit(json.loads((root / 'scripts/json' / file).read_text()))
    return result


def exclusion_reasons(item):
    reasons = []
    if not item['name']:
        reasons.append('missing_name')
    if item['rebirths']:
        reasons.append('rebirth_restricted')
    if item['forge_output']:
        reasons.append('forge_output')
    for flag in ('cash', 'quest', 'timeLimited'):
        if item[flag]:
            reasons.append(flag)
    if item['slot'] == 'Weapon' and item['itemid'] // 10000 not in SUPPORTED_WEAPONS:
        reasons.append('unsupported_weapon_type')
    if item['reqJob'] < 0 or item['reqJob'] & ~31:
        reasons.append('unsupported_job_mask')
    return reasons


def supports(item, mask):
    return item['reqJob'] == 0 or bool(item['reqJob'] & mask)


def coverage(items, levels):
    candidates = [i for i in items if not exclusion_reasons(i)]
    result = []
    for level in levels:
        for mask, family in FAMILIES.items():
            slots = [(f'weapon_{prefix}', lambda i, p=prefix: i['slot'] == 'Weapon' and i['itemid'] // 10000 == p)
                     for prefix in WEAPONS[mask]]
            slots += [(slot, lambda i, s=slot: i['slot'] == s) for slot in ARMOUR_SLOTS]
            for slot, match in slots:
                available = [i for i in candidates if i['reqLevel'] <= level and supports(i, mask) and match(i)]
                new = [i for i in available if level - 20 < i['reqLevel'] <= level]
                result.append(dict(level=level, family=family, slot=slot, available_count=len(available),
                                   new_option_count=len(new), best_item_level=max((i['reqLevel'] for i in available), default=0),
                                   new_item_ids=';'.join(str(i['itemid']) for i in new)))
    return result


def write_csv(path, rows):
    if not rows:
        raise ValueError(f'No rows for {path}')
    with path.open('w') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)


def audit(wz_path, root, minimum, maximum):
    wz = WzItems(wz_path)
    names = wz.names()
    restrictions = read_rebirths(root / 'src/main/java/constants/inventory/ItemConstants.java')
    forged = forge_outputs(root)
    planned = Counter()
    for filename in ('rows.csv', 'gap-rows.csv'):
        for row in csv.DictReader((root / 'docs/economy/bb-drops' / filename).open()):
            planned[int(row['itemid'])] += 1
    classic = {int(r['itemid']): r for r in csv.DictReader((root / 'docs/economy/bb-drops/classic-pool.csv').open())}
    items, issues = [], []
    for itemid, (slot, path) in sorted(wz._equip_index().items()):
        try:
            info = ET.parse(path).getroot().find("imgdir[@name='info']")
            if info is None:
                raise ValueError('Missing root info')
            raw = {n.get('name'): n.get('value') for n in info if n.get('value') is not None}
            values = {k: int(raw.get(k, 0)) for k in NUMBERS}
            if not minimum <= values['reqLevel'] <= maximum:
                continue
            item = dict(itemid=itemid, name=names.get(itemid, ''), slot=slot, **values,
                        rebirths=restrictions.get(itemid, 0), forge_output=itemid in forged,
                        planned_050_055_rows=planned[itemid],
                        historical_classic_droppers=classic.get(itemid, {}).get('classic_droppers', ''),
                        active_source_verified=False, client_verified=False)
            item['exclusion_reasons'] = ';'.join(exclusion_reasons(item))
            item['review_flags'] = ';'.join(k for k in ('tradeBlock', 'equipTradeBlock', 'accountSharable', 'only', 'bossReward') if item[k])
            items.append(item)
        except (ET.ParseError, ValueError) as error:
            issues.append(dict(itemid=itemid, file=str(path), error=str(error)))
    return items, issues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wz', type=Path, required=True)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--minimum', type=int, default=90)
    parser.add_argument('--maximum', type=int, default=200)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.minimum < 0 or args.maximum < args.minimum:
        parser.error('Require 0 <= minimum <= maximum')
    items, issues = audit(args.wz, args.repo, args.minimum, args.maximum)
    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / 'equipment-inventory.csv', items)
    candidates = [i for i in items if not i['exclusion_reasons']]
    if candidates:
        write_csv(args.output / 'normal-candidates.csv', candidates)
    matrix = coverage(items, [100, 120, 140, 160, 180, 200])
    write_csv(args.output / 'coverage.csv', matrix)
    summary = dict(item_rows=len(items), candidate_rows=len(candidates), issues=issues,
                   candidates_150_200=sum(i['reqLevel'] >= 150 for i in candidates),
                   newer_weapon_options={str(level): sum(r['new_option_count'] for r in matrix if r['level'] == level and r['slot'].startswith('weapon_')) for level in (160, 180, 200)},
                   coverage_is_raw_level_options_only=True, active_sources_verified=False, client_verified=False)
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))
    return bool(issues)


if __name__ == '__main__':
    raise SystemExit(main())
