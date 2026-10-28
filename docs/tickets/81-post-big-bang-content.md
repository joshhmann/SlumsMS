# 81: Post-Big-Bang areas (Grandis, Arcane River, Future/Twilight): finish or close

Status: parked, **keep** (owner, 2026-10-25: "if it's for rebirth we should keep it"). Later content project:
finish it as the rebirth-3 area (option 1). Do not close it.

## What is there (TEST server data, counted 2026-10-25)
- Reached via the Henesys teleporter (scripts/npc/9010002.js, 3 rebirths for Grandis/Arcane) and the
  Temple of Time portal (scripts/portal/arcaneriver.js, 3 rebirths). Bots have the same routes (BotTransit.yaml).
- **Mobs: all ported.** Every mob placed on 270-273 / 400-419 / 450-459 maps loads (~49k placements, 305 distinct
  mobs in 4xx). EXP works.
- **Drops: none, not even mesos** (TEST `mapleroot_persist`, counted again 2026-10-28 for player report #7, "Monster
  Book: mobs above level 131 have no drops"). Mobs placed on these maps, by drop_data rows:

  | Region | Mobs | Monster card only | No rows at all | Mesos or items |
  |---|---|---|---|---|
  | Arcane River 450-459 | 160 | 104 | 56 | 0 |
  | Grandis 400-419 | 145 | 108 | 37 | 0 |
  | Temple of Time / Future / Twilight Perion 270-273 | 70 | 34 | 20 | 16 (all on 270, the v83 Temple of Time) |
  | Mirror World 321-328 (no way in) | 178 | 0 | 178 | 0 |

  A card-only mob drops its card (2%) plus the global drops (NX cards, Maple Leaves, Chaos/White Scroll, Flaming
  Feather). A mob with no rows drops nothing at all: `MapleMap.dropFromMonster` returns before the global drops when
  the mob's own list is empty (`server/maps/MapleMap.java:883`, stock Cosmic). The client's Monster Book
  (monsterbook.json from `tools/monsterbook/build_book.py`) shows exactly this, so its empty Dropping tab for 196 of
  the 203 carded mobs above level 130 is true. The 7 carded mobs above 130 that a player can reach without rebirths
  (Lilynouch, Chief Oblivion Guardian, Lyka, Zakum, Horntail, Pink Bean, the Dojo's Mu Gong Merits) each have a drop table.
- **NPCs: mostly placed, almost none scripted.** e.g. Arcane towns 6-28 NPCs each with 1 script; Fox Point
  410000200/410000300 have 0 of 16/18 NPCs; Grandis 400000000 has 21 of 38. No quests.
- The client ships more maps/NPCs/mobs than the server has (DeepSeek map report: ~9.5k NPC and ~3.4k mob
  placements), but those are on maps nothing sends players to.

## Who uses it
- Live 2026-10-25: 16 human characters, 0 with any rebirth, 0 standing in these maps. Unreachable today.

## Options when we pick it up
1. Keep as a rebirth-3 grind zone; add drop tables and script the key town NPCs (shops, storage, warp back).
2. Close it: a patch removing the teleporter menus, the Arcane portal and the bot routes is ready
   (scratchpad warp-menu-cut.patch; re-make it from this description if lost).
3. Re-extract server WZ from the client for the remaining NPCs: only with 1, it adds more unscripted NPCs.

Decide before the first player reaches 3 rebirths.
