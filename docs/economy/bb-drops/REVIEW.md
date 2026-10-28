# Post-Big-Bang drop tables and HP: review for the owner (draft, round 2, ticket 81)

This is the second draft, after the owner's answers on 2026-10-28. Mesos stay by level. HP comes down to a classic
curve with EXP kept. Mirror World is left out. Three new item kinds are added: each mob's own ETC, mastery books and
post-Big-Bang gear. The 0-EXP copies stay an open question. ETC items only go in when they have a use here or an NPC
price (coordinator's rule; exclusions listed below). **Nothing is applied anywhere**: migration
`050-bb-drop-tables` (a placeholder number) is not on TEST, not in a release conf and not merged. The HP table only
takes effect when a build with it is deployed. Rules: `RULES.md`. Every drop row: `rows.csv`. Every HP change:
`hp.csv`. Inventory: `mobs.csv`.

## In one paragraph

335 mobs in Arcane River, Grandis and Future/Twilight Perion get a drop table, 7,699 rows in all. Each normal mob
gets:
- classic level-scaled mesos on every kill
- 3 potions and 5 ores
- its own ETC item where Item.wz has one (the Droplet Stone in Arcane)
- 4 scrolls and 2 mastery books at classic rates
- 5 classic equips
- 2 post-Big-Bang pieces on par with classic gear (Eclectic, Muspell)
- 1 rare outclassing piece (Pensalir, Jaihin, Utgard, AbsoLab)

325 of these mobs get a new max HP on a classic curve for their level. Arcane River comes down 59-446x (median
261x) and Grandis and Future about 25x. Cygnus and Magnus come down to Zakum and Pink Bean scale. The 246 Monster
Book card rows are untouched. The 24 mobs with 0 EXP get nothing. With the same damage output as a classic Temple of
Time grinder, these maps now pay 4-13M mesos an hour (classic spot: 9.15M). The new risk is EXP: these mobs keep
their post-Big-Bang EXP on classic HP, so EXP per hour is 2-70x a classic spot.

## Totals

| Region | Target mobs | Tables | Normal / elite / boss / low | 0 EXP (nothing) | Drop rows | HP rows | Mesos per kill, in game (normal) |
|---|---|---|---|---|---|---|---|
| Arcane River 450-459 | 160 | 146 | 145 / 0 / 1 / 0 | 14 | 3,511 | 145 | 10,240-24,000 |
| Grandis 400-419 | 145 | 140 | 135 / 1 / 2 / 2 | 5 | 3,103 | 138 | 6,980-23,880 |
| Future / Twilight Perion 271-273 | 54 | 49 | 46 / 0 / 0 / 3 | 5 | 1,085 | 42 | 7,940-20,690 |
| **Total** | **359** | **335** | 326 / 1 / 3 / 5 | **24** | **7,699** | **325** | |
| Mirror World 321-328 (left out) | 178 | 0 | `--regions all` adds 176 tables | 2 | 0 | 0 | |

Of the 335, 246 keep their card row and 89 had no rows at all (today they drop nothing, not even global drops).

Rows by kind:

| Kind | Rows |
|---|---|
| Mesos | 335 |
| Potions | 1,001 |
| Ores, rocks and stimulators | 1,645 |
| Own and region ETC | 190 |
| Scrolls | 1,328 |
| Mastery books | 667 |
| Classic equips | 1,660 |
| Post-Big-Bang gear, par | 614 |
| Post-Big-Bang gear, rare | 259 |

## HP: before and after

The new HP follows a classic curve for the mob's level: lv 109 = 63k, lv 131 = 119k, lv 170 = 290k, lv 200 = 507k.
Within a level it keeps each mob's relative toughness (0.75-1.5x). Only mobs more than 1.25x over their new value
change. "Before" is what the server uses today, after LifeFactory's halving of non-boss lv 160+ mobs and its
hard-coded boss HP.

| Region | Normal mobs changed | Server HP before (min / median / max) | After (min / median / max) | Divided by (min / median / max) |
|---|---|---|---|---|
| Arcane River | 145 | 22.3M / 132M / 339M | 380k / 507k / 760k | 59 / 261 / 446 |
| Grandis | 135 | 2.18M / 2.6M / 1.07B | 63k / 116k / 722k | 8 / 25 / 2,118 |
| Future / Twilight Perion | 42 of 46 | 350k / 6.67M / 28.5M | 209k / 346k / 490k | 1.4 / 23 / 70 |

| Elite and bosses | Before | After |
|---|---|---|
| Sky Guardian (Grandis, elite, lv 200) | 446,400,000 | 15,200,000 (30x the curve) |
| Cygnus (lv 140) | 2,100,000,000 | 110,000,000 (Zakum's body) |
| Magnus (lv 190) | 200,000,000,000 (hard-coded in LifeFactory) | 2,100,000,000 (Pink Bean) |
| Singularity Maker (lv 200) | 2,147,483,647 | unchanged (already Pink Bean scale) |

### How it is done, and what the client sees

- **Server.** LifeFactory reads Mob.wz `info/maxHP`, halves it for non-boss lv 160+ mobs over 30M, and hard-codes some
  bosses. There was no data override. The new `server.life.MobHpOverrides` reads
  `src/main/resources/soloMapling/mob-hp-overrides.tsv` (mob id, HP, comment). The file ships inside the jar, so
  deploys need no extra data file. LifeFactory applies it last, after its own HP. The WZ files are not patched and
  nothing else changes (EXP, level, attack, defence). `-Dslums.mobHpOverrides=false` turns it off. 325 rows.
- **Client: no change needed for play.**
  - A normal mob's HP bar is the percentage the server sends (`Monster.broadcastMobHpBar`, SHOW_MONSTER_HP).
  - A boss bar is the server's current and max HP (`showBossHP`).
  - Kills are the server's. Damage numbers are worked out from the player's own stats.
- **Client: the Monster Book still shows the old HP.** The book (monsterbook.json baked into Custom.wz) prints
  Mob.wz `maxHP` unless the entry has a `defaultHp` string. `tools/monsterbook/build_book.py` now takes
  `--hp-overrides <tsv>` and writes the new HP as `defaultHp` (checked: Sun Spirit prints 507000). It only shows in
  game after a Custom.wz rebuild and a client release. Not tested in a real client.

## Mesos and EXP per hour

Assumption: the same character as at a classic spot. Reference: **Chief Oblivion Guardian** (Temple of Time,
lv 131, 141,000 HP), 1,000 kills an hour = 141M damage an hour. That pays **9.15M mesos and 21.2M EXP an hour**.
Below, the same 141M damage an hour on each mob, capped at 1,500 kills an hour for spawns.

| Mob (region, level) | HP before -> after | Mesos per kill | Kills/hour before -> after | Mesos/hour before -> after | EXP/hour after (classic 21.2M) |
|---|---|---|---|---|---|
| Happy Erda (Arcane, 200) | 22.3M -> 380k | 12,800 | 6.3 -> 371 | 81k -> 4.75M | 110M |
| Sun Spirit (Arcane, 200, median) | 132M -> 507k | 16,000 | 1.1 -> 278 | 17k -> 4.45M | 335M |
| Embrion (Arcane, 200, toughest) | 339M -> 760k | 20,000 | 0.4 -> 186 | 8k -> 3.71M | 401M |
| Green Grossular (Grandis, 109) | 2.18M -> 63k | 8,720 | 65 -> 1,500 | 0.56M -> 13.1M | 49M |
| Specter Stray Dog (Grandis, 130, median) | 2.6M -> 116k | 10,400 | 54 -> 1,216 | 0.56M -> 12.6M | 47M |
| Ebonstar Magician (Grandis, 200) | 1.07B -> 507k | 16,470 | 0.1 -> 278 | 2k -> 4.58M | 1,525M |
| Official Knight D (Future, 174, median) | 5.70M -> 236k | 11,135 | 25 -> 597 | 0.28M -> 6.65M | 42M |
| Ancient Mixed Golem (Future, 198) | 14.0M -> 490k | 15,840 | 10 -> 288 | 0.16M -> 4.56M | 56M |
| Corrupted Wyvern (Twilight Perion, 124) | 113k (unchanged) | 9,920 | 1,248 | 12.4M | 7.7M |

Reading it:
- **Mesos** now sit at about half a classic spot in Arcane for a classic-strength character. A level-200 character
  hits harder, so it will be higher.
- **Grandis lv 109-130** pays about what a classic spot of the same level pays.
- **EXP** is the side effect. Keeping post-Big-Bang EXP on classic HP gives 2-70x the classic EXP per hour (Ebonstar
  Magician: 1.8M EXP a kill at 507k HP). See open question 1.

Items add about 0.5 drops a kill on top (median), mostly potions and ores. The 38 mobs with their own ETC drop up to 2.3
items a kill.

## Sample tables

Chances are drop_data values (out of 1,000,000). The percentage is the in-game chance after the 4x drop rate (3x for
bosses). Every mob also keeps the global drops.

### Arcane River
**Happy Erda** (8641000, lv 200, HP 22,320,000 -> 380,000, EXP 98,708, normal, card row kept: yes)
- Mesos 1,024-1,536 base = 10,240-15,360 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Mana Elixir 20000 (8%)
- Ores: DEX Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); Black Crystal Ore 9000 (3.6%); Magic Powder (Purple) 10000 (4%); Shield Production Stimulator 6000 (2.4%)
- Own/region ETC: Arcane River Droplet Stone 10000 (4%)
- Scrolls: Scroll for Helmet for HP 10% 300 (0.12%); Scroll for Earring for INT 10% 300 (0.12%); Scroll for Cape for HP 10% 300 (0.12%); Scroll for Two-Handed BW for Accuracy 10% 300 (0.12%)
- Mastery books: [Mastery Book] Dragon's Breath 20 500 (0.2%); [Mastery Book] High Defense 20 1500 (0.6%)
- Classic equips: Green Canal Hood 1287 (0.515%); Dark Katinas 700 (0.28%); Purple Valkyrie Skirt 800 (0.32%); Red Martier 1000 (0.4%); Magicodar 700 (0.28%)
- Post-Big-Bang gear: Muspell Thief Hat 800 (0.32%); Muspell Warrior Suit 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): AbsoLab Piercing Spear 15 (0.006%)

