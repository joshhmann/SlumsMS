# SlumsMS Hidden Street Data Sweep Report

Generated as part of Ticket 78 (Phase 1: Data Generator & Sweep Report).
This sweep checks cross-references across WZ XML data and server database tables to detect broken references, missing assets, and unlinked content.

## Summary of Broken References

| Category | Issue Count | Description |
| --- | --- | --- |
| **Items with no name** | 2264 | Items in Item.wz/Character.wz with missing or empty String.wz name |
| **Drops / Rewards / Shop missing items** | 0 | DB drop_data, reactordrops, shopitems or quest rewards pointing to missing items |
| **Quests with missing references** | 90 | Quests pointing to non-existent NPCs, maps, items, or mobs |
| **Portals leading to missing maps** | 50 | Map portals targeting non-existent map IDs |
| **Mobs / NPCs placed that don't exist** | 4010 | Maps with life spawns (type 'm' or 'n') pointing to missing mob/NPC IDs |

### Dataset Entity Counts
- **Items:** 60,398
- **Monsters:** 6,789
- **Maps:** 11,352
- **NPCs:** 8,353
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

## 3. Quests with Missing References (Total: 90)
Quests requiring or involving an NPC, map, item, or mob that does not exist.

| # | Quest ID | Quest Name | Ref Type | Missing ID | State (0=Start, 1=Complete) |
| --- | --- | --- | --- | --- | --- |
| 1 | `1205` | Romeo and Juliet | `npc` | **`9212000`** | 0 |
| 2 | `20726` | Information From Those Who Remember | `mob` | **`9101001`** | 1 |
| 3 | `2105` | DANGER! <1-G. Mushroom> | `mob` | **`9101000`** | 1 |
| 4 | `2107` | DANGER! <3-Z. Mushroom> | `mob` | **`9101001`** | 1 |
| 5 | `2111` | DANGER! <1-G. Mushroom> | `mob` | **`9101000`** | 1 |
| 6 | `2113` | DANGER! <3-Z. Mushroom> | `mob` | **`9101001`** | 1 |
| 7 | `21717` | Rowen's Request 1 | `mob` | **`9101000`** | 1 |
| 8 | `21718` | Rowen's Request 2 | `mob` | **`9101000`** | 1 |
| 9 | `21722` | Ghost Stumps | `mob` | **`9101002`** | 1 |
| 10 | `21727` | Zombie Mushrooms | `mob` | **`9101001`** | 1 |
| 11 | `2196` | Beginner Pirate's Fourth Training Session | `mob` | **`9101000`** | 1 |
| 12 | `2239` | Balrog and the Seal | `mob` | **`9101003`** | 1 |
| 13 | `28275` | [Hunt] You Were Bitten by a Green Mushroom? | `mob` | **`9101000`** | 1 |
| 14 | `4000` | 아종의 취미생활 1 | `npc` | **`9330016`** | 0 |
| 15 | `4000` | 아종의 취미생활 1 | `npc` | **`9330016`** | 1 |
| 16 | `4001` | 아종의 취미생활 2 | `npc` | **`9330016`** | 0 |
| 17 | `4001` | 아종의 취미생활 2 | `npc` | **`9330016`** | 1 |
| 18 | `4002` | 할머니가 아파요 | `npc` | **`9330017`** | 0 |
| 19 | `4002` | 할머니가 아파요 | `npc` | **`9330018`** | 1 |
| 20 | `4003` | 오씨의 버블티 사업 | `npc` | **`9330018`** | 0 |
| 21 | `4003` | 오씨의 버블티 사업 | `npc` | **`9330018`** | 1 |
| 22 | `4004` | 죽 요리 배달 | `npc` | **`9330018`** | 0 |
| 23 | `4005` | 껌팔이 소녀의 핑크예티 | `npc` | **`9330017`** | 0 |
| 24 | `4005` | 껌팔이 소녀의 핑크예티 | `npc` | **`9330017`** | 1 |
| 25 | `4006` | 연인의 마법선물 | `npc` | **`9330003`** | 0 |
| 26 | `4006` | 연인의 마법선물 | `npc` | **`9330003`** | 1 |
| 27 | `4007` | 완벽한 상자 | `npc` | **`9330005`** | 0 |
| 28 | `4007` | 완벽한 상자 | `npc` | **`9330005`** | 1 |
| 29 | `4009` | 영웅의 북 | `npc` | **`9330006`** | 0 |
| 30 | `4009` | 영웅의 북 | `npc` | **`9330006`** | 1 |
| 31 | `4010` | 영원의 눈물 | `npc` | **`9330004`** | 0 |
| 32 | `4010` | 영원의 눈물 | `npc` | **`9330004`** | 1 |
| 33 | `4011` | 주걸균의 음악상자 | `npc` | **`9330003`** | 0 |
| 34 | `4011` | 주걸균의 음악상자 | `npc` | **`9330019`** | 1 |
| 35 | `4012` | 목걸이를 찾아서 1 | `npc` | **`9330019`** | 0 |
| 36 | `4012` | 목걸이를 찾아서 1 | `npc` | **`9330020`** | 1 |
| 37 | `4013` | 목걸이를 찾아서 2 | `npc` | **`9330020`** | 0 |
| 38 | `4013` | 목걸이를 찾아서 2 | `npc` | **`9330003`** | 1 |
| 39 | `4109` | 정경찰의 부탁 | `npc` | **`9310005`** | 0 |
| 40 | `4109` | 정경찰의 부탁 | `npc` | **`9310005`** | 1 |
| 41 | `4200` | 폰라의 부탁 | `npc` | **`9250000`** | 0 |
| 42 | `4204` | 폰라를 위한 마법의 화장품 | `npc` | **`9250000`** | 1 |
| 43 | `4205` | 폰라의 파티복과 유리구두 | `npc` | **`9250000`** | 0 |
| 44 | `4205` | 폰라의 파티복과 유리구두 | `npc` | **`9250014`** | 1 |
| 45 | `4206` | 장로의 조건 | `npc` | **`9250014`** | 0 |
| 46 | `4206` | 장로의 조건 | `npc` | **`9250014`** | 1 |
| 47 | `4207` | 완성된 파티복과 유리구두 | `npc` | **`9250014`** | 0 |
| 48 | `4207` | 완성된 파티복과 유리구두 | `npc` | **`9250000`** | 1 |
| 49 | `4208` | 아픈 뱀을 위한 영양식 I | `npc` | **`9250001`** | 0 |
| 50 | `4208` | 아픈 뱀을 위한 영양식 I | `npc` | **`9250001`** | 1 |
*(Showing first 50 of 90 entries)*

