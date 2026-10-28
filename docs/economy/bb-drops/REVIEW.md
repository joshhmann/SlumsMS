# Post-Big-Bang drop tables: review for the owner (draft, ticket 81)

Owner decision 2026-10-28: give the ~550 post-Big-Bang mobs that drop nothing (or only their card) real drop tables.
This is the draft for your review. **Nothing is applied anywhere**: migration `050-bb-drop-tables` (placeholder
number) is not on TEST, not in a release conf, not merged. Rules: `RULES.md`. Every row: `rows.csv`. Inventory:
`mobs.csv`.

## In one paragraph

511 mobs get a table (9,172 rows): mesos on every kill scaled like classic mobs of the same level, 3 potions, 5
ores, 4 scrolls and 5 equips, all items and rates taken from what classic v83 mobs already drop. 4 mini-bosses
in Mirror World and 1 in Grandis get elite tables; Cygnus, Magnus and the Singularity Maker get conservative
Zakum-shaped tables. The 246 Monster Book card rows stay exactly as they are. 26 mobs with 0 EXP stay empty. The
big caveat: most Arcane River and level-200 Grandis mobs have 45 million to 2.1 billion HP (300 to 15,000 times a
level-131 Temple of Time mob), so what these tables pay per hour depends almost entirely on how hard a 3-rebirth
character hits. Per kill they pay 1.1-2.6x a Temple of Time kill; per point of damage dealt they pay far less.

## Totals

| Region | Mobs (target) | Tables | Normal / elite / boss / low | No table (0 EXP) | Rows | Mesos per kill, in game (normal) |
|---|---|---|---|---|---|---|
| Arcane River 450-459 | 160 | 146 | 145 / 0 / 1 / 0 | 14 | 2,632 | 10,240-24,000 |
| Grandis 400-419 | 145 | 140 | 135 / 1 / 2 / 2 | 5 | 2,503 | 6,980-23,880 |
| Future / Twilight Perion 271-273 (+3 maze mobs on 272) | 54 | 49 | 46 / 0 / 0 / 3 | 5 | 834 | 7,940-20,690 |
| Mirror World 321-328 (**no way in today**) | 178 | 176 | 171 / 5 / 0 / 0 | 2 | 3,203 | 6,530-17,180 |
| **All** | **537** | **511** | 497 / 6 / 3 / 5 | **26** | **9,172** | |

The 70 mobs on 270-273 include 16 v83 Temple of Time mobs on 270 that already have classic tables; they are not
touched. Of the 511, 246 already had their card row (kept) and 265 had no rows at all (they drop nothing today, not
even the global drops: `MapleMap.java:883`).

Rows by kind: 511 meso, 1,529 potion, 2,545 ore/rock/stimulator, 2,042 scroll, 2,545 equip.

## How the mesos compare with a classic grinding spot

World rates are 10x mesos, 4x drops, 3x EXP. Reference: **Chief Oblivion Guardian** (Temple of Time, lv 131, 141,000
HP, 7,060 EXP), the highest classic grinding mob, pays 760-1,070 base = **9,150 mesos per kill**. Assume a
character that kills 1,000 of them an hour: **9.15M mesos/hour** and 21.2M EXP/hour.

