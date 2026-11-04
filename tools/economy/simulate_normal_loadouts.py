#!/usr/bin/env python3
"""Seeded item-acquisition model for five armour pieces plus one correct weapon.

Models independent drop rolls and changing to the source mob with the highest summed missing-item probability.
No travel, competition, pickup loss, spawn shortage, combat or existing DB rows are simulated. Not gameplay QA.
"""
import argparse
import csv
import json
import math
import random
from pathlib import Path
from collections import defaultdict
from gen_normal_drops import wait_stats


def one_loadout(sources, required, rng):
    missing=set(required)
    if not missing or any(not any(i in drops for drops in sources.values()) for i in missing):
        raise ValueError('Require a nonempty loadout with a source for every item')
    if any(not 0<p<=1 for drops in sources.values() for p in drops.values()):
        raise ValueError('Invalid drop probability')
    kills=0
    while missing:
        # A source is a mob ID, not an extra roll for having two possible source mobs.
        mob=max(sorted(sources),key=lambda m:sum(p for i,p in sources[m].items() if i in missing))
        active=sorted((i,p) for i,p in sources[mob].items() if i in missing)
        q=1-math.prod(1-p for _,p in active)
        kills+=1 if q==1 else int(math.log1p(-rng.random())/math.log1p(-q))+1
        # Conditional first-success distribution; subsequent rolls remain independent.
        pick=rng.random()*q
        prefix=1.0
        for pos,(item,p) in enumerate(active):
            mass=prefix*p
            if pick<mass or pos==len(active)-1:
                missing.remove(item)
                for extra,prob in active[pos+1:]:
                    if rng.random()<prob:
                        missing.remove(extra)
                break
            pick-=mass
            prefix*=1-p
    return kills


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--replicates',type=int,default=2000)
    ap.add_argument('--kills-per-hour',type=float,default=300)
    args=ap.parse_args()
    if args.replicates<100 or not math.isfinite(args.kills_per_hour) or args.kills_per_hour<=0:
        ap.error('Require >=100 replicates and a finite positive kill pace')
    root=Path(__file__).resolve().parents[2];docs=root/'docs/economy/normal-200'
    options=list(csv.DictReader((docs/'late-item-options.csv').open()))
    rows=list(csv.DictReader((docs/'pilot-source-proposal.csv').open()))
    policy=json.loads((docs/'drop-policy.json').read_text())
    output=[]
    for family in ['Commerci','Sweetwater']:
        candidates=[r for r in options if r['name'].startswith(family+' ')]
        armour={int(r['itemid']) for r in candidates if r['slot']!='Weapon'}
        assert len(armour)==5
        weapons=[r for r in candidates if r['slot']=='Weapon']
        for weapon in weapons:
            for rate in [1,4]:
                sources=defaultdict(dict)
                required=armour|{int(weapon['itemid'])}
                for r in rows:
                    if int(r['itemid']) in required:
                        category='weapon' if int(r['itemid'])==int(weapon['itemid']) else 'armour'
                        sources[int(r['dropperid'])][int(r['itemid'])]=wait_stats(policy['base_chances'][category],rate,args.kills_per_hour)['effective_probability']
                rng=random.Random(f'normal-200:{family}:{weapon["itemid"]}:{rate}:v1')
                samples=sorted(one_loadout(sources,required,rng) for _ in range(args.replicates))
                output.append(dict(family=family,weapon_id=weapon['itemid'],weapon=weapon['name'],drop_multiplier=rate,
                                   replicates=args.replicates,kills_per_hour=args.kills_per_hour,mean_kills=sum(samples)/len(samples),
                                   median_kills=samples[len(samples)//2],p90_kills=samples[math.ceil(len(samples)*.9)-1],
                                   mean_hours=sum(samples)/len(samples)/args.kills_per_hour,
                                   p90_hours=samples[math.ceil(len(samples)*.9)-1]/args.kills_per_hour,
                                   model_only=True))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open('w') as h:
        writer=csv.DictWriter(h,fieldnames=list(output[0]),lineterminator='\n');writer.writeheader();writer.writerows(output)
    for rate in [1,4]:
        group=[r for r in output if r['drop_multiplier']==rate]
        print(json.dumps(dict(rate=rate,loadouts=len(group),mean_hour_range=[min(r['mean_hours'] for r in group),max(r['mean_hours'] for r in group)],p90_hour_range=[min(r['p90_hours'] for r in group),max(r['p90_hours'] for r in group)])))

if __name__=='__main__':main()
