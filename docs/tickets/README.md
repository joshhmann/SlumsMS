## Alt marriage (owner direction, 2026-11-03)

| # | Ticket | Purpose |
|---|---|---|
| [189](189-alt-marriage-1-6-4.md) | Marriage with your own alts for 1.6.4 | Guards and deletion guard (stage 1), proxy marriage with fee behind `-Dslums.ownedalts.marry` (stage 2), alt-versus-regular bonuses open (stage 3) |

# SlumsMS tickets

## Server-code audit (owner direction, 2026-11-03)

Read-only audit of the server code (eleven lanes). Documented, not changed.

| # | Ticket | State |
|---|---|---|
| [182](182-server-code-audit-1-6-4.md) | Fix the server-code defects the audit-164 audit found | Owner decisions (a)-(e) answered; cards b289-b298, then cardless impact-4 set, migration 059, config validation |
## Client map integrity (owner request, 2026-11-03 Pacific)

| # | Ticket | State |
|---|---|---|
| [181](272-client-map-integrity-and-crashes.md) | Missing map assets, broken map links and Rose Garden crash proof | Investigation recorded; links b251 and existing map-load work; implementation and real-client proof pending |
## Monster loot coverage (owner request, 2026-11-03 Pacific)

| # | Ticket | State |
|---|---|---|
| [182](274-missing-monster-and-boss-loot-tables.md) | Missing mob/boss loot tables, with gear progression overlap | Read-only audit; candidate list needs reward/source triage and targeted kills |
## Marriage fixes (owner direction, 2026-11-03)

| # | Ticket | Purpose |
|---|---|---|
| [188](188-marriage-fixes-1-6-4.md) | Marriage fixes for 1.6.4 | Fix the chapel booking, ticket loss, divorce order and ring-missing crashes found by the two marriage audits |

## Promise audit (2026-11-01, pending triage)

| # | Ticket | State |
|---|---|---|
| [177](177-promised-feature-code-audit.md) | Promised-feature implementation and acceptance audit | First server/QA pass; reconcile with release book |
| [178](178-sibling-only-upgrade-handover.md) | Sibling-only upgrade offer cannot start a handover | Source-confirmed; coordinator + submitter triage required |
| [179](179-qa-worker-loses-batched-ready-events.md) | Client readiness events lost between waits | Reproduced locally; coordinator + submitter triage required |

## Bot and alt improvements (owner direction, 2026-11-03)

| # | Ticket | Purpose |
|---|---|---|
| [183](183-bot-alt-improvements-1-6-4.md) | Cheap bot and alt improvements that need no owner decision | Owned-lifetime sweep, mint/destroy ledger (observation only), alt lockout/boot line/follow message (restock receipt and wear predicate parked, see 184); no economy numbers |
## Possible overlaps (owner direction, 2026-11-03)

| # | Ticket | Purpose |
|---|---|---|
| [184](184-possible-overlaps-1-6-4.md) | Possible overlaps to revisit | Parking list of places where in-flight 1.6.4 work could collide, with how to check each and what to do; check before starting or merging |
## Improvement backlog (owner direction, 2026-11-03)

| # | Ticket | Purpose |
|---|---|---|
| [185](185-bot-alt-improvement-backlog-1-6-4.md) | Bot and alt improvement backlog | Improvements that need an owner decision or a later start; a menu, nothing started, no briefs |
## Other-repo bot audit (owner direction, 2026-11-03)

| # | Ticket | Purpose |
|---|---|---|
| [186](186-external-bot-repo-adoption-1-6-4.md) | Adopt ideas from the other-repo bot audit | BotStory, nutnnut/Cosmic, SoloMapling, cosmic_agents: 12 ranked candidates, 7 measurements first; nothing started, boss fights stay with the boss lane |
## Social intelligence (owner direction, 2026-11-03)

| # | Ticket | State |
|---|---|---|
| [271](271-social-intelligence.md) | Persistent NPC social intelligence (traits, emotions, needs, relationships, memory, actions, modes, Henesys pilot) | Stages 0, 0b on `164-social-core`; 1 on `164-social-mind`; 2-7 planned. Design: `docs/design/social-intelligence-audit.md` |
## 1.6.4 content hunt (2026-11-02)

From `/root/work/agent-runs/FOLLOWUPS-164.md`, the merge record `agent-runs/server/merge-164-c/REPORT.md` and the
boss/expedition audit `agent-runs/server/s-boss-audit/`. Story and environment traps:
[`docs/1.6.4-content-hunt-handoff.md`](../1.6.4-content-hunt-handoff.md).

