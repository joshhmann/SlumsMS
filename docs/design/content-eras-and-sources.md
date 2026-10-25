# MapleRoot content: which era it comes from, and where to find the real requirements

Research snapshot of 2026-10-25, for ticket 89's "put progression back" track (planned for 1.7.0). Read-only study: no scripts or
game data were touched. Companion to `docs/design/mapleroot-npcs.md` (the catalogue) and tickets 81 and 82.

**How to read the confidence marks.**
- `[src]` = the fact was read on the cited page during this research.
- `[mem]` = from general knowledge of v83/GMS, not re-checked. Treat as a lead, confirm before building on it.
- `[stock]` = checked against stock Cosmic data in `/root/work/cosmic-triage/Cosmic/wz` (the quest id exists there, so it is v83 or older).
- "approx" = a date only roughly known. "undated" = no source found. Version numbers are given only where a page states them.

Web access during this pass was limited (several wikis returned 403, web.archive.org is blocked from the research tool, MapleLegends
did not load). The Hidden Street / BasilMarket / MapleLegends / MapleRoyals links below are therefore the known homes of those
guides, **not pages that were opened here**. Open them from a browser or via the Wayback Machine.

## 1. Era overview

| Era | What it is | Typical content in our build | Best sources |
|---|---|---|---|
| 1. Stock v83 (GMS ~2009, pre Big Bang) | Everything in Cosmic's WZ and scripts: Victoria, Ossyria, Ludibrium, Leafre, Mu Lung, Amoria, Zakum/Horntail/Pink Bean, Maker (quest 6029), 4th job tests and skill-book quests | Town NPCs, job instructors, CPQ, Dojo, boss expeditions Zakum to Pink Bean, quests 6xxx | Cosmic/HeavenMS scripts in this repo (`scripts/npc`, `scripts/quest`); Hidden Street (old site, via Wayback); BasilMarket (Wayback); MapleLegends library; MapleRoyals forum guides; StrategyWiki MapleStory |
| 2. Big Bang and after (2010-2013) | Big Bang (July 2010, KMS-based date), then "Aftershock" (Lion King's Castle, Von Leon, Ultimate Explorer), Chaos (Cygnus expedition in Hidden Street's v.99 notes), Cygnus Returns/Awakening (Jan 2013 KMS) | Von Leon chain 3163-3182, Cygnus fight, Hallowed Ground/Knight District, Henesys Ruins (undated), Ellin Forest (undated) | MapleStory Fandom/maplestorywiki.net update pages; Hidden Street forum update notes ("v.99 Chaos"); Ayumilove GMS update notes; Orange Mushroom (KMS/GMS patch posts); StrategyWiki (Von Leon page) |
| 3. Modern (2016+) | V update (GMS v.179, 2016-12-15), Arcane River, Lucid, Will, Black Mage update (Verus Hilla, 2018-12-12/13 GMS), Darknell, Grandis zones, Magnus | Arcane River maps (450M), Lachelein, Heliseum/Tyrant's Castle, Esfera, Limina, Tenebris, 4xx maps, Cernium/Edelstein | maplestorywiki.net (boss `/Monster` subpages hold level, ARC, prequest, clear limit); Ayumilove GMS v.xxx update notes (v.179 confirmed reachable); StrategyWiki Arcane River; Orange Mushroom; Grandis Library (grandislibrary.com) for lore/quest text |
| 4. MapleRoot / SlumsMS custom | No official source: rebirth, Wyatt, Key Binder, Rooty and donor/Maple Leaf shops, Nimble crafting, Lab Tech, Cotton Warper menu, Job Master, Cotton Stylist menu, Coco/Agent E/Dalair-merge scripts, boss recruiters Kao/Gate/etc. | Design decision, not a lookup | This repo's ledgers and tickets; the owner |

**Backport servers worth reading (they ported later content onto a v83 base):** MapleRoyals (Neo Tokyo and its quest/boss guides on
the Royals forum), Rulaxing's community v83 repack, HeavenMS/Cosmic forks. `[mem]` for all three; not opened in this pass.

