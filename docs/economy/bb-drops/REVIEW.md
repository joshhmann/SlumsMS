# Post-Big-Bang drops, HP, EXP and the 120+ ladder: review for the owner (round 4, ticket 81)

The owner approved the 120+ ladder for TEST. This is round 4, made merge-ready for 1.6.1. The goal is good 120-200
training for friends at 120+. Compared with round 2:
- (round 3) The new areas' EXP is set to about 2x a classic spot per hour, on a smooth curve by level.
- (round 3) The Future and lower Grandis open at level 120 without rebirths.
- (round 3) Leafre/Minar, the Temple of Time and Ulu City get EXP x1.4.
- (round 4) Bots follow the same level-120 rule.
- (round 4) Lion King's Castle and Rose Garden get the full treatment: HP, EXP and classic-pool drop tables.
- (round 4) Migration 050 is listed in `docs/release/1.6.1.conf` and auto-QA check `t-ld-1` is added.

The drop tables and rare gear are as in round 2, and Mirror World stays out. **Nothing is applied anywhere yet**:
- This is not merged and not on TEST.
- The stat table, the gates and the script changes take effect with the jar; the drops take effect with migration
  050.

Player notes text: `PLAYER-NOTES.md`.

## Migration 055: boss drop gaps (owner 2026-10-29, branch 161-bossdrops)