| # | Ticket | State |
|---|---|---|
| [181](273-config-template-gap.md) | The shipped config template omits 353 of 359 keys and every world, so a fresh install boots into 0-valued boot-critical fields | Merged at `1c168eff9`; forward-merge hazard to `release-1.6.4` remains (S2) |
| [182](275-boss-clear-reward-bot-guard.md) | Papulatus, Scarga and Showa dereference a bot's headless client in the clear-reward loop | Source-confirmed, reachable; fix + test required (S3, F01) |
| [183](276-expedition-attempt-logging.md) | The daily expedition-attempt ledger is never written, so the quota counts clears | Source-confirmed; needs the owner's switch decision (S3, F02) |
| [184](277-expedition-hwid-reward-limit.md) | `EXPEDITION_HWID_LIMIT` reads 0 and nothing calls the limit, so "4 per machine per day" is enforced nowhere | Source-confirmed; arithmetic decision required (S3, F03) |
| [185](278-world-unclaimed-expedition.md) | `World.addUnclaimed` is a no-op and `removeUnclaimed` puts the same value back | Source-confirmed; 14 script callers are inert (S4, F07) |
| [186](279-dead-code-cleanup.md) | Dead `RedisJobQueue` + its `jedis` dependency, a half-written `Ola.startOla`, and F08 filed stale | Source-confirmed; deletion (S4) |
| [187](187-stale-divergence-ledger-rows.md) | Three divergence-ledger rows describe code that has changed (NP-007, SK-033, MP-003) | Docs-only; owner confirmation required (S4) |

## 1.6.2 release closure (owner direction, 2026-10-31)

| # | Ticket | Purpose |
|---|---|---|
| [168](168-finish-1.6.2-and-freeze-scope.md) | Finish 1.6.2 with fixed scope and explicit acceptance gates | Reconcile remaining promises, freeze additions, complete QA and prepare the owner go/no-go |

Current status is in `/root/work/STATUS-1.6.2.md` and the release book; the older tables below are historical.

## Bot behaviour audit (owner direction, 2026-11-03)

Read-only behaviour review of the population and owned-alt code paths. Documented, not changed.

| # | Ticket | Purpose |
|---|---|---|
| [181](181-bot-behaviour-audit-1-6-4.md) | Fix the 60 bot behaviour defects the 1.6.2 audit found | Two S1s first (a stall offer rewriting a listing below cost, merchant bots minting an item per sale), then 20 S2s by subsystem; every economy number is an owner question |

## Bot platform review (owner direction, 2026-11-01)

Read-only architecture review of BotStory, nutnnut/Cosmic, SoloMapling and cosmic_agents. Documented, not changed.

| # | Ticket | Purpose |
|---|---|---|
| [176](176-bot-platform-comparison.md) | Adopt from four upstream bot platforms, in the owner's order | Correctness debt, then cost shape, then capability coverage, then realism; records the reviewed shas and the gate each item must pass first |

## b269 pool forensics follow-ups (2026-11-01)

From `agent-runs/server/269-dbpool-forensics/AUDIT.md` and the fix branch `269-dbpoolfix`. Documented, not changed.

| # | Ticket | Severity |
|---|---|---|
| [174](174-roster-load-pool-get.md) | The roster and store snapshot reads bypass the bot share | S3 |

## Lock-order follow-ups (2026-10-31)

| # | Ticket | Priority |
|---|---|---|
| [173](173-life-runtime-lock.md) | `LifeRuntime.accepted` takes the bot's monitor over a PlayerStorage read (same family as b267, no cycle today) | S4, after 1.6.2 |

## Exchange item catalog (owner direction, 2026-11-03)

| # | Ticket | Status |
|---|---|---|
| [270](270-exchange-item-catalog.md) | The exchange item catalog: all scrolls, some use items, then ETC as tickers, plus the item maker and the sell cap | built, 1.6.4, branch `164-excatalog` (709 tickers, tests green, not deployed); the list is [docs/economy/exchange-item-catalog.md](../economy/exchange-item-catalog.md) |

## Economy (after 1.6.3, owner 2026-11-01)

| # | Ticket | Status |
|---|---|---|
| [175](175-economy-rework-flea-lessons.md) | Economy rework: lessons from Tarkov's flea market (ideas and questions, decides nothing) | later (book e01) |

## QA follow-ups (2026-10-31)