**Date anchors found `[src]`.**
- Big Bang: initial release July 2010 (KMS timeline) - maplestorywiki.net/w/MapleStory:_Big_Bang. Same page: Lion King's Castle and Von Leon are *not* in Big Bang but in the follow-up GMS "Aftershock" (no date given).
- Chaos removed Maker for Professions - maplestorywiki.net/w/Maker. Cygnus Expedition (lv 170+, party 3-18, channel 13) described in Hidden Street's "v.99 Chaos: Age of Heroes" notes (page returned 403 here; seen only in a search snippet).
- Cygnus Returns: Jan 2013 KMS, "Cygnus Awakening" in GMS, first three parts merged - maplestorywiki.net/w/MapleStory:_Cygnus_Returns.
- Shining Star = GMS v.157, 2014-12-03, introduced Star Force - ayumilove.net/maplestory-update-notes-gms-v157/. Spell Trace scrolls arrived with "You & I" (wiki says Dec 2013) - maplestorywiki.net/w/MapleStory:_You_%26_I. Monster Carnival closed with "You & I / Rising Heroes" - maplestorywiki.net/w/Monster_Carnival.
- V: 5th Job = GMS v.179, live 2016-12-15; Vanishing Journey same day; Chu Chu 2016-12-28 - ayumilove.net/maplestory-update-notes-gms-v179/. maplestorywiki.net/w/MapleStory:_V lists the four GMS parts: Limitless, 5th Job, Lucid, Champions (Lucid is part 3).
- Black Mage final chapter in GMS: 2018-12-12/13 (Nexon press release, godisageek.com 2018/12 article). Verus Hilla Hard: v.201 "Black Mage: Labyrinth" (temporary), permanent in v.202 "New Beginnings"; Normal in v.233 "Destiny: Remastered" - maplestorywiki.net/w/Verus_Hilla.
- Will: Hard in "ARK: Monad", Normal in v.199 "Darkness Looms", Easy in v.233 - maplestorywiki.net/w/Will/Monster. Lucid Hard in "Beyond / Override" (Normal in "V: Lucid") - maplestorywiki.net/w/Lucid/Monster.

## 2. Table

Columns: **ID** = NPC id (or quest ids). **Era**: 1 / 2 / 3 / 4 as above; "1+4" = stock NPC or placement whose *function* MapleRoot rewrote.
"Official requirement" is what the real game asked for when a source says so; `-` means the NPC has no official counterpart to gate
against. Guide links are reference pages to start from, not all opened here.

### 2a. Base of Roots hub (993910000)

| NPC / content | ID | Era | Introduced (source) | Official requirement | Best guide links |
|---|---|---|---|---|---|
| Glowing Treasure (Star Force) | 2616 | 4 (concept official, GMS 2014) | Star Force: GMS v.157, 2014-12-03 `[src]` ayumilove v157 | In GMS: mesos + (Spell Traces are the *scroll* currency from 2013); Star Force needs item level/star caps by equip, no NPC quest `[mem]` | ayumilove.net/maplestory-update-notes-gms-v157/; maplestorywiki.net/w/Coordinator%27s_Touch:_Star_Force |
| Wyatt the Reborn (rebirth) | 1300009 | 4 | Custom; loosely echoes the official Reboot/Rebirth idea only | - | Owner ledger NP-009 |
| Gerrard (Storage) | 2060008 | 1 | Stock Aquarium NPC `[stock]` | Normal storage fee | Cosmic `scripts/npc/2060008.js` |
| "4th job skills" (Gold Compass) | 2084000 | 1 (NPC placement) + 4 (function) | Stands in Gold Richie's Treasure Vault, which is in stock Cosmic; Gold Richie itself: undated | Real: level 120+, 4th job advancement test (Harmonia/Gritto/Hellin/Samuel group), then each 4th-job skill comes from a skill-book quest or boss/PQ drop and often needs mastery or a prior skill level `[mem]` | Hidden Street "4th job skill books" pages (Wayback); MapleLegends library "Skill Books" and quests; MapleRoyals forum "4th job skill guide"; StrategyWiki MapleStory job pages |
| Lab Tech (boss attempts) | 3004015 | 4 | - | - | - |
| Cotton Warper (boss warps) | 9010002 | 1 (stock NPC) + 4 (menu) | Destinations are v83 bosses: Papulatus (220080000), Balrog (105100100), Spooky World/Aldol (551030100), Krexel (541020700), Boudoir/Showa (800040401), Zakum entrance (211042400) | Each official boss had its own gate: Papulatus = Ludibrium clocktower route + Clocktower party, Zakum = Adobis's Mission I / Eye of Fire, Balrog = Mu Young recruit + level, etc. `[mem]` | Hidden Street boss pages (Wayback); MapleLegends library boss guides; Cosmic expedition scripts in `scripts/event` |
| Job Master | 9010003 | 4 (stock placeholder NPC) | - | Real: 1st job lv 8-10 (see 2e), 2nd job lv 30 + Dark Crystal / job test, 3rd job lv 70 + test + holy stone, 4th job lv 120 + test; fees already added in 1.5.2 | Hidden Street job pages; StrategyWiki MapleStory "Job advancement"; scripts `1012100.js`, `2020009.js`, `2081100.js`, `2020013.js` |
| Rooty | 9010004 | 4 | - | - | - |
| Cotton Stylist | 9900000 | 4 (stock GM-room NPC) | - | Official stylists use Royal/VIP cash coupons; here it is a menu | Cosmic `STYLIST.js` |
| Key Binder | 9977778 | 4 | - | - | - |
| Nimble | 9977779 | 4 | - | Official analogue: Maker (quest 6029 Meren, lv 45/75/105) plus per-town ore refiners | maplestorywiki.net/w/Maker `[src]`; ticket 82 section 1 |

