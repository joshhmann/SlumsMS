Every player-job skill in MapleRoot's server data - `wz/Skill.wz/<job>.img.xml` for the skills and
`wz/String.wz/Skill.img.xml` for their names, what `SkillFactory` loads - compared with upstream Cosmic's (stock
v83). The bots' builds are made from this list (`BotSkillPlan`, `BotAttackConfig`, `BotBuffConfig`); the tests check
every id they use against it (`src/test/resources/soloMapling/mapleroot-skills.tsv`, written by the same tool).

## What MapleRoot changed

- **Removed:** every booster (weapon, spell, Cygnus and Aran), all second warrior weapon forms (axe, blunt weapon,
  pole-arm: masteries, final attacks, Panic/Coma, the charges, Crusher, Dragon Fury, Divine Charge), Slow, Big Bang,
  Heaven's Hammer, Steal, Taunt, Ninja Ambush, Assassinate, Energy Drain, Transformation, Shockwave, Super
  Transformation, Demolition, Snatch, Barrage, Hypnotize, Maker, the Wind Archer's Eagle Eye / Wind Piercing / Wind
  Shot, the Night Walker's Vanish and the Thunder Breaker's Transformation / Shockwave. Warriors are therefore sword
  (Fighter, Page) or spear (Spearman) only.
- **Same id, new skill:** 1121008 Raging Blow (was Brandish; 5 hits on 3 mobs), 4121007 Quadruple Throw, 4111005
  Shadow Slash, 2111004 / 2211004 / 2311005 / 12111002 Fire Rush (was Seal / Doom; a one-level critical-rate buff),
  2121003 Fire Haze, 2221003 Glacier, 5121008 and 5221010 Hero's Will, 1120003 Advanced Combo.
- **Added:** custom 4th-job skills for every Explorer 4th job but the Bishop (whose only addition is the passive
  Buff Master), a 4th job for each Cygnus Knight (x12), a 5th tier for every class (x13, four level-1 skills each),
  beginner skills 1050-1054, and the jobs 700 Super Beginner and 710 Synth Master.

## How a player gets each tier

From `scripts/npc/9010003.js` (the Job Master) and `scripts/npc/2084000.js`:

| Tier | Requirement | What comes with it |
| --- | --- | --- |
| 1st job | level 10 (magician 8); Cygnus and Aran the same | weapons and ammo for the class |
| 2nd job | level 30 | - |
| 3rd job | level 70 | Hermit and 3rd-job Night Walker: Shadow Partner at level 30 |
| 4th job (x12) | level 120, for every class including Cygnus Knights and Aran | Bishop: Bahamut master level 30 |
| 4th-job package (NPC 2084000) | 10,000,000 mesos (100,000,000 for a beginner), any job, any level | master level 10 on the skill-book 4th-job skills (25 for the Beholder passives); Hero's Will, the custom 4th-job skills and the Cygnus 4th-job skills at level 1, master 5 (Dawn Warrior Power Stance master 30); Echo of Hero; Power Drill at level 1, master 10; Shadow Partner at 30 |
| 5th tier (x13) | a 4th job, rebirth 3 or more and item 4036541 (used up) | the tier's four skills at level 1 |
| Super Beginner (700) | a beginner picks it at level 10 (Chain Attack, and the job stays 0); at level 120 the Job Master makes a Chain Attack holder a 700 | Chain Attack; the 2084000 package also teaches the 700 skills (7001000-7001006) to anyone who buys it |
| Synth Master (710) | a 700 at level 120 with rebirth 3 or more and item 4036541 (used up) | eight Synth Master skills at level 1 |

Aran's 1st job also opens (level 0, with master levels) Combo Ability, Full Swing, Polearm Mastery, Final Charge,
Combo Smash and Combo Drain.

## What the bots do with it

- Bots are built for the regular classes up to the 4th job (Cygnus Knights included), spend 3 SP a level as the
  server grants it, and take the Job Master's advancement grants for free.
- On reaching the 4th job a bot gets its own job's share of the 2084000 package. Bots hold real mesos: one with
  10,000,000 pays for it; one without still gets it and the price is not collected (left to the economy ticket, 15).
- The package's skill-book master levels are not modelled: a bot's 4th-job master level is its plan target, as if
  it had read the mastery books.
- **Not built (future work):** the 5th tier (x13), Super Beginner (700) and Synth Master (710) - they need rebirths
  and item 4036541. A bot of one of those jobs is left exactly as it is.
