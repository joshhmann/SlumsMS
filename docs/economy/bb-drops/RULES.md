# Post-Big-Bang drops, HP, EXP and gates: rules (draft, ticket 81)

On 2026-10-28 the owner decided to build drop tables for the post-Big-Bang mobs that have none. After round 1 he
added more: mesos stay level-based, mob HP goes onto a classic curve, Mirror World is left out, and three new item
kinds are added (the region's own ETC, mastery books and post-Big-Bang gear). Round 3 aims at good 120-200 training:
new-area EXP at about 2x a classic spot, the Future and lower Grandis opened at level 120, and the classic 120+ spots
buffed. This file is the rule
set that `tools/economy/gen_bb_drops.py` implements. If you change a rule, change it here and in the generator
together, then regenerate. The review summary is in `REVIEW.md`.

## Which mobs

- **Inventory** (`mobs.csv`, made by `tools/economy/bb_inventory.py`): every mob placed on maps 450-459 (Arcane
  River), 400-419 (Grandis), 270-273 (Temple of Time, Future and Twilight Perion) and 321-328 (Mirror World). "Placed"
  means a Map.wz `life` entry of type `m`. Each mob has its Mob.wz level, HP, EXP and boss flag (following
  `info/link`) and its drop_data rows on TEST (`mapleroot_persist`, exported 2026-10-28). A mob placed in two
  regions counts in the region where it has the most placements.
- **Target:** every inventoried mob whose drop_data rows are none or only its Monster Book card. The 16 v83 Temple
  of Time mobs on 270 already have classic tables and are left alone.
- **Regions:** Arcane, Grandis and Future by default. Mirror World (178 mobs) is left out until it has a way in;
  `--regions all` adds it back, with the same rules.
- **Skipped:** mobs with 0 EXP in Mob.wz get no table and no HP change. There are 24 in the three regions:
  scripted soldiers, event Erdas, the Cygnus Knights in the Cygnus fight, the Ascendion/Foreberion/Embrion copies on
  450012301-303, and Ariel, Solomon, Rex, Hugin and Munin. REVIEW.md lists them as an open question.

## World rates (not baked into the data)

drop_data holds 1x values and the world multiplies them. SlumsMS runs EXP 3x, mesos 10x, drops 4x and boss drops 3x
(divergence ledger EC-001). `MapleMap.dropItemsFromMonsterOnMap` multiplies **every** row's chance by the drop rate,
the meso row included, and multiplies the meso amount by the meso rate. So a meso row at chance 400000 drops on every
kill (0.4 x 4 >= 1) and pays 10x its base range. Mesos are itemid 0, with minimum/maximum_quantity holding the base
meso range. The server draws `nextInt(max - min) + min`, so max must be greater than min.

## Tiers

| Tier | Which | Table |
|---|---|---|
| normal | not a boss, EXP > 0, level >= 30 | mesos, 3 potions, 5 ores, own/region ETC, 4 scrolls, 2 mastery books, 5 classic equips, 2 post-Big-Bang gear, 1 rare post-Big-Bang gear |
| elite | boss flag, HP < 1,000,000,000 | mesos x4, more picks, every item chance x2 |
| boss | boss flag, HP >= 1,000,000,000 | Zakum-shaped and conservative (see below) |
| low | level < 30 (maze pigs, garbage event mobs) | mesos and 1 low-level potion |
| skip | EXP 0 | nothing |

The Monster Book card row (migration 045, 2%) stays as it is. The generator never emits a card item, and the
migration skips any (mob, item) pair that is already in drop_data.

## Mesos (by level, owner 2026-10-28)

- The curve is classic, fit on the TEST drop_data meso rows of non-boss classic mobs. The midpoint is
  0.085 x level² up to level 94, then 8 x level. That gives lv 113 = 904, lv 131 = 1,048 and lv 200 = 1,600 base.
- The range is the midpoint -20% / +20%, at chance 400000.
- Within one region and level, the midpoint is multiplied by sqrt(EXP / median EXP), clamped to 0.8-1.25.
- Elite mobs get 4x. Bosses get 90 x level at chance 700000 (Zakum, lv 140, pays 10,000-15,000 base).

## HP and EXP (stat overrides)

- **Where the server's values come from.** `LifeFactory.getMonsterStats` reads Mob.wz `info/maxHP` and `info/exp`.
  It halves the HP of non-boss mobs of level 160+ with more than 30M HP, and it hard-codes some bosses (Magnus 8880002
  = 200,000,000,000, for example). Before this change there was no data-driven override.
- **The override.** `src/main/resources/soloMapling/mob-stat-overrides.tsv` has the columns mob id, max HP, EXP and a
  comment; `-` in a column keeps the mob's own value. It is a jar resource, so it deploys with the jar.
  `server.life.MobStatOverrides` loads it, and LifeFactory applies it after its own values, so a row wins over the WZ
  value, the halving and the hard-coded numbers. Turn it off with `-Dslums.mobStatOverrides=false`. The server's WZ
  files are never touched. `stats.csv` lists every row with before and after values.

### HP (owner 2026-10-28: a classic-like curve)

- **Normal mobs:** the classic curve is HP = 0.0064595 x level^3.4309. This is a power fit on classic non-boss mobs,
  lv 30-131: lv 113 = 71k, lv 131 = 118k, lv 200 = 507k. It is multiplied by sqrt(server HP / median server HP of
  the same region and level), clamped to 0.75-1.5, so tougher mobs stay tougher. The result is rounded to 3
  significant figures.
- **Elite:** 30x the curve (Manon is about 55x a normal mob of its level).
- **Boss:** log-interpolated between Zakum's body (lv 140, 110M) and Pink Bean (lv 180, 2.1B), capped at 2.1B.
- **Only clearly off-curve mobs change:** a mob gets an HP value only when its current server HP is more than 1.25x
  the new value.

### EXP (owner 2026-10-28, round 3: about 2x a classic spot per hour, smooth by level)

- **Model.** The model character deals the damage it takes to kill 1,000 Chief Oblivion Guardians an hour
  (141,000 HP each), which is 141M damage an hour. A map can feed at most 1,500 kills an hour. At 3x EXP that
  classic spot pays 21.18M EXP an hour.
- **Target.** For a mob of level L, the target is 2 x 21.18M x classicEXP(L) / classicEXP(131). classicEXP is the
  classic non-boss EXP curve, 0.00107394 x L^3.08225, a power fit on classic mobs from lv 30 to lv 131. So EXP per
  hour grows with level the way classic EXP per kill does: lv 120 = 32M, lv 150 = 64M, lv 170 = 95M, lv 200 = 156M.
  That is about 0.7 hours per level at lv 120, 1.8 at lv 150 and 10.5 at lv 199.
- **EXP per kill** = target / (3 x kills per hour at the mob's new HP), but never below classicEXP(L), and rounded to
  3 significant figures. Only normal mobs change; elite and boss EXP stay as they are.

### Classic 120+ spots (round 3: moderately up, below the new areas)

- The non-boss mobs of level 95+ (excluding event mobs 9300000-9399999) placed in these areas get EXP x1.4:
  - Leafre / Minar Forest, 240010000-240049999
  - Temple of Time, 270010000-270039999
  - Lion King's Castle, 211060000-211069999
  - Rose Garden, 211080000-211089999
  - Ulu City, 541020000-541029999
- That is 39 mobs. The override is per mob, so a mob placed in other maps too gets the same EXP there.
- **Density:** the server has no per-map spawn override, only an integer multiplier that nothing sets, and
  `createMobInterval` is WZ data. So this draft buffs EXP only.

## Area gates (round 3: good 120-200 training)

- `server.maps.AreaGates` holds the rule.
- **Future / Twilight Perion** (271000000-273999999): level 120, or 2 rebirths as before.
- **Lower Grandis**: level 120, or 3 rebirths. This is the lv 109-142 band: Pantheon, the low streets of Savage
  Terminal, Sanctuary, Caravan Refuge, Fox Ridge, Cheong-woon Valley, Ristonia and Toolen City, 187 map ids in
  `src/main/resources/soloMapling/lower-grandis-maps.txt`. `tools/economy/gen_area_gates.py` generates the list from
  Map.wz:
  - it starts at the lower towns and walks the portals;
  - a map with mobs is lower when its strongest mob is lv 142 or less (Grandis has no mobs between lv 143 and 174);
  - a map with no mobs (town, interior, door) is lower unless it sits next to an upper mob map and next to no lower
    one.
- **The rest of Grandis and Arcane River** (400000000-460000000): 3 rebirths, as before.
- **Where the rule is checked:** the Hyper Rock (`HyperTPHandler`), the Henesys teleporter (`scripts/npc/9010002.js`,
  per destination), the Temple of Time's Gate of Time (`scripts/portal/270000000.10.js`) and every unscripted portal
  (`GenericPortal`). The unscripted portals catch walking between lower and upper Grandis; all four boundary portals
  are unscripted. The Arcane River portal (`arcaneriver.js`) is unchanged. GMs pass.
- **Bots** keep their rebirth-only rule (`BotMapGates`), so they do not use the newly opened maps yet.

## Items (only what classic mobs drop, plus the three owner-approved extras)

`classic-pool.csv` is built from drop_data. It holds every item a classic non-boss mob drops (ids 100000-8799999,
outside the target set), minus anything that is cash, quest, tradeBlock, accountSharable, equipTradeBlock,
timeLimited or one-of-a-kind, or that has no String.wz name. The choice of items per mob is seeded from the mob id,
so regenerating gives the same tables.

- **Potions** (20000 each): Power Elixir, Mana Elixir, Elixir, Unagi, Reindeer Milk, Sunset Dew.
- **Ores** at classic rates: 1 crystal ore (10000), 2 mineral or jewel ores (9000), 1 Magic Rock, Summoning Rock or
  magic powder (10000), and 1 production stimulator (6000).
- **Scrolls** at classic rates: 4 of the 206 standard equipment scrolls that classic mobs drop. The 10%, 60% and 100%
  scrolls are at 300; the 30% and 70% dark scrolls are at 750. No Chaos, White or Clean Slate scrolls (Chaos and
  White already come from the global drops).
- **Mastery books** (4th job, owner: yes): 2 of the 104 `[Mastery Book]` items that classic non-boss mobs drop, each
  at the median rate classic mobs drop it at (500-1500).
- **Classic equips:** 5 from the classic mob-dropped equipment with reqLevel in [top - 20, top], where
  top = min(mob level, 108).
- **Own and region ETC** (owner: yes). Candidates are Etc items whose name starts with the mob's name ("Sleepy
  Grobbler Stalk") or whose description names the mob ("Knight Mask"), excluding quest and untradeable items. A
  candidate goes in only when it **has a use here** or is **plain sellable ETC** (rule added by the coordinator on
  2026-10-28):
  - it has a use if its id appears under `scripts/` (a quest, an NPC exchange, a craft such as the Master Forger's
    recipes in `scripts/json/gearSets.json`);
  - it is plain sellable if it has an NPC price;
  - it is never included when its name marks a post-Big-Bang system v83 does not have (Spell Trace, symbols, Star
    Force, flames, nodestones/V-matrix, cores, cubes/potential, mileage or coin currencies).

  The first item that qualifies is at 400000 and a second at 200000. The Arcane River Droplet Stone (4001878, a
  crafting material, NPC price 1) goes on every Arcane mob at 10000. Excluded items are written to
  `etc-excluded.csv` and listed in REVIEW.md.
- **Post-Big-Bang gear** (owner: yes). This is equipment with a setItemID and reqLevel 110+ that no mob drops today,
  restricted to v83 weapon types. Every candidate is checked in the server's Character.wz for stats, cash,
  tradeBlock and the other flags. Each family is compared with the best gear any mob, bosses included, drops for the
  same slot or weapon type. A family **outclasses** when any of its pieces has ATT more than 10% higher or 5+ more
  total stats. Families: Eclectic (120) and Muspell armour (130) are on par with classic gear and drop like classic
  equips (800). Pensalir armour (140) and the Jaihin (130), Utgard (140) and AbsoLab (160) weapons outclass, and
  drop rarely: 60, or 15 for reqLevel 150+ (AbsoLab). A mob draws from reqLevel [min(level, 160) - 30, min(level, 160)]:
  2 par pieces and 1 rare piece.
- **Elite:** more picks, every chance x2.
- **Boss:** Zakum's potion rows; Magic Rock and Summoning Rock at 7000; 6 scrolls at 13500; 8 classic equips at
  22500; 4 mastery books at 20000; 3 par post-Big-Bang pieces at 22500 and 1 rare piece at 3000 (1000 for AbsoLab).
  No boss-unique classic gear.

## Checks the generator and tests enforce

- Every item exists in Item.wz (Consume/Etc) or Character.wz, has a name, and is not cash, quest, untradeable,
  account-bound or one-of-a-kind. Every ETC item (other than ores) has a use in `scripts/` or an NPC price.
  Every mob exists in Mob.wz. No card items, no duplicate (mob, item) pairs,
  questid 0, chance 1-999999, and exactly one meso row per mob with max > min. No Mirror World mob by default
  (`BbDropTablesDataTest`).
- Migration 050 applies twice and rolls back twice, leaving card rows and older rows alone
  (`BbDropTablesMigrationIT`, which needs `-Dslums.family.it.url`).
- LifeFactory applies the HP and EXP table (Sun Spirit: both; Skelegon: EXP x1.4 only). The table matches
  `stats.csv`. Bad rows are refused (`MobStatOverridesTest`).
- The gates: level 120 opens the Future and lower Grandis, upper Grandis and Arcane still need 3 rebirths, the Hyper
  Rock agrees, and the teleporter and the Gate of Time ask AreaGates (`AreaGatesTest`).

## Regenerating

```
# 1. export drop_data from TEST (SELECT only) to a TSV, then
python3 tools/economy/bb_inventory.py --drops drop_data.tsv          # docs/economy/bb-drops/mobs.csv
python3 tools/economy/gen_bb_drops.py pool --drops drop_data.tsv     # docs/economy/bb-drops/classic-pool.csv
python3 tools/economy/gen_bb_drops.py gen [--regions all]            # rows.csv, stats.csv, migration 050, stat table
python3 tools/economy/gen_area_gates.py                              # lower-grandis-maps.txt
# the client Monster Book can print the new HP (needs a Custom.wz rebuild):
python3 tools/monsterbook/build_book.py ... --hp-overrides src/main/resources/soloMapling/mob-stat-overrides.tsv
```
