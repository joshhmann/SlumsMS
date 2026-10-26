# SlumsMS Hidden Street Data Generator & Sweep Tool

Part of **Ticket 78 (Phase 1)**: builds the normalized game dataset and data sweep report directly from SlumsMS server WZ XML dumps and database tables.

The static web viewer (Phase 2) consumes the normalized JSON dataset generated here so that game data never goes stale. Generating the dataset also acts as a data sweep to catch broken references feeding 1.5.x bug squash fixes.

## Quick Start

### 1. Run the Data Generator & Sweep
```bash
python3 tools/hiddenstreet/build_data.py \
    --wz wz \
    --db-dir cache/db \
    --out-dir data/hiddenstreet \
    --report tools/hiddenstreet/report.md \
    --meta tools/hiddenstreet/meta.json
```

The 1.6.0 build reads the TEST server's WZ (`rsync` `/opt/mapleroot-dev/persistence-test/Server/wz` into the
git-ignored `wz/`) and TEST DB tables exported SELECT-only into the git-ignored `cache/db/` as TSV:
`drop_data`, `drop_data_global`, `reactordrops`, `shops`, `shopitems`. The dataset (`data/hiddenstreet/`, about
60 MB) is git-ignored; `tools/hiddenstreet/meta.json` and `report.md` are the committed record of a build.

Options:
- `--wz <dir>`: Path to server WZ XML dump (defaults to `./wz`, then `/opt/mapleroot-dev/persistence-test/Server/wz`).
- `--db-dir <dir>`: Path to directory containing exported TSV or JSON tables (`drop_data`, `reactordrops`, `shops`, `shopitems`).
- `--out-dir <dir>`: Target directory for normalized JSON datasets (defaults to `data/hiddenstreet/`).
- `--report <path>`: Output Markdown path for the reference sweep report (defaults to `tools/hiddenstreet/report.md`).
- `--workers <n>`: Number of parallel worker processes for map/mob/equip parsing (defaults to available CPU cores).
- `--include-korean`: keep entities whose name is Korean-only (Hangul and no Latin letter). By default they are
  left out of the dataset; they are always listed in the report and in `meta.json` (`korean_names`).
- `--server-config <config.yaml>`: read world 1's `drop_rate` / `boss_drop_rate` / `meso_rate` for the drop-rate
  rule in `meta.json` (default: TEST's values, 4 / 3 / 10).
- `--meta <path>`: also write `meta.json` there (it is always written into `--out-dir`).

### Cleaning (1.6.0)
- **Duplicate rows:** DB rows identical except their row id are dropped (TEST: 40 duplicate `reactordrops`), and
  repeated rows inside an entity's lists (quest links, portals with no position, a shop offering one item at one
  price twice) are dropped. Counts go to `meta.json` `dedupe`.
- **Duplicate names:** every entity gets `display_name` and `name_qualifier`. The first entity of a same-name group
  keeps the plain name; the others get a qualifier, e.g. `Stirge (event)` for mob 9400503 vs `Stirge` 2300100. The
  rule is in `meta.json` `duplicate_names.rule`.
- **Korean-only names:** see `--include-korean`.
- **Drop rates:** chances are base chances; `meta.json` `drop_rates` says how the server multiplies them (Bera:
  mob drops x4 including quest items, bosses x3, reactors x4, global drops not multiplied, meso amount x10).

### 2. Run the Unit Tests
```bash
python3 -m unittest tools/hiddenstreet/test_build_data.py
```

## Normalized Dataset Schema

The generator produces 5 primary JSON datasets in `--out-dir`, plus `summary.json` and `meta.json` (sources, counts,
cleaning, the drop-rate rule and data notes). Every entity also has `display_name` and `name_qualifier`.

1. **`items.json`**
   - Key: item ID (string)
   - Fields: `id`, `name`, `display_name`, `name_qualifier`, `desc`, `type` (category), `req_level`, `req_job`, `stats` (PAD, MAD, PDD, MDD, STR, DEX, INT, LUK, HP/MP recovery, etc.), `drops` (which mobs drop it with chance/quantities), `global_drops` (drop_data_global: any mob on a continent, flat chance), `shops` (which NPCs sell it and price), `quests_reward` (quests rewarding it), `quests_require` (quests requiring it).

2. **`mobs.json`**
   - Key: mob ID (string)
   - Fields: `id`, `name`, `display_name`, `name_qualifier`, `level`, `hp`, `mp`, `exp`, `boss` (uses the boss drop rate), `stats` (PAD, PDD, MAD, MDD, speed, etc.), `maps` (spawn maps with counts), `drops` (items dropped with chances and quantities).

3. **`maps.json`**
   - Key: map ID (string)
   - Fields: `id`, `name`, `street`, `mobs` (placed mob IDs, names, spawn counts), `npcs` (placed NPC IDs, names, counts), `portals` (portal names, types, target map IDs, target portal names), `targets` (unique reachable target map IDs).

4. **`npcs.json`**
   - Key: NPC ID (string)
   - Fields: `id`, `name`, `maps` (maps where NPC stands), `shop_items` (items sold, prices, positions), `quests_start` (quests initiated), `quests_end` (quests completed).

5. **`quests.json`**
   - Key: quest ID (string)
   - Fields: `id`, `name`, `start_npc` (id, name), `end_npc` (id, name), `requirements` (min/max level, jobs, prerequisite quests, required items with counts, required mobs with counts, required maps/meso), `rewards` (exp, meso, fame, reward items with counts/chances, next quest).

## Reference Sweep Categories

The generator analyzes cross-references across WZ files and database tables and records warnings in `report.md`:
1. **Items with no name**: Items defined in `Character.wz` or `Item.wz` with no entry or empty name in `String.wz`.
2. **Drops / rewards / shop rows pointing at missing items**: DB `drop_data`, `reactordrops`, `shopitems`, or quest rewards referencing item IDs that do not exist.
3. **Quests with missing references**: Quests whose start/end NPC, prerequisite map (`fieldEnter`), required item, or required mob does not exist.
4. **Portals leading to missing maps**: Map portals whose destination target map ID (`tm != 999999999` and `tm > 0`) does not exist in `Map.wz`.
5. **Mobs / NPCs placed that don't exist**: Map `life` spawns (type `m` or `n`) referencing mob or NPC IDs that do not exist.
