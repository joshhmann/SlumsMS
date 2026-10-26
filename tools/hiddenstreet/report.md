# SlumsMS Hidden Street Data Sweep Report

Generated as part of Ticket 78 (Phase 1: Data Generator & Sweep Report).
This sweep checks cross-references across WZ XML data and server database tables to detect broken references, missing assets, and unlinked content.

## Summary of Broken References

| Category | Issue Count | Description |
| --- | --- | --- |
| **Items with no name** | 2264 | Items in Item.wz/Character.wz with missing or empty String.wz name |
| **Drops / Rewards / Shop missing items** | 0 | DB drop_data, reactordrops, shopitems or quest rewards pointing to missing items |
| **Quests with missing references** | 89 | Quests pointing to non-existent NPCs, maps, items, or mobs |
| **Portals leading to missing maps** | 50 | Map portals targeting non-existent map IDs |
| **Mobs / NPCs placed that don't exist** | 4010 | Maps with life spawns (type 'm' or 'n') pointing to missing mob/NPC IDs |

### Dataset Entity Counts
- **Items:** 60,167
- **Monsters:** 6,773
- **Maps:** 11,338
- **NPCs:** 8,340
- **Quests:** 2,343

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

## 3. Quests with Missing References (Total: 89)
Quests requiring or involving an NPC, map, item, or mob that does not exist.

| # | Quest ID | Quest Name | Ref Type | Missing ID | State (0=Start, 1=Complete) |
| --- | --- | --- | --- | --- | --- |
| 1 | `20726` | Information From Those Who Remember | `mob` | **`9101001`** | 1 |
| 2 | `2105` | DANGER! <1-G. Mushroom> | `mob` | **`9101000`** | 1 |
| 3 | `2107` | DANGER! <3-Z. Mushroom> | `mob` | **`9101001`** | 1 |
| 4 | `2111` | DANGER! <1-G. Mushroom> | `mob` | **`9101000`** | 1 |
| 5 | `2113` | DANGER! <3-Z. Mushroom> | `mob` | **`9101001`** | 1 |
| 6 | `21717` | Rowen's Request 1 | `mob` | **`9101000`** | 1 |
| 7 | `21718` | Rowen's Request 2 | `mob` | **`9101000`** | 1 |
| 8 | `21722` | Ghost Stumps | `mob` | **`9101002`** | 1 |
| 9 | `21727` | Zombie Mushrooms | `mob` | **`9101001`** | 1 |
| 10 | `2196` | Beginner Pirate's Fourth Training Session | `mob` | **`9101000`** | 1 |
| 11 | `2239` | Balrog and the Seal | `mob` | **`9101003`** | 1 |
| 12 | `28275` | [Hunt] You Were Bitten by a Green Mushroom? | `mob` | **`9101000`** | 1 |
| 13 | `4000` | 아종의 취미생활 1 | `npc` | **`9330016`** | 0 |
| 14 | `4000` | 아종의 취미생활 1 | `npc` | **`9330016`** | 1 |
| 15 | `4001` | 아종의 취미생활 2 | `npc` | **`9330016`** | 0 |
| 16 | `4001` | 아종의 취미생활 2 | `npc` | **`9330016`** | 1 |
| 17 | `4002` | 할머니가 아파요 | `npc` | **`9330017`** | 0 |
| 18 | `4002` | 할머니가 아파요 | `npc` | **`9330018`** | 1 |
| 19 | `4003` | 오씨의 버블티 사업 | `npc` | **`9330018`** | 0 |
| 20 | `4003` | 오씨의 버블티 사업 | `npc` | **`9330018`** | 1 |
| 21 | `4004` | 죽 요리 배달 | `npc` | **`9330018`** | 0 |
| 22 | `4005` | 껌팔이 소녀의 핑크예티 | `npc` | **`9330017`** | 0 |
| 23 | `4005` | 껌팔이 소녀의 핑크예티 | `npc` | **`9330017`** | 1 |
| 24 | `4006` | 연인의 마법선물 | `npc` | **`9330003`** | 0 |
| 25 | `4006` | 연인의 마법선물 | `npc` | **`9330003`** | 1 |
| 26 | `4007` | 완벽한 상자 | `npc` | **`9330005`** | 0 |
| 27 | `4007` | 완벽한 상자 | `npc` | **`9330005`** | 1 |
| 28 | `4009` | 영웅의 북 | `npc` | **`9330006`** | 0 |
| 29 | `4009` | 영웅의 북 | `npc` | **`9330006`** | 1 |
| 30 | `4010` | 영원의 눈물 | `npc` | **`9330004`** | 0 |
| 31 | `4010` | 영원의 눈물 | `npc` | **`9330004`** | 1 |
| 32 | `4011` | 주걸균의 음악상자 | `npc` | **`9330003`** | 0 |
| 33 | `4011` | 주걸균의 음악상자 | `npc` | **`9330019`** | 1 |
| 34 | `4012` | 목걸이를 찾아서 1 | `npc` | **`9330019`** | 0 |
| 35 | `4012` | 목걸이를 찾아서 1 | `npc` | **`9330020`** | 1 |
| 36 | `4013` | 목걸이를 찾아서 2 | `npc` | **`9330020`** | 0 |
| 37 | `4013` | 목걸이를 찾아서 2 | `npc` | **`9330003`** | 1 |
| 38 | `4109` | 정경찰의 부탁 | `npc` | **`9310005`** | 0 |
| 39 | `4109` | 정경찰의 부탁 | `npc` | **`9310005`** | 1 |
| 40 | `4200` | 폰라의 부탁 | `npc` | **`9250000`** | 0 |
| 41 | `4204` | 폰라를 위한 마법의 화장품 | `npc` | **`9250000`** | 1 |
| 42 | `4205` | 폰라의 파티복과 유리구두 | `npc` | **`9250000`** | 0 |
| 43 | `4205` | 폰라의 파티복과 유리구두 | `npc` | **`9250014`** | 1 |
| 44 | `4206` | 장로의 조건 | `npc` | **`9250014`** | 0 |
| 45 | `4206` | 장로의 조건 | `npc` | **`9250014`** | 1 |
| 46 | `4207` | 완성된 파티복과 유리구두 | `npc` | **`9250014`** | 0 |
| 47 | `4207` | 완성된 파티복과 유리구두 | `npc` | **`9250000`** | 1 |
| 48 | `4208` | 아픈 뱀을 위한 영양식 I | `npc` | **`9250001`** | 0 |
| 49 | `4208` | 아픈 뱀을 위한 영양식 I | `npc` | **`9250001`** | 1 |
| 50 | `4209` | 아픈 뱀을 위한 영양식 I I | `npc` | **`9250001`** | 0 |
*(Showing first 50 of 89 entries)*

