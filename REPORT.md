# Boss lane, ticket 130 - report (branch `164-bossfight`, 1.6.4)

Worktree `/root/work/164-bossfight`, branch `164-bossfight` off `origin/release-1.6.4`. Nothing pushed, nothing merged,
live server untouched. `GuildBossRuns` is untouched on purpose: its roll with the bots logged out is a different path
and stays one (see `MechanicsRuns.onInstanceStarted`'s comment and the phase A1 section in
`docs/tickets/130-bots-play-mechanics.md`).

The lane made real expeditions act: the death count, the two fixes that stop a bot standing still, a Mechanics run for a
real expedition, a `boss-phases` solver with Zakum and Horntail rows, plus a damage floor and a per-stage clock.

## Results, item by item

| Item | Result |
| --- | --- |
| Phase 1, the death count (BotStory `ExpeditionDeathCount` port) | Done. One counter per instance, charged only by humans, the last death ends the run (everyone out, disposed, a party line). Switches off by default. |
| Fix 1, an owned alt in an instance | Done. The 650x250 px owner box stays exactly as it is outside an instance; inside one, an alt eligible by engagement faces the party's fight. |
| Fix 2, a follower and an unreachable boss part | Done. A boss part the bot can neither swing at from where it stands nor walk to is never chosen; ordinary mobs pay nothing. |
| Phase A1, the seam | Done. `EventManager.startInstance` calls `MechanicsRuns.onInstanceStarted(eim, true)` after `eim.startEvent()`; a `kind: BOSS` row starts a run owned by nothing (the script owns the instance), a row that does not exist is one log line. |
| Phase A2, `boss-phases` + Zakum | Done. Semantics of BotStory's `BossTargetRules`/`AgentBossPriorityFight`, not its classes: `WAITING_FOR_SUMMON` -> eight arms before the body -> `defeatedBoss=1` clear. Widened `SolverStep.Fight` to an ordered candidate list and `StageView.cleared()` to value equality. |
| Phase B, Horntail | Done. Three rooms, each clearing on the script's own property (`defeatedHead` 1, 2, `defeatedBoss` 1), parts before body, corpse parts and the intro never a target. Rooms are entered by map id through a new `SolverStep.WarpTo`, because the door's WZ portal name is nowhere in our data. Adds are not modelled: nothing in our scripts spawns `8810019-8820023`. |
| Items 1-2 of the final round | The damage floor (`BotCombatFormula.estimateMinimumDamage`, `BotSkillChoice` floor rule, off by default) and the per-stage boss clock (each room's own `timeLimitMinutes` from its own start). |

## Commits

|
| --- |
| `b5784e7f2` expedition death count (BotStory port, off by default) |
| `a338ed10d` fix 1: an owned alt in an event instance fights the party's fight |
| `e5d469302` fix 2: a follower does not choose a boss part it can never hit |
| `a888fe6bd` fix 2b: build the swing profile once per tick, not per part |
| `9dd2e171a` phase A1: a real expedition arms a Mechanics run for its bots |
| `4d5acbffb` phase A2: a `boss-phases` solver and Zakum's arms before its body |
| `e57591229` the jar-loading test knows both content rows |
| `ff8c29250` zakum.yaml settles `exitMap` and `minPlayers` |
| `0e95aab62` phase B: Horntail, three rooms, entered by map id |
| `43afaa8ad` item 1: the damage floor decides the attack on a high-WDEF target |
| `e3bd04adf` item 2: a boss run's time budget is per stage |

## Switches (every one, with its default)

| Switch | Default | Why |
| --- | --- | --- |
| `-Dslums.mechanics` | off | the existing Mechanics gate; KPQ rows still only start through it |
| `-Dslums.mechanics.boss` | **off** | the only gate a `kind: BOSS` row has. Off means a real expedition starts no run and behaves exactly as it does today |
| `-Dslums.bossfight.deathcount` | **off** | arms the per-instance death count; off means no counter is even armed |
| `-Dslums.bossfight.deathcount.cap` | 10 | the party's deaths, with an optional per-boss override (`....cap.zakum`, `....cap.horntail`) |
| `-Dslums.altfight.instanceleash` | **on** | fix 1. On because the change only exists so alts act inside an instance and both controls are pinned by tests; off restores the 650x250 px box everywhere |
| `-Dslums.bossfight.reach` | **on** | fix 2. On because the measurement it adds is skippable by construction: the reach check runs only for `m.isBoss()` / `MapleMap.hasBossMechanics(id)`, and `anOrdinaryMobNeverPaysTheWalkCost` asserts zero `BotAttackDriver.pathCost` calls for an ordinary mob |
| `-Dslums.bossfight.damagefloor` | **off** | item 1. Off because it changes which attack a bot throws, and the owner asked for the conservative default; nothing is measured while it is off |
| `-Dslums.mechanics.dir`, `-Dslums.mechanics.lobby` | as before | unchanged by this lane |

## Tests added (all green)

| Test class | Count | What it pins |
| --- | --- | --- |
| `ExpeditionDeathCountTest` | 4 | the cap arithmetic, one end, humans only, the switch |
| `ExpeditionDeathCountRunTest` | 4 | the live seam: a human death spends, a bot's does not, the last one ends and disposes |
| `OwnedAltInstanceFightTest` | 5 | the arm faced and targeted in an instance, an unengaged far mob refused, the box outside, the switch |
| `FollowerFightReachTest` | 3 | an unreachable part skipped, a reachable part kept, an ordinary mob paying nothing |
| `BossRunSeamTest` | 3 | the expedition seam on, off, and a boss with no row |
| `BossPhasesSolverTest` | 9 | waits for the summon, eight arms, the last arm, the body, the fake body, the clear, the setup 0, a missing kill target, the shipped row |
| `HorntailPhasesTest` | 9 | empty room before the reactor, parts after it, parts before body, corpse parts never, both doors, a member left behind, the win, the shipped row |
| `BossStageBudgetTest` | 3 | a late body room is not cut off, its own hour ends the run, a stage limit applies from the stage's start |
| `BotSkillChoiceFloorTest` | 6 | the flip on a boss, the switch off, a hurtable boss, a pack unaffected, an ordinary mob at the floor, the formula's floor |

Full suite after both items: `mvn -o test` -> `Tests run: 8407, Failures: 0, Errors: 0, Skipped: 137`, BUILD SUCCESS
(`/tmp/final.log`, exit 0). The one flake this lane hit, `OpenAICompatibleBrainTest.queuedCallsPastTheDeadlineFallBackInsteadOfWaiting`
under load, passes alone and did not recur.

## Auto-QA proof

`tools/ops/autoqa-proof` (uncommitted/checked-out changes) reports 14 of 186 checks for the files this lane touches:

```
t-at-1 t-bk-1 t-bk-3 t-cb-1 t-cb-2 t-in-3 t-mx-1 t-mx-2 t-mx-3 t-mx-4 t-mx-5 t-mx-6 t-su-1 t-su-3
```

`t-mx-1..6` need `-Dslums.mechanics=true` on the server (the KPQ scenarios SKIP otherwise). Run:
`tools/ops/autoqa-run t-at-1,t-bk-1,t-bk-3,t-cb-1,t-cb-2,t-in-3,t-mx-1,t-mx-2,t-mx-3,t-mx-4,t-mx-5,t-mx-6,t-su-1,t-su-3`
No auto-QA check covers a boss room yet: the suite has no Zakum or Horntail scenario.

## Release-book rows

| Row | Text |
| --- | --- |
| bl-1 | Boss lane: a real expedition (Zakum, Horntail) can arm a bot party; **off** (`-Dslums.mechanics.boss`), so nothing in the release changes until the owner flips it |
| bl-2 | Expedition death count: **off** (`-Dslums.bossfight.deathcount`, cap 10) |
| bl-3 | An owned alt inside an event instance fights the party's fight: **on** by default (`-Dslums.altfight.instanceleash`) |
| bl-4 | A follower no longer walks at a boss part it can never hit: **on** by default (`-Dslums.bossfight.reach`) |
| bl-5 | Damage floor on a high-defence target: **off** (`-Dslums.bossfight.damagefloor`) |
| bl-6 | Boss rooms keep their own clock (Horntail 120/120/60 min): data, active only with bl-1 |

## Owner questions

1. **A human death unregisters.** `ZakumBattle.js`/`HorntailBattle.js` unregister a dead member; `Orchestrator.memberLost`
   aborts the run when the party drops below the row's `minPlayers` (6). A party that loses its leader thus ends its run.
   Should a boss run instead pause for revives (the scripts do have `playerRevive`)?
2. **Alts inside an instance is now on by default.** It changes behaviour on a path that already worked (an alt in any
   event instance: KPQ, Dojo, a boss room). It was asked for as a fix rather than a feature, and the open-world box is
   untouched, but if you want it off for 1.6.4 the switch is one `System.setProperty` away.
3. **Kill target `8800002`.** Zakum's `defeatedBoss=1` is set when `8800002` dies, and nothing in our scripts or server
   spawns it - it is the map's own WZ life. If it is ever missing, the run abandons with a reason instead of spinning.
   Worth one TEST run to confirm the map still carries it.
4. **`at:` bands for boss parts** are supported end to end and unused, because no arm coordinates exist in our WZ map
   (only a filler mob and the reactor). BotStory has Horntail anchors only. Do we want fabricated anchors, or no bands?
5. **Horntail adds** (`8810019-8820023`) exist in BotStory's catalog and in no script of ours. Confirm they are not
   spawned by a script we did not read before anyone ports them.

## Risks

- Nothing in this lane has been run live. Both boss rows are gated off; a bot has never stood in 280030000 or 240060200
  on TEST (the only Zakum QA run on TEST stops at join: `logs/qa/ex-1-20261102T061732Z.log`).
- The kill target dependency above (Zakum `8800002`, Horntail `8810026`/`8810018` as the map's own life via
  `MapleMap.spawnHorntailOnGroundBelow`).
- `WarpTo` warps into the event's own map instance by id; a bot standing on a map the event never registered cannot be
  fixed by it (one log line, the bot is left).
- The damage floor's cost is only paid with the switch on, but it builds two swing profiles per attack tick then; if the
  owner turns it on, a CPU check on TEST with a busy bot population is worth doing.
- Per-stage budgets are per row, so a `kind: BOSS` row with several stages and no per-stage `timeLimitMinutes` silently
  inherits the row's minutes for each stage - that is the documented fallback, not a guess.
- `Stage` grew two fields this lane (`summonProperty`, `killTarget`) and then a third (`timeLimitMinutes`); every YAML
  and test constructing it was updated, and the jar-only content path is tested by `MechanicsDataFilesTest`.

## Reproducing the checks

```
cd /root/work/164-bossfight
mvn -q -o test -Dtest='soloMapling.mechanics.*Test,MechanicsDataFilesTest,BotSkillChoice*Test,BotTypes.*Test'
mvn -o test                     # full suite
tools/ops/autoqa-proof          # the ids above, for the files this branch touches
```
