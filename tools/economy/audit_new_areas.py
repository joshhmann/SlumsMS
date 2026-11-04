#!/usr/bin/env python3
"""Read-only ticket-81 combat/reward inventory. Never changes WZ, SQL or gameplay values.

HP/EXP use the server override resource. PA/MA and attack metadata are raw server XML values,
NOT measured player damage. Planned drop rows are repository content, NOT active DB coverage.
An optional six-column TSV export distinguishes actual exported drop rows from planned rows.
Hourly figures use an explicit fixed-DPS model; they are not measured training rates.
"""
import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET

import gen_bb_drops as rules
from ladder_table import read_stats


def chance(base, rate):
    # MapleMap draws nextInt(999999), not nextInt(1000000). No card/family/event multiplier assumed.
    return min(1.0, max(0, int(base * rate)) / 999999)


def expected_quantity(row):
    lo, hi = int(row['minimum_quantity']), int(row['maximum_quantity'])
    # The game's upper bound is exclusive, with fixed-quantity item rows handled separately.
    return (lo + hi - 1) / 2 if hi > lo else lo


def mob_fields(path):
    root = ET.parse(path).getroot()
    info = root.find("./imgdir[@name='info']")
    if info is None:
        raise ValueError(f'{path}: missing info')
    fields = {n.get('name'): n.get('value') for n in info if n.tag != 'imgdir'}
    attacks = []
    for attack in root.findall('./imgdir'):
        if not attack.get('name', '').startswith('attack'):
            continue
        node = attack.find("./imgdir[@name='info']")
        if node is not None:
            attacks.append({'action': attack.get('name'), **{
                n.get('name'): n.get('value') for n in node if n.tag in ('int', 'short', 'long', 'string')}})
    return fields, attacks


def load_export(path):
    """Only explicit input files; no connections or credentials. Header must use drop_data column names."""
    result = defaultdict(list)
    required = {'dropperid', 'itemid', 'minimum_quantity', 'maximum_quantity', 'questid', 'chance'}
    with path.open(newline='') as source:
        reader = csv.DictReader(source, delimiter='\t')
        if not required.issubset(reader.fieldnames or []):
            raise ValueError('drop export must be a headered six-column drop_data TSV')
        for row in reader:
            for key in required:
                int(row[key])
            result[int(row['dropperid'])].append(row)
    return result


def discover_inventory(wz, stock_maps, historical, minimum_level):
    """Maps outside an explicit stock baseline; report reused classic mob IDs instead of treating them as imports."""
    names_root = ET.parse(wz / 'String.wz' / 'Mob.img.xml').getroot()
    names = {int(n.get('name')): next((c.get('value') for c in n if c.get('name') == 'name'), '')
             for n in names_root if n.get('name', '').isdigit()}
    known = {int(r['mobid']): r for r in historical}
    fields_cache = {}
    baseline_mobs = set()
    placed = defaultdict(lambda: {'maps': set(), 'placements': 0})
    errors = []
    failed = set()
    scanned = 0
    imported_maps = 0
    for path in sorted((wz / 'Map.wz' / 'Map').glob('Map*/*.img.xml')):
        stem = path.name.split('.')[0]
        if not stem.isdigit():
            continue
        mid = int(stem)
        scanned += 1
        try:
            root = ET.parse(path).getroot()
            life = root.find("./imgdir[@name='life']")
            if life is None:
                continue
            if mid not in stock_maps:
                imported_maps += 1
            for entry in life:
                props = {n.get('name'): n.get('value') for n in entry}
                if props.get('type') != 'm':
                    continue
                mob = int(props['id'])
                if mid in stock_maps:
                    baseline_mobs.add(mob)
                    continue
                if mob not in fields_cache:
                    try:
                        fields_cache[mob] = mob_fields(wz / 'Mob.wz' / f'{mob:07d}.img.xml')[0]
                    except (ET.ParseError, OSError, ValueError):
                        fields_cache[mob] = None
                if fields_cache[mob] is None:
                    if (mid, mob) not in failed:
                        errors.append(f'map {mid}: missing/unreadable Mob.wz/{mob:07d}.img.xml')
                        failed.add((mid, mob))
                    continue
                if int(fields_cache[mob].get('level', 0)) < minimum_level:
                    continue
                placed[mob]['maps'].add(mid)
                placed[mob]['placements'] += 1
        except (ET.ParseError, OSError, ValueError, KeyError) as error:
            errors.append(f'{path}: {error}')
    result = []
    for mob, locations in sorted(placed.items()):
        previous = known.get(mob, {})
        first_map = min(locations['maps'])
        result.append({'mobid': str(mob), 'name': names.get(mob, str(mob)),
                       'region': previous.get('region', f'other-{first_map // 1000000:03d}'),
                       'status': previous.get('status', 'unknown'),
                       'map_ids': sorted(locations['maps']), 'placements': locations['placements'],
                       'also_on_stock_maps': mob in baseline_mobs})
    return result, {'map_files_scanned': scanned, 'nonstock_maps_with_life': imported_maps, 'minimum_mob_level': minimum_level,
                    'errors': errors, 'stock_map_count': len(stock_maps)}


