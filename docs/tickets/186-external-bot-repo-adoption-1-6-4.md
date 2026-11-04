# 186: Adopt ideas from the other-repo bot audit

Requested by the owner, 2026-11-03 10:37 UTC (02:37 PT). Target: 1.6.4. Docs only.

## Read this first

**Nothing here is started. No briefs exist.** The owner asked for the other-repo audit as its own ticket. Each item
below becomes its own ticket and helper brief only after the owner picks it. The audit itself is
`/root/work/research/COMPARISON.md` (snapshot 2026-11-01, read-only study; per-repo sources in
`/root/work/research/findings/`). A second read of it for improvements is
`/root/work/agent-runs/server/audit-164/BOT-ALT-IMPROVEMENTS-EXTERNAL.md`. Where those two do not say something, this
page marks it **inference**.

## Repos compared (COMPARISON.md:34-40), all AGPL-3.0

| Repo | Pinned commit |
|---|---|
| Incluwin/BotStory | `81a3b1dfb` |
| nutnnut/Cosmic | `b684bf785` (dev ideas at `77e0c5bc9`) |
| MadaraGameDev/SoloMapling | `47b61b538` |
| Kiwiism/cosmic_agents | `3c7e09435` |

SlumsMS itself was compared at `release-1.6.3`. All five are AGPL-3.0, so the upstream licence is no new obligation;
the live rule is per-commit attribution in `docs/PROVENANCE.md` (COMPARISON.md:40).

## Rule for anything copied or borrowed

Copied source needs a provenance entry (repo, commit, file, what changed) in `docs/PROVENANCE.md` and licence
compliance (keep the AGPL-3.0 notices and in-file credit lines). **Idea-only ports are recorded there too.** Prefer to
re-implement from the idea, as the gear optimizer was (PROVENANCE.md:61-66).

## Already adopted (BOT-ALT-IMPROVEMENTS-EXTERNAL.md section 3)

- SoloMapling `47b61b53` plus the persistence series is the bot foundation; nutnnut's movement system arrived inside it
  (PROVENANCE.md:11-19).
- cosmic_agents `3c7e09435`: gear optimizer precursor, code not copied (PROVENANCE.md:61-66); KPQ stage-data
  cross-check only (:240). Pareto DP from nutnnut replaced it in ticket 162 (:139).
- nutnnut/Cosmic: nav, combat, gear, grind, builds and companions ports, plus idea-only ports at dev `77e0c5bc9`
  (PROVENANCE.md:98-243; the companion command and sharing set at :178).
- **BotStory: nothing adopted.** A search of PROVENANCE.md finds no mention.

## Ranked candidates (COMPARISON.md:136-147)

