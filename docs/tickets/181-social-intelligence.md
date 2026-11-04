# 181: Persistent NPC social intelligence

Status lives in the release book (this file's "Status:" line is not kept current). Owner direction 2026-11-03.
Number 181 was the next free in `release-1.6.4` on 2026-11-03; the Coordinator may renumber.

## Goal

Roster bots (the "NPCs" of the brief; script NPCs are not agents) get a persistent social life: continuous traits,
decaying emotions, needs, directed multi-axis relationships, structured memory with no omniscience, a registry of social
actions with observable outcomes, routines and groups, and a ~20-bot Henesys pilot, all by extending `BotLifeSystem`,
`BotBonds`, `BotMemory`, `BotTalk` and the LLM dialogue path. No parallel framework, no new client assets, no protocol
change.

## Design and audit

`docs/design/social-intelligence-audit.md`: architecture map, gap by phase, integration design, staged plan.

## Decisions (owner, 2026-11-03)

- Gifts are non-economic: an affinity effect, gear that fits the receiver (by job and level), gear that fits every job
  (earrings and similar), or a usable. Nothing is minted; the item comes from the giver.
- The daily-life brain, bot talk and crews are ON by default (`-D...=false` is the rollback).
- Any roster bot can switch between training, social and town modes while online.

## Stages and state

| Stage | What | State |
|---|---|---|
| 0 | Flags on by default, `SocialSim` clock/RNG seam, `social-tick` cost phase | done, branch `164-social-core` `cea8d6671` |
| 0b | Mode switching (`ModeSwitch`, `BotRoster.resumeChoice`) | done on the branch; TEST run and an auto-QA check pending |
| 1 | `SocialMind`: traits, emotions, needs, decay, persistence | built on branch `164-social-mind` `43c1128c4` (model, persistence, event hooks; not yet read by any decision, that is Stage 4); TEST persistence check pending |
| 2 | 8-axis `Bond`/`Tie`, migration 059, background bond formation | built on branch `164-social-bonds` (`f03304844`): 8 directed axes with per-axis decay, `Stage` with hysteresis, significance eviction, background tier on unobserved maps (`-Dslums.bonds.background`), `BotBonds.axes/stage`; migration 059 not yet applied anywhere; nothing reads the axes yet (Stage 4) |
| 3 | `botmemory` extension (migration 060), relevance recall, knowledge boundaries, memory in bot-bot talk | built on branch `164-social-memory`; unit-tested, not run on TEST or in a client |
| 4 | `SocialAction` registry, missing actions, gifts seam, emotion and memory into the decision model | built on branch `164-social-acts`: 16 acts in `SocialActions` (wrapped: greet, chat, help ask and offer, invite/accept/decline, share info, rumor; built: compliment, apologize, reconcile, argue, avoid, spend time, gift through `GiftSeam`), utility from needs, emotions, traits, the directed edge, memory and the target's company, `SocialDecider` in the bot-talk tick, mind inputs in `TalkBrain`'s inputs and a small SOCIAL-need bonus in `LifeRuntime`; wired here to the other branches' work: `GIVE_GIFT` calls `BotBotGift`, `SocialIntents.performer` is `SocialActions.perform` (set at `BotTalk.start`), unwatched bots decide less often through `SocialLod`; `-Dslums.socialacts=false` is the rollback (pair it with `-Dslums.intents=false`: with acts off a promise cannot be kept); unit and 3-day 12-bot simulation tests only, not run on TEST |
| 5 | Persisted intentions: `SocialIntent`/`IntentBook`/`SocialIntents` inside `LifeMemory`, dialogue-to-intention, kept/broken consequences, commitment clause, counters, `!botwhy` line | built on branch `164-social-intents` (`81a7faa31`); unit-tested; the performer is a seam (`SocialIntents.performer`), wired to `SocialActions.perform` by Stage 4 (`164-social-acts`); TEST not run |
| 6 | Social LOD, loop breaker, metrics | 6a built on branch `164-social-observe`: `SocialBudget` loop breaker hooked into `BotTalk` (greet, chat, ask acts), social metrics in `live.json`, `/bots/{cid}` and `!botwhy` (`docs/design/inspect-api.md`); 6b social LOD planned; no migration; not run on TEST **6b:** 6b social LOD built on branch `164-social-lod` (`SocialLod`: ACTIVE/BACKGROUND/DORMANT, 30 s hold, 5-minute background maintenance, one bounded login catch-up; `-Dslums.sociallod=false` is the rollback). Loop breaker and dashboard metrics still planned; TEST run pending |
| 7 | Henesys pilot and live verification | built on branch `164-social-pilot`: `SocialPilot` (20 roster bots chosen by hash, a director hold across their play window, Henesys placement and hang-out pull, `stats()`), `SocialRoutine` (a soft daily schedule as bias into `ModeSwitch.decide`, the gathering place and the hang-out pull), `SocialGroups` (mutual-friend components, group mates and events as bias, bounded conflict between groups, reputation read); `SocialPilotSimulationTest` runs 20 bots for 7 simulated days with visits and a restart and asserts the ten pilot criteria in a fake world; TEST run and a client check pending (`audit` section 10 says which criteria need them) |

## Acceptance (from the brief)

Emergent behaviour from persistent state and decisions, shown by execution: unit tests (traits, decay, bonds, memory,
action scoring, knowledge boundaries), integration tests (dialogue to intention, decision model, movement to
interaction, persistence), a multi-day town simulation, and a client-visible check in Henesys. A stage is not done until
it has run; the audit file says what has and has not.

## Switches

`slums.botlife.enabled`, `slums.botlife.execute`, `slums.bottalk`, `slums.crews`, `slums.modeswitch`, `slums.socialbudget`, `slums.socialacts`, `slums.intents`, `slums.sociallod`, `slums.socialpilot`, `slums.socialroutine`, `slums.socialgroups`:
all `-D` JVM properties, on unless set to `false`. None is a `config.yaml` key. The pilot also takes `slums.socialpilot.size` (20) and `slums.socialpilot.map` (100000000, Henesys); `slums.socialroutine.zone` overrides the routine's time zone (default `BOT_ROSTER_TIMEZONE`).

Gifts (`BotBotGift`): `slums.botgifts` (on unless `false`), and the limits
`slums.botgifts.giver.cooldownMin` (360), `slums.botgifts.pair.cooldownMin` (1440), `slums.botgifts.receiver.perDay`
(3), `slums.botgifts.perHour` (15), `slums.botgifts.valueCap` (100000 mesos at NPC prices).