---

## 4. Portals Leading to Missing Maps (Total: 50)
Map portals whose destination `tm` does not correspond to any valid map.

| # | Source Map ID | Source Map Name | Portal Name | Target Map ID |
| --- | --- | --- | --- | --- |
| 1 | `867120900` | Outdoor Theater Stage | `pt_west` | **`101073200`** |
| 2 | `867120800` | Ellinel Lake Shore | `pt_west` | **`101070100`** |
| 3 | `811000030` | *(unnamed)* | `out00` | **`807000000`** |
| 4 | `814041000` | Step Game Arena | `player1` | **`812010100`** |
| 5 | `814041000` | Step Game Arena | `player2` | **`812010100`** |
| 6 | `814041000` | Step Game Arena | `player3` | **`812010100`** |
| 7 | `814041000` | Step Game Arena | `player4` | **`812010100`** |
| 8 | `814041000` | Step Game Arena | `player5` | **`812010100`** |
| 9 | `867120100` | Shady Beach | `out00` | **`120041800`** |
| 10 | `867120300` | Captain's Quarters | `867120300_out` | **`106030600`** |
| 11 | `811000001` | Near Momiji Hills 1 | `out00` | **`807000000`** |
| 12 | `863000100` | Heart Tree Guardian Grove | `ra10` | **`86000`** |
| 13 | `867125750` | Eliminate the 6th Angel | `player1` | **`812010100`** |
| 14 | `867125750` | Eliminate the 6th Angel | `player2` | **`812010100`** |
| 15 | `867125750` | Eliminate the 6th Angel | `player3` | **`812010100`** |
| 16 | `867125750` | Eliminate the 6th Angel | `player4` | **`812010100`** |
| 17 | `867125750` | Eliminate the 6th Angel | `player5` | **`812010100`** |
| 18 | `811000008` | Master Room | `out00` | **`807000000`** |
| 19 | `867136124` | Front Yard | `west00` | **`100030200`** |
| 20 | `811000011` | Honnou-ji Interior | `out00` | **`807100111`** |
| 21 | `811000032` | Master Room | `out00` | **`807000000`** |
| 22 | `865010200` | Berry | `berry_out` | **`610052100`** |
| 23 | `18100` | *(unnamed)* | `st01` | **`18104`** |
| 24 | `14300` | *(unnamed)* | `house00` | **`100014300`** |
| 25 | `18998` | *(unnamed)* | `st02` | **`18101`** |
| 26 | `2000` | *(unnamed)* | `out03` | **`1013`** |
| 27 | `2010` | *(unnamed)* | `cc3` | **`171`** |
| 28 | `100` | *(unnamed)* | `out03` | **`70`** |
| 29 | `16202` | *(unnamed)* | `out01` | **`16211`** |
| 30 | `18103` | *(unnamed)* | `st02` | **`18101`** |
| 31 | `1500` | *(unnamed)* | `house00` | **`100001500`** |
| 32 | `66` | NY Streets | `out03` | **`70`** |
| 33 | `1501` | *(unnamed)* | `house00` | **`100001500`** |
| 34 | `4000` | *(unnamed)* | `in04` | **`4200`** |
| 35 | `4000` | *(unnamed)* | `in00` | **`4003`** |
| 36 | `11020` | *(unnamed)* | `st00` | **`100011020`** |
| 37 | `706030000` | 9th Anniversary Event | `home00` | **`691000100`** |
| 38 | `701100000` | Yu Garden | `YuGa01` | **`91000`** |
| 39 | `970033000` | *(unnamed)* | `test` | **`970033001`** |
| 40 | `350020014` | Lady Syl's Room | `out00` | **`103050100`** |
| 41 | `350020018` | Ship Deck 1 | `west00` | **`350003300`** |
| 42 | `350020018` | Ship Deck 1 | `east00` | **`350003100`** |
| 43 | `350020019` | Ship Deck 3 | `west00` | **`350003000`** |
| 44 | `350020015` | Lady Syl's Room | `out00` | **`103050100`** |
| 45 | `302090405` | A Nagging Doubt | `east00` | **`302040000`** |
| 46 | `325090210` | Underground Plaza | `west00` | **`325000110`** |
| 47 | `302090410` | White Mage's Darkness | `west00` | **`302040000`** |
| 48 | `310010010` | Training Room Entrance | `tr01` | **`91000`** |
| 49 | `410000300` | Fountain Square | `in00` | **`993162016`** |
| 50 | `450000020` | Tynerum Horizon | `pt_horizon` | **`105300000`** |

