#!/usr/bin/env python3
"""Audit a supplied SELECT-only drop export and preview the RB0 120–149 bridge.

Writes evidence CSV/JSON only: no DB connection, SQL, WZ mutation or activation.
Combat fields are retained server references, never measured human damage.
"""
import argparse
import csv
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET

from audit_normal_gear import SUPPORTED_WEAPONS, forge_outputs, read_rebirths, write_csv
from gen_normal_drops import validate_sources, wait_stats
from wzitems import WzItems

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / 'docs/economy/normal-200'


def classify_rows(rows, target):
    ordinary = [r for r in rows if int(r['questid']) == 0]
    if not rows:
        return 'insert'
    if not ordinary:
        return 'quest_only'
    return 'preserve' if any(int(r['chance']) >= target for r in ordinary) else 'raise_floor'


def validate_coverage(weapons, armour, cape):
    prefixes = [i['itemid'] // 10000 for i in weapons]
    if len(prefixes) != 16 or set(prefixes) != SUPPORTED_WEAPONS:
        raise ValueError('Incomplete or duplicated weapon type coverage')
    slots = {'Cap', 'Longcoat', 'Glove', 'Shoes'} | ({'Cape'} if cape else set())
    expected = {(mask, slot) for mask in (1, 2, 4, 8, 16) for slot in slots}
    actual = [(i['reqJob'], i['slot']) for i in armour]
    if set(actual) != expected or len(actual) != len(expected):
        raise ValueError('Incomplete or duplicated armour family/slot coverage')


def source_facts(wz, mob, mapid, maximum_level):
    path = wz / 'Map.wz/Map' / f'Map{mapid // 100000000}' / f'{mapid:09d}.img.xml'
    life = ET.parse(path).getroot().find("imgdir[@name='life']")
    entries = [{x.get('name'): x.get('value') for x in n} for n in life] if life is not None else []
    if not any(v.get('type') == 'm' and int(v.get('id', 0)) == mob for v in entries):
        raise ValueError(f'{mob}/{mapid}: no actual monster placement')
    info = ET.parse(wz / 'Mob.wz' / f'{mob:07d}.img.xml').getroot().find("imgdir[@name='info']")
    if info is None:
        raise ValueError(f'{mob}: no info')
    raw = {x.get('name'): x.get('value') for x in info if x.get('value') is not None}
    if raw.get('link') or any(int(raw.get(k, 0)) for k in ('boss', 'allyMob')) or int(raw.get('exp', 0)) <= 0:
        raise ValueError(f'{mob}: not a plain positive-EXP grinding mob')
    if not 1 <= int(raw.get('level', 0)) <= maximum_level:
        raise ValueError(f'{mob}: source level exceeds acquisition band')
    return {k: int(raw.get(k, 0)) for k in ('level', 'maxHP', 'exp', 'PADamage', 'MADamage', 'acc', 'eva')}


def read_export(path):
    with path.open() as handle:
        reader = csv.DictReader(handle, delimiter='\t')
        columns = {'dropperid', 'itemid', 'minimum_quantity', 'maximum_quantity', 'questid', 'chance'}
        if not reader.fieldnames or set(reader.fieldnames) != columns:
            raise ValueError('Expected exactly the six permitted drop_data columns')
        out = []
        for row in reader:
            r = {k: int(v) for k, v in row.items()}
            if (not 0 <= r['chance'] <= 1000000 or r['minimum_quantity'] < 1
                    or r['maximum_quantity'] < r['minimum_quantity'] or r['questid'] < 0):
                raise ValueError(f'Invalid exported drop row: {r}')
            out.append(r)
        return out


def audit(config, wz_path, export, drop_rate, pace):
    wz = WzItems(wz_path)
    restrictions = read_rebirths(ROOT / 'src/main/java/constants/inventory/ItemConstants.java')
    forged = forge_outputs(ROOT)
    policy = json.loads((DOCS / config['rate_policy']).read_text())
    indexed = defaultdict(list)
    byitem = defaultdict(list)
    for r in export:
        indexed[r['dropperid'], r['itemid']].append(r)
        byitem[r['itemid']].append(r)
    inventory = {int(r['mobid']): r for r in csv.DictReader((ROOT / 'docs/economy/bb-drops/mobs.csv').open())}
    overrides = {}
    for line in (ROOT / 'src/main/resources/soloMapling/mob-stat-overrides.tsv').read_text().splitlines():
        if line and not line.startswith('#'):
            mid, hp, exp, *_ = line.split('\t')
            overrides[int(mid)] = (hp, exp)
    items, proposal, sources, current = [], [], [], []
    seen_items = set()
    for band in config['bands']:
        band_items = []
        for category in ('weapons', 'armour'):
            for itemid in band[category]:
                item = wz.info(itemid)
                if (not item or not item['name'] or item['kind'] != 'equip'
                        or restrictions.get(itemid) or itemid in forged
                        or any(item[k] for k in ('cash', 'quest', 'timeLimited', 'only', 'accountSharable'))):
                    raise ValueError(f'{itemid}: not an eligible normal bridge item')
                if itemid in seen_items:
                    raise ValueError(f'{itemid}: duplicated item')
                seen_items.add(itemid)
                slot, path = wz._equip_index()[itemid]
                node = ET.parse(path).getroot().find("imgdir[@name='info']")
                raw = {n.get('name'): n.get('value') for n in node}
                fact = dict(band=band['name'], itemid=itemid, name=item['name'], slot=slot,
                            **{k: int(raw.get(k, 0)) for k in ('reqLevel', 'reqJob', 'reqSTR', 'reqDEX',
                               'reqINT', 'reqLUK', 'incPAD', 'incMAD', 'incMHP', 'incMMP', 'tuc')})
                if fact['reqLevel'] > band['equip_band']:
                    raise ValueError(f'{itemid}: output level exceeds equip band')
                band_items.append(fact)
                for row in byitem[itemid]:
                    mobfile = wz_path / 'Mob.wz' / f"{row['dropperid']:07d}.img.xml"
                    rawmob = {}
                    if mobfile.exists():
                        info = ET.parse(mobfile).getroot().find("imgdir[@name='info']")
                        rawmob = {n.get('name'): n.get('value') for n in info} if info is not None else {}
                    ref = inventory.get(row['dropperid'], {})
                    current.append(dict(name=item['name'], **row,
                                        mob_level=rawmob.get('level', ''), raw_boss_flag=rawmob.get('boss', '0'),
                                        reference_region=ref.get('region', 'unclassified'),
                                        mean_eligible_hours_at_assumed_rate=(
                                            wait_stats(row['chance'], drop_rate, pace)['mean_hours']
                                            if row['questid'] == 0 and row['chance'] > 0 else '')))
        validate_coverage([i for i in band_items if i['slot'] == 'Weapon'],
                          [i for i in band_items if i['slot'] != 'Weapon'], band['equip_band'] == 140)
        items.extend(band_items)
        if config['sources_per_item'] != 2 or len(set(map(tuple, band['sources']))) != 5:
            raise ValueError('Require five distinct source placements and two sources per item')
        for mob, mapid in band['sources']:
            fact = source_facts(wz_path, mob, mapid, band['maximum_source_level'])
            # Reuse the existing gate/actor validation; no new RB exceptions.
            validate_sources([{'dropperid': mob, 'mapid': mapid, 'raw_mob_level': fact['level']}], wz_path, ROOT)
            hp, exp = overrides.get(mob, ('-', '-'))
            sources.append(dict(band=band['name'], dropperid=mob, mapid=mapid,
                                recommended_start=band['recommended_start'], **fact,
                                override_hp=hp, override_exp=exp, combat_proven=False))
        for offset, item in enumerate(band_items):
            category = 'weapon' if item['slot'] == 'Weapon' else 'armour'
            target = policy['base_chances'][category]
            for source_offset in range(2):
                mob, mapid = band['sources'][(offset + source_offset) % 5]
                existing = indexed[mob, item['itemid']]
                proposal.append(dict(band=band['name'], itemid=item['itemid'], item=item['name'],
                                     dropperid=mob, mapid=mapid, quantity=1, questid=0,
                                     proposed_base_chance=target, action=classify_rows(existing, target),
                                     existing_rows=len(existing), existing_chances=';'.join(str(r['chance']) for r in existing),
                                     drop_multiplier=drop_rate, assumed_eligible_kills_per_hour=pace,
                                     **wait_stats(target, drop_rate, pace)))
    return items, proposal, sources, current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wz', type=Path, required=True)
    parser.add_argument('--drop-export', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--drop-rate', type=float, default=4)
    parser.add_argument('--eligible-kills-per-hour', type=float, default=300)
    args = parser.parse_args()
    configfile = DOCS / 'bridge-120-149.json'
    config = json.loads(configfile.read_text())
    export = read_export(args.drop_export)
    items, proposal, sources, current = audit(config, args.wz, export, args.drop_rate, args.eligible_kills_per_hour)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, rows in [('items', items), ('proposal', proposal), ('source-combat', sources), ('current-sources', current)]:
        if rows:
            write_csv(args.output / f'{name}.csv', rows)
    summary = dict(status=config['status'], items=len(items), proposed_pairs=len(proposal),
                   source_mobs=len(sources), live_export_rows=len(export),
                   actions=dict(Counter(r['action'] for r in proposal)),
                   rates_assumed=True, combat_proven=False, sql_generated=False,
                   inputs={str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in [configfile, DOCS / config['rate_policy'], args.drop_export]})
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
