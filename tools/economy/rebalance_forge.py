#!/usr/bin/env python3
"""Preview/apply the reviewed-shape forge profile locally. Never deploys or connects to a DB.

Recipes retain output IDs, optional enhancements and declared equip/crafting milestones.
The profile numbers are engineering candidates against the owner's 8–12h target, not measured results.
"""
import argparse
import copy
import csv
import json
import re
from pathlib import Path
from wzitems import WzItems

ROOT=Path(__file__).resolve().parents[2]
ARMOUR_SLOTS=(100,105,107,108,110)
CORES=dict(Warrior=4036544,Magician=4036545,Bowman=4036546,Thief=4036547,Pirate=4036548)
SW={100:1003976,105:1052669,107:1072870,108:1082556,110:1102623,130:1302297,131:1312173,
    132:1322223,133:1332247,137:1372195,138:1382231,140:1402220,141:1412152,142:1422158,
    143:1432187,144:1442242,145:1452226,146:1462213,147:1472235,148:1482189,149:1492199}


def set_recipe(text,recipe):
    pattern=r'\{\s*"name"\s*:\s*"(?:[^"\\]|\\.)*"\s*,\s*"itemId"\s*:\s*'+str(recipe['itemId'])+r'\b'
    match=re.search(pattern,text)
    if not match:raise ValueError(f'Missing recipe {recipe["itemId"]}')
    _,length=json.JSONDecoder().raw_decode(text[match.start():])
    indent=match.start()-text.rfind('\n',0,match.start())-1
    block=json.dumps(recipe,indent=2).replace('\n','\n'+' '*indent)
    return text[:match.start()]+block+text[match.start()+length:]


def desired(policy,gear,weapons):
    output={};changes=[]
    for filename,data in [('gearSets',gear),('weapons',weapons)]:
        for family,groups in data.items():
            previous=SW
            for group in groups:
                stage=next((v for k,v in policy['stages'].items() if group['name']==(v['armour_group'] if filename=='gearSets' else k)),None)
                end=policy['endgame'].get(group['name']) if filename=='weapons' else None
                if stage is None and end is None:raise ValueError(f'Unsupported stage {group["name"]}')
                for old in group['recipes']:
                    r=copy.deepcopy(old);prefix=r['itemId']//10000
                    r['materials']=[dict(itemId=previous[prefix],amount=1,processStarforce=True)]
                    if stage:
                        regional=stage['regional_items'];weapon=filename=='weapons'
                        chosen=(regional[:2] if len(regional)>1 else regional) if weapon else [regional[ARMOUR_SLOTS.index(prefix)%len(regional)]]
                        r['materials'] += [dict(itemId=i,amount=stage['weapon_quantity' if weapon else 'armour_quantity']) for i in chosen]
                        if weapon:
                            r['materials'].append(dict(itemId=stage['weapon_trophy'],amount=1))
                            if stage['weapon_core']:r['materials'].append(dict(itemId=CORES[family],amount=1))
                        r['cost']=stage['fee_per_piece']
                        r['requiredLevel']=stage['craft_level']
                    else:
                        r['materials'] += [dict(itemId=int(i),amount=n) for i,n in end['materials'].items()]
                        r['materials'].append(dict(itemId=CORES[family],amount=end['class_core']))
                        r['cost']=end['fee']
                        r['requiredLevel']=end['craft_level']
                    output[r['itemId']]=r
                    changes.append(dict(file=filename,family=family,stage=group['name'],itemid=r['itemId'],old_fee=old['cost'],new_fee=r['cost'],old_inputs=json.dumps(old['materials']),new_inputs=json.dumps(r['materials'])))
                previous={r['itemId']//10000:r['itemId'] for r in group['recipes']}
    return output,changes


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--wz',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--write',action='store_true')
    args=ap.parse_args();policy=json.loads((ROOT/'docs/economy/forge-stages.json').read_text())
    texts={f:(ROOT/f'scripts/json/{f}.json').read_text() for f in ['gearSets','weapons','refiningMaterials']}
    recipes,changes=desired(policy,json.loads(texts['gearSets']),json.loads(texts['weapons']))
    fragment=dict(name='Genesis Fragment',**policy['fragment_recipe']);w=WzItems(args.wz)
    for r in [*recipes.values(),fragment]:
        info=w.info(r['itemId'])
        if not info or not info['name'] or info['cash']:raise ValueError(f'Invalid output {r["itemId"]}')
        for m in r['materials']:
            info=w.info(m['itemId'])
            if not info or not info['name'] or info['cash'] or info['quest'] or info['timeLimited']:
                raise ValueError(f'Unsupported material {m["itemId"]}')
            if not isinstance(m['amount'],int) or m['amount']<=0:raise ValueError('Invalid material amount')
            if m.get('processStarforce') and m['itemId']//10000!=r['itemId']//10000:raise ValueError('Input weapon/slot mismatch')
    args.output.mkdir(parents=True,exist_ok=True)
    with (args.output/'recipe-diff.csv').open('w') as h:
        writer=csv.DictWriter(h,fieldnames=list(changes[0]),lineterminator='\n');writer.writeheader();writer.writerows(changes)
    if args.write:
        if Path.cwd().resolve()!=ROOT:ap.error('--write requires this repository root')
        for filename in ['gearSets','weapons']:
            text=texts[filename]
            for r in changes:
                if r['file']==filename:text=set_recipe(text,recipes[r['itemid']])
            json.loads(text);(ROOT/f'scripts/json/{filename}.json').write_text(text)
        refining=json.loads(texts['refiningMaterials'])
        if any(r['itemId']==fragment['itemId'] for r in refining):
            text=set_recipe(texts['refiningMaterials'],fragment)
        else:
            text=texts['refiningMaterials'].rstrip()[:-1].rstrip()+',\n'+json.dumps(fragment,indent=2)+'\n]\n'
        json.loads(text);(ROOT/'scripts/json/refiningMaterials.json').write_text(text)
    print(json.dumps(dict(equipment_recipes=len(recipes),fragment_recipe=fragment['itemId'],written=args.write,client_verified=False,measured_pacing=False)))

if __name__=='__main__':main()