---

## 5. Mobs and NPCs Placed on Maps That Do Not Exist (Total: 4010)
Life placements in map files referencing non-existent mob or NPC IDs.

| # | Map ID | Map Name | Life Type | Life ID | Spawn Count |
| --- | --- | --- | --- | --- | --- |
| 1 | `200101400` | Inside the Coliseum | `npc` | **`2170006`** | 1 |
| 2 | `200101508` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 3 | `200101500` | Challenging Xerxes | `npc` | **`2170007`** | 1 |
| 4 | `200101504` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 5 | `211080000` | Gardener's Spot | `npc` | **`2162000`** | 1 |
| 6 | `211080000` | Gardener's Spot | `npc` | **`2160000`** | 1 |
| 7 | `200101501` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 8 | `200101505` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 9 | `200101509` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 10 | `273000000` | Twilight Perion | `npc` | **`2142113`** | 1 |
| 11 | `200101503` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 12 | `200101507` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 13 | `200100010` | Chryse Hideout | `npc` | **`2170000`** | 1 |
| 14 | `200100010` | Chryse Hideout | `npc` | **`2170001`** | 1 |
| 15 | `200100010` | Chryse Hideout | `npc` | **`2170003`** | 1 |
| 16 | `200100010` | Chryse Hideout | `npc` | **`2170002`** | 1 |
| 17 | `211060400` | Second Tower | `npc` | **`2160000`** | 1 |
| 18 | `200101502` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 19 | `200101506` | *(unnamed)* | `npc` | **`2170007`** | 1 |
| 20 | `200100000` | Road to Chryse | `npc` | **`2170004`** | 1 |
| 21 | `867123850` | Forest Depths Data Fragment | `npc` | **`9400527`** | 1 |
| 22 | `865020300` | Canal 4 | `mob` | **`9390810`** | 15 |
| 23 | `865020300` | Canal 4 | `npc` | **`9390250`** | 1 |
| 24 | `867142018` | *(unnamed)* | `npc` | **`9400431`** | 1 |
| 25 | `867142018` | *(unnamed)* | `npc` | **`9400447`** | 2 |
| 26 | `867142018` | *(unnamed)* | `npc` | **`9400435`** | 1 |
| 27 | `867142018` | *(unnamed)* | `npc` | **`9400436`** | 1 |
| 28 | `867142018` | *(unnamed)* | `npc` | **`9400449`** | 1 |
| 29 | `867142018` | *(unnamed)* | `npc` | **`9400450`** | 1 |
| 30 | `867142018` | *(unnamed)* | `npc` | **`9400451`** | 1 |
| 31 | `867113723` | The Afterlands | `npc` | **`9400321`** | 1 |
| 32 | `867200110` | *(unnamed)* | `npc` | **`9400570`** | 1 |
| 33 | `867136122` | HQ | `npc` | **`9400805`** | 1 |
| 34 | `867136122` | HQ | `npc` | **`9400806`** | 1 |
| 35 | `867202200` | *(unnamed)* | `npc` | **`9400570`** | 1 |
| 36 | `865020061` | Canal Battleground 5 | `npc` | **`9390208`** | 1 |
| 37 | `865020061` | Canal Battleground 5 | `npc` | **`9390232`** | 4 |
| 38 | `865020061` | Canal Battleground 5 | `npc` | **`9390233`** | 4 |
| 39 | `865020061` | Canal Battleground 5 | `npc` | **`9390234`** | 1 |
| 40 | `867149660` | Stage 5 | `mob` | **`9601390`** | 19 |
| 41 | `867149660` | Stage 5 | `npc` | **`9401145`** | 1 |
| 42 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390300`** | 1 |
| 43 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390301`** | 1 |
| 44 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390302`** | 1 |
| 45 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390315`** | 1 |
| 46 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390304`** | 1 |
| 47 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390305`** | 1 |
| 48 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390307`** | 1 |
| 49 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390308`** | 1 |
| 50 | `867140300` | Beast Tamer Epilogue 1 | `npc` | **`9390309`** | 1 |
*(Showing first 50 of 4010 placements)*