"Bots / alts" is **inference** throughout: the audit has no alt section (see "Not read"). "Owner decision" is also not
stated by the audit; STATUS-1.6.4.md rows e03, e05 and e06 record that the owner said yes to scoping ranks 1, 2 and 3
(and rank 9's lease) on 2026-11-01 08:23 UTC, so those are marked "scoped"; each still needs an owner go before a brief.

| # | Source repo | What it does | Effort (audit) | Bots / alts (inference) | Owner decision |
|---|---|---|---|---|---|
| 1 | SoloMapling `ObserverTracker`+`CoarseExecutor`; cosmic_agents 50/250/5000 ms tiers | Measure what today's tick ladder saves, then sharpen "observed" past one boolean (per viewer, per distance) | medium, switch default OFF | bots; alts' 200 ms tick is the same area | scoped (e03) |
| 2 | BotStory `CosmicHeadlessQuestScriptGateway` | Make `BotQuestScript` a general capability (bounded script advances, per-capability budgets) instead of Temple of Time only; skip the `Quest.wz` log mirror | medium | bots; alts inference | scoped (e05) |
| 3 | cosmic_agents `AgentAttackPlanScoringPolicy:19-75` | Useful-DPS attack scoring, prefer guaranteed kills; may close the DK DPS gap (b087) | low, needs only `BotCombatFormula` | bots and alt followers | scoped (e06) |
| 4 | cosmic_agents `AgentObjectiveKernel` + checkpoints | Interrupt stack: resupply preempts a quest, quest resumes; one checkpoint per transition on its own thread | medium | bots; alts (resupply) | y |
| 5 | cosmic_agents `AgentBehaviorCalibrationState` | Seeded, replayable behaviour random numbers; precondition for a real regression harness | low | bots | y |
| 6 | cosmic_agents `AgentMapActivityPolicy` | Map-wide crowd fairness with rotating active slots (replaces the 50-bot cap idea) | low | bots | y |
| 7 | cosmic_agents `JdbcEconomyInvariantAuditor` | Exchange invariants as durable rows checked in CI | low | economy | y |
| 8 | BotStory `EconomyController` | Design rule for any live lever: at most 3 bounded levers, dead-band, shadow mode, replay on boot | low (rule, not port) | economy | y |
| 9 | cosmic_agents lease + hysteresis; nutnnut `MAP_SWITCH_HYSTERESIS`; BotStory soft-then-hard capacity | Stops cohort map-thrash and stacking | low | bots | scoped for the lease (e06); rest y |
| 10 | BotStory `CHATTER_TOWN_LINES_PER_MINUTE`; nutnnut 15 s self-cooldown | Per-map burst ceiling for ambient chat | low | bots | y |
| 11 | BotStory `TellAudit` + LIVE/SHADOW judge modes | Likeness measurement; shadow-judge a reply before going live | low | bots | y |
| 12 | nutnnut `AMMO_RESERVE`/`POT_SPEND` ladder | Tiered spend policy and broke-bot guardrails; realism, no economy mutation per audit | low | bots | y (inference: changes bot spending) |

The audit's summary says nine of twelve are low and two medium; item 4's own text says medium too (COMPARISON.md:139).

## The boss-fight gap: OWNED BY THE BOSS-FIGHT / PARTY-QUEST LANE

Upstream bots actually fight bosses (BotStory expeditions: Zakum, Balrog, CWKPQ, GPQ with a shared death budget;
cosmic_agents' easy-Balrog lobby). Ours rolls the outcome (`GuildRules.runClears`) and no fight happens
(COMPARISON.md:14, :22). The audit states no effort and does not rank it.
The boss lane is working on it: STATUS e04 ("real bot boss runs", owner yes) and the `164-bossfight` `MechanicsRuns`
arming hook. Per `/root/work/agent-runs/playerbot/e04/REPORT.md` (2026-11-03 09:05 UTC, read by me):
- **It replaces the roll with a fight**, for Zakum and Horntail: "Bots fight boss expeditions instead of rolling a
  guild-run clear" (`REPORT.md:156`); the instance start arms a run (`BossRunSeamTest`, `:105`); 20 more boss rows and
  six GuardianNex rows ride on the same switch (`:163-164`).
- **Behind switches, all OFF today:** `-Dslums.mechanics.boss` (`SWITCHES.md:14`) plus `-Dslums.mechanics.<row>` per
  row (`SWITCHES.md:15`); `.damagefloor` and `.deathcount` are OFF too (`SWITCHES.md:22`, `:18`).
- **Simulator-proven only:** "proven against a fake `StageView` or a simulated run, not a `MapleMap`"; no client has
  seen a boss die (`REPORT.md:140-145`). Bots in an instance are immortal (`:230-231`, an open owner decision).

`/root/work/DIFFICULTY-CHAOS-PLAN.md` (headings, §5.4, §6 table read) covers Chaos Zakum and tiers, not this gap.
**186 does not start it.**

## Anti-patterns not to import (COMPARISON.md:149-160)

Config as a second codebase (6,924 BotStory / 4,402 cosmic_agents required keys); pass-through
`XRuntime`/`XService`/`XState` triads; ~70 boolean hot-path flags (nutnnut); no map locking and pointwise race
defences (nutnnut); an unauthenticated admin web API (nutnnut, rejected as NN-020; any inspect API goes through the
existing admin login); `id > 20000` bot identity (SoloMapling); a shared template row for all bots, self-creating
tables outside migrations; committed client assets (BotStory ships 162 MB of `wz/` and needs a patched client);
three relationship stores (do not add a third); docs that contradict the code (read upstream docs as intent only).

## Measurements to take BEFORE any build (COMPARISON.md:176-184)

1. What the existing tick ladder saves: the share of bot CPU already at 9-12 s or 60-120 s, and how much a finer
   observation test would recover for the DB pool (b269). Instrument before changing any rate.
2. Whether useful-DPS scoring closes the DK DPS gap (b087), or the gap is in the formula.
3. Whether navigation graphs (`GRAPH_VERSION` 70) are keyed by movement profile (speed, jump) or by map only.
4. Whether chat has a per-map lines-per-minute ceiling, or only per-bot gaps (`RepeatGuard`).
5. How many quests a general scripted-quest capability would unlock, versus the 6/day picker plus Temple of Time.
6. Whether the Exchange is invariant-checked anywhere as durable data, or only in tests that can stop being run.
7. How often `BotDbGate.acquire` refuses at peak, and how many refusals reach `BotMemory`'s DEBUG catch (state lost).

## Order of work

1. The seven measurements, first; each is a read-only or instrument-only task, none changes behaviour.
2. Then ranks 1-3: observation granularity, the headless quest-script gateway, useful-DPS scoring. Each becomes its own
   brief only after the owner picks it. Start rank 3 only after the boss lane merges `BotCombatFormula` (184 entry 7);
   keep rank 1 clear of 183's ledger and e03 (184 entry 7).
3. Ranks 4-12 stay on the menu.

## Not read

`/root/work/research/BotStory-qa-review/docs/COMPANION_*.md` (alt companion plans, e.g. `COMPANION_LENDING_PLAN.md`,
`COMPANION_BOSSING_PLAN.md`): **next read if owned alts are in scope.** COMPARISON.md has no alt section. The
`findings/*.md` files, tickets 169 and 170 (earlier QA-only reviews), and the upstream source were not read here.