**Sun Spirit** (8644001, lv 200, HP 132,308,000 -> 507,000, EXP 401,055, normal, card row kept: yes)
- Mesos 1,280-1,920 base = 12,800-19,200 in game, every kill
- Potions: Power Elixir 20000 (8%); Mana Elixir 20000 (8%); Unagi 20000 (8%)
- Ores: Wisdom Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); Black Crystal Ore 9000 (3.6%); Magic Powder (Blue) 10000 (4%); Dagger Forging Stimulator 6000 (2.4%)
- Own/region ETC: Arcane River Droplet Stone 10000 (4%)
- Scrolls: Scroll for Helmet for HP 60% 300 (0.12%); Scroll for Bottomwear for DEX 10% 300 (0.12%); Scroll for Two-Handed Axe for Accuracy 30% 750 (0.3%); Scroll for Claw for ATT 60% 300 (0.12%)
- Mastery books: [Mastery Book] Piercing Arrow 20 500 (0.2%); [Mastery Book] High Mastery 20 1500 (0.6%)
- Classic equips: Green Varr Hat 1287 (0.515%); Blue Commodore 800 (0.32%); Dark War Greave 800 (0.32%); Dark Ades Shoes 800 (0.32%); Concerto 500 (0.2%)
- Post-Big-Bang gear: Muspell Bowman Hat 800 (0.32%); Muspell Bowman Suit 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): AbsoLab Blade Lord 15 (0.006%)

