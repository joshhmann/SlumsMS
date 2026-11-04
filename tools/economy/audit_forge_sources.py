#!/usr/bin/env python3
"""Verify repository/WZ source contracts for the main forged sets/weapons and Fragment.

Existing source rows and script receipts are not deployment, route, or real-client proof.
Accessories are outside this curated stage contract and retain their separate catalogue.
"""
import argparse,csv,json,re
from collections import defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET
from wzitems import WzItems
from audit_new_areas import mob_fields
from gen_royal_materials import PAIRS,build as royal_rows

ROOT=Path(__file__).resolve().parents[2]


def map_cases(script):
    script=re.sub(r'/\*.*?\*/|//[^\n]*','',script,flags=re.S)
    matches=list(re.finditer(r'^\s*case\s+(\d+)\s*:',script,re.M))
    result={}
    for n,m in enumerate(matches):
        # The later exit-map helper repeats these IDs; the first switch is the actual reward handler.
        result.setdefault(int(m.group(1)),script[m.end():matches[n+1].start() if n+1<len(matches) else len(script)])
    return result


def has_reward(case,item,quantity):
    case=re.sub(r'/\*.*?\*/|//[^\n]*','',case,flags=re.S)
    return re.search(r'^\s*playerInteraction\.gainItem\(\s*'+str(item)+r'\s*,\s*'+str(quantity)+r'\s*\)\s*;',case,re.M) is not None


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--wz',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);args=ap.parse_args()
    w=WzItems(args.wz);contract=json.loads((ROOT/'docs/economy/forge-source-contract.json').read_text())
    policy=json.loads((ROOT/'docs/economy/forge-stages.json').read_text());regional={i for p in policy['stages'].values() for i in p['regional_items']}
    needed=set(regional)
    for p in policy['stages'].values():needed.add(p['weapon_trophy'])
    for p in policy['endgame'].values():needed.update(map(int,p['materials']))
    needed.update(contract['core_pool']['items'])
    needed.update(m['itemId'] for m in policy['fragment_recipe']['materials'])
    planned=defaultdict(list)
    for file in ['rows.csv','gap-rows.csv']:
        for r in csv.DictReader((ROOT/'docs/economy/bb-drops'/file).open()):
            if int(r['itemid']) in regional:planned[int(r['itemid'])].append(r)
    inventory={int(r['mobid']):r for r in csv.DictReader((ROOT/'docs/economy/bb-drops/mobs.csv').open())}
    royal_rows(args.wz,ROOT)  # Verify current source roles and placements for060 as well.
    for mob,item in PAIRS:
        planned[item].append(dict(dropperid=mob,chance=400000,source='060 gap fill'))
    cases=map_cases((ROOT/'scripts/npc/3004006.js').read_text());output=[];issues=[]
    for item in sorted(needed):
        info=w.info(item)
        if not info or not info['name'] or info['cash'] or info['quest']:raise ValueError(f'Unsupported material {item}')
        if item in regional:
            valid=[]
            for r in planned[item]:
                mob=int(r['dropperid']);known=inventory.get(mob)
                if not known:continue
                f,_=mob_fields(args.wz/'Mob.wz'/f'{mob:07d}.img.xml')
                if f.get('link') or int(f.get('boss',0)) or int(f.get('allyMob',0)) or int(f.get('exp',0))<=0:continue
                mid=int(known['first_map']);life=ET.parse(args.wz/'Map.wz/Map'/f'Map{mid//100000000}'/f'{mid:09d}.img.xml').getroot().find("imgdir[@name='life']")
                if life is None:continue
                if not any(n.find("*[@name='type']") is not None and n.find("*[@name='type']").get('value')=='m' and n.find("*[@name='id']") is not None and int(n.find("*[@name='id']").get('value'))==mob for n in life):continue
                valid.append((mob,mid,int(r['chance'])))
            if not valid:issues.append(f'{item}: no ordinary placed regional source')
            sources='; '.join(f'{m}@{mid}:base{c}' for m,mid,c in sorted(set(valid)))
            role='regional drop, repository rows/WZ placement'
        elif str(item) in contract['boss_rewards']:
            p=contract['boss_rewards'][str(item)]
            if p['name']!=info['name'] or not has_reward(cases.get(p['map'],''),item,p['quantity']):issues.append(f'{item}: boss reward contract mismatch')
            sources=f"clear {p['map']}: fixed {p['quantity']} (no drop-rate multiplication)";role='boss clear receipt'
        elif item in contract['core_pool']['items']:
            p=contract['core_pool']
            if not has_reward(cases.get(p['map'],''),item,1):issues.append(f'{item}: missing class-core pool branch')
            sources=f"clear {p['map']}: one random class core total; one-for-one exchange";role='core pool + exchange'
        elif item==contract['fragment_recipe']:
            recipes=json.loads((ROOT/'scripts/json/refiningMaterials.json').read_text())
            found=[r for r in recipes if r['itemId']==item]
            if len(found)!=1 or found[0]['materials']!=policy['fragment_recipe']['materials']:issues.append(f'{item}: fragment recipe mismatch')
            sources='Nimble refining: four hard-boss tokens,1M,level225,Maker3';role='refining recipe'
        else:raise ValueError(f'{item}: no source contract')
        output.append(dict(itemid=item,name=info['name'],role=role,sources=sources,active_verified=False,route_and_client_verified=False))
    args.output.mkdir(parents=True,exist_ok=True)
    with (args.output/'forge-source-audit.csv').open('w') as h:
        writer=csv.DictWriter(h,fieldnames=list(output[0]),lineterminator='\n');writer.writeheader();writer.writerows(output)
    summary=dict(material_ids=len(output),regional_ids=len(regional),issues=issues,active_sources_verified=False,client_verified=False)
    (args.output/'forge-source-summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
    if issues:raise SystemExit(1)

if __name__=='__main__':main()
