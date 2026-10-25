# Ticket 82: finish the quest chains SlumsMS hides (MapleRoot leftovers, removed skills)

Status: open, 2026-10-25. Content project for after 1.5.2. Owner decision (1.5.2 quest sweep): "hide now, ticket to
finish later".

## What 1.5.2 does

`server.quest.HiddenQuests` lists quests nobody can start. `Quest.canStart` and `Quest.forceStart` refuse them, so the
quest window (`QuestActionHandler` start and scripted start), NPC/quest scripts (`qm.forceStartQuest`,
`cm.startQuest`), events and auto-start quests all stop at the same gate. The quest window says "This quest can't be
finished on SlumsMS yet, so it can't be started." Nothing is deleted. A player who already has one open keeps it,
can still complete it if it can be completed, and can forfeit it. The WZ data is unchanged.

The client still shows these quests (lightbulb, quest log "available" list) because it reads its own Quest.wz. The
server just refuses the start. Hiding them in the client too would need a client Quest.wz edit.

To bring a quest back: finish what it lacks (below), remove it from `HiddenQuests`, and run
`tools/questcheck/quest_check.py` against the server WZ and DB exports to confirm.

Evidence: `tools/questcheck/quest_check.py` on TEST's WZ (same files as live's, md5-checked) and TEST's
`drop_data` / `reactordrops` / `shopitems` / `plife` exports, 2026-10-25, then checked by hand (scripts, Java, Map.wz).

## 1. Skill-book quests for skills MapleRoot removed

MapleRoot replaced much of the v83 skill tree (see `docs/release/1.5.2-jobs-skills-audit.md`). These quests reward
a skill that is not in our `Skill.wz`, so the player does the whole quest for nothing (`SkillAction` skips a missing
skill).

| quests | who | reward that no longer exists | 1.5.2 |
|---|---|---|---|
| 6141 The Night Warrior | Night Lord, Shadower | Ninja Ambush 4121004 / 4221004 | hidden |
| 6150-6153 Sayram chain | Paladin | Heaven's Hammer 1221011 | hidden **for Paladins only**: Heroes (Enrage 1121010) and Dark Knights (Berserk 1320006) still get theirs |
| 6200-6202 A Peculiar Request / Shawn's Fear / Arec and Shadower | Shadower | Assassinate 4221001 | hidden (6201 too: it only leads to 6202) |
| 6330 How to Improve Your Strength (Kyrin's test) | Marauder | Super Transformation 5121003 | hidden |
| 6340 Presence of Mind | Marauder | Snatch 5121005 (and it needs Super Transformation to start) | hidden |
| 6350 Body of Steel | Marauder | Demolition 5121004 (same) | hidden |
| 6410 Mission: Free the Monsters | Corsair | Hypnotize 5221009 | hidden |
| 6029-6036 Meren's Maker chain | everyone, lv 45+ | the Maker skill 1007 / 10001007 / 20001007 (MapleRoot crafts at NPCs instead, `NpcMakerCraftingService`); 6033 and 6036 teach Maker levels 2 and 3 | hidden (6030-6032 are 6029's steps, 6033-6036 its follow-ups) |

Not hidden, checked:
- **6280 The Holy Knight's Sword / 6281 Whereabouts of the Grail** (Paladin) still work: the grail comes from 6281,
  the Fountain of Life (`scripts/npc/2083005.js`) fills it, and 6280 still teaches **Holy Charge: Sword 1221003**
  (master level 10). Only its second skill, Divine Charge: BW 1221004, is gone. Hiding it would take a real skill
  away from Paladins.
- **6140 Return of the Dark Lord** (lead-in to 6141) works on its own (300,000 EXP).

