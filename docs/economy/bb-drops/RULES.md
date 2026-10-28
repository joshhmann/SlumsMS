# Post-Big-Bang drop tables: rules (draft, ticket 81)

Owner decision 2026-10-28: build drop tables for the post-Big-Bang mobs that have none. This file is the rule set
`tools/economy/gen_bb_drops.py` implements. Change a rule here and in the generator together, then regenerate.
The review summary is `REVIEW.md`.

## Which mobs

- **Inventory** (`mobs.csv`, `tools/economy/bb_inventory.py`): every mob placed (Map.wz `life`, type `m`) on maps
  450-459 (Arcane River), 400-419 (Grandis), 270-273 (Temple of Time, Future and Twilight Perion) and 321-328 (Mirror
  World), with its Mob.wz level, HP, EXP and boss flag (following `info/link`) and its drop_data rows on TEST
  (`mapleroot_persist`, exported 2026-10-28). A mob placed in two regions counts in the one where it has the most
  placements.
- **Target:** every inventoried mob whose rows are none or only its Monster Book card (537 mobs). The 16 mobs on 270
  (the v83 Temple of Time) that already have classic tables are left alone.
- **Skipped (no table):** mobs with 0 EXP in Mob.wz (26: scripted soldiers, event Erdas, the Cygnus Knights in the
  Cygnus fight, Ariel/Solomon/Rex/Hugin/Munin, Mirror World training dummies with no EXP field). They give nothing
  today and keep giving nothing; see REVIEW.md open questions.

## World rates (do not bake them in)

drop_data holds 1x values; the world multiplies. SlumsMS runs EXP 3x, meso 10x, drop 4x, boss drop 3x
(divergence ledger EC-001). In `MapleMap.dropItemsFromMonsterOnMap` the drop rate multiplies **every** row's chance,
the meso row included, and the meso rate multiplies the amount. So a meso row at 400000 drops on every kill
(0.4 x 4 >= 1) and pays 10x its base range. Mesos are encoded as itemid 0 with minimum/maximum_quantity = the base
meso range; the server draws `nextInt(max - min) + min`, so max must be greater than min.

## Tiers

| Tier | Which | Table |
|---|---|---|
| normal | not boss, EXP > 0, level >= 30 | mesos + 3 potions + 5 ores + 4 scrolls + 5 equips (18 rows) |
| elite | boss flag, HP < 1,000,000,000 | mesos x4 + 3 potions + 9 ores + 6 scrolls + 6 equips, item chances x2 |
| boss | boss flag, HP >= 1,000,000,000 | Zakum-shaped, conservative (below) |
| low | level < 30 (maze pigs, garbage event mobs) | mesos + 1 low potion |
| skip | EXP 0 | no table |

The Monster Book card row (migration 045, 2%) stays as it is on every mob that has one. The generator never emits a
card item, and the migration skips any (mob, item) pair already in drop_data.

## Mesos

- Classic curve, fit on the TEST drop_data meso rows of non-boss classic mobs: midpoint = 0.085 x level² up to
  level 94, then 8 x level (Temple of Time lv 91-131 pays 780-915, Skelegons lv 110 858, Wyverns lv 97-103
  826-940). So lv 113 = 904, lv 131 = 1,048, lv 200 = 1,600 base.
- Range = midpoint -20% / +20% (classic rows are about +-20%). Chance 400000 like every classic meso row.
- Within one region and level, mobs that give more EXP pay a little more: x sqrt(EXP / median EXP of that region and
  level), clamped to 0.8-1.25. This spreads Arcane River (all level 200) from 1,024-1,536 to 1,600-2,400 base.
- Elite: 4x the normal midpoint (classic mini-bosses such as Manon pay about 5x).
- Boss: midpoint 90 x level (Zakum, lv 140, pays 10,000-15,000), chance 700000 like Zakum. Pink Bean and Horntail
  (40,000-50,000) are deliberately not the model.

## Items (only what classic mobs already drop)

`classic-pool.csv` is built from drop_data: every item a classic non-boss mob (ids 100000-8799999, outside the
target set) drops, minus anything that is cash, quest, tradeBlock, accountSharable, equipTradeBlock, timeLimited or
one-of-a-kind in the WZ, or has no String.wz name. No post-Big-Bang equipment, no custom items, no mastery or skill
books, no Chaos/White/Clean Slate scrolls (Chaos and White already come from the global drops), no mob-specific ETC.
The choice of items per mob is seeded from the mob id, so regenerating gives the same tables.

- **Potions** (classic lv 90+ set, 20000 each): Power Elixir, Mana Elixir, Elixir, Unagi, Reindeer Milk, Sunset
  Dew; 3 per mob. Low tier: White Potion, Blue Potion or Speed Potion.
- **Ores** (classic rates): 1 crystal ore (Power/Wisdom/DEX/LUK/Dark, 10000), 2 mineral or jewel ores (9000), 1 Magic
  Rock / Summoning Rock / magic powder (10000), 1 production stimulator (6000).
- **Scrolls** (classic rates): 4 from the 206 standard equipment scrolls classic mobs drop; 10%, 60% and 100% at
  300, the 30% and 70% dark scrolls at 750.
- **Equips**: 5 from the classic mob-dropped equipment with reqLevel in [top - 20, top], top = min(mob level, 108).
  108 is the highest reqLevel any classic non-boss mob drops, so every mob of level 108+ draws from the same lv
  88-108 classic set (Varr, Arnah, Katinas, Neos, Canal, Sparta, Blue Marine...). Chance = the median chance classic
  mobs drop that item at, clamped to 500-1287. Equips can still get the 1.5.2 godly roll like any mob equip.
- **Elite**: the same pools with more picks and every item chance x2.
- **Boss**: Zakum's potion rows (Elixir, Power Elixir, Reindeer Milk, Sunset Dew 999999; Mana Elixir 1-4 at
  40000), Magic Rock and Summoning Rock 7000, 6 scrolls at 13500 (Zakum's scroll rate), 8 classic lv 88-108 equips
  at 22500 (Zakum's lowest weapon rate). No boss-unique gear (no Zakum helmet, Horntail necklace, Timeless/Reverse),
  no books.

## Checks the generator and tests enforce

- Every item exists in Item.wz (Consume/Etc) or Character.wz (equips), has a name, and is not cash, quest,
  untradeable, account-bound or one-of-a-kind; every mob exists in Mob.wz (`BbDropTablesDataTest`, WZ present).
- No card item, no duplicate (mob, item), questid 0, chance 1-999999, exactly one meso row per mob with max > min.
- Migration 050 applies twice and rolls back twice on MySQL 8.0 and 8.4, leaving card rows and older rows alone
  (`BbDropTablesMigrationIT`, needs `-Dslums.family.it.url`).

## Regenerating

```
# 1. export drop_data from TEST (SELECT only) to a TSV, then
python3 tools/economy/bb_inventory.py --drops drop_data.tsv          # docs/economy/bb-drops/mobs.csv
python3 tools/economy/gen_bb_drops.py pool --drops drop_data.tsv     # docs/economy/bb-drops/classic-pool.csv
python3 tools/economy/gen_bb_drops.py gen [--regions arcane,grandis,future]   # rows.csv + migration 050 + rollback
```
`--regions` leaves a region out (e.g. drop Mirror World until it has a way in).
