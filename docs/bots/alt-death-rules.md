# alt-death-rules (1.6.7): expected outcome

Card: alt-revive-respawn, boss-run death and revive. Written before the code (1.6.6 pilot rule).

Switch: `PartyCompanionPolicy.Feature.DEATH_RULES` (key `deathrules`). Owned alts are on by default
(`-Dslums.companion.deathrules.alts`, default true); party regulars are off by default
(`-Dslums.companion.deathrules.regulars`, default false), so a regular fights and dies exactly as it does today until an
owner turns the switch on.

## Why

Today a companion that dies is dead for good until someone rescues it. An owned alt that dies anywhere - including
inside the owner's boss run - is respawned in the instance's exit town, unregistered from the event and sent home
(`OwnedAltCompanion.afterDeath`), and the owner has to call it again with `@botme <name>`. A party regular in an
expedition (`FollowerBot`) has no after-death path at all: it lies where it fell. In a boss run that is the wrong shape:
the run goes on around it, the owner is a player down for the rest of the fight, and the only way back in is to leave the
run.

The owner's decision (8 Nov 2026) is that a companion dying **inside** a boss run waits where it fell and gets itself up
beside its owner, with the wait doubling each death in the same run, and that the owner may buy an instant revive for
mesos (the owner's own, the player typing it). Deaths **outside** a boss run stay exactly as they are.

## Expected outcome

1. **Outside a boss run nothing changes.** The alt respawns in its death town, is unregistered from the instance it was
   in (if any), is saved and sent home, and the owner reads the same line and calls it again with `@botme <name>`. A
   party regular outside a run keeps whatever path it has today.
2. **A death inside a boss run** (`ExpeditionHold.inRun(companion)` at the death, or a
   `MechanicsHold.committed` run) keeps the companion in the run: it is not unregistered from the event, not respawned
   in town, and it stays a member of the expedition. It revives **automatically**, on its own, after a wait measured
   from the death: 10 s on the 1st death in the run, then 20 s, 40 s, 80 s, capped at 120 s (the 5th and later deaths
   are 120 s). The count and the timers are per companion per run and are dropped when the run ends
   (`ExpeditionHold.release`, i.e. the expedition's dispose, and `MechanicsHold.release`).
3. **The revive is a player's revive.** It gets up where it lay, or beside its owner (leader for a regular) when the
   owner is on another map of the same instance, with the HP a Resurrection hands a player (`hpchange =
   getCurrentMaxHp()`, MP unchanged, the standing stance broadcast), and comes out of the corpse state
   (`BotDeaths.revived`).
4. **It then rests** (the rule ported from BotStory's `CompanionFieldBossRest`): no follow and no fight until its HP
   *and* MP are back to `REST_PERCENT` (100) of their maximum, or `REST_MAX_MS` (120 s) has passed since the revive. It
   drinks its own potions meanwhile through the existing `BotVitals` path (`regen` and `topUp`), so MP under 30% is
   potted up and HP/MP regenerate as they do for any bot. The sit-out rule of the source is **not** ported: a companion
   never refuses to go back into a fight.
5. **The owner may buy an instant revive**: `@botme revive <name>` while that companion waits dead in a boss run, and
   only then (not outside a run, not for a companion that is not down). The price is 10,000 x its level / 10 mesos
   (Lv 120: 120,000), doubling with each revive bought in the same run (120,000 then 240,000 then 480,000), and reset
   when the run ends. The mesos come from the **owner** - the player typing the command (for a party regular, its
   leader). If the owner cannot pay, one line states the price and nothing happens: no charge, no revive.
6. **The owner is told, once per event, in casual words, never "bot"**: on death
   `<Alt> is down, back in 10s (or @botme revive <Alt> for 120,000 mesos)`, and on revive `<Alt> is back up` (with the
   price paid when it was bought: `<Alt> is back up - 120,000 mesos.`).
7. **Leaving ends it.** If the owner leaves the run, or the run ends, while the companion is dead or resting, the
   companion leaves the instance the way it does today: dead ones are respawned in the death town, saved and sent home
   (rule 1). Rest and the counters go with the run.
8. **A party regular with the switches off is unchanged**: no death handling appears for it, it takes exactly the tick
   it took before (only the resting check is skipped). With `-Dslums.companion.deathrules.regulars=true` it follows
   rules 2 to 7, and its instant revive is paid by its leader.

## Proof

New tests, each failing before the change:

- `CompanionBossReviveTest`
  - `firstDeathInARunWaitsTenSeconds`: nothing at +9 999 ms, up (and `BotDeaths.revived`) at +10 000 ms.
  - `theWaitDoublesEachDeathAndCapsAtTwoMinutes`: 20 s on the 2nd, 80 s on the 4th, 120 s on the 5th and the 6th.
  - `aNewRunStartsAtTenSecondsAgain`: after `ExpeditionHold.release`, the next death waits 10 s.
  - `itStaysInTheInstanceAndGetsUpBesideItsOwner`: while dead the event is never unregistered and the expedition still
    has it; on revive on the owner's map it stays there, on another map of the instance it is moved to the owner's map
    at the owner's position; HP is its maximum.
  - `aBoughtReviveCostsTheOwnersMesosAndDoubles`: Lv 120 charges 120,000 then 240,000 in one run, 120,000 again in the
    next.
  - `aBoughtReviveWithoutTheMesosChangesNothing`: the owner keeps his mesos, the companion stays dead.
  - `afterAReviveItRestsUntilWholeOrTheTimeIsUp`: not resting before the revive; resting right after; done once HP and
    MP are at 100% (`REST_PERCENT`); done at once when `-Dslums.companion.rest.maxMs=0` has passed; still resting at
    50% MP before the limit.
  - `aBossRunIsAnInstanceOrAMechanicsRun`: a death on a map whose monsters include a boss is **not** a boss run (the
    rule keys on the run, not the map), a death with no instance is the old path.
- `OwnedAltCompanionTest` (existing file): `anAltThatDiesInABossRunStaysInTheRunAndGetsUpBesideItsOwner` (dead in an
  expedition instance: no `respawn`, no `sendHomeAfterDeath`, up after the wait, the owner told both lines), plus the
  two existing death tests unchanged (a death outside a run is still town then home, rule 1).
- `FollowerBotDeathRulesTest`: with the defaults a party regular takes the same tick as before (no
  `BotDeaths.readyToRespawn`, no revive, the fight path runs); with `-Dslums.companion.deathrules.regulars=true` it
  waits 10 s and gets up, and the leader's `@botme revive` charges the leader and revives it at once.
- `BotMeCommandReviveTest`: the command's three answers (revived with the price, cannot pay, not down/unknown).

Full suite: `mvn -q -o test -Dwz-path=/root/work/SlumsMS-152-maps/wz`, counts from
`target/surefire-reports/TEST-*.xml`.

Untestable here: the real client's view of the revived alt (the stance broadcast and the map it appears on) and a real
Zakum run on TEST; both need the client, noted in the hand-back.
