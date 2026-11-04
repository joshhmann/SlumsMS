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
| 1 | `SocialMind`: traits, emotions, needs, decay, persistence | planned |
| 2 | 8-axis `Bond`/`Tie`, migration 059, background bond formation | planned |
| 3 | `botmemory` extension, relevance recall, knowledge boundaries | planned |
| 4 | `SocialAction` registry, missing actions, gifts, emotion and memory into the decision model | planned |
| 5 | Persisted intentions; memory in bot-bot prompts | planned |
| 6 | Social LOD, loop breaker, metrics | planned |
| 7 | Henesys pilot and live verification | planned |

## Acceptance (from the brief)

Emergent behaviour from persistent state and decisions, shown by execution: unit tests (traits, decay, bonds, memory,
action scoring, knowledge boundaries), integration tests (dialogue to intention, decision model, movement to
interaction, persistence), a multi-day town simulation, and a client-visible check in Henesys. A stage is not done until
it has run; the audit file says what has and has not.

## Switches

`slums.botlife.enabled`, `slums.botlife.execute`, `slums.bottalk`, `slums.crews`, `slums.modeswitch`: all `-D` JVM
properties, on unless set to `false`. None is a `config.yaml` key.
