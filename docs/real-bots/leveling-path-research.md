# Leveling path research (ticket 31)

First-draft research for [ticket 31](../tickets/31-leveling-path.md). It covers where v83-era players (GMS 2008-2009, before
Big Bang) trained, by level band and job group, with every spot mapped to a real MapleRoot map id. The machine-readable
draft is [leveling-path-draft.yaml](leveling-path-draft.yaml). Nothing reads it yet, and no code changed.

## How this was built

- **Guides:** three research passes over classic training guides:
  - Explorer warrior, thief and pirate, plus Aran.
  - Explorer mage and archer, plus Cygnus.
  - The later-GMS areas MapleRoot imported.

  Many classic sites are offline or blocked: Basilmarket and Southperry archives, hidden-street, strategywiki and
  GameFAQs all returned 403 to the fetcher. The usable sources were:
  - Ayumilove's 2008-2009 GMS guides, written during v0.6x-0.8x.
  - MapleRoyals forum guides. Royals is a v62-v83-style server, so its spots mirror v83 content, plus a few later maps.
  - DreamMS guides (a v82/v83 server).
  - Wikibooks.
  - For the later areas: Ayumilove's later guides, gamerempire, Orange Mushroom, The Story of Maple and Grandis
    Library.

  Each table row names its sources (codes are listed under [Sources](#sources)). A row with several codes is a
  consensus pick.
- **Map ids:** every id comes from the MapleRoot server's own data, copied read-only from
  `root@192.168.0.164:/opt/mapleroot/MapleRoot Full Repack/Server/wz/`. Nothing on that host was changed.
  - Names come from `String.wz/Map.img.xml`.
  - Each map exists as `Map.wz/Map/MapN/<id>.img.xml`.
  - Its `life` entries give the mobs and spawn counts.
  - Each mob's `Mob.wz/<id>.img.xml` `info` gives level, `elemAttr` and `undead`.

  The *Mob lv*, *Spawns* and element/undead tags in the tables are therefore what this server actually runs, not
  what the guides say.
- **Draft generation:** a small generator (kept out of the repo) builds both the tables and the YAML from one list,
  and fails on any id that is missing, has no mobs, sits more than 15 levels outside its band, or has the wrong
  rebirth gate. Every id in the draft passed those checks.

## Headline findings

1. **The v83 route is fully present.** Every consensus spot from levels 1-120 exists on MapleRoot, with mob levels
   matching GMS v83. That includes:
   - Ant Tunnel, Wild Boars, Terrace Hall, Path of Time.
   - Forest of Golem, Coolie Zombies, Ghost Ship 2 / Mysterious Path 3.
   - Wolf Spiders, the Aqua Road gorges, Gallopera.
   - Himes (Encounter with the Buddha), Skeles, Temple of Time.

   Some post-v83 content is also on the server, though the guides flag it as later: Ulu City and Destroyed Park
   (Duku) in Singapore, and Neo City. The draft uses it only at low weight.
2. **Today bots never leave Victoria, Orbis, El Nath or Ludi** (`TrainingRegions`). The guides' 50-120 route depends on:
   - **Singapore/Malaysia** (Ghost Ship, MP3, Fantasy Theme Park, Gallopera).
   - **Zipangu** (Himes, Showa, Ninja Castle).
   - **Aqua Road** (gorges).
   - **Leafre** (Kentaurus, Wyverns, Skeles).
   - **Temple of Time**.
   - **Magatia** (labs).
   - **Masteria** (Wolf Spiders, Lower Ascent, Haunted House).
   - **Mu Lung**.

   Ticket 03 travel must cover these before the draft is usable past level 50.
3. **`TrainingRegions` already admits Lion King's Castle without a rebirth.** Its El Nath window `211000000-212000000`
   contains the LKC gate range `211060000-211070550`, so a level-120 bot could walk in today. This should be
   fixed when the path lands.
4. **The rebirth gates have holes and odd edges.** `HyperTPHandler` only gates the Hyper Teleport Rock, not portals:
   - **Twilight Perion:** Warrior Grounds `273060300` (the classic party map) sits outside the range (`...273060200`),
     so it is not gated at all.
   - **LKC:** the later "3rd-job" Lion King's Castle maps `306010100-306010700` (mobs L110-117) are outside the LKC
     gate.
   - **Twilight Perion range also catches other areas:** Dark Ereve, Henesys Ruins and Leafre of Past (`272000xxx`).
   - **Knight Stronghold:** `271030000-271040300` is fully blocked from Hyper TP (`isRestrictedMap`).
   - **Grandis range catches more than endgame:** it contains Pantheon, Savage Terminal, Fox Ridge, Cheong-woon and
     Ristonia (110-142, see finding 5), not only Arcane.

   The draft tags every entry with the gate *the id range implies* (`rebirths:`). Bots should treat that as a hard
   rule even where a portal would let them walk in.
5. **MapleRoot rescaled most imported areas, so GMS guide levels don't apply:**

   | Area | GMS level | MapleRoot level (server data) |
   |---|---|---|
   | Pantheon (Kaiser/AB start) | 13-41 | 113-141 |
   | Savage Terminal (Ark start) | 13-42 | 110-184 |
   | Cheong-woon Valley (Hoyoung start) | 12-30 | 112-127 |
   | Ristonia / Toolen City | low | 110-130 |
   | Heliseum | 95-115 | 195-200 |
   | Kritias | 170-187 (2019) | two copies: 140 and 180, **every element "strong" (P2 H2 F2 I2 S2 L2 D2)** |
   | Arcane River | 200-275 | **all mobs level 200**; difficulty only via HP (44M at Vanishing Journey to 677M at Limina) |
   | Cernium / Burning Cernium | 260-264 | level 200, **maxHP 2147483647 (int max)**: effectively unkillable, exclude |
   | Mirror World "<1>..<7>" fields | Zero-only instanced dungeons, 100-180 | open fields 102-179, towns `32x000000`, no portal in from the classic world |
   | Lion King's Castle | 115-133 | 117-127 (matches) |
   | Twilight Perion | 190-198 | 190-198 (matches) |
   | Knight Stronghold | 168-184 | 168-184 (matches) |
   | Black Heaven Scrapyard to Maze | 200-219 | 200-219 (matches) |

6. **Above 200 there is nothing harder than level 219.** Without a rebirth, the only 200+ content is Black Heaven
   (`310070100-310070490`, mobs 200-219). It is ungated and reachable on foot from Edelstein (`310030300`). With
   rebirth 3, Arcane River adds HP-scaled level-200 mobs. Levels 220-255 must reuse these.
7. **Guide claims that the server data contradicts:**
   - **Sahel 1-3** are mobs L24-29 here (guides say 30-45), so they moved to the 21-30 band.
   - **Road to Oblivion monks** are `F2S3` (poison-weak, fire-strong), not ice-weak as the Paladin guide says.
   - **Deep Sea Gorge I/II** spawn Bone Fish (undead, holy-weak) plus Bombing Fish Houses. "Gobies" only come from
     the houses (Goby is not in any map's life list).
   - **Himes** are the mob `Dreamy Ghost` (L100, undead, holy-weak) on `800020130`.
   - **Ghost Ship 3/4/7** (`541010020/30/60`) and Ludibrium "Path of Time" `220050300` have **no spawns** on this
     server.
   - **Most "Round Table of Kentaurus" copies** (`240020502-530`) are empty.

## Coverage

Entries per band and job group in the draft:

| Band | general | warrior | mage_fp | mage_il | mage_cleric | archer | thief | pirate_brawler | pirate_gunslinger | aran | cygnus |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1-10 | 5 | - | 1 | 1 | 1 | 2 | - | - | - | 4 | 5 |
| 10-21 | 7 | 4 | 3 | 3 | 2 | 4 | 3 | 2 | 3 | 3 | 1 |
| 21-30 | 10 | 3 | 3 | 3 | 3 | 5 | 4 | 3 | 3 | 5 | - |
| 30-40 | 10 | 3 | 3 | 5 | 4 | 5 | 3 | 3 | 3 | 3 | - |
| 40-50 | 10 | 5 | 4 | 5 | 5 | 5 | 4 | 3 | 4 | 4 | - |
| 50-60 | 14 | 3 | 5 | 3 | 7 | 5 | 3 | 3 | 3 | 4 | - |
| 60-70 | 14 | 4 | 5 | 3 | 6 | 5 | 4 | 3 | 3 | 4 | - |
| 70-85 | 12 | 3 | 4 | 3 | 4 | 3 | 3 | 3 | 3 | 3 | - |
| 85-100 | 16 | 3 | 3 | 3 | 5 | 4 | 3 | 3 | 2 | 3 | - |
| 100-120 | 22 | 3 | 3 | 3 | 4 | 3 | 3 | 3 | 2 | 3 | - |
| 120-140 | 22 | - | - | 1 | 3 | - | - | - | - | - | - |
| 140-160 | 10 | - | - | - | - | - | - | - | - | - | - |
| 160-180 | 12 | - | - | - | - | - | - | - | - | - | - |
| 180-200 | 16 | - | - | - | - | - | - | - | - | - | - |
| 200-255 | 22 | - | - | - | - | - | - | - | - | - | - |

- **Cygnus:** Ereve starter maps only. After that they inherit the explorer route, as every Cygnus guide says:
  Dawn Warrior → warrior, Blaze Wizard → F/P, Wind Archer → archer, Night Walker → thief, Thunder Breaker → brawler.
- **Above 120:** the guides stop splitting by job (they turn to bossing: Zakum, Papulatus, Horntail and so on), so
  only the general list exists. Exceptions are clerics on Skeles and the mob-element tags in the tables.
- **Assassin and Bandit share `thief`.** Bandit-only notes (Meso Explosion at Himes/Bigfoot) are in the notes column.
  Bowman and crossbowman share `archer`.
- **Party quests** are listed separately in the YAML (`party_quests`: HPQ, KPQ, CPQ, LPQ, LMPQ, OPQ, PPQ, MPQ,
  CWKPQ), with lobby and stage ids, for a future party system. They are not grind maps.

## Spots by level band

Columns:
- **Mob lv:** min-max non-boss mob level on that map in MapleRoot's data.
- **Spawns:** mob spawn points in the map's life list.
- **Gate:** rebirths needed per the HyperTPHandler id ranges.
- **Mode:** solo or party, as the guides describe the spot.
- **Notes:** element tags in brackets come from `elemAttr`. `F` fire, `I` ice, `L` lightning, `S` poison, `H` holy,
  `D` dark, `P` physical. `1` immune, `2` strong, `3` weak. `U` = undead.
- **Src `MR`:** a MapleRoot-only zone, chosen from the server data because no v83 guide covers it.

### Levels 1-10: Starter islands and town edges

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Victoria Road: Henesys Hunting Ground I | 104040000 | 2-7 | 39 | solo | - | Henesys Hunting Ground I: snails, Shrooms, Pigs | CON |
| All jobs (fallback) | Victoria Road: The Hill East of Henesys | 100010000 | 2-8 | 52 | solo | - | Hill East of Henesys: bowman 1st-job field | DMS-ALL |
| All jobs (fallback) | Victoria Road: The Field South of Ellinia | 100050000 | 4-15 | 58 | solo | - | Field South of Ellinia [F3 L3 S1] | DMS-ALL |
| All jobs (fallback) | Victoria Road: The Tree That Grew I | 101010100 | 4-6 | 30 | solo | - | The Tree That Grew I [L3] | CON |
| All jobs (fallback) | Victoria Road: Kerning City Construction Site | 103010000 | 4-12 | 51 | solo | - | Kerning City Construction Site [L3] | AYU-B |
| Mage F/P | Victoria Road: Dungeon, Southern Forest I | 100040001 | 6-6 | 21 | solo | - | Southern Forest I: Slimes; mages leave Maple Island at 8 [L3] | RIL,RBISH,RLG |
| Mage I/L | Victoria Road: Dungeon, Southern Forest I | 100040001 | 6-6 | 21 | solo | - | Southern Forest I: Slimes (lightning-weak, L3) [L3] | RIL,RBISH,RLG |
| Cleric/Priest/Bishop | Victoria Road: Dungeon, Southern Forest I | 100040001 | 6-6 | 21 | solo | - | Southern Forest I: Slimes [L3] | RBISH |
| Archer (Hunter/Crossbowman) | Victoria Road: The Hill East of Henesys | 100010000 | 2-8 | 52 | solo | - |  | DMS-ALL |
| Archer (Hunter/Crossbowman) | Victoria Road: Henesys Hunting Ground I | 104040000 | 2-7 | 39 | solo | - |  | RBM |
| Cygnus (starter only) | Empress' Road: Training Forest I | 130010000 | 1-3 | 15 | solo | - | Training Forest I: Tino/Tiv | MSW-EREVE |
| Cygnus (starter only) | Empress' Road: Training Forest II | 130010100 | 5-7 | 24 | solo | - | Training Forest II: Timu/Tiru | MSW-EREVE |
| Cygnus (starter only) | Empress' Road: Timu's Forest | 130010110 | 5-5 | 14 | solo | - | Timu's Forest | MSW-EREVE |
| Cygnus (starter only) | Empress' Road: Tiru's Forest | 130010120 | 7-7 | 15 | solo | - | Tiru's Forest | MSW-EREVE |
| Cygnus (starter only) | Empress' Road: Training Forest III | 130010200 | 7-9 | 8 | solo | - | Training Forest III: Tiguru | MSW-EREVE |
| Aran | Snow Island: Snow-covered Field 1 | 140020000 | 3-3 | 21 | solo | - | Snow-covered Field 1: Murupa | ARB,AYA,DMS |
| Aran | Snow Island: Snow-covered Field 2 | 140020100 | 5-5 | 12 | solo | - | Snow-covered Field 2 | ARB |
| Aran | Snow Island: Snow-covered Field 3 | 140020200 | 7-7 | 18 | solo | - | Snow-covered Field 3 | ARB |
| Aran | Snow Island: Dangerous Forest | 140010200 | 9-9 | 13 | solo | - | Dangerous Forest: Murukun L9 | ARB |

### Levels 10-21: Victoria Island first job

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Victoria Road: Henesys Hunting Ground II | 104040001 | 7-15 | 43 | solo | - | HHG II [S1] | CAT,NL,SHAD |
| All jobs (fallback) | Victoria Road: Henesys Hunting Ground III | 104040002 | 8-15 | 42 | solo | - | HHG III [S1] | CAT,NL,SHAD |
| All jobs (fallback) | Victoria Road: The Tree That Grew II | 101010101 | 6-10 | 64 | solo | - | Tree That Grew II: Slime Tree [F3 L3] | AYU-B |
| All jobs (fallback) | Victoria Road: The Tree That Grew III | 101010102 | 10-15 | 43 | solo | - | Tree That Grew III [F3 S1] | SAIR,AYP |
| All jobs (fallback) | Hidden Street: The Pig Beach | 104010001 | 7-42 | 22 | solo | - | Pig Beach | AYW,PAL,AYP |
| All jobs (fallback) | Kerning City Subway: Line 1 <Area 1> | 103000101 | 15-15 | 64 | solo | - | Line 1 <Area 1>: Bubblings | CAT,NL,COMM |
| All jobs (fallback) | Hidden Street: Mushroom Garden | 104030001 | 2-15 | 34 | solo | - | Mushroom Garden [S1] | AYP |
| Warrior (Fighter/Page/Spearman) | Hidden Street: The Pig Beach | 104010001 | 7-42 | 22 | solo | - | Pig Beach: flat, dense | AYW,PAL |
| Warrior (Fighter/Page/Spearman) | Kerning City Subway: Line 1 <Area 1> | 103000101 | 15-15 | 64 | solo | - |  | CAT |
| Warrior (Fighter/Page/Spearman) | Victoria Road: East Rocky Mountain II | 101030401 | 4-10 | 40 | solo | - | East Rocky Mountain II: Dark Stumps [F3] | CAT |
| Warrior (Fighter/Page/Spearman) | Victoria Road: East Rocky Mountain III | 101030402 | 10-17 | 40 | solo | - | East Rocky Mountain III: Axe Stumps [F3] | CAT |
| Mage F/P | Victoria Road: Dungeon, Southern Forest III | 100040003 | 15-15 | 22 | solo | - | Southern Forest III: Green Mushroom [S1] | RIL,RBISH,RLG |
| Mage F/P | Victoria Road: Dungeon, Southern Forest IV | 100040004 | 15-15 | 19 | solo | - | Southern Forest IV: Bubbling | RIL,RLG |
| Mage F/P | Victoria Road: Dungeon, Southern Forest II | 100040002 | 6-6 | 23 | solo | - |  [L3] | RIL |
| Mage I/L | Victoria Road: Dungeon, Southern Forest III | 100040003 | 15-15 | 22 | solo | - |  [S1] | RIL,RLG |
| Mage I/L | Victoria Road: Dungeon, Southern Forest IV | 100040004 | 15-15 | 19 | solo | - |  | RIL |
| Mage I/L | Victoria Road: Dungeon, Southern Forest II | 100040002 | 6-6 | 23 | solo | - | Slimes are lightning-weak [L3] | RIL |
| Cleric/Priest/Bishop | Victoria Road: Dungeon, Southern Forest III | 100040003 | 15-15 | 22 | solo | - |  [S1] | RBISH |
| Cleric/Priest/Bishop | Hidden Street: The Pig Beach | 104010001 | 7-42 | 22 | solo | - | Pig Beach | RBISH |
| Archer (Hunter/Crossbowman) | Victoria Road: Kerning City Construction Site | 103010000 | 4-12 | 51 | solo | - | Kerning Construction Site: 'most recommended' [L3] | AYU-B,RSNIP |
| Archer (Hunter/Crossbowman) | Victoria Road: Henesys Hunting Ground III | 104040002 | 8-15 | 42 | solo | - |  [S1] | RBM |
| Archer (Hunter/Crossbowman) | Victoria Road: The Tree That Grew II | 101010101 | 6-10 | 64 | solo | - |  [F3 L3] | AYU-B |
| Archer (Hunter/Crossbowman) | Hidden Street: The Pig Beach | 104010001 | 7-42 | 22 | solo | - | Pigs can sit inside minimum range | WB |
| Thief (Assassin/Bandit) | Kerning City Subway: Line 1 <Area 1> | 103000101 | 15-15 | 64 | solo | - |  | NL,COMM |
| Thief (Assassin/Bandit) | Victoria Road: Henesys Hunting Ground III | 104040002 | 8-15 | 42 | solo | - |  [S1] | NL,SHAD |
| Thief (Assassin/Bandit) | Victoria Road: Dungeon, Southern Forest I | 100040001 | 6-6 | 21 | solo | - |  [L3] | NL |
| Pirate Brawler | Kerning City Subway: Line 1 <Area 1> | 103000101 | 15-15 | 64 | solo | - | Somersault Kick works without a knuckle | BUCC |
| Pirate Brawler | Victoria Road: The Tree That Grew III | 101010102 | 10-15 | 43 | solo | - |  [F3 S1] | SAIR |
| Pirate Gunslinger | Victoria Road: Henesys Hunting Ground II | 104040001 | 7-15 | 43 | solo | - |  [S1] | AYP |
| Pirate Gunslinger | Hidden Street: Mushroom Garden | 104030001 | 2-15 | 34 | solo | - |  [S1] | AYP |
| Pirate Gunslinger | Victoria Road: The Tree That Grew III | 101010102 | 10-15 | 43 | solo | - |  [F3 S1] | SAIR |
| Aran | Victoria Road: Henesys Hunting Ground III | 104040002 | 8-15 | 42 | solo | - |  [S1] | ARB |
| Aran | Hidden Street: The Pig Beach | 104010001 | 7-42 | 22 | solo | - |  | ARB |
| Aran | Hidden Street: Caution Falling Down | 103010001 | 12-15 | 10 | solo | - | Caution Falling Down [L3] | ARB |
| Cygnus (starter only) | Empress' Road: Tiguru's Forest | 130010210 | 9-9 | 5 | solo | - | Tiguru's Forest, then explorer route via Ereve boat | MSW-EREVE |

### Levels 21-30: Sleepywood, Perion, Kerning subway; KPQ

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Dungeon: Ant Tunnel III | 105050200 | 22-24 | 100 | solo | - | Ant Tunnel III: 100 spawns [U H3] | CAT,AYW,SHAD |
| All jobs (fallback) | Dungeon: Ant Tunnel IV | 105050300 | 22-24 | 65 | solo | - |  [U H3] | SHAD |
| All jobs (fallback) | Dungeon: Ant Tunnel II | 105050100 | 22-24 | 47 | solo | - |  [U H3] | SHAD |
| All jobs (fallback) | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - | Land of Wild Boar II [F3] | CAT,NL,SAIR |
| All jobs (fallback) | Hidden Street: Land of Wild Boar | 101040001 | 22-35 | 35 | solo | - | Land of Wild Boar [F3] | SAIR |
| All jobs (fallback) | Dungeon: The Cave of Evil Eye I | 105070100 | 27-35 | 21 | solo | - |  | RLG |
| All jobs (fallback) | Dungeon: The Cave of Evil Eye II | 105070200 | 27-35 | 43 | solo | - |  | NL |
| All jobs (fallback) | Victoria Road: Excavation Site III | 101030103 | 23-24 | 25 | solo | - | Excavation Site III: masks | NL,RBM |
| All jobs (fallback) | Sunset Road: Sahel 2 | 260020600 | 24-29 | 14 | solo | - | Sahel 2 (L24-29 here; guides say 30s) | AYA |
| All jobs (fallback) | Sunset Road: Sahel 1 | 260020700 | 29-29 | 10 | solo | - | Sahel 1: Scorpion L29 | AYA |
| Warrior (Fighter/Page/Spearman) | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - | Wild Boars: flat and dense [F3] | CAT,SIRE |
| Warrior (Fighter/Page/Spearman) | Dungeon: Ant Tunnel III | 105050200 | 22-24 | 100 | solo | - |  [U H3] | CAT,AYW |
| Warrior (Fighter/Page/Spearman) | Victoria Road: Tree Dungeon, Forest Up North VII | 101020008 | 22-22 | 19 | solo | - | Forest Up North VII | CAT |
| Mage F/P | Dungeon: Ant Tunnel III | 105050200 | 22-24 | 100 | solo | - |  [U H3] | RMAG30,DMS-ALL |
| Mage F/P | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - |  [F3] | RIL,RBISH |
| Mage F/P | Dungeon: The Cave of Evil Eye I | 105070100 | 27-35 | 21 | solo | - |  | RLG |
| Mage I/L | Dungeon: Ant Tunnel III | 105050200 | 22-24 | 100 | solo | - |  [U H3] | RMAG30 |
| Mage I/L | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - |  [F3] | RIL |
| Mage I/L | Kerning City Subway: Transfer Area | 103000102 | 20-20 | 38 | solo | - | Stirges for lightning | AYU-M |
| Cleric/Priest/Bishop | Dungeon: Ant Tunnel III | 105050200 | 22-24 | 100 | solo | - | Zombie Mushroom: undead, Heal hits [U H3] | RMAG30,AYU-M |
| Cleric/Priest/Bishop | Dungeon: Deep Ant Tunnel I | 105060100 | 22-35 | 71 | solo | - | Deep Ant Tunnel I: Zombie Mushroom [U H3] | AYU-M |
| Cleric/Priest/Bishop | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - |  [F3] | RBISH |
| Archer (Hunter/Crossbowman) | Victoria Road: Excavation Site III | 101030103 | 23-24 | 25 | solo | - |  | RBM,RBOW |
| Archer (Hunter/Crossbowman) | Victoria Road: Excavation Site II | 101030102 | 19-24 | 55 | solo | - |  | RBM |
| Archer (Hunter/Crossbowman) | Kerning City Subway: Line 1 <Area 1> | 103000101 | 15-15 | 64 | solo | - |  | AYU-B,RSNIP |
| Archer (Hunter/Crossbowman) | Ludibrium: Eos Tower 73rd Floor | 221023100 | 23-23 | 16 | solo | - | Eos Tower 73rd: Trixters, 3 spawn points [H2F3] | RSNIP |
| Archer (Hunter/Crossbowman) | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo/party | - | Boars are fast; better in a party [F3] | RSNIP |
| Thief (Assassin/Bandit) | Dungeon: Ant Tunnel III | 105050200 | 22-24 | 100 | solo | - |  [U H3] | SHAD,AYT |
| Thief (Assassin/Bandit) | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - |  [F3] | NL |
| Thief (Assassin/Bandit) | Dungeon: The Cave of Evil Eye II | 105070200 | 27-35 | 43 | solo | - |  | NL |
| Thief (Assassin/Bandit) | Victoria Road: Excavation Site III | 101030103 | 23-24 | 25 | solo | - |  | NL |
| Pirate Brawler | Hidden Street: Land of Wild Boar | 101040001 | 22-35 | 35 | solo | - |  [F3] | SAIR,AYP |
| Pirate Brawler | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - |  [F3] | SAIR |
| Pirate Brawler | Dungeon: Ant Tunnel III | 105050200 | 22-24 | 100 | solo | - |  [U H3] | AYP |
| Pirate Gunslinger | Victoria Road: Excavation Site III | 101030103 | 23-24 | 25 | solo | - |  | AYP |
| Pirate Gunslinger | Zipangu: Crow Forest | 800020000 | 25-25 | 26 | solo | - | Zipangu Crow Forest [L3] | AYP |
| Pirate Gunslinger | Sunset Road: Sahel 2 | 260020600 | 24-29 | 14 | solo | - | Sahel: Sand Rats/Scorpions | AYP |
| Aran | Dungeon: Ant Tunnel III | 105050200 | 22-24 | 100 | solo | - |  [U H3] | AYA,ARB |
| Aran | Dungeon: Deep Ant Tunnel I | 105060100 | 22-35 | 71 | solo | - |  [U H3] | AYA |
| Aran | Dungeon: Hunting Ground in the Deep Forest I | 105040100 | 27-35 | 29 | solo | - | Hunting Ground in the Deep Forest I [S2] | AYA |
| Aran | Sunset Road: Sahel 1 | 260020700 | 29-29 | 10 | solo | - | Sahel 1: high spawn, easy combo | AYA |
| Aran | Warning Street: Deep Valley II | 106000100 | 17-19 | 42 | solo | - | Deep Valley II: Ghost Stumps | ARB |

### Levels 30-40: Ludibrium Terrace Hall, Fire Boars; LPQ/CPQ

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Ludibrium: Terrace Hall | 220010500 | 30-32 | 36 | solo | - | Terrace Hall: Brown Teddy, the 30-35 consensus | CAT,DK,PAL,HERO,NL,RLG |
| All jobs (fallback) | Ludibrium: Cloud Terrace<2> | 220010300 | 32-34 | 33 | solo | - | Cloud Terrace<2> | RBM |
| All jobs (fallback) | Ludibrium: Sky Terrace<4> | 220010900 | 32-34 | 36 | solo | - |  | - |
| All jobs (fallback) | Dungeon: The Cave of Evil Eye III | 105070300 | 27-35 | 43 | solo | - |  | CAT |
| All jobs (fallback) | Warning Street: The Burnt Land I | 106000101 | 25-32 | 31 | solo | - | Burnt Land I: Fire Boar [F2I3] | AYU-B,AYP |
| All jobs (fallback) | Hidden Street: Dangerous Valley II | 106000002 | 32-32 | 29 | solo | - | Dangerous Valley II: Fire Boar [F2I3] | AYU-B |
| All jobs (fallback) | Kerning City Subway: Line 1 <Area 2> | 103000103 | 20-35 | 91 | solo | - | Line 1 <Area 2>: Jr. Wraith [U H3F3] | AYP |
| All jobs (fallback) | Ludibrium: The Path of Time <2> | 220040100 | 37-37 | 46 | solo | - | Path of Time <2>: Chronos L37 [U] | CAT |
| All jobs (fallback) | Ludibrium: The Path of Time <3> | 220040300 | 37-37 | 42 | solo | - | Path of Time <3> [U] | CAT |
| All jobs (fallback) | Mushroom Castle: Secluded Mushroom Forest | 106020100 | 30-30 | 18 | solo | - | Mushroom Castle questline | DMS-DW |
| Warrior (Fighter/Page/Spearman) | Ludibrium: Terrace Hall | 220010500 | 30-32 | 36 | solo | - |  | CAT,DK,PAL,HERO |
| Warrior (Fighter/Page/Spearman) | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - | Wild Boars to 38 [F3] | SIRE |
| Warrior (Fighter/Page/Spearman) | Dungeon: The Cave of Evil Eye III | 105070300 | 27-35 | 43 | solo | - |  | CAT |
| Mage F/P | Ludibrium: Terrace Hall | 220010500 | 30-32 | 36 | solo | - |  | RLG,RUPD |
| Mage F/P | Zipangu: Castle Corridor | 800040201 | 25-30 | 25 | solo | - | Ninja Castle Corridor: Genin/Ashigaru fire-weak (S3F3) [I3S3 S3F3] | RUPD |
| Mage F/P | El Nath: Snowy Hill | 211000200 | 32-35 | 47 | solo | - | Snowy Hill: Jr. Pepe/Leatty I2F3 [I2F3] | AYU-M |
| Mage I/L | Warning Street: The Burnt Land I | 106000101 | 25-32 | 31 | solo | - | Fire Boar F2I3 [F2I3] | AYU-M |
| Mage I/L | Hidden Street: Dangerous Valley II | 106000002 | 32-32 | 29 | solo | - |  [F2I3] | AYU-M |
| Mage I/L | Orbis: Orbis Tower <11th Floor> | 200081100 | 30-30 | 17 | solo | - | Orbis Tower 11F: Fire Sentinel F2I3 [F2I3] | AYU-M |
| Mage I/L | Ludibrium: Helios Tower (100th Floor) | 222020300 | 36-36 | 15 | solo | - | Helios 100th: Retz | AYU-M |
| Mage I/L | Florina Road: Lorang Lorang | 110020000 | 37-37 | 40 | solo | - | Lorang L3 [L3] | AYU-M |
| Cleric/Priest/Bishop | Kerning City Subway: Line 1 <Area 2> | 103000103 | 20-35 | 91 | solo | - | Jr. Wraith: undead [U H3F3] | RBISH,RCLER |
| Cleric/Priest/Bishop | Ludibrium: Terrace Hall | 220010500 | 30-32 | 36 | solo | - |  | RBISH,RCLER |
| Cleric/Priest/Bishop | Ludibrium: The Path of Time <2> | 220040100 | 37-37 | 46 | solo | - | Chronos: undead [U] | RBISH |
| Cleric/Priest/Bishop | Phantom Forest: Hollowed Ground | 682000001 | 35-35 | 12 | solo | - | Hollowed Ground [U H3F3] | RBISH |
| Archer (Hunter/Crossbowman) | Warning Street: The Burnt Land I | 106000101 | 25-32 | 31 | solo | - |  [F2I3] | AYU-B,RBM |
| Archer (Hunter/Crossbowman) | Hidden Street: Dangerous Valley II | 106000002 | 32-32 | 29 | solo | - |  [F2I3] | AYU-B |
| Archer (Hunter/Crossbowman) | Hidden Street: Monkey Swamp III | 107000403 | 21-40 | 44 | solo | - | Monkey Swamp III: Zombie Lupin, arrow bomb [U H3] | RLG,RBOW |
| Archer (Hunter/Crossbowman) | Hidden Street: Tree Dungeon, Monkey Forest II | 100040104 | 40-40 | 26 | solo | - | Monkey Forest II [U H3] | RBOW |
| Archer (Hunter/Crossbowman) | Ludibrium: Terrace Hall | 220010500 | 30-32 | 36 | solo | - |  | RBM |
| Thief (Assassin/Bandit) | Ludibrium: Terrace Hall | 220010500 | 30-32 | 36 | solo | - |  | NL |
| Thief (Assassin/Bandit) | Dungeon: The Cave of Evil Eye III | 105070300 | 27-35 | 43 | solo | - |  | NL |
| Thief (Assassin/Bandit) | Warning Street: The Burnt Land I | 106000101 | 25-32 | 31 | solo | - |  [F2I3] | AYT |
| Pirate Brawler | Ludibrium: Terrace Hall | 220010500 | 30-32 | 36 | solo | - |  | AYP |
| Pirate Brawler | Ludibrium: Cloud Terrace<5> | 220010000 | 30-39 | 22 | solo | - | Cloud Terrace<5>: Toy Trojans | AYP |
| Pirate Brawler | Zenumist Research Institute: Lab - Unit 101 | 261010001 | 32-35 | 25 | solo | - | Zenumist Lab 101: Cube Slime | AYP |
| Pirate Gunslinger | Ludibrium: Terrace Hall | 220010500 | 30-32 | 36 | solo | - |  | AYP |
| Pirate Gunslinger | Ludibrium: Helios Tower (100th Floor) | 222020300 | 36-36 | 15 | solo | - | Helios Tower: Retz | AYP |
| Pirate Gunslinger | Zenumist Research Institute: Lab - Unit 103 | 261010003 | 35-38 | 22 | solo | - | Zenumist Lab 103: Rumo | AYP |
| Aran | Warning Street: The Burnt Land I | 106000101 | 25-32 | 31 | solo | - | Fire Boars [F2I3] | AYA |
| Aran | Dungeon: The Cave of Evil Eye III | 105070300 | 27-35 | 43 | solo | - |  | HS |
| Aran | Hidden Street: The Land of Wild Boar II | 101030001 | 22-25 | 33 | solo | - |  [F3] | HS |

### Levels 40-50: Path of Time, Kerning subway, Magatia labs; LPQ/CPQ

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Ludibrium: The Path of Time <1> | 220040000 | 41-41 | 34 | solo | - | Path of Time <1>: Platoon Chronos [U] | SIRE,CAT,NL,RIL,RBM |
| All jobs (fallback) | Ludibrium: Lost Time <1> | 220050000 | 41-41 | 36 | solo | - |  [U] | SIRE |
| All jobs (fallback) | Ludibrium: Lost Time <2> | 220050200 | 46-46 | 38 | solo | - | Lost Time <2>: Master Chronos [U] | RBM |
| All jobs (fallback) | Kerning City Subway: Line 1 <Area 4> | 103000105 | 48-48 | 57 | solo | - | Line 1 <Area 4>: Wraith [U H3] | HERO,COMM |
| All jobs (fallback) | Dungeon: The Tunnel That Lost Light I | 105090000 | 45-50 | 80 | solo | - | Copper Drake | AYW |
| All jobs (fallback) | Warning Street: The Burnt Land IV | 106000130 | 42-45 | 30 | solo | - | Burnt Land IV | COMM |
| All jobs (fallback) | Alcadno Research Institute: Lab - Area B-3 | 261020600 | 42-42 | 14 | solo | - | Alcadno B-3: Iron Mutae, flat | AYP,AYA |
| All jobs (fallback) | Alcadno Research Institute: Lab - Area B-1 | 261020200 | 47-47 | 25 | solo | - |  | AYP |
| All jobs (fallback) | Singapore: Suburban Area 2 | 540020100 | 48-48 | 16 | solo | - | Suburban Area 2: Trucker [SG] [L3] | PAL,AYT |
| All jobs (fallback) | Florina Road: Lorang and Clang | 110030000 | 37-48 | 47 | solo | - | Lorang and Clang [L3] | AYU-M |
| Warrior (Fighter/Page/Spearman) | Ludibrium: The Path of Time <1> | 220040000 | 41-41 | 34 | solo | - |  [U] | SIRE,CAT |
| Warrior (Fighter/Page/Spearman) | Ludibrium: Lost Time <1> | 220050000 | 41-41 | 36 | solo | - |  [U] | SIRE |
| Warrior (Fighter/Page/Spearman) | Kerning City Subway: Line 1 <Area 4> | 103000105 | 48-48 | 57 | solo | - |  [U H3] | HERO |
| Warrior (Fighter/Page/Spearman) | Alcadno Research Institute: Lab - Area B-3 | 261020600 | 42-42 | 14 | solo | - | Mutae: flat | PAL |
| Warrior (Fighter/Page/Spearman) | Singapore: Suburban Area 2 | 540020100 | 48-48 | 16 | solo | - |  [L3] | PAL |
| Mage F/P | Dungeon: Another Path | 105090400 | 40-40 | 23 | solo | - | Another Path: Cold Eye I2F3 [I2F3] | AYU-M |
| Mage F/P | El Nath: Snowy Hill | 211000200 | 32-35 | 47 | solo | - | Jr. Pepe [I2F3] | AYU-M |
| Mage F/P | Ludibrium: Eos Tower 9th Floor | 221020800 | 35-42 | 14 | solo | - | Block Golem [L2I2F3] | AYU-M |
| Mage F/P | Kerning City Subway: Line 1 <Area 4> | 103000105 | 48-48 | 57 | solo | - |  [U H3] | AYU-M |
| Mage I/L | Ludibrium: The Path of Time <1> | 220040000 | 41-41 | 34 | solo | - |  [U] | RIL |
| Mage I/L | Florina Road: Lorang Lorang | 110020000 | 37-37 | 40 | solo | - | Lorang L3 [L3] | AYU-M |
| Mage I/L | Singapore: Suburban Area 2 | 540020100 | 48-48 | 16 | solo | - | Trucker L3 [L3] | RUPD |
| Mage I/L | Ludibrium: Helios Tower (100th Floor) | 222020300 | 36-36 | 15 | solo | - |  | RIL |
| Mage I/L | Orbis: Garden of Yellow I | 200010120 | 33-33 | 22 | solo | - | Garden of Yellow I: Jr. Lioner [L2I3] | AYU-M |
| Cleric/Priest/Bishop | Kerning City Subway: Line 1 <Area 4> | 103000105 | 48-48 | 57 | solo | - | Wraith: undead [U H3] | RBISH,RCLER |
| Cleric/Priest/Bishop | Ludibrium: The Path of Time <1> | 220040000 | 41-41 | 34 | solo | - |  [U] | RBISH |
| Cleric/Priest/Bishop | Ludibrium: Lost Time <2> | 220050200 | 46-46 | 38 | solo | - |  [U] | AYU-M |
| Cleric/Priest/Bishop | Hidden Street: Monkey Swamp III | 107000403 | 21-40 | 44 | solo | - | Zombie Lupin: undead [U H3] | AYU-M |
| Cleric/Priest/Bishop | Victoria Road: Remains <Tomb> I | 101030105 | 44-47 | 23 | solo | - | Remains <Tomb> I: Skeledog undead [U H3] | AYU-M |
| Archer (Hunter/Crossbowman) | Ludibrium: The Path of Time <1> | 220040000 | 41-41 | 34 | solo | - |  [U] | AYU-B,RSNIP,RBM |
| Archer (Hunter/Crossbowman) | Ludibrium: Lost Time <2> | 220050200 | 46-46 | 38 | solo | - | Snipe from platforms [U] | RBM |
| Archer (Hunter/Crossbowman) | Orbis: Cloud Park IV | 200050000 | 30-45 | 33 | solo | - | Cloud Park IV: Lunar Pixie [H2] | WB,AYU-B |
| Archer (Hunter/Crossbowman) | Ludibrium: Toy Factory <Process 1> Zone 6 | 220020500 | 36-41 | 38 | solo | - | Toy Factory: Robo [I2F2] | AYU-B |
| Archer (Hunter/Crossbowman) | Omega Sector: Off-Limits | 221030000 | 41-41 | 51 | solo | - | Omega Off-Limits: Mateon | AYU-B |
| Thief (Assassin/Bandit) | Ludibrium: The Path of Time <1> | 220040000 | 41-41 | 34 | solo | - |  [U] | NL |
| Thief (Assassin/Bandit) | Kerning City Subway: Line 1 <Area 4> | 103000105 | 48-48 | 57 | solo | - |  [U H3] | AYT |
| Thief (Assassin/Bandit) | Dungeon: Drake Hunting Ground | 105080000 | 35-50 | 29 | solo | - | Copper Drake | AYT |
| Thief (Assassin/Bandit) | Phantom Forest: Creeping Evil | 610010011 | 45-50 | 7 | solo | - | Creeping Evil, money [F3 H3I1] | NL |
| Pirate Brawler | Alcadno Research Institute: Lab - Area B-3 | 261020600 | 42-42 | 14 | solo | - |  | AYP |
| Pirate Brawler | Alcadno Research Institute: Lab - Area B-1 | 261020200 | 47-47 | 25 | solo | - |  | AYP |
| Pirate Brawler | Ludibrium: The Path of Time <1> | 220040000 | 41-41 | 34 | solo | - |  [U] | SAIR |
| Pirate Gunslinger | Orbis: Garden of Yellow I | 200010120 | 33-33 | 22 | solo | - | Orbis gardens [L2I3] | AYP |
| Pirate Gunslinger | Orbis: Garden of Red I | 200010110 | 33-33 | 22 | solo | - |  [F2I3] | AYP |
| Pirate Gunslinger | Zipangu: A Night in the Forest | 800020110 | 40-45 | 19 | solo | - | A Night in the Forest [U F2I3 L3] | AYP |
| Pirate Gunslinger | Haunted House: Study | 682000303 | 30-50 | 6 | solo | - | Haunted House Study | AYP |
| Aran | Alcadno Research Institute: Lab - Area B-3 | 261020600 | 42-42 | 14 | solo | - | single platform, godly spawn | AYA |
| Aran | Alcadno Research Institute: Lab - Area C-1 | 261020300 | 54-54 | 20 | solo | - | Alcadno C-1: Roid | AYA |
| Aran | Singapore: Suburban Area 2 | 540020100 | 48-48 | 16 | solo | - |  [L3] | AYA |
| Aran | Mu Lung: Practice Field : Beginner | 250020000 | 50-50 | 12 | solo | - | Straw Target Dummy | AYA |

### Levels 50-60: El Nath, Golems, Mu Lung, Singapore; OPQ/LMPQ/CPQ2

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - | Forest of Golem [H3] | SIRE,CAT,HERO,PAL,RIL |
| All jobs (fallback) | El Nath: Forest of Dead Trees I | 211041100 | 57-57 | 27 | solo | - | Forest of Dead Trees I: Coolie Zombie [U S1H3] | SHAD,NL,RBISH |
| All jobs (fallback) | El Nath: Forest of Dead Trees IV | 211041400 | 57-57 | 17 | solo | - |  [U S1H3] | CAT |
| All jobs (fallback) | Singapore: Ghost ship 1 | 541010000 | 58-58 | 26 | solo | - | Ghost ship 1 [SG] [U H3] | RUPD |
| All jobs (fallback) | Singapore: Ghost Ship 5 | 541010040 | 60-60 | 29 | solo | - | Ghost Ship 5 [SG]: Selkie Jr. [U F3] | CAT,SAIR |
| All jobs (fallback) | Mu Lung: Practice Field : Beginner | 250020000 | 50-50 | 12 | solo | - | Practice Field: Beginner | SIRE,NL |
| All jobs (fallback) | Mu Lung: Practice Field : Normal Level | 250020200 | 50-51 | 34 | solo | - |  | - |
| All jobs (fallback) | Alcadno Research Institute: Lab - Area C-1 | 261020300 | 54-54 | 20 | solo | - |  | AYP |
| All jobs (fallback) | Alcadno Research Institute: Lab - Area C-2 | 261020400 | 54-58 | 28 | solo | - |  | AYP,AYA |
| All jobs (fallback) | El Nath: Watch Out for Icy Path I | 211010000 | 50-55 | 26 | solo | - | Icy Path I: Jr. Yeti [I2F3] | AYU-M,AYP |
| All jobs (fallback) | El Nath: Cold Field I | 211030000 | 55-58 | 35 | solo | - | Cold Field I: Hector [I2F3] | AYU-M |
| All jobs (fallback) | Orbis: Cloud Park VI | 200080000 | 30-52 | 35 | solo | - | Cloud Park VI: Luster Pixie [I2F2L2H2] | AYU-B |
| All jobs (fallback) | Zipangu: Vanished Village | 800020120 | 56-60 | 24 | solo | - | Vanished Village: Water Goblin/Samiho [F3H3 I2F3] | SAIR,AYP |
| All jobs (fallback) | Victoria Road: Camp 1 | 101030110 | 57-63 | 25 | solo | - | Camp 1: Skeleton Soldier [U H3] | RBISH |
| Warrior (Fighter/Page/Spearman) | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - |  [H3] | SIRE,CAT,HERO,PAL |
| Warrior (Fighter/Page/Spearman) | El Nath: Forest of Dead Trees IV | 211041400 | 57-57 | 17 | solo | - |  [U S1H3] | CAT |
| Warrior (Fighter/Page/Spearman) | Mu Lung: Practice Field : Beginner | 250020000 | 50-50 | 12 | solo | - | filler | SIRE |
| Mage F/P | El Nath: Watch Out for Icy Path I | 211010000 | 50-55 | 26 | solo | - | Jr. Yeti I2F3 [I2F3] | AYU-M |
| Mage F/P | El Nath: Icy Cold Field | 211050000 | 55-58 | 27 | solo | - | Icy Cold Field: White Fang I2F3 [I2F3] | AYU-M |
| Mage F/P | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - |  [H3] | AYU-M |
| Mage F/P | Korean Folk Town: Fox Ridge | 222010300 | 56-56 | 22 | solo | - | Fox Ridge: Samiho F3 [F3H3] | AYU-M |
| Mage F/P | Zipangu: Castle Corridor | 800040202 | 50-50 | 21 | solo | - | Castle Corridor: Chunin L3F3 [L3F3] | RUPD |
| Mage I/L | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - |  [H3] | RIL |
| Mage I/L | Orbis: Garden of Red II | 200010111 | 53-53 | 29 | solo | - | Garden of Red II: Cellion F2I3 [F2I3] | AYU-M |
| Mage I/L | Dungeon: Drake's Meal Table | 105090300 | 2-62 | 35 | solo | - | Drake's Meal Table: Red Drake F2I3 [F2I3] | AYU-M |
| Cleric/Priest/Bishop | El Nath: Forest of Dead Trees I | 211041100 | 57-57 | 27 | solo | - | Coolie Zombie: undead consensus [U S1H3] | RBISH,RCLER,AYU-M |
| Cleric/Priest/Bishop | El Nath: Forest of Dead Trees II | 211041200 | 57-57 | 20 | solo | - |  [U S1H3] | - |
| Cleric/Priest/Bishop | El Nath: Forest of Dead Trees III | 211041300 | 57-57 | 19 | solo | - |  [U S1H3] | - |
| Cleric/Priest/Bishop | El Nath: Forest of Dead Trees IV | 211041400 | 57-57 | 17 | solo | - |  [U S1H3] | - |
| Cleric/Priest/Bishop | Victoria Road: Camp 1 | 101030110 | 57-63 | 25 | solo | - | Camp 1: Skeleton Soldier undead [U H3] | RBISH,AYU-M |
| Cleric/Priest/Bishop | Singapore: Ghost ship 1 | 541010000 | 58-58 | 26 | solo | - | Pac Pinky undead [U H3] | RCLER |
| Cleric/Priest/Bishop | Singapore: Ghost Ship 5 | 541010040 | 60-60 | 29 | solo | - |  [U F3] | RCLER |
| Archer (Hunter/Crossbowman) | El Nath: Forest of Dead Trees IV | 211041400 | 57-57 | 17 | solo | - | 'by far the best' 51-57 [U S1H3] | RSNIP,RBM,WB |
| Archer (Hunter/Crossbowman) | Orbis: Cloud Park VI | 200080000 | 30-52 | 35 | solo | - |  [I2F2L2H2] | AYU-B |
| Archer (Hunter/Crossbowman) | El Nath: Watch Out for Icy Path I | 211010000 | 50-55 | 26 | solo | - |  [I2F3] | AYU-B |
| Archer (Hunter/Crossbowman) | Zipangu: Showa Street 1 | 801010000 | 43-47 | 37 | solo | - | Showa Street 1 [S3F3] | AYU-B |
| Archer (Hunter/Crossbowman) | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - | once Power Knock-Back is maxed [H3] | RBM,RSNIP |
| Thief (Assassin/Bandit) | El Nath: Forest of Dead Trees IV | 211041400 | 57-57 | 17 | solo | - |  [U S1H3] | SHAD,NL,BAND |
| Thief (Assassin/Bandit) | Mu Lung: Practice Field : Beginner | 250020000 | 50-50 | 12 | solo | - |  | NL |
| Thief (Assassin/Bandit) | Singapore: Ghost Ship 5 | 541010040 | 60-60 | 29 | solo | - |  [U F3] | - |
| Pirate Brawler | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - |  [H3] | AYP |
| Pirate Brawler | Alcadno Research Institute: Lab - Area C-2 | 261020400 | 54-58 | 28 | solo | - |  | AYP |
| Pirate Brawler | El Nath: Forest of Dead Trees III | 211041300 | 57-57 | 19 | solo | - |  [U S1H3] | AYP |
| Pirate Gunslinger | Alcadno Research Institute: Lab - Area C-2 | 261020400 | 54-58 | 28 | solo | - |  | AYP |
| Pirate Gunslinger | Zipangu: Vanished Village | 800020120 | 56-60 | 24 | solo | - |  [F3H3 I2F3] | SAIR,AYP |
| Pirate Gunslinger | El Nath: Watch Out for Icy Path I | 211010000 | 50-55 | 26 | solo | - |  [I2F3] | AYP |
| Aran | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - |  [H3] | AYA |
| Aran | Alcadno Research Institute: Lab - Area C-2 | 261020400 | 54-58 | 28 | solo | - |  | AYA |
| Aran | Dungeon: Sleepy Dungeon V | 105040305 | 55-58 | 19 | solo | - | Sleepy Dungeon V [H3] | AYA |
| Aran | El Nath: Forest of Dead Trees I | 211041100 | 57-57 | 27 | solo | - |  [U S1H3] | AYA |

### Levels 60-70: Ghost Ship 2 / MP3, Ludi clocktower, drakes; OPQ/LMPQ/PPQ

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Singapore: Ghost Ship 2 | 541010010 | 63-63 | 29 | solo | - | Ghost Ship 2 [SG]: Slimy, the 60s consensus [U H3L3] | SIRE,CAT,HERO,COMM,SHAD |
| All jobs (fallback) | Singapore: Mysterious Path 3 | 541000300 | 60-63 | 20 | solo | - | Mysterious Path 3 [SG] [U F3 H3L3] | CAT,PAL,BAND |
| All jobs (fallback) | Singapore: Ghost Ship 6 | 541010050 | 68-68 | 23 | solo | - | Ghost Ship 6 [SG]: Mr. Anchor [U] | SAIR |
| All jobs (fallback) | Haunted House: Library | 682000800 | 20-70 | 68 | solo | - | Haunted House Library: Voodoo + Jesters [U S1H3] | NL,BAND |
| All jobs (fallback) | Ludibrium: Forgotten Path of Time<1> | 220070000 | 63-67 | 109 | solo | - | Forgotten Path of Time<1>: Soul Teddy [U L2I1H3 L2I2H3] | RBISH |
| All jobs (fallback) | Ludibrium: Warped Path of Time<1> | 220060000 | 61-66 | 89 | solo | - | Warped Path of Time<1>: Buffy/Lazy Buffy | - |
| All jobs (fallback) | Dungeon: Drake Area | 105090310 | 40-64 | 43 | solo | - | Drake Area: Ice Drake [I2F3] | - |
| All jobs (fallback) | Dungeon: Cold Cradle | 105090311 | 64-68 | 42 | solo | - |  [I2F3] | - |
| All jobs (fallback) | El Nath: Sharp Cliff II | 211040400 | 56-68 | 29 | solo | - | Sharp Cliff II [H3] | - |
| All jobs (fallback) | El Nath: Ice Valley I | 211040100 | 50-65 | 19 | solo | - | Ice Valley I: Yeti [I2F3] | - |
| All jobs (fallback) | Herb Town: Red-Nose Pirate Den 1 | 251010401 | 64-70 | 21 | solo | - | Red-Nose Pirate Den 1 | - |
| All jobs (fallback) | Alcadno Research Institute: Lab - Area C-3 | 261020500 | 58-75 | 31 | solo | - | Alcadno C-3: Neo Huroid [U] | AYA |
| All jobs (fallback) | Zipangu: Parking Lot | 801040001 | 64-72 | 22 | solo/party | - | Showa Parking Lot [F3H3I3 S3F3] | AYU-M,AYU-B |
| All jobs (fallback) | Zipangu: Castle Corridor | 800040203 | 50-60 | 26 | solo | - | Castle Corridor: Chunin/Kunoichi [F3H3 L3F3] | - |
| Warrior (Fighter/Page/Spearman) | Singapore: Ghost Ship 2 | 541010010 | 63-63 | 29 | solo | - | Linear map, circle-mob [U H3L3] | SIRE,CAT,HERO |
| Warrior (Fighter/Page/Spearman) | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - |  [H3] | SIRE |
| Warrior (Fighter/Page/Spearman) | Singapore: Mysterious Path 3 | 541000300 | 60-63 | 20 | solo | - |  [U F3 H3L3] | CAT,PAL |
| Warrior (Fighter/Page/Spearman) | Haunted House: Sophilia's Bedroom | 682000501 | 60-60 | 9 | solo | - | Sophilia's Bedroom [U] | CAT |
| Mage F/P | El Nath: Ice Valley I | 211040100 | 50-65 | 19 | solo | - | Yeti I2F3 [I2F3] | AYU-M |
| Mage F/P | Dungeon: Cold Cradle | 105090311 | 64-68 | 42 | solo | - | Cold Cradle: Ice Drake I2F3 [I2F3] | - |
| Mage F/P | Singapore: Ghost Ship 5 | 541010040 | 60-60 | 29 | solo | - | Selkie Jr. F3 [U F3] | AYU-M,RUPD |
| Mage F/P | Zipangu: Parking Lot | 801040001 | 64-72 | 22 | party | - | party Explosion spot [F3H3I3 S3F3] | AYU-M |
| Mage F/P | Ludibrium: Forgotten Path of Time<1> | 220070000 | 63-67 | 109 | solo | - | Poison Mist on Soul Teddies [U L2I1H3 L2I2H3] | RUPD |
| Mage I/L | Singapore: Ghost Ship 2 | 541010010 | 63-63 | 29 | solo | - | Slimy L3: lightning-weak, heavy spawn [U H3L3] | RIL |
| Mage I/L | Singapore: Mysterious Path 3 | 541000300 | 60-63 | 20 | solo | - |  [U F3 H3L3] | RLG |
| Mage I/L | Dungeon: Drake's Meal Table | 105090300 | 2-62 | 35 | solo | - | Red Drake F2I3 [F2I3] | AYU-M |
| Cleric/Priest/Bishop | Singapore: Ghost Ship 2 | 541010010 | 63-63 | 29 | solo | - | Ghost Ship all undead [U H3L3] | RCLER,RBISH |
| Cleric/Priest/Bishop | Singapore: Ghost Ship 6 | 541010050 | 68-68 | 23 | solo | - |  [U] | RCLER |
| Cleric/Priest/Bishop | Singapore: Mysterious Path 3 | 541000300 | 60-63 | 20 | solo | - |  [U F3 H3L3] | RBISH,RCLER |
| Cleric/Priest/Bishop | Ludibrium: Forgotten Path of Time<1> | 220070000 | 63-67 | 109 | solo | - | Soul Teddy undead [U L2I1H3 L2I2H3] | RBISH |
| Cleric/Priest/Bishop | Victoria Road: Camp 3 | 101030112 | 57-63 | 20 | solo | - | Camp 3: Officer Skeleton [U H3] | RBISH |
| Cleric/Priest/Bishop | Haunted House: Library | 682000800 | 20-70 | 68 | solo | - | Voodoo undead [U S1H3] | RBISH |
| Archer (Hunter/Crossbowman) | Singapore: Ghost Ship 2 | 541010010 | 63-63 | 29 | solo | - |  [U H3L3] | RSNIP,RBM |
| Archer (Hunter/Crossbowman) | Singapore: Ghost Ship 5 | 541010040 | 60-60 | 29 | solo | - |  [U F3] | RSNIP |
| Archer (Hunter/Crossbowman) | Crimsonwood Mountain: Lower Ascent | 610020002 | 70-80 | 19 | solo | - | Lower Ascent: Windraider [F3 L1 S1] | RBM,RLG |
| Archer (Hunter/Crossbowman) | Haunted House: Library | 682000800 | 20-70 | 68 | solo | - |  [U S1H3] | RSNIP,RBM |
| Archer (Hunter/Crossbowman) | Alcadno Research Institute: Lab - Area C-2 | 261020400 | 54-58 | 28 | solo | - | flat, arrow bomb | DMS-ALL |
| Thief (Assassin/Bandit) | Singapore: Ghost Ship 2 | 541010010 | 63-63 | 29 | solo | - |  [U H3L3] | SHAD,NL,CAT |
| Thief (Assassin/Bandit) | Singapore: Mysterious Path 3 | 541000300 | 60-63 | 20 | solo | - |  [U F3 H3L3] | BAND |
| Thief (Assassin/Bandit) | Haunted House: Library | 682000800 | 20-70 | 68 | solo | - |  [U S1H3] | NL,BAND |
| Thief (Assassin/Bandit) | Crimsonwood Mountain: Lower Ascent | 610020002 | 70-80 | 19 | solo | - |  [F3 L1 S1] | NL,BAND |
| Pirate Brawler | Singapore: Ghost Ship 2 | 541010010 | 63-63 | 29 | solo | - |  [U H3L3] | SAIR,CAT |
| Pirate Brawler | Dungeon: The forest of Golem | 105040306 | 58-59 | 30 | solo | - |  [H3] | AYP |
| Pirate Brawler | Singapore: Mysterious Path 3 | 541000300 | 60-63 | 20 | solo | - |  [U F3 H3L3] | SAIR |
| Pirate Gunslinger | Singapore: Ghost Ship 2 | 541010010 | 63-63 | 29 | solo | - |  [U H3L3] | SAIR |
| Pirate Gunslinger | Singapore: Ghost Ship 6 | 541010050 | 68-68 | 23 | solo | - | Mr. Anchor at 63+ [U] | SAIR |
| Pirate Gunslinger | Crimsonwood Mountain: Lower Ascent | 610020002 | 70-80 | 19 | solo | - |  [F3 L1 S1] | SAIR |
| Aran | Singapore: Mysterious Path 3 | 541000300 | 60-63 | 20 | solo | - |  [U F3 H3L3] | AYA,DMS |
| Aran | Crimsonwood Mountain: Lower Ascent | 610020002 | 70-80 | 19 | solo | - |  [F3 L1 S1] | AYA |
| Aran | Malaysia: Fantasy Theme Park 1 | 551010000 | 68-72 | 29 | solo | - | Fantasy Theme Park 1 [MY] [S2] | AYA |
| Aran | Alcadno Research Institute: Lab - Area C-3 | 261020500 | 58-75 | 31 | solo | - |  [U] | AYA |

### Levels 70-85: Wolf Spiders, Lower Ascent, Leafre forests; PPQ/MPQ

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | MesoGears: Wolf Spider Cavern | 600020300 | 80-80 | 64 | solo | - | Wolf Spider Cavern: 64 spawns | SIRE,HERO,CAT,NL,RIL |
| All jobs (fallback) | Crimsonwood Mountain: Lower Ascent | 610020002 | 70-80 | 19 | solo | - | Lower Ascent [F3 L1 S1] | NL,AYA,SAIR |
| All jobs (fallback) | Ludibrium: Warped Path of Time<3> | 220060200 | 74-83 | 66 | solo | - | Warped Path<3>: Ghost Pirate [I2F3 I3F2] | AYP |
| All jobs (fallback) | Ludibrium: Warped Path of Time<2> | 220060100 | 74-77 | 76 | solo | - | Warped Path<2>: Buffoon [I3F2] | - |
| All jobs (fallback) | Ludibrium: Forgotten Path of Time<2> | 220070100 | 70-76 | 109 | solo | - | Forgotten Path<2>: Klock [U H3 H3F3] | AYT |
| All jobs (fallback) | El Nath: Wolf Territory I | 211040500 | 55-75 | 29 | solo | - | Wolf Territory I: Werewolf [I2F3] | - |
| All jobs (fallback) | El Nath: Wolf Territory IV | 211040900 | 58-80 | 24 | solo | - | Wolf Territory IV: Lycanthrope [I2F3] | - |
| All jobs (fallback) | Leafre: Peach Monkey Forest | 240010101 | 70-74 | 36 | solo | - | Peach Monkey Forest: Dark Rash [S2 S3] | - |
| All jobs (fallback) | Leafre: Minar Forest : East Border | 240010900 | 76-76 | 18 | solo | - | Minar East Border: Dual Beetle [L2] | - |
| All jobs (fallback) | Malaysia: Fantasy Theme Park 2 | 551020000 | 75-82 | 17 | solo | - | Fantasy Theme Park 2 [MY] [F3S3] | AYA |
| All jobs (fallback) | Haunted House: Chimney Possessed by the Clown | 682010203 | 70-70 | 31 | solo | - | Chimney: Twisted Jester [U] | DMS-TB1,DMS-ALL |
| All jobs (fallback) | El Nath: Forest of Dead Trees IV | 211041400 | 57-57 | 17 | solo | - | Coolie Zombie to 80 [U S1H3] | AYW |
| Warrior (Fighter/Page/Spearman) | MesoGears: Wolf Spider Cavern | 600020300 | 80-80 | 64 | solo | - | sides then middle | SIRE,HERO,CAT |
| Warrior (Fighter/Page/Spearman) | El Nath: Forest of Dead Trees IV | 211041400 | 57-57 | 17 | solo | - |  [U S1H3] | AYW |
| Warrior (Fighter/Page/Spearman) | Leafre: The Area of Blue Kentaurus | 240020400 | 88-88 | 22 | solo | - | Blue Kentaurus (fire charge) [I2F3] | PAL |
| Mage F/P | El Nath: Wolf Territory IV | 211040900 | 58-80 | 24 | solo | - | Lycanthrope I2F3 [I2F3] | - |
| Mage F/P | Ludibrium: Warped Path of Time<3> | 220060200 | 74-83 | 66 | solo | - | Ghost Pirate I2F3 [I2F3 I3F2] | AYU-M |
| Mage F/P | Ludibrium: Forgotten Path of Time<3> | 220070200 | 85-85 | 74 | solo | - | Death Teddy: Mist [U H3F3] | RUPD |
| Mage F/P | Zipangu: Vanished Village | 800020120 | 56-60 | 24 | solo | - | Water Goblin I2F3 [F3H3 I2F3] | AYU-M |
| Mage I/L | MesoGears: Wolf Spider Cavern | 600020300 | 80-80 | 64 | solo | - |  | RIL |
| Mage I/L | Zipangu: WanWan Spa of Hell | 800020400 | 90-90 | 21 | solo | - | WanWan Spa: Bain F2I3 [F2I3] | AYU-M |
| Mage I/L | Leafre: The Area of Red Kentaurus | 240020000 | 88-88 | 11 | solo | - | Red Kentaurus F2I3 [F2I3] | - |
| Cleric/Priest/Bishop | Ludibrium: Forgotten Path of Time<3> | 220070200 | 85-85 | 74 | solo | - | Death Teddy undead [U H3F3] | RBISH |
| Cleric/Priest/Bishop | Haunted House: Chimney Possessed by the Clown | 682010203 | 70-70 | 31 | solo | - | Twisted Jester undead [U] | DMS-ALL |
| Cleric/Priest/Bishop | Leafre: The Area of Black Kentaurus | 240020200 | 88-88 | 14 | solo | - | Black Kentaurus undead [U H3] | RBISH |
| Cleric/Priest/Bishop | MesoGears: Wolf Spider Cavern | 600020300 | 80-80 | 64 | party | - | Holy Symbol leech 75+ | RBISH |
| Archer (Hunter/Crossbowman) | MesoGears: Wolf Spider Cavern | 600020300 | 80-80 | 64 | solo | - |  | RSNIP,RBM |
| Archer (Hunter/Crossbowman) | El Nath: Forest of Dead Trees IV | 211041400 | 57-57 | 17 | solo | - |  [U S1H3] | AYU-B |
| Archer (Hunter/Crossbowman) | Crimsonwood Mountain: The Path of Peril | 610020004 | 40-100 | 15 | solo | - | Typhons [I1S1] | RSNIP |
| Thief (Assassin/Bandit) | MesoGears: Wolf Spider Cavern | 600020300 | 80-80 | 64 | solo | - |  | NL |
| Thief (Assassin/Bandit) | Crimsonwood Mountain: Cavern of Pain | 610020011 | 70-80 | 10 | solo | - | Cavern of Pain [L1 S1] | NL |
| Thief (Assassin/Bandit) | Aqua Road: Dangerous Sea Gorge I | 230040200 | 90-97 | 29 | solo | - | Squids 85+ [F2I2L2S2 F2L2 S2F2] | NL |
| Pirate Brawler | MesoGears: Wolf Spider Cavern | 600020300 | 80-80 | 64 | solo | - |  | SAIR |
| Pirate Brawler | Ludibrium: Warped Path of Time<3> | 220060200 | 74-83 | 66 | solo | - |  [I2F3 I3F2] | AYP |
| Pirate Brawler | Leafre: Forest : Crossroad | 240010400 | 85-88 | 16 | solo | - | Birk [H2I3 H2S2] | AYP |
| Pirate Gunslinger | MesoGears: Wolf Spider Cavern | 600020300 | 80-80 | 64 | solo | - |  | SAIR |
| Pirate Gunslinger | Leafre: The Area of Black Kentaurus | 240020200 | 88-88 | 14 | solo | - | snipe Kentaurus [U H3] | AYP |
| Pirate Gunslinger | Leafre: The Area of Red Kentaurus | 240020000 | 88-88 | 11 | solo | - |  [F2I3] | AYP |
| Aran | Crimsonwood Mountain: Lower Ascent | 610020002 | 70-80 | 19 | solo | - |  [F3 L1 S1] | AYA |
| Aran | Malaysia: Fantasy Theme Park 3 | 551030000 | 87-87 | 26 | solo | - | Fantasy Theme Park 3 [MY] [F3L3] | AYA |
| Aran | Leafre: Battlefield of Fire and Darkness | 240020100 | 88-88 | 24 | solo | - |  [U F2I3 H3] | AYA |

### Levels 85-100: Ludi clocktower, Aqua Road gorges, Kentaurus, Gallopera, Memory Lane

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Ludibrium: Forgotten Path of Time<3> | 220070200 | 85-85 | 74 | solo | - | Death Teddy [U H3F3] | AYW,RBISH |
| All jobs (fallback) | Hidden Street: Lost Time | 220070201 | 89-89 | 52 | solo | - | Lost Time: Master Death Teddy [U H3I2L2] | RBISH |
| All jobs (fallback) | Ludibrium: Warped Path of Time<4> | 220060300 | 93-93 | 39 | solo | - | Spirit Viking [F2L3] | AYP,PAL |
| All jobs (fallback) | Hidden Street: Unbalanced Time | 220060201 | 87-87 | 21 | solo/party | - | Unbalanced Time: Dual Ghost Pirate [I2F3] | SIRE,AYU-B |
| All jobs (fallback) | Ludibrium: Forgotten Path of Time<4> | 220070300 | 95-95 | 61 | solo | - | Phantom Watch [U H3I2F3] | AYU-M |
| All jobs (fallback) | Leafre: The Area of Blue Kentaurus | 240020400 | 88-88 | 22 | solo | - |  [I2F3] | PAL |
| All jobs (fallback) | Leafre: Battlefield of Fire and Darkness | 240020100 | 88-88 | 24 | solo | - |  [U F2I3 H3] | - |
| All jobs (fallback) | Leafre: Valley of the Antelope | 240010500 | 85-88 | 22 | solo | - | Valley of the Antelope: Birk [H2I3 H2S2] | AYP |
| All jobs (fallback) | Aqua Road: Deep Sea Gorge II | 230040100 | 90-92 | 21 | solo | - | Deep Sea Gorge II: fish houses spawn Gobies [U F2H3 F2I2L2S2] | SIRE,HERO,AYU-B,RSNIP |
| All jobs (fallback) | Aqua Road: Deep Sea Gorge I | 230040000 | 90-92 | 20 | solo | - |  [U F2H3 F2I2L2S2] | AYU-B |
| All jobs (fallback) | Aqua Road: Dangerous Sea Gorge I | 230040200 | 90-97 | 29 | solo/party | - | Dangerous Sea Gorge I: Squid [F2I2L2S2 F2L2 S2F2] | NL,AYU-B,RBISH |
| All jobs (fallback) | Aqua Road: Dangerous Sea Gorge II | 230040300 | 94-97 | 34 | solo/party | - |  [F2L2 S2F2] | AYU-M,RBISH |
| All jobs (fallback) | Malaysia: Entrance to the Spooky World | 551030100 | 94-94 | 17 | solo | - | Entrance to the Spooky World [MY]: Gallopera [H3] | AYA,DMS,DMS-ALL |
| All jobs (fallback) | Time Lane: Memory Lane1 | 270010100 | 80-91 | 47 | solo | - | Memory Lane1 [S2L2] | HERO,NL,AYA |
| All jobs (fallback) | Time Lane: Memory Lane3 | 270010300 | 80-98 | 43 | solo | - |  [S2L2] | - |
| All jobs (fallback) | Leafre: Dragon Forest II | 240030200 | 95-100 | 16 | solo | - | Dragon Forest II: Rexton [L3S3 S2] | PAL |
| Warrior (Fighter/Page/Spearman) | Aqua Road: Deep Sea Gorge II | 230040100 | 90-92 | 21 | solo | - |  [U F2H3 F2I2L2S2] | SIRE,HERO |
| Warrior (Fighter/Page/Spearman) | Ludibrium: Warped Path of Time<4> | 220060300 | 93-93 | 39 | solo | - |  [F2L3] | PAL |
| Warrior (Fighter/Page/Spearman) | Malaysia: Entrance to the Spooky World | 551030100 | 94-94 | 17 | solo | - |  [H3] | DMS-DW |
| Mage F/P | Hidden Street: Unbalanced Time | 220060201 | 87-87 | 21 | solo | - | Dual Ghost Pirate I2F3 [I2F3] | AYU-M |
| Mage F/P | Leafre: The Area of Blue Kentaurus | 240020400 | 88-88 | 22 | solo | - | Blue Kentaurus I2F3 [I2F3] | AYU-M |
| Mage F/P | Aqua Road: Dangerous Sea Gorge II | 230040300 | 94-97 | 34 | party | - | Risell Squid: Poison Mist [F2L2 S2F2] | AYU-M |
| Mage I/L | Leafre: The Area of Red Kentaurus | 240020000 | 88-88 | 11 | solo | - | Red Kentaurus F2I3 [F2I3] | - |
| Mage I/L | Leafre: Dragon Forest III | 240030300 | 97-105 | 11 | solo | - | Brexton F2I3 [F2I3 S2] | - |
| Mage I/L | Ludibrium: Warped Path of Time<4> | 220060300 | 93-93 | 39 | solo | - | Spirit Viking L3 [F2L3] | - |
| Cleric/Priest/Bishop | Hidden Street: Lost Time | 220070201 | 89-89 | 52 | solo | - | Master Death Teddy undead [U H3I2L2] | RBISH |
| Cleric/Priest/Bishop | Ludibrium: Forgotten Path of Time<4> | 220070300 | 95-95 | 61 | solo | - | Phantom Watch undead [U H3I2F3] | - |
| Cleric/Priest/Bishop | Aqua Road: Deep Sea Gorge I | 230040000 | 90-92 | 20 | solo | - | Bone Fish undead [U F2H3 F2I2L2S2] | - |
| Cleric/Priest/Bishop | Malaysia: Entrance to the Spooky World | 551030100 | 94-94 | 17 | solo | - | Gallopera holy-weak [H3] | DMS-ALL |
| Cleric/Priest/Bishop | Aqua Road: Dangerous Sea Gorge II | 230040300 | 94-97 | 34 | party | - | leech 92+ [F2L2 S2F2] | RBISH |
| Archer (Hunter/Crossbowman) | Aqua Road: Deep Sea Gorge II | 230040100 | 90-92 | 21 | solo | - | Gobies: snipers [U F2H3 F2I2L2S2] | AYU-B,RSNIP,RBM |
| Archer (Hunter/Crossbowman) | Aqua Road: Deep Sea Gorge I | 230040000 | 90-92 | 20 | solo | - |  [U F2H3 F2I2L2S2] | AYU-B |
| Archer (Hunter/Crossbowman) | Aqua Road: Dangerous Sea Gorge I | 230040200 | 90-97 | 29 | party | - |  [F2I2L2S2 F2L2 S2F2] | RSNIP,RBM |
| Archer (Hunter/Crossbowman) | Hidden Street: Unbalanced Time | 220060201 | 87-87 | 21 | party | - |  [I2F3] | AYU-B |
| Thief (Assassin/Bandit) | Aqua Road: Dangerous Sea Gorge I | 230040200 | 90-97 | 29 | solo | - |  [F2I2L2S2 F2L2 S2F2] | NL |
| Thief (Assassin/Bandit) | Aqua Road: Deep Sea Gorge II | 230040100 | 90-92 | 21 | solo | - |  [U F2H3 F2I2L2S2] | SHAD |
| Thief (Assassin/Bandit) | Hidden Street: Unbalanced Time | 220060201 | 87-87 | 21 | solo | - |  [I2F3] | AYT |
| Pirate Brawler | Ludibrium: Warped Path of Time<4> | 220060300 | 93-93 | 39 | solo | - |  [F2L3] | AYP |
| Pirate Brawler | Aqua Road: Deep Sea Gorge II | 230040100 | 90-92 | 21 | solo | - |  [U F2H3 F2I2L2S2] | AYP,SAIR |
| Pirate Brawler | Leafre: Forest : Crossroad | 240010400 | 85-88 | 16 | solo | - |  [H2I3 H2S2] | AYP |
| Pirate Gunslinger | Aqua Road: Deep Sea Gorge II | 230040100 | 90-92 | 21 | solo | - |  [U F2H3 F2I2L2S2] | AYP |
| Pirate Gunslinger | Leafre: Destroyed Dragon Nest | 240040520 | 105-105 | 13 | solo | - | snipe from middle platform | AYP |
| Aran | Malaysia: Entrance to the Spooky World | 551030100 | 94-94 | 17 | solo | - | Gallopera 89-108 [H3] | AYA,DMS |
| Aran | Time Lane: Memory Lane1 | 270010100 | 80-91 | 47 | solo | - |  [S2L2] | AYA |
| Aran | Hidden Street: Unbalanced Time | 220060201 | 87-87 | 21 | solo | - |  [I2F3] | AYA |

### Levels 100-120: Himes, Leafre nests, Temple of Time; Ulu (post-v83 but present)

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | solo/party | - | Encounter with the Buddha: Dreamy Ghost (Himes) [U S1H3] | SIRE,HERO,DK,NL,SHAD,RIL,RBISH |
| All jobs (fallback) | Leafre: Destroyed Dragon Nest | 240040520 | 105-105 | 13 | solo | - | Destroyed Dragon Nest | HERO,CAT,NL |
| All jobs (fallback) | Leafre: Dangerous Dragon Nest | 240040521 | 105-110 | 18 | solo | - |  | HERO |
| All jobs (fallback) | Time Lane: Road of Regrets1 | 270020100 | 80-106 | 50 | solo | - | Road of Regrets1 [I2F3L3] | HERO,PAL,AYA |
| All jobs (fallback) | Time Lane: Road of Regrets3 | 270020300 | 80-113 | 49 | solo | - |  [I2F3L3] | - |
| All jobs (fallback) | Time Lane: Memory Lane5 | 270010500 | 80-101 | 52 | solo | - | Memory Lane5 [S2L2] | - |
| All jobs (fallback) | Aqua Road: The Grave of a Wrecked Ship | 230040400 | 90-102 | 38 | solo | - | Grave of a Wrecked Ship: Shark/Cold Shark [F2 F2I2 F2I2L2S2] | - |
| All jobs (fallback) | Leafre: Canyon : Crossroad | 240040100 | 97-101 | 13 | solo | - | Wyvern Canyon crossroad [F2I3 F3I2] | AYU-M |
| All jobs (fallback) | Leafre: Canyon : West Road | 240040300 | 103-103 | 16 | solo | - |  | - |
| All jobs (fallback) | Hidden Street: Forbidden Time | 220070301 | 99-99 | 48 | solo | - | Forbidden Time: Grim Phantom Watch [U H3F3I1] | RBISH,AYU-B |
| All jobs (fallback) | Hidden Street: Twisted Time | 220060301 | 98-98 | 26 | solo | - | Twisted Time: Gigantic Spirit Viking [F2L3] | - |
| All jobs (fallback) | Leafre: The Dragon Nest Left Behind | 240040511 | 110-113 | 17 | party | - | Skeles from ~108 in party [U H3] | RLG,DMS-WA |
| All jobs (fallback) | Singapore: Ulu Estate I | 541020100 | 85-90 | 52 | solo | - | Ulu Estate I (post-v83) | HERO |
| All jobs (fallback) | Singapore: Ulu City Center | 541020500 | 110-110 | 36 | solo | - | Ulu City Center: Petrifighter (post-v83) | RUPD |
| All jobs (fallback) | Leafre: The Burning Forest | 240030101 | 100-105 | 13 | solo | - | Burning Forest: Dark Cornian [S2] | - |
| All jobs (fallback) | Western Minar Forest: Western Minar Forest <1> | 321110000 | 102-102 | 18 | solo | - | Mirror World Western Minar Forest 102-104 [L2] | MR |
| All jobs (fallback) | Twilight Forest: Twilight Forest <1> | 321130000 | 106-106 | 18 | solo | - | Mirror World Twilight Forest 106-108 [F2I3 F3I2] | MR |
| All jobs (fallback) | Wyvern Gorge: Wyvern Gorge <6> | 321145000 | 108-108 | 17 | solo | - | Mirror World Wyvern Gorge <6>: Skeles at 108 [U H3D2] | MR |
| All jobs (fallback) | Refuge Outskirts: Refuge South 1 | 402000610 | 110-110 | 17 | solo | rebirth 3 | Refuge South 1 (Grandis, rescaled to 110) | - |
| All jobs (fallback) | Sanctuary: Eastern Crystal Forest 2 | 402000518 | 117-117 | 62 | solo | rebirth 3 | Eastern Crystal Forest 2: 62 spawns | - |
| All jobs (fallback) | Ristonia: Art Street 1 | 410000321 | 113-113 | 68 | solo | rebirth 3 | Ristonia Art Street 1: 68 spawns | - |
| All jobs (fallback) | Cheong-woon Valley: Chaotic Crock Territory 1 | 410000210 | 112-112 | 20 | solo | rebirth 3 | Cheong-woon Valley 112 | - |
| Warrior (Fighter/Page/Spearman) | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | solo/party | - | flat, holy-weak; Paladin holy charge [U S1H3] | SIRE,HERO,DK,PAL |
| Warrior (Fighter/Page/Spearman) | Leafre: Destroyed Dragon Nest | 240040520 | 105-105 | 13 | solo | - |  | HERO |
| Warrior (Fighter/Page/Spearman) | Time Lane: Road of Regrets1 | 270020100 | 80-106 | 50 | solo | - |  [I2F3L3] | PAL |
| Mage F/P | Hidden Street: Blue Wyvern's Nest | 240040210 | 101-101 | 12 | solo | - | Blue Wyvern's Nest F3I2 [F3I2] | - |
| Mage F/P | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | party | - |  [U S1H3] | RLG |
| Mage F/P | Time Lane: Road of Regrets1 | 270020100 | 80-106 | 50 | solo | - | Qualm Monk I2F3L3 [I2F3L3] | - |
| Mage I/L | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | party | - |  [U S1H3] | RIL |
| Mage I/L | Hidden Street: Red Wyvern's Nest | 240040310 | 97-97 | 9 | solo | - | Red Wyvern's Nest F2I3 [F2I3] | - |
| Mage I/L | Time Lane: Road of Regrets3 | 270020300 | 80-113 | 49 | solo | - | Qualm Guardian L3 [I2F3L3] | - |
| Cleric/Priest/Bishop | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | solo/party | - | Himes: consensus, leech 95+ [U S1H3] | RBISH,RCLER,AYU-M |
| Cleric/Priest/Bishop | Hidden Street: Forbidden Time | 220070301 | 99-99 | 48 | solo | - |  [U H3F3I1] | RBISH |
| Cleric/Priest/Bishop | Leafre: The Dragon Nest Left Behind | 240040511 | 110-113 | 17 | party | - | Skeles from 108 [U H3] | RBISH |
| Cleric/Priest/Bishop | Phantom Forest: The Evil Dead | 610010013 | 50-95 | 8 | solo | - | The Evil Dead: Elderwraith [U F3 H3I1] | RBISH |
| Archer (Hunter/Crossbowman) | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | party | - | priest lures [U S1H3] | RBM,RSNIP |
| Archer (Hunter/Crossbowman) | Leafre: Destroyed Dragon Nest | 240040520 | 105-105 | 13 | solo | - |  | RBM |
| Archer (Hunter/Crossbowman) | Aqua Road: Deep Sea Gorge II | 230040100 | 90-92 | 21 | solo | - |  [U F2H3 F2I2L2S2] | RSNIP |
| Thief (Assassin/Bandit) | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | solo/party | - | bandit bombs with a priest [U S1H3] | NL,SHAD |
| Thief (Assassin/Bandit) | Leafre: Destroyed Dragon Nest | 240040520 | 105-105 | 13 | solo | - | snipe Jr. Newties | NL |
| Thief (Assassin/Bandit) | Time Lane: Memory Lane1 | 270010100 | 80-91 | 47 | solo | - |  [S2L2] | NL |
| Pirate Brawler | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | party | - |  [U S1H3] | SAIR |
| Pirate Brawler | Leafre: Destroyed Dragon Nest | 240040520 | 105-105 | 13 | solo | - |  | AYP,SAIR |
| Pirate Brawler | Hidden Street: Twisted Time | 220060301 | 98-98 | 26 | solo | - |  [F2L3] | AYP |
| Pirate Gunslinger | Leafre: Destroyed Dragon Nest | 240040520 | 105-105 | 13 | solo | - |  | AYP |
| Pirate Gunslinger | Leafre: Forest : Crossroad | 240010400 | 85-88 | 16 | solo | - |  [H2I3 H2S2] | AYP |
| Aran | Zipangu: Encounter with the Buddha | 800020130 | 100-100 | 17 | solo | - | faster than Gallopera, less money [U S1H3] | DMS |
| Aran | Time Lane: Road of Regrets1 | 270020100 | 80-106 | 50 | solo | - |  [I2F3L3] | AYA |
| Aran | Leafre: The Dragon Nest Left Behind | 240040511 | 110-113 | 17 | solo | - | Skeles 108-120 [U H3] | DMS |

### Levels 120-140: Skeles, Road to Oblivion; first MapleRoot zones

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Leafre: The Dragon Nest Left Behind | 240040511 | 110-113 | 17 | solo | - | Dragon Nest Left Behind: Skelegon/Skelosaurus [U H3] | SIRE,CAT,NL,AYA |
| All jobs (fallback) | Leafre: Nest of a Dead Dragon | 240040510 | 110-113 | 17 | solo | - |  [U H3] | - |
| All jobs (fallback) | Time Lane: Road to Oblivion2 | 270030200 | 80-124 | 42 | solo | - | Road to Oblivion2 [F2S3] | HERO,NL |
| All jobs (fallback) | Time Lane: Road to Oblivion1 | 270030100 | 80-121 | 49 | solo | - |  [F2S3] | - |
| All jobs (fallback) | Time Lane: Road to Oblivion4 | 270030400 | 80-131 | 48 | solo | - | Road to Oblivion4 [F2S3] | HERO |
| All jobs (fallback) | Singapore: Destroyed Park I | 541020600 | 95-115 | 29 | solo | - | Destroyed Park I: Duku (post-v83) | AYU-B |
| All jobs (fallback) | Singapore: Long Kiss Duku Good Night | 541020620 | 115-115 | 29 | solo | - |  | - |
| All jobs (fallback) | Crimsonwood Mountain: Crimsonwood Keep | 610020006 | 120-120 | 8 | solo | - | Crimsonwood Keep: Crimson Guardian [F2I2L2S2H1] | AYA,SHAD |
| All jobs (fallback) | Ariant: Pyramid <1> | 322040000 | 118-118 | 18 | solo | - | Mirror World Pyramid <1> 118, undead [U F3I3] | MR |
| All jobs (fallback) | Henesys: Golden Coast <1> | 323030000 | 126-126 | 18 | solo | - | Mirror World Golden Coast <1> 122-128 | MR |
| All jobs (fallback) | Henesys: Deep within Sleeping Forest <1> | 323040000 | 128-128 | 18 | solo | - | Deep within Sleeping Forest 122-128 | MR |
| All jobs (fallback) | Mu Lung: Sky Forest <1> | 324020000 | 133-133 | 18 | solo | - | Mirror World Sky Forest 131-139 | MR |
| All jobs (fallback) | Lion King's Castle: Under the Castle Walls 1 | 211060100 | 117-117 | 13 | solo | rebirth 1 | Lion King's Castle Under the Castle Walls 1 (rebirth 1) | GL,AYU9 |
| All jobs (fallback) | Lion King's Castle: Under the Castle Walls 2 | 211060300 | 119-119 | 13 | solo | rebirth 1 | LKC Walls 2 | - |
| All jobs (fallback) | Lion King's Castle: Under the Castle Walls 3 | 211060500 | 120-120 | 14 | solo | rebirth 1 | LKC Walls 3 | - |
| All jobs (fallback) | Lion King's Castle: Under the Castle Walls 4 | 211060700 | 122-122 | 15 | solo | rebirth 1 | LKC Walls 4: Bearwolf 15 spawns | - |
| All jobs (fallback) | Lion King's Castle: Under the Castle Walls 5 | 211060900 | 122-122 | 13 | solo | rebirth 1 | LKC Walls 5 | - |
| All jobs (fallback) | East Pantheon: East Pantheon Forest | 400010300 | 124-124 | 35 | solo | rebirth 3 | East Pantheon Forest 124 | - |
| All jobs (fallback) | South Pantheon: South Pantheon Plains | 400030100 | 120-120 | 46 | solo | rebirth 3 | South Pantheon Plains 46 spawns | - |
| All jobs (fallback) | Fox Ridge: Tear Rock Hill 3 | 410000022 | 118-118 | 35 | solo | rebirth 3 | Fox Ridge Tear Rock Hill 3 | - |
| All jobs (fallback) | Desert Cavern: Desert Cavern 3 | 402000633 | 128-128 | 24 | solo | rebirth 3 | Desert Cavern 3 | - |
| All jobs (fallback) | Handsome Monk's Temple: Inside the Temple 3 | 410000242 | 130-130 | 24 | solo | rebirth 3 | Handsome Monk's Temple 130 | - |
| Cleric/Priest/Bishop | Leafre: The Dragon Nest Left Behind | 240040511 | 110-113 | 17 | party | - | Skeles undead; Bishop preferred [U H3] | RBISH,RCLER |
| Cleric/Priest/Bishop | Leafre: Nest of a Dead Dragon | 240040510 | 110-113 | 17 | party | - |  [U H3] | - |
| Cleric/Priest/Bishop | Ariant: Pyramid <1> | 322040000 | 118-118 | 18 | solo | - | Pyramid undead [U F3I3] | MR |
| Mage I/L | Leafre: Destroyed Dragon Nest | 240040520 | 105-105 | 13 | solo | - | Blizzard from a platform | RIL |

### Levels 140-160: MapleRoot zones: Mirror World, Kritias, LKC walls

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Time Lane: Road to Oblivion5 | 270030500 | 131-131 | 42 | solo | - | Road to Oblivion5: Chief Oblivion Guardian 131 [F2S3] | - |
| All jobs (fallback) | Edelstein: Streetlight Street <1> | 325010000 | 142-142 | 18 | solo | - | Mirror World Streetlight Street 142-148 [F2 H2D3] | MR |
| All jobs (fallback) | Edelstein: Perilous Rocky Mountain <1> | 325020000 | 144-144 | 18 | solo | - | Perilous Rocky Mountain | MR |
| All jobs (fallback) | Magatia: Zenumist's Laboratory A <1> | 326010000 | 152-152 | 18 | solo | - | Zenumist Lab A 152-158 | MR |
| All jobs (fallback) | Magatia: Alcadno's Laboratory A <1> | 326020000 | 154-154 | 18 | solo | - | Alcadno Lab A | MR |
| All jobs (fallback) | Kritias: Outside Kritias <1> | 329020000 | 140-140 | 18 | solo | - | Outside Kritias (140 set): all elements strong [P2H2F2I2S2L2D2] | MR |
| All jobs (fallback) | Lion King's Castle: Tall Castle Walls 1 | 211060620 | 127-127 | 9 | solo | rebirth 1 | LKC Tall Castle Walls: Castle Golem 127 | - |
| All jobs (fallback) | Lion King's Castle: Very Tall Castle Walls | 211060830 | 127-127 | 10 | solo | rebirth 1 | LKC Very Tall Castle Walls | - |
| All jobs (fallback) | Abandoned Offices: Mr. Hazard's Lair 2 | 402000321 | 140-142 | 25 | solo | rebirth 3 | Mr. Hazard's Lair 2: 140-142 | - |
| All jobs (fallback) | East Pantheon: Enchanted Forest | 400010400 | 141-141 | 23 | solo | rebirth 3 | Enchanted Forest 141 | - |

### Levels 160-180: MapleRoot zones: Mirror World Ludi/ToT, Gate to the Future (rebirth 2)

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Ludibrium: Toy Factory <1> | 327010000 | 162-162 | 18 | solo | - | Mirror World Toy Factory 162-168 | MR |
| All jobs (fallback) | Ludibrium: Inside Clocktower <1> | 327020000 | 164-164 | 18 | solo | - | Inside Clocktower | MR |
| All jobs (fallback) | Temple of Time: Road to Oblivion <1> | 328030000 | 179-179 | 18 | solo | - | Mirror World Road to Oblivion 173-179 [S3I3L3] | MR |
| All jobs (fallback) | Temple of Time: Road of Regrets <1> | 328020000 | 176-176 | 18 | solo | - |  [F3L3] | MR |
| All jobs (fallback) | Kritias: Outside Kritias <1> | 329010000 | 180-180 | 18 | solo | - | Outside Kritias (180 set) [P2H2F2I2S2L2D2] | MR |
| All jobs (fallback) | Dark Ereve: Mutant Tino Forest | 271000100 | 163-163 | 21 | solo | rebirth 2 | Dark Ereve: Mutant Tino Forest 163 | AYU3 |
| All jobs (fallback) | Ravaged Forest: Hill North of Henesys Ruins | 271010300 | 166-166 | 25 | solo | rebirth 2 | Ravaged Forest: Hill North of Henesys Ruins | - |
| All jobs (fallback) | Ravaged Forest: Dreamy Forest Trail | 271010400 | 168-168 | 14 | solo | rebirth 2 | Dreamy Forest Trail: Mutant Ribbon Pig 168 | - |
| All jobs (fallback) | Knight Stronghold: Second Drill Hall | 271030102 | 168-170 | 32 | solo | rebirth 2 | Second Drill Hall: Official Knight A/B (HyperTP-blocked) | GE,AYU3 |
| All jobs (fallback) | Knight Stronghold: Armory 2 | 271030320 | 174-176 | 24 | solo | rebirth 2 | Armory 2 (HyperTP-blocked) | - |
| All jobs (fallback) | Scrapyard: Seedy Scrapyard 2 | 402000125 | 175-176 | 21 | solo | rebirth 3 | Seedy Scrapyard 2: 175-176 | - |
| All jobs (fallback) | Brawl 'n' Bash Club: Dingy Brawl 'n' Bash Club 1 | 402000312 | 181-181 | 33 | solo | rebirth 3 | Dingy Brawl 'n' Bash Club 181 | - |

### Levels 180-200: Twilight Perion (rebirth 2), Kritias, Umbra, Scrapyard

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Kritias: Outside Kritias <1> | 329010000 | 180-180 | 18 | solo | - | Outside Kritias 180 [P2H2F2I2S2L2D2] | MR |
| All jobs (fallback) | Umbra Temple: Dusty Corridor 1 | 307000310 | 190-190 | 8 | solo | - | Umbra Temple: 8 spawns, 39M HP [S2L2] | MR |
| All jobs (fallback) | Scrapyard: Scrapyard Entrance | 310070100 | 200-200 | 23 | solo | - | Scrapyard Entrance 200 | AYU-163 |
| All jobs (fallback) | Knight Stronghold: Knights' Chamber 4 | 271030530 | 182-182 | 21 | solo | rebirth 2 | Knights' Chamber 4: Advanced Knight D 182 | AYU3 |
| All jobs (fallback) | Knight Stronghold: Hall of Honor | 271030600 | 177-184 | 25 | solo | rebirth 2 | Hall of Honor 177-184 | - |
| All jobs (fallback) | Twilight Perion: Deserted Southern Ridge | 273010000 | 190-190 | 25 | solo | rebirth 2 | Deserted Southern Ridge: Swollen Stump 190 [F3] | TSOM,GE |
| All jobs (fallback) | Twilight Perion: Weathered Rocky Zone | 273020300 | 190-192 | 40 | solo | rebirth 2 | Weathered Rocky Zone: 40 spawns [F3] | - |
| All jobs (fallback) | Twilight Perion: Tempest Grave | 273020400 | 192-192 | 38 | solo | rebirth 2 | Tempest Grave: 38 spawns [F3] | - |
| All jobs (fallback) | Twilight Perion: Forsaken Excavation Site 2 | 273040100 | 195-195 | 27 | solo | rebirth 2 | Forsaken Excavation Site 2: most-cited single map [L3F3] | AYU5,GE |
| All jobs (fallback) | Twilight Perion: Forsaken Excavation Site 4 | 273040300 | 196-196 | 30 | solo | rebirth 2 |  [L3F3] | - |
| All jobs (fallback) | Twilight Perion: Warrior Grounds | 273060300 | 198-198 | 26 | party | rebirth 2 | Warrior Grounds: party area, 196-198; id is outside the HyperTP range, tagged rebirth 2 by intent [H3D2] | TSOM |
| All jobs (fallback) | Polluted Sewer: Foul Glooger Habitat 3 | 402000453 | 184-184 | 32 | solo | rebirth 3 | Polluted Sewer: Foul Glooger 183-184 | - |
| All jobs (fallback) | Twisted Forest: Savage Dog Forest | 401010000 | 195-195 | 32 | solo | rebirth 3 | Heliseum Savage Dog Forest 195 | GE |
| All jobs (fallback) | Forbidden Forest: Yellow Speeyor's Pasture | 401020200 | 198-198 | 23 | solo | rebirth 3 | Yellow Speeyor's Pasture 198 | GE |
| All jobs (fallback) | Fox Valley: Fox Tree Top Path 2 | 410000112 | 191-192 | 41 | solo | rebirth 3 | Fox Tree Top Path 2: 191-192 | OM |
| All jobs (fallback) | Beyond the Clouds: Guardian Walkway 3 | 410000123 | 199-199 | 39 | solo | rebirth 3 | Guardian Walkway 3: 199 | - |

### Levels 200-255: Black Heaven (open), Arcane River / Grandis endgame (rebirth 3)

| Job group | Map | Map id | Mob lv | Spawns | Mode | Gate | Notes (elements: 2 = strong, 3 = weak; U = undead) | Src |
|---|---|---|---|---|---|---|---|---|
| All jobs (fallback) | Scrapyard: Scrapyard Hill 2 | 310070120 | 202-202 | 27 | solo | - | Scrapyard Hill 2 (mob levels 200-210) | AYU-163 |
| All jobs (fallback) | Scrapyard: Scrapyard Hill 4 | 310070140 | 204-206 | 17 | solo | - | Scrapyard Hill 4 | AYU-163 |
| All jobs (fallback) | Scrapyard: Scrapyard Hill 5 | 310070160 | 208-210 | 21 | solo | - | Scrapyard Hill 5 | - |
| All jobs (fallback) | Skyline: Skyline 2 | 310070230 | 213-216 | 23 | solo | - | Skyline 2: 212-216 | - |
| All jobs (fallback) | Black Heaven Decks: Black Heaven Entrance | 310070310 | 213-215 | 35 | solo | - | Black Heaven Entrance 213-215 | - |
| All jobs (fallback) | Black Heaven: Black Heaven Maze 6 | 310070480 | 219-219 | 50 | solo | - | Black Heaven Maze 6: 50 spawns, 219 | - |
| All jobs (fallback) | Twilight Perion: Warrior Grounds | 273060300 | 198-198 | 26 | party | rebirth 2 | Warrior Grounds still; id is outside the HyperTP range, tagged rebirth 2 by intent [H3D2] | - |
| All jobs (fallback) | Lake of Oblivion: Weathered Land of Happiness and Rage | 450001011 | 200-200 | 26 | solo | rebirth 3 | Weathered Land of Rage (VJ): lowest Arcane HP | GE,AYU9 |
| All jobs (fallback) | Extinction Zone: Fire Zone | 450001112 | 200-200 | 26 | solo | rebirth 3 | Fire Zone | GE |
| All jobs (fallback) | Cave of Repose: Arma's Hideout | 450001230 | 200-200 | 26 | solo | rebirth 3 | Cave of Repose | - |
| All jobs (fallback) | Slurpy Forest: Dealie-Bobber Forest 2 | 450002007 | 200-200 | 29 | solo | rebirth 3 | Slurpy Forest (Chu Chu) | GE |
| All jobs (fallback) | Lachelein Night Market: Chicken Festival 3 | 450003320 | 200-200 | 40 | solo | rebirth 3 | Chicken Festival 3 (Lachelein) | GE |
| All jobs (fallback) | Lachelein Clocktower: Nightmare Clocktower 4F | 450003530 | 200-200 | 27 | solo | rebirth 3 | Nightmare Clocktower 4F | GE |
| All jobs (fallback) | Arcana: The Forest of Earth | 450005130 | 200-200 | 25 | solo | rebirth 3 | Forest of Earth (Arcana) | GE |
| All jobs (fallback) | Morass: Path to the Coral Forest 2 | 450006010 | 200-200 | 70 | solo | rebirth 3 | Morass | - |
| All jobs (fallback) | Esfera: Living Spring 2 | 450007010 | 200-200 | 39 | solo | rebirth 3 | Esfera Living Spring | - |
| All jobs (fallback) | Moonbridge: Last Horizon 1 | 450009110 | 200-200 | 38 | solo | rebirth 3 | Moonbridge Last Horizon | - |
| All jobs (fallback) | Tenebris: Labyrinth of Suffering Interior 2 | 450011410 | 200-200 | 28 | solo | rebirth 3 | Labyrinth of Suffering | - |
| All jobs (fallback) | Limina: World's Sorrow Depths 1 | 450012020 | 200-200 | 23 | solo | rebirth 3 | Limina | - |
| All jobs (fallback) | Fox Valley: Fox Tree Side Path | 410000114 | 193-193 | 43 | solo | rebirth 3 | Fox Valley: Fox Tree Side Path 191-195 | OM |
| All jobs (fallback) | Fox Point Village: Fox Tree Midway 2 | 410000102 | 196-197 | 59 | solo | rebirth 3 | Fox Tree Midway 2: 59 spawns | - |
| All jobs (fallback) | Heliseum Downtown: East Downtown Walkway | 401040700 | 195-200 | 37 | solo | rebirth 3 | Heliseum East Downtown Walkway 195-200 | - |

## MapleRoot-specific areas (candidates for 120-255 and rebirth bands)

No v83 guide covers these. The mob levels, HP and density below come from MapleRoot's own data. The "source version"
column records where each area came from in GMS/KMS, per the later-era research (the "Later-era areas" sources), so the owner can judge
how far MapleRoot changed it.

Notes from the later-era guides:
- **Lion King's Castle:** in GMS it was a questline (115-125) rather than a grind spot, because spawns were low.
  MapleRoot's wall maps have 9-15 spawns each.
- **Gate to the Future** (Henesys Ruins, Dark Ereve, Knight Stronghold) was the standard 160-190 route in GMS 2013-2019:
  - Mutant Tino Forest (Dark Ereve): 155-165.
  - Second Drill Hall / Armory 2: 165-180.
  - Knight's Chamber / Hall of Honor: 179-193.
- **Twilight Perion** was the main 180-200 area, with no Star Force needed:
  - Swollen Stumps on Deserted Southern Ridge / Desolate Hills: 180-190.
  - Forsaken Excavation Site 2 (Rocky Mask): the most-cited single map, 185-200.
  - Warrior Grounds: Ancient Golems, the party area, 190-210.
- **Black Heaven Scrapyard Hill 4/5** were the 190-210 alternative.
- **Arcane River**, in GMS order: Vanishing Journey (200-210) → Chu Chu (210-220) → Lachelein (220-225) → Arcana (225-240)
  → Morass → Esfera (235-250) → Moonbridge → Labyrinth → Limina (255+).

  On MapleRoot every Arcane mob is level 200, and the HP column is the only real progression signal. Its order matches
  GMS order. Arcane Force / Sacred Symbol requirements are unknown on MapleRoot and should be checked before bots enter.
- **Mirror World fields** in GMS were Zero-only instanced "premium hunting" stages (one copy per stage `<1>`..`<7>`). On
  MapleRoot they are plain field maps with 17-18 spawns. Nothing in `Map.wz` portals into them from the classic world,
  so a bot would have to be warped in. The Hyper Teleport Rock allows that; it is ungated.

| Area | Map ids | Maps w/ mobs | Mob lv (MapleRoot) | Mob HP | Densest maps | Source version | Notes |
|---|---|---|---|---|---|---|---|
| Lion King's Castle (walls) | 211060100-211060900 | 14 | 117-127 | 251k-500k | 211060700 (15), 211060500 (14) | KMS 2010 / GMS ~2011 | questline 115-125 in GMS; LKC gate = rebirth 1 |
| Lion King's Castle / Desolate Moor / Gate (3rd-job story copies) | 306010100-306040100 | 13 | 110-110 | 617k-617k | 306030100 (19), 306030200 (19) | later GMS story maps | outside the LKC gate range |
| Mirror World: Leafre fields | 321110000-321146000 | 28 | 102-108 | 198k-260k | 321115000 (18), 321124000 (18) | KMST 1.2.482 (Jul 2013), Zero-only in GMS | no portal in from classic world |
| Mirror World: Ariant | 322010000-322046000 | 28 | 112-118 | 308k-388k | 322036000 (18), 322045000 (18) | same |  |
| Mirror World: Henesys | 323010000-323046000 | 28 | 122-128 | 447k-565k | 323010000 (18), 323044000 (18) | same |  |
| Mirror World: Mu Lung | 324010000-324056000 | 35 | 131-139 | 663k-959k | 324024000 (18), 324041000 (18) | same |  |
| Mirror World: Edelstein | 325010000-325046000 | 28 | 142-148 | 1M-1M | 325014000 (18), 325025000 (18) | same |  |
| Mirror World: Magatia | 326010000-326046000 | 28 | 152-158 | 2M-2M | 326044000 (18), 326021000 (18) | same |  |
| Mirror World: Ludibrium | 327010000-327046000 | 28 | 162-168 | 3M-3M | 327011000 (18), 327036000 (18) | same |  |
| Mirror World: Temple of Time | 328010000-328036000 | 21 | 173-179 | 4M-5M | 328016000 (18), 328031000 (18) | same |  |
| Kritias | 329010000-329024000 | 10 | 140-180 | 7M-21M | 329010000 (18), 329021000 (18) | KMS Dec 2015 / GMS ~2016 | all elements strong |
| Gate to the Future: Henesys Ruins / Ravaged Forest / Singing Mushroom ruins | 271010100-271020100 | 7 | 162-168 | 350k-410k | 271010300 (25), 271010200 (25) | GMS ~2012 | rebirth 2 range |
| Gate to the Future: Dark Ereve | 271000100-271000210 | 3 | 163-167 | 360k-400k | 271000100 (21), 271000200 (15) | GMS ~2012 | rebirth 2 range |
| Knight Stronghold | 271030010-271030600 | 20 | 168-184 | 5M-30M | 271030101 (49), 271030102 (32) | GMS ~2012 | rebirth 2 range and HyperTP-restricted |
| Leafre of Past / Dark ToT / Dimensional Schism | 272000100-272030400 | 13 | 10-166 | 10-390k | 272000500 (23), 272000600 (18) | GMS ~2012 | rebirth 2 range |
| Twilight Perion | 273010000-273060300 | 18 | 190-198 | 11M-14M | 273020300 (40), 273020400 (38) | GMS v.137 Unleashed (Jun 2013) | Warrior Grounds 273060300 outside gate |
| Ellin Forest | 300010000-300030310 | 15 | 42-109 | 2k-158k | 300030200 (19), 300030300 (19) | theme dungeon ~2012 |  |
| Stormy Forest / Leafre / Aqua / Mu Lung 3rd-job story maps | 302050100-303050200 | 16 | 80-113 | 27k-2M | 303050100 (25), 303050000 (21) | later GMS |  |
| Gelimer Lab / Verne Mine / Ereve in Danger | 304010100-304060300 | 11 | 100-110 | 520k-617k | 304060200 (21), 304010300 (19) | Resistance era (2010) |  |
| Grand Athenaeum | 305000000-305010100 | 4 | 105-110 | 331k-411k | 305010000 (16), 305000000 (15) | theme dungeon |  |
| Shadowvale / Umbra Temple | 307000010-307000330 | 10 | 20-190 | 375-39M | 307000221 (20), 307000231 (19) | Zero Mirror World |  |
| Edelstein (Resistance start) | 310010100-310060300 | 32 | 5-85 | 50-70k | 310040000 (18), 310010100 (16) | GMS 2010 |  |
| Black Heaven: Scrapyard to Maze | 310070100-310070490 | 25 | 200-219 | 15M-25M | 310070480 (50), 310070410 (43) | GMS v.160-163 (2015) | ungated; reachable from Edelstein |
| Grandis: Pantheon | 400010100-400040300 | 16 | 113-141 | 2M-3M | 400030100 (46), 400020100 (46) | Kaiser/AB start ~2013-14 | rescaled +100; rebirth 3 |
| Grandis: Heliseum + Tyrant's Castle | 401010000-401060300 | 40 | 195-200 | 4M-4M | 401040800 (37), 401040700 (37) | ~2014 | rescaled ~+100; rebirth 3 |
| Grandis: Savage Terminal / Illium/Ark areas | 402000110-402000647 | 76 | 109-184 | 2M-33M | 402000518 (62), 402000517 (61) | Ark ~2017 | rescaled; rebirth 3 |
| Grandis: Fox Ridge | 410000010-410000051 | 12 | 113-128 | 2M-3M | 410000022 (35), 410000011 (30) | ? | rebirth 3 |
| Grandis: Fox Valley / Fox Point / Beyond the Clouds | 410000101-410000123 | 13 | 191-199 | 34M-36M | 410000102 (59), 410000103 (56) | KMS 1.2.286 (Oct 2017) | rebirth 3 |
| Grandis: Cheong-woon Valley / Handsome Monk's Temple | 410000210-410000243 | 11 | 112-130 | 2M-3M | 410000242 (24), 410000243 (24) | Hoyoung start (GMS Nov 2019) | rescaled; rebirth 3 |
| Grandis: Ristonia / Toolen City | 410000311-410000422 | 26 | 110-130 | 2M-3M | 410000321 (68), 410000312 (64) | Lara start? (unconfirmed) | rescaled; rebirth 3 |
| Grandis: Cernium / Burning Cernium | 410000520-410001030 | 32 | 200-200 | 2147M-2147M | 410001030 (40), 410001000 (39) | GMS Neo (Jun 2021) | maxHP int max: exclude; rebirth 3 |
| Arcane: Vanishing Journey | 450001010-450001500 | 27 | 200-200 | 45M-101M | 450001260 (37), 450001262 (29) | V update (2016+) | rebirth 3 |
| Arcane: Chu Chu Island | 450002001-450002302 | 23 | 200-200 | 120M-507M | 450002017 (29), 450002016 (29) |  |  |
| Arcane: Lachelein | 450003200-450003560 | 21 | 200-200 | 188M-507M | 450003210 (44), 450003220 (43) |  |  |
| Arcane: Arcana | 450005110-450005550 | 26 | 200-200 | 259M-319M | 450005411 (45), 450005412 (42) |  |  |
| Arcane: Morass | 450006010-450006430 | 17 | 200-200 | 294M-440M | 450006010 (70), 450006030 (65) |  |  |
| Arcane: Esfera | 450007010-450007220 | 14 | 200-200 | 319M-474M | 450007220 (41), 450007030 (39) |  |  |
| Arcane: Moonbridge | 450009110-450009360 | 20 | 200-200 | 531M-570M | 450009302 (45), 450009202 (41) |  |  |
| Arcane: Labyrinth of Suffering (Tenebris) | 450011410-450011650 | 18 | 200-200 | 581M-623M | 450011550 (38), 450011560 (36) |  |  |
| Arcane: Limina | 450012020-450012440 | 19 | 200-200 | 634M-678M | 450012301 (58), 450012303 (46) |  |  |

Also present but excluded from the draft:
- **`867xxxxxx` custom/event content:** Eluna Prime, Retro World, The Afterlands, Aspire, the "Stage 1..Final" event
  copies of Zipangu/Malaysia/Shanghai/Commerci/Masteria, and others. Their mob data is placeholder (1000 HP / 10 EXP,
  0 EXP, level 250 with 10 HP) or missing entirely.
- **`350xxxxxx` story instances:** Lumiere, Black Heaven story, and Heroes of Maple. Heroes of Maple has 71 spawn
  entries whose mobs are missing from `Mob.wz`, plus 0 EXP.
- **Haunted House/Phantom Forest area bosses** (Headless Horseman, Bigfoot): guides recommend these for 80-150, but they
  are single boss spawns on `610010100-610010202` / `682000001`, not grind maps.
- **Premium Road (`19xxxxxxx`) internet-café maps.**

## Unverified or unmapped spots

The guides name these spots, but they could not be pinned to a map id on this server, or they hold no usable mobs:

| Guide name | Status |
|---|---|
| "Himes" | Not a mob name. It means Dreamy Ghost on Encounter with the Buddha `800020130` (mapped). |
| Gobies (Deep Sea Gorge) | Goby has no map life entry. It is presumably summoned by Bombing Fish House; the gorges `230040000/100` are mapped as Bone Fish maps. |
| Kappa, Night Fox, King Clang, Krappi | The mob strings exist, but no map spawns them. Not mapped. |
| Ghost Ship 3, 4, 7 | `541010020/030/060` exist with **0 spawns**. Not mapped. |
| Ludibrium "Path of Time" (`220050300`) | 0 spawns. Guides mean The Path of Time <1>-<3> `220040000/100/300` and Lost Time `220050000/200` (mapped). |
| "Rainforest East of Henesys", "Cave where mushrooms grow", "Origin of Clocktower", "Female Boss" Parlor | No map by that name; the Parlor `801040003` has one Female Boss among other mobs. Not mapped. |
| Truckers | Trucker L48 on Singapore Suburban Area 2 `540020100` (mapped). |
| Ninja Castle (Mushroom Shrine) | Zipangu "Castle Corridor" `800040200-800040208` (mapped). |
| Cygnus "X's Forest" maps | Present in v83 data (`130010010-130010210`). The guide caveat about later versions does not apply here. |
| Crimsonwood Keep / CWKPQ | Present (`610020006`, `610030000+`). The two research passes disagreed on whether it is v83; it ships with v83 Cosmic data. |
| Ulu City / Destroyed Park, Neo City | Post-v83 in GMS but present on this server. Used at low weight only. |
| Bosses (Zakum, Papulatus, Horntail, Krexel, Anego) | Out of scope for grind maps. |
| Temple of Time | Present (`270010100-270030500`, Memory Lane 91-101, Road of Regrets 106-116, Road to Oblivion 121-131). One pass doubted it is v83; the data settles it. |

## Gaps for the implementation

- **Party content:**
  - Party quests have no automation. They are recorded in `party_quests` only.
  - Several consensus spots are party spots: Himes with a priest, Squids, Warrior Grounds, Showa Parking Lot. They
    are marked `mode: party/both`.
- **Job specificity above 120** is general-only, since the guides turn to bossing there. Element tags in the tables let
  a later pass add mage preferences automatically:
  - F/P to ice-weak (`I2F3`) maps.
  - I/L to fire-weak (`F2I3`) and lightning-weak (`L3`) maps.
  - Clerics to undead (`U`) and holy-weak (`H3`) maps.
- **Rebirth bands:** the draft tags gated entries with `rebirths: N` inside the level bands. It does not yet have
  separate "rebirth count" bands. Open question for the owner: does MapleRoot reset level on rebirth (so a rebirth-3
  bot at level 120 should prefer Pantheon/Ristonia), or not?
- **Travel:** most 50-120 spots are outside today's `TrainingRegions`. Ticket 03 must cover:
  - The Ellinia-Orbis boat.
  - Orbis to Leafre, Ludibrium, Ariant and Mu Lung.
  - Aqua Road.
  - The Singapore/Malaysia airport, Zipangu (Showa), Masteria/NLC, Magatia and Ereve.
  - Edelstein, for Black Heaven.
- **Levels 220-255:** there are no harder mobs. Bots at 220+ should keep cycling the 200-219 maps, or Arcane River
  with rebirth 3.

## Sources

Explorer warrior/thief/pirate and Aran:

| Code | Source |
|---|---|
| CAT | Catlaunch's Training Guide (MapleRoyals): https://royals.ms/forum/threads/catlaunchs-training-guide.12021/ |
| COMM | Community training guide (MapleRoyals): https://royals.ms/forum/threads/community-training-guide-leave-suggestions.651/ |
| SIRE | Sire's Complete MapleRoyals Warrior Guide: https://royals.ms/forum/threads/sires-complete-mapleroyals-warrior-guide.8474/ |
| HERO | Hero Guide (MapleRoyals): https://royals.ms/forum/threads/hero-guide.57080/ |
| DK | Into Darkness DK guide: https://royals.ms/forum/threads/no-longer-updated-into-darkness-dark-knight-guide.131788/ |
| PAL | Smashing! A Guide to Paladins: https://royals.ms/forum/threads/smashing-a-guide-to-paladins.118048/ |
| NL | A comprehensive Night Lord guide: https://royals.ms/forum/threads/a-comprehensive-night-lord-guide.23303/ |
| SHAD | In-Depth Shadower Guide: https://royals.ms/forum/threads/in-depth-shadower-guide.8122/ |
| BAND | Bandit guide training: https://royals.ms/forum/threads/bandit-guide-training.96579/ |
| BUCC | Donn1e's Buccaneer Guide: https://royals.ms/forum/threads/donn1es-buccaneer-guide.188641/ |
| SAIR | A Comprehensive Corsair Guide: https://royals.ms/forum/threads/a-comprehensive-corsair-guide.122934/ |
| AYW | Ayumilove 2008, warrior training spots: https://ayumilovemaple.wordpress.com/2008/08/31/maplestory-ultimate-guide-warriors-training-spot/ |
| AYT | Ayumilove 2008, thief training spots: https://ayumilovemaple.wordpress.com/2008/09/07/maplestory-ultimate-guide-thief-training-spot/ |
| AYP | Ayumilove 2008, pirate training spots: https://ayumilovemaple.wordpress.com/2008/09/19/maplestory-ultimate-guide-pirate-training-spot/ |
| AYA | Ayumilove 2009 Aran guide (quotes a Hidden Street list): https://ayumilovemaple.wordpress.com/2009/08/16/maplestory-aran-skills-skill-build/ |
| ARB | Aran the Legend blog (2010): http://aranthelegend.blogspot.com/2010/02/places-to-train-and-job-advance.html |
| DMS | DreamMS Comprehensive Aran Guide: https://forum.dreamms.gg/threads/comprehensive-aran-guide.3503/ |
| HS | Hidden Street Aran list, via a search snippet (the page returned 403) |

Explorer mage/archer and Cygnus:

| Code | Source |
|---|---|
| AYU-M | Ayumilove 2008, magician training spots: https://ayumilovemaple.wordpress.com/2008/09/01/maplestory-ultimate-guide-magician-training-spot/ |
| AYU-B | Ayumilove 2008, bowman training spots: https://ayumilovemaple.wordpress.com/2008/09/01/maplestory-ultimate-guide-bowman-training-spot/ |
| RLG | MapleRoyals leveling guide: https://royals.ms/forum/threads/2025-leveling-guide.157840/ |
| RUPD | MapleRoyals updated leveling guide: https://royals.ms/forum/threads/updated-leveling-guide.119206/ |
| RIL | MapleRoyals I/L guide: https://royals.ms/forum/threads/ice-lightning-mage-guide.18154/ |
| RBISH | Bella's Bishop Guide: https://royals.ms/forum/threads/bellas-bishop-guide.2997/ |
| RCLER | Cleric training spots thread: https://royals.ms/forum/threads/cleric-training-spots.253668/ |
| RMAG30 | Dave's magician 1-30 guide: https://royals.ms/forum/threads/guide-for-newbie-magician-to-level-30-by-dave.198200/ |
| RBOW | Bowman Training Guide (MapleRoyals): https://royals.ms/forum/threads/bowman-training-guide.434/ |
| RSNIP | Archer-to-Sniper guide: https://royals.ms/forum/threads/a-relatively-complete-guide-from-archer-to-sniper.7215/ |
| RBM | Fius' Bowmaster guide: https://royals.ms/forum/threads/fius-somewhat-okay-bowmaster-guide.32068/ |
| WB | Wikibooks Bowman Guide: https://en.wikibooks.org/wiki/MapleStory/Bowman_Guide |
| DMS-ALL | DreamMS all-jobs training guide: https://forum.dreamms.gg/threads/light-bulb-training-and-job-advancement-guide-all-jobs.2815/ |
| DMS-TB1 | DreamMS Thunder Breaker guide: https://forum.dreamms.gg/threads/the-thunder-breaker-guide-of-all-time-2025.10580/ |
| DMS-WA | Sabrina's Wind Archer guide: https://forum.dreamms.gg/threads/sabrinas-windarcher-guide.3092/ |
| DMS-DW | DreamMS Dawn Warrior guide: https://forum.dreamms.gg/threads/dawn-warrior-guide.2178/ |
| MSW-EREVE | maplestorywiki Ereve page: https://maplestorywiki.net/w/Ereve |

Later-era areas:

| Code | Source |
|---|---|
| AYU3 / AYU5 / AYU9 | Ayumilove training guides V3, V5, V9: https://ayumilove.net/best-maplestory-training-spots-guide-v3/ , https://ayumilove.net/best-maplestory-training-spots-guide-v5/ , https://ayumilove.net/best-maplestory-training-spots-guide-v9/ |
| AYU-163 | GMS v.163 patch notes (Scrapyard): https://ayumilove.net/maplestory-update-notes-gms-v163/ (v.137, Twilight Perion: https://ayumilove.net/maplestory-update-notes-gms-v137/) |
| GE | gamerempire training guide: https://gamerempire.net/maplestory-training-guide/ |
| TSOM | The Story of Maple, Twilight Perion: https://thestoryofmaple.squarespace.com/twilight-perion |
| OM | Orange Mushroom (Fox Valley, Mirror World): https://orangemushroom.net/2017/10/25/kms-ver-1-2-286-fox-valley-halloween/ , https://orangemushroom.net/2013/07/09/kmst-ver-1-2-482-zero-mirror-world/ |
| GL | Grandis Library level content guide: https://grandislibrary.com/content/level-content-guide |
| LE-wiki | maplestorywiki pages (Lion King's Castle, Heliseum, Savage Terminal, Kritias, Mirror World, Western Minar Forest), via search snippets |

Other codes:

| Code | Meaning |
|---|---|
| CON | Common to many guides; no single source |
| MR | MapleRoot server data only |
| - | Picked from server data to fill a band; no guide cites it |