| # | Ticket | Target |
|---|---|---|
| [169](169-botstory-qa-reference-adoption.md) | Borrow targeted QA diagnostics from BotStory | After 1.6.2; only minimal diagnostics/regressions for an existing blocker may enter the frozen release |
| [170](170-cosmic-agents-qa-reference-adoption.md) | Borrow controlled QA fixtures from cosmic_agents | After 1.6.2; deduplicate shared BotStory tooling and preserve the release freeze |
| [171](171-1.6.3-incremental-qa-and-formal-triage.md) | Incremental scripted QA with formal triage before fixes | 1.6.3 stabilization and balancing; required client proof and explicit map coverage |

## 1.5.1 technical review follow-ups (2026-10-24)

Source review of 1.5.0 `06a724dfc`; all four tickets are open and need regression/runtime validation.
These are additions to the current [handoff backlog](../HANDOFF.md), not changes to historical status below.

| # | Ticket | Priority |
|---|---|---|
| [62](62-shutdown-active-player-saves.md) | Save active player sessions before bot shutdown | P1 |
| [63](63-shutdown-unsaved-bot-debits.md) | Preserve bot debits when the shutdown budget expires | P1 |
| [64](64-bera-wealth-scroll-budget.md) | Use Bera's rate for the bot wealth scrolling bonus | P2 |
| [65](65-shutdown-save-failure-reporting.md) | Report failed player saves accurately | P2 |

The temporary roster-growth reset is already tracked in `docs/HANDOFF.md` section 3; do not duplicate it here.

## Historical ticket index

Created 2026-10-19 from the owner's plan. Order: nav v2 → real bots → companions → economy → bots live → addons and later items. See `HANDOFF.md` for where everything runs.

