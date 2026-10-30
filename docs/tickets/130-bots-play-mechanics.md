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

GM: `!mechanics kpq [me] [bots] [perfect] [stage1-5]` (default: the whole PQ). QA: `!qa run mx-2` (four bots, whole PQ,
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

Simulation (`KpqFullRunTest`, Ligator model at TEST's pace): real leader + 3 bots, 40 seeds: mean 375 s / worst 572 s before,
262 s / 450 s after with the leader standing still, 196 s / 338 s when the leader hunts too. About 66 coupons at three
hunters' pace is the floor; more needs more damage (not changed).

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
