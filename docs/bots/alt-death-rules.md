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

Tests as built, each failing before the change (the new class is new, so for `CompanionBossReviveTest` "before" means
the class did not exist; every other test below fails on the base branch and passes after):

- `src/test/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionBossReviveTest.java` (9)
  - `theFirstDeathWaitsTenSecondsAndTheSecondTwenty`: nothing at +9 999 ms, up (and `BotDeaths.revived`) at +10 000 ms,
    20 s on the second death.
  - `theWaitingDoublesWithEachDeathAndStopsAtTwoMinutes`: 10, 20, 40, 80, 120, 120 s.
  - `aNewRunStartsTheWaitingOverAtTenSeconds`: after `ExpeditionBotsFixture.Joined.release()` (the expedition's dispose)
    and a new instance, the next death waits 10 s again.
  - `whileItWaitsItStaysInTheRunAndGetsUpOnItsOwnersMap`: while dead, never `respawn`, never `unregisterPlayer`/
    `playerDisconnected`, the expedition still has it; on the revive it is `changeMap`ped to the owner's map (it fell on
    the boss's map), gets `applyHpMpChange(0, maxHp, 0)`, broadcasts stance 4, and comes out of `BotDeaths.isDead`.
  - `anInstantReviveCostsAThousandMesosALevelAndDoublesWithinTheRun`: Lv 120 charges 120,000 (told with the price) then
    240,000 in one run, and 120,000 again after the run is released.
  - `tooFewMesosChargesNothingAndItStaysDown`: no `gainMeso`, no `applyHpMpChange`, still dead and still waiting.
  - `afterTheReviveItRestsUntilWholeOrTheLimit`: resting right after the revive, still resting at half HP/MP, done at
    100%, and done when `REST_MAX_MS` has passed; the rest starts over with the next revive.
  - `aCompanionThatDiedOutsideARunKeepsItsOldPath`: on a map with a boss on it but with no instance, `gate` says no, no
    line, no revive, the caller's own path keeps the death.
  - `aRegularIsLeftAloneByDefaultAndWaitsUnderTheSwitch`: with the shipped switches a regular's `gate` is false and its
    leader is told nothing; with `-Dslums.companion.deathrules.regulars=true` it waits and its leader is told.
- `src/test/java/soloMapling/companions/OwnedAltCompanionTest.java` (the three added to the existing 21)
  - `anAltDownInABossRunWaitsInTheInstanceAndGetsUpBesideItsOwner`: through `OwnedAltCompanion.tick`, dead in an
    expedition instance: no `respawn`, no `unregisterPlayer`, no `sendHomeAfterDeath`, the owner reads "Second is down,
    back in 10s (or @botme revive Second for 120,000 mesos)", and at +10 000 ms "Second is back up".
  - `aRevivedAltRestsBeforeItFollowsOrFightsAgain`: the tick holds the alt after the revive (`s.doing` "resting after a
    revive", no follow, no attack) and lets it go once HP and MP are whole.
  - `anAltDeadOnABossMapOutsideARunStillGoesToTown`: rule 1 with a boss on the map but no run: town, then home.
- `src/test/java/soloMapling/ArtificialPlayer/BotTypes/FollowerBotDeathRulesTest.java` (2)
  - `withTheSwitchesTheServerShipsItsTickIsTodaysTick`: a dead regular under the default switches still takes today's
    follow tick (`GCMovement.follow` called, no revive, still dead).
  - `withTheRegularsSwitchOnItWaitsInsteadOfFollowing`: with `-Dslums.companion.deathrules.regulars=true` it waits
    (`CompanionBossRevive.waiting`), no follow, not fighting, still dead, and its leader is told.
- `src/test/java/soloMapling/ArtificialPlayer/BotPartySystem/BotMeReviveCommandTest.java` (4): the command's answers -
  revived and charged to the player typing it (120,000 at Lv 120) with the price in the line; too few mesos -> the price
  line, no charge, still down; a name that is not one of your down companions -> "There is nobody called ..."; a
  regular down in a run, with the regulars' switch on, is revived by `@botme revive <name>` and paid by its leader
  (70,000 at Lv 70).

Full suite: `mvn -q -o test -Dwz-path=/root/work/SlumsMS-152-maps/wz`, counts from
`target/surefire-reports/TEST-*.xml`.

Untestable here: the real client's view of the revived alt (the stance broadcast and the map it appears on) and a real
Zakum run on TEST; both need the client, noted in the hand-back.