---

## 6. Cleaning (1.6.0)

### Duplicate rows removed

| Where | Rows removed |
| --- | --- |
| DB `drop_data` (22,225 rows) | 0 |
| DB `drop_data_global` (6 rows) | 0 |
| DB `reactordrops` (1,106 rows) | 40 |
| DB `shopitems` (3,879 rows) | 0 |
| items.json `quests_require` | 2113 |
| items.json `quests_reward` | 26 |
| items.json `shops` | 19 |
| maps.json `portals` | 7256 |
| npcs.json `shop_items` | 19 |

Rule: DB rows identical except their row id are one row; repeated rows inside an entity's lists are dropped; a shop listing the same item at the same price twice is one offer.

### Korean-only names (excluded)

Rule: a name with Hangul and no Latin letter is Korean-only (untranslated KMS data); names with both are only reported.

| Kind | Korean-only | Mixed Korean/English (kept) |
| --- | --- | --- |
| items | 231 | 48 |
| mobs | 16 | 2 |
| maps | 14 | 0 |
| npcs | 13 | 0 |
| quests | 569 | 24 |
- items: `23026` 크리스토프 성형1, `23027` 크리스토프 성형2, `23126` 크리스토프 성형1, `23127` 크리스토프 성형2, `23226` 크리스토프 성형1, `23227` 크리스토프 성형2, `23326` 크리스토프 성형1, `23327` 크리스토프 성형2, `23426` 크리스토프 성형1, `23427` 크리스토프 성형2, `23526` 크리스토프 성형1, `23527` 크리스토프 성형2, `23626` 크리스토프 성형1, `23627` 크리스토프 성형2, `23726` 크리스토프 성형1 ... (+216)
- mobs: `6400005` 주니어 발록, `9010098` 황금돼지바 배달부, `9303162` 돼지바 돼지, `9303163` 돼지바 돼지, `9400303` 방벽전체크용, `9402056` 밤의 용사 버섯, `9402057` 밤의 용사 발록, `9700030` 해군 이등병 유령, `9700031` 해군 일등병 유령, `9700032` 해군 상병 유령, `9700033` 해군 병장 유령, `9700034` 해군 하사 유령, `9700035` 일등 항해사, `9700036` 나무 상자, `9700037` 유령선 함장 ... (+1)
- maps: `229020000` 음산한 호박밭, `229020100` 어두운 호박밭, `229020200` 그늘진 호박밭, `229020300` 외딴 호박밭, `229020400` 조용한 호박밭, `229030000` 첫번째 방, `229030100` 두번째 방, `680000000` 웨딩빌리지, `680000200` 웨딩홀 로비, `680000210` 웨딩홀, `680000300` 웨딩케이크 스튜디오, `680000400` 웨딩파크, `680000401` 웨딩박스, `680000500` 나가는 곳
- npcs: `1013000` 미르, `9330021` 니키, `9330022` 줄리, `9330023` 허치희, `9330024` 곽이랑, `9330025` 소부현, `9330026` 동네 총각, `9330027` 동네 아가씨, `9330028` 과일가게 할아버지, `9330029` 유유, `9330030` 불량 청소년, `9330031` 과일주스 아줌마, `9330032` 과일가게 할아버지
- quests: `1115` 마야의 사랑의 증표, `1116` 로웬의 사랑의 증표, `1117` 이얀의 사랑의 증표, `1118` 넬라의 사랑의 증표, `1119` 에릭손의 사랑의 증표, `1120` 티군의 사랑의 증표, `1121` 뮤즈의 사랑의 증표, `1122` 팜의 사랑의 증표, `1123` 한태수의 사랑의 증표, `1124` 지유르의 사랑의 증표, `2147` 스텀피의 묘목 기르기, `4000` 아종의 취미생활 1, `4001` 아종의 취미생활 2, `4002` 할머니가 아파요, `4003` 오씨의 버블티 사업 ... (+554)