**Embrion** (8645022, lv 200, HP 338,881,400 -> 760,000, EXP 721,247, normal, card row kept: none)
- Mesos 1,600-2,400 base = 16,000-24,000 in game, every kill
- Potions: Elixir 20000 (8%); Mana Elixir 20000 (8%); Sunset Dew 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Gold Ore 9000 (3.6%); Garnet Ore 9000 (3.6%); Magic Powder (Brown) 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Own/region ETC: Arcane River Droplet Stone 10000 (4%)
- Scrolls: Dark Scroll for Face Accessory for HP 70% 750 (0.3%); Scroll for Topwear for HP 100% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 30% 750 (0.3%); Scroll for Two-Handed Axe for Accuracy 60% 300 (0.12%)
- Mastery books: [Mastery Book] Paralyze 20 500 (0.2%); [Mastery Book] Triple Throw 30 500 (0.2%)
- Classic equips: Blue Canal Hood 1287 (0.515%); Blue Arnah Cap 1287 (0.515%); Green Varuna 700 (0.28%); Red Varr Shoes 800 (0.32%); Blue Marine 700 (0.28%)
- Post-Big-Bang gear: Muspell Warrior Hat 800 (0.32%); Muspell Warrior Suit 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Pensalir Battle Boots 60 (0.024%)