| Mob (region, level) | HP | Mesos/kill in game | Same 1,000 kills/hour | Same damage per hour as the classic spot | Mesos per EXP (classic 0.43) |
|---|---|---|---|---|---|
| Happy Erda (Arcane, 200) | 44.6M | 12,800 | 12.8M/h | 3.2 kills/h, 40k/h | 0.043 |
| Sun Spirit (Arcane, 200, median) | 265M | 16,000 | 16.0M/h | 0.53 kills/h, 8.5k/h | 0.013 |
| Embrion (Arcane, 200, toughest) | 678M | 20,000 | 20.0M/h | 0.21 kills/h, 4.2k/h | 0.009 |
| Green Grossular (Grandis, 109) | 2.18M | 8,720 | 8.7M/h | 65 kills/h, 0.56M/h | 0.27 |
| Specter Stray Dog (Grandis, 130, median) | 2.6M | 10,400 | 10.4M/h | 54 kills/h, 0.56M/h | 0.27 |
| Ebonstar Magician (Grandis, 200) | 2.15B | 16,470 | 16.5M/h | 0.07 kills/h, 1k/h | 0.003 |
| Corrupted Wyvern (Twilight Perion, 124) | 113k | 9,920 | 9.9M/h | 1,248 kills/h, 12.4M/h | 1.61 |
| Official Knight D (Future, 174, median) | 5.7M | 11,135 | 11.1M/h | 25 kills/h, 0.28M/h | 0.16 |
| Beetle (Mirror, 102) | 198k | 8,160 | 8.2M/h (classic lv 121: 8.8M) | 580 kills/h, 4.7M/h | 0.95 |
| Jar (Mirror, 137, median) | 881k | 10,960 | 11.0M/h | 131 kills/h, 1.4M/h | 0.46 |

How to read it: the third column is the ceiling (a character strong enough to kill these as fast as a classic mob):
up to about 2x a classic spot in Arcane, about the same elsewhere. The fourth column is the same character as at
the classic spot: almost nothing in Arcane and level-200 Grandis, because the HP is so large. EXP has the same
problem (Arcane mobs give 14-100x the EXP of a classic mob for 300-4,800x the HP), so this is a mob-balance question
more than a drop-table one. Items add about 0.44 drops per kill (mostly potions and ores) on top.

Elite and boss mesos: Mirror World mini-bosses (Intruder, Mummy, Horus, Advisor Mummy, Master Dummy, lv 116-131)
and the Sky Guardian (Grandis, lv 200) pay 4x a normal mob: 29,700-76,800 per kill. Cygnus 100,800-151,200, Magnus
136,800-205,200, Singularity Maker 144,000-216,000 per kill (Zakum pays 100,000-150,000 at 10x).

## Sample tables

Chances are drop_data values (out of 1,000,000); the percentage is the in-game chance after the 4x drop rate (3x
for bosses). Every listed mob also keeps the global drops.

### Arcane River
**Happy Erda** (8641000, lv 200, HP 44,640,000, EXP 98,708, normal, card row kept: yes)
- Mesos 1,024-1,536 base = 10,240-15,360 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Mana Elixir 20000 (8%)
- Ores: DEX Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); Black Crystal Ore 9000 (3.6%); Magic Powder (Purple) 10000 (4%); Shield Production Stimulator 6000 (2.4%)
- Scrolls: Scroll for Helmet for HP 10% 300 (0.12%); Scroll for Earring for INT 10% 300 (0.12%); Scroll for Cape for HP 10% 300 (0.12%); Scroll for Two-Handed BW for Accuracy 10% 300 (0.12%)
- Equips: Green Canal Hood 1287 (0.5148%); Dark Katinas 700 (0.28%); Purple Valkyrie Skirt 800 (0.32%); Red Martier 1000 (0.4%); Magicodar 700 (0.28%)

**Sun Spirit** (8644001, lv 200, HP 264,616,000, EXP 401,055, normal, card row kept: yes)
- Mesos 1,280-1,920 base = 12,800-19,200 in game, every kill
- Potions: Power Elixir 20000 (8%); Mana Elixir 20000 (8%); Unagi 20000 (8%)
- Ores: Wisdom Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); Black Crystal Ore 9000 (3.6%); Magic Powder (Blue) 10000 (4%); Dagger Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Helmet for HP 60% 300 (0.12%); Scroll for Bottomwear for DEX 10% 300 (0.12%); Scroll for Two-Handed Axe for Accuracy 30% 750 (0.3%); Scroll for Claw for ATT 60% 300 (0.12%)
- Equips: Green Varr Hat 1287 (0.5148%); Blue Commodore 800 (0.32%); Dark War Greave 800 (0.32%); Dark Ades Shoes 800 (0.32%); Concerto 500 (0.2%)