---

## 4. Portals Leading to Missing Maps (Total: 50)
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
| 16 | `706030000` | 9th Anniversary Event | `home00` | **`691000100`** |
| 17 | `970033000` | *(unnamed)* | `test` | **`970033001`** |
| 18 | `350020019` | Ship Deck 3 | `west00` | **`350003000`** |
| 19 | `350020015` | Lady Syl's Room | `out00` | **`103050100`** |
| 20 | `302090405` | A Nagging Doubt | `east00` | **`302040000`** |
| 21 | `325090210` | Underground Plaza | `west00` | **`325000110`** |
| 22 | `302090410` | White Mage's Darkness | `west00` | **`302040000`** |
| 23 | `310010010` | Training Room Entrance | `tr01` | **`91000`** |
| 24 | `350020014` | Lady Syl's Room | `out00` | **`103050100`** |
| 25 | `350020018` | Ship Deck 1 | `west00` | **`350003300`** |
| 26 | `350020018` | Ship Deck 1 | `east00` | **`350003100`** |
| 27 | `410000300` | Fountain Square | `in00` | **`993162016`** |
| 28 | `450000020` | Tynerum Horizon | `pt_horizon` | **`105300000`** |
| 29 | `811000001` | Near Momiji Hills 1 | `out00` | **`807000000`** |
| 30 | `863000100` | Heart Tree Guardian Grove | `ra10` | **`86000`** |
| 31 | `867125750` | Eliminate the 6th Angel | `player1` | **`812010100`** |
| 32 | `867125750` | Eliminate the 6th Angel | `player2` | **`812010100`** |
| 33 | `867125750` | Eliminate the 6th Angel | `player3` | **`812010100`** |
| 34 | `867125750` | Eliminate the 6th Angel | `player4` | **`812010100`** |
| 35 | `867125750` | Eliminate the 6th Angel | `player5` | **`812010100`** |
| 36 | `811000008` | Master Room | `out00` | **`807000000`** |
| 37 | `867136124` | Front Yard | `west00` | **`100030200`** |
| 38 | `811000011` | Honnou-ji Interior | `out00` | **`807100111`** |
| 39 | `865010200` | Berry | `berry_out` | **`610052100`** |
| 40 | `811000032` | Master Room | `out00` | **`807000000`** |
| 41 | `811000030` | *(unnamed)* | `out00` | **`807000000`** |
| 42 | `867120900` | Outdoor Theater Stage | `pt_west` | **`101073200`** |
| 43 | `867120800` | Ellinel Lake Shore | `pt_west` | **`101070100`** |
| 44 | `867120300` | Captain's Quarters | `867120300_out` | **`106030600`** |
| 45 | `867120100` | Shady Beach | `out00` | **`120041800`** |
| 46 | `814041000` | Step Game Arena | `player1` | **`812010100`** |
| 47 | `814041000` | Step Game Arena | `player2` | **`812010100`** |
| 48 | `814041000` | Step Game Arena | `player3` | **`812010100`** |
| 49 | `814041000` | Step Game Arena | `player4` | **`812010100`** |
| 50 | `814041000` | Step Game Arena | `player5` | **`812010100`** |

---

## 5. Mobs and NPCs Placed on Maps That Do Not Exist (Total: 4010)
Life placements in map files referencing non-existent mob or NPC IDs.