def audit(wz, inventory, stats, plans, exp_rate, meso_rate, drop_rate, boss_drop_rate,
          damage_per_hour, kill_cap, exported=None):
    planned = defaultdict(list)
    pairs = set()
    for row in plans:
        pair = int(row['dropperid']), int(row['itemid'])
        if pair in pairs:
            raise ValueError(f'duplicate planned drop pair: {pair}')
        pairs.add(pair)
        planned[pair[0]].append(row)
    output = []
    for source in inventory:
        mid = int(source['mobid'])
        fields, attacks = mob_fields(wz / 'Mob.wz' / f'{mid:07d}.img.xml')
        level = int(fields.get('level', 0))
        hp, exp = int(fields.get('maxHP', 0)), int(fields.get('exp', 0))
        boss = fields.get('boss', '0') == '1'
        # Exactly the existing generator's LifeFactory model, then the shipped resource's winning row.
        hp = rules.server_hp({'mobid': mid, 'level': level, 'maxHP': hp, 'boss': boss})
        override_hp, override_exp = stats.get(mid, (None, None))
        hp = override_hp if override_hp is not None else hp
        exp = override_exp if override_exp is not None else exp
        loot = planned[mid]
        rate = boss_drop_rate if boss else drop_rate
        kph = min(kill_cap, damage_per_hour / hp) if hp > 0 and exp > 0 and not boss else None
        mesos = sum(expected_quantity(row) * chance(int(row['chance']), rate) * meso_rate
                    for row in loot if int(row['itemid']) == 0)
        rare_rolls = sum(chance(int(row['chance']), rate) for row in loot if row['category'] == 'bbrare')
        items = defaultdict(float)
        for item in loot:
            if int(item['itemid']) != 0:
                items[item['category']] += expected_quantity(item) * chance(int(item['chance']), rate)
        row = {'mob_id': mid, 'name': source['name'], 'region': source['region'], 'level': level,
               'boss': boss, 'info_link': fields.get('link', ''),
               'source_hp': fields.get('maxHP', ''), 'server_hp': hp, 'server_exp': exp,
               'source_pa': int(fields.get('PADamage', 0)), 'source_ma': int(fields.get('MADamage', 0)),
               'source_accuracy': int(fields.get('acc', 0)), 'source_avoid': int(fields.get('eva', 0)),
               'special_info': {key: fields[key] for key in ('invincible', 'damagedByMob', 'fixedDamage',
                   'onlyNormalAttack', 'firstAttack', 'bodyAttack', 'removeAfter', 'hideHP', 'allyMob',
                   'invincibleAttack', 'fixedBodyAttackDamageR') if key in fields},
               'planned_rows': len(loot), 'baseline_inventory_status': source['status'],
               'active_export_rows': None if exported is None else len(exported.get(mid, [])),
               'active_export_meso_rows': None if exported is None else sum(int(r['itemid']) == 0
                   for r in exported.get(mid, [])),
               'active_export_noncard_item_rows': None if exported is None else sum(int(r['itemid']) > 0
                   and not 2380000 <= int(r['itemid']) < 2390000 and int(r['questid']) == 0
                   for r in exported.get(mid, [])),
               'model_kills_per_hour': kph, 'planned_mesos_per_kill': mesos,
               'model_planned_mesos_per_hour': None if kph is None else kph * mesos,
               'model_exp_per_hour': None if kph is None else kph * exp * exp_rate,
               'model_rare_rolls_per_hour': None if kph is None else kph * rare_rolls,
               'model_planned_items_per_hour': None if kph is None else {key: kph * value for key, value in items.items()},
               'categories': dict(Counter(r['category'] for r in loot)), 'attacks': attacks}
        if 'map_ids' in source:
            row.update({key: source[key] for key in ('map_ids', 'placements', 'also_on_stock_maps')})
        output.append(row)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wz', type=Path, required=True)
    parser.add_argument('--inventory', type=Path, default=Path('docs/economy/bb-drops/mobs.csv'))
    parser.add_argument('--stats', type=Path, default=Path('src/main/resources/soloMapling/mob-stat-overrides.tsv'))
    parser.add_argument('--rows', type=Path, action='append', help='repeatable; defaults to 050 and 055 row manifests')
    parser.add_argument('--drop-export', type=Path)
    parser.add_argument('--stock-map-ids', type=Path, help='discover ALL non-baseline maps instead of only ticket 81')
    parser.add_argument('--minimum-level', type=int, default=100)
    parser.add_argument('--exp-rate', type=float, required=True)
    parser.add_argument('--meso-rate', type=float, required=True)
    parser.add_argument('--drop-rate', type=float, required=True)
    parser.add_argument('--boss-drop-rate', type=float, required=True)
    parser.add_argument('--damage-per-hour', type=float, default=141000000)
    parser.add_argument('--kill-cap', type=float, default=1500)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for key in ('exp_rate', 'meso_rate', 'drop_rate', 'boss_drop_rate', 'damage_per_hour', 'kill_cap'):
        if not math.isfinite(getattr(args, key)) or getattr(args, key) <= 0:
            parser.error(f'{key} must be finite and positive')
    paths = args.rows or [Path('docs/economy/bb-drops/rows.csv'), Path('docs/economy/bb-drops/gap-rows.csv')]
    plans = []
    for path in paths:
        with path.open(newline='') as file:
            plans.extend(csv.DictReader(file))
    exported = load_export(args.drop_export) if args.drop_export else None
    with args.inventory.open(newline='') as file:
        inventory = list(csv.DictReader(file))
    discovery = None
    if args.stock_map_ids:
        stock_maps = {int(line.strip()) for line in args.stock_map_ids.read_text().splitlines()
                      if line.strip().isdigit()}
        inventory, discovery = discover_inventory(args.wz, stock_maps, inventory, args.minimum_level)
    else:
        inventory = [row for row in inventory if row['region'] != 'mirror']
    rows = audit(args.wz, inventory, read_stats(args.stats), plans, args.exp_rate, args.meso_rate,
                     args.drop_rate, args.boss_drop_rate, args.damage_per_hour, args.kill_cap, exported)
    summary = {}
    for region in sorted({row['region'] for row in rows}):
        subset = [r for r in rows if r['region'] == region]
        summary[region] = {'mobs': len(subset), 'with_planned_tables': sum(r['planned_rows'] > 0 for r in subset),
                           'positive_exp_without_planned_or_historical_table': [r['mob_id'] for r in subset
                           if r['server_exp'] > 0 and r['planned_rows'] == 0 and r['baseline_inventory_status'] != 'has-table']}
    payload = {'scope': (f'all non-baseline maps with level-{args.minimum_level}+ mobs; no reachability assertion' if discovery else
                        'retained ticket-81 inventory excluding Mirror World; no inference about other new maps'),
               'discovery': discovery,
               'drop_state': 'unverified' if exported is None else 'supplied TSV export only',
               'model': {key: getattr(args, key) for key in ('exp_rate', 'meso_rate', 'drop_rate', 'boss_drop_rate',
                                                            'damage_per_hour', 'kill_cap')},
               'sources': {'wz': str(args.wz), 'inventory': str(args.inventory), 'stats': str(args.stats),
                           'planned_rows': [str(p) for p in paths],
                           'stock_map_ids': str(args.stock_map_ids) if args.stock_map_ids else None,
                           'drop_export': str(args.drop_export) if args.drop_export else None},
               'summary': summary, 'mobs': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + '\n')
    print(json.dumps({'mobs': len(rows), 'drop_state': payload['drop_state'], 'summary': summary}, indent=2))
    if discovery and discovery['errors']:
        raise SystemExit('map discovery had errors; inspect discovery.errors in the report')


if __name__ == '__main__':
    main()
