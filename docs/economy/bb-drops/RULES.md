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
- **Regions:** Arcane, Grandis, Future and (round 4) "elnath", which is Lion King's Castle 211060xxx and Rose Garden
  211080xxx: classic lv 117-134 spots whose mobs had huge HP and no meso drops. Mirror World (178 mobs) is left out
  until it has a way in; `--regions all` adds it back, with the same rules.
- **Classic regions** (elnath) get the same HP, EXP and drop rules, but their tables come from the classic pool only:
  no post-Big-Bang gear.
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
- **Lower Grandis (1.6.1 b040).** The notes promise lower Grandis "pays roughly twice what the Temple of Time does".
  Against the BUFFED Temple (the same release gives it x1.4) round 3's target gave only 1.3x (34M against 26M an
  hour). The normal Grandis mobs placed on a lower-Grandis map get the target x LOWER_GRANDIS_EXP_X = 1.55, which
  puts the area's median training map at 2.0x the buffed Temple of Time's Road to Oblivion (52.6M against 26.1M;
  `tools/economy/ladder_table.py`). Upper Grandis, Arcane River, Lion King's Castle and Rose Garden keep the
  round-3 target.
- **The Future (owner 2026-10-29).** It paid ~3.9x the buffed Temple; its normal mobs now get the target x
  FUTURE_EXP_X = 0.75, so its median training map pays 3.0x the buffed Road to Oblivion (78.4M against 26.1M).
- **Leafre of the Past (owner 2026-10-29)** (272000100-272000600, the Future's lv 124 Corrupted Wyvern, Cornian,
  Skelegon and Kentaurus) opens as a Gate of Time choice. Its mobs get the lower-Grandis multiplier (x1.55), not the
  Future trim, so a lv 124 map pays what lower Grandis pays at that level: 14,800 EXP a kill, 55.4M an hour.
- The generators read a mob's level from its Mob.wz `info` node only (`info_text`): the first `level` in the file was
  sometimes a skill's (Corrupted Skelegon's skill 4, the mob 124). That changed no gate and no row, only two comments
  in lower-grandis-maps.txt and the ladder tool's map filter.

### Classic 120+ spots (round 3: moderately up, below the new areas; 1.6.1 b040: every mob)

- Every mob placed in these areas that gives EXP gets EXP x1.4, field bosses included (excluding event mobs
  9300000-9399999 and the ticket-81 target mobs, which have their own rows). The player notes say these areas "give
  40% more EXP"; round 3 only took the non-boss mobs of level 95+ (25 mobs), 1.6.1 b040 takes all of them (53):
  - Leafre / Minar Forest, 240010000-240049999
  - Temple of Time, 270010000-270039999
  - Ulu City, 541020000-541029999
- The override is per mob, so a mob placed in other maps too gets the same EXP there. Lion King's Castle and Rose
  Garden were on this list in round 3; round 4 gives them the full treatment instead.
- **Density:** the server has no per-map spawn override, only an integer multiplier that nothing sets, and
  `createMobInterval` is WZ data. So this draft buffs EXP only.

## Area gates (round 3: good 120-200 training)

- `server.maps.AreaGates` holds the rule.
- **Future / Twilight Perion** (271000000-273999999): level 120, or 2 rebirths as before.
- **Lower Grandis**: level 120, or 3 rebirths. This is the lv 109-142 band: Pantheon, the low streets of Savage
  Terminal, Sanctuary, Caravan Refuge, Fox Ridge, Cheong-woon Valley, Ristonia and Toolen City, 181 map ids in
  `src/main/resources/soloMapling/lower-grandis-maps.txt`. `tools/economy/gen_area_gates.py` generates the list from
  Map.wz:
  - it starts at the lower towns and walks the portals;
  - a map with mobs is lower when its strongest mob is lv 142 or less (Grandis has no mobs between lv 143 and 174);
  - a map with no mobs (town, interior, door) is lower unless it sits next to an upper mob map and next to no lower
    one.
  - (1.6.1 b039) the deeper parts the notes keep at 3 rebirths stay out even where the walk reaches them: every map
    whose name says Heliseum, the Fox Tree or Cernium, and Savage Terminal's hidden back streets (Back Alley,
    Desolate Black Market, Desolate Scrapyard, 402000400-402000402). Round 3 had opened six of them (the Fox Tree,
    the Heliseum Hideout, the Transitional Dimensional Door and the three back streets).
  - It prints every portal from a lower map into a closed one and flags a scripted one; all 9 are unscripted, so
    GenericPortal checks them.
- **The rest of Grandis and Arcane River** (400000000-460000000): 3 rebirths, as before.
- **Lion King's Castle and the Rose Garden** (1.6.1 b040): no rebirth gate. `in_lkc.js`, `211060000.4.js`,
  `out_VLRoof4.js`, the Hyper Rock and the bots' gate no longer ask for 1 rebirth. Still gated: the towers past
  Walls 1 (key items 4032832-4032834 / 4032858 or quests 3165-3167, as before) and Von Leon's wing 211070000-211070550
  (rebirth 1 by Hyper Rock and for bots; on foot it is reached through the towers). The Fifth Tower roof's portal into the Rose
  Garden (`pt_rosegarden.js`) and its way back (`pt_rosegardenout.js`) were missing and now exist (owner 2026-10-29).