To finish (owner's call per skill): retarget each quest to MapleRoot's replacement skill (e.g. Paladin's Holy Flame
Hammer 1221017 for 6153), or leave them hidden. Granting replacement skills is an owner decision (1.5.2: "keep
MapleRoot's skill set, restore single skills only on request").

## 2. Von Leon chain (3163-3182, lv 105, Lion King's Castle)

| quest | what it lacks |
|---|---|
| 3164 Jenn's Brother | needs item 4032831 "Jenn's Brother": no mob, reactor, shop, script or Java gives it |
| 3165-3167 Keys to the First/Second/Third Tower | need 50 x 4000625 Crocky's Helmet / 4000626 Reindeer's Spear / 4000627 Bearwolf's Morning Star: nothing drops them (the Von Leon field mobs have **no drop_data rows at all**, e.g. Prison Guard Boar 8210006, Prison Guard Rhino 8210007, Grey Vulture 8210004); and they follow 3164 |
| 3168-3171 Knight of the Lion King ... Defeat the Lion King | follow 3182; 3170 needs 4032835 Dirty Magic Scroll of the Knight (nothing gives it) |
| 3173 The Mistrustful King, 3175 The Deluded King | hand in to **Von Leon NPC 2161000**, which stands on no map and no script spawns |
| 3174 Key to the Rose Garden | needs 4032836 Key to the Rose Garden (nothing gives it) |
| 3176 Why Did the King Change? | follows 3175 |
| 3177 A Frozen Heart | needs 10 x 4032838 Alliance Badge (nothing gives it) |
| 3181 Murt's Letter, 3182 Alcaster's Crystal | start/hand in at **Murt 2160000**, which has no `Npc.wz` entry at all on the server |

Kept: **3163** The Adventure of Jenn the Locksmith (auto-start, talk to Jenn, 230,300 EXP) and **3172** The Weeping
Queen (talk to Ifia, 30,100 EXP): both finish on their own; the next step simply won't start.

To finish: drop_data rows for the castle mobs (the tower items, Alliance Badge, scroll, rose-garden key), a source
for Jenn's Brother (a reactor or script in the prison map), Von Leon NPC 2161000 placed (or spawned by the boss
script) for 3173/3175, and Murt 2160000 added to the server's Npc.wz (and the client's) and placed in El Nath.

## 3. Henesys Ruins (31100-31160, lv 160-170, "Gate to the Future")

The whole area's mobs have **no drop_data rows**: Mutant Snail / Orange Mushroom / Slime / Ribbon Pig / Tino / Tiru
/ Tiguru (8600000-8600006) and the Official / Advanced Knights (8610005-8610014). The kill quests work; the
collection quests do not.

| quest | what it lacks |
|---|---|
| 31110 Gathering Food 1 | 60 x 4032921 Snail Meat |
| 31111 Gathering Food 2 | 60 x 4000643 Mutant Mushroom Cap; follows 31110 |
| 31114 Utah's Request | 4032930 Glittering Crystal Marble |
| 31115 Welcome Home Utah | 60 x 4000645 Mutant Pork; follows 31114 |
| 31116 Never Give Up | 50 x 4032940 Pig Oil, 60 x 4000644 Mutant Slime Bubble |
| 31117 Utah's Pig Farm | works by itself (its start gives 20 x catch item 2270021, `UseCatchItemHandler` turns a Mutant Ribbon Pig into 4032924), but follows 31114 |
| 31118 Jay the Warlock | 60 x 4000642 Mutant Snail Shell |
| 31122 Jay and the Quest for Power | 60 x 4000646 Mutant Tino Feather, 60 x 4000647 Mutant Tiru Feather; follows 31118 |
| 31123 Superior Arrows | 60 x 4000648 Mutant Tiguru Feather |
| 31132 From Father to Son | 4032926 Stan's Letter |
| 31133 Lost Smile | 4032941 Cheery Book |
| 31134 Trouble | 100 x 4000652 Knight Gloves, 150 x 4000651 Knight Bandana |
| 31135 Utah's Protection | 200 x 4000653 Knight Pauldron; follows 31114 |
| 31141 Superior Implements | 4032925 Superior Magic Wand; follows 31118 |
| 31142 Anne's Misunderstanding, 31143 Worthy Weapon | 4032928 Superior Bow, 4032927 Ornate Sword; follow 31123 |
| 31158-31160 Dream Fragment / Doubt / Creator of Dreams | each needs 4020013 Dream Fragment to start, nothing gives it |

Kept (they finish): the story 31100-31106, 31107-31109 and 31120-31121 (kill quests), 31112-31113, the stronghold
line 31124-31131, Knight Hunting 31136-31140, 31145-31148, **31149 Cygnus Garden** (the checker flags it, but
`scripts/portal/in_cygnusGarden.js` completes it), 31150-31156, and so the **Arcane Path 31161-31167** (lv 200,
which gives 4036541, the 5th-job item) and the **Rebirth Path 31168-31170** stay. 31148 Versus Chief Knights and
31153 Stop Cygnus! need the Cygnus fight (`CygnusBattle`, the knights 8850000-8850004 spawn during it,
`MapleMap.spawnCygnusMobs`). 31119 and 31144 have Act data but no Check data, so the server never loads them.

Not verified in a real client: that the Henesys Ruins maps, the Cygnus fight and the Arcane Path bosses work end to
end. The checker says their NPCs stand on maps and their mobs spawn; nobody has played them on SlumsMS yet.

To finish: drop_data rows for the Mutant and Knight mobs (the items above; GMS rates are in public drop tables), a
source for the quest items (Snail Meat, Glittering Crystal Marble, Pig Oil, Stan's Letter, Cheery Book, the
superior weapons, Dream Fragment), then unhide.

## Done when

Each hidden quest is either finished and removed from `HiddenQuests` (with `quest_check.py` clean for it and a
real-client run), or the owner decides it stays hidden.
