# Boss lane, ticket 130 - report (branch `164-bossfight`, 1.6.4)

The `ex-2` QA scenario that proves a fight happens (and what it logs) has its own report: `REPORT-ex2.md`; it
ships on this branch because this is the one branch that both has the boss switches and can run it.

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
