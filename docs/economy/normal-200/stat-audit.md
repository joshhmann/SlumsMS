# Base gear and actual forged power

Status: base-data audit complete, with 500 matching QA-client template references. Class DPS, installed-client
rendering/behaviour and in-game survivability are not certified.
The owner asked to check base stats before changing them. No base WZ stats, requirements or existing inventories
were rewritten. The audit found no normal weapon attack regression or invalid numeric/class/slot fields in its
available equipment scope. This is not a declaration that every class is equally powerful.

The repeatable tool reads 500 available equipment templates, 80 normal weapon profiles and 112 clean forged
loadouts. It checks all 16 normal weapon types through entry, Jaihin, Utgard, Commerci and Sweetwater, and reports
attack/main stats, HP/MP, physical/magic defence, accuracy/avoid, speed/jump, slots and equip requirements.
Set bonuses stack at every earned threshold, with all-stat bonuses expanded once. The normal source manifest
separately checks the five armour families and their actual job masks.

One stale restriction-list ID, 1112406, has no retained equipment template/name. It has no forge recipe or planned
drop row and is not part of the bridge or main crafted path. It remains a reported legacy asset gap rather than
inventing an item or silently removing its restriction. Throwing stars/bullets are excluded from equipment-only
stat checks; their supply remains a separate acceptance check.

A read-only copy of the QA VM's 500 corresponding unpacked Character images was parsed with the existing
client WZ reader. All 500 matched the retained server XML over 29 base stat/requirement/slot/binding fields;
there were no missing images or parse failures. SHA-256 receipts and full differences (none) are in the task's
private `bridge-120-149/client-stat-reference/full-comparison.json`. This is a named QA snapshot comparison,
not a read of every friend's install or proof of the client's effective overlay namespace.

Commerci Polearm1442243 and Sweetwater Polearm1442242 lack an explicit `attackSpeed` field in **both** references.
The audit preserves that distinction instead of inventing a speed of zero. The inspected client line's custom
attack timing uses weapon class, and the server's matching bot timing does the same; polearms use degree3 before
party boosting. Thus missing item metadata alone does not prove a live timing bug. Tooltip/display and alternate
client/fallback behaviour still need checking. No arbitrary speed field or timing change was introduced.

## Normal weapons are upgrades, but not stock-era class balancing

| Retained weapon type | Entry (110; wand exception 98) | Jaihin 130 | Utgard 140 | Commerci 150 | Sweetwater 160 |
|---|---:|---:|---:|---:|---:|
| Two-handed sword: weapon attack | 151 | 218 | 249 | 280 | 321 |
| Staff: magic attack | 141 | 207 | 242 | 272 | 313 |
| Claw: weapon attack | 151 | 218 | 249 | 280 | 321 |

The sword's first step is about 44% more attack, followed by roughly 12–15% increases. Requirements remain explicit;
the next weapon can be collected early but cannot be worn early. These base templates improve consistently in
the selected route. They do not justify a blanket reduction just because their items came from a newer era.

The identical sword/claw attack curve is a **class-balance review flag**: job skills, attack speed, weapon
multipliers and ammo differ. Equal attack does not establish equal DPS. Class combat profiles must establish
whether the current custom normalization is appropriate; do not advertise this audit as class parity.

## Use the transferred result when balancing rebirth encounters

A permitted read of the live config found `STARFORCE_BOOST_PERCENTAGE=0.2`; the second main-stat-percentage key
was absent. The audit explicitly uses 20% attack transfer and 0% main-stat percentage, corresponding to the Java
field's zero default for the absent key. These are config/source inputs, not an in-memory JVM interrogation.
Rerun with explicit values when deployment settings differ.

Even a clean Sweetwater sword with 321 attack becomes **604 attack** when forged to Royal: 218 new base +321 old
actual attack +ceil(321×0.2)=65. This is before armour and set bonuses. The old scroll/trace upgrade counter can
add more; optional Maker bonuses and random drop stat variations are excluded from this clean comparison.

| Hero loadout | Equipment/set weapon attack total | Equipment/set STR | Equipment/set HP |
|---|---:|---:|---:|
| Royal | 662 | 246 | 1,740 |
| Lionheart | 1,063 | 354 | 2,390 |
| Amaterasu | 1,567 | 573 | 4,040 |
| AbsoLab | 2,284 | 773 | 4,540 |
| Arcane | 3,254 | 1,158 | 5,040 |
| Sealed Genesis weapon + Arcane armour | 4,358 | 1,308 | 5,040 |
| Genesis weapon + Arcane armour | 5,918 | 1,458 | 5,040 |

These are five armour pieces plus the sword with clean recursive transfers and currently declared stacked set
bonuses. They exclude character AP, skills, accessories, active buffs and optional enhancements. HP is equipment
and set HP, not a character's maximum HP. The table does not say that a fresh drop of the same ID has these stats.
Normal gear through 200 and forged gear after RB1 are intentionally different power budgets.

The tool mirrors the transfer helper: only attack/main stats and HP/MP carry, with the existing per-item short
cap; defence/accuracy/speed/jump use the new template rather than inheriting the previous piece's values. Existing
forged copies keep their actual stored stats; no retroactive inventory migration is supplied.

## Health still needs encounter calibration

Eclectic/Muspell mage armour has no raw HP; Pensalir mage armour adds 350 raw HP over five pieces. Set bonuses,
Magic Guard, MP supply and incoming client damage must be checked separately. The gear supply fix improves the
weapon/armour path; it does not establish that all level-120-accessible maps are safe.

```sh
python3 tools/economy/audit_gear_stats.py --wz /root/work/bake/wz \
  --attack-transfer 0.2 --stat-transfer 0 --output <stats-folder>
```

Outputs: base-items.csv, normal-weapon-curves.csv, clean-forged-loadouts.csv, summary.json. Unit tests cover full
attack inheritance, ceiling percentages, counter bonuses, HP/MP carry/cap and stacked sets. A Java regression
test also checks the actual helper's 321→386 transfer bonus and 604 clean Royal result. Actual client equip/display
and class combat remain in the normal-gear QA checklist; no damage or base item stat change was activated.
