# 07: Real combat damage from stats, gear, skills and buffs

**Area:** Real bots  
**Depends on:** 05  
**Status:** done 2026-10-19 on branch `combat` (from `bots-integration`), not yet merged

## What

A server-side formula (after nutnnut's CombatFormulaProvider) behind our attack adapter; offscreen LOD uses averaged numbers. PacketCreator and AbstractDealDamageHandler stay untouched.

## Done when

Bot damage tracks its gear and level; the packet bytes are unchanged; tests.

## Result

- **Formula (`BotCombatFormula`, pure arithmetic):**
  - Physical ceiling = `Character.calculateMaxBaseDamage(total WATK, weapon)` x skill damage %, exactly what
    `parseDamage` checks. Floor = the client's: main stat x 0.9 x mastery + secondary.
  - Spells: `((MATK^2/1000 + MATK)/30 + INT/200)` x spell MAD, the floor using the spell's mastery, times Element
    Amplification. Always at or under `parseDamage`'s magic ceiling.
  - Own formulas, keyed by skill id: Lucky Seven / Triple Throw (MapleRoot's Quadruple Throw keeps 4121007),
    Dragon Roar, Heal against the undead, a ranged weapon swung in melee, fixed damage. Unarmed pirates as in
    `calculateMaxBaseDamage`.
  - Then the buffs' damage bonus (`parseDamage`'s bonusDmgBuff), Berserk under its HP line, combo finishers by orbs.
  - Against the mob: element (weak x1.5, strong x0.5, immune 1), WK/Paladin charge vs a weak mob, then weapon or
    magic defence and the level gap (-1% a level above the bot). Never under 1.
  - Crits: Critical Shot / Throw / Punch, plus Sharp Eyes and MapleRoot's SHARP_EYES-stat buffs (Fire Rush, Jolly
    Roger, Cygnus Sharp Eyes) - spells crit only from those. A crit adds its bonus to the skill's %.
  - Shadow Partner: the second half of the lines repeats the first at the skill's y % (50), crit and all.
  - Lines stay 32-bit (the packet), clamped so a crit still encodes negative; mob HP is 64-bit as before.
- **The bot's numbers (`BotDamage`):** the server's totals (`getTotalStr/Watk/Magic`, gear included) plus the bot's
  shown buffs it really holds (bot buffs are not registered): Rage, Meditation, Bless, Enrage, Concentrate, Infinity,
  Maple Warrior (% of base stats), Combo's and the charges' damage. Weapon mastery from the WZ `mastery` grade of
  the mastery skill it holds (Bow Expert / Marksman Boost / High Mastery beat it). Ammo: until bots carry real ammo
  (ticket 08) the projectile they are drawn throwing adds its attack.
- **Driver:** `BotAttackDriver` rolls each mob's lines through one `BotDamage.Swing`. `BotDamageModel`, the tier
  bands and the per-class crit table are gone. Packet building is unchanged (`PacketCreator`,
  `AbstractDealDamageHandler`, `BotAttackEffects` untouched); immune mobs take 1 a line, as `parseDamage` makes it.
- **Offscreen (`OffscreenCombat`):** the same swings, averaged, against the map's median-level mob: swings to kill
  at the profile cooldown plus 1.5 s walking, an AoE catching up to 3, capped at the old 30 kills/min. EXP per kill
  = the mob's EXP x the bot's EXP rate x the map multiplier, as an observed solo kill. Cached per bot until its
  level, job, map, weapon or totals change. Maps whose mob cannot be built keep the old abstract pace.
  - **Offscreen EXP pace (owner's decision, 2026-10-19):** offscreen kills are worth a third of that real EXP, "so
    bots climb more like a casual player and the world stays spread across levels longer"
    (`OffscreenCombat.DEFAULT_OFFSCREEN_EXP_PACE`; override with `-Dsolomapling.offscreenExpPace=<factor>`, 1 = full
    real pace, not a config.yaml key). Only the EXP a kill is worth is scaled: the kill rate and its 30/min cap are
    unchanged, `TrainingBot` still carries the leftover EXP fraction between ticks, observed kills give real EXP, and
    maps without a buildable mob keep the old abstract pace unscaled.
- `StatEffect` now reads the WZ `mastery` grade and exposes `prop` (no behaviour change for players).
- **Tests:** 2538 run, 0 failures (26 new: `BotCombatFormulaTest`, `BotDamageTest`). Ten representative builds
  (beginner to Shadower, every weapon class) match `calculateMaxBaseDamage` x skill % to the point; spells stay
  under the magic ceiling; crits stay inside `parseDamage`'s crit allowance; a better weapon raises the ceiling by
  exactly the server's formula; shown buffs count only when held.
- **Headless check on 164** (`/opt/mapleroot-dev/real-stats-test`, ports 8499/8105, logs kept there as
  `combat-offscreen-run.log` and `combat-observed-run.log`; the jar was put back afterwards):
  - Offscreen: 32 bots estimated, 0 errors. E.g. lv 7 beginner 13.7 kills/min (5-37 a hit on 80 HP mobs), lv 32
    assassin 26.3/min, lv 77 ranger 28/min at 220 EXP a kill, lv 78 Hermit on lv 87 mobs at the 30 cap.
  - Observed (a temporary, uncommitted `-Dslums.verify.observeAll` switch, since no headless player exists): average
    damage a swing climbs with level and class - lv 8 beginner 20, lv 26 thief 1.2k, lv 59 fighter 4k, lv 76
    priest 10.6k, lv 89 dragon knight 17.7k, lv 135 shadower 187k (multi-mob swings). Observed kill rates ran
    6-58 kills/min. For the same bot and map they were within about 2x of the offscreen estimate: lower where the
    map ran out of mobs (lv 78 Hermit: 16 against 30), higher on easy maps (lv 32 Assassin: 42 against 26). 0 errors.
- **Open issues:**
  - Cosmic does not load mob avoidability or accuracy, so bots never miss.
  - Combo Attack's per-orb damage and Aran's combo crit are not modelled (only Combo's flat damage and finishers).
  - Offscreen EXP is real mob EXP at a third pace: mid-level bots level about 1-1.3x the old mob-level-per-kill
    pace offscreen (3-4x before the owner's pace factor). The 30 kills/min cap is conservative against what bots reach on screen.
  - The crit encoding (`encodeCritLine` / `decodeDamageLine`, pre-existing) drops 1 HP per crit line.
  - Shown buffs are taken as always up. Some bots fight with weak kits (a lv 95 Crusader on Power Strike with no
    weapon mastery); the formula is honest about it, the attack table is where to fix it.
  - MapleRoot's buffed skill data (Magic Claw MAD 140) makes low-level mages hit hard - the same as players.