### Grandis
**Green Grossular** (2400400, lv 109, HP 2,180,000 -> 63,200, EXP 10,900, normal, card row kept: yes)
- Mesos 698-1,046 base = 6,980-10,460 in game, every kill
- Potions: Power Elixir 20000 (8%); Mana Elixir 20000 (8%); Sunset Dew 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Amethyst Ore 9000 (3.6%); Emerald Ore 9000 (3.6%); The Magic Rock 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Topwear for HP 60% 300 (0.12%); Scroll for Cape for INT 60% 300 (0.12%); Scroll for Two-Handed Sword for Accuracy 10% 300 (0.12%); Scroll for Two-handed Axe for ATT 60% 300 (0.12%)
- Mastery books: [Mastery Book] Power Stance 20 500 (0.2%); [Mastery Book] Concentrate 30 500 (0.2%)
- Classic equips: Red Osfa Hat 1287 (0.515%); Blue Valhalla Helmet 1287 (0.515%); Blue Sage 850 (0.34%); Silver Kalkan 700 (0.28%); The Peacemaker 500 (0.2%)

**Specter Stray Dog** (2400307, lv 130, HP 2,600,000 -> 116,000, EXP 13,000, normal, card row kept: yes)
- Mesos 832-1,248 base = 8,320-12,480 in game, every kill
- Potions: Mana Elixir 20000 (8%); Reindeer Milk 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Bronze Ore 9000 (3.6%); Garnet Ore 9000 (3.6%); Magic Powder (Purple) 10000 (4%); Two-Handed Sword Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Topwear for HP 100% 300 (0.12%); Scroll for Topwear for HP 60% 300 (0.12%); Scroll for One-Handed BW for Accuracy 60% 300 (0.12%); Scroll for Spear for Accuracy 30% 750 (0.3%)
- Mastery books: [Mastery Book] Mana Reflection 20 500 (0.2%); [Mastery Book] Time Leap 30 500 (0.2%)
- Classic equips: Red Ades 700 (0.28%); Blue Oriental Fury Coat 700 (0.28%); Green Oriental Fury Coat 700 (0.28%); Blue Czar 700 (0.28%); Purple Valkyrie Skirt 800 (0.32%)
- Post-Big-Bang gear: Eclectic Hermosa 800 (0.32%); Muspell Pirate Gloves 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Jaihin Wand 60 (0.024%)

**Ebonstar Magician** (8645130, lv 200, HP 1,073,741,823 -> 507,000, EXP 1,828,409, normal, card row kept: none)
- Mesos 1,318-1,976 base = 13,180-19,760 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Reindeer Milk 20000 (8%)
- Ores: Dark Crystal Ore 10000 (4%); Steel Ore 9000 (3.6%); AquaMarine Ore 9000 (3.6%); The Summoning Rock 10000 (4%); One-Handed Blunt Weapon Forging Stimulator 6000 (2.4%)
- Scrolls: Scroll for Earring for INT 60% 300 (0.12%); Scroll for Overall Armor for DEX 100% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 100% 300 (0.12%); Scroll for Dagger for ATT 10% 300 (0.12%)
- Mastery books: [Mastery Book] Genesis 30 750 (0.3%); [Mastery Book]High Mastery 30 1500 (0.6%)
- Classic equips: Dark Valhalla Helmet 1287 (0.515%); Green Osfa Suit 800 (0.32%); Dark Anes 700 (0.28%); Green Sage 1000 (0.4%); Casa Crow 500 (0.2%)
- Post-Big-Bang gear: Muspell Magician Hat 800 (0.32%); Muspell Pirate Gloves 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Jaihin Two-handed Axe 60 (0.024%)

