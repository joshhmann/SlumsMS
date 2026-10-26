# MapleRoot's NPC changes in SlumsMS: catalogue

Research snapshot of 2026-10-25, branch `152-integration` (commit 277631c1 and later).
Read-only study: no scripts, game data, DB or servers were touched. Owner decisions are collected at the end.

## 1. Summary

**How this was built.** Stock = `/root/work/cosmic-triage/Cosmic` (`scripts/npc/*.js`, `wz/Map.wz` life entries). Ours = the TEST server's
`/opt/mapleroot-dev/persistence-test/Server/wz` (Map.wz life entries of type `n`, String.wz names) plus the TEST DB `plife` table and
`shops` table, and this repo's `scripts/npc/`. Script diffs ignore whitespace and blank lines (`diff -wB`). Nothing was run in a client;
every "what it does" line comes from reading the script, so "untested in a real client" applies to all of it.

**Headline numbers**

| Measure | Count |
|---|---|
| Maps with at least one NPC: Cosmic / ours | 1,852 / 3,376 |
| Maps ours has that Cosmic does not (later-version WZ content; ids 67..993910000) | 1524 maps, 4,755 NPC placements, 2,692 distinct NPC ids |
| Of those ids: with a script file / with a shop row / with neither (inert on click) | 107 / 24 / 2,561 |
| NPCs added to a map Cosmic also has (Map.wz) | 2 (Plunk 3004006 in Horntail's Cave and Zakum's Altar) |
| NPC placements Cosmic has that our Map.wz dropped | 15 (6 town Gachapon machines, 7 town Maple TVs, 2 boss-room exit NPCs) |
| `plife` rows (permanent NPCs, SlumsMS-added) | 16 rows = 8 NPC spots x 2 worlds (world 0 and 1) |
| Scripts in `scripts/npc/` missing from Cosmic | 68 files (32 named by NPC id, 36 helper/command scripts) |
| Scripts that differ from Cosmic (whitespace ignored) | 83 files (77 named by NPC id, 6 helpers) |
| Scripts removed from Cosmic's set | 0 |
| NPC ids with a script but not placed on any map or in `plife` | 10 (section 9) |

Reading the numbers: in the 5,263 maps both servers share, MapleRoot barely touched NPC placement (2 added, 15 removed). Almost all
of MapleRoot's work is (a) a much bigger map set from a later client version, where nearly every NPC is unscripted, (b) the Base of
Roots hub (993910000), a map Cosmic does not have, and (c) scripts: stock "I'm just a placeholder" NPCs turned into real services, and
a set of boss-expedition recruiters/exit NPCs for Grandis/Arcane content.

