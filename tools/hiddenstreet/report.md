# SlumsMS Hidden Street Data Sweep Report

Generated as part of Ticket 78 (Phase 1: Data Generator & Sweep Report).
This sweep checks cross-references across WZ XML data and server database tables to detect broken references, missing assets, and unlinked content.

## Summary of Broken References

| Category | Issue Count | Description |
| --- | --- | --- |
| **Items with no name** | 2264 | Items in Item.wz/Character.wz with missing or empty String.wz name |
| **Drops / Rewards / Shop missing items** | 0 | DB drop_data, reactordrops, shopitems or quest rewards pointing to missing items |
| **Quests with missing references** | 1 | Quests pointing to non-existent NPCs, maps, items, or mobs |
| **Portals leading to missing maps** | 34 | Map portals targeting non-existent map IDs |
| **Mobs / NPCs placed that don't exist** | 12 | Maps with life spawns (type 'm' or 'n') pointing to missing mob/NPC IDs |

### Dataset Entity Counts
- **Items:** 60,398
- **Monsters:** 11,024
- **Maps:** 23,052
- **NPCs:** 18,501
- **Quests:** 2,912

---

## 1. Items with No Name (Total: 2264)
Items defined in WZ data files that have no matching name string in `String.wz`.

| # | Item ID | Category / Path |
| --- | --- | --- |
| 1 | `100` | Special |
| 2 | `200` | Special |
| 3 | `300` | Special |
| 4 | `400` | Special |
| 5 | `500` | Special |
| 6 | `600` | Special |
| 7 | `700` | Special |
| 8 | `800` | Special |
| 9 | `900` | Special |
| 10 | `1000` | Special |
| 11 | `2000` | Special |
| 12 | `3000` | Special |
| 13 | `4000` | Special |
| 14 | `5000` | Special |
| 15 | `6000` | Special |
| 16 | `7000` | Special |
| 17 | `8000` | Special |
| 18 | `9000` | Special |
| 19 | `10000` | Special |
| 20 | `15000` | Special |
| 21 | `20071` | Face |
| 22 | `20072` | Face |
| 23 | `20079` | Face |
| 24 | `20141` | Face |
| 25 | `20241` | Face |
| 26 | `20341` | Face |
| 27 | `20815` | Face |
| 28 | `20871` | Face |
| 29 | `21066` | Face |
| 30 | `21067` | Face |
| 31 | `21139` | Face |
| 32 | `21140` | Face |
| 33 | `21239` | Face |
| 34 | `21240` | Face |
| 35 | `21339` | Face |
| 36 | `21340` | Face |
| 37 | `21815` | Face |
| 38 | `21850` | Face |
| 39 | `22222` | Face |
| 40 | `30928` | Hair |
| 41 | `30929` | Hair |
| 42 | `31900` | Hair |
| 43 | `31901` | Hair |
| 44 | `31902` | Hair |
| 45 | `31903` | Hair |
| 46 | `31904` | Hair |
| 47 | `31905` | Hair |
| 48 | `31906` | Hair |
| 49 | `31907` | Hair |
| 50 | `32000` | Hair |
*(Showing first 50 of 2264 items)*

---

## 2. Drops, Rewards, and Shop Rows Pointing at Missing Items (Total: 0)
Database drops, reactor drops, NPC shop listings, or quest rewards referencing item IDs not found in game data.

*None found.*

---

## 3. Quests with Missing References (Total: 1)
Quests requiring or involving an NPC, map, item, or mob that does not exist.

| # | Quest ID | Quest Name | Ref Type | Missing ID | State (0=Start, 1=Complete) |
| --- | --- | --- | --- | --- | --- |
| 1 | `1205` | Romeo and Juliet | `npc` | **`9212000`** | 0 |

---

## 4. Portals Leading to Missing Maps (Total: 34)
Map portals whose destination `tm` does not correspond to any valid map.