**Embrion** (8645022, lv 200, HP 677,762,800, EXP 721,247, normal, card row kept: none)
- Mesos 1,600-2,400 base = 16,000-24,000 in game, every kill
- Potions: Elixir 20000 (8%); Mana Elixir 20000 (8%); Sunset Dew 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Gold Ore 9000 (3.6%); Garnet Ore 9000 (3.6%); Magic Powder (Brown) 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Scrolls: Dark Scroll for Face Accessory for HP 70% 750 (0.3%); Scroll for Topwear for HP 100% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 30% 750 (0.3%); Scroll for Two-Handed Axe for Accuracy 60% 300 (0.12%)
- Equips: Blue Canal Hood 1287 (0.5148%); Blue Arnah Cap 1287 (0.5148%); Green Varuna 700 (0.28%); Red Varr Shoes 800 (0.32%); Blue Marine 700 (0.28%)

### Grandis
**Green Grossular** (2400400, lv 109, HP 2,180,000, EXP 10,900, normal, card row kept: yes)
- Mesos 698-1,046 base = 6,980-10,460 in game, every kill
- Potions: Power Elixir 20000 (8%); Mana Elixir 20000 (8%); Sunset Dew 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Amethyst Ore 9000 (3.6%); Emerald Ore 9000 (3.6%); The Magic Rock 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Topwear for HP 60% 300 (0.12%); Scroll for Cape for INT 60% 300 (0.12%); Scroll for Two-Handed Sword for Accuracy 10% 300 (0.12%); Scroll for Two-handed Axe for ATT 60% 300 (0.12%)
- Equips: Red Osfa Hat 1287 (0.5148%); Blue Valhalla Helmet 1287 (0.5148%); Blue Sage 850 (0.34%); Silver Kalkan 700 (0.28%); The Peacemaker 500 (0.2%)

**Specter Stray Dog** (2400307, lv 130, HP 2,600,000, EXP 13,000, normal, card row kept: yes)
- Mesos 832-1,248 base = 8,320-12,480 in game, every kill
- Potions: Mana Elixir 20000 (8%); Reindeer Milk 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Bronze Ore 9000 (3.6%); Garnet Ore 9000 (3.6%); Magic Powder (Purple) 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Topwear for HP 100% 300 (0.12%); Scroll for Topwear for HP 60% 300 (0.12%); Scroll for One-Handed BW for Accuracy 60% 300 (0.12%); Scroll for Spear for Accuracy 30% 750 (0.3%)
- Equips: Red Ades 700 (0.28%); Blue Oriental Fury Coat 700 (0.28%); Green Oriental Fury Coat 700 (0.28%); Blue Czar 700 (0.28%); Purple Valkyrie Skirt 800 (0.32%)

**Ebonstar Magician** (8645130, lv 200, HP 2,147,483,647, EXP 1,828,409, normal, card row kept: none)
- Mesos 1,318-1,976 base = 13,180-19,760 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Reindeer Milk 20000 (8%)
- Ores: Dark Crystal Ore 10000 (4%); Steel Ore 9000 (3.6%); AquaMarine Ore 9000 (3.6%); The Summoning Rock 10000 (4%); One-Handed Blunt Weapon Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Earring for INT 60% 300 (0.12%); Scroll for Overall Armor for DEX 100% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 100% 300 (0.12%); Scroll for Dagger for ATT 10% 300 (0.12%)
- Equips: Dark Valhalla Helmet 1287 (0.5148%); Green Osfa Suit 800 (0.32%); Dark Anes 700 (0.28%); Green Sage 1000 (0.4%); Casa Crow 500 (0.2%)

Boss:

**Cygnus** (8850111, lv 140, HP 2,100,000,000, EXP 2,040,000, boss, card row kept: none)
- Mesos 10,080-15,120 base = 100,800-151,200 in game, every kill
- Potions: Elixir 999999 (100%); Power Elixir 999999 (100%); Reindeer Milk 999999 (100%); Sunset Dew 999999 (100%); Mana Elixir x1-4 40000 (12%)
- Ores: The Magic Rock 7000 (2.1%); The Summoning Rock 7000 (2.1%)
- Scrolls: Scroll for Helmet for HP 100% 13500 (4.05%); Scroll for Helmet for INT 10% 13500 (4.05%); Scroll for Overall Armor for DEF 10% 13500 (4.05%); Scroll for Gloves for ATT 10% 13500 (4.05%); Scroll for Cape for Weapon DEF 60% 13500 (4.05%); Scroll for Two-handed Axe for ATT 10% 13500 (4.05%)
- Equips: Red Brave Hamal 22500 (6.75%); Green Osfa Suit 22500 (6.75%); Black Oriental Fury Coat 22500 (6.75%); Blue Czar 22500 (6.75%); Dark Commodore Pants 22500 (6.75%); Red Osfa Boots 22500 (6.75%); Blue Crescent Boots 22500 (6.75%); Green Arnah Shoes 22500 (6.75%)