Elite:

**Sky Guardian** (2400259, lv 200, HP 446,400,000 -> 15,200,000, EXP 82,257, elite, card row kept: none)
- Mesos 5,120-7,680 base = 51,200-76,800 in game, every kill
- Potions: Elixir 40000 (16%); Power Elixir 40000 (16%); Reindeer Milk 40000 (16%)
- Ores: Wisdom Crystal Ore 20000 (8%); LUK Crystal Ore 20000 (8%); Adamantium Ore 18000 (7.2%); Amethyst Ore 18000 (7.2%); Diamond Ore 18000 (7.2%); Magic Powder (Brown) 20000 (8%); Magic Powder (White) 20000 (8%); Dagger Forging Stimulator 12000 (4.8%); Claw Production Stimulator 12000 (4.8%)
- Scrolls: Scroll for Topwear for STR 100% 600 (0.24%); Scroll for Gloves for DEX 10% 600 (0.24%); Scroll for Cape for INT 100% 600 (0.24%); Scroll for Pole Arm for ATT 100% 600 (0.24%); Scroll for Claw for ATT 10% 600 (0.24%); Scroll for Knuckle for ATT 10% 600 (0.24%)
- Mastery books: [Mastery Book] Berserk 30 1000 (0.4%); [Mastery Book] Shadow Shifter 20 1000 (0.4%); [Mastery Book] Combo Barrier 30 3000 (1.2%)
- Classic equips: Green Valhalla Helmet 2574 (1.03%); Green Lucida 1600 (0.64%); Red Katinas 1400 (0.56%); Blue Arzuna 1400 (0.56%); Black Duke Barkin Shoes 1600 (0.64%); Green Focus 2000 (0.8%)
- Post-Big-Bang gear: Muspell Warrior Shoes 1600 (0.64%); Muspell Warrior Gloves 1600 (0.64%); Muspell Magician Gloves 1600 (0.64%)
- Post-Big-Bang gear, outclassing (rare): AbsoLab Piercing Spear 30 (0.012%)

Boss:

**Cygnus** (8850111, lv 140, HP 2,100,000,000 -> 110,000,000, EXP 2,040,000, boss, card row kept: none)
- Mesos 10,080-15,120 base = 100,800-151,200 in game, every kill
- Potions: Elixir 999999 (100%); Power Elixir 999999 (100%); Reindeer Milk 999999 (100%); Sunset Dew 999999 (100%); Mana Elixir x1-4 40000 (12%)
- Ores: The Magic Rock 7000 (2.1%); The Summoning Rock 7000 (2.1%)
- Scrolls: Scroll for Helmet for HP 100% 13500 (4.05%); Scroll for Helmet for INT 10% 13500 (4.05%); Scroll for Overall Armor for DEF 10% 13500 (4.05%); Scroll for Gloves for ATT 10% 13500 (4.05%); Scroll for Cape for Weapon DEF 60% 13500 (4.05%); Scroll for Two-handed Axe for ATT 10% 13500 (4.05%)
- Mastery books: [Mastery Book] Brandish 30 20000 (6%); [Mastery Book] Blind 20 20000 (6%); [Mastery Book]High Mastery 30 20000 (6%); [Mastery Book] Combo Tempest 30 20000 (6%)
- Classic equips: Red Brave Hamal 22500 (6.75%); Green Osfa Suit 22500 (6.75%); Black Oriental Fury Coat 22500 (6.75%); Blue Czar 22500 (6.75%); Dark Commodore Pants 22500 (6.75%); Red Osfa Boots 22500 (6.75%); Blue Crescent Boots 22500 (6.75%); Green Arnah Shoes 22500 (6.75%)
- Post-Big-Bang gear: Muspell Thief Hat 22500 (6.75%); Eclectic Prinsid 22500 (6.75%); Eclectic Rontano 22500 (6.75%)
- Post-Big-Bang gear, outclassing (rare): Jaihin Guards 3000 (0.9%)

