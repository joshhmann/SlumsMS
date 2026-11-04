#!/usr/bin/env python3
"""Forge funding/material/clear-time scenarios. Inputs are explicit assumptions, not player telemetry.

Normal hunting earns mesos while collecting materials: those times overlap, so use max rather than sum.
Boss clears and travel are additional; prior equipment acquisition is excluded from this upgrade-stage budget.
"""
import argparse,csv,json,math
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]


def active_hours(fee,net_per_hour,material_kills,kills_per_hour,boss_hours,travel_hours,starting_mesos=0):
    values=[fee,net_per_hour,material_kills,kills_per_hour,boss_hours,travel_hours,starting_mesos]
    if not all(math.isfinite(x) for x in values) or min(fee,material_kills,boss_hours,travel_hours,starting_mesos)<0 or net_per_hour<=0 or kills_per_hour<=0:
        raise ValueError('Invalid scenario inputs')
    funding=max(fee-starting_mesos,0)/net_per_hour
    hunting=material_kills/kills_per_hour
    return dict(funding_hours=funding,material_hours=hunting,total_active_hours=max(funding,hunting)+boss_hours+travel_hours)


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--drop-rate',type=float,default=4)
    ap.add_argument('--kills-per-hour',type=float,default=300)
    ap.add_argument('--boss-minutes',type=float,default=30)
    ap.add_argument('--travel-hours',type=float,default=.5)
    args=ap.parse_args()
    if args.drop_rate<=0 or not math.isfinite(args.drop_rate):ap.error('Invalid drop rate')
    policy=json.loads((ROOT/'docs/economy/forge-stages.json').read_text());rows=[]
    for name,p in list(policy['stages'].items())+list(policy['endgame'].items()):
        if 'fee_per_piece' in p:
            fee=6*p['fee_per_piece'];units=5*p['armour_quantity']+min(2,len(p['regional_items']))*p['weapon_quantity']
            base=10000 if name=='Arcane Umbra' else 400000
            probability=min(int(base*args.drop_rate)/999999,1)
            material_kills=units/probability;clears=1
        else:
            fee=p['fee']+(policy['fragment_recipe']['cost'] if name=='Genesis' else 0)
            units=0;material_kills=0;clears=5 if name=='Genesis' else 4
        for scale in [.75,1,1.25]:
            net=p['reference_net_mesos_per_hour']*scale
            result=active_hours(fee,net,material_kills,args.kills_per_hour,clears*args.boss_minutes/60,args.travel_hours)
            rows.append(dict(stage=name,fee_mesos=fee,regional_units=units,drop_multiplier=args.drop_rate,
                             expected_material_kills=material_kills,kills_per_hour=args.kills_per_hour,
                             assumed_net_mesos_per_hour=net,income_sensitivity=scale,boss_clears=clears,
                             assumed_minutes_per_clear=args.boss_minutes,assumed_travel_hours=args.travel_hours,
                             **result,model_only=True))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open('w') as h:
        writer=csv.DictWriter(h,fieldnames=list(rows[0]),lineterminator='\n');writer.writeheader();writer.writerows(rows)
    for r in rows:
        if r['income_sensitivity']==1:print(r['stage'],r['fee_mesos'],round(r['total_active_hours'],2),'assumed hours')

if __name__=='__main__':main()
