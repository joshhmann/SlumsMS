#!/usr/bin/env python3
"""Audit retained base gear, clean forged inheritance, requirements and stacked set bonuses.

No DB/WZ edits. Attack equality across classes is not a DPS or survival proof.
Transfer percentages are explicit inputs; optional scroll/trace/Maker bonuses are excluded.
"""
import argparse
import csv
import json
import math
import re
from collections import Counter
from functools import lru_cache
from pathlib import Path
import xml.etree.ElementTree as ET
from audit_normal_gear import FAMILIES, WEAPONS, read_rebirths, write_csv
from wzitems import WzItems

ROOT = Path(__file__).resolve().parents[2]
STATS = ('incPAD','incMAD','incSTR','incDEX','incINT','incLUK','incMHP','incMMP',
         'incPDD','incMDD','incACC','incEVA','incSpeed','incJump')
TRANSFER = STATS[:6]
REQUIREMENTS = ('reqLevel','reqJob','reqSTR','reqDEX','reqINT','reqLUK','tuc')


def transfer(base, old, attack_percent, stat_percent, upgrade_counter):
    if any(not math.isfinite(p) or p<0 for p in (attack_percent,stat_percent)) or upgrade_counter<0:
        raise ValueError('Invalid transfer parameters')
    result = {k: base.get(k,0) for k in STATS}
    for key in TRANSFER:
        bonus=2*(upgrade_counter//2)+(upgrade_counter//4 if key in ('incPAD','incMAD') else 0)
        percent=attack_percent if key in ('incPAD','incMAD') else stat_percent
        result[key]=min(32767,result[key]+old.get(key,0)+math.ceil(old.get(key,0)*percent)+bonus)
    for key in ('incMHP','incMMP'):
        result[key]=min(32767,result[key]+old.get(key,0))
    return result


def read_sets(path):
    """Read the generator's constrained YAML shape without introducing a Python YAML dependency."""
    result=[];current=None
    for line in path.read_text().splitlines():
        clean=line.split('#')[0].rstrip()
        if not clean:continue
        start=re.fullmatch(r'  - id: (\d+)',clean)
        if start:
            current={'id':int(start[1]),'items':set(),'tiers':{}};result.append(current);continue
        item=re.fullmatch(r'      - (\d+)\s*',clean)
        tier=re.fullmatch(r'      (\d+): \{(.*)\}',clean)
        if item and current is not None:current['items'].add(int(item[1]))
        elif tier and current is not None:
            current['tiers'][int(tier[1])]={k.strip():int(v.strip()) for k,v in
                                         (entry.split(':',1) for entry in tier[2].split(','))}
        elif clean=='sets:' or re.fullmatch(r'    (?:name:.*|complete: \d+|items:|tiers:)',clean):pass
        else:raise ValueError('Unsupported set catalog shape: '+clean)
    return result


def set_bonus(equipped,sets):
    result={k:0 for k in STATS}
    for s in sets:
        count=len(set(equipped)&s['items'])
        for pieces,stats in s['tiers'].items():
            if pieces<=count:
                for key,value in stats.items():
                    if key=='incAllStat':
                        for main in ('incSTR','incDEX','incINT','incLUK'):result[main]+=value
                    elif key in result:result[key]+=value
                    else:raise ValueError('Unknown set stat '+key)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wz',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--attack-transfer',type=float,required=True)
    parser.add_argument('--stat-transfer',type=float,required=True)
    args=parser.parse_args()
    transfer({}, {}, args.attack_transfer,args.stat_transfer,0)
    wz=WzItems(args.wz); index=wz._equip_index()
    recipes={}
    def visit(node):
        if isinstance(node,dict):
            if 'itemId' in node and 'materials' in node:
                ident=int(node['itemId'])
                if ident in recipes:raise ValueError(f'Duplicate recipe {ident}')
                recipes[ident]=node
            else:
                for value in node.values():visit(value)
        elif isinstance(node,list):
            for value in node:visit(value)
    for name in ('gearSets.json','weapons.json','otherAccessories.json'):
        visit(json.loads((ROOT/'scripts/json'/name).read_text()))
    restrictions=read_rebirths(ROOT/'src/main/java/constants/inventory/ItemConstants.java')
    config=json.loads((ROOT/'docs/economy/normal-200/bridge-120-149.json').read_text())
    late=list(csv.DictReader((ROOT/'docs/economy/normal-200/pilot-source-proposal.csv').open()))
    items={int(r['itemid']) for r in csv.DictReader((ROOT/'docs/economy/forge-catalog/equipment.csv').open())
           if 1000000<=int(r['itemid'])<2000000}
    items.update(i for band in config['bands'] for key in ('weapons','armour') for i in band[key])
    items.update(int(r['itemid']) for r in late if 1000000<=int(r['itemid'])<2000000)
    items.update(i for i in recipes if 1000000<=i<2000000)
    @lru_cache(None)
    def raw(itemid):
        slot,path=index[itemid]
        node=ET.parse(path).getroot().find("imgdir[@name='info']")
        values={c.get('name'):c.get('value') for c in node if c.get('value') is not None}
        return dict(itemid=itemid,name=wz.names().get(itemid,''),slot=slot,
                    rebirths=restrictions.get(itemid,0),
                    attackSpeed=int(values['attackSpeed']) if 'attackSpeed' in values else '',
                    **{k:int(values.get(k,0)) for k in STATS+REQUIREMENTS})
    visiting=set()
    @lru_cache(None)
    def profile(itemid):
        if itemid in visiting:raise ValueError(f'Crafting cycle at {itemid}')
        visiting.add(itemid)
        result=raw(itemid)
        inputs=[m for m in recipes.get(itemid,{}).get('materials',[]) if m.get('processStarforce')]
        if len(inputs)>1:raise ValueError(f'Multiple transfer inputs for {itemid}')
        if inputs:
            old=inputs[0]
            if old['amount']!=1:raise ValueError(f'Transfer quantity is not one for {itemid}')
            result=transfer(result,profile(old['itemId']),args.attack_transfer,args.stat_transfer,0)
        visiting.remove(itemid)
        return result
    missing=sorted(items-set(index))
    base=[raw(i) for i in sorted(items) if i in index]
    anomalies=[]
    for row in base:
        if not row['name']:anomalies.append({'itemid':row['itemid'],'issue':'missing_name'})
        if row['reqJob']<0 or row['reqJob']&~31:anomalies.append({'itemid':row['itemid'],'issue':'unsupported_job_mask'})
        if any(row[k]<0 or row[k]>32767 for k in STATS):anomalies.append({'itemid':row['itemid'],'issue':'stat_out_of_range'})
        if not 0<=row['tuc']<=127:anomalies.append({'itemid':row['itemid'],'issue':'slot_out_of_range'})
    sets=read_sets(ROOT/'src/main/java/soloMapling/SetEquip/set-items.yaml')
    loadouts=[]
    for row in csv.DictReader((ROOT/'docs/economy/forge-catalog/complete-loadouts.csv').open()):
        ids=[int(row['weapon_id'])]+[int(i.strip()) for i in row['armour_ids'].split(';')]
        bonus=set_bonus(ids,sets)
        totals={k:sum(profile(i).get(k,0) for i in ids)+bonus[k] for k in STATS}
        loadouts.append(dict(family=row['family'],stage=row['stage'],weapon=int(row['weapon_id']),
                             minimum_rebirths=row['minimum_rebirths_to_equip'],**totals))
    normal=[]
    for mask,family in FAMILIES.items():
        for prefix in WEAPONS[mask]:
            candidates=[(b['name'],next(i for i in b['weapons'] if i//10000==prefix)) for b in config['bands']]
            for stage in ('Commerci','Sweetwater'):
                candidates.append((stage,next(int(r['itemid']) for r in late if r['stage']==stage
                                              and int(r['itemid'])//10000==prefix)))
            previous=None
            for stage,ident in candidates:
                row=raw(ident);attack=row['incMAD'] if mask==2 else row['incPAD']
                if row['reqJob'] and not row['reqJob'] & mask:
                    anomalies.append({'itemid':ident,'issue':'normal_weapon_wrong_class'})
                if previous is not None and attack<previous:
                    anomalies.append({'itemid':ident,'issue':'normal_weapon_attack_regression'})
                normal.append(dict(family=family,prefix=prefix,stage=stage,itemid=ident,name=row['name'],
                                   reqLevel=row['reqLevel'],attack=attack,attackSpeed=row['attackSpeed'],
                                   relative_to_previous=attack/previous if previous else ''))
                previous=attack
    args.output.mkdir(parents=True,exist_ok=True)
    write_csv(args.output/'base-items.csv',base)
    write_csv(args.output/'normal-weapon-curves.csv',normal)
    write_csv(args.output/'clean-forged-loadouts.csv',loadouts)
    summary=dict(items=len(base),missing_template_ids=missing,forged_loadouts=len(loadouts),normal_weapon_profiles=len(normal),
                 missing_weapon_speed=[r['itemid'] for r in base if r['slot']=='Weapon' and r['attackSpeed']==''],
                 attack_transfer=args.attack_transfer,stat_transfer=args.stat_transfer,upgrade_counter=0,
                 anomalies=anomalies,base_edits=False,client_parity_verified=False,
                 excluded=['scroll/trace upgrades','optional Maker bonuses','character AP and skills','buffs and actual DPS'],
                 review_flags=['equal attack across weapon types does not establish class parity',
                               'base-only forge comparisons omit inherited attack and main stats',
                               'low mage armour HP does not establish safe incoming damage'])
    (args.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary))
    if anomalies:raise SystemExit(1)


if __name__=='__main__':main()
