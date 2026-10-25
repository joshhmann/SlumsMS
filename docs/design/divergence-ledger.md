# Divergence ledger: where SlumsMS differs from vanilla v83

Started 2026-10-25 at the owner's request: one living list of every place our server differs from stock v83 (stock
Cosmic), who changed it, and what was decided.

## Layers

1. **Vanilla (Cosmic).** Stock Cosmic, our baseline for "what v83 does". Audit baselines: `2d7525f2b` (jobs/skills),
   `afba85827` (ticket 68), tree at `/root/work/cosmic-triage/Cosmic`.
2. **MapleRoot.** The build we forked (import commit `03d13cb21`): class rework, 5th job, Super Beginner / Synth
   Master, rebalanced skills, custom content, the hub. Its changes are by default "keep MapleRoot's class identity".
3. **SlumsMS.** Ours: bots, social systems, 1.5.x fixes, owner choices. Own code lives in `soloMapling.*`.
4. **nutnnut/Cosmic.** A third-party fork (AGPL-3.0) we borrow from; see the last section.

## How to use it

- Find the area, find the row, read the status. Add a row for any new divergence the moment it is made.
- Columns: ID, what differs (vanilla -> current), changed by (MapleRoot / SlumsMS / both), status, decision and date
  (owner's words where known), source (file, ticket, commit).
- IDs are stable: `SK` skills, `QU` quests, `NP` NPCs and shops, `MP` maps and PQs, `EC` economy and cash shop,
  `SY` systems and config, `SE` security and stability, `NN` nutnnut. Never reuse or renumber an ID.
- Dates are 2026 unless stated. Owner decisions dated 2026-10-24/25 are recorded as 10-25.

## Status values

| status | meaning |
|---|---|
| keep | Differs on purpose; do not "restore" it. |
| restore-vanilla | Decided to go back to stock behaviour or numbers. |
| tune | Keep the idea, adjust the numbers. |
| fixed | A bug from a divergence, fixed (commit named). |
| broken-open | Known broken, no fix yet. |
| finish-or-disable | Half-built content: finish it or turn it off. |
| undecided | Waiting for the owner; the recommendation is in the row. |

**Agents must not change a decided row without the owner.** A row with status keep, restore-vanilla, tune or fixed
is decided. Only the owner moves an `undecided` row; record his words and the date. Agents may add new rows (as
`undecided` or `broken-open`) and update the source column.

---

## 1. Classes & skills

Full per-skill numbers (64 removed, 147 new, 230 changed) are in `docs/release/1.5.2-jobs-skills-audit.md`
(tables "Skill.wz: removed / new / changed stock skills, by class"). Rows below group them and list the notable ones.
Owner (10-25): keep MapleRoot's class identity.

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| SK-001 | Class identity: MapleRoot replaced much of the v83 skill set (64 removed, 147 new, 230 changed) | MapleRoot | keep | "Keep MapleRoot's class identity" 10-25 | jobs-skills audit |
| SK-002 | Removed: every weapon Booster and Spell Booster (Sword, Axe, BW, Spear, Pole Arm, Bow, Crossbow, Claw, Dagger, Knuckler, Gun, Cygnus equivalents, Aran Polearm Booster) | MapleRoot | keep | 10-25 (SK-001) | audit "removed" table |
| SK-003 | Removed: the second weapon type of each Warrior branch (Axe/BW/Pole Arm masteries, Final Attacks, charges, Panic/Coma Axe, Crusher/Fury Pole Arm) | MapleRoot | keep | 10-25 | audit |
| SK-004 | Removed: Big Bang x3, Slow x3, Heaven's Hammer, Ninja Ambush x2, Taunt x2, Steal, Assassinate, Energy Drain, Transformation x2, Shockwave x2, Demolition, Snatch, Barrage, Hypnotize | MapleRoot | keep | 10-25 | audit |
| SK-005 | Removed: Maker (1007, 10001007, 20001007); client Maker window only takes level-0 recipes, NPCs craft instead (`NpcMakerCraftingService`) | MapleRoot | keep | 10-25 | audit, `MakerService` |
| SK-006 | Removed: Wind Archer Eagle Eye / Wind Piercing / Wind Shot, Night Walker Vanish, Aran Polearm Booster | MapleRoot | keep | 10-25 | audit |
| SK-007 | New: a 4th job for every Cygnus class (1112/1212/1312/1412/1512; Job Master only) | MapleRoot | keep | 10-25 | audit |
| SK-008 | New: "5th job" (113, 123 ... 523, 1113..1513, 2113), four skills each; Job Master, rebirth 3 + item 4036541 | MapleRoot | keep | 10-25 | audit |
| SK-009 | New: extra 4th-job attacks for every explorer branch (Rising Rage, Holy Flame Hammer, La Mancha Spear, Megiddo Flame, Arrow Stream, Showdown, Octopunch, Brain Scrambler ...) | MapleRoot | keep | 10-25 | audit "new" table |
| SK-010 | New: Super Beginner (700, Chain Arts, Summon Spiked Bat, Nova Temperance) and Synth Master (710, music skills); Ninja Bounce / Vertical Bounce for every beginner | MapleRoot | keep | 10-25 | audit |
| SK-011 | Super Beginner HP/MP gain per level was never applied (700 % 100 branch order); now 300-350 HP / 150-200 MP | SlumsMS | fixed | `fdd18e1a6` | `JobAdvanceHpGainTest` |
| SK-012 | Buffs: almost every buff lasts 3600 s (stock 30 s to 15 min), incl. Maple Warrior, Hyper Body, Booster-class buffs, Meditation | MapleRoot | keep | Balance, "FYI, already live" (decisions list #11); 10-25 keep | audit "changed" table |
| SK-013 | Attack skills hit more targets and lines for 2-10x damage (Brandish 5 lines, Blast, Meteor Shower, Genesis, Snipe 4000 % x4, Assaulter, Savage Blow ...) | MapleRoot | keep | FYI, live (decisions #11) | audit |
| SK-014 | Hero's Will: cooldown 10 s (stock 600-360 s) for all classes; Corsair 5221010 renamed `HEROS_WILL` | MapleRoot | keep | FYI, live (decisions #11) | audit, `Corsair` |
| SK-015 | Seal (F/P, I/L, Blaze) and Doom: one-level crit buff (`SHARP_EYES`) instead of a mob debuff | MapleRoot | keep | Balance, live | `StatEffect`, audit |
| SK-016 | Summons (Elquines, Ifrit, Bahamut, Puppet, Golden Eagle, Frostprey, Beholder, Cygnus summons): were 20 s / 60 s cooldown, back to stock durations and no cooldown since 1.5.1; MapleRoot's summon damage buffs kept | both | tune | 10-25: stock v83 durations, no cooldown; owner leans longer later; keep summon damage buffs | `stock-summon-skills.yaml` |
| SK-017 | Mana Reflection reflects a flat 20 damage (stock x % of hit, cap 1/5 mob HP); also grants `STANCE` 80 | MapleRoot | keep | FYI | `TakeDamageHandler`, `StatEffect` |
| SK-018 | Meso Guard costs no mesos (cost commented out) | MapleRoot | undecided | Rec: keep (deliberate). Owner question #12 | `TakeDamageHandler:258` |
| SK-019 | Combo Tempest: no "non-boss drops to 0 HP" rule; always the damage formula (2700 % at max, 5 lines) | MapleRoot | undecided | Rec: keep. Owner question #12 | audit |
| SK-020 | Aran combo costs 10/30/200 (stock 30/100/200), remainder kept instead of reset to 0 | MapleRoot | keep | Balance, live | `RangedAttackHandler` |
| SK-021 | Armor/Magic/Power Crash each remove both weapon and magic immunity (stock: one each); `USE_ANTI_IMMUNITY_CRASH` on | MapleRoot | keep | Balance, live | `Monster`, config |
| SK-022 | Freeze skills last half of stock (`ret.duration *= 2` removed) | MapleRoot | keep | Balance, live | `StatEffect` |
| SK-023 | Cygnus: no bonus AP per level (stock about +73), level cap 200 (stock 120); level-up AP follows MapleRoot's rebirth scale (5/4/3/2, 10-50 past 200) | MapleRoot | keep | Consistent with MapleRoot design | `Character.levelUp`, audit |
| SK-024 | Beginners gain 60 HP / 60 MP a level (stock 12-16 / 10-12) and never auto-assign AP | MapleRoot | keep | Balance, live | `Character.levelUp` |
| SK-025 | A mob's Dispel no longer removes Magic Guard or Echo of Hero; `USE_UNDISPEL_HOLY_SHIELD` on | MapleRoot | keep | Live | `Character.dispel`, config |
| SK-026 | Steal does nothing on bosses; White Knight charges no longer add elemental weakness; Snipe fixed 195-200k damage removed | MapleRoot | keep | Live | `AbstractDealDamageHandler` |
| SK-027 | Summon damage cap removed (anti-cheat) | MapleRoot | keep | Live | `SummonDamageHandler` |
| SK-028 | Battleship HP removed; the ship never breaks | MapleRoot | keep | Owner 2026-10-21 keep; reconfirm for 1.5.1 (ticket 68 owner question #3) | `TakeDamageHandler:268`, `cosmic-diff-1.4.1.md:99` |
| SK-029 | Dark Tidings (1321016, DK "za warudo") froze the map before level/cooldown checks, any packet froze the map | MapleRoot | fixed | `d8b0fd9ff` | `DarkTidingsGateTest` |
| SK-030 | Dark Tidings / Brain Scrambler never affect bosses (`bossBypass` flag set but never read) | MapleRoot | undecided | Rec: keep bosses immune (8 s map-wide freeze every 6 s is too strong). Decisions #9 | `StatEffect`, `applyStatus` |
| SK-031 | Stock scripts teach 13 skills MapleRoot removed; null key in skills map made every later save of that character roll back | both | fixed | `a610b1a60` (Leafre 4th job F/P, I/L, Bishop, Bucc; "teach my skills" for several; Aran 21101/21720; Kyrin 6330) | `UnknownSkillTest` |
| SK-032 | Dialogue in Leafre NPCs, Kyrin, Aran quests still promises removed skills (now skipped quietly) | MapleRoot | fixed | 10-25 owner: "rewrite dialogue text" (decisions #10), no replacement skills: `61bf3c976` | audit "Needs the owner" #5, `RemovedSkillDialogueTest` |
| SK-033 | 19 mastery books for removed skills still drop (82 mob rows + 10 reactor rows) and do nothing | MapleRoot | undecided | Rec: remove from drops. Decisions #7 | audit #1 |
| SK-034 | Job Master 4th job gives no starting master levels (Leafre gives level 10 in three skills; `changeJob` lost `setMasteries`) | MapleRoot | undecided | Rec: match Leafre. Decisions #8 | audit #2 |
| SK-035 | Job Master NPC 9010003 offers every advancement from level 1 to 5th job with no tests or items; stock NPCs/quests unchanged (1st-job instructors refuse skill 1051 holders) | MapleRoot | keep | Class identity | audit "Job advancement" |
| SK-036 | Ninja Ambush DoT branch deleted in `Monster.applyStatus`; Heaven's Hammer non-boss rule; Flame Thrower venom flag | MapleRoot | keep | Moot (skills do not exist / flag unread) | audit "Known leads" |
| SK-037 | Warrior class group (Fighter to Dark Knight, Page to Paladin, Spearman to DK): Rage, Power Guard, Achilles, Monster Magnet, Brandish, Blast, Spear Crusher, Berserk, Hex of the Beholder rebalanced | MapleRoot | keep | Class identity | audit changed tables 100-132 |
| SK-038 | Magician group (F/P, I/L, Bishop): Teleport range 205-300 and 1 MP, Meditation, bolt/arrow skills hit 2-3 lines and 3-12 targets, Infinity cooldown 180 s, Bahamut, Angel Ray, Genesis, Resurrection cooldown 30 s | MapleRoot | keep | Class identity | audit tables 200-232 |
| SK-039 | Bowman group (Hunter to Marksman): Blizzard, Arrow Eruption, Strafe, Piercing Arrow 6 lines, Dragon's Breath, Sharp Eyes numbers, Snipe cooldown 8 s | MapleRoot | keep | Class identity | audit tables 300-322 |
| SK-040 | Thief group: Venomous Star, Triple Throw, Ninja Storm, Savage Blow, Assaulter, Band of Thieves, Boomerang Step 6 lines, Shadow Stars/Pickpocket/Meso Guard 3600 s | MapleRoot | keep | Class identity | audit tables 400-422 |
| SK-041 | Pirate group: Energy Charge, Dragon Strike, Energy Orb, Time Leap cooldown, Aerial Strike, Rapid Fire, Battleship (cooldown 120 s, 20 s), Cannon/Torpedo damage | MapleRoot | keep | Class identity | audit tables 511-522 |
| SK-042 | Cygnus group (Dawn Warrior, Blaze Wizard, Wind Archer, Night Walker, Thunder Breaker): 4th job and 5th job skills, removed boosters, Power Stance 11121204 | MapleRoot | keep | Class identity | audit tables 1000-1511 |
| SK-043 | Aran group: Final Blow, Combo Tempest, Over Swing 4-5 lines, Freeze Standing, High Defense / Combo Barrier numbers; `USE_FULL_ARAN_SKILLSET` on | MapleRoot | keep | Class identity | audit tables 2000-2112 |
| SK-044 | Level-up and job change: `Character.changeJob` lost stock `setMasteries`; Job Master lets a level-1 character swap Beginner / Noblesse / Legend | MapleRoot | keep | See SK-034 for the open part | `Character` |
| SK-045 | Job advance paths verified read-only per class (Warrior, Mage, Bowman, Thief, Pirate, Cygnus, Aran): OK after `a610b1a60`; none played in a real client | SlumsMS | broken-open | Needs one real-client pass per class | audit "Job advancement" table |

## 2. Quests

Source: quest checker report (2892 quests in Check.img: 1607 findings; 180 never start, 81 never complete; 434 differ
from stock; 1023 expired event quests are expected). Only decisions and notable groups are rows.

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| QU-001 | Skill-book quests for removed skills (6141, 6150/6153, 6200/6202, 6280/6281, 6330/6340/6350, 6410, Maker 6029/6034): finish with no reward or cannot start | MapleRoot | finish-or-disable | 10-25 owner: "hide now, ticket to finish later". Hidden in `server.quest.HiddenQuests` (6150-6153 for Paladins only); 6280/6281 kept (still teach Holy Charge: Sword). Ticket 82 | qc report, decisions-152, `HiddenQuestsTest` |
| QU-002 | MapleRoot-added content: Von Leon chain 3163-3182, Henesys Ruins / Arcane Path / Rebirth 31100-31170 (lv 105-200): items nothing drops, NPCs on no map, bosses never spawn | MapleRoot | finish-or-disable | 10-25 owner: "hide now, ticket to finish later". Dead steps hidden (`HiddenQuests`); working ones kept (Henesys Ruins story/kill quests, Arcane Path, Rebirth Path). Ticket 82 | qc report, `HiddenQuestsTest` |
| QU-003 | Von Leon map-wide potions removed | SlumsMS | restore-vanilla | 10-25: removed (vanilla Von Leon has no map-wide potion); see also MP-004 | ticket 68 open #1 context |
| QU-004 | Zipangu quests 4300-4309 dated 2000-01-02 (can never start); stock has no date | MapleRoot | restore-vanilla | 10-25 owner "yes" (decisions #3): server WZ deploy step `zipangu-quest-dates.tsv`, docs/release/1.5.2-deploy-steps.md #1; real-client check pending | qc report |
| QU-005 | 3714 Horntail egg: stock Dragon Stone reward missing | MapleRoot | restore-vanilla | 10-25 owner "yes" (decisions #4): Cosmic's `scripts/quest/3714.js` back (Dragon Stone 2041200, no second stone while one is carried) | qc report, `QuestScriptHandInTest` |
| QU-006 | 1205 Romeo and Juliet: start NPC 9212000 does not exist | MapleRoot | restore-vanilla | 10-25 owner "yes" (decisions #5): start NPC back to Cosmic's 2112018, server WZ deploy step `quest-start-npc.tsv`, 1.5.2-deploy-steps #2; real-client check pending | qc report |
| QU-007 | Medal quests: Dalair (9000066) stands on no map (stock gap) | stock gap | fixed | 10-25 owner "yes" (decisions #6): migration `026-dalair-henesys` places him in Henesys (x 4550, y 454) | qc report, `DalairMigrationTest` |
| QU-008 | Quest endmeso (meso cost on completion) was not checked; now enforced | SlumsMS | keep | 10-25: quest endmeso enforced | 1.5.2 quest sweep |
| QU-009 | Chained quests blocked by a broken prerequisite: Mushroom Forest 2312-2338, NLC Quiz 4900-4923, Keol 6014-6028, Yoona quiz 8020-8025, 20706-20711 and others (180 never start) | stock / MapleRoot | broken-open | Triage per chain; many are stock gaps | qc "never start" list |
| QU-010 | Amoria marriage quests 8862-8871 gated by marriage, level 10020 on 8862 | stock / MapleRoot | finish-or-disable | Tied to ticket 75 (MP-006); decide with it | qc report, ticket 75 |
| QU-011 | NLC quiz 4900 lvmin 100200 and Zakum armor 8013 lvmin 201 above the level cap | MapleRoot | broken-open | Needs data fix or removal | qc report |
| QU-012 | Event/lost-item quests whose start item is unobtainable (9412-9416, 19000/19002, 4924/4925) | stock / MapleRoot | broken-open | Low priority | qc report |
| QU-013 | Quest rate: `USE_QUEST_RATE` on, quest EXP/meso rate 1 (stock 5 and off) | MapleRoot | keep | Config | ticket 68 table |
| QU-014 | Kill quests prefill: `TOT_MOB_QUEST_REQUIREMENT` 200 (stock 0) | MapleRoot | keep | Config | ticket 68 table |
| QU-015 | 434 quests' Check/Act data differs from stock Cosmic (mostly requirement edits for the rebirth/level cap) | MapleRoot | keep | Listed in qc report; spot-check only | qc report last section |
| QU-016 | Quest-status bloat: empty NOT_STARTED rows saved for every quest | both | keep | Fix adopted from nutnnut `87fe36eda`, see NN-006 | ticket 80 |

## 3. NPCs & shops

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| NP-001 | Hub map 993910000 with Job Master (9010003), Stylist button, MTS redirect | MapleRoot | keep | MapleRoot identity | ticket 68 |
| NP-002 | Hub Stylist button opened `"stylist"` but file is `STYLIST.js` (case-sensitive FS): menu looped; two more dangling script references | MapleRoot | fixed | `053f0f78d` | `ScriptReferencesTest` |
| NP-003 | Maple Trading System gone: `MTS_OPERATION` not registered, MTS button warps to the hub; `USE_MTS: true` lies and does nothing; redirect ignores `CANNOTMIGRATE` | MapleRoot | undecided | Rec: keep MTS gone, drop the flag and dead registration. Ticket 68 owner question #2 | `PacketProcessor:227`, `EnterMTSHandler` |
| NP-004 | Job advancement NPCs (Leafre, Kyrin) still name removed skills | MapleRoot | undecided | See SK-032 | audit |
| NP-005 | Makers craft through NPCs (`NpcMakerCraftingService`), Maker skill absent; Maker crystals only improve (no downgrade/never-decrease variants) | both | keep | 10-25: Maker crystals only improve | `MakerService` |
| NP-006 | Duey on: `USE_DUEY` true (Cosmic default true; MapleRoot had it off) | SlumsMS | keep | 10-25: Duey on; live config needs it at the 1.5.2 release | decisions-152, ticket 68 |
| NP-007 | Family system off (`USE_FAMILY_SYSTEM` false; stock on) | MapleRoot | undecided | Ticket 68 owner question #4: deliberate? | ticket 68 |
| NP-008 | Buy-back NPC system: paid in NX (`USE_BUYBACK_WITH_MESOS` false), 1 min cooldown, last 5 transactions | MapleRoot | keep | Config | ticket 68 table |
| NP-009 | Rebirth NPC 1300009 (`USE_REBIRTH_SYSTEM` on): rates x3/x2/x1, reset to level 10 | MapleRoot | keep | MapleRoot identity | ticket 68 table |
| NP-010 | Daily check-in and Color Prism (cash coloring) exist | both | keep | Color Prism addon source lives in the Z:\MSv83 addons rar | ticket 17, config |
| NP-011 | Merchant, gacha and other service bots now talk back | SlumsMS | keep | Bot social layer | commit `93b31fc2` |

## 4. Maps & PQs

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| MP-001 | PQ clear crashed (`EventInstanceManager.clearPQ` assumed an expedition) | MapleRoot | fixed | `039d13164` | ticket 68 |
| MP-002 | PQ rewards were changed by MapleRoot; restored to Cosmic amounts | SlumsMS | restore-vanilla | 10-25: PQ rewards restored to Cosmic amounts | 1.5.2 owner decisions |
| MP-003 | Expedition maps (Zakum 211070100/101): any USE item is applied to every character on the map; `inExpedition` / `potionCount` flags leak on death, GM warp, script warp | MapleRoot | undecided | Rec: group potions are a design choice; the flag leak is a bug to fix wherever flags reset. Ticket 68 owner question #1 | `UseItemHandler:55`, `ChangeMapHandler:146` |
| MP-004 | Von Leon map-wide potions removed (see QU-003) | SlumsMS | restore-vanilla | 10-25 | owner decisions |
| MP-005 | Amoria PQ: stock needs exactly 6 married players of both genders; loosened to 2-6 players, no marriage | SlumsMS | keep | 10-25: "loosen that" (ticket 75) | `AmoriaPQ.js`, ticket 75 |
| MP-006 | Nett's Pyramid PQ: maps load, no entry (Duarte unavailable, mirror commented out); parked | stock gap | undecided | 10-25: "leave it off for now"; Cosmic PR #221 is the source if revived (ticket 76) | ticket 76 |
| MP-007 | Mount breeding room limited to 3 minutes | SlumsMS | keep | 10-25 | owner decisions |
| MP-008 | `ARAN_TUTORIAL_START` set to hub id so the tutorial range check is dead; `@goto fm` entry lost | MapleRoot | fixed | `619a25e32` | `MapConstantsTest` |
| MP-009 | Solo and daily expeditions on (`USE_ENABLE_SOLO_EXPEDITIONS`, `USE_ENABLE_DAILY_EXPEDITIONS`, 4 per machine per day); recall event on; full respawn on; mobs respawn every 5 s | MapleRoot | keep | Config | ticket 68 table |
| MP-010 | Fishing system and map ownership on (stock off) | MapleRoot | keep | Config | ticket 68 table |
| MP-011 | Jump quests pay 10k EXP and 100k meso | MapleRoot | keep | Config | ticket 68 table |
| MP-012 | Post-v83 monster-id blocks in `MobSkill.applyEffect` (Von Leon, Lotus, Lucid, Will), `NormalLotusBattle` script, post-v83 map constants | MapleRoot | keep | Dead code on v83 data; never port | ticket 68 |
| MP-013 | MapleRoot custom maps (Henesys Ruins, Arcane Path) are not wired to working content | MapleRoot | finish-or-disable | See QU-002 | qc report |

## 5. Economy & Cash Shop

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| EC-001 | Rates: EXP 3x, meso 10x, drop 4x, boss drop 3x (stock 10/10/10/10); party EXP bonus 2.0 | MapleRoot | keep | 10-25: flat 3x EXP, no progressive | ticket 68 table |
| EC-002 | Rate coupons: only 2x EXP 7 days (6,000 NX) and 3x EXP 1 day (3,000 NX) sold; no drop cards; `USE_SUPPLY_RATE_COUPONS` false | SlumsMS | keep | 10-25 | owner decisions |
| EC-003 | Rate coupons survive death | SlumsMS | keep | 10-25 | owner decisions |
| EC-004 | Godly drops: 1.5 % chance, 3 stats, +1..+5, mob drops only (no crafting/other sources) | SlumsMS | keep | 10-25; defaults, no `GODLY_*` keys in live config before the 1.5.2 jar | decisions-152 |
| EC-005 | Maker crystals only improve (never downgrade) | SlumsMS | keep | 10-25 | owner decisions |
| EC-006 | PIC on (`ENABLE_PIC` true; MapleRoot had false, so characters could not be deleted); PIC on every login (`BYPASS_PIC_EXPIRATION` 0); no PIN | SlumsMS | keep | 10-25: PIC on | ticket 68 |
| EC-007 | GM trade / drop / storage / Duey minimum GM level 4 (MapleRoot had 1) | SlumsMS | keep | 10-25 | decisions-152 |
| EC-008 | Meso Guard costs nothing (see SK-018) | MapleRoot | undecided | Rec: keep | audit |
| EC-009 | Drops: loot lies 11 min, 450 drops per map, untradeables may be dropped and looted, one copy of multi-roll equips, no explosion-erase | MapleRoot | keep | Config | ticket 68 table |
| EC-010 | Autoloot cash item (5590000), star force (20 % boost, 50 m meso cap), bag search UI, max equip stat 150000, level-up equip stat +3 | MapleRoot | keep | Config | ticket 68 table |
| EC-011 | Cash shop world transfer refused; cash chairs beyond stock id range count as chairs | MapleRoot | keep | Data fit | ticket 68 |
| EC-012 | Mount packet missing the character id for non-Battleship mounts (others never saw your mount) | MapleRoot | fixed | `f3c86ac8f` | `MonsterRidingPacketTest` |
| EC-013 | Battleship/Homing Beacon buff packet 3 bytes short for own buff | MapleRoot | undecided | Needs one in-game ride to settle; do not guess | ticket 68 open #3, `PacketCreator:2830` |
| EC-014 | Salon / face / hair id ranges too narrow for MapleRoot's data ("no options") | MapleRoot | fixed | `1fe365733` | ticket 68 |
| EC-015 | Progressive EXP, flat scroll bonus, tradeable untradeables | nutnnut | restore-vanilla | Rejected, see NN section; 10-25 flat 3x EXP | nutnnut reports |

## 6. Systems & config

Config lives in the deploy `config.yaml` (untracked); ticket 68 lists the 102 keys that differ from Cosmic (45 change
player rules).

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| SY-001 | Autologin removed | SlumsMS | keep | 10-25 | owner decisions |
| SY-002 | Chat gate runs on the real clock (not server-tick time) | SlumsMS | keep | 10-25 | owner decisions |
| SY-003 | 5 channels in one world (Bera), channel load 125 (stock 3 channels / 21 worlds) | MapleRoot | keep | Config | ticket 68 table |
| SY-004 | `TIMEZONE` GMT-6 (stock GMT): daily resets there | MapleRoot | keep | Pacific time is shown next to UTC in reports | ticket 68 |
| SY-005 | Rebirth system, add-slots-by-level, full Aran skill set, create guild alone (min partners 1) | MapleRoot | keep | Config | ticket 68 table |
| SY-006 | Pets: autopot ratio 0.52, one potion per trigger, never hungry | MapleRoot | keep | Config | ticket 68 table |
| SY-007 | `USE_STARTER_MERGE` declared never read; `USE_MTS` read nowhere; `USE_ENHANCED_CLEAN_SLATE` spelled differently from Cosmic's key (Cosmic spelling silently ignored) | MapleRoot | undecided | Rec: drop dead keys; no player effect | ticket 68 |
| SY-008 | `PQ_BONUS_EXP_RATE` 10.0 is inert (gated on a 2015 timestamp) | MapleRoot | keep | Inert | ticket 68 |
| SY-009 | Bot population (~3,000 living bots, roster ~9k, director, LOD, LLM chat), companion bots planned for 1.6.0 | SlumsMS | keep | Bot population direction; economy work deprioritized | `soloMapling.*` |
| SY-010 | Social systems, GM staging commands (e.g. `!botshowcase`), release notes page, dashboard on 8091 | SlumsMS | keep | Owner likes in-game staging commands | tickets, `tools/dashboard` |
| SY-011 | Scripted world events with bot reactions (spec) | SlumsMS | undecided | Spec only; build not scheduled | commit `277631c1` |
| SY-012 | Jar-only data loading gap (data not loaded from the server folder) | SlumsMS | broken-open | Noted in handoff | commit `22cec588` |
| SY-013 | Characters cannot be created with a bot's name | SlumsMS | fixed | commit `4fc53172` | git log |
| SY-014 | Client: our own patched client (EXP as long in `addCharStats`, ExpTable 256 entries) | SlumsMS | keep | Safe because we ship the client | ticket 68 |
| SY-015 | Cygnus/Aran first-job entry and Beginner switch via Job Master only | MapleRoot | keep | See SK-035 | audit |

## 7. Security & stability

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| SE-001 | `DETERRED_MULTICLIENT` on (one client per PC); `USE_CHARACTER_ACCOUNT_CHECK` on | MapleRoot | keep | Config | ticket 68 table |
| SE-002 | Expedition HWID limit 4 per machine per day | MapleRoot | keep | Config | ticket 68 table |
| SE-003 | Summon damage cap removed; stock anti-cheat limit gone | MapleRoot | keep | Live | `SummonDamageHandler` |
| SE-004 | `getHandler` bounds check added in `PacketProcessor` | SlumsMS | fixed | Bounds check on handler lookup | ticket 68 |
| SE-005 | Null skill key: any save of an affected character rolled back (inventory, mesos, levels) | both | fixed | `a610b1a60` | SK-031 |
| SE-006 | Stability fixes ported from nutnnut/Cosmic (lock order, snapshots, disposed characters, shutdown autosave) | SlumsMS | undecided | Adopted list in NN-001; each needs the 3,000-bot soak | ticket 80 |
| SE-007 | GM level 1 could trade, drop and use storage | MapleRoot | fixed | 10-25 level 4 (EC-007) | decisions-152 |

## 8. nutnnut/Cosmic (a fork we borrow from)

Credit: nutnnut/Cosmic, AGPL-3.0 (same licence as ours). Credit `nutnnut/Cosmic <sha>` in each port commit and in the
patch notes. Reports: `nutnnut-bots-report.md`, `nutnnut-misc-report.md`, ticket 80. Outside its bot system the fork is
almost stock Cosmic.

### Adopted

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| NN-001 | Ticket 80 stability fixes: lock order in `updateActiveEffects` / `cancelBuffStats` / `cancelAllBuffs` (AB-BA hang), `Inventory.list()` snapshot, `getSummonsValues()` copy, ring sort copy, `PartySearchEchelon` lock, disposed-character `sendPacket`, `HiredMerchant` visitor purge, `EquipSlot` null guard, shutdown autosave skip | nutnnut | keep | Adopted 1.5.2; soak 3,000 bots | `c9ba4f4da` `8a2d4adaf` `6050d1d9d` `ab7253d97` `51daa42f1` `dfe03664a` `b1707d11b` `0a9f08bf3` `56a43dcea` `e61767a42` |
| NN-002 | Trade item loss: `completeTrade()`/`cancel()` ignored `addFromDrop` returning false; item dropped at the receiver's feet; lock/cap checks | nutnnut | keep | Adopted 1.5.x | `e8b44ffc4`, `Trade.java` |
| NN-003 | Thread-safe caches: `LifeFactory.monsterStats`, `MonsterInformationProvider` maps to ConcurrentHashMap; failed mob loads negative-cached (log once) | nutnnut | keep | Adopted | `3c094c453` |
| NN-004 | Account-view null guards (`haveCharacterEntry`, `getAccountCharacterCount`, `validateCharacterOwnership` fallback) | nutnnut | keep | Adopted | `aa04eabef` |
| NN-005 | `!warp <map name>` / `!search` by name (`MapSearchHelper`) | nutnnut | keep | Adopted | `8799c9ef0` |
| NN-006 | Don't save empty NOT_STARTED quest rows; characterid / `drop_data` indexes (verify with `SHOW INDEX` first) | nutnnut | keep | Adopted | `87fe36eda`, migrations 028/029/032 |
| NN-007 | `!zakumpq` staging command and `ZakumPrequest` helper | nutnnut | keep | Adopted | `ZakumPqCommand` |
| NN-008 | `wz-data` agent skill (WZ layout, skill key traps) | nutnnut | keep | Adopted | `.claude/skills/wz-data` |
| NN-009 | Maker crystals only improve | nutnnut | keep | Adopted 10-25 (see EC-005) | owner decisions |
| NN-010 | Godly drops idea (1.5 % / 3 stats / +1..+5, mob drops only) in our own tuned form | nutnnut | keep | Adopted as idea 10-25 (see EC-004) | owner decisions |

### Planned

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| NN-011 | Bot movement fixes: ground continuity at overlapping footholds (`235b6aefa`), walk-off graph v68-71 (`8a7e7b3a5` `362926fdf` `26b372e7c`), zMass wall rule (`43b9f06c8`, riskiest), swim wall unfreeze, frozen-air watchdog | nutnnut | tune | Planned for 1.5.x; TEST real-WZ compare first | bots report 3(a) |
| NN-012 | Companion bots (owned alts, ticket 13) and command set (formations, patrol/sentry, supplies, info, gear/trade) | nutnnut | tune | Planned for 1.6.0 | bots report 3(b) |
| NN-013 | Bot market ideas: market web page, price candles, listings (ticket 79) | nutnnut | undecided | Planned as ideas for ticket 79; economy work deprioritized | bots report 5 |
| NN-014 | Save-path performance: skip unchanged writes, batched inserts, `rewriteBatchedStatements` | nutnnut | undecided | Later (1.6.x) after the ticket 80 soak | misc report #3 |
| NN-015 | Lock-free monster index in `MapleMap` | nutnnut | undecided | Later; profile a 50-bot map first | misc report #6 |
| NN-016 | `TimerManager` pool sizing (4 -> `max(4, cores)`), `!deletechar`, mob-rate density, world-map web export, item icon extractor | nutnnut | undecided | Measure or later; mob-rate needs the owner | misc report #5, #8, #10, #12, #13 |

### Considered and rejected

| ID | What differs (vanilla -> current) | By | Status | Decision + date | Source |
|---|---|---|---|---|---|
| NN-017 | Progressive EXP (per-level multipliers on world rate) | nutnnut | restore-vanilla | Rejected: flat 3x EXP, 10-25 | misc report #11 |
| NN-018 | Flat +10 % scroll bonus | nutnnut | restore-vanilla | Rejected: custom gameplay | misc skip list |
| NN-019 | Tradeable untradeables, one-of-a-kind check off, level 250 | nutnnut | restore-vanilla | Rejected | ticket 80 skip |
| NN-020 | Unauthenticated web admin (port 8089, `/api/command`, `/api/settings`, wipe) | nutnnut | restore-vanilla | Rejected for security; ours is basic auth behind nginx | misc security note |
| NN-021 | Their LLM chat, LOD and population scheduler, Ollama | nutnnut | restore-vanilla | Rejected: ours is ahead | bots report 3(c) |

---

*Sources: `docs/release/1.5.2-jobs-skills-audit.md`; quest checker report and decisions list (1.5.2 sweep scratchpad);
`docs/tickets/68-mapleroot-vs-cosmic-audit.md`, `75-amoria-pq-for-friends.md`, `76-nett-pyramid-pq.md`,
`80-port-nutnnut-stability-fixes.md`; nutnnut reports (bots, misc).*