050 only looked at mobs placed on maps in its regions, so the boss-fight mobs got no table. 055 adds 242 rows by the
same rules (`gen_bb_drops.py gaps`, `gap-rows.csv`), one BOSS table per clear on the form that dies and ELITE tables
for the other fighting forms (owner's second and third passes):

| Mob | Level, tier | Rows | Mesos (base) | Rare outclassing piece |
|---|---|---|---|---|
| Cygnus 8850012 (the form that dies) | 190, boss | 30 | 13,680-20,520 at 70% | Utgard Crossbow 3000 (0.9%) |
| Guards Mihile, Oz, Irena, Eckhart, Hawkeye 8850000-8850004 | 188, elite | 32 each | 4,813-7,219 at 40% | one each at 120 (30 for AbsoLab) |
| Castellan Toad 9400409 (the form that dies) | 180, elite (450M HP) | 28 (27 new: its meso row stays) | 4,608-6,912 (kept: 10,000-20,000) | none (classic pool) |
| Lantern Erda 8641008 | 200, normal | 24 | 1,024-1,536 | Pensalir Mage Gloves 60 |

Checked in the server code, and changed:
- **Cygnus 8850012 is the kill:** CygnusBattle.isCygnus is 8850012, and it dies through `killMonster(..., true)`.
  8850011 is a stub: `spawnCygnusMobs` kills it with `withDrops=false` each time a guard comes in.
- **The guards that fight are 8850000-8850004** (`8850000 + cygnusSpawnCounter`). Their deaths went through
  `afterBossKilled` straight to `killAllMonsters()` (`withDrops=false`), so they dropped nothing. Since 1.6.1 the guard
  first dies through `killMonster(guard, chr, true)` (its drops, its EXP, the event's monsterKilled), then the
  drop-free field-clear and Cygnus' return run as before (`CygnusGuardDropsTest`). A full Cygnus clear rolls five elite
  tables and one boss table. 8850006-8850009 are never spawned and get no table.
- **Castellan:** one table per clear, on the Toad. Castellan 9400408 does die with drops before the Toad comes, but
  has no rows (owner's choice).

## Round 6 (owner 2026-10-29, branch 161-ladder3)

The owner's decisions on round 5:
- **The Future is trimmed to about 3x the buffed Temple of Time** (it was ~3.9x): its EXP target is x0.75. Example:
  Official Knight D (lv 174) 56,700 -> 42,500 EXP a kill.
- **Leafre of the Past opens** as the Gate of Time's third choice (Crack in Time, 272000000), behind the same level-120
  gate, so the Future starts at level 120 there. Bots: a "Gate of Time" transit row to it, Crack in Time as a hub of
  "The Future" region, and 6 path entries at 120-140 (2 at 140-160); all 30 maps of the 120-140 band are reachable for a
  rebirth-0 bot at level 120 (LevelPathSweepCheck).
- **The Corrupted mobs at their right EXP:** now that people can reach them they are paid like lower Grandis at
  lv 124 (x1.55 instead of the Future trim): 14,800 EXP a kill (Mob.wz 2,051), 55.4M an hour, 1.05x the lower-Grandis
  median. Round 4's 9,550 (36M) would have been well below lower Grandis at the same level; with the Future trim alone
  it would have been ~27M, the Temple's rate.
- **Rose Garden walk-in fixed:** `pt_rosegarden.js` (Fifth Tower roof -> Gardener's Spot) and `pt_rosegardenout.js`
  (back) existed in the WZ portals only. The Fifth Tower is still behind the tower keys; the Hyper Rock also goes in.
- Also fixed: the generators read the first `level` in a Mob.wz file, which for mobs with skills was the skill's
  level (Corrupted Skelegon and Cornian read as lv 4 and 5). No gate or stat row changed; the ladder's Future row now
  counts 56 maps instead of 46 (its 100.7M before the trim, not 105.4M).

The ladder now (`tools/economy/ladder_table.py`, same model as round 5; Leafre of the Past is shown on its own and left
out of the Future row):

| Area | Strongest mob per map | Maps | EXP/hour | x buffed Temple of Time (Road to Oblivion) |
|---|---|---|---|---|
| Ulu City (Singapore) | 90-115 | 8 | 27.1M | 1.04x |
| Leafre / Minar Forest | 93-113 | 24 | 18.7M | 0.71x |
| Temple of Time, Road of Regrets | 106-116 | 5 | 24.6M | 0.94x |
| Temple of Time, Road to Oblivion | 121-131 | 5 | 26.1M | 1.00x |
| Lion King's Castle | 117-127 | 13 | 34.0M | 1.30x |
| Rose Garden | 128-134 | 6 | 42.4M | 1.62x |
| Lower Grandis | 109-142 | 110 | 52.6M | 2.01x |
| Leafre of the Past (Future, Gate of Time) | 124-124 | 6 | 55.4M | 2.12x |
| Future / Twilight Perion | 109-198 | 50 | 78.4M | 3.00x |
| Upper Grandis | 114-200 | 113 | 153.4M | 5.87x |
| Arcane River | 200-200 | 272 | 155.8M | 5.96x |

Before this round the Future row read 100.7M (3.85x) with the corrected levels, Leafre of the Past 35.7M (0.68x lower
Grandis), and every other row as above. Lower Grandis 2.0x < Future 3.0x < upper Grandis/Arcane 5.9-6.0x: it still
climbs as the areas get higher.

## Round 5 (1.6.1 b039-b041, branch 161-ladder2): the notes are now true

The 1.6.1 audit (REPORT-small items 14-19) found five places where the player notes promised more than the build
did. The owner's rule is to build what the notes say, so:
- **Deeper Grandis stays rebirth 3.** Six maps the notes call deeper were open at level 120: the Fox Tree (410000002),
  the Heliseum Hideout (400020300), Heliseum's Transitional Dimensional Door (401000002) and Savage Terminal's hidden
  back streets (402000400-402000402). `gen_area_gates.py` now keeps every Heliseum / Fox Tree / Cernium map and the
  back streets out; lower Grandis is 181 maps. The 9 portals from lower Grandis into closed maps are all unscripted, so
  `GenericPortal` checks each one (`LowerGrandisPortalsWzTest`).
- **The Gate of Time reaches Grandis.** It opens a menu: the Future, or lower Grandis at Pantheon (400000000), both
  behind the level-120 gate. Bots get a "Gate of Time" row to Pantheon.
- **Leafre/Minar, the Temple of Time and Ulu City give 40% more EXP for every mob**, not only the 25 of lv 95+: 53
  mobs now, Beetles, Harps, Kentauruses, Eyes of Time, Manon/Griffey, Dodo/Lilynouch/Lyka and Ulu's Berserkie and
  Veetron included.
- **Lower Grandis pays about 2x the buffed Temple of Time.** Its EXP target is x1.55 (78 mobs, lv 109-142).
- **Lion King's Castle needs no rebirth.** Only Von Leon's wing (211070000-211070550) keeps rebirth 1 by Hyper Rock and
  for bots; the towers past Walls 1 keep their keys and quests.
- **Bots:** TrainingRegions has "Lower Grandis" and "The Future" (from level 120); the path has 15 lower-Grandis maps
  at 120-140 and 8 more (plus 5 Future maps) at 140-160.

The ladder now (same model as below: 141M damage an hour, at most 1,500 kills an hour, EXP 3x; per area the median of
its training maps, a map being the mean of its mob placements; `tools/economy/ladder_table.py`; "before" is release
1.6.1 as merged):

| Area | Strongest mob per map | Maps | EXP/hour before -> after | x buffed Temple of Time (Road to Oblivion) |
|---|---|---|---|---|
| Ulu City (Singapore) | 90-115 | 8 | 27.1M -> 27.1M | 1.04x -> 1.04x |
| Leafre / Minar Forest | 93-113 | 24 | 18.7M -> 18.7M | 0.72x -> 0.71x |
| Temple of Time, Road of Regrets | 106-116 | 5 | 24.3M -> 24.6M | 0.94x -> 0.94x |
| Temple of Time, Road to Oblivion | 121-131 | 5 | 25.9M -> 26.1M | 1.00x -> 1.00x |
| Lion King's Castle | 117-127 | 13 | 34.0M -> 34.0M | 1.31x -> 1.30x |
| Rose Garden | 128-134 | 6 | 42.4M -> 42.4M | 1.64x -> 1.62x |
| Lower Grandis | 109-142 | 109 | 34.0M -> 52.6M | 1.31x -> 2.01x |
| Future / Twilight Perion | 109-198 | 46 | 105.4M -> 105.4M | 4.07x -> 4.03x |
| Upper Grandis | 114-200 | 108 | 151.8M -> 151.8M | 5.86x -> 5.81x |
| Arcane River | 200-200 | 272 | 155.8M -> 155.8M | 6.01x -> 5.96x |

Notes on the table:
- The Temple of Time moves a little (24.3 -> 24.6M, 25.9 -> 26.1M) because its Eyes of Time (lv 80) now get x1.4 too.
  Ulu and Leafre's medians do not move: their median maps were already all lv 95+ mobs.
- The Future / Twilight Perion figure is 105.4M here (round 4 said 99.8M over 60 maps); the tool counts only maps
  whose strongest mob is lv 90+, which drops 14 filler and event maps. Its EXP is unchanged (the owner is deciding).
- **The four Corrupted mobs** (Wyvern, Cornian, Skelegon, Kentaurus, 8220016-8220019, lv 124, EXP 2,051 -> 9,550 =
  x4.66) are not a mistake of the rule: they are Future-region mobs, so they get the new-area target for lv 124 (36M an
  hour), and Nexon gave them a very low base EXP (2,051 against a classic lv-124 mob's ~3,000), which makes the
  multiplier look large. They live only on Leafre of the Past (272000100-272000300), which no portal or script reaches
  and the Hyper Rock cannot land on (CANNOTVIPROCK), so today nobody, player or bot, can fight them. Round 4 labelled
  them Twilight Perion; they are not. They are left as they are.
- **The Future from about lv 124 for bots is not possible as things stand:** the Future's weakest maps a player or bot
  can reach have lv 162+ mobs (Henesys Ruins, Dark Ereve); the lv 124 ones are Leafre of the Past above. Bots take the
  Future from about lv 147 (the path's maxAbove of 15). Opening Leafre of the Past (for example as a third Gate of Time
  destination) would give the Future a lv 124 entry; that is the owner's call, since the Future is on hold.


Files: rules in `RULES.md`, drop rows in `rows.csv`, HP and EXP changes in `stats.csv`, lower Grandis map list in
`src/main/resources/soloMapling/lower-grandis-maps.txt`, inventory in `mobs.csv`.

## In one paragraph

350 mobs in Arcane River, Grandis, Future/Twilight Perion, Lion King's Castle and Rose Garden get a drop table,
8,011 rows in all. Each normal mob in the newer areas gets classic level-scaled mesos, 3 potions, 5 ores, its own ETC
where it has a use, 4 scrolls, 2 mastery books, 5 classic equips, 2 on-par post-Big-Bang pieces and 1 rare
outclassing piece. Lion King's Castle and Rose Garden draw from the classic pool only, with no post-Big-Bang gear.

HP and EXP:
- 340 of these mobs get classic-curve HP.
- 340 normal mobs get new EXP: EXP per hour is about 2x the classic Temple of Time spot at lv 131 and grows with
  level (lv 120 = 32M an hour, lv 150 = 64M, lv 200 = 156M).
- 25 classic mobs (Leafre/Minar, Temple of Time, Ulu City) get EXP x1.4.

Access:
- The Future and 187 lower Grandis maps (lv 109-142) open at level 120, for players and bots.
- Upper Grandis and Arcane River stay rebirth 3.

For the same character (the damage it takes to kill 1,000 Chief Oblivion Guardians an hour):
- the classic spots pay 20-28M EXP an hour (was 14-20M);
- Lion King's Castle and Rose Garden pay 34-42M (was 3-10M);
- lower Grandis pays 34M;
- the Future pays 100M;
- upper Grandis and Arcane pay 156M.

## The 120+ training ladder

Model:
- The same character everywhere: 141M damage an hour, the damage it takes to kill 1,000 Chief Oblivion Guardians an
  hour.
- Maps capped at 1,500 kills an hour.
- World rates applied (EXP 3x, mesos 10x).
- Per area, the median over its training maps, each map averaged over its mob placements.

A stronger character earns more everywhere; the model is for comparing areas.

| Area | Mob levels | EXP/hour before -> after | Mesos/hour before -> after | Gate | Maps |
|---|---|---|---|---|---|
| Ulu City (Singapore) | 95-115 | 19.9M -> 27.9M | 10.9M | none | 6 |
| Leafre / Minar Forest | 93-113 | 14.2M -> 19.8M | 13.6M | none | 22 |
| Temple of Time, Road of Regrets | 106-116 | 17.6M -> 24.3M | 11.6M | none | 5 |
| Temple of Time, Road to Oblivion | 121-131 | 18.7M -> 25.9M | 9.2M | none | 5 |
| **Lion King's Castle** | 117-127 | 10.1M -> **34.0M** | 0 -> 14.0M | none on foot; Hyper Rock and bots need rebirth 1 | 13 |
| **Rose Garden** | 128-134 | 2.8M -> **42.4M** | 0 -> 12.2M | none | 6 |
| **Lower Grandis** (Pantheon, Sanctuary, Refuge, Fox Ridge, Cheong-woon, Ristonia, Toolen) | 109-142 | 2.1M -> **34.0M** | 0 -> 13.7M | **level 120** (or rebirth 3) | 110 |
| **Future / Twilight Perion** | 124-198 (plus a few low event mobs) | 2.0M -> **99.8M** | 0 -> 6.0M | **level 120** (or rebirth 2) | 60 |
| Upper Grandis (Heliseum, Savage Terminal back streets, Fox Tree, Cernium) | 175-200 | 2.1M -> 153.4M | 0 -> 4.7M | rebirth 3 | 113 |
| Arcane River | 200 | 1.3M -> 155.8M | 0 -> 4.4M | rebirth 3 | 272 |

The "before" columns are today's HP, EXP and drop_data. The newer areas and Rose Garden had no meso rows, and the
Lion King's Castle mobs only their card. (Round 3's table showed non-zero "before" mesos for the new areas; that was
a calculation slip: it counted the new meso rows.) The ToT rows include its 7 Eyes of Time (lv 80)
per map.

Reading it:
- A lv 120 friend can go to lower Grandis or Lion King's Castle (34M an hour, about 1.4x the buffed Temple of Time).
- Rose Garden (lv 128-134) pays 42M.
- From about lv 160 the Future pays 100M.
- With 3 rebirths, upper Grandis and Arcane pay 156M.

Hours per level at the curve's target:

| Level | Hours |
|---|---|
| 120 | 0.7 |
| 130 | 1.0 |
| 140 | 1.3 |
| 150 | 1.8 |
| 160 | 2.6 |
| 170 | 3.6 |
| 180 | 5.2 |
| 190 | 7.5 |
| 199 | 10.5 |

Mesos are lower than classic per hour in the lv 160+ areas: their mesos per kill are by level, as you chose, and
their kill rate is lower.

## Totals

| Region | Target mobs | Tables | Normal / elite / boss / low | 0 EXP (nothing) | Drop rows | HP rows | EXP rows | Mesos per kill, in game (normal) |
|---|---|---|---|---|---|---|---|---|
| Arcane River 450-459 | 160 | 146 | 145 / 0 / 1 / 0 | 14 | 3,511 | 145 | 145 | 10,240-24,000 |
| Grandis 400-419 | 145 | 140 | 135 / 1 / 2 / 2 | 5 | 3,103 | 138 | 135 | 6,980-23,880 |
| Future / Twilight Perion 271-273 | 54 | 49 | 46 / 0 / 0 / 3 | 5 | 1,085 | 42 | 46 | 7,940-20,690 |
| Lion King's Castle 211060xxx / Rose Garden 211080xxx | 15 | 15 | 14 / 1 / 0 / 0 | 0 | 312 | 15 | 14 | 6,080-15,840 |
| **Total** | **374** | **350** | 340 / 2 / 3 / 5 | **24** | **8,011** | **340** | **340** | |
| Classic spots (Leafre/Minar, Temple of Time, Ulu) | | | | | 0 | 0 | 25 (x1.4) | unchanged |
| Mirror World 321-328 (left out) | 178 | 0 | `--regions all` adds 176 tables | 2 | 0 | 0 | 0 | |

Of the 350, 252 keep their card row and 98 had no rows at all (today they drop nothing, not even global drops).

Rows by kind:

| Kind | Rows |
|---|---|
| Mesos | 350 |
| Potions | 1,046 |
| Ores, rocks and stimulators | 1,724 |
| Own and region ETC | 194 |
| Scrolls | 1,390 |
| Mastery books | 698 |
| Classic equips | 1,736 |
| Post-Big-Bang gear, par | 614 |
| Post-Big-Bang gear, rare | 259 |

## HP: before and after

The new HP follows a classic curve for the mob's level: lv 109 = 63k, lv 131 = 119k, lv 170 = 290k, lv 200 = 507k.
Within a level it keeps each mob's relative toughness (0.75-1.5x). Only mobs more than 1.25x over their new value
change. "Before" is what the server uses today, after LifeFactory's halving of non-boss lv 160+ mobs and its
hard-coded boss HP.

| Region | Normal mobs changed | Server HP before (min / median / max) | After (min / median / max) | Divided by (min / median / max) |
|---|---|---|---|---|
| Arcane River | 145 | 22.3M / 132M / 339M | 380k / 507k / 760k | 59 / 261 / 446 |
| Grandis | 135 | 2.18M / 2.6M / 1.07B | 63k / 116k / 722k | 8 / 25 / 2,118 |
| Future / Twilight Perion | 42 of 46 | 350k / 6.67M / 28.5M | 209k / 346k / 490k | 1.4 / 23 / 70 |
| Lion King's Castle / Rose Garden | 14 | 252k / 456k / 4.17M | 60k / 93k / 162k | 4 / 5 / 33 |

| Elite and bosses | Before | After |
|---|---|---|
| Sky Guardian (Grandis, elite, lv 200) | 446,400,000 | 15,200,000 (30x the curve) |
| Red Crocky (Lion King's Castle, elite, lv 115) | 9,500,000 | 2,280,000 (30x the curve) |
| Cygnus (lv 140) | 2,100,000,000 | 110,000,000 (Zakum's body) |
| Magnus (lv 190) | 200,000,000,000 (hard-coded in LifeFactory) | 2,100,000,000 (Pink Bean) |
| Singularity Maker (lv 200) | 2,147,483,647 | unchanged (already Pink Bean scale) |

## EXP: before and after (round 3)

How EXP per kill is set (normal mobs only; elite and boss EXP unchanged):
- The target is 2 x 21.18M x classicEXP(level) / classicEXP(131) EXP an hour.
- EXP per kill = target / (3 x kills per hour at the new HP).
- It is never below the classic EXP curve for that level (0.00107394 x level^3.08225).

| Region | Normal mobs | EXP per kill before (min / median / max) | After | EXP/hour before (median) | After (min / median / max) |
|---|---|---|---|---|---|
| Arcane River | 145 | 98,708 / 401,055 / 721,247 | 140,000 / 187,000 / 280,000 | 1.3M | 156M / 156M / 157M |
| Grandis | 135 | 10,900 / 13,000 / 1,828,409 | 5,340 / 11,300 / 254,000 | 2.1M | 24M / 41M / 156M |
| Future / Twilight Perion | 46 | 2,051 / 25,550 / 74,577 | 9,550 / 83,800 / 175,000 | 2.0M | 36M / 102M / 151M |
| Lion King's Castle / Rose Garden | 14 | 3,806 / 8,520 / 17,733 | 6,640 / 9,190 / 16,600 | 9.1M | 30M / 39M / 45M |

| Mob (region, level) | HP before -> after | EXP per kill before -> after | Kills/hour after | EXP/hour before -> after | Mesos/hour after |
|---|---|---|---|---|---|
| Happy Erda (Arcane, 200) | 22.3M -> 380k | 98,708 -> 140,000 | 371 | 1.9M -> 156M | 4.75M |
| Sun Spirit (Arcane, 200, median) | 132M -> 507k | 401,055 -> 187,000 | 278 | 1.3M -> 156M | 4.45M |
| Embrion (Arcane, 200, toughest) | 339M -> 760k | 721,247 -> 280,000 | 186 | 0.9M -> 156M | 3.71M |
| Green Grossular (Grandis, 109) | 2.18M -> 63k | 10,900 -> 5,340 | 1,500 | 2.1M -> 24M | 13.1M |
| Specter Stray Dog (Grandis, 130, median) | 2.6M -> 116k | 13,000 -> 11,300 | 1,216 | 2.1M -> 41M | 12.6M |
| Ebonstar Magician (Grandis, 200) | 1.07B -> 507k | 1,828,409 -> 187,000 | 278 | 0.7M -> 156M | 4.58M |
| Corrupted Wyvern (Twilight Perion, 124) | 113k (unchanged) | 2,051 -> 9,550 | 1,248 | 7.7M -> 36M | 12.4M |
| Official Knight D (Future, 174, median) | 5.70M -> 236k | 23,495 -> 56,700 | 597 | 1.7M -> 102M | 6.65M |
| Ancient Mixed Golem (Future, 198) | 14.0M -> 490k | 64,640 -> 175,000 | 288 | 2.0M -> 151M | 4.56M |

Classic spots, EXP x1.4 (25 mobs, `stats.csv`; round 4 moved Lion King's Castle and Rose Garden out of this list
and onto the full treatment):
- Temple of Time Memory Guardian to Chief Oblivion Guardian (lv 98-131, 10 mobs).
- Leafre and Minar Wyverns, Cornians, Rexton/Brexton, Newties, Skelegons, Skelosaurus, Nest Golem (lv 95-113,
  11 mobs).
- Ulu City (lv 95-115, 4).

Chief Oblivion Guardian goes 7,060 -> 9,884, so the reference spot itself now pays 29.7M an hour. The new areas'
targets are still set from the old 21.2M, and they stay above it: lower Grandis 34M at lv 120-140.

The server has no per-map spawn override (only an integer multiplier nothing sets), so density is not changed.

Lion King's Castle and Rose Garden (round 4): their mobs had 250k-4M HP at lv 117-134 and no meso drops (LKC: card
only; Rose Garden: nothing). They now go through the same rules as the newer areas:
- HP onto the classic curve (60k-162k);
- EXP for about 2x the classic EXP per hour for their level (30-45M);
- drop tables from the classic pool: mesos, potions, ores, own ETC where it has a use (Reindeer's Spear, Bearwolf's
  Morning Star, Vulture's Shoulder Accessory, Castle Golem Brick), scrolls, mastery books and classic equips. No
  post-Big-Bang gear.

Red Crocky (lv 115, boss flag) is an elite: 2.28M HP, its EXP is kept.

## Area gates (rounds 3 and 4)

| Area | Before | Now |
|---|---|---|
| Future / Twilight Perion 271000000-273999999 | rebirth 2 (Hyper Rock, Gate of Time); rebirth 3 (Henesys teleporter) | **level 120**, or rebirth 2 |
| Lower Grandis (187 maps below) | rebirth 3 | **level 120**, or rebirth 3 |
| Upper Grandis and Arcane River | rebirth 3 | rebirth 3 (unchanged) |

- **The rule** lives in `server.maps.AreaGates`.
- **Where it is checked:**
  - the Hyper Rock (`HyperTPHandler`);
  - the Henesys teleporter, per destination now instead of per section (`scripts/npc/9010002.js:31`);
  - the Temple of Time's Gate of Time (`scripts/portal/270000000.10.js`, was rebirth 2 only);
  - **every unscripted portal** (`GenericPortal`). There was no server-side map-entry check before. This is what
    stops a level-120 player walking from lower into upper Grandis. The 9 boundary portals (round 5) are all
    unscripted:
    - Pantheon -> Transitional Dimensional Door (Heliseum)
    - West Pantheon Plains -> Heliseum Hideout
    - Fox Point Village -> Fox Tree
    - Brigand's Row -> Back Alley; Waste Treatment Plant 3 -> Desolate Scrapyard; Shaded Black Market -> Desolate
      Black Market (hidden portals)
    - Mountain of Trash -> Seedy Scrapyard (lv 175)
    - Nutria Gutter 2 -> Sketchy Back-Alley (lv 178)
    - Brawl 'n' Bash Club Entrance -> Dingy Brawl 'n' Bash Club (lv 181)
- **Unchanged:** the Arcane River portal (`arcaneriver.js`, rebirth 3) and the boss-entry portals.
- **GMs** pass every gate.
- **Bots (round 4)** follow the same rule. `BotMapGates.mayEnter(map, rebirths, level)` asks AreaGates; it is used
  by bot map choice (TrainingMapFinder, TrainingTravel, LevelingPathChooser), routing (GCWorldGraph, with the transit
  level tier) and the Hyper Rock (GCTravel), world events and TrainingBot. The Henesys teleporter's rows in
  `BotTransit.yaml` for the Future and lower Grandis towns now need level 120 instead of 3 rebirths. A row is AND-only,
  so a low-level 3-rebirth bot walks there instead of taking the teleporter. Upper Grandis (Heliseum) and Arcane rows
  still need 3 rebirths. Callers that do not know a level keep the strict rebirth rule.
- **Lower Grandis map ids** (generated from Map.wz by `tools/economy/gen_area_gates.py`, 187 maps):
  400000000-400000001, 400010000, 400010100, 400010300, 400010400, 400020000, 400020100, 400020200, 400020300,
  400030100, 400030300, 400030400, 400030500, 400040000, 400040100, 400040200, 400040300, 401000002,
  402000000-402000003, 402000100, 402000110-402000112, 402000120-402000123, 402000200, 402000210,
  402000220-402000221, 402000300-402000302, 402000310-402000311, 402000320-402000323, 402000400-402000402,
  402000500-402000530, 402000600, 402000610-402000618, 402000620-402000622, 402000630-402000635,
  402000640-402000648, 410000000-410000002, 410000010-410000012, 410000020-410000022, 410000030-410000031,
  410000040-410000041, 410000050-410000051, 410000200-410000201, 410000210-410000213, 410000220-410000222,
  410000230, 410000240-410000244, 410000250-410000251, 410000300, 410000310-410000314, 410000320-410000325,
  410000330-410000333, 410000340-410000345, 410000350-410000352, 410000355-410000357, 410000401-410000412,
  410000415-410000423.
- **How the list is built:** lower = maps whose strongest mob is lv 142 or less (Grandis has no mobs between 143 and
  174), plus towns and interiors that do not sit only next to upper maps. The Heretic Temple (400030600-603) has no
  plain portal from the lower maps, so it stays rebirth 3.

### What the client sees (stats and gates)

- **Server.** `server.life.MobStatOverrides` reads `src/main/resources/soloMapling/mob-stat-overrides.tsv` (mob id,
  HP or `-`, EXP or `-`, comment; 368 rows). It ships inside the jar. LifeFactory applies it last. The WZ files are
  not patched. `-Dslums.mobStatOverrides=false` turns it off.
- **Client: no change needed for play.**
  - A normal mob's HP bar is the percentage the server sends (SHOW_MONSTER_HP).
  - A boss bar is the server's HP (`showBossHP`).
  - EXP and kills are the server's.
  - A refused portal shows a message and the player stays put.
- **Client: the Monster Book still shows the old HP.** `tools/monsterbook/build_book.py --hp-overrides <tsv>`
  writes the new HP into the book. It only shows after a Custom.wz rebuild and client release.
- **Not tested in a real client.** In particular, the portal refusal has not been seen in game; it should get a TEST
  check with a level-119 and a level-120 character.

Items add about 0.5 drops a kill on top (median), mostly potions and ores. The 38 mobs with their own ETC drop up to
2.3 items a kill.

## Sample tables

Chances are drop_data values (out of 1,000,000). The percentage is the in-game chance after the 4x drop rate (3x for
bosses). Every mob also keeps the global drops. The EXP in each header is the Mob.wz value; the new EXP is in
`stats.csv` and the tables above.

### Arcane River
**Happy Erda** (8641000, lv 200, HP 22,320,000 -> 380,000, EXP 98,708, normal, card row kept: yes)
- Mesos 1,024-1,536 base = 10,240-15,360 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Mana Elixir 20000 (8%)
- Ores: DEX Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); Black Crystal Ore 9000 (3.6%); Magic Powder (Purple) 10000 (4%); Shield Production Stimulator 6000 (2.4%)
- Own/region ETC: Arcane River Droplet Stone 10000 (4%)
- Scrolls: Scroll for Helmet for HP 10% 300 (0.12%); Scroll for Earring for INT 10% 300 (0.12%); Scroll for Cape for HP 10% 300 (0.12%); Scroll for Two-Handed BW for Accuracy 10% 300 (0.12%)
- Mastery books: [Mastery Book] Dragon's Breath 20 500 (0.2%); [Mastery Book] High Defense 20 1500 (0.6%)
- Classic equips: Green Canal Hood 1287 (0.515%); Dark Katinas 700 (0.28%); Purple Valkyrie Skirt 800 (0.32%); Red Martier 1000 (0.4%); Magicodar 700 (0.28%)
- Post-Big-Bang gear: Muspell Thief Hat 800 (0.32%); Muspell Warrior Suit 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): AbsoLab Piercing Spear 15 (0.006%)

**Sun Spirit** (8644001, lv 200, HP 132,308,000 -> 507,000, EXP 401,055, normal, card row kept: yes)
- Mesos 1,280-1,920 base = 12,800-19,200 in game, every kill
- Potions: Power Elixir 20000 (8%); Mana Elixir 20000 (8%); Unagi 20000 (8%)
- Ores: Wisdom Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); Black Crystal Ore 9000 (3.6%); Magic Powder (Blue) 10000 (4%); Dagger Forging Stimulator 6000 (2.4%)
- Own/region ETC: Arcane River Droplet Stone 10000 (4%)
- Scrolls: Scroll for Helmet for HP 60% 300 (0.12%); Scroll for Bottomwear for DEX 10% 300 (0.12%); Scroll for Two-Handed Axe for Accuracy 30% 750 (0.3%); Scroll for Claw for ATT 60% 300 (0.12%)
- Mastery books: [Mastery Book] Piercing Arrow 20 500 (0.2%); [Mastery Book] High Mastery 20 1500 (0.6%)
- Classic equips: Green Varr Hat 1287 (0.515%); Blue Commodore 800 (0.32%); Dark War Greave 800 (0.32%); Dark Ades Shoes 800 (0.32%); Concerto 500 (0.2%)
- Post-Big-Bang gear: Muspell Bowman Hat 800 (0.32%); Muspell Bowman Suit 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): AbsoLab Blade Lord 15 (0.006%)

**Embrion** (8645022, lv 200, HP 338,881,400 -> 760,000, EXP 721,247, normal, card row kept: none)
- Mesos 1,600-2,400 base = 16,000-24,000 in game, every kill
- Potions: Elixir 20000 (8%); Mana Elixir 20000 (8%); Sunset Dew 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Gold Ore 9000 (3.6%); Garnet Ore 9000 (3.6%); Magic Powder (Brown) 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Own/region ETC: Arcane River Droplet Stone 10000 (4%)
- Scrolls: Dark Scroll for Face Accessory for HP 70% 750 (0.3%); Scroll for Topwear for HP 100% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 30% 750 (0.3%); Scroll for Two-Handed Axe for Accuracy 60% 300 (0.12%)
- Mastery books: [Mastery Book] Paralyze 20 500 (0.2%); [Mastery Book] Triple Throw 30 500 (0.2%)
- Classic equips: Blue Canal Hood 1287 (0.515%); Blue Arnah Cap 1287 (0.515%); Green Varuna 700 (0.28%); Red Varr Shoes 800 (0.32%); Blue Marine 700 (0.28%)
- Post-Big-Bang gear: Muspell Warrior Hat 800 (0.32%); Muspell Warrior Suit 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Pensalir Battle Boots 60 (0.024%)

### Grandis
**Green Grossular** (2400400, lv 109, HP 2,180,000 -> 63,200, EXP 10,900, normal, card row kept: yes)
- Mesos 698-1,046 base = 6,980-10,460 in game, every kill
- Potions: Power Elixir 20000 (8%); Mana Elixir 20000 (8%); Sunset Dew 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Amethyst Ore 9000 (3.6%); Emerald Ore 9000 (3.6%); The Magic Rock 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Topwear for HP 60% 300 (0.12%); Scroll for Cape for INT 60% 300 (0.12%); Scroll for Two-Handed Sword for Accuracy 10% 300 (0.12%); Scroll for Two-handed Axe for ATT 60% 300 (0.12%)
- Mastery books: [Mastery Book] Power Stance 20 500 (0.2%); [Mastery Book] Concentrate 30 500 (0.2%)
- Classic equips: Red Osfa Hat 1287 (0.515%); Blue Valhalla Helmet 1287 (0.515%); Blue Sage 850 (0.34%); Silver Kalkan 700 (0.28%); The Peacemaker 500 (0.2%)

**Specter Stray Dog** (2400307, lv 130, HP 2,600,000 -> 116,000, EXP 13,000, normal, card row kept: yes)
- Mesos 832-1,248 base = 8,320-12,480 in game, every kill
- Potions: Mana Elixir 20000 (8%); Reindeer Milk 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Bronze Ore 9000 (3.6%); Garnet Ore 9000 (3.6%); Magic Powder (Purple) 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Topwear for HP 100% 300 (0.12%); Scroll for Topwear for HP 60% 300 (0.12%); Scroll for One-Handed BW for Accuracy 60% 300 (0.12%); Scroll for Spear for Accuracy 30% 750 (0.3%)
- Mastery books: [Mastery Book] Mana Reflection 20 500 (0.2%); [Mastery Book] Time Leap 30 500 (0.2%)
- Classic equips: Red Ades 700 (0.28%); Blue Oriental Fury Coat 700 (0.28%); Green Oriental Fury Coat 700 (0.28%); Blue Czar 700 (0.28%); Purple Valkyrie Skirt 800 (0.32%)
- Post-Big-Bang gear: Eclectic Hermosa 800 (0.32%); Muspell Pirate Gloves 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Jaihin Wand 60 (0.024%)

**Ebonstar Magician** (8645130, lv 200, HP 1,073,741,823 -> 507,000, EXP 1,828,409, normal, card row kept: none)
- Mesos 1,318-1,976 base = 13,180-19,760 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Reindeer Milk 20000 (8%)
- Ores: Dark Crystal Ore 10000 (4%); Steel Ore 9000 (3.6%); AquaMarine Ore 9000 (3.6%); The Summoning Rock 10000 (4%); One-Handed Blunt Weapon Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Earring for INT 60% 300 (0.12%); Scroll for Overall Armor for DEX 100% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 100% 300 (0.12%); Scroll for Dagger for ATT 10% 300 (0.12%)
- Mastery books: [Mastery Book] Genesis 30 750 (0.3%); [Mastery Book]High Mastery 30 1500 (0.6%)
- Classic equips: Dark Valhalla Helmet 1287 (0.515%); Green Osfa Suit 800 (0.32%); Dark Anes 700 (0.28%); Green Sage 1000 (0.4%); Casa Crow 500 (0.2%)
- Post-Big-Bang gear: Muspell Magician Hat 800 (0.32%); Muspell Pirate Gloves 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Jaihin Two-handed Axe 60 (0.024%)

Elite:

**Sky Guardian** (2400259, lv 200, HP 446,400,000 -> 15,200,000, EXP 82,257, elite, card row kept: none)
- Mesos 5,120-7,680 base = 51,200-76,800 in game, every kill
- Potions: Elixir 40000 (16%); Power Elixir 40000 (16%); Reindeer Milk 40000 (16%)
- Ores: Wisdom Crystal Ore 20000 (8%); LUK Crystal Ore 20000 (8%); Adamantium Ore 18000 (7.2%); Amethyst Ore 18000 (7.2%); Diamond Ore 18000 (7.2%); Magic Powder (Brown) 20000 (8%); Magic Powder (White) 20000 (8%); Dagger Forging Stimulator 12000 (4.8%); Claw Production Stimulator 12000 (4.8%)
- Scrolls: Scroll for Topwear for STR 100% 600 (0.24%); Scroll for Gloves for DEX 10% 600 (0.24%); Scroll for Cape for INT 100% 600 (0.24%); Scroll for Pole Arm for ATT 100% 600 (0.24%); Scroll for Claw for ATT 10% 600 (0.24%); Scroll for Knuckle for ATT 10% 600 (0.24%)
- Mastery books: [Mastery Book] Berserk 30 1000 (0.4%); [Mastery Book] Shadow Shifter 20 1000 (0.4%); [Mastery Book] Combo Barrier 30 3000 (1.2%)
- Classic equips: Green Valhalla Helmet 2574 (1.03%); Green Lucida 1600 (0.64%); Red Katinas 1400 (0.56%); Blue Arzuna 1400 (0.56%); Black Duke Barkin Shoes 1600 (0.64%); Green Focus 2000 (0.8%)
- Post-Big-Bang gear: Muspell Warrior Shoes 1600 (0.64%); Muspell Warrior Gloves 1600 (0.64%); Muspell Magician Gloves 1600 (0.64%)
- Post-Big-Bang gear, outclassing (rare): AbsoLab Piercing Spear 30 (0.012%)

Boss:

**Cygnus** (8850111, lv 140, HP 2,100,000,000 -> 110,000,000, EXP 2,040,000, boss, card row kept: none)
- Mesos 10,080-15,120 base = 100,800-151,200 in game, every kill
- Potions: Elixir 999999 (100%); Power Elixir 999999 (100%); Reindeer Milk 999999 (100%); Sunset Dew 999999 (100%); Mana Elixir x1-4 40000 (12%)
- Ores: The Magic Rock 7000 (2.1%); The Summoning Rock 7000 (2.1%)
- Scrolls: Scroll for Helmet for HP 100% 13500 (4.05%); Scroll for Helmet for INT 10% 13500 (4.05%); Scroll for Overall Armor for DEF 10% 13500 (4.05%); Scroll for Gloves for ATT 10% 13500 (4.05%); Scroll for Cape for Weapon DEF 60% 13500 (4.05%); Scroll for Two-handed Axe for ATT 10% 13500 (4.05%)
- Mastery books: [Mastery Book] Brandish 30 20000 (6%); [Mastery Book] Blind 20 20000 (6%); [Mastery Book]High Mastery 30 20000 (6%); [Mastery Book] Combo Tempest 30 20000 (6%)
- Classic equips: Red Brave Hamal 22500 (6.75%); Green Osfa Suit 22500 (6.75%); Black Oriental Fury Coat 22500 (6.75%); Blue Czar 22500 (6.75%); Dark Commodore Pants 22500 (6.75%); Red Osfa Boots 22500 (6.75%); Blue Crescent Boots 22500 (6.75%); Green Arnah Shoes 22500 (6.75%)
- Post-Big-Bang gear: Muspell Thief Hat 22500 (6.75%); Eclectic Prinsid 22500 (6.75%); Eclectic Rontano 22500 (6.75%)
- Post-Big-Bang gear, outclassing (rare): Jaihin Guards 3000 (0.9%)

### Future / Twilight Perion
**Dawn** (8610000, lv 169, HP 4,796,400 -> 284,000, EXP 20,280, normal, card row kept: yes)
- Mesos 1,082-1,622 base = 10,820-16,220 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Unagi 20000 (8%)
- Ores: DEX Crystal Ore 10000 (4%); Garnet Ore 9000 (3.6%); Emerald Ore 9000 (3.6%); Magic Powder (Black) 10000 (4%); Pole Arm Forging Stimulator 6000 (2.4%)
- Own/region ETC: Dawn Pendant 400000 (100%); Dawn Stone 200000 (80%)
- Scrolls: Scroll for Earring for LUK 100% 300 (0.12%); Scroll for Topwear for LUK 100% 300 (0.12%); Scroll for Two-Handed Axe for Accuracy 100% 300 (0.12%); Scroll for Two-Handed Axe for Accuracy 10% 300 (0.12%)
- Mastery books: [Mastery Book] Paralyze 20 500 (0.2%); [Mastery Book] Wrath of the Octopi 20 500 (0.2%)
- Classic equips: Red Ades 700 (0.28%); Red Ades Shoes 750 (0.3%); Green Crescent Boots 800 (0.32%); Dark Imperial 1000 (0.4%); Red Martier 1000 (0.4%)
- Post-Big-Bang gear: Muspell Magician Shoes 800 (0.32%); Muspell Pirate Gloves 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Pensalir Chaser Hat 60 (0.024%)

**Official Knight D** (8610008, lv 174, HP 5,696,400 -> 236,000, EXP 23,495, normal, card row kept: yes)
- Mesos 891-1,336 base = 8,910-13,360 in game, every kill
- Potions: Mana Elixir 20000 (8%); Reindeer Milk 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Opal Ore 9000 (3.6%); Sapphire Ore 9000 (3.6%); Magic Powder (Brown) 10000 (4%); Pole Arm Forging Stimulator 6000 (2.4%)
- Own/region ETC: Knight Gloves 400000 (100%)
- Scrolls: Scroll for Topwear for LUK 100% 300 (0.12%); Scroll for Overall Armor for DEF 60% 300 (0.12%); Scroll for Bottomwear for DEX 10% 300 (0.12%); Scroll for Pole Arm for Accuracy 100% 300 (0.12%)
- Mastery books: [Mastery Book] Paralyze 30 500 (0.2%); [Mastery Book] Elemental Boost 20 500 (0.2%)
- Classic equips: Green Canal Hood 1287 (0.515%); Red Varr Hat 1287 (0.515%); Black Oriental Fury Coat 700 (0.28%); Red Focus 1000 (0.4%); Dark Neschere 500 (0.2%)
- Post-Big-Bang gear: Muspell Warrior Suit 800 (0.32%); Muspell Pirate Gloves 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Jaihin Crossbow 60 (0.024%)

**Ancient Mixed Golem** (8620011, lv 198, HP 13,955,200 -> 490,000, EXP 64,640, normal, card row kept: yes)
- Mesos 1,267-1,901 base = 12,670-19,010 in game, every kill
- Potions: Elixir 20000 (8%); Sunset Dew 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); AquaMarine Ore 9000 (3.6%); The Magic Rock 10000 (4%); Crossbow Production Stimulator 6000 (2.4%)
- Scrolls: Scroll for Earring for LUK 60% 300 (0.12%); Scroll for Bottomwear for HP 10% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 100% 300 (0.12%); Scroll for Knuckle for Accuracy 60% 300 (0.12%)
- Mastery books: [Mastery Book] Advanced Combo Attack 30 500 (0.2%); [Mastery Book] Berserk 30 500 (0.2%)
- Classic equips: Blue Czar 700 (0.28%); Green Focus 1000 (0.4%); Blue Arcina 1000 (0.4%); White Nisrock 500 (0.2%); White Neschere 600 (0.24%)
- Post-Big-Bang gear: Muspell Bowman Hat 800 (0.32%); Muspell Bowman Shoes 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Utgard Pistol 60 (0.024%)

## Risky items

- **Outclassing post-Big-Bang gear (259 rows).** These were checked in the server's Character.wz: none is cash,
  untradeable or one-of-a-kind, and all have stats.
  - Jaihin (lv 130) and Utgard (lv 140) weapons have 207-249 ATT, 47-65% over the best classic weapons (Horntail's
    Dragon weapons, 141-151).
  - Pensalir armour (lv 140) has 2-3x the stats of the best classic armour.
  - AbsoLab (lv 160) weapons have 313-321 ATT with +60/+60 stats.
  - Rates: 60 for most (0.024% a kill), 15 for AbsoLab (0.006%, 41 normal mobs). Cygnus and Magnus carry one at
    3000/1000 (0.9%/0.3% a kill).
  - At Grandis' lv 130+ kill rate (about 1,200 an hour) a player finds about one Jaihin/Pensalir/Utgard piece every
    3-4 hours. In Arcane (about 280 kills an hour) it is about one every 15 hours. On the 41 mobs that carry an
    AbsoLab weapon it is about one every 60 hours.
  - If that is too generous, lowering everything outclassing to 15 is one constant (`BB_RARE_CHANCE`).
- **On-par post-Big-Bang gear (614 rows).** Eclectic and Muspell armour is about classic endgame (a few stat points
  better) and drops at 800 like classic armour.
- **Mastery books (667 rows)** at classic rates (500-1500, 0.2-0.6% a kill). That is about 5 books an hour at 1,200
  kills an hour, the same as classic lv 100+ mobs. Bosses: 4 books at 20000 (Zakum's rate).
- **Attack scrolls (262 rows)** on weapons and gloves, at the classic 0.12%. Cygnus has Gloves for ATT 10% at 4% a
  kill.
- **Own ETC items feed the Master Forger's crafting.** Nimble's Master Forger (NPC 9977779, placed in the Free
  Market by migration 027) crafts Lionheart, Amaterasu, AbsoLab and Arcane Umbra gear from `scripts/json/gearSets.json`,
  `weapons.json` and `otherAccessories.json`. Each recipe takes 100 of several ETC items plus 200M-1.3B mesos. 32 of
  the ETC items in this draft (Grobbler Stalks, Speeyor coats, Dino parts, the Future mutants' feathers, the Knight
  pieces, the Pendants, the Droplet Stone...) are recipe materials that had **no source at all** before.
  - I checked all 131 recipes that use them: **none becomes fully craftable** with this draft. Each still needs other
    materials nothing drops (most often 4001890, 4033732, 4000659 and 4000736).
  - Once those get a source, these drops open crafting of Arcane Umbra and AbsoLab-tier gear. That is a separate
    decision.
  - The Dawn, Blaze, Wind and Night Stones are used by `scripts/item/dreamkey.js` (5 of each). Hedgehog Balls have an
    NPC price (235).
- **ETC left out** by the use-or-price rule (16 items, `etc-excluded.csv`):
  - **No use in scripts and no NPC price (15):** Rubble (Amethyst Tokka), Dinogoth Tail, Giant Firewood (Swollen Axe
    Stump), Mutated Tooth (Pillaging Wild Boar), Heavy Armor Fragment (both Pillaging Boars), Cursed Plank, Slate and
    Plate (the Sinister Masks), Thunder Spear (Thunder), Mouse Tail (Balloon Mouse), Red and Green Frog Legs (the
    Poison Frogs), Mohawk Feather (Delinquent Chick), Rocker Feather (Punk Chick), Frozen Rose Petal (Frozen Rose).
  - **Post-Big-Bang system (1):** Core Blaze Tip (4032358, Blaze).
  - Never candidates (the name and description matching does not reach them, and the system filter would refuse
    them): Spell Trace (4000999/4001832), Arcane Symbols (1712xxx/1713xxx are not in the server's Character.wz at
    all), Star Force and Flame items, Nodestones and V-matrix cores, symbol upgrade
    materials and coin/mileage currencies. The post-Big-Bang gear families included (Eclectic, Muspell, Pensalir,
    Jaihin, Utgard, AbsoLab) are plain equips here: their set bonuses and Star Force do not exist in v83, so they
    count only for their base stats.
- **Boss equips** trigger the bots' rare-drop megaphone, about once every two boss kills.
- **Godly rolls.** Every mob equip can take the 1.5.2 godly roll, and that includes the post-Big-Bang gear.
- **Bots.** 3-rebirth bots already travel to Grandis and Arcane. With classic HP and these tables, bots there earn
  mesos, items and EXP under the existing per-map and daily caps (ticket 15). Bot EXP has no cap. Worth a
  `!botyield` look on TEST.

## Open questions for you

1. **The 0-EXP copies.** Ascendion, Foreberion and Embrion (8645040-42) fill 450012301-303 with 7,441 placements.
   They have 0 EXP in Mob.wz, so they give nothing, and they keep their 655-678M WZ HP (no stat row, no drop
   table). Only Embrion has an EXP-giving twin placed (8645022, in this draft). Also left alone at 0 EXP: the Erda
   variants and Arma's Follower on 450001400/450001500, the Cygnus and Resistance soldiers, Belle, Brighton,
   Corrupt Spirit of Harmony, ??? (8642091), the Cygnus Knights (Grandis 400000003) and Ariel, Solomon, Rex, Hugin,
   Munin (270050100). Give them EXP, HP and tables, despawn them, or leave them as scenery?
2. **Spawn density** has no per-map override on the server. If the classic spots should be busier rather than
   richer, that needs a small code change (a per-map spawn multiplier table).
3. **Outclassing gear rates**: 60 (0.024%) for Jaihin/Utgard/Pensalir and 15 for AbsoLab. With the new EXP these
   maps will be busier, so a lv 130 Grandis grinder finds one about every 3-4 hours. Keep, lower, or keep AbsoLab
   boss-only?
4. **Bosses**: are Cygnus (400000003), Magnus (401060101) and the Singularity Maker (450014250) meant to be
   farmable? Cygnus is at 110M HP and Magnus at 2.1B, with Zakum-sized tables.
5. **Monster Book HP**: rebuild Custom.wz with `build_book.py --hp-overrides` in the next client release?

## Release (1.6.1, after a TEST pass)

- **Migration:** `050-bb-drop-tables` is in `docs/release/1.6.1.conf` MIGRATIONS after 049, with a comment. 051 is the
  penguin move; the two touch different tables, so their order does not matter.
- **Ship together:** the jar (stat table, AreaGates, the portal check, bot gates, BotTransit rows) and the scripts
  (`9010002.js`, `270000000.10.js`) must go with migration 050. The stat table without the drops makes these mobs
  easy kills that drop little.
- **Auto-QA `t-ld-1`** (probe `trainingLadder`):
  - level 119 is refused and level 120 let in at two Future and two lower Grandis maps, by the players' rule and the
    bots';
  - Heliseum HQ and Arcane stay closed to a level-200, 2-rebirth character;
  - Sun Spirit, Sleepy Grobbler, Official Knight D, Crocky and Skelegon load with their table HP and EXP;
  - the four newer-area samples have a meso row, and `bb_drops_050_rows` has its rows.
  - (round 5) the six deeper maps stay closed, the Gate of Time offers lower Grandis behind AreaGates, and Lion King's
    Castle / Rose Garden let a rebirth-0 character in while Von Leon's wing does not.
  - It checks the gate rule directly; walking a real lv 119/120 character through the nine boundary portals is a
    manual QA step.
- **Manual QA on TEST:**
  - with a level-119 and a level-120 character: the teleporter, the Gate of Time (both choices), the Hyper Rock
    and the nine boundary portals listed under "Area gates";
  - with a rebirth-0 character: Desolate Moor -> Lion King's Castle, and the Hyper Rock to a castle wall;
  - kill a mob in lower Grandis, Lion King's Castle and Arcane, and check the HP bar, the EXP and the drops;
  - `!botyield` after an hour, to see bots training in the newly opened maps.
- **Player notes:** `PLAYER-NOTES.md` ("Training after 120").

## What was run

- `tools/economy/bb_inventory.py`, `gen_bb_drops.py` and `gen_area_gates.py` against the server WZ
  (`/root/work/bake/wz`) and a SELECT export of TEST `drop_data` (2026-10-28). Regenerating gives byte-identical
  files.
- `BbDropTablesDataTest`: every item exists in Item.wz or Character.wz, has a name, and is not cash, quest,
  untradeable, account-bound or one-of-a-kind; every ETC item has a use in `scripts/` or an NPC price; no card
  items; every mob is in Mob.wz; one meso row per mob; no Mirror World mob; 050 is listed in 1.6.1.conf.
- `BbDropTablesMigrationIT` on throwaway MySQL 8.0 and 8.4 containers: apply twice, roll back twice, apply again.
  Card rows, an older row and an unrelated mob are unchanged.
- `MobStatOverridesTest`: LifeFactory gives Sun Spirit its table HP and EXP and Skelegon EXP x1.4 with its HP kept; a
  mob with no row keeps its WZ values; the table matches `stats.csv`; bad rows are refused.
- `AreaGatesTest`, `HyperTPRefusalTest`:
  - level 120 opens the Future and lower Grandis;
  - upper Grandis, the Scrapyard, the Fox Tree Top and Arcane still need 3 rebirths;
  - the Hyper Rock agrees;
  - the teleporter and the Gate of Time ask AreaGates.
- `BotMapGatesTest`, `BotTransitHighLevelTest`, `GCTravelHighLevelTest`:
  - bots at level 119/120 at the Future and lower Grandis;
  - upper Grandis, Arcane and the Knight Stronghold stay closed;
  - the teleporter rows (level 120 for the Future and lower Grandis towns, 3 rebirths for the rest);
  - a level-119 bot is turned away at the teleporter's lower Grandis row and a level-120 one is taken.
- `tools/release` unit tests, and the full suite, `mvn -q -o test`.
- Not tested on TEST or in a real client.
