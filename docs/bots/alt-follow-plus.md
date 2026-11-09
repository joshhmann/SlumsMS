# Following that looks like a player (`FOLLOW_PLUS`)

Card: alt-qol-batch group C (1.6.7). Switch: `PartyCompanionPolicy.Feature.FOLLOW_PLUS`, property key
`follow`, i.e. `-Dslums.companion.follow.alts` (on by default) and `-Dslums.companion.follow.regulars`
(off by default). Nothing here runs for a bot with the switch off, so a party regular with the shipped
defaults follows exactly as it does today.

Shared layer: the following code both owned alts and party/follower regulars run. The stuck check and the
two casual lines live in `GCFollow`'s poll (the tailing loop every follower uses); the spot, climb and
line helpers are new classes under `soloMapling.ArtificialPlayer.BotPartySystem`, next to
`PartyCompanionPolicy`. The reserved-spot choice is called from `OwnedAltCompanion.keepFollowing`, the
owned-alt driver, because that is where an owned alt's stance and fight state are known.

## What is true after this

1. **Anti-stuck.** A follower that should be moving — its follow target is farther than
   `CompanionStuck.FOLLOW_FAR_PX`, it is not fighting, not holding a stance, not in the air, not on a
   rope — but has not moved more than `DRIFT_PX` in `STUCK_MS` (8 s) is put down beside its target with
   the one line `oops, coming!`. The recovery is same-map only, so the instance gate and `BotMapGates` are
   structural: nothing is snapped unless the companion and its owner are on the same `MapleMap` object
   (the same channel and the same event instance — a different map object with the same map id counts as
   elsewhere), and the cross-map half stays the existing travel plus `OwnedAltService.catchUp`, which do
   gate on the instance and on `BotMapGates`. After one recovery the
   companion is not snapped again for `CompanionStuck.COOLDOWN_MS`, and the clock restarts from the place
   it was put down, so a genuinely wedged bot gets at most one snap per cooldown instead of a teleport
   every 8 s.
2. **Reserved spot.** While the owner has stood still for `CompanionSpots.STILL_MS` (2 s) within
   `CompanionSpots.NEAR_PX` (150 px) of a portal or an NPC, each companion stops tailing and stands on its
   own slot beside the owner on the owner's foothold: slot 1 left, slot 2 right, slot 3 further left, slot
   4 further right, `SLOT_PX` (40 px) apart. No slot is on the owner, and a slot whose 40 px lands within
   `CompanionSpots.CLEAR_PX` (40 px) of the portal or NPC being kept clear is moved out to 40 px past
   that portal instead. The order is the companion's index among that owner's loaded alts by character
   id, so it is stable between ticks and two companions never choose the same spot. When the owner moves
   again the companion goes back to ordinary tailing. The slot comes from `OwnedAltCompanion.driveAll`,
   which is the only place that sees all of one owner's companions at once, so reserved spots are an
   owned-alt behaviour; party regulars (switch on) get the stuck check and the lines from the shared
   `GCFollow` poll but keep the ordinary tailing.
3. **Rope and ladder following.** A companion whose owner is on a rope or ladder, or is more than
   `CompanionClimb.CLIMB_DY` (60 px) above or below it on the same map, gets an explicit move target on
   the owner instead of the follow tail, so `BotNavigationManager` plans the CLIMB edges it already has
   (rope hop included). While it climbs, the companion is exempt from the generic stuck check, and when it
   reaches the top of the rope but the owner is on the ledge past it, `CompanionClimb` nudges one step off
   the rope head (`ROPE_EXIT_PX`) toward the owner so it does not hang at the top. If the climb has not
   closed `CompanionClimb.PROGRESS_PX` in `CLIMB_TIMEOUT_MS` (12 s), the companion falls back to the
   catch-up: the same put-beside-the-owner and the same "oops, coming!" line as (1), with the same
   cooldown.
4. **Lines.**
   - *Arrival*: when a companion and its owner first stand on the same town map (`MapleMap.isTown`, so a
     hunting field or a PQ stage says nothing) after a map change, one companion says `we're in <map>` to the
     owner — once per owner per map, and a map re-entered inside
     `CompanionLines.MAP_COOLDOWN_MS` (10 min) stays quiet. Other companions of the same owner say
     nothing.
   - *Welcome back*: when an owner has not moved for `CompanionLines.OWNER_AFK_MS` (5 min) and then moves,
     one line `welcome back, still in <map>` — once, to that owner only.
   - Both are skipped for a player with `@quiet bots` on (`BotNoiseFilter.get(charId).bots()`, the same
     value `SettingsMenu` shows), both go through the party channel when the owner is in party with the
     companion and a direct chat line otherwise, and neither ever contains the word "bot". Both are
     additionally rate limited by `CompanionLines.GAP_MS` (30 s) per owner so a portal-heavy map cannot
     produce a stream of map names.

## What existing characters keep