- **Where the rule is checked:** the Hyper Rock (`HyperTPHandler`), the Henesys teleporter (`scripts/npc/9010002.js`,
  per destination), the Temple of Time's Gate of Time (`scripts/portal/270000000.10.js` opens the menu
  `scripts/npc/gateOfTime.js`: the Future, lower Grandis at Pantheon (1.6.1 b040) or Leafre of the Past at the Crack
  in Time (272000000, owner 2026-10-29), all level 120) and every unscripted portal
  (`GenericPortal`). The unscripted portals catch walking between lower and upper Grandis; all nine boundary portals
  are unscripted. The Arcane River portal (`arcaneriver.js`) is unchanged. GMs pass.
- **Bots** (round 4) follow the same rule. `BotMapGates.mayEnter(map, rebirths, level)` asks AreaGates. It is used for
  bot map choice, routing (with the transit level tier), the bots' Hyper Rock, world events and TrainingBot.
  Callers with no level keep the strict rebirth rule. The Henesys teleporter rows in `BotTransit.yaml` for the
  Future and lower Grandis towns need level 120 instead of 3 rebirths.

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
- Bots follow the gates: `BotMapGatesTest`, `BotTransitHighLevelTest` (teleporter rows) and `GCTravelHighLevelTest`
  (a level-119 bot is turned away at the teleporter's lower Grandis row, a level-120 one goes).
- 1.6.1 b039-b041: `LowerGrandisPortalsWzTest` (no Heliseum / Fox Tree / Cernium / back-street map is lower, every
  way out of lower Grandis into a closed map is checked), `GateOfTimeScriptTest`, `TrainingLadderWzTest` (every mob
  of the buffed spots has x1.4; lower Grandis is 1.9-2.2x the buffed Temple), `HyperTPRefusalTest` (the castle without
  rebirths), `TrainingRegionsTest` and `LevelingPathTest` (bots' regions and path from level 120).
- On TEST, auto-QA `t-ld-1` checks the gates at levels 119 and 120, the deeper Grandis maps, the Gate of Time's
  Grandis option, Lion King's Castle at rebirth 0, the sampled mobs' HP and EXP, and 050's rows.

## Regenerating

```
# 1. export drop_data from TEST (SELECT only) to a TSV, then
python3 tools/economy/bb_inventory.py --drops drop_data.tsv          # docs/economy/bb-drops/mobs.csv
python3 tools/economy/gen_bb_drops.py pool --drops drop_data.tsv     # docs/economy/bb-drops/classic-pool.csv
python3 tools/economy/gen_area_gates.py                              # lower-grandis-maps.txt (run before gen)
python3 tools/economy/gen_bb_drops.py gen [--regions all]            # rows.csv, stats.csv, migration 050, stat table
python3 tools/economy/ladder_table.py                                # the 120+ ladder (EXP an hour per area)
# the client Monster Book can print the new HP (needs a Custom.wz rebuild):
python3 tools/monsterbook/build_book.py ... --hp-overrides src/main/resources/soloMapling/mob-stat-overrides.tsv
```

## 055: the boss-fight mobs outside 050's regions (owner 2026-10-29)

- `gen_bb_drops.py gaps` builds tables for `GAP_MOBS` by the same rules (`build_rows`, priced against 050's own mobs
  for the meso medians) and writes `database/migrations/055-boss-drop-gaps.sql` (ledger `boss_drops_055_rows`) and
  `docs/economy/bb-drops/gap-rows.csv`. Regenerating 050 is unchanged by it.
- **One boss table per clear (owner, second and third passes):** the BOSS table goes on the form that dies and that the
  event counts as the kill; the other fighting forms get ELITE tables (mesos x4, every chance x2, no boss-tier rolls).
  `GAP_MOBS` sets the tier per mob.
  - Cygnus: 8850012 (CygnusBattle.isCygnus; MapleMap kills it with drops) gets the boss table. The guards the fight
    really spawns, Mihile, Oz, Irena, Eckhart and Hawkeye (8850000-8850004, `8850000 + cygnusSpawnCounter`), get elite
    tables, and since 1.6.1 a guard dies through `MapleMap.killMonster(guard, chr, true)` in `afterBossKilled` before
    the drop-free field-clear, so its table drops. 8850006-8850009 (never spawned) get nothing.
  - Castellan: one table per clear, an elite table on Castellan Toad 9400409 (CastellanBattle.isCastellan; boss flag but
    450M HP; its existing meso row stays). Castellan 9400408 gets none.
  - Ninja Castle is a classic area ("zipangu"): classic pool only, no post-Big-Bang gear.
  - The Arcane Lantern Erda (8641008, revived from 8641007) is a normal mob by the rules.
- The phase/form stubs 8850005, 8850011, 8880150, 8880302, 8880415, 8645009 and 8641009 stay dropless. 8850011 is
  killed with `withDrops=false` each time a guard comes in (`spawnCygnusMobs`), so it never drops.
- Checks: `BossDropGapsDataTest` (the mobs, the 050 row rules, tiers, own ledger, listed after 054),
  `BossDropGapsMigrationIT` (apply twice, rollback twice, the card row, the Toad's meso row and 050's rows untouched),
  `CygnusGuardDropsTest` (a guard's death kills it with drops, then the field-clear and Cygnus' return run as before).