### Future / Twilight Perion
**Corrupted Wyvern** (8220016, lv 124, HP 113,000, EXP 2,051, normal, card row kept: none)
- Mesos 794-1,190 base = 7,940-11,900 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Mana Elixir 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Bronze Ore 9000 (3.6%); Emerald Ore 9000 (3.6%); Magic Powder (Yellow) 10000 (4%); Bottomwear Production Stimulator 6000 (2.4%)
- Scrolls: Dark Scroll for Eye Accessory for Accuracy 30% 750 (0.3%); Scroll for Shield for DEF 100% 300 (0.12%); Scroll for Shield for HP 100% 300 (0.12%); Scroll for One-Handed BW for ATT 100% 300 (0.12%)
- Equips: Blue Katinas 700 (0.28%); Red Katinas 700 (0.28%); Red Armis 700 (0.28%); Green Ades Shoes 800 (0.32%); Bronze Kalkan 700 (0.28%)

**Official Knight D** (8610008, lv 174, HP 5,696,400, EXP 23,495, normal, card row kept: yes)
- Mesos 891-1,336 base = 8,910-13,360 in game, every kill
- Potions: Mana Elixir 20000 (8%); Reindeer Milk 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Opal Ore 9000 (3.6%); Sapphire Ore 9000 (3.6%); Magic Powder (Brown) 10000 (4%); Pole Arm Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Topwear for LUK 100% 300 (0.12%); Scroll for Overall Armor for DEF 60% 300 (0.12%); Scroll for Bottomwear for DEX 10% 300 (0.12%); Scroll for Pole Arm for Accuracy 100% 300 (0.12%)
- Equips: Green Canal Hood 1287 (0.5148%); Red Varr Hat 1287 (0.5148%); Black Oriental Fury Coat 700 (0.28%); Red Focus 1000 (0.4%); Dark Neschere 500 (0.2%)

**Ancient Mixed Golem** (8620011, lv 198, HP 13,955,200, EXP 64,640, normal, card row kept: yes)
- Mesos 1,267-1,901 base = 12,670-19,010 in game, every kill
- Potions: Elixir 20000 (8%); Sunset Dew 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); AquaMarine Ore 9000 (3.6%); The Magic Rock 10000 (4%); Crossbow Production Stimulator 6000 (2.4%)
- Scrolls: Scroll for Earring for LUK 60% 300 (0.12%); Scroll for Bottomwear for HP 10% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 100% 300 (0.12%); Scroll for Knuckle for Accuracy 60% 300 (0.12%)
- Equips: Blue Czar 700 (0.28%); Green Focus 1000 (0.4%); Blue Arcina 1000 (0.4%); White Nisrock 500 (0.2%); White Neschere 600 (0.24%)

### Mirror World (unreachable today)
**Beetle** (2600000, lv 102, HP 198,133, EXP 2,853, normal, card row kept: none)
- Mesos 653-979 base = 6,530-9,790 in game, every kill
- Potions: Reindeer Milk 20000 (8%); Sunset Dew 20000 (8%); Unagi 20000 (8%)
- Ores: Dark Crystal Ore 10000 (4%); Bronze Ore 9000 (3.6%); Lidium Ore 9000 (3.6%); Magic Powder (Brown) 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Bottomwear for HP 100% 300 (0.12%); Scroll for Cape for Weapon DEF 10% 300 (0.12%); Scroll for Pole Arm for Accuracy 100% 300 (0.12%); Scroll for Pole Arm for Accuracy 10% 300 (0.12%)
- Equips: Green Arlic Helmet 1287 (0.5148%); Purple Valkyrie 800 (0.32%); Green Ades 700 (0.28%); Blue Oriental Fury Coat 700 (0.28%); Black Giles Cape 1000 (0.4%)