### Future / Twilight Perion
**Dawn** (8610000, lv 169, HP 4,796,400 -> 284,000, EXP 20,280, normal, card row kept: yes)
- Mesos 1,082-1,622 base = 10,820-16,220 in game, every kill
- Potions: Elixir 20000 (8%); Power Elixir 20000 (8%); Unagi 20000 (8%)
- Ores: DEX Crystal Ore 10000 (4%); Garnet Ore 9000 (3.6%); Emerald Ore 9000 (3.6%); Magic Powder (Black) 10000 (4%); Pole Arm Forging Stimulator 6000 (2.4%)
- Own/region ETC: Dawn Pendant 400000 (100%); Dawn Stone 200000 (80%)
- Scrolls: Scroll for Earring for LUK 100% 300 (0.12%); Scroll for Topwear for LUK 100% 300 (0.12%); Scroll for Two-Handed Axe for Accuracy 100% 300 (0.12%); Scroll for Two-Handed Axe for Accuracy 10% 300 (0.12%)
- Mastery books: [Mastery Book] Paralyze 20 500 (0.2%); [Mastery Book] Wrath of the Octopi 20 500 (0.2%)
- Classic equips: Red Ades 700 (0.28%); Red Ades Shoes 750 (0.3%); Green Crescent Boots 800 (0.32%); Dark Imperial 1000 (0.4%); Red Martier 1000 (0.4%)
- Post-Big-Bang gear: Muspell Magician Shoes 800 (0.32%); Muspell Pirate Gloves 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Pensalir Chaser Hat 60 (0.024%)

**Official Knight D** (8610008, lv 174, HP 5,696,400 -> 236,000, EXP 23,495, normal, card row kept: yes)
- Mesos 891-1,336 base = 8,910-13,360 in game, every kill
- Potions: Mana Elixir 20000 (8%); Reindeer Milk 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Opal Ore 9000 (3.6%); Sapphire Ore 9000 (3.6%); Magic Powder (Brown) 10000 (4%); Pole Arm Forging Stimulator 6000 (2.4%)
- Own/region ETC: Knight Gloves 400000 (100%)
- Scrolls: Scroll for Topwear for LUK 100% 300 (0.12%); Scroll for Overall Armor for DEF 60% 300 (0.12%); Scroll for Bottomwear for DEX 10% 300 (0.12%); Scroll for Pole Arm for Accuracy 100% 300 (0.12%)
- Mastery books: [Mastery Book] Paralyze 30 500 (0.2%); [Mastery Book] Elemental Boost 20 500 (0.2%)
- Classic equips: Green Canal Hood 1287 (0.515%); Red Varr Hat 1287 (0.515%); Black Oriental Fury Coat 700 (0.28%); Red Focus 1000 (0.4%); Dark Neschere 500 (0.2%)
- Post-Big-Bang gear: Muspell Warrior Suit 800 (0.32%); Muspell Pirate Gloves 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Jaihin Crossbow 60 (0.024%)

**Ancient Mixed Golem** (8620011, lv 198, HP 13,955,200 -> 490,000, EXP 64,640, normal, card row kept: yes)
- Mesos 1,267-1,901 base = 12,670-19,010 in game, every kill
- Potions: Elixir 20000 (8%); Sunset Dew 20000 (8%); Unagi 20000 (8%)
- Ores: LUK Crystal Ore 10000 (4%); Adamantium Ore 9000 (3.6%); AquaMarine Ore 9000 (3.6%); The Magic Rock 10000 (4%); Crossbow Production Stimulator 6000 (2.4%)
- Scrolls: Scroll for Earring for LUK 60% 300 (0.12%); Scroll for Bottomwear for HP 10% 300 (0.12%); Scroll for One-Handed Sword for Accuracy 100% 300 (0.12%); Scroll for Knuckle for Accuracy 60% 300 (0.12%)
- Mastery books: [Mastery Book] Advanced Combo Attack 30 500 (0.2%); [Mastery Book] Berserk 30 500 (0.2%)
- Classic equips: Blue Czar 700 (0.28%); Green Focus 1000 (0.4%); Blue Arcina 1000 (0.4%); White Nisrock 500 (0.2%); White Neschere 600 (0.24%)
- Post-Big-Bang gear: Muspell Bowman Hat 800 (0.32%); Muspell Bowman Shoes 800 (0.32%)
- Post-Big-Bang gear, outclassing (rare): Utgard Pistol 60 (0.024%)