### Duplicate names labelled

| Kind | Entities given a qualifier |
| --- | --- |
| items | 15305 |
| mobs | 3252 |
| maps | 3840 |
| npcs | 1070 |
| quests | 211 |

Entities of one kind that share a name get display_name = name + ' (qualifier)'; the first of the group keeps the plain name (mobs: a normal-range, placed mob first; items: a non-cash item that drops, sells or is a reward; maps and NPCs: one with life or a placement; then the lowest id). Qualifiers, first unused wins: mobs 'event' (ids 9400000-9599999), 'party quest' (9300000-9399999), 'special' (9000000-9299999), 'boss' (WZ boss flag), then 'Lv. N'; items 'cash', category, 'Lv. N'; maps street name; NPCs first map placed; quests start NPC, 'Lv. N'; last resort '#id'. 'name' stays the raw String.wz name.

### Drop rates

Chances in this dataset are BASE chances. Bera multiplies mob drops by 4 (bosses by 3), quest items included, and reactor drops by 4. Global drops (NX cards, Maple Leaves, Chaos/White Scrolls, Flaming Feather) are NOT multiplied. Meso drops: the chance x4 (boss x3) and the amount x10.

### Weapon magic attack

Weapons: in SlumsMS's (MapleRoot) WZ every v83 weapon has incMAD equal to incPAD (e.g. Maple Kandayo 1472032: 74/74; upstream Cosmic: 23 attack, no magic attack). The shipped client Data/Character/Weapon has the same values, and the server adds equip incMAD to total magic attack (Character.recalcLocalStats), so the in-game tooltip and stats show it. The dataset keeps the values as the game uses them. It only matters to magic attacks: a claw's magic attack does nothing for a thief, but a magician can hold a no-job-requirement weapon for magic attack.