**Where the "borrowed" NPCs came from.** Hub NPC stock homes: Cotton Warper 9010002 from Henesys (also still there), Job Master 9010003 from Ellinia
(also still there), Cotton Stylist 9900000 from the GM Room 180000000 (also still there), Gerrard 2060008 (Storage) from 230000002 Aquarium,
"4th job skills" 2084000 (the owner's "Gold Compass") from Gold Richie's Treasure Vault <1>..<5> (390000000-390000400, 11 maps in stock, 12 in ours). Hub-only
(no stock home at all): Glowing Treasure 2616, Wyatt the Reborn 1300009, Lab Tech 3004015, Rooty 9010004, Key Binder 9977778, Nimble 9977779.

**Top issues (details with file:line in section 10).**
1. `USE_ENABLE_CUSTOM_NPC_SCRIPT: false` on TEST and LIVE turns Coco 9000017, Agent E 9000036 and Dalair 9000040 (placed in about 20 towns each), the Maple TV scroll
   generator, Asia 2082014, T-1337 9201101 and Spiegelmann's ore refining into "Hi, I'm X" dead ends. These are MapleRoot's restored HeavenMS services.
2. Jump Quest chests (1063000/1/2, 2032003) pay 100,000 mesos + 10,000 exp x rate per run with no cooldown, and Cotton Warper/Jump Quests NPC 9010001 warps in for free.
3. Retired ore refiners (Perion 1022003, Vogen 2020000, Pi 2040016, Arwen 1032100, Muhamad 2100001) all say "go to Nimble in the Free Market", but Nimble 9977779 stands only in the hub.
4. Arwen 1032100 is gated at `getLevel() >= 3000`: Moon Rock, Star Rock and Black Feather crafting is unreachable, and Nimble does not craft them.
5. The Rebirth NPC takes no item: the rebirth key item (4033446/4033442/4033450) is checked, never consumed, and the item may be tradable.
6. Boss recruiters/wake NPCs with scripts but no placement: Von Leon (2161000/2161008), Lucid robe 3002122, Cygnus 3003903/3003970, Castellan 9111058 (Tengu), Murgoth 9000174.
7. CPQ NPCs (2042000/1/2/5) allow a party of 1 up to level 200 (stock: 2+ players, level 30-50); 2042002 hard-codes "solo expedition" ignoring the config flag.
8. Pink Bean exit NPC 2141002 dereferences a null event instance outside the fight and hands extra rewards (1000 Power Elixirs and more) on top of `giveEventReward`.
9. Cotton Stylist (STYLIST.js) keeps hidden catalogs (selections 40, 50, 80, 91, 92) that change hair/face with no fee, reachable by a forged selection.
10. Rooty (9010004.js) menu skips choices 3 and 5 that the code handles; Donor Shop (donorshop.js, 1,647 lines) and Rooty (rooty.js, 889 lines) are near-duplicate catalogs.

**1.5.2 work already done on these NPCs (not re-flagged below, marked "1.5.2")** is listed in section 11.

Flag legend: BROKEN = does not work or cannot be reached; EXPLOIT = free value, no cost, or a gate bypass; DUP = duplicates another NPC's service; DEAD = script/NPC cannot be reached in play; NOTE = worth knowing, no action needed; OK = nothing found.


## 2. Base of Roots hub (map 993910000, "Hidden Street : Base of Roots")

Cosmic has no such map. New characters of every creator start here (Maple Administrator 2007 also sends skippers here). 11 NPCs stand in it, none from `plife`.

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 2616 | Glowing Treasure | 993910000 Base of Roots | none (not placed in Cosmic) | 2616.js | Starforce: choose an equip and pay mesos plus Spell Traces to raise its star level for a chance at +stats; shows the success rate first. | Mesos (cm.getMesoStarforceCost) + Spell Traces 4000999 (cm.getSpellTraceCost), both scale with star level | NOTE: after a success the script resets status=0 without dispose (lines 75-79), so the dialog stays open; otherwise OK |
| 1300009 | Wyatt the Reborn | 993910000 Base of Roots | none (not placed in Cosmic) | (none) NPCTalkHandler.java:80 -> rebirth.js | Rebirth: at level 200 and fully advanced, reborn as Beginner/Noblesse/Legend; the exp rate becomes the rebirth-tier rate (ledger NP-009: x3/x2/x1), up to 3 rebirths. Also the host NPC id for the @craft, @exchange, @memory, @minesweeper, @damageskin command windows. | Level 200 + one key item per rebirth: 4033446 (1st), 4033442 (2nd), 4033450 (3rd). Item is only checked (rebirth.js:69), never taken | EXPLOIT (minor): key item is never consumed, so a tradable item could be lent around; confirm the three items are untradable. Divergence-ledger NP-009 "keep" |
| 2060008 | Gerrard | 12001 (unnamed non-stock map); 230000002 Department Store; 993910000 Base of Roots | 230000002 Department Store | 2060008.js (identical to Cosmic) | Storage keeper: opens the account storage. | Normal storage rules | OK (hub-wide storage is a MapleRoot convenience) |
| 2084000 | 4th job skills | 390000000 Gold Richie's Treasure Vault <1>; 390000100 Gold Richie's Treasure Vault <2>; 390000200 Gold Richie's Treasure Vault <3> (+9 more, 12 maps) | 390000000 Gold Richie's Treasure Vault <1> (+10 more, 11 maps) | 2084000.js | Sells every 4th-job skill that otherwise needs a skill book or quest, mastery 10 (25 for two Dark Knight skills) plus some extra high-level skills, but only for the player's own job line and the lines of previous rebirths. Name in String.wz is literally "4th job skills". | 10,000,000 mesos (100,000,000 if the job id is 0, i.e. a freshly reborn Beginner) | 1.5.2 gave it its own job line. EXPLOIT/NOTE: skips every 4th-job quest and skill-book gate for 10m; the x10 price for Beginners is a rebirth-return fee, not a bug |
| 3004015 | Lab Tech | 993910000 Base of Roots | none (not placed in Cosmic) | 3004015.js | Info only: lists today's boss attempts used / allowed for 18 bosses (Zakum, Horntail, Pink Bean, Papulatus, Von Leon, Cygnus, Lucid, Will, Verus, Darknell Normal/Hard, Magnus, Krexel, Castellan, Scarga). | free | OK |
| 9010002 | Cotton Warper | 100000000 Henesys; 993910000 Base of Roots | 100000000 Henesys | 9010002.js | Cotton Warper teleporter: Boss Maps (7 maps: 220080000 Deep Inside the Clocktower; 105100100 Bottom of the Temple; 551030100 Entrance to the Spooky World; 541020700 Ruins of Krexel I; 800040401 Boudoir 2; 211042400 Entrance to Zakum Altar; 240050400 Entrance to Horntail's Cave), Grandis towns (9), Arcane River and Future towns (15). | Free. Grandis and Arcane River need 3 rebirths | 1.5.2 (Henesys teleporter). NOTE: also stands in Henesys. Warps skip every map quest and level gate |
| 9010003 | Job Master | 101000000 Ellinia; 993910000 Base of Roots | 101000000 Ellinia | 9010003.js | Job Master: advance to any next job from level 1 to 4th job (and 5th-job tier ids) with no test. Gives starter gear and 50 Power Elixirs on each advance; Super Beginner 700/710 line for the 1051 skill holders. | Level gates 30/70/120; fee for skipping the test: 1st job free, 2nd 800,000, 3rd 15,000,000, 4th 100,000,000 mesos; 5th tier needs item 4036541 and 3 rebirths (item not consumed) | 1.5.2 (fees, 4th-job masteries, no-regex rewrite). Divergence-ledger SK-035/NP-001 |
| 9010004 | Rooty | 993910000 Base of Roots | none (not placed in Cosmic) | 9010004.js (opens rooty.js, retrojqrewards.js, serverinfo.js) | Menu NPC: Rooty and Donor Shop (both open the one merged shop, rooty.js: Nx Goodies, Donor NX, mastery books, Maple weapons, Root Shop QoL items, Donor Shop stamps), Retro JQ rewards (100 Donkey Kong / Yoshi coins for a pet), Server Information. | Nx Goodies 1 Root Coin 4310000 each; Donor NX 1 Billion Coin 4001253 each; Maple weapons 5/10/15/20 Maple Leaf 4001126; High-Five Stamps 5590000/5590001 10 Billion Coins; QoL items 5130000/5520000 100 leaves, 5510000 10m mesos, 5041000 2m mesos | 1.6.0: donorshop.js merged into rooty.js (one catalog, each item id once; Maple weapons buyable again). NOTE: code handles menu choices 3 (damage skin options) and 5 (reload canvas) that the menu never shows (lines 32-38). 1.5.2 limited the shop to listed items |
| 9900000 | Cotton Stylist | 900 (unnamed non-stock map); 180000000 GM Room; 329999999 (unnamed non-stock map) (+1 more, 4 maps) | 180000000 GM Room | 9900000.js -> STYLIST.js / saloon.js | Cotton Stylist: change skin, hair colour, eye colour, hair and face from catalogs; the Saloon saves and reloads looks by name. | 1 Perfect Pitch (4310000) per change (STYLIST.js line 5); Saloon is free | 1.5.2 (server-side validation). EXPLOIT (low): hidden free catalogs at STYLIST.js:277-326 (selection 40/50/80/91/92) skip the fee; Saloon keeps an unbounded list of saved looks |
| 9977778 | Key Binder | 993910000 Base of Roots | none (not placed in Cosmic) | 9977778.js | Rebirth helper: re-bind skills of earlier rebirth jobs to keys, set up three skill macros, bind the current job, and claim 100 SP once per rebirth. | free (SP claim is counted server-side per rebirth) | 1.5.2 (SP once per rebirth). NOTE: stale 9977779.old.js sits next to the live Nimble script |
| 9977779 | Nimble | 993910000 Base of Roots | none (not placed in Cosmic) | 9977779.js (+ interactions/*.js) | Nimble the forger: craft equipment from materials, refine mineral ores into plates and jewels. | Materials + mesos per interactions/CraftingEquipment.js and RefiningMaterials.js (amounts not audited here) | DUP/NOTE: every retired refiner points players here "in the Free Market", but Nimble only stands in this hub |


## 3. Free Market and SlumsMS-added placements (`plife`)

Free Market 910000000 has 4 stock NPCs in both servers (9000069, 9030000, 9030100, 9250025); MapleRoot changed nothing there. SlumsMS added the rows below
to the TEST `plife` table through migrations 013, 021 and 026 (world 0 and world 1 identical). They are not MapleRoot changes but are listed because they
explain differences from Cosmic's Map.wz.

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 9100101 | Gachapon | 101000000 Ellinia at x=1120, y=288 (plife) | same town, but our Map.wz dropped it (Cosmic x/y differ) | NPCTalkHandler -> gachapon.js | Ellinia gachapon machine: spend one Gachapon Ticket (5220000) for one roll of that town's pool (gachapon-pools.yaml). | Gachapon Ticket; prize must fit first | OK (migration 013, 1.5.0) |
| 9100102 | Gachapon | 102000000 Perion at x=1500, y=589 (plife) | same town, but our Map.wz dropped it (Cosmic x/y differ) | NPCTalkHandler -> gachapon.js | Perion gachapon machine: spend one Gachapon Ticket (5220000) for one roll of that town's pool (gachapon-pools.yaml). | Gachapon Ticket; prize must fit first | OK (migration 013, 1.5.0) |
| 9100103 | Gachapon | 103000000 Kerning City at x=900, y=156 (plife) | same town, but our Map.wz dropped it (Cosmic x/y differ) | NPCTalkHandler -> gachapon.js | Kerning City gachapon machine: spend one Gachapon Ticket (5220000) for one roll of that town's pool (gachapon-pools.yaml). | Gachapon Ticket; prize must fit first | OK (migration 013, 1.5.0) |
| 9100104 | Gachapon | 105040300 Sleepywood at x=545, y=255 (plife) | same town, but our Map.wz dropped it (Cosmic x/y differ) | NPCTalkHandler -> gachapon.js | Sleepywood gachapon machine: spend one Gachapon Ticket (5220000) for one roll of that town's pool (gachapon-pools.yaml). | Gachapon Ticket; prize must fit first | OK (migration 013, 1.5.0) |
| 9100105 | Gachapon | 800000000 Mushroom Shrine at x=3320, y=95 (plife) | same town, but our Map.wz dropped it (Cosmic x/y differ) | NPCTalkHandler -> gachapon.js | Mushroom Shrine gachapon machine: spend one Gachapon Ticket (5220000) for one roll of that town's pool (gachapon-pools.yaml). | Gachapon Ticket; prize must fit first | OK (migration 013, 1.5.0) |
| 9100109 | Gachapon | 600000000 New Leaf City - Town Center at x=3800, y=501 (plife) | same town, but our Map.wz dropped it (Cosmic x/y differ) | NPCTalkHandler -> gachapon.js | New Leaf City gachapon machine: spend one Gachapon Ticket (5220000) for one roll of that town's pool (gachapon-pools.yaml). | Gachapon Ticket; prize must fit first | OK (migration 013, 1.5.0) |
| 9209006 | Hanako | 910000000 Free Market Entrance at x=-250, y=34 (plife) | 680100000-680100003 Amoria wedding maps | MesoSinks.java (NPCTalkHandler:73) | Special item merchant: sells hats and cosmetic items for mesos from economy.yaml. | Mesos per item | OK, meso sink (migration 021, 1.5.0) |
| 9000066 | Dalair | 100000000 Henesys at x=4550, y=454 (plife) | none (not placed in Cosmic) | (none) | Medal-quest hand-in NPC: only quests talk to him. | n/a | 1.5.2 (migration 026). NOTE: clicking him outside a quest logs "NPC is not coded" and shows nothing |


**Stock placements our Map.wz dropped (15).** All 7 town Maple TVs are gone but `mapleTV.js` and the NPCTalkHandler "Maple TV" name hook remain.

| Stock NPC ids | Name | Stock maps | Script | Note | Flag |
|---|---|---|---|---|---|
| 9250042, 9250044, 9250045, 9250043, 9201066, 9270012, 9270013 | Maple TV (Henesys, Ellinia, Perion, Kerning, NLC, Mushroom Shrine, Showa) | 100000000, 101000000, 102000000, 103000000, 600000000, 800000000, 801000000 | mapleTV.js | Megaphone-TV broadcast NPC. Without the NPC placed, the feature cannot be reached from towns | NOTE: dead feature unless the TVs are restored (owner decision) |
| 9100101-9100105, 9100109 | Gachapon x6 | 101000000, 102000000, 103000000, 105040300, 800000000, 600000000 | gachapon.js | Replaced by `plife` rows above | OK |
| 2083002 | Crystal of Roots | 240060200 Horntail's Cave | 2083002.js (stock) | Exit NPC of the Horntail fight; replaced by Plunk 3004006 | OK, superseded |
| 2030010 | Amon | 280030000 Zakum's Altar | 2030010.js (changed) | Exit NPC of the Zakum fight; replaced by Plunk 3004006 (Amon still stands in 280020000/1 and 280030001) | OK, superseded |


## 4. Services placed in many towns

These stand in about 20 town and city maps each in both Cosmic and ours (the placement is stock; the script is what MapleRoot changed). They are the largest block of
changed scripts because Cosmic ships them as placeholders ("Hi, I'm X", "the medal ranking system is currently unavailable").

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 9000017 | Coco | 100000000 Henesys; 101000000 Ellinia (+19 more, 21 maps) | 100000000 Henesys; 101000000 Ellinia (+19 more, 21 maps) | 9000017.js | Coco: synthesise a Chaos Scroll of Goodness (2049100) from 8 material types (e.g. 100 x 4031203, 60 x 4001356, 200 x 4000021) at 1,200,000 mesos each, up to 100 at a time. | 1,200,000 mesos + the materials per scroll | BROKEN on TEST and LIVE: gated by USE_ENABLE_CUSTOM_NPC_SCRIPT=false, so script line 55 only says "Hi, I'm Coco" |
| 9000036 | Agent E | 1500 (unnamed non-stock map); 1501 (unnamed non-stock map) (+20 more, 22 maps) | 100000000 Henesys; 101000000 Ellinia (+13 more, 15 maps) | 9000036.js | Agent E: refine pendants, face/eye accessories, belts/medals and rings from ingredients. | Materials + mesos 25,000-2,500,000 | BROKEN (same flag, line 43) |
| 9000040 | Dalair | 100000200 Henesys Park; 101000000 Ellinia (+17 more, 19 maps) | 100000200 Henesys Park; 101000000 Ellinia (+17 more, 19 maps) | 9000040.js | Dalair (the Equipment Merger): merge equips from later slots into the equipped item for extra stats. | 50,000 mesos; USE_STARTER_MERGE=true means no Maker level 3 / level 160 requirement | BROKEN (same flag, line 52). NOTE: shares the name "Dalair" with 9000066 |
| 9000041 | Donation Box | 100000200 Henesys Park; 101000000 Ellinia (+17 more, 19 maps) | 100000200 Henesys Park; 101000000 Ellinia (+17 more, 19 maps) | 9000041.js | Donation Box: sell an inclusive slot range of one inventory tab to the NPC for the normal sell price. | free; pays the NPC sell prices | NOTE: the config gate was commented out, so unlike its siblings this one works. Meso source: bulk-sells unlocked items (Maple Leaf sell price was reset by a 1.5.2 deploy step) |
| 9010001 | Jump Quests | 102000000 Perion; 540000000 CBD | 102000000 Perion; 540000000 CBD | 9010001.js | Jump Quests: warp to 4 Jump Quest maps (105040311/13/16 Deep Forest of Patience steps, 280020000 Breath of Lava). | free | EXPLOIT (with the chests below): free entry into repeatable meso/exp JQs |
| 9000038 | Agent Kitty | 970030000 Exclusive Training Center; 970030006 Resting Spot I; 970030007 Resting Spot II (+3 more, 6 maps) | 970030000 Exclusive Training Center; 970030006 Resting Spot I (+4 more, 6 maps) | 9000038.js | Agent Kitty (Exclusive Training Center): prize exchange for equipment, potions and ores in six level tiers; the scroll rows (2040xxx/2041xxx) were removed from every list. | Prize tokens per tier | NOTE: quantities for potions were also edited; not audited item by item |


## 5. Town by town: changed and added scripts that are placed

Only NPCs that have a script added or changed versus Cosmic are listed (every other NPC in these towns is stock). Groups of identical instructors share a row.


### Henesys (100000000 area)

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 1012100 | Athena Pierce | 100000201 Bowman Instructional School | 100000201 Bowman Instructional School | 1012100.js | Athena Pierce, Bowman job instructor: first job at level 10, with the Super Beginner (skill 1051) holders refused. | Level 10, free | NOTE: refuses Super Beginner (skill 1051) holders |
| 1012119 | Power B. Fore | 100010000 The Hill East of Henesys | 100010000 The Hill East of Henesys | 1012119.js | Power B. Fore (training ground, The Hill East of Henesys): enter the free training ground. | Level under 200 (stock: under 20) | 1.5.2 (text states the real limit). NOTE: stock limit was 20; 200 makes it a general grind spot |
| 9010002 | Cotton Warper | 100000000 Henesys; 993910000 Base of Roots | 100000000 Henesys | 9010002.js | See hub row: also stands in Henesys. | see hub | 1.5.2 |


### Ellinia (101000000 area)

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 1032001 | Grendel the Really Old | 101000003 Magic Library | 101000003 Magic Library | 1032001.js | Grendel the Really Old, Magician job instructor: first job at level 8; 1051 holders refused. | Level 8, free | NOTE: refuses Super Beginner (skill 1051) holders |
| 1032100 | Arwen the Fairy | 101000000 Ellinia | 101000000 Ellinia | 1032100.js | Arwen the Fairy: was the Moon Rock / Star Rock / Black Feather crafter; now says "I took an ore to the knee". | Moon/Star Rock cost raised 10k/15k to 500,000 mesos in the dead branch | BROKEN: line 59 `if (cm.getLevel() >= 3000)` guards the whole crafting menu, so it can never run. Nobody else crafts Moon Rock, Star Rock or Black Feather |
| 1032114 | Power B. Fore | 100050000 The Field South of Ellinia | 100050000 The Field South of Ellinia | 1032114.js | Power B. Fore (Field South of Ellinia training ground). | Level under 200 | NOTE as above |
| 9010003 | Job Master | 101000000 Ellinia; 993910000 Base of Roots | 101000000 Ellinia | 9010003.js | See hub row: also stands in Ellinia. | see hub | 1.5.2 |


### Perion (102000000 area)

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 1022000 | Dances with Balrog | 102000003 Warriors' Sanctuary | 102000003 Warriors' Sanctuary | 1022000.js | Dances with Balrog, Warrior job instructor (Warriors' Sanctuary): first job at level 10; 1051 holders refused. | Level 10, free | NOTE: refuses Super Beginner (skill 1051) holders |
| 1022003 | Mr. Thunder | 102000000 Perion | 102000000 Perion | 1022003.js | Mr. Thunder, helmet/shield upgrader: ore refining was retired and commented out; only helmet and shield upgrades remain. | Materials + mesos for upgrades | BROKEN (text): line 34 sends players to "Nimble in the Free Market" but Nimble stands in the hub |
| 1022104 | Power B. Fore | 910220000 Warrior Training Center | 910220000 Warrior Training Center | 1022104.js | Power B. Fore (Warrior Training Center) and 1022105 (Perion Street Corner): free training ground. | Level under 200 | NOTE |
| 9010001 | Jump Quests | 102000000 Perion; 540000000 CBD | 102000000 Perion; 540000000 CBD | 9010001.js | Jump Quests NPC (also Singapore CBD). | free | see section 4 |


### Kerning City (103000000 area)

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 1052001 | Dark Lord | 103000003 Thieves' Hideout | 103000003 Thieves' Hideout | 1052001.js | Dark Lord, Thief job instructor: first job at level 10; 1051 holders refused. | Level 10 | NOTE: refuses Super Beginner (skill 1051) holders |
| 1052014 | Vending Machine | 193000000 Kerning City Internet Cafe | 193000000 Kerning City Internet Cafe | 1052014.js | Vending Machine (Kerning Internet Cafe): put in erasers or coins earned from quests to redeem tiered prizes; scroll rows were removed from the prize tiers. | Erasers / quest coins | 1.5.2 restored amounts. NOTE: scroll rows removed |
| 1052015 | Billy | 193000000 Kerning City Internet Cafe | 193000000 Kerning City Internet Cafe | 1052015.js | Billy (same cafe): the same kind of eraser prize tiers as the Vending Machine. | Erasers / quest coins | NOTE: near-twin of 1052014 (not byte-identical) |
| 1052114 | Power B. Fore | 103010000 Kerning City Construction Site | 103010000 Kerning City Construction Site | 1052114.js | Power B. Fore (Construction Site) and 1052113 (Thief Training Center). | Level under 200 | NOTE |
| 2042002 | Spiegelmann | 103000000 Kerning City; 980000010 Exit; 980000103 Carnival Field 1 <Victorious> (+11 more, 14 maps) | 103000000 Kerning City; 980000010 Exit (+12 more, 14 maps) | 2042002.js | Spiegelmann (Kerning entry to Monster Carnival and the CPQ result NPC for 14 carnival maps). | Level 30-200 (stock 30-50), party of 1 allowed (stock 2+); solo expedition hard-coded (line 225-227) | EXPLOIT/NOTE: CPQ farm for Maple Coins solo up to level 200 |
| 9201023 | Nana(K) | 14000 (unnamed non-stock map); 103000000 Kerning City; 261000000 Magatia (+2 more, 5 maps) | 103000000 Kerning City; 261000000 Magatia (+1 more, 3 maps) | 9201023.js | Nana(K), Amoria wedding helper (ring box): fixed a typo only. | wedding items | OK |


### Sleepywood, Lith and the Nautilus (105xxxxxx, 120000000)

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 1061014 | Mu Young | 1120 (unnamed non-stock map); 105100100 Bottom of the Temple | 105100100 Bottom of the Temple | 1061014.js | Mu Young (Bottom of the Temple): Balrog expedition recruiter; null guard added on the event instance. | Balrog expedition rules | OK (also stands in non-stock map 1120) |
| 1063000 | a pile of pink flowers | 14000 (unnamed non-stock map); 105040311 The Deep Forest of Patience <Step 2> | 105040311 The Deep Forest of Patience <Step 2> | 1063000.js | Pink flower pile (Deep Forest of Patience step 2): Jump Quest chest; pays exp + mesos instead of a random ore. | free; reward 100,000 mesos + 10,000 x exp rate | EXPLOIT: no cooldown, repeatable (lines 38-41). Same in 1063001 (blue) and 1063002 (white) |
| 1090000 | Kyrin | 120000101 Navigation Room; 912010200 Kyrin's Training Room | 120000101 Navigation Room; 912010200 Kyrin's Training Room | 1090000.js | Kyrin: Pirate first job at level 10 and her test; no longer teaches Super Transformation. | Level 10 | 1.5.2 |


### Orbis, El Nath, Aquarium, Magatia

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 2010000 | Staff Sergeant Charlie | 3200 (unnamed non-stock map); 200000000 Orbis | 200000000 Orbis | 2010000.js | Staff Sergeant Charlie (Orbis): trade 100 of a requested item for prizes from a tier table; scroll rows removed from the tables. | 100 of the requested item per trade | NOTE: stray `];` leftovers are harmless |
| 2010007 | Heracle | 10010 (unnamed non-stock map); 200000301 Guild Headquarters <Hall of Fame> | 200000301 Guild Headquarters <Hall of Fame> | 2010007.js | Heracle (Guild HQ): create/disband/enlarge a guild, plus new option 3 showing the guild ranking. | Stock guild fees | OK (rc6) |
| 2042000 | Spiegelmann | 1500 (unnamed non-stock map); 1501 (unnamed non-stock map); 200000000 Orbis (+2 more, 5 maps) | 200000000 Orbis; 220000000 Ludibrium (+1 more, 3 maps) | 2042000.js | Spiegelmann (Orbis): enter Monster Carnival; also hosts the disabled ore-refiner (option 4). | Level 30-200 (stock 30-50), party of 1 | EXPLOIT/NOTE as 2042002 |
| 2020000 | Vogen | 211000100 El Nath Market | 211000100 El Nath Market | 2020000.js | Vogen (El Nath Market): ore refining retired; only Create Materials / Create Arrows remain. | Materials + mesos | BROKEN (text): points to Nimble in the Free Market |
| 2020009 | Robeira | 211000001 Chief's Residence | 211000001 Chief's Residence | 2020009.js | Robeira (Chief's Residence): 2nd-job text, lines reworded for the skill set. | job test | 1.5.2 |
| 2020013 | Pedro | 211000001 Chief's Residence | 211000001 Chief's Residence | 2020013.js | Pedro: Marauder advancement text. | job test | 1.5.2 |
| 2030013 | Adobis | 211042400 Entrance to Zakum Altar | 211042400 Entrance to Zakum Altar | 2030013.js | Adobis (Zakum Altar entrance): null guard added to the late-join check. | normal | OK |
| 2030010 | Amon | 280020000 Breath of Lava <Level 1>; 280020001 Breath of Lava <Level 2>; 280030001 (unnamed non-stock map) | 280020000 Breath of Lava <Level 1>; 280020001 Breath of Lava <Level 2> (+1 more, 3 maps) | 2030010.js | Amon: exit NPC of Adobis's Mission and the Zakum altar; now copes with no event instance. | free | OK |
| 2032003 | Lira | 280020001 Breath of Lava <Level 2> | 280020001 Breath of Lava <Level 2> | 2032003.js | Lira (Breath of Lava Level 2): jump-quest end prize now exp + mesos. | free; 100,000 mesos + 10,000 x exp rate | EXPLOIT (JQ faucet), line 53 |


### Ludibrium

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 2040016 | Pi | 220000300 Ludibrium Village | 220000300 Ludibrium Village | 2040016.js | Pi (Ludibrium Village): ore refining retired; only "Create materials / arrows" remains. | materials + mesos | BROKEN (text): points to Nimble in the Free Market |
| 2041024 | Tombstone | 220080000 Deep Inside the Clocktower | 220080000 Deep Inside the Clocktower | 2041024.js | Tombstone (Deep Inside the Clocktower): Papulatus expedition recruiter (Cosmic's file is a different, much shorter script). | Papulatus min/max level | 1.5.2 late-join fix |
| 2041025 | Machine Apparatus | 220080001 Origin of Clocktower | 220080001 Origin of Clocktower | 2041025.js | Machine Apparatus (Origin of Clocktower): exit NPC that reuses Plunk's leave/reward logic (Cosmic: a plain warp to 220080000). | free | 1.5.2 |
| 2043000 | Papulatus | 922020300 Origin of the Clock Tower | 922020300 Origin of the Clock Tower | 2043000.js | Papulatus (Origin of the Clock Tower): Buccaneer Time Leap quest 6363 progress is set on talk. | free | 1.5.2 |
| 2042001 | Spiegelmann | 980000100 Carnival Field 1 <Lobby>; 980000200 Carnival Field 2 <Lobby>; 980000300 Carnival Field 3 <Lobby> (+3 more, 6 maps) | 980000100 Carnival Field 1 <Lobby>; 980000200 Carnival Field 2 <Lobby> (+4 more, 6 maps) | 2042001.js | Spiegelmann (Carnival lobbies): same CPQ changes. | Level 30-200, party of 1 | see 2042002 |
| 2042005 | Spiegelmann | 980030000 Spiegelmann's Office | 980030000 Spiegelmann's Office | 2042005.js | Spiegelmann (2nd Monster Carnival office): CPQ 2 level cap 70 raised to 200 and party minimum 2 lowered to 1. | Level 30-200 | EXPLOIT/NOTE |


### Leafre and the dragon bosses

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 2081100 | Harmonia | 12002 (unnamed non-stock map); 240010501 Forest of the Priest | 240010501 Forest of the Priest | 2081100.js | Harmonia, Gritto 2081200, Hellin 2081400, Samuel 2081500 (Forest of the Priest): 4th-job tests; teachSkill lines for skills MapleRoot removed (Big Bang, Ninja Ambush, Assassinate, Barrage, Super Transformation, Demolition, Snatch) deleted. | Level 120 + test | 1.5.2 |
| 2082014 | Asia | 17052 (unnamed non-stock map); 240070600 <Year 2503> Air Battleship Bow | 240070600 <Year 2503> Air Battleship Bow | 2082014.js | Asia (Neo City air battleship): opens a shop when USE_ENABLE_CUSTOM_NPC_SCRIPT is on, else the stock quest talk. | shop prices | NOTE: the shop path is off on TEST/LIVE |
| 2083004 | Mark of the Squad | 240050400 Entrance to Horntail's Cave | 240050400 Entrance to Horntail's Cave | 2083004.js | Mark of the Squad (Entrance to Horntail's Cave): null-guard on the event instance. | expedition rules | OK |
| 2083002 | Crystal of Roots | 240050100 Room of Maze; 240050101 1st Room of Maze; 240050102 2nd Room of Maze (+11 more, 14 maps) | 240050100 Room of Maze; 240050101 1st Room of Maze (+12 more, 14 maps) | 2083002.js | Crystal of Roots (13 stock maps, Horntail's Cave one removed): leave the Horntail area. | free | OK |
| 2141001 | Forgotten Temple Keeper | 270050000 Forgotten Twilight | 270050000 Forgotten Twilight | 2141001.js | Forgotten Temple Keeper (Forgotten Twilight): null guard. | normal | OK |
| 2141002 | Forgotten Temple Keeper | 270050100 Twilight of Gods; 270050200 Forgotten Twilight | 270050100 Twilight of Gods; 270050200 Forgotten Twilight | 2141002.js | Forgotten Temple Keeper (Twilight of Gods / Forgotten Twilight): Pink Bean exit and reward. | Pink Bean cleared | EXPLOIT/BROKEN (low): line 29 `eim.isEventCleared()` throws when there is no event instance; lines 33-37 add a flat reward (Pink Bean, 2022282 x2, 2022179 x6, 2000005 x1000) outside the inventory-room check |
| 3004006 | Plunk | 240060200 Horntail's Cave; 280030000 Zakum's Altar; 401060100 Tyrant's Throne (+2 more, 5 maps) | none (not placed in Cosmic) | 3004006.js | Plunk ("Tana"): boss-room exit and reward hand-out. After a cleared run, every player in the instance receives that boss's items (Zakum: 4032133 + 100 Power Elixirs; Horntail: 4001094, 2022179, 200 elixirs; Von Leon: 4001693 + a roll; Cygnus: 4000659, 500 Spell Traces, 500 elixirs, ...). Shows damage dealt. | Boss cleared; flag `rewarded` stops a second hand-out | 1.5.2 (always lets players out). NOTE: rewards are large and unaudited vs the economy |


### Ellin Forest (300000000 area)

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 2131003 | Loha | 300000000 Altaire Camp | 300000000 Altaire Camp | 2131003.js | Loha, Sion 2131005, Duru 2131006, Tess 2131007: trade 100 forest monster drops (4000437 / 4000436 / 4000440 / 4000438) for 1 Maple Leaf each (stock paid Perfect Pitch). | 100 drops per leaf | EXPLOIT/NOTE: converts common drops into the server's premium currency (Rooty/Donor Shop). Max 300 leaves per talk; also stands in 5 non-stock maps (1602-1607) |


### Asia and the exotic towns (Mu Lung, Zipangu, Ariant, Singapore, Malaysia, NLC, Amoria)

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 2091005 | So Gong | 925020001 Mu Lung Dojo Hall; 925020100 Mu Lung Dojo 1st Floor (+38 more, 40 maps) | 925020001 Mu Lung Dojo Hall; 925020100 Mu Lung Dojo 1st Floor (+38 more, 40 maps) | 2091005.js | So Gong (Mu Lung Dojo): exchange dojo points for belts; only the two lowest belts remain and they need rebirths, not levels. | 100 / 250 points (or 10 / 90 fast mode); level 25 entry gate restored | 1.5.2 (Dojo 2091005) |
| 2100001 | Muhamad | 260000200 The Town of Ariant | 260000200 The Town of Ariant | 2100001.js | Muhamad (Ariant): ore refining retired: just says "go to Nimble". | n/a | BROKEN (text): Nimble is in the hub |
| 9120201 | Konpei | 801040004 Armory | 801040004 Armory | 9120201.js | Konpei (Armory) and 9120202 (Nightmarish Last Days exit): Scarlion/Targa boss helper; null guards added. | expedition rules | OK |
| 9201000 | Moony | 680000000 Amoria | 680000000 Amoria | 9201000.js | Moony (Amoria): craft a ring box and propose; the male-only block is removed. | Cosmic costs | 1.5.0 ticket 58 |
| 9201044 | Amos the Strong | 670010200 Stage 1 - Magik Mirror; 670010300 Stage 2 - Heart Strings (+3 more, 5 maps) | 670010200 Stage 1 - Magik Mirror; 670010300 Stage 2 - Heart Strings (+3 more, 5 maps) | 9201044.js | Amos the Strong (Amorian Challenge stages 1-3): party-size aware combos, bots ignored. | PQ rules | 1.5.2 Amoria PQ for 2-6 players |
| 9201045 | Amos the Strong | 670010500 Stage 4 - Last Man Standing; 670010600 Stage 5 - Fluttering Hearts (+2 more, 4 maps) | 670010500 Stage 4 - Last Man Standing; 670010600 Stage 5 - Fluttering Hearts (+2 more, 4 maps) | 9201045.js | Amos the Strong (stages 4-7): item requirement scales with party size (50 x players / 6). | trial items | 1.5.2 |
| 9201047 | The Glimmer Man | 670010100 Entrance of Amorian Challenge; 670010200 Stage 1 - Magik Mirror; 670011000 Exit | 670010100 Entrance of Amorian Challenge; 670010200 Stage 1 - Magik Mirror (+1 more, 3 maps) | 9201047.js | The Glimmer Man (Amorian Challenge): text edits. | PQ rules | OK |
| 9201097 | Joko | 8000 (unnamed non-stock map); 8100 (unnamed non-stock map); 610010004 Dead Man's Gorge | 610010004 Dead Man's Gorge | 9201097.js | Joko (Dead Man's Gorge, Crimsonwood exchange quest): trade quest items for a prize; the Att 60% scroll rows were removed from the tables. | Quest items | NOTE |
| 9201101 | T-1337 | 600000000 New Leaf City - Town Center | 600000000 New Leaf City - Town Center | 9201101.js | T-1337 (NLC town center): shop only when USE_ENABLE_CUSTOM_NPC_SCRIPT is on; otherwise silent. | shop prices | BROKEN on TEST/LIVE (flag off; line 8): clicking does nothing |
| 9201134 | Aldol | 551030200 Spooky World | 551030200 Spooky World | 9201134.js | Aldol (Spooky World): exit + damage-dealt readout, boss log credit. | free | OK |
| 9270047 | Aldol | 13300 (unnamed non-stock map); 551030100 Entrance to the Spooky World | 551030100 Entrance to the Spooky World | 9270047.js | Aldol (Entrance to Spooky World): null guard. | free | OK |


### Maple Island

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 2007 | Maple Administrator | 10000 Maple Tree Hill | 10000 Maple Tree Hill | 2007.js | Maple Administrator: skip the tutorial, now to the hub 993910000 instead of Lith Harbor. | free | 1.5.2 (hub) |


## 6. Boss-expedition and Grandis/Arcane NPCs MapleRoot added (scripts missing from Cosmic)

All use the stock Cosmic expedition-recruiter template (list members, start fight, stop expedition) with a quest gate. Level windows come from `ExpeditionType`
(solo parties allowed). 1.5.2 made every wake/recruiter script refuse non-leaders and fixed Lotus (b48887f23, ad0c3ff57, cb41e2290).

| ID | In-game name | Stands (our Map.wz / plife) | Stock home (Cosmic) | Script | What it does for a player | Cost / requirement | Flag |
|---|---|---|---|---|---|---|---|
| 2491006 | Lotus | 67 (unnamed non-stock map); 1064 (unnamed non-stock map); 1065 (unnamed non-stock map) (+2 more, 5 maps) | none (not placed in Cosmic) | 2491006.js | Lotus: recruits the Hard Lotus expedition. | Level 30-190 | 1.5.2 fix. Only stands in non-stock maps 67, 1064, 1065 (+2) |
| 3000131 | Magnus | 401053002 Tyrant's Castle Sitting Room Entry | none (not placed in Cosmic) | 3000131.js | Magnus recruiter (Tyrant's Castle Sitting Room Entry). | Level 30-200; quest 31166 "New Beginnings" done | OK |
| 3003133 | Kao | 450001105 Lakeshore | none (not placed in Cosmic) | 3003133.js | Kao: Krexel recruiter. | Level 30-120, no quest gate | NOTE: no quest gate, unlike the Arcane trio |
| 9010100 | Mr. Flopsy the Dreamweaver | 450003600 Nightmare Clocktower Peak; 450004000 Nightmare Clocktower | none (not placed in Cosmic) | 9010100.js | Mr. Flopsy the Dreamweaver: Lucid (Normal) recruiter. | Quest 31161 done, level 30-200 | OK |
| 3003560 | Gate | 450007240 Radiant Throne | none (not placed in Cosmic) | 3003560.js | Gate: Will (Normal) recruiter. | Quest 31162 done | OK |
| 3003806 | Light in the Dark | 450012600 Final Horizon | none (not placed in Cosmic) | 3003806.js | Light in the Dark: Darknell (Normal) recruiter. | Quest 31163 done | OK |
| 3003773 | Orchid | 450011660 Source of Suffering | none (not placed in Cosmic) | 3003773.js | Orchid: Verus Hilla (Normal) recruiter. | Quest 31164 done | OK |
| 9906602 | Lucid's Dream | 450003600 Nightmare Clocktower Peak | none (not placed in Cosmic) | 9906602.js | Lucid's Dream: Lucid (Hard) recruiter. | Quest 31165 done | OK |
| 9906603 | Will's Dream | 450007240 Radiant Throne | none (not placed in Cosmic) | 9906603.js | Will's Dream: Will (Hard) recruiter. | Quest 31165 done | OK |
| 9906604 | Darknell's Dream | 450012600 Final Horizon | none (not placed in Cosmic) | 9906604.js | Darknell's Dream: Darknell (Hard) recruiter. | Quest 31165 done | OK |
| 9906605 | Hilla's Dream | 450011660 Source of Suffering | none (not placed in Cosmic) | 9906605.js | Hilla's Dream: Verus (Hard) recruiter. | Quest 31165 done | OK |
| 3003554 | ??? | 450007170 Slumbering Deep; 450010100 Altar of Desire | none (not placed in Cosmic) | 3003554.js | "???": wake-up NPC; the leader says yes and Verus (8880415) spawns. | Expedition leader only | OK (1.5.2 leader check) |
| 3003559 | Will | 450007510 Fading Light | none (not placed in Cosmic) | 3003559.js | Will (wake): leader says yes, Will spider (8880344) spawns. | Expedition leader only | OK |
| 3003921 | Darknell | 450012210 Final Horizon | none (not placed in Cosmic) | 3003921.js | Darknell (wake): leader says yes, Darknell (8645009) spawns. | Expedition leader only | OK |
| 2161000 | Von Leon | not placed anywhere | none (not placed in Cosmic) | 2161000.js | Von Leon recruiter ("Audience Room"). | Quest 3170/3171 started or done (hidden chain) | DEAD: not placed anywhere; quest chain hidden by HiddenQuests (QU-002) |
| 2161008 | Von Leon | not placed anywhere | none (not placed in Cosmic) | 2161008.js | Von Leon (wake): spawns Von Leon (8840000). | Expedition leader only | DEAD: not placed |
| 2161001 | Ifia | 211061001 Roof of the Fifth Tower | none (not placed in Cosmic) | 2161001.js | Ifia: warps to Von Leon's tower 211070000 while quests 3173-3176 are started. | quest | DEAD in play: quests hidden; stands in 211061001 |
| 3002122 | ??? | not placed anywhere | none (not placed in Cosmic) | 3002122.js | Lucid's robe (wake): spawns Lucid + two Lucid mobs. | Expedition leader only | DEAD: not placed |
| 3003903 | Sleeping Cygnus | not placed anywhere | none (not placed in Cosmic) | 3003903.js | Sleeping Cygnus (wake): spawns Cygnus (8850011). | Expedition leader only | DEAD: not placed |
| 3003970 | Cygnus | not placed anywhere | none (not placed in Cosmic) | 3003970.js | Cygnus ("The True Queen"): Cygnus recruiter. | Level 30-120 | DEAD: not placed |
| 9111058 | Tengu | not placed anywhere | none (not placed in Cosmic) | 9111058.js | Tengu: Castellan recruiter. | Level 30-120 | DEAD: not placed |
| 9000174 | Murgoth | not placed anywhere | none (not placed in Cosmic) | 9000174.js | Murgoth: sells a Von Leon Audience Room pass (2030021) for 1,000,000 mesos after quest 3170. | 1,000,000 mesos + quest 3170 done | DEAD: not placed; quest hidden |
| 9906600 | Spawn Rate Totem | not placed anywhere | none (not placed in Cosmic) | 9906600.js | Spawn Rate Totem: script body is `// DEADBEEF`, an empty stub. | n/a | DEAD/BROKEN: no behaviour, not placed |
| 9977777 | ? | not placed anywhere | none (not placed in Cosmic) | 9977777.js | Server feature list ("?" in String.wz): scrolls through ~100 feature lines and plays an anthem. | free | DEAD: not placed; only NpcId.java mentions it |
| 9270043 | Gachapon | not placed anywhere | none (not placed in Cosmic) | gachapon.js logic | Singapore CBD gachapon (one 5220000 ticket = one roll of the Singapore pool). | Gachapon Ticket | DEAD for walking players: not placed (plife machines cover six towns; Singapore has none); reachable only through Remote Gachapon Tickets |
| 2143000 | Informant | 271030200 Knight District 2 | none (not placed in Cosmic) | 2143000.js | Informant (Knight District 2): free warp to five Hallowed Ground maps. | free | EXPLOIT (low): no gate; the Hallowed Grounds normally need Cygnus Garden progress |
| 3002015 | Fox God | 410000002 Fox Tree | none (not placed in Cosmic) | 3002015.js | Fox God (Fox Tree, Fox Point Village): pay to go up the tree (410000100). | 1,000 mesos (10% for Beginners) | OK |
| 3004039 | Adventurer's Bag | 402000222 Sketchy Back-Alley 1 | none (not placed in Cosmic) | 3004039.js | Adventurer's Bag ("infinity storage") for Use, Etc, Set-up and auto-stored ores; also opened by @backpack. | free; 32,000 per move | NOTE: stores only item id and count, so item flags (expiry, owner tag) are lost on withdrawal |


## 7. Helper and command scripts added or changed (no NPC placement)

These are opened from a command, another NPC or Java code, not by clicking a map NPC. `1300009` is borrowed as the host id for the command windows.

| Script | Opened by | What it does | Cost | Flag |
|---|---|---|---|---|
| botmenu.js | BotMenus.java | Menu for talking to or commanding bot players. | free | OK |
| buyback.js | @bb / @buyback | Buy back an item you sold to an NPC (last 5 sales, 1 minute cooldown). | NX (USE_BUYBACK_WITH_MESOS false) | NOTE: USE_BUYBACK_SYSTEM is false on TEST |
| cashshop_partner_ring.js | CashShopPartnerRings.java | Choose the partner for a cash-shop couple ring. | ring price | OK |
| cerezeth_rebirth_keybind.js | nothing opens it | Older rebirth key-binder; superseded by 9977778. | free | DEAD: no caller |
| craft.js | @craft (commented out) | Craft items from materials. | materials | DEAD: the command is commented out (CommandsExecutor.java:232) |
| damageskin.js / damageskinoption.js / damageskinstorage.js | @damageskin, Rooty | Pick, store and swap damage skins. | free | NOTE: reached from the Rooty menu only through unlisted choice 3 |
| exchange.js | @exchange | Trade 2 ores/crystals for 1 of another ore. | 2 for 1 | OK (room check before taking) |
| expeditionLeaderboard.js | @elb | Show expedition clear leaderboards. | free | OK |
| job_advancer.js | nothing opens it | Older job advancer (levels 70/120 only). | free | DEAD: no caller |
| make.js | @make (GM3) | GM item maker. | GM only | OK |
| masterybooks.js | Rooty menu | Mastery-book shop (dead-skill books removed in 1.5.2). | Maple Leaf | 1.5.2 |
| memory.js | @memory | Memory card game window. | free | OK |
| minesweeper.js | @minesweeper | Minesweeper window. | free | OK |
| mobdrop.js / search.js / sense.js / spyOnPlayers.js / ShopEditor.js | GM commands (GM2-3) | Look up drops, search ids, QA sense, spy on players, edit shops. | GM only | OK; ShopEditor starts with a gm>=3 check |
| qolitems.js | Rooty menu | Sells Cosmetic/QoL cash items for Maple Leaf or 2m/10m mesos. | 100 leaves each, or 10,000,000 / 2,000,000 mesos | OK |
| quicksell.js | @qs | Sell an inventory slot range (same job as the Donation Box). | free | DUP of Donation Box 9000041 |
| reloadcanvas.js | Rooty choice 5 (not shown) | Reload the client canvas after UI edits. | free | NOTE: unlisted menu entry |
| retrojqrewards.js | Rooty menu | Trade 100 Donkey Kong / Yoshi JQ coins for a 180-day pet. | 100 coins (4036550 / 4036553) | OK |
| rooty.js | Rooty menu (Rooty and Donor Shop entries) | The one Rooty/Donor shop catalog (CATALOG at the top). | Root Coin, Billion Coin or Maple Leaf per section | 1.5.2 listed items only; 1.6.0 donorshop.js merged in |
| rebirth.js | NPCTalkHandler:80 | See Wyatt the Reborn. | see hub | see hub |
| saloon.js / STYLIST.js | 9900000 menu | See Cotton Stylist. | see hub | see hub |
| scroll_generator.js | mapleTV.js (flag) | Scroll generator: spend points to generate scrolls. | varies | BROKEN on TEST/LIVE: mapleTV.js:28 only opens it when USE_ENABLE_CUSTOM_NPC_SCRIPT is on |
| serverinfo.js | Rooty menu | Short server information text. | free | OK |
| slumsmenu.js | MenuWindow.java | SlumsMS in-game menu. | free | OK |
| worldEventNpc.js | NPCTalkHandler:71 | Talk to a scripted world-event NPC (ticket 21). | free | OK |
| gachapon.js / gachaponInfo.js / gachaponRemote.js / gachaponold.js | NPCTalkHandler:76-77, Remote ticket | Gachapon machines, loot info, remote ticket roll; pools now come from gachapon-pools.yaml. | Gachapon Ticket 5220000 / Remote 5451000 | 1.5.0 rc3 (double-ticket bug fixed). gachaponold.js is unused |
| mapleTV.js | NPCTalkHandler:78 | Maple TV: opens the scroll generator when the custom flag is on, else nothing. | n/a | BROKEN: the TV NPCs are not placed and the flag is off |
| 1022101_old.js | nothing opens it | Old scroll exchange: scroll list cut to two copies of 3010001. | n/a | DEAD |
| 9977779.old.js | nothing opens it | Backup of Nimble. | n/a | DEAD (cleanup) |
| lib/, interactions/ | loaded by Nimble, ELB | Shared helpers. | n/a | OK |


## 8. NPCs in maps Cosmic does not have

1524 maps, 4,755 placements, 2,692 NPC ids. The block is later-version content (Grandis, Arcane River, Esfera, Limina, Tenebris, Cernium, Edelstein, Fox Point, Black Market, etc.) plus
53 maps with very small ids (67..8110, e.g. Cernium 1500) that hold copies of stock towns' NPCs. Of the 2,692 ids, only
107 have a script file and 24 more have only a shop row; **2,561 have neither**, so clicking logs
"NPC ... is not coded" and shows nothing (NPCTalkHandler.java:84-86). Most of those are story or quest NPCs whose quests are hidden or unfinished (QU-002, ticket 81/82); none were audited quest by quest.

| Region (map id / 10M) | Maps | Placements | Distinct ids | With script or shop | Inert |
|---|---|---|---|---|---|
| under 10M (small ids; 53 maps are below 10,000) | 174 | 343 | 197 | 91 | 106 |
| 200M (Orbis area) | 13 | 16 | 7 | 0 | 7 |
| 210M (El Nath) | 15 | 18 | 13 | 2 | 11 |
| 240M (Leafre) | 1 | 1 | 1 | 1 | 0 |
| 270M (Temple of Time) | 27 | 54 | 44 | 1 | 43 |
| 280M (Zakum) | 1 | 1 | 1 | 1 | 0 |
| 300M (Ellin Forest) | 101 | 242 | 104 | 3 | 101 |
| 310M (Edelstein) | 26 | 147 | 126 | 1 | 125 |
| 320M | 121 | 601 | 268 | 4 | 264 |
| 330M (Arcane precursor) | 53 | 240 | 220 | 0 | 220 |
| 350M (Mirror Worlds) | 153 | 376 | 167 | 3 | 164 |
| 400M (Grandis) | 85 | 297 | 262 | 5 | 257 |
| 410M (Fox Point) | 74 | 260 | 245 | 2 | 243 |
| 450M (Arcane River) | 155 | 476 | 366 | 14 | 352 |
| 700M (China) | 98 | 270 | 128 | 1 | 127 |
| 740M | 22 | 55 | 43 | 0 | 43 |
| 780M | 4 | 6 | 3 | 0 | 3 |
| 800M (Zipangu) | 2 | 14 | 14 | 1 | 13 |
| 810M | 18 | 58 | 37 | 0 | 37 |
| 820M | 1 | 20 | 20 | 0 | 20 |
| 860M (event/instance maps) | 379 | 1249 | 532 | 2 | 530 |
| 990M (Base of Roots) | 1 | 11 | 11 | 10 | 1 |

Notes:
- The scripted ids in these maps are almost all stock NPCs duplicated into the small-id maps (Rain, Natasha, Phil, Cloy, Sam, Mar the Fairy, Eurek the Alchemist x25 maps, Dimensional Mirror x22, ...) with their normal Cosmic script. They are listed in the sections above only when MapleRoot changed the script.
- Inert NPCs in towns that matter for 1.5.x (Cernium 1500-1620, Edelstein 310M, Arcane River 450M) were not individually reviewed; ticket 81/82 owns that sweep.


## 9. Scripts with no placement, and placements with no script

**Script exists, NPC is placed nowhere (our Map.wz and plife)**: 2161000, 2161008, 3002122, 3003903, 3003970, 9000174, 9111058, 9906600, 9977777, 9270043 (all in section 6, flagged DEAD).
Stock-map placements are identical to Cosmic's apart from the 17 changes in section 3 (2 added, 15 dropped), so NPCs standing in shared maps that lack a script are the same quest-only/decorative NPCs Cosmic has.
**Placed, no script, no shop, not handled in Java**: every hub and plife NPC is covered (Gachapon by id range in NPCTalkHandler, Hanako by MesoSinks, Wyatt by the rebirth hook).
The one stand-alone case is Dalair 9000066 (quest hand-in only). The 2,561 inert ids in non-stock maps are in section 8.


## 10. Broken and exploitable: top 10 with file:line

| # | Kind | Where | Finding |
|---|---|---|---|
| 1 | BROKEN | `scripts/npc/9000017.js:55`, `9000036.js:43`, `9000040.js:52`, `mapleTV.js:28`, `2082014.js:44`, `9201101.js:8`, `2042000.js:35` (and 2042001/2042002) | `USE_ENABLE_CUSTOM_NPC_SCRIPT` is false in TEST and LIVE config.yaml, so Coco, Agent E, Dalair (merge), the scroll generator, Asia, T-1337 and Spiegelmann's ore refining do nothing. Dalair/Coco/Agent E stand in 15-20 towns each |
| 2 | EXPLOIT | `1063000.js:38-41`, `1063001.js:38`, `1063002.js:38`, `2032003.js:53` | Jump Quest chests give 100,000 mesos + 10,000 x exp rate per completion (config JUMPQUEST_*); NPC 9010001 warps in for free; no cooldown or daily cap |
| 3 | BROKEN | `1032100.js:59` | `getLevel() >= 3000` gates all of Arwen's crafting, so Moon Rock, Star Rock and Black Feather cannot be made anywhere (Nimble does not offer them) |
| 4 | BROKEN | `1022003.js:34`, `2020000.js:35`, `2040016.js:36`, `2100001.js:40`, `1032100.js:62` | Retired refiners send players to "Nimble in the Free Market"; Nimble 9977779 stands only in the hub 993910000 |
| 5 | EXPLOIT | `rebirth.js:69` and `:119` | The rebirth key item is checked but never consumed; if 4033446/4033442/4033450 are tradable they can be passed along |
| 6 | DEAD | `2161000.js`, `2161008.js`, `3002122.js`, `3003903.js`, `3003970.js`, `9111058.js`, `9000174.js`, `9906600.js`, `9270043.js` | Boss recruiters/wake NPCs with scripts but no placement: Von Leon, Lucid, Cygnus, Castellan cannot be started through an NPC; Singapore has no gachapon |
| 7 | EXPLOIT | `2042000.js:19-20`, `2042001.js`, `2042002.js:19-20,225-227`, `2042005.js` | CPQ minimum party 1 and maximum level 200 (stock 2+ and 30-50); 2042002 hard-codes solo instead of reading `USE_ENABLE_SOLO_EXPEDITIONS` |
| 8 | BROKEN/EXPLOIT | `2141002.js:29`, `:33-37` | Pink Bean exit throws with a null instance; the extra reward block (1000 Power Elixirs, 2022282 x2, 2022179 x6, 4021010) sits outside the room check |
| 9 | EXPLOIT (low) | `STYLIST.js:277-326` | Hidden catalogs (selections 40, 50, 80, 91, 92) change hair/face with no Perfect Pitch fee when the selection is forged |
| 10 | NOTE | `9010004.js:4,32-38`; `donorshop.js` vs `rooty.js` | Menu has no entries for choices 3 and 5; two near-identical 900-1,600 line shops to keep in sync |

Others worth knowing: `2131003/5/6/7.js` convert 100 common drops into 1 Maple Leaf (premium currency); `2143000.js` free warp to Hallowed Ground; `2084000.js:30` charges Beginners 100m mesos
(reborn players return to Beginner, so this is the intended cost for previous-line skills); `3004039.js` loses item flags; NPCs Maple TV x7 removed but `mapleTV.js` retained.

## 11. 1.5.2 changes already made (not re-flagged)

From `git log --since=2026-10-24` on `152-integration`:

| NPC | Change | Commit |
|---|---|---|
| Rebirth helper 9977778 | Get 100 SP once per rebirth, counted server-side | e92b4606d |
| Job Master 9010003 | Advancement fees (1st free, 2nd 800k, 3rd 15m, 4th 100m), 4th-job masteries like Leafre, no regex in script | 69b27baa0, f57944b2a, fb4f5fd82, 6c9a0d626, de54be714 |
| Gold Compass 2084000 | Sells only the player's own job line | 6ae8f4b11 |
| Cotton Stylist 9900000 | Server-side hair/face/skin validation; hub stylist file name fix | dc6ea0fb6, 053f0f78d |
| Cotton Warper 9010002 | Henesys teleporter, free warps to boss/Grandis/Arcane maps | 7268863aa (merge 152-warp-menu) |
| Dalair 9000066 | Placed in Henesys (migration 026) | c24db1239 |
| Mu Lung Dojo 2091005 | Level 25 entry gate restored | 36bbec040 |
| Expedition recruiters/wake NPCs | Leader-only wake, late-join and null fixes, Lotus 2491006 recruits Hard Lotus | cb41e2290, ad0c3ff57, b48887f23, b323a53ce |
| Plunk/Tana 3004006 | Always lets players out of boss rooms | c36e59b49 |
| Rooty and Donor Shop | Sell only listed items | 4e8242ab5 |
| Job instructors, Leafre NPCs, Kyrin | Dialogue and teaching of removed skills dropped | 61bf3c976 |
| Training-ground NPCs (Power B. Fore) | State the real level limit | aec415f82 |
| Amoria NPCs (Amos, Moony, Glimmer) | PQ for 2-6, any-gender ring box, trial item scaling | 53bea0bc7, 35723c08f, f82c3eeb5, 18a400edb |
| Papulatus NPCs 2041024/2043000 | Late-join, Time Leap quest 6363 | 155149ccf, f1d9d320d |
| Kerning Vending Machine, Billy, Boss Rush | Real reward amounts restored | 8273171bb, 929252612 |
| Mr. Do, Spiegelmann scroll | Scrolls make again, 40 Maple Coins | 3185401f8, bbb1ac5bf |
| Maple Administrator 2007 | Sends players to the hub | 009fd7cde |
| Trade NPCs | Check inventory room before taking payment | b7573a083 |
| Puno, Puri Puri, Thorr | Shops restocked | 9a91ea0c7 |
| Scroll generator | Scroll tables restored from HeavenMS | a1d5fc8ec |
| Gachapon machines | Six town machines placed by plife; remote ticket and Singapore pool fixed | 1.5.0 rc3 |

- **809c5a3b0** (owner, after the catalogue): Jump Quest chests 1063000-1063002 and 2032003 pay the custom meso/EXP reward once per character per day (quest record 32002), then their stock prize; the Cotton Stylist accepts only the menu's catalogs (the hidden 40/50/80/91/92 are refused).

## 12. Owner decisions (ticket 89)

Decisions 2 (Jump Quest chests) and the stylist's hidden catalogs were settled on 2026-10-25 (fixed in 1.5.2, above).

1. **USE_ENABLE_CUSTOM_NPC_SCRIPT**: turn it on (restores Coco, Agent E, Dalair merge, Asia, T-1337, ore refining, scroll generator in ~20 towns) or accept that these NPCs only greet. It restores MapleRoot content the owner may have switched off on purpose; the ore refining option overlaps Nimble.
2. **Jump Quest chests**: keep 100k mesos + 10k exp x rate per run with no cooldown, or add a cap or lower the number (economy; your call).
3. **Nimble / retired refiners**: move Nimble to the Free Market, or change the five retired-refiner texts to say "the Base of Roots". Decide whether Moon Rock, Star Rock and Black Feather should be craftable again (Arwen level gate).
4. **Rebirth key items**: consume them at rebirth, or confirm they are untradable.
5. **Boss recruiters with no placement** (Von Leon, Lucid, Cygnus, Castellan): place them (Map.wz or plife) or delete the scripts; ties into tickets 81/82 and QU-002.
6. **Maple TVs**: restore the seven town TVs (needs plife rows) or retire `mapleTV.js`; and whether Singapore gets a gachapon machine.
7. **CPQ**: keep solo up to level 200, or restore Cosmic's 2-player, 30-50 limits.
8. **Duplicate shops**: merge `donorshop.js` into `rooty.js`; and decide what to do with the stale scripts (`9977779.old.js`, `1022101_old.js`, `job_advancer.js`, `cerezeth_rebirth_keybind.js`, `gachaponold.js`).
9. **Ellin Forest drop-to-Maple Leaf trades** and the Hallowed Ground free warp: economy/progression policy.
10. **Inert NPCs in the later-version maps** (about 2,500 ids): a quest-by-quest pass is still pending (ticket 82).
