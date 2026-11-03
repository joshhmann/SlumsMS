# 130: Bots play the mechanics (1.7.0 headline)

**Area:** Server / bots / party quests / bosses
**Kind:** FEATURE, 1.7.0 headline (docs/roadmap/1.7.0.md, "Bots play the mechanics"; owner 2026-10-26, scaffolding
OK'd 2026-10-28: "we can start scaffolding stuff")
**Status:** phase 0 scaffold built 2026-10-28 (branch `170-mechanics` from `release-1.6.1`). **Phase 1 built
2026-10-28 on branch `170-mech1`** (from `170-mechanics`, `release-1.6.1` merged in; not merged anywhere): bots clear
KPQ stage 1 end to end, see "Phase 1 (built)" below. Everything stays behind `-Dslums.mechanics` (default `false`):
with the flag off no command is registered and nothing calls the package. Unit-tested; **not yet run on TEST or
watched on a real client**.

**1.6.1 update (2026-10-30, branch `162-contentruns`):** KPQ automation moved into 1.6.1 (owner 2026-10-30, ticket
87: every nutnnut port ships with alt bots in 1.6.1). Phase 2's KPQ half is built there: all five stages, the prize,
the bonus and the way out, for bot-only parties and for bots or owned alts in a player's party. See "KPQ end to end
(1.6.1)" below. Still behind `-Dslums.mechanics` (default off; the owner decides at integration). Unit-tested against
simulations of our scripts; **not yet run on TEST or watched on a real client**.

Numbering: this ticket was 114 until 2026-10-28; 114 is also bot talk on `161-botchat`, so it is now 130.

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
| **objectives** | per role, in order: TALK_NPC, KILL_FOR_ITEM, COLLECT, PLACE_ITEM, HAND_IN, GIVE_TO_LEADER, ANSWER, OCCUPY_POSITION, HIT_REACTOR, REACH, DEFEAT, ENTER_PORTAL, WAIT. A `PLACE_ITEM` names `item`, `count` and either a `reactor` (WZ name like `scar1`, or a numeric reactor id) or an `at` point: it drops the stack inside that reactor's box, because `MapleMap.activateItemReactors` matches the reactor's item **and** quantity | ask -> farm coupons (count `answer`) -> hand in -> give pass to leader; leader hands in `party-1` passes |
| **positions** | rectangles to stand on / hang from (script coordinates), `rope: true` for ropes | (KPQ 2-4: the ropes and platforms from Cloto's script) |
| **items** | items used, `cleanup` when the event takes them back | coupon 4001007, pass 4001008 |
| **answers** | the puzzle's answer table, mirrored from our script | the 7 Cloto questions (10, 35, 20, 25, 25, 30, 8) |
| **phases** | boss HP bands (100 -> 0, no overlap) and what the party does in each | (bosses only) |
| **clear** | the event property the stage script sets on clear, and `equals:` when the script writes the property before it means anything (OPQ writes `statusStg0..8 = -1` at setup). `clear: { instance: true }` is the other form: a stage whose script writes **no** property and only calls `eim.clearPQ()`, so the clear is the instance's own flag (`EventInstanceManager.isEventCleared()`, `StageView.eventCleared()`) | `1stageclear`, or `instance: true` |
| **solver** | which stage solver plays it | `question-coupons` |

Content also carries players/levels/time limit, `sources:` (required: where the data was checked from, with credit),
optional `tiers:`, and an optional `flag:` - the content's own system property, on top of `-Dslums.mechanics`, so a row
that covers only part of a party quest (Orbis PQ: `slums.mechanics.opq`) cannot be switched on by the flag that switches
Kerning PQ on. `Mechanics.enabledFor(Content)` is the gate; a row without `flag:` answers to `-Dslums.mechanics` alone.

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

Jar-only installs (done in phase 1): a folder cannot be listed inside the jar (HANDOFF's jar-only data gap), so
`content/index.txt` names the files and `StageData.load()` reads the index and each file through
`soloMapling.DataFiles` (override dir, source tree, then the jar). A new content file must be added to the index
(`MechanicsDataFilesTest` fails otherwise). `-Dslums.mechanics.dir` still lists a folder on disk.

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

## Built in phase 0 (branch `170-mechanics`)

`src/main/java/soloMapling/mechanics/`:
- `Mechanics` (flag `slums.mechanics`, data dir), `MechanicsModel` (records above), `StageData` (YAML loader and
  checks), `content/kpq.yaml` (KPQ stage 1 filled in).
- `RunOrchestrator`, `StageSolver`, `StageView`, `SolverStep`, `RunMember`: interfaces, no implementation.
- `RoleClaims`, `TryPlanner`, `MechanicsPersona`: pure logic, tested.
- Tests: `src/test/java/soloMapling/mechanics/StageDataTest` (10), `RoleClaimsAndTriesTest` (8).

Phase 0 wired nothing in; phase 1 (above) adds the runtime behind the same flag.

## Phase 1 (built 2026-10-28, branch `170-mech1`)

What a run does (KPQ stage 1, "Coupons for Cloto"):

1. **Staging** (`KpqStaging`): fresh level 30-35 bots spawn next to Lakelis on 103000000 of the GM's channel, form a
   party (a bot leads, or the GM with `me`), and the PQ starts the way Lakelis starts it: `KerningPQ` event, the
   script's own `getEligibleParty`, `startInstance(party, map, 1)`. The event script warps everyone in.
2. **Run** (`MechanicsRuns`, 4 Hz on the shared scheduler): per tick a `LiveStageView` snapshot of the event instance
   (Cloto's grid via `eim.gridCheck`, the `1stageclear` property, inventories, passes on the floor), the
   `Orchestrator` (pure logic) returns each bot's `SolverStep`, the `StepExecutor` carries it out. While a run holds a
   bot its own brain is paused and `MechanicsHold` (a `BotCommitments` clause) keeps it from logging out; every end
   (cleared, budget, too few members, leader gone, event ended, exception, `!mechanics stop`) releases the bots and
   wakes their brains. Staged bots leave the party (the event script ends the PQ when short) and the world 20 s later.
3. **Solver** `question-coupons` (`QuestionCouponsSolver`, ids from the stage objectives): an answerer asks Cloto,
   fights Ligators until it holds the target count (loots only coupons a monster dropped), drops extras, hands in,
   drops the pass by the leader; the leader walks to Cloto, picks up passes dropped for him and hands in `party-1`.
4. **NPC talk** (`BotNpcTalk`): a bot has no client of its own, so it clicks Cloto by running **our**
   `scripts/npc/9020001.js` in a fresh engine with a conversation manager whose player is the bot (items through
   `BotHoldings`, dialogue recorded for the log). `ClotoScriptForBotsTest` runs the real script against exactly that
   bot-safe surface, so a script change that reaches for anything else fails a test, not a run.
5. **Attacks**: `BotAttackDriver.botAttack`, the call FollowerFight already makes in PQs. No new skill or profile.
6. **Tries** (`TryPlanner`, capped at 2 for this puzzle): once a bot's question is known it plans 0-2 wrong hand-ins
   below its answer, ascending (another answer from the table, a mixed-up question, or one short, a miscount), by
   persona (`MechanicsPersona.forCharacter`, a steady per-bot stand-in until phase 4 uses SocialProfile) and tier.
   The script answers each with "that is not the right answer" and the bot keeps farming. `perfect` turns them off.
7. **Data**: `content/index.txt` names the content files and each is read through `DataFiles` (the jar-only gap is
   closed; `MechanicsDataFilesTest` loads kpq.yaml from a packed jar with no source tree).

### Mixed parties: the player gets first claim (design and rule)

- **Rule (built, `RoleClaims`):** claim order is real players first, then bots, each in party order. The party
  leader keeps the LEADER role whoever he is. So a role with limited room (KPQ 2-4 rope/platform hangers, LPQ boxes)
  always seats the player first and bots take what is left; a bot never takes a player's job.
- **Never steered:** the orchestrator asks no solver for a real player's step; bots plan around him.
- **KPQ stage 1 with a real leader:** bots drop their passes at his feet (`DropFor` to his id, owned by him) and wait;
  he picks them up and talks to Cloto. A real answerer does his own question; bots only loot coupons a monster
  dropped and only up to their own count, so they do not vacuum his.
- Later phases add: callouts naming the player's spot ("u take rope 2"), and bots stepping off a position the player
  walks onto (a re-claim when a player changes his mind). Open question 4 below stays for the owner.

### Commands and checks

| What | How |
|---|---|
| GM | `!mechanics kpq [me] [bots] [perfect]` (alias `!mech`, GM 3, only registered with the flag): 4 bots alone, or you + 3 (2-3 with a count). `!mechanics status`, `!mechanics stop`. Progress lines come to the GM as yellow text and to the server log as `[Mechanics]` |
| `!qa` | `mx-1 [perfect]`: four bots clear stage 1; `MX1 PASS cleared stage 1 in <s> s (limit 900 s)` or `MX1 FAIL <why>` (docs/ops/qa-suite.md) |
| Auto-QA | t-mx-1 runs mx-1 and asserts the start, the `RUN stage 1 cleared` line, the PASS within 15 minutes, no FAIL and no script failure. SKIPs when the flag is off |

### Verified vs not

- **Unit-tested** (no server): orchestration on a simulation of Cloto's stage 1 for 40 seeds with 4 bots (all clear,
  at most 2 wrong tries each, every wrong hand-in a planned one), perfect bots, a real leader, a real answerer,
  extras dropped, members lost (re-claim, then abandon below 3, leader lost), the time budget, start refusals;
  the real Cloto script against the bot-safe surface; role claims for mixed parties; the jar-only loader; t-mx-1's
  patterns; the command's arguments.
- **Not yet verified:** a run on TEST (staging, Ligator coupon drops for bots under the yield caps, movement in the
  stage map, the script run for a bot through `BotNpcTalk` on the real server, the clear in time); a watch on a real
  client (bots look like players, swings visible). TEST needs `-Dslums.mechanics=true` in start-test.sh to try it.

## KPQ end to end (1.6.1, branch `162-contentruns`)

Built on phase 1 (merged from `170-mech1`). Everything through our own scripts; no NPC bypass, no teleport.

| Part | How |
|---|---|
| Start | A bot leader talks to Lakelis through his script (`9020000.js`, "I want to participate", `BotNpcTalk.converse`). A player who starts KPQ at Lakelis with bots or owned alts in his party gets a run automatically (`EventManager.startInstance` -> `MechanicsRuns.onPartyInstanceStarted`). GM staging with "me" still presses Lakelis' button for the GM. |
| Stage 1 | Phase 1's `question-coupons`, unchanged. |
| Between stages | Members behind walk into the stage's `next00` portal; `GCPortals` lets them through only when the portal script's gate is open (`PqGates`: `kpq0..3` test `<n>stageclear`). |
| Stages 2-4 | `combination` (`CombinationSolver`): the leader opens the stage with Cloto (the script picks the answer into `stg<n>Property`); the solver reads the answer from that property; three members (the non-leaders, plus the leader in a party of three) stand inside the script's rectangles (`Occupy`: aim at the centre, done when inside); when the formation is exactly the combo's 0/1 pattern and has held for 2.5 s the leader checks (`CheckAnswer`; from his rope/platform in a party of three, as players click from where they stand). Nobody else stands on a position. |
| Visible tries | A bot leader plans 0-3 wrong formations by persona and tier (`TryPlanner`), as a chain where each differs from the next by one mover and the last from the answer by one mover (idea: cosmic_agents' `AgentKpqCombinationOrder`, reimplemented). Each wrong check shows the script's wrong effect. Stage 1 keeps its 0-2 wrong hand-ins. |
| Stage 5 | `kill-collect-hand-in` (`KillCollectHandInSolver`): everyone fights the stage's monsters (7 x 9300000, 3 x 9300002, King Slime; each drops a pass), collectors loot only monster drops and walk them to the leader (3 at a time, all once the map is clear), the leader loots them and hands 10 to Cloto (`5stageclear`, `eim.clearPQ`). |
| Prize, bonus, out | `afterClear` in the stage data: each bot talks to Cloto and presses "next" (`Converse [0]`): the script gives the prize (`giveEventReward`) and warps to the bonus; it hunts there 60-150 s (a chaotic bot longer) and leaves through Nella ("yes"). A bot never leaves a real player alone in the bonus (the event would end and throw him out). The run ends, cleared, when no bot is left in the instance, or after 10 minutes. |
| Mixed parties | Real players are never given a step. A real leader gets the passes at his feet, opens and checks the combination stages himself; the bots form the answer straight away (no wrong tries: they cannot make him check them) and one bot says when it is ready. A real member on the puzzle keeps the position he stands on; bots leave him one and the bot leader calls it out ("Bob u take rope3"). |
| Owned alts | Same logic as party bots (owner's shared-systems rule): an alt is a driven member; its companion brain pauses while a run holds it. The prize: an alt claims its own at Cloto, and its owner's claim still gives it one; `EventInstanceManager` gives an alt one prize per instance whichever comes first. |
| Population bots' prizes | The shared headless bot client has no player, so `giveEventReward` now puts a bot's prize in its inventory through `BotHoldings` (was: the client path, which gave it to nobody). |

Stage data: `content/kpq.yaml` now has stages 2-5 and `afterClear`; `KpqStageDataTest` re-reads rectangles, combos,
the pass count, the prize warp, the portal gates and Nella's exit from our scripts. `ClotoLaterStagesForBotsTest` runs
Cloto's script on stages 2-5 against the bot-safe surface and the solver's own spots. `KpqFullRunTest` plays the
whole PQ against a simulation of the scripts (30 seeded 4-bot runs, party of three, chaotic and perfect leaders, real
leader, real member on the puzzle, the bonus rule, the portals).

GM: `!mechanics kpq [me] [bots] [perfect] [stage1-5] [to1-5]` (default: the whole PQ; 1.6.2: `stageN` starts on stage N, `toN` stops after it). QA: `!qa run mx-2` (four bots, whole PQ,
25-minute limit, every bot reaches the bonus); auto-QA **t-mx-2**. mx-1/t-mx-1 still stop after stage 1.

What needs a real client (Silvia copies against TEST) before the switch goes on: bots hanging still inside the
4 px rope rectangles (stage 2; nav must stop mid-rope) and standing on the 5 px barrel tops (stage 4); the Ligator and
stage-5 boss attacks (FollowerFight's attack profiles, the client remote-attack check); portal walk-ins looking like
walk-ins.

Bot-only parties on their own (phase 6, KPQ only): `KpqLobby`, behind `-Dslums.mechanics.lobby=true` on top of
`-Dslums.mechanics` (both off). Once a minute, on a channel whose Kerning PQ lobby (103000000) holds 3-4 free
population bots of level 21-30 (LifePq sends them there at PQ hours), they make a party, the strongest leads and
talks to Lakelis; the run plays the whole PQ; the party breaks up after. At most `-Dslums.mechanics.lobbyRuns` (2)
such runs at a time world-wide. Unit-tested (the pick); not run on TEST.

Not done: OPQ on the orchestrator, other PQs, bosses, PQ tiers.

## 1.6.2 fixes from the first real-client run (branch `162-kpqfix`, TEST ch 2, 2026-10-30 14:09-14:15 UTC)

GM AqClient led three bots (`!mechanics kpq me 3`); evidence in TEST's `BotLog.txt` (`[Mechanics]`) and the journal.

| Finding | Cause | Fix |
|---|---|---|
| After `!mechanics stop` and a warp out, Lakelis refused the next party ("the current party to finish"); status said no runs | (1) stopping a run never touched its instance; the staged bots left the party 20 s later. (2) That party-leave ran KerningPQ.js `end()`, which exits each member before `eim.dispose()`; `unregisterPlayer` took the coupons/passes back through the bot's client, and the shared headless bot client has no player: NPE, `end()` stopped half way, instance alive until its timer, whose `end()` threw the same way (also 14:02 on the lobby run) | `EventInstanceManager.takeExclusiveItems`: a bot's event items go through `BotHoldings.takeAll`; a failure is logged and never stops the exit. `MechanicsRuns`: a run staging or the lobby started (`ownsInstance`) that ends short of the clear (stop, leader gone, too few, exception, budget) ends its instance (script `end`, fallback: unregister, warp to `exitMap`, dispose); any run's instance with nobody but bots left ends too; a player's own PQ is never ended under him. New `!mechanics dispose kpq [channel]` (GM 3, behind the flag, logged): ends stuck KPQ instances with no real non-GM player inside |
| Stage 1 slow with a real leader: two passes after 3 min, the third bot alone on 17 coupons at 5.5 min | each bot only picked up coupons its own kills dropped (`LiveStageView.floorDrops` skipped a fellow bot's party drop even after the ownership window) and a bot whose pass was in stopped fighting. Kill pace on TEST: about 5 coupons a minute per bot. Also the tester waited by the spawn, far left; Cloto is far right | party loot rule (`QuestionCouponsSolver.mayTakeMobCoupon`): own kill, a fellow bot's, a real member's only when he is not farming (leader or pass in), anyone's after 15 s; done members and a bot leader hunt next to whoever misses the most coupons (loot nothing); the first pass dropped for a real leader comes with "<name> dropped my pass by u, take them to Cloto on the right" |

Second run, bot-only `!mechanics kpq 4 stage4` (TEST ch 2, 14:41 UTC): answers 35, 25, 35; passes at 10 and 11 minutes,
the third bot still hunting at 20 minutes. Drop data: Ligator 9300001 drops coupon 4001007 at 200000/999999 x drop rate
4 = 80 %, so kills were not the limit; coupons were (each bot kept only its own kills', the bot leader stood still).
Also "will try [8] first" for 25 cost a full walk across the map with 8 coupons. Fixed with the loot rule and helpers
above, and stage 1 tries are now one near miss at most (`NEAR_MISS` 5 below the answer, `MAX_WRONG_HAND_INS` 1).
`stage4` did not start on stage 4 (it meant "stop after 4"): `stageN` now starts there (`KpqStaging.stageEventTo`
leaves stages 1..N-1 as Cloto's `clearStage` does: clear property, gate, `next00` linked to `kpq<n-1>`, stage 1's
grid emptied; no stage rewards, nobody moved; members walk through every opened portal), `toN` stops after N.

## 1.6.2 real-leader recheck (branch `162-kpqlead`, TEST ch 3, 2026-10-30 18:11-18:37 UTC)

| Finding | Cause | Fix |
|---|---|---|
| `!mechanics kpq me 3`: ~8 min on stage 1, no pass reached the GM's Etc although two bots logged `DROP ... 4001008 for AqClient` (18:12:52, 18:15:48 UTC) | the drops landed (`paid` 1 each; owner AqClient, party drop type 0, lootable by him) within 50 px of where he stood at that moment. No pickup of them is logged (bot pickups are not logged per item), but no path in the run loots a pass meant for a real leader (bot brains are paused; the coupon scan needs a monster dropper; Loot is the bot leader's only), so most likely he had moved on and they lay there until he left the PQ (on the exit map 103000890 by 18:19:34, when `!mechanics stop` ended the instance). `MapItem.setPermanentOwner` is never read: after 15 s a pass is free for all | `PassHandover`: to a real leader a bot walks up first (within 60 px, the shared `Approach.inReach` test) and drops at his feet; if he has not picked it up after 20 s and stands >100 px from it, the bot picks its own pass back up (`SolverStep.TakeBack`, the bot's normal loot call, legal after the ownership window) and brings it again. Floor scans never take a hand-over another member dropped (`PassHandover.mayTake`). Stage 1 and stage 5. No inventory shortcut |
| `!mechanics kpq me 3 stage4`: the GM stayed on stage 1, the bots went to 4 | staging moved nobody; bots walk the opened portals, the GM is never driven | `KpqStaging.leaderThroughGates`: with `me`, the GM goes through each opened `next00` by its own portal script (`kpq0..2`), never a GM warp |
| QA wanted formations held for screenshots | | `!mechanics kpq <n> stage2-4 hold` (and `me`): the run's checks and hand-ins become waits (`Orchestrator.hold`); `!mechanics release` (new) lets the run go on; `stop` ends it. Implies `perfect` |

Bot audit (ticket 163): **b113** runs were kept by instance name, and every channel's event manager names its first
KPQ "Kerning0" (KerningPQ.js: `"Kerning" + lobbyid`, lobbies per channel), so a second channel's run was refused
("this event instance already has a run") and `runFor` could return another channel's run; runs and lobby runs are now
keyed by name + world + channel (`MechanicsRuns.keyOf`). The script's names stay stock. Lakelis itself refuses a party
only when that channel's one KPQ lobby (`maxLobbies = 1`) is taken, which the orphan instances above did. **b118**: an
aborted run in a PQ a real player stays in sends out an owned alt whose owner left (alts with their owner stay); with
no real player left the instance ends (above), alts included.

Bot audit (ticket 163) medium/low items, fixed on the same branch:

| Item | Fix |
|---|---|
| K4 a player-led run cannot be finished | the leader's part stays his (only the event leader can hand in or check); every solver now says when it is his turn (`StageSolver.leaderPrompt`) and one bot reminds him 20 s after, then every 40 s (`Orchestrator.realLeader`) |
| K5 the owner's death is invisible | `StageView.alive`; while a real leader is dead every bot waits and one says so once; they go on when he is up (a revive outside the PQ ends the run, and the instance with it when only bots and alts are left) |
| K6 party ops tear alts out mid-run | `OwnedAltService.finish` has the alt leave its event through the script's `playerExit` before the save (`Backend.leaveEvent`): saved in town, no `playerDisconnected` that ends the PQ |
| K7 a throw between party and run leaks | `MechanicsRuns.start`: a throw after the holds (scheduler refusing) aborts and finishes the run (holds released, unregistered); `KpqLobby.start` disbands the party on any throw |
| K10 a broken stage script floods FAILED | `StepExecutor.scriptOutcome`: a failure is logged once in a row, the third in a row abandons the run |
| B6 prize re-talk on a full bag | a bot gives up on its prize after `PRIZE_GIVE_UP_MS` (60 s) and waits; the run ends when every bot left gave up. (The bonus guard counting members anywhere is kept: KPQ's own "team lacking" check counts every registered member, so a bot leaving could end the PQ under a player still on stage 5.) |
| B9 lobby level band 21-30 | `KpqLobby.pickParty` takes the content file's band (kpq.yaml 21-255) |
| K8 lobby slot race | the slot is taken before the run starts, given back if it does not |
| K9 / one lobby per channel | a channel whose KPQ has a live instance is skipped; one where Lakelis refused anyway is left alone 10 minutes |
| B8 a despawning bot stays in the instance | `BotGeneration.removeBotFromServer` calls the event's `playerDisconnected` (bot-safe since the first fix) |
| doc | `Mechanics.java` says what calls it |

Auto-QA: t-mx-3 (a stopped run leaves nobody inside), t-mx-4 (two channels at once), t-mx-5 (a player leads his two
alts and a bot; the alts are driven, the idle leader is reminded, the stop leaves nobody inside). See docs/ops/qa-suite.md.

Simulation (`KpqFullRunTest`, Ligator model at TEST's pace): real leader + 3 bots, 40 seeds: mean 375 s / worst 572 s before,
262 s / 450 s after with the leader standing still, 196 s / 338 s when the leader hunts too. About 66 coupons at three
hunters' pace is the floor; more needs more damage (not changed).

### Bots yield KPQ to players (1.6.2, owner 2026-10-30)

The bot lobby held a channel's one KPQ for its 30-minute timer and real players and auto-QA got "Another party has
already entered the Party Quest in this channel" (the 10-30 full auto-QA lost t-vb-3 and t-mx-1..5 to it). Now:

- **The lobby yields.** When a party whose leader is a real player (not a bot, not an owned alt; auto-QA's pretend
  player counts) starts KPQ on a channel (`EventManager.startInstance`: Lakelis' script, a GM's `!mechanics kpq me`,
  t-vb-3's direct start) and a bot **lobby** run holds that channel's KPQ, the run ends at once through the stop path
  (bots leave the party, the instance is ended with KerningPQ.js `end`, `KpqLobby.yieldFor`) and the instance's lobby
  is freed now, not after `EVENT_LOBBY_DELAY` (`EventManager.releaseInstanceNow`; the delayed release then leaves the
  new `Kerning0` alone), so the party enters on the same click. The leader gets "The bots in this channel's Kerning
  PQ step aside for your party." A player-led or staged run, a run with a real player inside, and a run that already
  cleared never yield. A bot-led run staged by a GM or QA check (mx-1..4) makes the lobby yield too
  (`KpqLobby.yieldForStaging`). Log: `[Mechanics] lobby yields ch N to <leader>'s party`.
- **No re-take.** The channel gets K9's 10-minute backoff after a yield.
- **Auto-QA t-mx-6** proves it (below); t-vb-3 and t-mx-1..5 no longer lose their channel to the lobby.

### b153: lobby runs never cleared stage 1 (TEST 2026-10-30)

TEST's BotLog, 12:02-14:50 server time (18:02-20:50 UTC), every lobby run on build b32a14214: 14 runs, 35 answerers,
**9 passes**; no run cleared stage 1, and each held its channel until the event's 30-minute timer (or an `AqClient`
stop). The passes that did come took 21, 36, 99, 118, 158, 581, 663, 684 and 1736 s; 26 of 35 answerers never
handed anything in, including answers of 8 and 10 coupons, while their party mates hunted next to them. That is not
the model's pace (196-262 s mean for the whole stage, `KpqFullRunTest`; fresh staged bots on the same build: passes at
85 and 252 s): it is per-bot farmers that can never complete. The lobby takes population bots (any level 21+, worn
bags), the QA checks fresh ones with empty bags. A bot whose etc tab has no free slot cannot pick up a coupon
(`BotHoldings.canCreditDrop` -> `canHold`) and so farms forever; `BotBagRoom` counted 5,179 of 9,083 TEST bots with
a full etc tab on 10-26. Fixes: the lobby only picks bots with at least 3 free etc slots (`KpqLobby.bagRoom`); a run
says at its start when a member has no etc room; and a farmer whose coupon count has not moved for 3 minutes logs one
`STALL <bot> on <n>/<target> x 4001007 for <s> s: etc <free> free of <slots>, ..., <n> on the floor within 600 px (<m>
it may take), at x,y, level L` line, so the next run says why if it is something else (nav, kills). Not proven on
TEST yet (no read of bot bags without the DB); the next lobby run's STALL lines will show it.

**Follow-up (d4f103b0e, 10-31 01:09-01:50 UTC): not the bag, the movement LOD.** The bot-only staged t-mx-1/t-mx-2
(fresh bots, empty bags: `etc 96 free of 96`) ran out of their 15/25-minute budgets on stage 1 with 0-10 coupons per
bot; ch 2/3 lobby runs the same night logged the same STALL lines. The bots swung 0-2 times in 9-15 minutes
(`BotAttackStats`: xWaifu 0 swings in 544 s, Snailx 2 in 872 s) and their STALL positions repeat exactly across bots
and runs (-57,104 by Cloto; 303,-66 three bots at once; -437,121; -437,-428; -454,-385; -470,120). The "85 and 252 s"
staged run above had AqClient (a real client) on the stage map, and so did b153's player-led 534 s run (5-7 coupons a
minute per bot). With no real player on the map, movement LOD goes coarse: the coarse planner lands a bot on the
floor under a goal it cannot plan to (a point next to a Ligator on a ledge) and the fight step re-issues the same
goal for ever, never in reach. Nothing in b32a14214..d4f103b0e changed fight or movement; bot-only runs were slow
before too (14:41 UTC run: 10 minutes for 25 coupons). Fix (162-healamt): a run keeps the maps its bots stand on at
full movement physics (`GCMovement.holdFullPhysics`, LOD only, not "watched") on every run tick
(`MechanicsRuns.keepPhysics`). Proof needs a TEST run: t-mx-1 on the fix, or `!env lod pin 103000800` on the current
build as an A/B.

### KPQ stage 1 pace (b153/b204, branch `162-kpqpace`, release blocker)

TEST checkpoint d4f103b0e (2026-10-30 23:10-01:57 UTC): t-mx-1/t-mx-2 ran out of time on stage 1 with fresh bots. No
code regression: those checks had never finished on TEST before (the b32a14214 run SKIPped them), and "85/252 s" above
were single passes in a GM-led run. Causes and fixes:

| Cause | Fix |
|---|---|
| Every Ligator on 103000800 stands on an upper platform. `StepExecutor.fight` took the nearest one in a straight line and called it in reach by \|dy\| alone; the swing skips mobs on another ledge. STALL spots repeat: under the y=-340 platforms (-57,104), on the x=-437 rope (-437,-428), its foot (-470,120) | `BotAttackDriver.pickTarget` (the swing's own `mayTarget`, ranked by the nav planner's walk cost `GCMovement.pathCostMs`, never an unreachable mob; a mob a mate goes for costs ~400 px more), `canSwingFrom` (same ledge as the swing), `StepExecutor.Chase`: no 20 px closer in 3 s, or the swing sees no targetable mob, skips that mob 10 s |
| With no real player on the map the movement LOD went coarse and the coarse planner never climbs | `MechanicsRuns.keepPhysics` holds the run's maps at full physics each tick (922b1a5c2) |
| A helper (pass in) walked to the nearest mob anywhere, then back within 250 px of its farmer, and stopped swinging (the 534 s run) | `helpStep`: `Fight` leashed to mobs within 600 px of the farmer; it walks back only beyond 750 px |
| A near miss cost a walk across the map to Cloto | handed in only within 300 px of Cloto, else it farms on to the answer |
| A real leader was reminded 20 s after his passes were in | stage 1: at once, then every 30 s (`StageSolver.leaderPromptTiming`) |
| STALL only after 3 minutes of no change | STALL when under 2 coupons a minute over 90 s (once until it speeds up) |
| A visible player outside the party (a GM) fenced off the mobs by him | KS manners: a PQ/event instance map's mobs are shared, like world-event mobs |

Model: `KpqFullRunTest` now has stage 1's real layout (`Geo`: the 22 spawns on 13 platforms, climbs, drop rate 80 %).
Three bots incl. a ranged one, 20 draws: mean 269 s, worst 371 s, helpers swinging in 12.7 % of their help beats
(old helper rule: mean 317 s, worst 455 s, 4.6 %). Unwatched without the physics hold: no clear, no swing. Not yet
proven on TEST: needs a t-mx-1/t-mx-2 run and a lobby run's STALL lines.

## Phased plan

| Phase | What | Size |
|---|---|---|
| 0 | Scaffold: model, YAML format, KPQ stage 1, interfaces, role claims, try planner (this) | S, done |
| 1 | Live `StageView` adapter on the event instance; orchestrator core; `question-coupons`; a bot-only party clears KPQ stage 1 on TEST; GM staging command `!mechanics kpq` and `!mechanics status`; `!qa` scenario mx-1 + auto-QA t-mx-1 | M-L, built (TEST run pending) |
| 2 | `combination`, `kill-all`, KPQ 2-5 and the bonus map: KPQ end to end; OPQ ported onto the orchestrator to parity, OPQBot retired | L |
| 3 | `collect-hand-in`, `portal-maze`, `jump-puzzle`: LPQ, HPQ, EPQ, PPQ, LMPQ, rest of OPQ (one YAML each, cross-checked with nutnnut) | L |
| 4 | Step 2: personas from SocialProfile, visible tries, deaths, chatter, slow/AFK, ragequit; hurry mode | M |
| 5 | Bosses: verify attacks on a real client, `boss-phases`, PQ bosses then expeditions (Zakum first); GuildBossRuns become real | L |
| 6 | Bot-only LFG parties start the PQ themselves (LfgParty/LifePq) with a world cap; site feed lines | M |
| 7 | Difficulty tiers after the owner's grill: entry NPC choice, event-script scaling, leaderboard export for the site | M-L |

Each phase stays behind `-Dslums.mechanics` (on TEST, off in the release conf) until the owner says go.

## Boss lane: the death count (1.6.4, branch `164-bossfight`)

Real expeditions are a path of their own. `GuildBossRuns` stays exactly as it is (a roll with the bots logged out);
the fight a human-led party actually enters is driven by `soloMapling/mechanics/` and, on this branch, by
`ExpeditionDeathCount`.

Phase 1 of the boss lane is the per-instance death budget, ported from BotStory `server/expeditions/ExpeditionDeathCount.java`
at `81a3b1df` (see `docs/PROVENANCE.md`):

- one counter per event instance, armed from `-Dslums.bossfight.deathcount` (boolean, **off**) with
  `-Dslums.bossfight.deathcount.cap` (int, default 10) and a per-boss override
  `-Dslums.bossfight.deathcount.cap.<type>` (e.g. `.zakum`). No config.yaml key and no store: the count lives on the
  instance and dies with it;
- only humans count (`Character.isBot()` / `isOwnedAlt()` deaths are free), so bots can die and be revived without
  spending the party's budget;
- the death that spends the last one ends the run: a line to the party, then the script's own end path
  (`scheduledTimeout`) at 1500 ms so the death animation is seen, and a fallback that moves everybody to the map's
  return map and disposes the instance if the script does not;
- with the switch off nothing is armed and the death path is what it is today.

No client packet: BotStory has a `DEATH_COUNT` opcode because its client is patched; stock v83 has none, so the line
is chat (type 5) and the server log. Tests: `ExpeditionDeathCountTest` (the counter) and
`ExpeditionDeathCountRunTest` (a real `EventInstanceManager` and `playerKilled`), the latter written failing first.

## Boss lane: pause for revive (1.6.4, branch `164-opq`)

The owner settled the open question of what a human death does to a boss run (2026-11-02): **hold the run for a revive,
do not abort it**. The scripts unregister a dead character (`scripts/event/ZakumBattle.js:140-160`,
`scripts/event/HorntailBattle.js:140-160`) so it can revive and walk back in, and `Orchestrator.memberLost` treated that
as a member leaving - for a six-member expedition that is the `minPlayers` abort, i.e. one death ended everybody's run.

- `RevivePause` (`soloMapling/mechanics/RevivePause.java`) carries the rule: `-Dslums.bossfight.revivepause`
  (**default on**: the expiry path is today's behaviour, so a server that wants the old behaviour passes `=false`),
  `-Dslums.bossfight.revivepause.ms` (default 300000) and a per-row override
  `-Dslums.bossfight.revivepause.ms.<row id>`.
- A `kind: BOSS` run whose member leaves records a `RunState.Waiter` (who, why, where it belonged in the list, when the
  window closes) and stops: `tick` advances no stage, warps nobody, and puts a `Wait` naming that member in every bot's
  slot. The member's return - `StageView.registered(id)`, which asks the instance, not the run's member list - ends the
  pause and restores its place and its role claims; the stage clock gives the paused time back.
- The window expiring ends the run through the ordinary `abort`, with a line that says it was the revive window and not a
  solver failure. The death cap still wins: `EventInstanceManager.endForDeathCount` ends the instance and
  `MechanicsRuns.tick` aborts on that before the orchestrator is asked.
- Party quests keep today's rule on purpose. A KPQ or OPQ run whose member leaves still ends, because a stranger walking
  off is not a revive.
- The engine learns one read, `StageView.registered(int)` (and `StageView.nameOf(int)` for the log line), because a
  paused run has already removed the member from `members()`.

Tests: `RevivePauseTest` (5) - held then resumed, the switch off is today's abort, the window expiring, the death cap's
end winning, and a PQ still ending on a member loss. Nothing about `BotDeaths.protectedFromDeath` changed: bots still do
not die in an instance, which stays an open design question.

## Boss lane phases A1-A2: the run and the Zakum phases (1.6.4, branch `164-bossfight`)

The real fight is a `kind: BOSS` content row driven by the same engine as KPQ: `Orchestrator` is the group brain,
`RoleClaims` the roles, `StageView` the only read of the instance.

- **A1, the seam.** `EventManager.startInstance(int, Expedition, Character)` calls
  `MechanicsRuns.onInstanceStarted(eim, true)` after `eim.startEvent()` - the expedition's members are warped by the
  script's `playerEntry` during it, so an earlier call drives nobody. One entry point serves the party quests too; the
  content row's `kind` decides the switch: `kind: PQ` behind `-Dslums.mechanics`, `kind: BOSS` behind
  `-Dslums.mechanics.boss` (**off**). No row for the script is not an error: one bot-log line and the expedition is left
  to its humans. `GuildBossRuns` never reaches it.
- **A2, `boss-phases` + `content/zakum.yaml`.** `BossTargetRules` (ported from BotStory `81a3b1df`) orders a member's
  targets; `BossPhasesSolver` returns one `SolverStep.Fight` whose `mobIds` are an ordered candidate list, so a part the
  member cannot reach falls through to the next instead of stalling it (widening `Fight` kept the single-id constructor,
  so `kpq.yaml` is untouched). The rung is: the eight arms `8800003-8800010` while any is alive and never the body, then
  the body `8800000`/`8800001`/`8800002`; a fake part is never a candidate; nothing standing but the map's own life is
  `WAITING_FOR_SUMMON` - the human leader clicks the Eye of Fire altar (`scripts/reactor/2111001.js:28`) and the solver
  waits (a `Wait`, so the run cannot abort on it).
- **Clear is a property value.** `ZakumBattle.js:101` writes `defeatedBoss=0` at setup, so presence would clear the
  room on its first tick. `clear:` gained `equals:` (presence stays the default for `kpq.yaml`); Zakum clears on
  `defeatedBoss: 1`, which the script sets when `8800002` dies. One stage, one map, no `nextPortal`.
- **The room is the expedition's, not the run's.** `MechanicsRuns.closeInstance` never ends an instance for a BOSS row
  (chests still to spawn or loot); the script's `end()`/`dispose()` does. If the kill target `8800002` is absent at
  runtime - it is the map's own WZ life, with no spawn site in our scripts or server code - the solver returns
  `SolverStep.Abandon` and the orchestrator ends the run with that reason instead of fighting arms until the budget runs
  out.
- **Known, reported, not changed:** a human death unregisters (`ZakumBattle.js:140-160`) and `memberLost` aborts the
  run, so one human death ends the bots' run. That is the owner's call, not this phase's.

## Boss lane phase B: Horntail, three rooms (1.6.4, branch `164-bossfight`)

Our Horntail, not BotStory's draft: three stages in `content/horntail.yaml`, all `solver: boss-phases`, each clearing on
the script's own property (`defeatedHead` 1 then 2, then `defeatedBoss` 1 - `HorntailBattle.js:237-238`, `:187-194`).

- **The rooms are entered by map id.** The door script is `scripts/portal/hontale_BR.js` but its WZ portal name is not in
  the repo, and `StepExecutor` stalls silently on a wrong name. `SolverStep.WarpTo(mapId)` (boss rows only; emitted
  before anything else when the member stands on another map) warps into the event instance's own map and logs one
  line. It only ever moves across maps, so nothing is teleported inside a fight.
- **Room 1 and 2** (`240060000`, `240060100`) hold nothing until a human touches reactor `2408003`, which spawns the fake
  body, sets `summoned` and spawns the head `8810000` / `8810001` (`2408003.js:35,40,43`). The stage names
  `summonProperty: summoned`, so no head is fought before the touch.
- **Room 3** (`240060200`) is empty until reactor `2401000` is hit, which calls `MapleMap.spawnHorntailOnGroundBelow`:
  intro `8810026`, body `8810018`, parts `8810002-8810009` (`MapleMap.java:4908-4954`), and restarts the timer to 60
  minutes. That reactor sets no property, so the stage names none: an empty room is a `Wait`, which is the same
  behaviour driven by the room itself.
- **Parts before the body, add family absent.** A part's damage and heal are forwarded to the body
  (`MapleMap.java:4939-4947`), so the eight parts come first and the body is only offered once none is left. The add
  family `8810019-8820023` is not in our scripts or server code and is not stage data; the corpse parts
  `8810010-8810017` and the intro `8810026` are never a role (`LiveStageView.partRole`).
- **`summonProperty` is data now.** A stage names the property that says its parts were summoned (null = no gate).
  Zakum names it; Horntail names none. The old `killTarget` field is gone (`164-bossrows`): nothing read it, and what
  it was meant to say - "the room may legitimately stand empty until the boss arrives" - is now `spawnWaitSeconds`
  (see the next section).
- **Two facts settled while doing this.** `exitMap` means "where a run ended by us sends a member"
  (`MechanicsRuns.endInstance`), so Zakum's row now says `211042400` - the script sends everyone there itself
  (`ZakumBattle.js:159-162`) - and `minPlayers` is the script's own gate (Zakum 6, Horntail 6), because bots join as
  party members and the gate is about the party, not about who leads it.
- **A latent ordering bug surfaced.** Content rows are read in file-name order, and two tests took "the first row" to
  mean KPQ; they now look the row up by id. Nothing in production depended on the order.

## Boss lane: the attack floor and the per-stage clock (1.6.4, branch `164-bossfight`)

Two items the owner took from ticket 176 C's b087 gate and from phase B's risk list.

- **The damage floor** (`-Dslums.bossfight.damagefloor`, **off**). A bot ranks its attacks by the average line, which
  is blind to a high-WDEF target: the skill with the top average can bottom out at the formula's floor (1, the clamp in
  `BotCombatFormula.clamp`) while the other attack still does real damage. `BotCombatFormula.estimateMinimumDamage`
  reads the same `Range` at its floor - no second damage model, no crit input (a crit only raises a line) - and
  `BotSkillChoice.choose` gains a floor overload: with the switch on, a boss always prefers the higher floor, and an
  ordinary mob only when the rule's own pick would do nothing, so a pack is unaffected. Off means the measurement is
  never taken (`BotAttackDriver.minimumPerMob` is only called behind `BotSkillChoice.floorOn()`), which is byte for
  byte today's behaviour. Semantics from BotStory `AgentAttackPlanScoringPolicy` (`81a3b1df`), credited in
  `docs/PROVENANCE.md`.
- **The per-stage clock.** A boss row's budget was the row's `timeLimitMinutes` counted from stage 1, but each boss
  room has its own clock - Horntail's body room restarts the script's timer to 60 minutes when its reactor is hit
  (`scripts/reactor/2401000.js:33`). `MechanicsModel.Stage` now carries its own `timeLimitMinutes` (0 = the row's),
  parsed from the stage block, and `Orchestrator.budgetFor` counts a `kind: BOSS` run's spent time from
  `RunState.stageStartedMs()`. Horntail's rooms carry 120, 120 and 60. PQ rows are untouched: they still use the row's
  budget from the run's start.

## Boss lane: the review round (1.6.4, branch `164-bossfight`)

Five defects found reviewing A1-B; all fixed on the same branch, switches unchanged.

- **The body is a chain, not one id.** `8800002` is `MobId.ZAKUM_3`, the third *phase* of the body, not map life, so
  "no `8800002` on the map" is the normal state for the first half of the fight. Where the chain lives matters, because
  a reader looking for it in Java finds nothing: it is WZ data - `Mob.wz/8800000.img.xml` `revive` 0 = `8800001`,
  `8800001` revive `8800002`, `8800002` none - consumed by `Monster.killBy` (`Monster.java:899-973`: `:903`
  `final List<Integer> toSpawn = this.getRevives();`, `:924-933` spawns the next phase on the revive map). The map code
  contributes one thing only: the last arm's death un-fakes the body `8800000` (`MapleMap.java:1785-1810`, the un-fake
  at `:1803-1804`). `makeMonsterReal` spawns no body phase. The old rule ("the script's kill target is missing while arms stand → abandon") therefore ended every Zakum
  run at the summon. Now the candidates are the eight arms in data order and then the body family in the order its
  phases wake (`8800000`, `8800001`, `8800002`), never a body phase while an arm is up, whichever phase is standing;
  `Abandon` only when, after the summon, the room has carried no arm and no body phase of any id for a 30 s grace
  (`BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS`), and the line names the ids it saw.
- **A warp must confirm its landing.** `StepExecutor.warp` now checks `bot.getMapId()` after the change, retries on the
  existing 2 s latch up to `WARP_TRIES` (3), and then logs the bot, the wanted map and the map it is really on and ends
  the run; a `WarpTo` naming any map other than the current stage's is refused with one line as a solver bug.
- **A bot opens Horntail's body room.** `2401000` is a click (`2401000.js:26-33`), so a bot-only party used to burn its
  budget in an empty room. A stage may now name `hitReactors:` (a list since `164-bossrows`, when Scarga needed two), and with no part
alive the solver returns
  `SolverStep.HitReactor(id)`; `StepExecutor` walks to the reactor and clicks it with the bot's own client, the way
  `ReactorHitHandler` does for a player. The reactor's own "no `8810026` yet" guard makes a repeat harmless.
- **A silently unarmed instance is visible.** The four script-supplied-`EventInstanceManager` overloads in
  `EventManager` never arm a run; when one starts a script a `kind: BOSS` row matches they now print one line, once per
  instance, instead of failing quietly.
- **A stranger's mob stays the stranger's.** Pinned by test: an alt inside a PQ instance does not take a mob a real
  player outside the party is hitting (`OwnedAltCombat.strangers`), on top of the two existing controls.

## Boss lane: seven single-room rows and the spawn wait (1.6.4, branch `164-bossrows`)

Seven `kind: BOSS` rows landed in one branch, every one of them one stage on one map clearing on `clear: { instance:
true }`, because none of their scripts writes a property a row could compare: `dunas` (TD Battle 2), `aufheben` (3),
`oberon` (4), `bergamot` (1), `nibelung` (5), `maha`, `castellan`. Per-row script/gate/map/target/spawn/clear detail is in
`REPORT-bossrows.md`, the credits in `docs/PROVENANCE.md`.

- **Why they used to abandon themselves in 30 s.** The abandon branch fires when the room has carried no `ARM` and no
  `BODY` part for `NOTHING_TO_HIT_GRACE_MS`, and `LiveStageView.partRole` gives every non-Zakum/Horntail id the role
  `ADD` - so `nothingToHit()` was true from tick one in all seven rooms, whose boss is the map's own life (or, for
  Castellan, the script's `setup()` spawn). The fix is row data, not a wider grace: a stage may now say
  `spawnWaitSeconds:`, and `BossPhasesSolver.allowedEmptyMs` allows `max(30 s, that)`. Zero (the default) is exactly the
  old behaviour, so a genuinely dead instance still ends at the grace instead of burning its clock. The fail-before
  pair is `BossPhasesSolverTest.aRoomThatDeclaresItsSpawnWaitIsNotAbandonedAtTheGrace` /
  `aRoomWithNoDeclaredSpawnWaitStillAbandonsAtTheGrace`, and the whole-run version is
  `RowRunSimulationTest.aDunasRoomThatStandsEmptyForFortyFiveSecondsIsNotAbandonedAtThirty`.
- **`killTarget` is gone.** Deleting it beat wiring it in: ordering could only duplicate `mobs:` order, and "the id whose
  death is the clear" is what `clear:` already says, so wiring it would have been a third spelling of one fact plus the
  second dead field the ticket forbids. The temporal part of what it meant is `spawnWaitSeconds`.
- **A chain is one stage, in revive order.** `bergamot` (`7220003 -> 7220004 -> 7220005`), `nibelung`
  (`8220013 -> 8220014 -> 8220015`) and `castellan` (`9400408 -> 9400409`) list every form in the stage that holds them:
  the scripts have one room and no per-form property, so a stage boundary would invent a room the server does not have,
  and the solver must never look for a form its predecessor has not woken. `Monster.killBy`
  (`Monster.java:903-963`) is what spawns the next one, from WZ `info/revive`.
- **Who can start one.** The five TD rows are portal + party (`scripts/portal/TD_Boss_enter.js`), so a human party
  leader's click is enough. `maha` is a quest instance for one character (`scripts/quest/21401.js:23-24`), hence
  `minPlayers 1 / maxPlayers 1` and a level bar (120) read from `Quest.wz/Check.img.xml` because the script declares
  none. `castellan` needs the NPC's expedition and a bot cannot lead an expedition, so that row only ever runs when a
  human made the expedition - recorded, not worked around.
- **The switch still decides.** `kind` is what `MechanicsRuns.onInstanceStarted` tests against
  `-Dslums.mechanics.boss`, not which `startInstance` overload called it, so a portal-entered party instance of a boss
  script is armed by the same flag: `BossRunSeamTest.aTdBattleInstanceStaysInertWhileTheBossSwitchIsOff` and
  `...ArmsARunForItsBotsWhenTheSwitchIsOn` pin both sides.

## Boss lane: the reactor rooms, and the two rows that wait on a primitive (1.6.4, branch `164-bossrows`)

Round two of the same lane added seven more `kind: BOSS` rows - `papulatus`, `krexel`, `showa`, `balrog`, `scarga`,
`elemental`, `pinkbean` - and changed one field. Per-row script/gate/map/target/spawn/clear detail is in
`REPORT-bossrows.md`, the credits in `docs/PROVENANCE.md`.

- **`hitReactors` is a list, not one id.** Scarga's room needs `5511000` and `5511001`, and the row - not the solver -
  decides which is clicked first, so `Stage.hitReactor()` became `Stage.hitReactors()`. The scalar spelling was
  **removed** rather than kept beside it (one fact, one spelling); `content/horntail.yaml` is the only other row that
  used it and now reads `hitReactors: [ 2401000 ]`. The double-click hazard is why the list is clicked once per stage:
  the reactor scripts are unguarded (`scripts/reactor/2201004.js:22-29` spawns on every `act()`), so a second click is a
  second boss. The solver clicks the first one while the room is empty, then the rest even though the first reactor's
  boss is standing - that is Scarga, where Targa answering must not stop Scarlion's click.
  Fail-before: `BossPhasesSolverTest.aRoomThatOpensOnlyHalfItsFightIsNeverCalledCleared` (one reactor, one chain, then an
  empty room that abandons and never says `Done`) and `bothOfScargasReactorsAreClickedBeforeEitherHalfIsFought`.
- **A click costs the other members one idle tick.** On the tick the click is emitted the room is still empty for
  everybody else, so they take the solver's 500 ms `Wait`. That is honest idling, not a stall, and the reactor rows'
  simulation assertion is therefore "nobody waits once the fight has started"
  (`RowRunSimulationTest.assertOpenedRoomClear`) instead of the `waits == 0` the populated rooms keep.
- **Pink Bean keeps its property clear.** `defeatedBoss == 1` is written by `monsterKilled` after the fifteenth junior
  kill (`PinkBeanBattle.js:261`), so the equality first becomes true exactly at the clear. The five statues
  `8820019`-`8820023` and the decoy `8820000` are not in `mobs:` at all, and the simulation asserts no statue reaches a
  `Fight` step.
- **Two rooms were refused rather than faked.** `latanica` needs a primitive that does not exist: the only entrance is
  portal `captinsg00`, which refuses a player without item `4000381` (`scripts/portal/captinsg00.js:4`), and `ItemUse`
  is evaluated inside a stage, i.e. only after the gate that needs the item. `delli` needs a survive-the-timer
  objective: `DelliBattle.js:119-123` clears when its own 6-minute timer expires, and killing the friendly `9300162`
  ends the instance (`:194-198`), so a `boss-phases` row would spend six minutes killing trash that is not the win
  condition and could end the run with one stray hit. `latanica`'s primitive was built in round three (below); `delli`
  is not ours - `164-opq` @ `ac08ce380` ships `DelliHoldSolver` + `content/dellibattle.yaml` + `DelliBattleSolversTest`
  for exactly that room, and two spellings of "won by staying alive" is worse than one. What *is* provable here was
  pinned instead: `RowRunSimulationTest.aFriendlyMobTheRowDoesNotListNeverBecomesATarget`.

## Boss lane: a door the row can name, and five quest spawns (1.6.4, branch `164-bossrows`)

Round three built the entry-item primitive round two refused to fake, shipped `latanica` on it, then added the five
quest-gated bosses: `latanica`, `magnus`, `nwill`, `nlucid`, `nverus`, `ndarknell` - twenty-two `kind: BOSS` rows in
all. Per-row script/gate/map/target/spawn/clear detail is in `REPORT-bossrows.md`, the credits in
`docs/PROVENANCE.md`. The primitive is additive: no new `SolverStep`, no portal special-cased, nothing changed in
`Orchestrator`, `StageView` or the PQ engine.

- **`entryItems:` - a door the run can see it is not through.** A stage may declare the items the run must have in hand
  before its first step. `captinsg00.js:4` is the only place that asks for `4000381` and the instance never asks again,
  so `latanica.yaml` declares it and `BossPhasesSolver` turns a run without it into
  `Wait(1_000, "the run has no entry item [4000381] in hand, so stage 1 cannot be entered: Capt. Latanica")` - no `EnterPortal`, no reactor click, no `Fight`, and (importantly)
  no `Abandon`: a dry run is not a dead room. It is one member's item, not everyone's, because the portal asks the player
  who walks through and the item is not consumed. The check reads `StageView.itemCount`, so a collect step earlier in
  the same stage would satisfy it - the primitive is "what must be in hand", not "when it was picked up".
  Fail-before: `BossPhasesSolverTest.aDoorItemTheRunDoesNotHoldIsAWaitThatNamesItAndTouchesNothingElse` and, whole-run,
  `RowRunSimulationTest.aRunWithoutTheWhiteEssenceWaitsAtLatanicasDoorAndNeverEnters`.
- **A survive-the-stage objective was built and then reverted.** The same round carried `ObjectiveKind.SURVIVE` plus a
  stage-level `neverTargets`, so `boss-phases` could hold Delli's room for the script's own clock and keep the friendly
  `9300162` out of every `Fight`. It worked and is gone: `164-opq` @ `ac08ce380` owns that room as `DelliHoldSolver`,
  which names the server's clock instead of keeping one. This branch carries no `SURVIVE`, no `neverTargets`, no
  `delli.yaml` and no Delli test - the revert is recorded in `REPORT-bossrows.md` so nobody re-adds it by hand.
- **The five quest instances are a precondition, not a gate.** `31166` (Magnus), `31162` / `31161` / `31164` / `31163`
  (Will, Lucid, Verus, Darknell) are quest states checked by the expedition recruiters and, for bots, by
  `ExpeditionBots.memberQuest` (skipped with reason `quest`); no instance script re-checks them and no run writes them.
  The rows ship: a human with the quest gets a working boss, and one without is refused at the recruiter where they can
  see it. Their simulated halves are the five 5 s spawn tests; the entry half is unproven by construction, because
  `StageView` cannot see a quest state - said so in `REPORT-bossrows.md` instead of pretending.
- **The 5 s spawn is inside the grace, so no row declares a wait.** `MagnusBattle.js:91-92`, `NWillBattle.js:116-117`,
  `NLucidBattle.js:115-116`, `NVerusBattle.js:116-117` and `NDarknellBattle.js:116-117` spawn their only target five
  seconds after setup, against a 30 s grace: a `spawnWaitSeconds` there would hide a regression rather than describe
  the room.

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