**Jar** (2600317, lv 137, HP 880,641, EXP 7,935, normal, card row kept: none)
- Mesos 877-1,315 base = 8,770-13,150 in game, every kill
- Potions: Elixir 20000 (8%); Reindeer Milk 20000 (8%); Unagi 20000 (8%)
- Ores: Dark Crystal Ore 10000 (4%); Amethyst Ore 9000 (3.6%); Diamond Ore 9000 (3.6%); The Magic Rock 10000 (4%); Gloves Production Stimulator 6000 (2.4%)
- Scrolls: Scroll for Shield for STR 30% 750 (0.3%); Scroll for Cape for Weapon DEF 10% 300 (0.12%); Scroll for Wand for Magic ATT 60% 300 (0.12%); Scroll for Pet Equip. for DEX 60% 300 (0.12%)
- Equips: Blue Neos 800 (0.32%); Dark Valkyrie 750 (0.3%); Blue Czar 700 (0.28%); Red Ades Shoes 750 (0.3%); White Fangz 500 (0.2%)

**Chief Oblivion Guardian** (2600714, lv 179, HP 4,598,524, EXP 27,075, normal, card row kept: none)
- Mesos 1,146-1,718 base = 11,460-17,180 in game, every kill
- Potions: Elixir 20000 (8%); Mana Elixir 20000 (8%); Reindeer Milk 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Amethyst Ore 9000 (3.6%); AquaMarine Ore 9000 (3.6%); Magic Powder (Yellow) 10000 (4%); Bow Production Stimulator 6000 (2.4%)
- Scrolls: Dark Scroll for Helmet for DEX 70% 750 (0.3%); Scroll for Overall Armor for DEX 100% 300 (0.12%); Scroll for Shield for STR 100% 300 (0.12%); Scroll for Cape for INT 60% 300 (0.12%)
- Equips: Purple Osfa Suit 700 (0.28%); Red Osfa Suit 800 (0.32%); Red Belly Duke 700 (0.28%); Red Katina Boots 800 (0.32%); Dark Pachone 1000 (0.4%)

Elite:

**Intruder** (2600116, lv 116, HP 360,244, EXP 4,236, elite, card row kept: none)
- Mesos 2,970-4,454 base = 29,700-44,540 in game, every kill
- Potions: Mana Elixir 40000 (16%); Reindeer Milk 40000 (16%); Unagi 40000 (16%)
- Ores: Power Crystal Ore 20000 (8%); LUK Crystal Ore 20000 (8%); Garnet Ore 18000 (7.2%); Opal Ore 18000 (7.2%); Sapphire Ore 18000 (7.2%); Magic Powder (Yellow) 20000 (8%); Magic Powder (Black) 20000 (8%); Two-Handed Sword Forging Stimulator 12000 (4.8%); Two-Handed Axe Forging Stimulator 12000 (4.8%)
- Scrolls: Scroll for Helmet for HP 60% 600 (0.24%); Scroll for Earring for INT 10% 600 (0.24%); Scroll for Shoes for Jump 100% 600 (0.24%); Scroll for Shoes for Jump 10% 600 (0.24%); Scroll for Cape for Magic Def. 60% 600 (0.24%); Scroll for One-Handed Sword for Accuracy 60% 600 (0.24%)
- Equips: Rose Earrings 2000 (0.8%); Red Focus 2000 (0.8%); Dark Pachone 2000 (0.8%); Dark Mystra 2000 (0.8%); Hellslayer 1400 (0.56%); White Nisrock 1000 (0.4%)

## Risky items