Nothing is removed, reset or rewritten. The change adds decisions to a following companion and adds two
chat lines; no character field, saved setting, inventory, quest state, level, stat or rebirth counter is
read for writing. An owned alt that already exists loads with exactly the data it has today and follows
exactly as today while `follow.alts=false` — the new behaviour is additive and switched off together with
the rest of the feature.

Migration: **none needed, because** no column, file or setting is added or changed; the new state
(`CompanionStuck`, `CompanionSpots`, `CompanionClimb`, `CompanionLines`) is in-memory only and rebuilt
from the live map each tick. A server restart loses only the memory of "already said hello in Henesys",
which is the wanted behaviour.

## How the tests prove it

`src/test/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionStuckTest.java`

- `stuckAfterEightSecondsSaysOopsAndPutsItBesideTheOwner` — a follower 300 px from its owner, never moving,
  at t0 and t0+8 s: no action at t0, one put-beside-owner plus one `oops, coming!` at t0+8 s.
- `fightingOrHoldingAStanceIsNotStuck` — the same silence for 20 s with "fighting/stance/climbing/airborne"
  true, which is the single `busy` verdict `GCFollow` passes (`GCMovement.fighting`, `isClimbing`,
  `isAirborne`, and a `CompanionStance` that is not `FOLLOW`).
- `anInstanceTheOwnerIsNotInNeverTeleports` — the companion on a different map object (another channel or
  another event instance, same map id): 20 s of nothing, no snap, no line. The cross-map recovery is the
  travel and the catch-up, not this class.
- `oneRecoveryThenTheCooldown` — a second 8 s window inside `COOLDOWN_MS` does nothing; after the cooldown
  it fires again.
- `aRegularIsUnchangedWithTheDefaults` / `aRegularFollowsTheSwitch` — the default `regulars=false` keeps a
  non-alt bot silent, `-Dslums.companion.follow.regulars=true` makes it behave like the alt.

`src/test/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSpotsTest.java`

- `twoCompanionsGetDistinctSpotsBesideTheOwner` — slots 1 and 2 are on opposite sides, the same foothold,
  neither on the owner, both inside `NEAR_PX`, and a portal within `CLEAR_PX` of a slot pushes it out.
- `aRegularIsUnchangedWithTheDefaults` — no spot for a regular with the shipped defaults.

`src/test/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionClimbTest.java`

- `aFollowTargetOnARopePlatformChoosesAClimb` — the owner 200 px above on the same map gives the companion a
  move target on the owner (the climb entry the nav layer needs) rather than "stand at the bottom".
- `noProgressTimesOutToTheCatchUp` — after `CLIMB_TIMEOUT_MS` without closing the gap, the catch-up runs.
- `theRopeTopNudgeStepsOffTheHead` — at the rope head with the owner on the ledge past it, the nudge target
  is past the head, not on it.

`src/test/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionLinesTest.java`

- `theArrivalLineIsSaidOncePerTown` — two companions, two ticks on Henesys: one line total; a third tick on
  the same map adds nothing, and the text contains no "bot".
- `theWelcomeBackLineComesOnce` — 5 minutes of an unchanged owner, then a move: one line, the second move
  says nothing; the text contains no "bot".
- `aShortPauseIsNotAnAbsence` — 1 s short of `OWNER_AFK_MS` is not a return, so no welcome-back line.
- `quietBotsSilencesBoth` — with `BotNoiseFilter.get(id).bots()` true (`@quiet bots`), neither line is sent.
- `aRegularIsUnchangedWithTheDefaults` — silent for a regular until `-Dslums.companion.follow.regulars=true`.

Every one of those fails on `release-1.6.7 @ 536f197e0` (the classes and the `GCMovement.setDownBeside` hook do
not exist, so the four test classes do not compile against it) and all 20 pass after this change.

## Switches, the watcher gate and QA

- `-Dslums.companion.follow.alts` (default on) and `-Dslums.companion.follow.regulars` (default off);
  `Feature.FOLLOW_PLUS` reads them through `PartyCompanionPolicy.on`.
- The put-beside is `GCMovement.setDownBeside`, which deliberately snaps even though the owner is a real
  player standing on the map: it is the owner's own follow command, the alternative would be the companion
  never catching up. `WatchGate.watched` still sees it and counts it (`noteSnappedWhileWatched`,
  `!qa run lk-1`), so the "teleports in front of a player" row stays honest.
- QA: the behaviour is visible in `!qa` as the companion arriving next to its owner after standing 8 s
  away (`!qa run lk-1` covers the reposition), and the two lines appear in the owner's chat. No QA class was
  touched by this card.

## Provenance

Ported shapes only, no verbatim code: the stuck thresholds and the "only when it should be moving" gate
from `cosmic_agents` `AgentStuckDetectionService`, the reserved-spot idea from `BotStory`
`CompanionFollowGuard.waitsAt`, the arrival and welcome-back lines from `BotStory`'s
`AgentTownLifeDialogueReactionService` / `AgentChatWelcomeBackFlow`, and the rope-exit nudge from
`BotStory` `AgentNavigationTargetService.recentExitNudgeTarget`. See `docs/PROVENANCE.md`.
