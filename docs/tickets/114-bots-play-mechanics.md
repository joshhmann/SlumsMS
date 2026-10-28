# 114: Bots play the mechanics (1.7.0 headline)

**Area:** Server / bots / party quests / bosses
**Kind:** FEATURE, 1.7.0 headline (docs/roadmap/1.7.0.md, "Bots play the mechanics"; owner 2026-10-26, scaffolding
OK'd 2026-10-28: "we can start scaffolding stuff")
**Status:** phase 0 scaffold built 2026-10-28 (branch `170-mechanics` from `release-1.6.1`, not merged). Switched
off behind `-Dslums.mechanics` (default `false`); nothing in the running server calls the new code.

## Why

Bots can stand in PQ lobbies (LifePq), form bot-only LFG parties (LfgParty) and follow a player into a PQ or boss
and swing at mobs (FollowerFight, ExpeditionBots), but only Orbis PQ has a bot brain that solves stages
(`BotTypes/OPQ`, stages 1-2). Everywhere else a party of bots stands at the first NPC forever: nobody answers Cloto,
holds the KPQ ropes, finds LPQ's boxes or dodges a boss's phase. The owner wants bots that work any content that needs
logic, first perfectly (step 1), then like real people (step 2: mistakes by persona, visible tries, deaths, chatter,
ragequits), and PQ difficulty tiers built on the same framework.

## Goals

1. A bot-only party (or a player with bots) clears KPQ, LPQ, OPQ, HPQ, EPQ, PPQ and LMPQ end to end on TEST.
2. Party bots do real damage and follow boss phases in expeditions (needs client-verified attacks, see below).
3. One generic orchestrator; a new PQ is a YAML file plus, at most, a new solver. No per-PQ bot class like OPQBot.
4. Step 2: imperfection by persona that never stops a run from finishing (unless the persona ragequits).
5. Normal / Hard / Expert / Master as data, picked at the entry NPC.

Not goals: real players are never steered (bots plan around them); no new rewards or EXP (rates rule); no economy
changes; no client changes in this ticket.

## The model

A piece of **content** (PQ, boss, JQ, expedition) is an ordered list of **stages**. Each stage has:

| Part | What | KPQ stage 1 example |
|---|---|---|
| map, npc | where it happens, the stage's NPC | 103000800, Cloto 9020001 |
| **roles** | who does what; `who: LEADER / NON_LEADER / ANY`, `min`/`max` as a number or `party` / `party-1` | `leader` (1), `answerer` (party-1) |
| **objectives** | per role, in order: TALK_NPC, KILL_FOR_ITEM, COLLECT, HAND_IN, GIVE_TO_LEADER, ANSWER, OCCUPY_POSITION, HIT_REACTOR, REACH, DEFEAT, ENTER_PORTAL, WAIT | ask -> farm coupons (count `answer`) -> hand in -> give pass to leader; leader hands in `party-1` passes |
| **positions** | rectangles to stand on / hang from (script coordinates), `rope: true` for ropes | (KPQ 2-4: the ropes and platforms from Cloto's script) |
| **items** | items used, `cleanup` when the event takes them back | coupon 4001007, pass 4001008 |
| **answers** | the puzzle's answer table, mirrored from our script | the 7 Cloto questions (10, 35, 20, 25, 25, 30, 8) |
| **phases** | boss HP bands (100 -> 0, no overlap) and what the party does in each | (bosses only) |
| **clear** | the event property the stage script sets on clear | `1stageclear` |
| **solver** | which stage solver plays it | `question-coupons` |

Content also carries players/levels/time limit, `sources:` (required: where the data was checked from, with credit)
and optional `tiers:`.

**The server is the truth.** A solver never trusts the YAML answer table at run time: it reads the live answer from
the event instance (Cloto's `eim.gridCheck` entry, the `stg2Property` combination index, OPQ's reactor state). The
YAML copy exists for validation, QA, the dashboard and the tests that keep it in step with our scripts
(`StageDataTest.kpqAnswersMatchClotosScript` reads `scripts/npc/9020001.js`).

### Stage data format

One YAML file per piece of content in `src/main/java/soloMapling/mechanics/content/` (override
`-Dslums.mechanics.dir`). Loaded and validated by `StageData` (strict like `EventTemplates`: a file with any mistake
is skipped and reported, never half-loaded). See `content/kpq.yaml` for the full example. Checks today: required
fields, players/levels sane, stages numbered 1..n, a solver and a clear per stage, objectives name a declared role,
`count: answer` only with answers, every item an objective uses is declared, KILL_FOR_ITEM has mob/item/count,
HAND_IN has an NPC, one leader at most, role minimums fit the party, boss phases run down without overlap, tier names
and positive multipliers, unique ids.

Deploy note for phase 1: the loader lists a directory, which does not work from inside the jar (see HANDOFF's
jar-only data gap). Before switching on, either name the files in an index read through `soloMapling.DataFiles`, or
add the folder to `tools/ops/deploy-test` and `tools/release/stage`.

## The orchestrator

`RunOrchestrator` (interface now; implemented in phase 1) runs one content for one party. It is `OPQOrchestrator`
made general, keeping what works there:

- **Blackboard, not puppeteer.** One run state per event instance (OPQ's shared context, but keyed per instance, not
  per map id, the bug OPQ had to fix with its weak per-map index). Bots read it and decide their own step.
- **1 Hz run tick:** detect stage clears (`StageView.cleared()` reads the clear property), move everyone to the next
  stage's claims, watch the event timer, notice lost members.
- **Bots warped server-side where a script would warp a client** (OPQ's `followLeaderWarp`: a bot has no client, so
  NPC scripts that warp `cm.getPlayer()` do nothing for it).
- **Per-member step on the bot's own tick:** the stage's `StageSolver.next(view, member)` returns a `SolverStep`
  intent (MoveTo, Fight, Loot, TalkNpc, DropFor, HitReactor, Say, Wait, Done). The bot's existing systems carry it
  out: movement through BotNav/GCMovement, attacks through `BotAttackDriver`, NPC talk through the same server-side
  NPC path OPQ bots use. Solvers are stateless and never touch game state.

### Roles and coordination (`RoleClaims`, built)

Deterministic so every bot computes the same answer without messaging: the party leader takes the LEADER role; each
other role gets its minimum from free members in party order; free members then fill roles with room; leftovers are
`support` (follow, fight, loot). A role below its minimum is reported; the orchestrator waits (a member is walking
in), re-claims after a loss, or abandons. Resource claims inside a role (which rope, which box, which reactor) use
OPQ's "closest unclaimed live object" handout, moved into the run state. Real players in the party count as members
for claims; if a player holds a role, bots plan around it (call out "u take rope 2"), never move the player. When the
leader is a real player the bots hand passes to him and wait for him to talk to the NPC.

### Solvers (planned)

| Solver | Stages | Notes |
|---|---|---|
| `question-coupons` | KPQ 1 | ask, farm exactly N, drop extras, hand in, drop pass by leader |
| `combination` | KPQ 2-4 (ropes/platforms), LPQ 8 (boxes), similar | three members on the right positions, leader checks; tries walk "one mover" at a time |
| `collect-hand-in` | LPQ 1-6 passes, OPQ clouds/records, HPQ seeds, EPQ, PPQ | the OPQ bots' loot-and-return loop, generalised |
| `kill-all` | stages that clear on an empty map | FollowerFight targeting |
| `jump-puzzle` | LPQ/OPQ jump rooms, JQs | NavMovementSkills / recorded player paths (NavPlayerReplay) |
| `portal-maze` | LPQ portal rooms, OPQ tower | try-and-learn portals, run state remembers which were wrong |
| `boss-phases` | expedition bosses, PQ bosses (Alishar, Papa Pixie, Lord Pirate) | HP band -> actions: position, focus, burst, dispel, run |

### Imperfection and visible tries (`TryPlanner` and `MechanicsPersona`, built)

The bot knows the real answer but acts out a believable number of wrong tries first; the last try is always right.
`MechanicsPersona(careful, chaos)` (later built from SocialProfile's Bartle blend, skill and mood: an Achiever is
careful, a Killer chaotic, a Socializer chats more than it tries) sets the expected wrong tries, the tier's
`botMistakeFactor` scales it (draft 1.0 / 1.25 / 1.5 / 2.0), capped at `MAX_WRONG_TRIES` (4) and by the options left.
`MechanicsPersona.PERFECT` is step 1. Other step-2 behaviour, by persona, all optional per run: collecting one coupon
too many and fixing it, walking into the wrong portal, dying to a mob, being slow ("brb"), AFK in a stage, chatter
("which rope lol"), blaming, and rare ragequits (only when the party still meets the minimum, or as a full abandon
with a line in chat). A "hurry" rule drops all optional mistakes when the remaining event time is short.

### Failure and retry

| What | Response |
|---|---|
| wrong hand-in / wrong combination | the script says "wrong" (`showWrongEffect`); next try from `TryPlanner` |
| a bot dies | respawns where the map sends it; re-enters if it can, else `memberLost` and re-claim |
| a bot is stuck | NavStuckLog entry; after a limit, a short out-of-sight warp to the target (never in a player's view) |
| member lost (logout, left party) | re-claim; under the content's minimum -> abandon |
| event timer | hurry mode at 25% left; at 0 the event script ends the run, bots go back to their lives |
| leader (real player) leaves | the event disbands the party; bots leave with a line |
| orchestrator exception | logged once per run, run abandoned, bots released (never a stuck commitment) |

Every abandon releases the bots' commitments (`BotCommitments`, LfgParty's `holds`) so nothing logs out mid-run and
nothing stays held after.

### Boss phase scripts

Bosses are stages with `phases:`. Each phase is an HP band and a list of actions in a small vocabulary (to be fixed in
phase 5): `stand <x>`, `spread`, `focus <part>`, `burst`, `dispel`, `pot-up`, `retreat`, `res`. Multi-part bosses
(Zakum arms, Horntail parts) get one stage per part group with `DEFEAT` objectives per part. Phase changes are read
from the boss's HP and, where the server has them, the boss's skill casts.

### Client-verified attacks

Per the client remote-attack check: the client drops remote attacks with the wrong pose or weapon, so a bot can deal
server damage while a watching player sees nothing. Before a stage asks bots to kill in front of players (KPQ
Ligators, LPQ, every boss), the attack profiles involved must be checked on a real client (client MCP / Silvia
copies against TEST). Phase 1 uses only the attacks FollowerFight already uses in PQs. Phase 5 (bosses) needs the
skills used there verified first.

## What existing code is reused

| Code | Reuse |
|---|---|
| `BotTypes/OPQ/*` (OPQOrchestrator, OPQSharedContext, OPQBot, OPQPartyDuty) | the blueprint: blackboard, 1 Hz tick, server-side warps, reactor claims, per-instance state. OPQ moves onto the orchestrator in phase 2; OPQBot stays until the new path reaches parity, then goes |
| `BotConvo/LfgParty`, `BotLifeSystem/LifePq` | how bot-only parties form and travel to the lobby; phase 6 makes them start the PQ |
| `BotTypes/FollowerFight`, `FollowerBot` | fighting next to a leader inside an event instance, friendly-mob guard |
| `BotPartySystem/ExpeditionBots`, `BotGuildSystem/GuildBossRuns` | getting bots into expeditions; staged guild runs become real runs once bosses work |
| `GCMoveSystem` (BotNav graph, NavMovementSkills, NavPlayerReplay, NavStuckLog) | all movement: ropes, platforms, jump puzzles |
| `BotAttackSystem/BotAttackDriver` | attacks |
| `BotLifeSystem/SocialProfile` | persona for step 2 |
| `qa/PqScenarios` (`pq-1`, `pq-2`) | home for the new `!qa` scenarios |

## Data sources and credit

1. **Our own scripts** (`scripts/event/*.js`, `scripts/npc/*.js`, `scripts/portal/*.js`): the truth for answers,
   rectangles, items and clear properties.
2. **WZ data** (`Map.wz` life/portals/footholds, `String.wz`): positions, mobs, NPC spots.
3. **nutnnut/cosmic_agents** (AGPL-3.0, local copy `/root/work/nutnnut/cosmic_agents`, read at 3c7e09435):
   `server/agents/capabilities/partyquest/{kpq,lpq,opq,hpq,epq,ppq,lmpq}/Agent*Definition.java` and coordinators hold
   their stage data and solving logic for all seven PQs. Use it as a **cross-check and idea source**, credited in
   each file's `sources:` and in commit messages ("nutnnut/cosmic_agents <sha>"), not as copied code. Example: its KPQ
   answers match ours, but its stage-2 rope rectangles are listed in a different order from our Cloto script, so
   positions always come from our script. Its "one mover" combination ordering (one member moves per try) is the
   model for our combination solver's wrong tries. **Do not take** its director panel / database console web admin
   or any unauthenticated admin endpoint.

## Difficulty tiers as data

`tiers:` per content: `minLevel`, `maxLevel`, `mobLevelBonus`, `mobHpMultiplier`, `mobDamageMultiplier`,
`timeLimitMinutes`, `botMistakeFactor`. NORMAL is the stock PQ and needs no entry. Picked at the entry NPC
(phase 7), applied by the event script when it spawns mobs. To grill the owner before Hard and up are built (roadmap
row "PQ difficulties"): what scales, entry levels, rewards and EXP per tier (rates rule: no hidden bonus EXP), bot
behaviour per tier, and a clear-time leaderboard per tier on the site.

## Built in phase 0 (this branch)

`src/main/java/soloMapling/mechanics/`:
- `Mechanics` (flag `slums.mechanics`, data dir), `MechanicsModel` (records above), `StageData` (YAML loader and
  checks), `content/kpq.yaml` (KPQ stage 1 filled in).
- `RunOrchestrator`, `StageSolver`, `StageView`, `SolverStep`, `RunMember`: interfaces, no implementation.
- `RoleClaims`, `TryPlanner`, `MechanicsPersona`: pure logic, tested.
- Tests: `src/test/java/soloMapling/mechanics/StageDataTest` (10), `RoleClaimsAndTriesTest` (8).

Nothing is wired in: no bot, script, command or tick calls this package.

## Phased plan

| Phase | What | Size |
|---|---|---|
| 0 | Scaffold: model, YAML format, KPQ stage 1, interfaces, role claims, try planner (this) | S, done |
| 1 | Live `StageView` adapter on the event instance; orchestrator core; `question-coupons`; a bot-only party clears KPQ stage 1 on TEST; GM staging command `!mech kpq <n bots>` (owner likes these) and `!mech status`; `!qa` scenario + auto-QA log check | M-L |
| 2 | `combination`, `kill-all`, KPQ 2-5 and the bonus map: KPQ end to end; OPQ ported onto the orchestrator to parity, OPQBot retired | L |
| 3 | `collect-hand-in`, `portal-maze`, `jump-puzzle`: LPQ, HPQ, EPQ, PPQ, LMPQ, rest of OPQ (one YAML each, cross-checked with nutnnut) | L |
| 4 | Step 2: personas from SocialProfile, visible tries, deaths, chatter, slow/AFK, ragequit; hurry mode | M |
| 5 | Bosses: verify attacks on a real client, `boss-phases`, PQ bosses then expeditions (Zakum first); GuildBossRuns become real | L |
| 6 | Bot-only LFG parties start the PQ themselves (LfgParty/LifePq) with a world cap; site feed lines | M |
| 7 | Difficulty tiers after the owner's grill: entry NPC choice, event-script scaling, leaderboard export for the site | M-L |

Each phase stays behind `-Dslums.mechanics` (on TEST, off in the release conf) until the owner says go.

## QA plan

- **Unit:** every content YAML loads clean; answers/rectangles/items are re-read from our scripts and compared
  (one test per PQ like `kpqAnswersMatchClotosScript`); role claims and try plans per stage; solver steps against a
  fake `StageView` for every branch (wrong count, lost member, real-player leader).
- **`!qa` scenarios** (docs/ops/qa-suite.md): `mech-kpq-1` stages a GM with three bots in KPQ stage 1 and checks the
  clear; later one per PQ and per boss; `!qa do where`-style checks for stuck bots.
- **Auto-QA:** run a bot-only party through each PQ on TEST; pass = clear property set within the time budget,
  no exception in the log, no bot left holding a commitment, no leftover PQ items.
- **Soak:** many concurrent runs (target: 20 parties) on TEST for CPU and memory (orchestrator tick, nav).
- **Real client:** a person (or the Silvia client copies) watches a run: bots look like players (no snaps, attacks
  visible, believable tries). Required before any phase with new attacks.
- **Owner checklist** per release in `docs/release/<v>-qa.md`.

## Open questions for the owner

1. Tier numbers and rewards (above).
2. Ragequit: allowed to sink a run that a real player is in, or only bot-only runs?
3. World cap on concurrent bot-only PQ runs (CPU), and PQ hours.
4. Should bots ever take a stage's leader role when a real player is in the party?