## Risky items

- **Outclassing post-Big-Bang gear (259 rows).** These were checked in the server's Character.wz: none is cash,
  untradeable or one-of-a-kind, and all have stats.
  - Jaihin (lv 130) and Utgard (lv 140) weapons have 207-249 ATT, 47-65% over the best classic weapons (Horntail's
    Dragon weapons, 141-151).
  - Pensalir armour (lv 140) has 2-3x the stats of the best classic armour.
  - AbsoLab (lv 160) weapons have 313-321 ATT with +60/+60 stats.
  - Rates: 60 for most (0.024% a kill), 15 for AbsoLab (0.006%, 41 normal mobs). Cygnus and Magnus carry one at
    3000/1000 (0.9%/0.3% a kill).
  - At Grandis' lv 130+ kill rate (about 1,200 an hour) a player finds about one Jaihin/Pensalir/Utgard piece every
    3-4 hours. In Arcane (about 280 kills an hour) it is about one every 15 hours. On the 41 mobs that carry an
    AbsoLab weapon it is about one every 60 hours.
  - If that is too generous, lowering everything outclassing to 15 is one constant (`BB_RARE_CHANCE`).
- **On-par post-Big-Bang gear (614 rows).** Eclectic and Muspell armour is about classic endgame (a few stat points
  better) and drops at 800 like classic armour.
