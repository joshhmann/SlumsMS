# SlumsMS tickets

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
| [49](49-bot-buddies.md) | Bots as buddies: accept, send, buddy chat, online status (BUILD, M) | Real bots / Social | 26,36,37 | open |
| [50](50-guilds.md) | Guilds: fix creation near bots (FIX, S), bot-run guilds and guild chat (BUILD, L) | Server / Social | 49 (B,C) | 50A on `social-fixes-1.4.1` (1.4.1); B,C open |
| [51](51-bot-party-chat-meetups.md) | Parties with bots: party chat, meet-ups, buddy invites (BUILD, S-M) | Companions | 14,49 | open |
| [52](52-marriage-and-rings.md) | Marriage, weddings, couple/friendship rings (FIX rings M, verify weddings S, bot weddings M) | Server / Cash Shop / Social | 21 | open |
| [53](53-family.md) | Family system: enable and finish the entitlements (ENABLE+FIX, M) | Server | - | open |
| [54](54-messenger-and-memos.md) | Messenger and memos with bots (BUILD, S) | Social | 49 | open |
| [55](55-duey-storage-fredrick.md) | Duey, Ariant storage, Fredrick guard (ENABLE/FIX, S) | Server / Economy | cosmic-diff report | Fredrick + Rosen restored on `cosmic-diff-1.4.1` (1.4.1); Duey open |
| [56](56-bot-fm-buy-cap-and-meso-sink.md) | Cap what bots pay real players; bot wealth ceiling (BUILD, S / S-M) | Economy | 15 | 56A on `social-fixes-1.4.1` (1.4.1); B open |

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

1. 50A, the guild-creation fix (S): broken for players whenever a bot stands in the Guild HQ.
2. 56A, the FM buy cap (S): before real players open shops.
3. 55 (S).
4. 49, buddies (M).
5. 51 (S-M).
6. 52: rings, then the wedding check, then bot weddings.
7. 53.
8. 50B/C.
9. 54.
