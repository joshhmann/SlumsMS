# Backlog audit for 1.6.0 (2026-10-25)

Audit of every file in `docs/tickets/` against the code and git history of `152-integration` (= live 1.5.2 plus docs) and the
unmerged `origin/160-*` branches. No ticket file was edited. Read-only audit; the ticket headers and `README.md` index are
stale in many places (listed below), so use this table, not the headers.

Classes: **A** close now (done or obsolete), **B** finish in 1.6.0 (rc1 = "bots feel alive", or the rest of the 1.6.0 list),
**C** move to 1.7.0 (or park), **D** needs an owner decision.
"Live" means contained in `origin/release-1.5.1` / `152-integration`. Evidence is a commit sha, a branch that is an ancestor of
`152-integration`, or a file path.

## Summary

90 ticket files (numbers 01-91, with 04a-c and 58c counted; README.md and HANDOFF.md are not tickets).

| Class | Count | Tickets |
|---|---|---|
| A close now | 56 | 01, 02, 03, 04, 04b, 04c, 05, 06, 07, 08, 09, 10, 11, 12, 14, 15, 16, 17, 18, 19, 21, 22, 23, 26, 27, 28, 31, 33, 34, 36, 37, 38, 41, 42, 43, 44, 45, 46, 47, 49, 50, 51, 52, 55, 56, 57, 58, 59, 62, 65, 67, 68, 69, 75, 80, 88 |
| B finish in 1.6.0 | 22 | 13, 25, 32, 39, 40, 48, 58c, 60, 61, 63, 64, 66, 77, 78, 79, 84, 85, 86, 87, 89, 90, 91 |
| C move to 1.7.0 / park | 6 | 04a, 54, 76, 81, 82, 83 |
| D owner decision | 6 | 20, 24, 29, 30, 35, 53 |

Plus 7 tickets that exist only on `origin/160-life-in-town` (second table): B 5, C 2, D 0, A 0.
Also 6 roadmap items have no ticket at all (see "Missing tickets").

Key findings:
- **The 1.6.0 headline is already built but unmerged.** The bot brain (60), fishing (68*), chat semantic guard (72) are on
  `160-bot-brain` / `160-life-in-town` / `160-convo-scope` (tag `v1.6.0-rc1`). They branch from 1.5.1, so they need a merge-forward
  onto 152-integration (183 commits ahead of release-1.5.1) before rc1 is cut. Companions, party chat (85), megaphone buy
  requests (86) and owned alts (13) are NOT built.
- **Number collision:** `160-life-in-town` adds tickets 68-74, but 152-integration already uses 68 (Cosmic audit) and 69 (vb-*
  auto-QA). Renumber the branch tickets (suggest 92-98) when merging; the table marks them `68*`, `69*`.
- **A lot of A-class is just stale headers:** 11, 12, 49, 52, 55, 56, 57, 59, 62, 65, 67, 69, 75, 80 say open or "not deployed"
  but are done and live.
- **Genuinely unfixed bugs found:** 63 (bot debits dropped at shutdown deadline), 64 (wealth scroll bonus reads world 0, Bera is
  world 1), 66 (KS-war helpers on other ledges).

## Overlaps and duplicates

- **Companions epic: 13 (owned alts) + 60 (bot brain) + 85 (party chat) + 87 (nutnnut logic) + 51 + 14.** 14 is done (parser).
  51 is wholly superseded by 85 + the Companions rules in 1.6.0.md. 13 and the "bot-friend companions" share the 5-slot cap,
  the party rules and the command set, so build one companion layer (60 supplies the decisions, 14 the commands) and have
  13 only add the "owned character" identity kind. 87 is research that should land first and feed both; its "party/PQ play"
  findings overlap the PQ-helper roles in the grill. Write one new Companions ticket and make 13/85/87 children.
- **36 vs 60 (and 37, 59, 72):** 36/37 (chat brain phases, talk window) shipped and 59 built on them; 60's "bots do what they say"
  consumes 59's reply+action contract. Chat model/routing work continues as branch ticket 72. Close 36, 37, 59; keep 60 and 72.
- **39 vs 90 item 2 vs 73:** dgVoodoo2 (39) and the D3D9 wrapper + ImGui (90.2) are the same Parsec fix by two routes; ImGui is
  also the settings-window host (73). Decide the route once; close 39 into 90.