- **Attack scrolls.** 384 rows on 283 mobs are weapon or gloves ATT/Magic ATT scrolls (10%, 30%, 60%), at classic
  rates (300 = 0.12% a kill in game, 750 = 0.3% for 30%). Gloves for ATT 60% and 10% are on 14 mobs each. That is
  the same rate a Temple of Time mob drops them at, but it is 283 new sources. If you want fewer, the easy cut is
  to drop ATT scrolls from the pool or halve them to 150.
- **Boss scrolls.** Cygnus has Gloves for ATT 10% at 13,500 (4% a kill at 3x boss drop), Magnus has Knuckle ATT
  60% and Gun ATT 30%, Singularity Maker Bow ATT 60% and Knuckle ATT 10%. Zakum's rate; bosses are rare kills.
- **Boss equips** (8 per boss at 6.75% each) are classic lv 88-108 gear, not boss gear, but any equip a boss drops
  triggers the bots' rare-drop megaphone (`WorldEventMegaphones.isRareDrop`): about one every two boss kills.
- **Godly equips.** Every mob equip can take the 1.5.2 godly roll; 2,545 new equip rows mean more godly rolls, all
  on lv 88-108 classic gear.
- **Bots.** 3-rebirth bots already travel to Grandis and Arcane (BotTransit). Once these tables exist, the bots'
  offscreen yield model (`OffscreenRewardBudget`) reads them, so these maps start paying bots too, under the same
  per-map and daily caps (ticket 15). Worth a `!botyield` look after it lands on TEST.
- **No high-value items were added**: no Chaos, White or Clean Slate scrolls (Chaos and White already come from the
  global drops), no mastery or skill books, no boss gear, no post-Big-Bang equipment.

## Open questions for you

1. **Mesos by level (this draft) or by EXP?** Level-scaled mesos are what you asked for and are conservative, but
   in Arcane they give 1/30 of the classic mesos per EXP. Scaling by EXP instead (classic 0.43 mesos per EXP) would
   pay an Arcane mob about 520,000 mesos a kill (52,000 base); not recommended until we know how fast a 3-rebirth
   character kills them. The generator has one constant for each if you want a middle ground (e.g. x3).
2. **Mob HP.** Arcane mobs have 45M-678M HP and many level-200 Grandis mobs 2.1B (the int maximum). Is that what
   you want rebirth-3 content to feel like? If HP gets rebalanced, the per-hour numbers above change with it.
3. **The 26 mobs with 0 EXP**, especially Ascendion, Foreberion and Embrion (8645040-42, 7,441 placements on
   450012301-303) and the Erda variants on 450001400: they give no EXP and no drops. Give them EXP and tables, or
   leave the maps as scenery?
4. **Mirror World**: include its 3,203 rows now (harmless, nobody can get there) or regenerate without it
   (`--regions arcane,grandis,future`) until it has a way in?
5. **Region items.** These mobs have their own ETC items and the region has post-Big-Bang gear (Arcane Umbra etc.)
   in the client data. This draft adds none of them. Add region ETC (NPC-sell income, future quests) later?
6. **Mastery books**: classic lv 100+ mobs drop them at 500. Left out here. Want them?
7. **Bosses**: are Cygnus (400000003), Magnus (401060101) and the Singularity Maker (450014250) meant to be farmable
   fights? Their tables are Zakum-sized and could be cut to mesos and potions.
8. **Release**: 050 is a placeholder number and the migration is not in `docs/release/1.6.1.conf`. When you say go,
   it gets its real number, a notes line and a TEST deploy (`!reloaddrops` or a restart picks it up).

## What was run

- `tools/economy/bb_inventory.py` and `gen_bb_drops.py` against the server WZ (`/root/work/bake/wz`) and a SELECT
  export of TEST `drop_data` (2026-10-28). Regenerating gives byte-identical files.
- `BbDropTablesDataTest`: every item exists in Item.wz/Character.wz, has a name, no cash/quest/untradeable/
  account-bound/one-of-a-kind items, no card items, every mob in Mob.wz, one meso row per mob.
- `BbDropTablesMigrationIT` on throwaway MySQL 8.0.46 and 8.4.11 containers: apply twice, rollback twice, apply
  again; card rows, an older row and an unrelated mob unchanged.
- Not tested in a real client or on TEST.
