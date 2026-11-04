#!/usr/bin/env python3
"""Validate the normal-200 roster, preview acquisition, optionally emit migration 059.

No DB connection, installed-data claim or global droppable-policy modification.
The checked-in rate profile is proposed tuning, not measured player results.
"""
import argparse
import csv
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET
from collections import Counter
from audit_normal_gear import read_rebirths, forge_outputs, SUPPORTED_WEAPONS
from wzitems import WzItems
from gen_bb_drops import write_sql

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT/'docs/economy/normal-200'
MIGRATION = '059-normal-gear-through-200'


def check_item(itemid, info, allowed, restrictions, forged):
    if not info or not allowed:
        raise ValueError(f'{itemid}: missing data or allowlist entry')
    if not info['name'] or info['name'] != allowed['name'] or info['kind'] != allowed['kind']:
        raise ValueError(f'{itemid}: name/kind changed')
    if info['kind'] not in ('equip','consume'):
        raise ValueError(f'{itemid}: unsupported item kind')
    for flag in ('cash','quest','timeLimited','only','accountSharable'):
        if info[flag]:
            raise ValueError(f'{itemid}: forbidden {flag}')
    actual = {flag for flag in ('tradeBlock','equipTradeBlock') if info[flag]}
    if actual != set(allowed['bindings']):
        raise ValueError(f'{itemid}: binding flags changed')
    if restrictions.get(itemid,0) or itemid in forged:
        raise ValueError(f'{itemid}: forged/rebirth-restricted output')
    if info['slot'] == 'Weapon' and itemid//10000 not in SUPPORTED_WEAPONS:
        raise ValueError(f'{itemid}: unsupported weapon')
    if info['kind'] == 'consume' and info['success'] != 60:
        raise ValueError(f'{itemid}: expected a 60% scroll')


def wait_stats(chance, multiplier, eligible_kills_per_hour):
    if not (0 < chance <= 1000000 and math.isfinite(multiplier) and multiplier > 0
            and math.isfinite(eligible_kills_per_hour) and eligible_kills_per_hour > 0):
        raise ValueError('Invalid chance/rate/kill pace')
    # MapleMap compares a truncated chance against nextInt(999999).
    probability = min(int(chance*multiplier)/999999,1.0)
    if probability <= 0:
        raise ValueError('Effective chance rounded to zero')
    mean = 1/probability
    p90 = 1 if probability == 1 else math.ceil(math.log(.1)/math.log1p(-probability))
    return dict(effective_probability=probability,mean_eligible_kills=mean,p90_eligible_kills=p90,
                mean_hours=mean/eligible_kills_per_hour,p90_hours=p90/eligible_kills_per_hour)


def validate_sources(rows, wz_path, repo):
    lower = {int(v) for line in (repo/'src/main/resources/soloMapling/lower-grandis-maps.txt').read_text().splitlines()
             if (v:=line.split('#')[0].strip())}
    checked=set()
    for row in rows:
        mob,mid=int(row['dropperid']),int(row['mapid'])
        if (mob,mid) in checked:
            continue
        if not (271000000 <= mid <= 273999999 or mid in lower):
            raise ValueError(f'{mob}/{mid}: not RB0 area-gate accessible')
        root=ET.parse(wz_path/'Map.wz/Map'/f'Map{mid//100000000}'/f'{mid:09d}.img.xml').getroot()
        life=root.find("imgdir[@name='life']")
        if life is None:
            raise ValueError(f'{mid}: missing life section')
        present=False
        for node in life:
            ident=node.find("*[@name='id']");typ=node.find("*[@name='type']")
            if ident is not None and typ is not None and typ.get('value')=='m' and int(ident.get('value'))==mob:
                present=True
        if not present:
            raise ValueError(f'{mob}/{mid}: no monster placement')
        info=ET.parse(wz_path/'Mob.wz'/f'{mob:07d}.img.xml').getroot().find("imgdir[@name='info']")
        if info is None:
            raise ValueError(f'{mob}: missing info')
        v={n.get('name'):n.get('value') for n in info if n.get('value') is not None}
        if v.get('link') or int(v.get('boss',0)) or int(v.get('allyMob',0)) or int(v.get('exp',0))<=0:
            raise ValueError(f'{mob}: linked, boss, friendly or zero-EXP actor')
        if int(v['level']) != int(row['raw_mob_level']):
            raise ValueError(f'{mob}: level drift')
        checked.add((mob,mid))


