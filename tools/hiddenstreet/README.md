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
    --report tools/hiddenstreet/report.md
```

Options:
- `--wz <dir>`: Path to server WZ XML dump (defaults to `./wz`, then `/opt/mapleroot-dev/persistence-test/Server/wz`).
- `--db-dir <dir>`: Path to directory containing exported TSV or JSON tables (`drop_data`, `reactordrops`, `shops`, `shopitems`).
- `--out-dir <dir>`: Target directory for normalized JSON datasets (defaults to `data/hiddenstreet/`).
- `--report <path>`: Output Markdown path for the reference sweep report (defaults to `tools/hiddenstreet/report.md`).
- `--workers <n>`: Number of parallel worker processes for map/mob/equip parsing (defaults to available CPU cores).

### 2. Run the Unit Tests
```bash
python3 -m unittest tools/hiddenstreet/test_build_data.py
```

## Normalized Dataset Schema

The generator produces 5 primary JSON datasets in `--out-dir`:

1. **`items.json`**
   - Key: item ID (string)
   - Fields: `id`, `name`, `desc`, `type` (category), `req_level`, `req_job`, `stats` (PAD, MAD, PDD, MDD, STR, DEX, INT, LUK, HP/MP recovery, etc.), `drops` (which mobs drop it with chance/quantities), `shops` (which NPCs sell it and price), `quests_reward` (quests rewarding it), `quests_require` (quests requiring it).

2. **`mobs.json`**
   - Key: mob ID (string)
   - Fields: `id`, `name`, `level`, `hp`, `mp`, `exp`, `stats` (PAD, PDD, MAD, MDD, speed, etc.), `maps` (spawn maps with counts), `drops` (items dropped with chances and quantities).

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