- **Mastery books (667 rows)** at classic rates (500-1500, 0.2-0.6% a kill). That is about 5 books an hour at 1,200
  kills an hour, the same as classic lv 100+ mobs. Bosses: 4 books at 20000 (Zakum's rate).
- **Attack scrolls (262 rows)** on weapons and gloves, at the classic 0.12%. Cygnus has Gloves for ATT 10% at 4% a
  kill.
- **Own ETC items feed the Master Forger's crafting.** Nimble's Master Forger (NPC 9977779, placed in the Free
  Market by migration 027) crafts Lionheart, Amaterasu, AbsoLab and Arcane Umbra gear from `scripts/json/gearSets.json`,
  `weapons.json` and `otherAccessories.json`. Each recipe takes 100 of several ETC items plus 200M-1.3B mesos. 32 of
  the ETC items in this draft (Grobbler Stalks, Speeyor coats, Dino parts, the Future mutants' feathers, the Knight
  pieces, the Pendants, the Droplet Stone...) are recipe materials that had **no source at all** before.
  - I checked all 131 recipes that use them: **none becomes fully craftable** with this draft. Each still needs other
    materials nothing drops (most often 4001890, 4033732, 4000659 and 4000736).
  - Once those get a source, these drops open crafting of Arcane Umbra and AbsoLab-tier gear. That is a separate
    decision.
  - The Dawn, Blaze, Wind and Night Stones are used by `scripts/item/dreamkey.js` (5 of each). Hedgehog Balls have an
    NPC price (235).
- **ETC left out** by the use-or-price rule (15 items, `etc-excluded.csv`):
  - **No use in scripts and no NPC price (14):** Rubble (Amethyst Tokka), Dinogoth Tail, Giant Firewood (Swollen Axe
    Stump), Mutated Tooth (Pillaging Wild Boar), Heavy Armor Fragment (both Pillaging Boars), Cursed Plank, Slate and
    Plate (the Sinister Masks), Thunder Spear (Thunder), Mouse Tail (Balloon Mouse), Red and Green Frog Legs (the
    Poison Frogs), Mohawk Feather (Delinquent Chick), Rocker Feather (Punk Chick).
  - **Post-Big-Bang system (1):** Core Blaze Tip (4032358, Blaze).
  - Never candidates (the name and description matching does not reach them, and the system filter would refuse
    them): Spell Trace (4000999/4001832), Arcane Symbols (1712xxx/1713xxx are not in the server's Character.wz at
    all), Star Force and Flame items, Nodestones and V-matrix cores, symbol upgrade
    materials and coin/mileage currencies. The post-Big-Bang gear families included (Eclectic, Muspell, Pensalir,
    Jaihin, Utgard, AbsoLab) are plain equips here: their set bonuses and Star Force do not exist in v83, so they
    count only for their base stats.
- **Boss equips** trigger the bots' rare-drop megaphone, about once every two boss kills.
- **Godly rolls.** Every mob equip can take the 1.5.2 godly roll, and that includes the post-Big-Bang gear.
- **Bots.** 3-rebirth bots already travel to Grandis and Arcane. With classic HP and these tables, bots there earn
  mesos, items and EXP under the existing per-map and daily caps (ticket 15). Bot EXP has no cap. Worth a
  `!botyield` look on TEST.

## Open questions for you

1. **EXP with the new HP.** You asked to keep EXP. With classic HP, Arcane gives 5-19x and the worst Grandis lv 200
   mobs about 70x the classic EXP per hour, for a 3-rebirth character climbing back up. Options: keep it (rebirth-3
   reward), or scale EXP down with HP (for example to 3x classic EXP per HP), which is the same override mechanism
   for EXP.
2. **The 0-EXP copies.** Ascendion, Foreberion and Embrion (8645040-42) fill 450012301-303 with 7,441 placements.
   They have 0 EXP in Mob.wz, so they give nothing, and they keep their 655-678M WZ HP (no HP row, no drop table).
   Only Embrion has an EXP-giving twin placed (8645022, in this draft). Also left alone at 0 EXP: the Erda variants and
   Arma's Follower on 450001400/450001500, the Cygnus and Resistance soldiers, Belle, Brighton, Corrupt Spirit of
   Harmony, ??? (8642091), the Cygnus Knights (Grandis 400000003) and Ariel, Solomon, Rex, Hugin, Munin (270050100).
   Give them EXP, HP and tables, despawn them, or leave them as scenery?
3. **Outclassing gear rates**: 60 (0.024%) for Jaihin/Utgard/Pensalir and 15 for AbsoLab. Keep, lower, or keep
   AbsoLab boss-only?
4. **Bosses**: are Cygnus (400000003), Magnus (401060101) and the Singularity Maker (450014250) meant to be
   farmable? Cygnus at 110M HP and Magnus at 2.1B with Zakum-sized tables.
5. **Monster Book HP**: rebuild Custom.wz with `build_book.py --hp-overrides` in the next client release, or leave
   the old HP showing?
6. **Release**: 050 is a placeholder and not in `docs/release/1.6.1.conf`. The HP table goes live with the jar, so
   when you say go, the jar and the migration should ship together (the HP table without the drops makes these
   mobs easy kills that drop little).

## What was run

- `tools/economy/bb_inventory.py` and `gen_bb_drops.py` against the server WZ (`/root/work/bake/wz`) and a SELECT
  export of TEST `drop_data` (2026-10-28). Regenerating gives byte-identical files.
- `BbDropTablesDataTest`: every item exists in Item.wz or Character.wz, has a name, and is not cash, quest,
  untradeable, account-bound or one-of-a-kind; every ETC item has a use in `scripts/` or an NPC price; no card
  items; every mob is in Mob.wz; one meso row per mob; no Mirror World mob.
- `BbDropTablesMigrationIT` on a throwaway MySQL 8.4 container: apply twice, roll back twice, apply again. Card rows,
  an older row and an unrelated mob are unchanged.
- `MobHpOverridesTest`: LifeFactory gives Sun Spirit its table HP (507,000 instead of 132,308,000) with EXP and level
  kept; a mob with no row keeps its WZ HP; the table matches `hp.csv`; bad rows are refused.
- The full suite, `mvn -q -o test`.
- Not tested on TEST or in a real client.