### 2b. Free Market, plife, services in many towns

| NPC / content | ID | Era | Introduced | Official requirement | Best guide links |
|---|---|---|---|---|---|
| Gachapon machines (6 towns) | 9100101-9100109 | 1 (placements) + 4 (YAML pools) | Stock Cosmic NPCs | Gachapon Ticket (cash item) | Hidden Street Gachapon pages (Wayback); Cosmic `gachapon.js` |
| Hanako (cosmetic merchant) | 9209006 | 1 (Amoria NPC) + 4 (moved) | Stock | - | - |
| Dalair, medal hand-in | 9000066 | 1 | Medal quests exist in v83 `[mem]` | Medal quests, no cost | Cosmic `scripts/quest` medal scripts |
| Maple TV (7 town TVs) | 9250042-5, 9201066, 9270012-3 | 1 | Stock | Cash item: Maple TV Messenger | Cosmic `mapleTV.js` |
| Coco (Chaos Scroll synth) | 9000017 | 4 (stock placeholder) | - | Official Chaos Scroll of Goodness is a Cash Shop / event / Gachapon prize, not crafted `[mem]` | - |
| Agent E (accessory refine) | 9000036 | 4 | - | Official analogue: Maker accessories and town refiners | maplestorywiki.net/w/Maker `[src]` |
| Dalair, equipment merger | 9000040 | 4 | Cosmic's `USE_STARTER_MERGE` | Cosmic's own rule is Maker level 3 + level gate | Cosmic `9000040.js` |
| Donation Box | 9000041 | 4 | - | - | - |
| Jump Quests menu | 9010001 | 4 (menu) over 1 (maps) | Deep Forest of Patience, Breath of Lava are v83 maps | Real: Deep Forest of Patience is the Sleepywood JQ; Breath of Lava is the Zakum prequest area and needs the Zakum entry quest chain (Adobis's Mission I) `[mem]`; JQ rewards were small | Hidden Street JQ pages (Wayback); MapleLegends library "Jump Quests" |
| Agent Kitty | 9000038 | 1 | Stock Exclusive Training Center | Official gate: Exclusive Training Center entry (daily ticket) `[mem]` | Hidden Street "Exclusive Training Center" |

### 2c. Town NPCs with changed scripts (v83 base)

| NPC / content | ID | Era | Introduced | Official requirement | Best guide links |
|---|---|---|---|---|---|
| Job instructors Athena Pierce, Grendel, Dances with Balrog, Dark Lord, Kyrin | 1012100, 1032001, 1022000, 1052001, 1090000 | 1 | Stock | 1st job: Bowman/Thief/Warrior lv 10, Magician lv 8, Pirate lv 10 `[mem]` plus a job-instructor test | Hidden Street job pages; MapleLegends library |
| Power B. Fore (training ground) | 1012119, 1032114, 1022104/5, 1052113/4 | 1 | Stock | Level under 20 (our script: under 200) `[stock]` per catalogue | Hidden Street |
| Arwen the Fairy | 1032100 | 1 | Stock | Moon Rock / Star Rock / Black Feather crafting for 3rd-job quests; materials plus mesos per stock script | Cosmic `1032100.js`; Hidden Street "Black Feather" |
| Mr. Thunder, Vogen, Pi, Muhamad (ore refiners) | 1022003, 2020000, 2040016, 2100001 | 1 | Stock | Materials + mesos; refiners had no level gate | Cosmic scripts |
| Vending Machine, Billy | 1052014, 1052015 | 1 | Stock (Kerning cafe) | Erasers / quest coins from the cafe quest line | Cosmic scripts |
| Spiegelmann, Monster Carnival | 2042000-2042005 | 1 | Original CPQ: levels 30-50 `[src]`; CPQ2 levels 51-70 `[src]` maplestorywiki Monster_Carnival; party min 2 (Cosmic script) | Parties of 2+, levels 30-50 | maplestorywiki.net/w/Monster_Carnival; MapleLegends library "Monster Carnival"; Cosmic `2042000.js` |
| Mu Young (Balrog) | 1061014 | 1 | Stock | Balrog expedition rules; level gate in `ExpeditionType` | Hidden Street Balrog page |
| Pink flower pile (JQ chests) | 1063000-2 | 1 | Stock | Deep Forest of Patience reward is one random ore per run | Hidden Street Deep Forest JQ page |
| Staff Sergeant Charlie, Heracle, Robeira, Pedro | 2010000, 2010007, 2020009, 2020013 | 1 | Stock | Heracle guild fees; Robeira/Pedro are job tests | Cosmic scripts |
| Adobis, Amon (Zakum) | 2030013, 2030010 | 1 | Stock | Adobis's Mission I: Eye of Fire items, party; Zakum entry also needs the El Nath dungeon quests `[mem]` | Hidden Street Zakum page |
| Lira (Breath of Lava end) | 2032003 | 1 | Stock | Official: JQ prize is a random item | - |
| Tombstone, Machine Apparatus, Papulatus | 2041024, 2041025, 2043000 | 1 | Papulatus: v83-era, approx `[mem]` | Ludibrium Clocktower route; Time Leap quest 6363 (Buccaneer) `[stock]` | Hidden Street Papulatus page |
| Harmonia, Gritto, Hellin, Samuel (4th job tests) | 2081100, 2081200, 2081400, 2081500 | 1 | Stock | Level 120 + the 4th job test; some teach skills MapleRoot removed (ticket 82 section 1) | Hidden Street 4th job pages; MapleLegends |
| Asia (Neo City airship) | 2082014 | 1 | In stock Cosmic | Stock quest talk; Neo City is a Future-Henesys-line area `[mem]` | Hidden Street "Neo City"; Rulaxing / MapleRoyals Neo Tokyo guides |
| Mark of the Squad, Crystal of Roots (Horntail) | 2083004, 2083002 | 1 | Stock | Horntail expedition (level 80+, Horntail's Cave) | Hidden Street Horntail |
| Forgotten Temple Keeper (Pink Bean) | 2141001/2 | 1 | Stock; Pink Bean date: approx 2009 `[mem]` | Pink Bean expedition, level 120+ approx, Twilight of the Gods | Hidden Street Pink Bean |
| Plunk (boss exit reward) | 3004006 | 4 | - | Rewards are MapleRoot-specific | - |
| So Gong (Mu Lung Dojo) | 2091005 | 1 | Dojo in v83; GMS wiki page `[src]` (today: lv 105-200) | Belts cost dojo points; level 25 entry in stock | maplestorywiki.net/w/Mu_Lung_Dojo; Hidden Street Dojo |
| Konpei, Aldol, Moony, Amos, Glimmer Man, Joko, T-1337, Nana(K) | 9120201/2, 9201134, 9270047, 9201000, 9201044/5, 9201047, 9201097, 9201101, 9201023 | 1 | Stock | PQ and quest rules as per stock scripts | Cosmic scripts; Hidden Street Amoria |
| Maple Administrator | 2007 | 1+4 | Stock tutorial NPC, redirected | - | - |
| Loha, Sion, Duru, Tess (Ellin Forest) | 2131003, 2131005-7 | 2/3 (post-v83, undated) | Ellin Forest is not in stock Cosmic; exact GMS date not found | Official: Ellin Forest quest line (Perfect Pitch rewards) | maplestorywiki.net Ellin Forest page; Ayumilove Ellin Forest quests |

### 2d. Boss-expedition and Grandis/Arcane recruiters

| NPC / content | ID | Era | Introduced | Official requirement | Best guide links |
|---|---|---|---|---|---|
| Lotus (Hard) | 2491006 | 3 (undated) | Date not found | Level 190+, 1 clear/week, no prequest listed `[src]` maplestorywiki Lotus/Monster | maplestorywiki.net/w/Lotus/Monster |
| Magnus recruiter | 3000131 | 3 (undated; Heliseum/Tyrant's Castle) | Date not found; page says available on all regions | Prequest "[Heliseum] The Final Battle" once per account; levels Easy 115, Normal 155, Hard 175; Easy/Normal 1/day, Hard 1/week `[src]`. Ours uses quest 31166 | maplestorywiki.net/w/Magnus/Monster, /w/Tyrant%27s_Castle |
| Kao (Krexel recruiter) | 3003133 | 3 location, 1 boss | Krexel is a v83 boss; Lakeshore (450001105) is a Vanishing Journey map, GMS 2016-12-15 `[src]` | Official Krexel gate: stock expedition rules (Ruins of Krexel) | Hidden Street Krexel |
| Mr. Flopsy / Lucid's Dream | 9010100, 9906602 | 3 | V: Lucid (GMS part 3 of V, approx 2017); Hard in "Beyond/Override" `[src]` | Level 220+, ARC 360, prequest "[Lachelein] Nightmare Clocktower 4F", 1 clear/week `[src]` | maplestorywiki.net/w/Lucid/Monster; ayumilove V update notes |
| Gate / Will's Dream; Will wake NPC | 3003560, 9906603, 3003559 | 3 | Hard: ARK: Monad; Normal v.199; Easy v.233 `[src]` | Level 235+, ARC 760 (Normal/Hard), prequest "[Esfera] Mirrors in Mirrors" `[src]` | maplestorywiki.net/w/Will/Monster |
| Light in the Dark / Darknell's Dream; Darknell wake | 3003806, 9906604, 3003921 | 3 | Date not found (revamped RISE, Dec 2019 per wiki search snippet) | Level 255+, ARC 850, prequest "[Limina] Defying Destiny", 1 clear/week `[src]` | maplestorywiki.net/w/Guard_Captain_Darknell/Monster |
| Orchid / Hilla's Dream (Verus Hilla); ??? wake | 3003773, 9906605, 3003554 | 3 | Black Mage update GMS 2018-12-12/13; Hard v.201, Normal v.233 `[src]` | Level 250+, ARC 820/900, prequest "[Labyrinth of Suffering] Source of Suffering" `[src]` | maplestorywiki.net/w/Verus_Hilla |
| Von Leon recruiter + wake | 2161000, 2161008 | 2 | Lion King's Castle came with GMS "Aftershock" (after Big Bang, July 2010 base) `[src]` (no exact date) | Level 125+, prequest "(Lion King's Castle) A Frozen Heart" once per account, 1/day `[src]` | maplestorywiki.net/w/Von_Leon/Monster; strategywiki.org/wiki/MapleStory/Von_Leon |
| Ifia, Murgoth (Von Leon pass) | 2161001, 9000174 | 2 | as above | Quests 3173-3177 chain, ends at 3177 A Frozen Heart `[stock-absent]` (these quest ids are not in stock v83) | Ticket 82 section 2 |
| Cygnus (recruiter) + Sleeping Cygnus | 3003970, 3003903 | 2 | Cygnus expedition in Chaos (Hidden Street "v.99" notes: lv 170+, party 3-18) `[src, snippet]` | Official: level 170+ (our script: 30-120 - change this), party 3-18, daily entry | Hidden Street "v.99 Chaos: Age of Heroes Update Notes"; maplestorywiki.net Empress Cygnus |
| Tengu (Castellan) | 9111058 | 1 | Showa-era boss, approx (Castellan Toad) `[mem]` | Castellan expedition (Boss Rush-style) rules | Hidden Street Showa/Castellan |
| Lucid's robe wake NPC | 3002122 | 3 | as Lucid | Expedition leader | - |
| Informant (Hallowed Ground warp) | 2143000 | 2 (undated) | Hallowed Ground / Cygnus line; not in stock v83 map set | Per catalogue: needs Cygnus Garden progress | Ayumilove Cygnus Awakening notes; maplestorywiki.net/w/MapleStory:_Cygnus_Returns (does not name Hallowed Ground) `[src]` |
| Fox God | 3002015 | 3 (undated) | Fox Point (410M); date not found | 1,000 mesos in our script | - |
| Adventurer's Bag | 3004039 | 4 | - | - | - |
| Spawn Rate Totem, "?" feature list, Singapore gachapon | 9906600, 9977777, 9270043 | 4 | - | - | - |

### 2e. Quest chains from tickets 81 and 82

| Content | Quest ids | Era | Introduced | Official requirement | Best guide links |
|---|---|---|---|---|---|
| Maker chain (Meren) | 6029-6036 | 1 `[stock]` | Maker removed in Chaos `[src]` | Maker at Moren, Magatia; levels 45 / 75 / 105 `[src]` | maplestorywiki.net/w/Maker |
| Night Warrior, Sayram, Shadower, Marauder, Corsair skill books | 6141, 6150-6153, 6200-6202, 6330/6340/6350, 6410 | 1 `[stock]` (6141 confirmed) | v83 | 4th job skill-book quests, each with item hand-ins | Hidden Street 4th job pages; Cosmic `scripts/quest/6xxx.js` |
| Von Leon chain | 3163-3182 | 2 | Not in stock Quest.wz (grep: absent); Aftershock `[src]` | Per quest: tower keys, Alliance Badges, Jenn's Brother etc. (ticket 82 section 2) | strategywiki.org/wiki/MapleStory/Von_Leon; Hidden Street Lion King's Castle |
| Henesys Ruins "Gate to the Future" | 31100-31160 | 2/3 (undated) | Not in stock Quest.wz (absent); source update not found | Kill and collection quests lv 160-170 (ticket 82 section 3) | Ayumilove / Orange Mushroom searches for "Henesys Ruins" or "Future Henesys" |
| Arcane Path | 31161-31167 | 4 (custom quests on a post-BB base) | Ids are not in stock; official Arcane River entry is "Temple of Time questline + level 200 + V Matrix" `[src]` ayumilove v179 | Official: level 200+, Temple of Time questline complete | ayumilove.net/maplestory-update-notes-gms-v179/; strategywiki.org/wiki/MapleStory/Arcane_River (403 here) |
| Rebirth Path | 31168-31170 | 4 | - | - | - |
| Cygnus Garden | 31149 | 2/4 | undated | - | - |

## 3. Counts, undated items and gaps

Counts are by table row (grouped rows count once):

| Era | Rows |
|---|---|
| 1 (stock v83) | 25 |
| 1+4 (stock NPC or placement, MapleRoot function) | 6 |
| 2 (Big Bang and after) | 5 |
| 2/4 or 2/3 (post-v83, undated or mixed) | 4 |
| 3 (modern) | 9 |
| 4 (custom, no source; includes Star Force, whose concept is GMS 2014) | 17 |

**Undated or unconfirmed.** Gold Richie's Treasure Vault (no source found for any "Gold Richie" content), Pink Bean's own date, Papulatus,
Castellan/Tengu, Hallowed Ground and the Knight District/Ereve line, Henesys Ruins/"Gate to the Future" (quests 31100-31160), Ellin Forest,
Fox Point, Lotus, Magnus/Heliseum, Darknell's first GMS release, Cygnus expedition's exact date. Arcane Path quest ids 31161-31170 have no official source.

**Next steps for 1.7.0.** (1) Open the `[mem]` requirements from a browser (Hidden Street via Wayback, MapleLegends library, Royals forum) and
check each against a real client. (2) Use the boss `/Monster` subpages on maplestorywiki.net as the baseline for level and prequest of the
Grandis/Arcane recruiters. (3) For v83 items, the authoritative baseline is Cosmic's own scripts, which are in this repo; gate the shortcut NPCs
with the same checks those scripts make.