- **78 + 79 (+ 86):** Basil Market (79) needs the 78 site; 86 (megaphone buy requests) and 79 both have bots selling to players, so
  share the bot-seller offer/haggle code.
- **38 / 56 / 64:** all bot economy; 38 is parked by the owner's rule, 56 is done, 64 is a standalone bug.
- **62 / 63 / 65:** shutdown trio. 62, 65 done; 63 open.
- **81 / 82 / 83:** all 1.7.0 content; 83 (WZ tools) unlocks 81 and 82.
- **20 / 27 / 67:** launcher; 27 and 67 resolved, 20 open.
- **61 / 84 / 69 / 58c:** QA tooling; make real-client checks (58c, 32) one routine run per rc.

## Ticket table (docs/tickets/*.md on 152-integration)

| # | Title | Stated status | Real status | Evidence | Class | Note |
|---|---|---|---|---|---|---|
| 01 | Merge nutnnut nav v60-67 (graph v68) | shipped v1.3.0 | done and live | `nav-v2` in 152-integration; tag v1.3.0 | A | Already marked shipped; close. |
| 02 | Mobility skills per class | shipped v1.4.0 | done and live | `mobility-skills` in tree | A |  |
| 03 | Cross-map world graph | shipped v1.3.0/1.4.0 | done and live | `travel`, `high-level-routes` in tree | A |  |
| 04 | Nav learning on new edge types | shipped v1.3.0 | done and live | `nav-learning`, `nav-player-learning` in tree | A |  |
| 04a | Nav lab (continuous learning, no players) | open | not started | only test-side `NavMobilityLab`/`SwimNavLab`; no background job | C | Nice-to-have; nightly bake (04b) already covers most; not on the 1.6.0 list. |
| 04b | Nightly deep bake | shipped v1.3.0 | done and live | `nav-ops`; `slumsms-nav-bake.timer` on live | A |  |
| 04c | Daily stuck-hotspot report | shipped v1.3.0 | done and live | `nav-ops`; timer on live | A |  |
| 05 | Persist real stats | shipped v1.3.0 | done and live | `real-stats` in tree | A |  |
| 06 | AP/SP builds and job advance | shipped v1.3.0 | done and live | `real-stats` in tree | A |  |
| 07 | Real combat damage | shipped v1.3.0 | done and live | `combat` in tree | A |  |
| 08 | Real potion and ammo use | shipped v1.3.0 | done and live | `supplies` in tree | A |  |
| 09 | Buy supplies at NPC shops | shipped v1.3.0 | done and live | `shops` in tree | A |  |
| 10 | Equip the best owned gear | shipped v1.3.0 | done and live | `gear` in tree | A |  |
| 11 | Real scrolling and gachapon | open (gacha 'not built') | done and live (status stale) | bot scrolling `29d61aefc` + `economy-bot-scrolling-142`; gachapon `economy-gacha-150`, redesign `319c1555b`; all ancestors of release-1.5.1 | A | Header is stale: both halves shipped. |
| 12 | Sell and trade | open | done and live (status stale) | `economy-trade-150`, `bf32af563` (bot shops, direct trades, price board), `7e95afa36` (FM bots price/buy), `BotTradeSystem` | A | Header is stale. |
| 13 | Owned alt-bots (alts as companions) | open | not started | no owned-alt identity kind in code; owner grill 2026-10-25 puts it in 1.6.0 | B | Part of the Companions epic; commands exist via 14; shares the 5-slot cap with bot friends. Overlaps 60, 85, 87, 51. |
| 14 | Companion chat commands and menus | shipped v1.4.0 | done and live | `command-parser` in tree; `BotPartySystem/BotCommandParser` | A | Companion command set in the grill (formations, supplies, gear share) extends this, belongs to 13/Companions. |
| 15 | Per-map yield caps and daily meso cap | shipped v1.3.0 | done and live | `economy` in tree; `BotYieldCaps` | A |  |
| 16 | Bring the bot world live | done | done and live | live runs 3,000 bots (HANDOFF s.1) | A |  |
| 17 | Ship Daily Check-In | shipped v1.4.0 | done and live | `daily-checkin-2` in tree; migration 004 | A |  |
| 18 | SetEquipV3 addon | shipped v1.4.0 | done and live | `addon-setequip` in tree | A |  |
| 19 | MonsterBook addon | shipped v1.4.0 | done and live | `addon-monsterbook` in tree | A |  |
| 20 | Packed first install for remote friends | open | not started | no chunk/pack code in server repo; launcher repo not checked beyond tags v1.5.1/v1.5.2 | D | Launcher lane; memory notes say the 23.6 GB client needs first-install chunks before hosting widely. |
| 21 | Scripted world events with bot reactions | shipped v1.4.0 | done and live (scheduler off by default) | `world-events` in tree; `worldevents/` | A | Turning the scheduler on is an owner call, not an open ticket. |
| 22 | Jar-only data loading | shipped v1.4.0 | done and live (known gap) | `data-resolver` in tree; HANDOFF notes a jar-only loading gap | A | Gap noted in HANDOFF; track as a bug if it bites. |
| 23 | Scheduled live backups | done | done and live; superseded by 45 | ticket 45 scripts | A |  |
| 24 | Retire the junction test clients | open | not started (owner PC chore) | cannot verify from this box (Z:\MSv83) | D | Needs owner OK to archive old test clients. |
| 25 | SOLOMAPLING_HOOKS.md | open | not started | no such file in repo | B | Small docs task, any time in 1.6.0; low value now that the fork is merged; could also be dropped. |
| 26 | Bot chat follow-ups from persistence work | shipped v1.4.0 | done and live | `chat-polish` in tree | A |  |
| 27 | Launcher extras | self-update done; rest open | superseded | Electron launcher replaced by Tauri 1.5.2 (live 2026-10-24); launcher tags v1.5.1/v1.5.2 | A | Optional items (status endpoint, source link, registration webhook) belong in a launcher backlog, not here. |
| 28 | Addon decisions recorded | open (no action) | obsolete | Coloring Prism since built: `addon-colorprism` in tree, migration 010 | A | Decision record only. |
| 29 | Optional Maple Island tutorial | open | not started | only the reverse path exists (`009fd7cde`, Maple Administrator -> hub) | D | Owner never scheduled it. |
| 30 | Bot rebirths and the 5th tier | open (owner to pick approach) | not started | veteran-fill config exists (`BOT_ROSTER_VETERANS_PER_DAY`, `BotRosterLifecycle`) but no rebirth logic for bots | D | Needs the approach picked. |
| 31 | MapleRoot leveling path for bots | shipped v1.4.0 | done and live | `leveling-path` in tree; `LevelingPath.yaml` | A |  |
| 32 | Align bot attacks with real players | open (waves) | partly done | wave 1 v1.3.0; 4th-job wave `bot-4th-job-skills` in release-1.5.1; remaining items need a real client | B | Verify new skills on a real client (memory: client drops remote attacks with wrong pose/weapon); do inside the client-QA routine. |
| 33 | Human movement flaws | shipped v1.4.0 | done and live | `movement-flaws` in tree | A |  |
| 34 | Swim navigation | shipped v1.4.0 | done and live | `swim-nav` in tree | A |  |
| 35 | Admin dashboard | in progress | partly done (TEST only) | `dashboard`, `dashboard-next` in tree; runs on 164:8091 only | D | Is the dashboard going on live? |
| 36 | Fast/slow chat brain (GLiNER Decide + Qwen) | phase 1 shipped | done and live; later phases moved | `chat-natural` in tree; follow-ups are 59 and branch ticket 72 | A | Overlaps 37, 59, 72, and 60 ('bots do what they say'). Close; keep chat model work under 72. |
| 37 | Natural conversations (talk window) | phases 1-2 shipped | done and live; superseded by 59 | `chat-natural`, `chat-attention` in tree | A | Only the owner's in-game check was open. |
| 38 | Bot economy behaviour (meso plans, pricing, sinks) | open | superseded / parked | much of it shipped via 56, gachapon, `bf32af563`; 1.7.0.md: 'No economy/ledger work without the owner asking' | A | Close as parked; reopen only if the owner asks. |
| 39 | Client graphics wrapper (dgVoodoo2) | open | not started (audit only, on branch notes) | 160-life-in-town sprint board: 'audit client/render path'; no wrapper built | B | Duplicates 90 item 2 (D3D9 wrapper + ImGui as the Parsec fix and settings host). Run as ONE item: pick the approach in 90, close 39 into it. |
| 40 | Player guidebook and server wiki | text and wiki shipped; not published | partly done | `guide-wiki` in tree, `@guide` tour in 1.5.1 (`342f9e915`); client Custom.wz release and publish pending | B | Publish the wiki/guide with 78's site work. |
| 41 | Run the live world as Bera | shipped v1.3.1 | done and live | `world-bera` in tree | A |  |
| 42 | Live monitoring and alerts | shipped v1.4.0 | done and live | `monitoring` in tree; timers on live | A | Dashboard strip depends on 35. |
| 43 | GM account safety and command audit | shipped v1.4.0 | done and live | `gm-safety` in tree, migration 009 | A | Leftover ideas (@gmauth, GM hide) are minor. |
| 44 | One-command release pipeline | shipped v1.4.0 | done and live | `release-pipeline`; `758ff4a76` release-live.sh | A |  |
| 45 | Backups and rotation | shipped v1.4.0 | done and live | `backups` in tree; restore drill passed | A |  |
| 46 | Discord bridge and server status | status + news shipped | done and live (registration notices not done) | `monitoring` in tree | A | Chat bridge explicitly not wanted; registration notices optional. |
| 47 | Review bot chat and megaphone content | shipped v1.4.0 | done and live | `chat-content` in tree | A |  |
| 48 | Capacity soak with real players and bots | postponed to next RC | partly done (tools shipped) | `capacity-soak` NOT merged (last smoke-run commit only there); tools in tree | B | Run the soak against 1.6.0-rc1 (brain adds load); also the pending soak plan from ticket 80. |
| 49 | Bots as buddies | built, not deployed | done and live (status stale) | `bot-buddies` is an ancestor of release-1.5.1 | A | Header says not deployed; it is. |
| 50 | Guilds: creation, bot guilds, guild chat | A built; B/C built 1.5.0 | done and live | `bots-guilds-150` in release-1.5.1 | A |  |
| 51 | Parties with bots: party chat, meet-ups, bots invite buddies | open | not started; superseded by 85 + Companions | MultiChatHandler.java:64-66 sends party chat to no bot logic | A | Fold into 85 (party chat) and Companions (recruit/invites). Close as duplicate. |
| 52 | Marriage, weddings and rings | open | done and live (status stale) | `gameplay-partner-rings-150`, `gameplay-marriage-result-150`, `bots-affinity-150`/`bots-drama-150` (bot couples, weddings); PartnerRingScenarios QA | A | Same-gender client check is in 58-client. |
| 53 | Family system | open | not started | `USE_FAMILY_SYSTEM` default false; only `5ab28f6c1` ('coming soon' text); six entitlements still stubbed | D | Do we want the family system at all? |
| 54 | Messenger and memos with bots | open, low priority | not started | Messenger only in NXMerchantBot / debug | C | Low priority; not in the 1.6.0 list. |
| 55 | Duey, storage and Fredrick fixes | Duey done; rest open | done and live (status stale) | Duey `2e3cc3148`; `scripts/npc/2110000.js` exists; `FredrickProcessor.java:314 merchant.clearItems()` present | A | All three parts done. |
| 56 | Cap what bots pay players; bot wealth ceiling | open (recommendation) | done and live (status stale) | 56A `6b58d62b9`; 56B `319c1555b`, `25a2adec8` | A |  |
| 57 | Fewer, better bots (500) spread over channels | done, not deployed | done and live; numbers superseded | `bots-population-142` in release-1.5.1; live runs 3,000 bots peak, 5 channels | A | Owner direction since is ~500 bots again (memory 2026-10-21): decide if 3,000 stays; not a ticket. |
| 58 | Affinity between bots and players | 1.5.0 part built; bot-bot friendships open | done and live (bot-bot friendships open) | `bots-affinity-150`, `bots-drama-150` in release-1.5.1 | A | Bot-bot friendships re-enter via the brain (60: friends pull, rivals push). |
| 58c | (58 client lane) same-gender marriage check on v83 client | no status | not verified | server side done on `bots-affinity-150`; nothing run on a real client | B | Add to the client QA routine (Start-QA.cmd). |
| 59 | Natural bot conversations | built, not deployed | done and live (status stale) | `bots-convo-150` in release-1.5.1 | A | Follow-up work is branch ticket 72 and `160-convo-scope`. |
| 60 | Bot brain (headline) | 'Not started' | done on unmerged branches; not live | `160-bot-brain` (25 commits), `160-life-in-town`, tag `v1.6.0-rc1` = `93cfe9b33` on `test-1.6.0-rc1`; real-client review pending; branches based on 1.5.1, not on 152-integration | B | rc1 headline. Needs a merge-forward onto 152-integration. Status header on 152 is stale. Companions NOT built. |
| 61 | Client-driver MCP (visual QA) | spec | built; worker unavailable | `tools/ops/client-mcp`; `160-life-in-town` rewrites the header to 'built and accepted' (3-cycle receipt 2026-10-24) | B | Update header on merge; get the PC worker back and make it a routine rc step. |
| 62 | Save active player sessions before bot shutdown | open | done and live (status stale) | `4d08a0cba` (players first, 60 s bot budget) in release-1.5.1 | A |  |
| 63 | Preserve bot debits when shutdown save budget expires | open | not fixed | `BotStore.settleAndSaveAll` still returns `unsaved` at the deadline (BotStore.java ~1045-1056); only the 62 budget change landed | B | Small dupe-risk fix; bug => 1.6.0. |
| 64 | Use Bera's meso rate for wealth scroll bonus | open | not fixed | `BotWealth.worldMesoRate()` still `getWorld(0)` (BotWealth.java:279-290), `scrollBonus` uses it (:128) | B | One-line class of fix plus a test. |
| 65 | Report failed player saves accurately at shutdown | open | done and live (status stale) | `32806f0a3`, merged `2b990a20f` into 1.5.1 | A |  |
| 66 | KS-war helpers on their own platform | (none) | partly mitigated, still flaky | `151-qa-chat-gate` partial fix; `e57678500` (QA stands fight on lowest ledge); roadmap 'still open from 1.5.x' | B | Part of 'bots overlapping / floating' cleanup; low. |
| 67 | Tauri launcher on launcher repo main + tags | open | done (status stale) | launcher repo: tags v1.5.1, v1.5.2; main contains `573d7a2`; server repo `bcfc3afbe`, `04ef9c176` | A |  |
| 68 | MapleRoot-vs-Cosmic audit | audit done '2026-10-26' | done and live | `151-cosmic-audit` merged into release-1.5.1; fixes `039d13164`, `1fe365733` | A | Header date 2026-10-26 is in the future (typo). Open questions moved to 89 and the divergence ledger. |
| 69 | Auto-QA 'v83 basics' (vb-*) | open | done in 152-integration | `7a3ef3080`; 16 vb-* in `V83Scenarios.java` | A |  |
| 75 | Amoria PQ for friends (2-6 players) | open | done in 152-integration | `35723c08f`; `AmoriaPQ.js` `onlyMarriedPlayers=false`; `AmoriaPqRulesTest` | A | Real-client 2-player run is the roadmap's 'scaling after a 2-player test' (owner decides later). |
| 76 | Nett's Pyramid PQ | parked | not started (owner: leave off) | no event script; Cosmic PR #221 unmerged | C | Not on 1.7.0 either; keep parked. |
| 77 | Omok and Match Cards, then bots play | idea | not started | only the ticket commit `85c4cc114`; no minigame fixes since | B | Part 1 (players' rooms work) is a bug => 1.6.0 with a QA check. Part 2 (bots play): see question below. |
| 78 | Hidden Street database site | owner OK'd | partly done | generator `tools/hiddenstreet/` merged `e7ef997d2`; no site pages in `site` branch | B | Phase 2 site pages in 1.6.0 per roadmap; after rc1. |
| 79 | Basil Market | owner OK'd, 1.6.x | not started | no code; depends on 78 site and bot brain | B | Hobby kept in 1.6.0 by owner; not on cut list but is the next cut candidate. |
| 80 | Port nutnnut stability fixes (1.5.2) | open | done and live in 1.5.2 | `922510f92` merge of `152-nutnnut-stability`; migration 023 | A | 3,000-bot soak result: see 48. |
| 81 | Post-Big-Bang areas (Grandis, Arcane River, ...) | parked, keep | not started (owner: keep for rebirth-3) | drop_data 0 rows for 305 mobs; roadmap 1.7.0 'Grandis / Arcane River' | C | Already on 1.7.0. |
| 82 | Finish MapleRoot quest chains | open | partly done (hide only) | `HiddenQuests` gate shipped 1.5.2; chains not finished | C | Already on 1.7.0. |
| 83 | WZ Mod Tool Suite | planned 1.7.0 | not started | docs only `51f29dda9` | C | Already on 1.7.0. |
| 84 | Parallel auto-QA | planned 1.6.0 | not started | `091a60712` ticket only; `autoqa-run` has only a `--fast` lane | B | On the 1.6.0 list; do alongside rc1 because every rc needs full auto-QA. |
| 85 | Party chat with player bots | (1.6.0) | not started on 152; no commit on 160 branches | MultiChatHandler.java:64-66 | B | rc1 item. Absorbs 51. |
| 86 | Bots answer players' megaphone buy requests | (1.6.0) | not started | `BotMegaphoneSystem` has no buy-request logic | B | rc1 item. Overlaps 79 (Basil Market) and 12/56 (FM rules). |
| 87 | Second pass through nutnnut/Cosmic for bot logic | (1.6.0) | not started (research) | no report committed | B | Do first inside rc1: its output feeds Companions/13 and 60. Overlaps 13, 60. |
| 88 | Job Master advancement fees | (1.5.2) | done and live in 1.5.2 | `c679bac1b`, final fees `f57944b2a` (free/800k/15m/100m) | A | Ticket text already has the 800k/15m figures. |
| 89 | MapleRoot NPC owner decisions | (1.6.0) | partly done | JQ chests + stylist `809c5a3b0` in 1.5.2; grill decisions on 2026-10-25 are recorded in 1.6.0.md | B | Remaining: custom-NPC flag after per-NPC cost review, Nimble to FM, rebirth consumes key, Pink Bean exit NPC, CPQ 2+ players lv 30-50. |
| 90 | Client patches for 1.6.0 | (none) | partly started (client repo only) | `/root/work/client/src-160-invresize`, `src-160-settings`; settings window built `client-160-settings` @ `360b2e7` | B | Items: inventory resize, D3D9 wrapper/ImGui (dup 39), UI polish, 4-byte HP/MP (owner said yes, cap 30,000 -> 99,999). |
| 91 | Movable bottom bar / HP and MP gauges | (none) | not started | origin vector exists in `resolution.cpp` | B | Low; after 90. Could slip to 1.7.0 'Client quality-of-life' if rc2 is tight. |

## Tickets that exist only on origin/160-life-in-town

| # | Title | Stated status | Real status | Evidence | Class | Note |
|---|---|---|---|---|---|---|
| 68* | Fishing for players and bots | branch: implemented, isolated TEST verified | done on unmerged branch | `160-life-in-town` `a572d4361`, `3f8250967`; ticket file `68-fishing.md` | B | rc1 follow-on. Owner approved exact 90 s common table; real-client review pending. NUMBER CLASHES with 68. |
| 69* | Achievements and medals | branch: 30-goal proposal ready | partly done | `docs/achievements/1.6.0-proposal.md`; no progress/claim service | B | Hobby kept. Owner picks the ~30 medals (cosmetic only so far). NUMBER CLASHES with 69. |
| 70 | Daily dungeon | branch: planned | not started | ticket only | C | On the owner's cut list. |
| 71 | Chaos Zakum and Horntail | branch: planned (compat audit first) | not started | ticket only; chaos mob data present on TEST | C | On the owner's cut list. |
| 72 | Chat requests and routing (59 follow-up) | branch: semantic guard implemented | done on unmerged branch (guard); routing eval open | `160-life-in-town` `d1664e81c`, `c2215a4f2`; `160-convo-scope` 10 commits, fresh-v2 70.2% -> 85.7%, fresh-v3 `23d236b4b` | B | rc1 'chat rules fix'; Jev shadow on TEST next. Covers the 'Chat rules scope fix' and 'Smarter chat routing' roadmap rows. |
| 73 | In-game settings window | branch: built, 20 native tests | done on unmerged branch (client repo) | client `client-160-settings` @ `360b2e7`; real-game visuals pending | B | Client line; ships with the client release. |
| 74 | GM-only Evan compatibility trial | branch: planned research | not started | ticket only | B | Alongside (own branch, never in 1.6.0 release). |

## Roadmap items with no ticket

1. **Companions (bot friends, up to 5 at once)** - the biggest rc1 gap; see overlap above.
2. **Busy FM again** - owner says the FM is bare since 1.5.2, under investigation; rc1 item with no ticket.
3. **Quest pass (v83)** - "ticket to write" per 1.6.0.md; cut-list item (move to 1.7.0 if time runs short).
4. **Bugs after 1.5.2 intake** - container, not a ticket; suggest one rolling ticket.
5. **Client QA routine (`Start-QA.cmd`)** - small tooling item; fold into 61.
6. **Quick NPC fixes** (Nimble to FM, rebirth consumes key, Pink Bean exit, CPQ 2+ lv 30-50) - sit inside 89; a checklist ticket would help.

## Stale headers (do not trust, fix when closing)

11, 12, 49, 52, 55, 56, 57, 59, 60 ("Not started"), 61 ("spec"), 62, 65, 67, 68 (date 2026-10-26 is in the future), 69, 75, 80,
88 (no status line), plus `docs/tickets/README.md` index and `docs/tickets/HANDOFF.md` (dated 2026-10-19, says live is 1.2.1).
`docs/TASKS.md` is the old MapleRoot MR-xxx list (Oct 18), unrelated to these tickets.

## Questions for the owner (class D)

1. **Ticket 20:** Do you want packed first-install chunks (23.6 GB client) before more remote friends join, or keep per-file updates?
2. **Ticket 24:** OK to archive or delete the old addon test clients and junction clients under `Z:\MSv83\Integration\Addons`?
3. **Ticket 29:** Do you want an opt-in Maple Island tutorial for new characters, or drop it?
4. **Ticket 30:** Bot rebirths: earned-path rebirth for bots, veteran-only slice, or none (rebirth-3 content for players only)?
5. **Ticket 35:** Should the admin dashboard go on live (it runs on TEST only), or stay TEST-only?
6. **Ticket 53:** Do we want the family system for friends (turn on and implement the six stubbed entitlements), or keep the "coming soon" button?
7. **Ticket 77 (row stays B):** Omok/Match Cards for players is a 1.6.0 bug fix; should bots playing omok be 1.6.0 (brain activity) or 1.7.0?

## Decisions applied (2026-10-25)

Owner decisions applied on `152-integration` (docs only):

1. **Closed all 56 class-A tickets** (status line set to "closed 2026-10-25 (...)" with the audit evidence; bodies untouched): 01, 02, 03, 04, 04b, 04c, 05, 06, 07, 08, 09, 10, 11, 12, 14, 15, 16, 17, 18, 19, 21, 22, 23, 26, 27, 28, 31, 33, 34, 36, 37, 38, 41, 42, 43, 44, 45, 46, 47, 49, 50, 51, 52, 55, 56, 57, 58, 59, 62, 65, 67, 68, 69, 75, 80, 88. Also closed 39 (folded into 90 item 2).
2. **Overlaps:** new Companions epic (99) linking 13, 14, 51, 60, 85, 87 (parent line added to 13, 14, 60, 85, 87; 51 closed as folded in). 39 folded into 90 (line added in 90).
3. **New tickets:** 99 Companions, 100 Busy FM, 101 v83 quest pass, 102 Quick NPC fixes (from 89), 103 1.6.0 bug intake.
4. **Owner calls on class D (all set to 1.6.0):** 30 earned rebirth path for all bots; 35 dashboard on live, read-only, behind basic-auth nginx; 53 family system on with the six entitlements; 29 opt-in Maple Island tutorial; 20 first-install chunks before more remote friends; 24 clean old addon clients under `Z:\MSv83\Integration\Addons` (archive first, list before deleting, listing reviewed by Claude/owner, desktop not touched); 77 bots play omok/match cards and accept player challenges. Note: the question-6/7 wording above is superseded by these answers.
5. **Renumbering plan for branch `160-life-in-town` (branch NOT edited):** 68->92, 69->93, 70->94, 71->95, 72->96, 73->97, 74->98 (fishing, medals, daily dungeon, Chaos Zakum/Horntail, chat routing, settings window, Evan trial). New tickets here start at 99. Cross-references inside the branch (e.g. "72", "73") must be renumbered at merge.
6. Not done here: README.md index and stale headers of the B/C-class tickets (60, 61, etc.) are unchanged.