| # | Source Map ID | Source Map Name | Portal Name | Target Map ID |
| --- | --- | --- | --- | --- |
| 1 | `18103` | *(unnamed)* | `st02` | **`18101`** |
| 2 | `1500` | *(unnamed)* | `house00` | **`100001500`** |
| 3 | `16202` | *(unnamed)* | `out01` | **`16211`** |
| 4 | `4000` | *(unnamed)* | `in04` | **`4200`** |
| 5 | `4000` | *(unnamed)* | `in00` | **`4003`** |
| 6 | `11020` | *(unnamed)* | `st00` | **`100011020`** |
| 7 | `1501` | *(unnamed)* | `house00` | **`100001500`** |
| 8 | `66` | NY Streets | `out03` | **`70`** |
| 9 | `2000` | *(unnamed)* | `out03` | **`1013`** |
| 10 | `18998` | *(unnamed)* | `st02` | **`18101`** |
| 11 | `14300` | *(unnamed)* | `house00` | **`100014300`** |
| 12 | `18100` | *(unnamed)* | `st01` | **`18104`** |
| 13 | `100` | *(unnamed)* | `out03` | **`70`** |
| 14 | `2010` | *(unnamed)* | `cc3` | **`171`** |
| 15 | `701100000` | Yu Garden | `YuGa01` | **`91000`** |
| 16 | `970033000` | *(unnamed)* | `test` | **`970033001`** |
| 17 | `350020019` | Ship Deck 3 | `west00` | **`350003000`** |
| 18 | `325090210` | Underground Plaza | `west00` | **`325000110`** |
| 19 | `310010010` | Training Room Entrance | `tr01` | **`91000`** |
| 20 | `350020018` | Ship Deck 1 | `west00` | **`350003300`** |
| 21 | `350020018` | Ship Deck 1 | `east00` | **`350003100`** |
| 22 | `863000100` | Heart Tree Guardian Grove | `ra10` | **`86000`** |
| 23 | `867125750` | Eliminate the 6th Angel | `player1` | **`812010100`** |
| 24 | `867125750` | Eliminate the 6th Angel | `player2` | **`812010100`** |
| 25 | `867125750` | Eliminate the 6th Angel | `player3` | **`812010100`** |
| 26 | `867125750` | Eliminate the 6th Angel | `player4` | **`812010100`** |
| 27 | `867125750` | Eliminate the 6th Angel | `player5` | **`812010100`** |
| 28 | `811000011` | Honnou-ji Interior | `out00` | **`807100111`** |
| 29 | `865010200` | Berry | `berry_out` | **`610052100`** |
| 30 | `814041000` | Step Game Arena | `player1` | **`812010100`** |
| 31 | `814041000` | Step Game Arena | `player2` | **`812010100`** |
| 32 | `814041000` | Step Game Arena | `player3` | **`812010100`** |
| 33 | `814041000` | Step Game Arena | `player4` | **`812010100`** |
| 34 | `814041000` | Step Game Arena | `player5` | **`812010100`** |

---

## 5. Mobs and NPCs Placed on Maps That Do Not Exist (Total: 12)
Life placements in map files referencing non-existent mob or NPC IDs.

| # | Map ID | Map Name | Life Type | Life ID | Spawn Count |
| --- | --- | --- | --- | --- | --- |
| 1 | `1400` | *(unnamed)* | `mob` | **`9700042`** | 6 |
| 2 | `1580` | *(unnamed)* | `npc` | **`1064033`** | 1 |
| 3 | `1100` | *(unnamed)* | `mob` | **`9700041`** | 9 |
| 4 | `1110` | *(unnamed)* | `mob` | **`9700041`** | 9 |
| 5 | `1999` | *(unnamed)* | `mob` | **`9700050`** | 1 |
| 6 | `1120` | *(unnamed)* | `mob` | **`9700040`** | 16 |
| 7 | `1120` | *(unnamed)* | `mob` | **`9700049`** | 4 |
| 8 | `1531` | *(unnamed)* | `mob` | **`9700047`** | 47 |
| 9 | `17031` | *(unnamed)* | `mob` | **`1`** | 1 |
| 10 | `811000000` | *(unnamed)* | `npc` | **`9130101`** | 1 |
| 11 | `811000600` | *(unnamed)* | `npc` | **`9130121`** | 1 |
| 12 | `811000031` | *(unnamed)* | `npc` | **`9130101`** | 1 |