| # | Map ID | Map Name | Life Type | Life ID | Spawn Count |
| --- | --- | --- | --- | --- | --- |
| 1 | `1400` | *(unnamed)* | `mob` | **`9700042`** | 6 |
| 2 | `1500` | *(unnamed)* | `npc` | **`9390018`** | 1 |
| 3 | `67` | Suspicious Subway | `npc` | **`2491006`** | 1 |
| 4 | `17998` | *(unnamed)* | `npc` | **`1102100`** | 1 |
| 5 | `14441` | *(unnamed)* | `npc` | **`1033210`** | 1 |
| 6 | `17000` | *(unnamed)* | `npc` | **`1500004`** | 1 |
| 7 | `17000` | *(unnamed)* | `npc` | **`1500005`** | 1 |
| 8 | `17000` | *(unnamed)* | `npc` | **`1500006`** | 1 |
| 9 | `17000` | *(unnamed)* | `npc` | **`1500003`** | 1 |
| 10 | `17000` | *(unnamed)* | `npc` | **`1500001`** | 1 |
| 11 | `17000` | *(unnamed)* | `npc` | **`1500000`** | 1 |
| 12 | `17000` | *(unnamed)* | `npc` | **`1500007`** | 1 |
| 13 | `1580` | *(unnamed)* | `npc` | **`1064033`** | 1 |
| 14 | `1100` | *(unnamed)* | `mob` | **`9700041`** | 9 |
| 15 | `1532` | *(unnamed)* | `npc` | **`9390017`** | 1 |
| 16 | `1065` | The Trial of Earth 65 [Hard] | `npc` | **`2491006`** | 1 |
| 17 | `14025` | *(unnamed)* | `npc` | **`1104304`** | 1 |
| 18 | `19000` | *(unnamed)* | `npc` | **`1404015`** | 1 |
| 19 | `19100` | *(unnamed)* | `mob` | **`9833632`** | 9 |
| 20 | `19100` | *(unnamed)* | `mob` | **`9833694`** | 5 |
| 21 | `1064` | The Trial of Earth 65 [Normal] | `npc` | **`2491006`** | 1 |
| 22 | `14040` | *(unnamed)* | `npc` | **`1500003`** | 1 |
| 23 | `14040` | *(unnamed)* | `npc` | **`1500004`** | 1 |
| 24 | `14040` | *(unnamed)* | `npc` | **`1500006`** | 1 |
| 25 | `14040` | *(unnamed)* | `npc` | **`1500007`** | 1 |
| 26 | `14040` | *(unnamed)* | `npc` | **`1500028`** | 1 |
| 27 | `12000` | *(unnamed)* | `npc` | **`1002009`** | 1 |
| 28 | `12000` | *(unnamed)* | `npc` | **`2080007`** | 1 |
| 29 | `18030` | *(unnamed)* | `npc` | **`1404015`** | 1 |
| 30 | `1501` | *(unnamed)* | `npc` | **`9390018`** | 1 |
| 31 | `1110` | *(unnamed)* | `mob` | **`9700041`** | 9 |
| 32 | `1999` | *(unnamed)* | `mob` | **`9700050`** | 1 |
| 33 | `14000` | *(unnamed)* | `npc` | **`1012124`** | 1 |
| 34 | `17120` | *(unnamed)* | `npc` | **`1500006`** | 1 |
| 35 | `1120` | *(unnamed)* | `mob` | **`9700040`** | 16 |
| 36 | `1120` | *(unnamed)* | `mob` | **`9700049`** | 4 |
| 37 | `2065` | The Trial of Earth 65 [Hard] | `npc` | **`2491006`** | 1 |
| 38 | `14300` | *(unnamed)* | `npc` | **`1013102`** | 1 |
| 39 | `1531` | *(unnamed)* | `mob` | **`9700047`** | 47 |
| 40 | `1531` | *(unnamed)* | `npc` | **`9390017`** | 1 |
| 41 | `16001` | *(unnamed)* | `npc` | **`2159310`** | 1 |
| 42 | `17050` | *(unnamed)* | `mob` | **`9309049`** | 19 |
| 43 | `13300` | *(unnamed)* | `npc` | **`1013207`** | 1 |
| 44 | `17031` | *(unnamed)* | `mob` | **`1`** | 1 |
| 45 | `17031` | *(unnamed)* | `mob` | **`9833694`** | 3 |
| 46 | `10020` | *(unnamed)* | `npc` | **`9390019`** | 1 |
| 47 | `201` | *(unnamed)* | `npc` | **`9390017`** | 1 |
| 48 | `14401` | *(unnamed)* | `npc` | **`1033210`** | 1 |
| 49 | `18110` | *(unnamed)* | `npc` | **`1500006`** | 1 |
| 50 | `1530` | *(unnamed)* | `npc` | **`9390017`** | 1 |
*(Showing first 50 of 4010 placements)*