**Status** is checked against git as of 2026-10-21: "vX" means the ticket's branch is contained in tag vX (`git tag
--contains`), "on live" means it is also installed or switched on on 192.168.0.164. Each ticket's own `**Status:**` line
has the detail. Live runs 1.4.0.

| # | Title | Area | Depends on | Status |
|---|---|---|---|---|
| [01](01-nav-v66-merge.md) | Merge nutnnut nav v60-67 into GCMoveSystem (graph v68) | Nav v2 | - | v1.3.0 |
| [02](02-mobility-skills.md) | Mobility skills per class | Nav v2 | 01 | v1.4.0 |
| [03](03-cross-map-travel.md) | Cross-map world graph (boats, taxis, ferries) | Nav v2 | 01 | v1.3.0 (+ high-level routes v1.4.0) |
| [04](04-learning-on-v68.md) | Carry nav learning onto the new edge types | Nav v2 | 01 | v1.3.0 (+ player learning v1.4.0) |
| [04a](04a-nav-lab.md) | Nav lab: continuous learning without players | Nav v2 | 04 | open |
| [04b](04b-nightly-deep-bake.md) | Nightly deep bake | Nav v2 | 01 | v1.3.0 |
| [04c](04c-stuck-report.md) | Daily stuck-hotspot report | Nav v2 | 01 | v1.3.0 |
| [05](05-real-stats-store.md) | Persist real stats: skills, AP/SP, job | Real bots | 01 | v1.3.0 |
| [06](06-builds-job-advance.md) | AP/SP builds and job advancement (stock classes) | Real bots | 05 | v1.3.0 |
| [07](07-combat-formula.md) | Real combat damage from stats, gear, skills and buffs | Real bots | 05 | v1.3.0 |
| [08](08-pots-ammo.md) | Real potion and ammo use | Real bots | 07 | v1.3.0 |
| [09](09-shop-resupply.md) | Buy supplies at NPC shops | Real bots | 03,08 | v1.3.0 |
| [10](10-gear-optimizer.md) | Equip the best owned gear | Real bots | 05 | v1.3.0 |
| [11](11-scroll-gacha.md) | Real scrolling and gachapon | Real bots | 10 | open |
| [12](12-sell-trade.md) | Sell and trade | Real bots | 10 | open |
| [13](13-owned-alt-bots.md) | Owned alt-bots (alts as companions) | Companions | 05 | open |
| [14](14-command-parser.md) | Companion chat commands and menus (party-only) | Companions | 13 | v1.4.0 |
| [15](15-economy-caps.md) | Per-map yield caps and daily meso cap | Economy | 07 | v1.3.0 |
| [16](16-bots-live.md) | Bring the bot world live | Rollout | 01-15 (owner choice) | done (bots live since v1.3.0) |
| [17](17-daily-checkin-release.md) | Ship Daily Check-In | Addons | - | v1.4.0 (on live) |
| [18](18-addon-setequip.md) | SetEquipV3 addon | Addons | - | v1.4.0 |
| [19](19-addon-monsterbook.md) | MonsterBook addon | Addons | - | v1.4.0 |
| [20](20-launcher-packs.md) | Packed first install for remote friends | Launcher | - | open |
| [21](21-world-events.md) | Scripted world events with bot reactions | Later | 16 | v1.4.0 (scheduler off) |
| [22](22-jar-data-resolver.md) | Jar-only data loading | Later | - | v1.4.0 |
| [23](23-live-backups.md) | Scheduled live backups | Ops | - | done (ops) |
| [24](24-client-cleanup.md) | Retire the junction test clients | Client | - | open |
| [25](25-hooks-doc.md) | SOLOMAPLING_HOOKS.md: list every core file SoloMapling touches | Ops | - | open |
| [26](26-bot-chat-polish.md) | Bot chat follow-ups from the persistence work | Real bots | 16 | v1.4.0 |
| [27](27-launcher-extras.md) | Launcher extras (self-update first) | Launcher | - | partial (self-update done) |
| [28](28-addon-notes.md) | Addon decisions recorded | Addons | - | open (decisions) |
| [29](29-tutorial-island-option.md) | Optional Maple Island tutorial for new characters | Gameplay | - | open |
| [30](30-bot-rebirth.md) | Bot rebirths, veteran bots and the 5th tier | Real bots | 06 | open (owner decision) |
| [31](31-leveling-path.md) | MapleRoot leveling path (bands, regions, rebirth gates) | Real bots | 03,30 | v1.4.0 |
| [32](32-bot-skill-alignment.md) | Align bot attacks with real players, in waves | Real bots | - | partial: wave 1 v1.3.0; 4th job on `bot-4th-job-skills` |
| [33](33-human-movement-flaws.md) | Human movement flaws: missed jumps, falls, route variety | Nav v2 | 04 | v1.4.0 |
| [34](34-swim-navigation.md) | Swim navigation for Aqua Road | Nav v2 | 01,03,04 | v1.4.0 |
| [35](35-admin-dashboard.md) | Admin dashboard: nav learning map viewer, bots, economy | Ops | 04,15 | partial: code v1.3.0/v1.4.0, test dashboard only |
| [36](36-fast-slow-chat-brain.md) | Fast/slow chat brain: GLiNER Decide gates and classifies, Qwen replies | Real bots | 1.3.0 | phase 1 v1.4.0 |
| [37](37-natural-conversations.md) | Natural conversations: talk window on demand, era-true replies | Real bots | 36 | phases 1-2 v1.4.0 |
| [38](38-bot-economy-behaviour.md) | Bot economy behaviour: meso plans, FM pricing from real trades, sinks | Real bots / Economy | 09,12,15 (after 1.3.0) | open |
| [39](39-client-dgvoodoo-wrapper.md) | Client graphics wrapper for virtual displays | Client | 1.3.0 | open |
| [40](40-player-guide-and-wiki.md) | Player guidebook and server wiki | Content / Docs | 1.3.0, 37 | v1.4.0 tree, not published |
| [41](41-world-name-bera.md) | Run the live world as Bera | Server / Client / Ops | - | v1.3.1 |
| [42](42-live-monitoring-alerts.md) | Live monitoring and alerts | Ops | 35 | v1.4.0 (on live) |
| [43](43-gm-security-audit.md) | GM account safety and command audit | Security / Ops | - | v1.4.0 |
| [44](44-release-pipeline.md) | One-command release pipeline | Ops | - | v1.4.0 (used for 1.4.0) |
| [45](45-backups-and-rotation.md) | Back up bot learning data, rotate old backups | Ops | 23 | v1.4.0 (on live) |
| [46](46-discord-bridge.md) | Discord bridge and server status | Community | 42 | partial: status + news v1.4.0 |
| [47](47-bot-chat-content-review.md) | Review bot chat and megaphone content | Real bots / Chat | - | v1.4.0 |
| [48](48-live-capacity-soak.md) | Capacity soak with real players and bots | Performance | - | postponed (tools in v1.4.0) |
| [49](49-bot-buddies.md) | Bots as buddies: accept, send, buddy chat, online status (BUILD, M) | Real bots / Social | 26,36,37 | built on `bot-buddies`, not deployed |
| [50](50-guilds.md) | Guilds: fix creation near bots (FIX, S), bot-run guilds and guild chat (BUILD, L) | Server / Social | 49 (B,C) | 50A on `social-fixes-1.4.1` (1.4.1); B,C open |
| [51](51-bot-party-chat-meetups.md) | Parties with bots: party chat, meet-ups, buddy invites (BUILD, S-M) | Companions | 14,49 | open |
| [52](52-marriage-and-rings.md) | Marriage, weddings, couple/friendship rings (FIX rings M, verify weddings S, bot weddings M) | Server / Cash Shop / Social | 21 | open |
| [53](53-family.md) | Family system: enable and finish the entitlements (ENABLE+FIX, M) | Server | - | open |
| [54](54-messenger-and-memos.md) | Messenger and memos with bots (BUILD, S) | Social | 49 | open |
| [55](55-duey-storage-fredrick.md) | Duey, Ariant storage, Fredrick guard (ENABLE/FIX, S) | Server / Economy | cosmic-diff report | Fredrick + Rosen restored on `cosmic-diff-1.4.1` (1.4.1); Duey open |
| [56](56-bot-fm-buy-cap-and-meso-sink.md) | Cap what bots pay real players; bot wealth ceiling (BUILD, S / S-M) | Economy | 15 | 56A on `social-fixes-1.4.1` (1.4.1); B open |
| [57](57-bot-population-500.md) | Fewer, better bots: ~500 at peak, spread ch1-heavy over the channels (BUILD, M) | Real bots / Population | 08, 10 | done on `bots-population-142` (1.4.2) |
| [58](58-bot-affinity.md) | Affinity: bots like or dislike players, `@botdrama`, earned speed, bot couples and weddings (BUILD, L) | Real bots / Social | 49, 21, 52 | 1.5.0 part on `bots-affinity-150` |

## 1.6.0 sprint (current roadmap work)

The older table above is historical. Current sprint state and verification: [sprint board](../release/1.6.0-sprint.md).

| Ticket | Work |
|---|---|
| [60](60-bot-brain.md) | Bot brain, RC1 review |
| [92](92-fishing.md) | Fishing for players and bots |
| [93](93-achievements-medals.md) | Achievements and medals |
| [94](94-daily-dungeon.md) | Daily dungeon |
| [95](95-chaos-bosses.md) | Chaos Zakum and Horntail |
| [96](96-chat-understanding-routing.md) | Chat requests and routing (59 follow-up) |
| [39](39-client-dgvoodoo-wrapper.md) | Parsec graphics fix |
| [97](97-client-settings-window.md) | In-client settings window |
| [98](98-evan-compatibility-trial.md) | GM-only Evan research, excluded from 1.6.0 release |
| [61](61-client-mcp.md) | Client visual QA |

## Priorities after 1.4.0

The owner's list from `docs/release/backlog-after-1.4.0.md` (2026-10-21), in order, with the tickets they touch.

**For 1.4.1**

| Item | Ticket |
|---|---|
| Coloring Prism: fix the QA issues (dye weapon, effects, hair/eyes, persistence, others seeing it; no reset button) and re-test | 28 (branch `addon-colorprism`) |
| Bots stuck on top of ladders: fix it; at most a rare deliberate fumble, never a permanent stall | 33 |
| 4th-job bot attacks: AFK check with `!botshowcase 4th 10` and the attackdiag log, then a day of EXP pace | 32 (`bot-4th-job-skills`) |
| Channel load bars count bots in full: count a bot as a fraction of a player, then CHANNEL_LOAD back to 100 (live has 300 as a stopgap) | - |
| Cash Shop slot expansion items (9111000-9114000) have no icons: add them via Custom.wz | - |
| Newer pet equips (355 hidden in 1.4.0): find what their WZ needs, or keep them hidden | - |
| Test notes page: ready `!item` commands for set-bonus tests (template: `tools/release/notes-page/`) | 18, 44 |
| GM hide: bots still notice a hidden GM and monsters stay frozen | 43 |
| Release tools: a check so a stray copy like the 1.4.0 `/files` incident cannot recur | 44 |

**Next release:** compare every class against stock Cosmic for stripped or hollowed code (ScrollHandler was the first
find), sort deliberate from broken, restore the broken ones with tests.

**Later (social systems):** friends with bots, marriages, guilds, family.

## Social systems (tickets 49-56, research 2026-10-21): suggested order

See docs/real-bots/social-systems-research.md.

1. 50A, the guild-creation fix (S): done in `ad0359efc` — bots are no longer eligible cofounders, so a bot in the
   Guild HQ cannot block a creation, and an unanswered creation expires after 60 s.
2. 56A, the FM buy cap (S): before real players open shops.
3. 55 (S).
4. 49, buddies (M).
5. 51 (S-M).
6. 52: rings, then the wedding check, then bot weddings.
7. 53.
8. 50B/C.
9. 54.