def build(rows, policy, wz, repo, multiplier, pace):
    restrictions=read_rebirths(repo/'src/main/java/constants/inventory/ItemConstants.java')
    forged=forge_outputs(repo)
    pairs=set();source_counts=Counter();out=[];preview=[]
    for row in rows:
        mob,item=int(row['dropperid']),int(row['itemid'])
        if (mob,item) in pairs or int(row['quantity'])!=1:
            raise ValueError(f'{mob}/{item}: duplicate pair or unexpected quantity')
        pairs.add((mob,item));source_counts[item]+=1
        info=wz.info(item)
        check_item(item,info,policy['items'].get(str(item)),restrictions,forged)
        if row['item']!=info['name']:
            raise ValueError(f'{item}: manifest name drift')
        category='scroll' if info['kind']=='consume' else ('weapon' if info['slot']=='Weapon' else ('accessory' if row['stage']=='Alien' else 'armour'))
        chance=policy['base_chances'][category]
        if type(chance) is not int:
            raise ValueError(f'{item}: non-integer chance')
        stats=wait_stats(chance,multiplier,pace)
        out.append((mob,item,1,1,0,chance))
        preview.append(dict(stage=row['stage'],dropperid=mob,itemid=item,item=info['name'],category=category,
                            base_chance=chance,drop_multiplier=multiplier,eligible_kills_per_hour=pace,
                            **stats,already_in_050_055=row['already_in_050_055']))
    if set(map(str,source_counts))!=set(policy['items']) or set(source_counts.values())!={2}:
        raise ValueError('Require exactly two distinct source mobs per allowlisted item; no unused allowlist IDs')
    return sorted(out),preview


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--wz',type=Path,required=True)
    ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--drop-rate',type=float,default=4)
    ap.add_argument('--eligible-kills-per-hour',type=float,default=300)
    ap.add_argument('--write-sql',action='store_true',help='Write local draft migration/rollback; never executes SQL')
    args=ap.parse_args()
    rows=list(csv.DictReader((DOCS/'pilot-source-proposal.csv').open()))
    policy=json.loads((DOCS/'drop-policy.json').read_text())
    validate_sources(rows,args.wz,ROOT)
    out,preview=build(rows,policy,WzItems(args.wz),ROOT,args.drop_rate,args.eligible_kills_per_hour)
    args.output.mkdir(parents=True,exist_ok=True)
    with (args.output/'acquisition-preview.csv').open('w') as h:
        writer=csv.DictWriter(h,fieldnames=list(preview[0]),lineterminator='\n');writer.writeheader();writer.writerows(preview)
    if args.write_sql:
        if Path.cwd().resolve() != ROOT:
            ap.error('--write-sql must run from this tool’s repository root')
        comment=f'''-- DRAFT 1.6.4 normal gear through 200: {len(out)} candidate rows, proposed rates.
-- Source: docs/economy/normal-200/drop-policy.json and pilot-source-proposal.csv.
-- Generated by tools/economy/gen_normal_drops.py; edit the inputs, not generated SQL.
-- Pending economy review, client/source/combat proof and release admission. Not auto-enabled.
-- Existing pairs (including quest/card/older drops) are skipped; only inserted pairs are ledgered.
-- Apply/rollback twice is tested locally on disposable MySQL. Reload drops after an approved deployment.
'''
        write_sql(out,[],MIGRATION,'normal_gear_059_rows','normal_gear_059_new',comment,
                  '-- Roll back only pairs introduced by 059; older pairs are preserved. Reload drops after rollback.\n')
    print(json.dumps(dict(rows=len(out),items=len({r[1] for r in out}),rate_status=policy['status'],
                          sql_written=args.write_sql,active_source_verified=False,client_verified=False)))

if __name__=='__main__':main()