## 7. SlumsMS vs stock v83

Stock source: `/root/work/cosmic-triage/Cosmic/wz` (https://github.com/P0nk/Cosmic.git @ fec53bc77 2026-02-03 Merge pull request #334 from anliting/fix-quest-21010-reward #patch). Values are SlumsMS's own WZ (what the game uses). 'stock' holds the upstream Cosmic v83 value for every compared stat that differs (a stat missing on one side counts as 0), with slums_changed true; slums_new true means the entity does not exist in stock v83 (MapleRoot/SlumsMS custom or later-version content). Entities with a name but no WZ data of their own get neither flag.

| | Changed from stock | Not in stock |
| --- | --- | --- |
| Items | 4,632 | 42,108 |
| Mobs | 49 | 5,219 |

### Items changed, by category

| Category | Changed | Not in stock |
| --- | --- | --- |
| Etc | 1,091 | 4,304 |
| Weapon | 996 | 4,660 |
| Consume | 656 | 7,128 |
| Cap | 402 | 1,926 |
| Accessory | 282 | 2,515 |
| Shoes | 226 | 874 |
| Pants | 222 | 202 |
| Coat | 219 | 257 |
| Longcoat | 208 | 1,486 |
| Glove | 184 | 399 |
| Cape | 78 | 754 |
| Shield | 48 | 2 |
| Ring | 20 | 853 |
| Hair | 0 | 7,669 |
| Face | 0 | 4,276 |
| Install | 0 | 1,843 |
| TamingMob | 0 | 1,421 |
| Pet | 0 | 788 |
| Cash | 0 | 389 |
| PetEquip | 0 | 362 |

Stats changed most often (items): tuc 2,711, slotMax 1,745, incMAD 1,001, incPAD 994, reqDEX 949, reqSTR 923, reqLUK 768, reqINT 343, reqLevel 10, reqJob 3, incDEX 3, time 3, incSTR 2, cursed 2, price 2

### Top items changes (largest relative change of a headline stat)

| # | ID | Name | Changes (stock->Slums) |
| --- | --- | --- | --- |
| 1 | `1402046` | Timeless Nibleheim | incPAD 112->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 2 | `1402047` | Reverse Nibleheim | incPAD 110->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 3 | `1402061` | GMS Claymore | incPAD 110->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 4 | `1412033` | Timeless Tabarzin | incPAD 114->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 5 | `1412034` | Reverse Tabarzin | incPAD 112->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 6 | `1412041` | GMS Battle Axe | incPAD 112->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 7 | `1422037` | Timeless Bellocce | incPAD 114->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 8 | `1422038` | Reverse Bellocce | incPAD 112->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 9 | `1422044` | GMS Maul | incPAD 112->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 10 | `1432047` | Timeless Alchupiz | incPAD 114->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 11 | `1432049` | Reverse Alchupiz | incPAD 112->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 12 | `1442002` | Eviscerator | incPAD 102->186, incMAD 0->186, tuc 7->12, reqSTR 310->0 |
| 13 | `1442063` | Timeless Diesra | incPAD 114->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 14 | `1442067` | Reverse Diesra | incPAD 112->186, incMAD 0->186, tuc 7->12, reqSTR 370->0 |
| 15 | `1452057` | Timeless Engaw | incPAD 107->186, incMAD 0->186, tuc 7->12, reqSTR 125->0, reqDEX 305->0 |
| 16 | `1452059` | Reverse Engaw | incPAD 105->186, incMAD 0->186, tuc 7->12, reqSTR 125->0, reqDEX 305->0 |
| 17 | `1462050` | Timeless Black Beauty | incPAD 110->186, incMAD 0->186, tuc 7->12, reqSTR 120->0, reqDEX 330->0 |
| 18 | `1462051` | Reverse Black Beauty | incPAD 108->186, incMAD 0->186, tuc 7->12, reqSTR 120->0, reqDEX 330->0 |
| 19 | `1472068` | Timeless Lampion | incPAD 56->186, incMAD 0->186, tuc 7->12, reqDEX 160->0, reqLUK 350->0 |
| 20 | `1472071` | Reverse Lampion | incPAD 54->186, incMAD 0->186, tuc 7->12, reqDEX 160->0, reqLUK 350->0 |
| 21 | `1482023` | Timeless Equinox | incPAD 84->186, incMAD 0->186, tuc 7->12, reqSTR 330->0, reqDEX 120->0 |
| 22 | `1482024` | Reverse Equinox | incPAD 82->186, incMAD 0->186, tuc 7->12, reqSTR 330->0, reqDEX 120->0 |
| 23 | `1482034` | GMS Knuckle | incPAD 82->186, incMAD 0->186, tuc 7->12, reqSTR 330->0, reqDEX 120->0 |
| 24 | `1492023` | Timeless Blindness | incPAD 84->186, incMAD 0->186, tuc 7->12, reqSTR 120->0, reqDEX 335->0 |
| 25 | `1492025` | Reverse Blindness | incPAD 82->186, incMAD 0->186, tuc 7->12, reqSTR 120->0, reqDEX 335->0 |

### Mobs changed, by category

| Category | Changed | Not in stock |
| --- | --- | --- |
| boss | 40 | 2,428 |
| Lv 1-30 | 7 | 746 |
| Lv 121+ | 1 | 1,297 |
| Lv 31-70 | 1 | 304 |
| Lv 71-120 | 0 | 444 |

Stats changed most often (mobs): exp 36, hp 25, eva 17, level 14, acc 14, PDDamage 13, mp 12, MDDamage 12, PADamage 11, MADamage 8, speed 1

### Top mobs changes (largest relative change of a headline stat)

| # | ID | Name | Changes (stock->Slums) |
| --- | --- | --- | --- |
| 1 | `8810018` | Horntail | exp 0->107545760 |
| 2 | `9001007` | Scarecrow for Training | level 35->1, hp 800->2000000000, PDDamage 50->0, MDDamage 20->0, acc 10->1, eva 15->0 |
| 3 | `9501018` | Stat Change Test | hp 300000->999999999, mp 1000->0, PDDamage 30->0, MDDamage 30->0, acc 200->0, speed -30->-999999999 |
| 4 | `8800002` | Zakum3 | exp 7000000->15780800 |
| 5 | `5090001` | Master Dummy (boss) | level 128->59, hp 15375000->5600, mp 120000->200, exp 114200->220, eva 64->0 |
| 6 | `6090004` | Rurumo | hp 7600000->7600, eva 100->999 |
| 7 | `8800003` | Zakum's Arm 1 | exp 1344000->0 |
| 8 | `8800004` | Zakum's Arm 2 | exp 1344000->0 |
| 9 | `8800005` | Zakum's Arm 3 | exp 896000->0 |
| 10 | `8800006` | Zakum's Arm 4 | exp 896000->0 |
| 11 | `8800007` | Zakum's Arm 5 | exp 1120000->0 |
| 12 | `8800008` | Zakum's Arm 6 | exp 1120000->0 |
| 13 | `8800009` | Zakum's Arm 7 | exp 1030400->0 |
| 14 | `8800010` | Zakum's Arm 8 | exp 1030400->0 |
| 15 | `8810000` | Horntail's Left Head | exp 9732030->0 |
| 16 | `8810001` | Horntail's Right Head | exp 9732030->0 |
| 17 | `8810002` | Horntail's Head A | exp 13902900->0 |
| 18 | `8810003` | Horntail's Head B | exp 20673700->0 |
| 19 | `8810004` | Horntail's Head C | exp 13902900->0 |
| 20 | `8810005` | Horntail's Left Hand | exp 9689900->0 |
| 21 | `8810006` | Horntail's Right Hand | exp 9689900->0 |
| 22 | `8810007` | Horntail's Wings | exp 11375100->0 |
| 23 | `8810008` | Horntail's Legs | exp 5476900->0 |
| 24 | `8810009` | Horntail's Tails | exp 3370400->0 |
| 25 | `6090000` | Riche | level 74->65, exp 25000->255 |
