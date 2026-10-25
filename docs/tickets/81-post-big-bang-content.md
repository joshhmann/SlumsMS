# 81: Post-Big-Bang areas (Grandis, Arcane River, Future/Twilight): finish or close

Status: parked, **keep** (owner, 2026-10-25: "if it's for rebirth we should keep it"). Later content project:
finish it as the rebirth-3 area (option 1). Do not close it.

## What is there (TEST server data, counted 2026-10-25)
- Reached via the Henesys teleporter (scripts/npc/9010002.js, 3 rebirths for Grandis/Arcane) and the
  Temple of Time portal (scripts/portal/arcaneriver.js, 3 rebirths). Bots have the same routes (BotTransit.yaml).
- **Mobs: all ported.** Every mob placed on 270-273 / 400-419 / 450-459 maps loads (~49k placements, 305 distinct
  mobs in 4xx). EXP works.
- **Drops: none.** drop_data has 0 rows for those 305 mobs: only mesos and global drops.
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
