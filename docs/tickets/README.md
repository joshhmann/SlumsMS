# SlumsMS tickets

Created 2026-10-19 from the owner's plan. Order: nav v2 → real bots → companions → economy → bots live → addons and later items. See `HANDOFF.md` for where everything runs.

| # | Title | Area | Depends on |
|---|---|---|---|
| [01](01-nav-v66-merge.md) | Merge nutnnut nav v60-67 into GCMoveSystem (graph v68) | Nav v2 | - |
| [02](02-mobility-skills.md) | Mobility skills per class | Nav v2 | 01 |
| [03](03-cross-map-travel.md) | Cross-map world graph (boats, taxis, ferries) | Nav v2 | 01 |
| [04](04-learning-on-v68.md) | Carry nav learning onto the new edge types | Nav v2 | 01 |
| [04a](04a-nav-lab.md) | Nav lab: continuous learning without players | Nav v2 | 04 |
| [04b](04b-nightly-deep-bake.md) | Nightly deep bake | Nav v2 | 01 |
| [04c](04c-stuck-report.md) | Daily stuck-hotspot report | Nav v2 | 01 |
| [05](05-real-stats-store.md) | Persist real stats: skills, AP/SP, job | Real bots | 01 |
| [06](06-builds-job-advance.md) | AP/SP builds and job advancement (stock classes) | Real bots | 05 |
| [07](07-combat-formula.md) | Real combat damage from stats, gear, skills and buffs | Real bots | 05 |
| [08](08-pots-ammo.md) | Real potion and ammo use | Real bots | 07 |
| [09](09-shop-resupply.md) | Buy supplies at NPC shops | Real bots | 03,08 |
| [10](10-gear-optimizer.md) | Equip the best owned gear | Real bots | 05 |
| [11](11-scroll-gacha.md) | Real scrolling and gachapon | Real bots | 10 |
| [12](12-sell-trade.md) | Sell and trade | Real bots | 10 |
| [13](13-owned-alt-bots.md) | Owned alt-bots (alts as companions) | Companions | 05 |
| [14](14-command-parser.md) | Companion chat commands and menus (party-only) | Companions | 13 |
| [15](15-economy-caps.md) | Per-map yield caps and daily meso cap | Economy | 07 |
| [16](16-bots-live.md) | Bring the bot world live | Rollout | 01-15 (owner choice) |
| [17](17-daily-checkin-release.md) | Ship Daily Check-In | Addons | - |
| [18](18-addon-setequip.md) | SetEquipV3 addon | Addons | - |
| [19](19-addon-monsterbook.md) | MonsterBook addon | Addons | - |
| [20](20-launcher-packs.md) | Packed first install for remote friends | Launcher | - |
| [21](21-world-events.md) | Scripted world events with bot reactions (built, branch `world-events`) | Later | 16 |
| [22](22-jar-data-resolver.md) | Jar-only data loading | Later | - |
| [23](23-live-backups.md) | Scheduled live backups | Ops | - |
| [24](24-client-cleanup.md) | Retire the junction test clients | Client | - |
| [25](25-hooks-doc.md) | SOLOMAPLING_HOOKS.md: list every core file SoloMapling touches | Ops | - |
| [26](26-bot-chat-polish.md) | Bot chat follow-ups from the persistence work | Real bots | 16 |
| [27](27-launcher-extras.md) | Launcher extras (self-update first) | Launcher | - |
| [28](28-addon-notes.md) | Addon decisions recorded | Addons | - |
| [29](29-tutorial-island-option.md) | Optional Maple Island tutorial for new characters | Gameplay | - |
| [30](30-bot-rebirth.md) | Bot rebirths, veteran bots and the 5th tier | Real bots | 06 |
| [31](31-leveling-path.md) | MapleRoot leveling path (bands, regions, rebirth gates) | Real bots | 03,30 |
| [32](32-bot-skill-alignment.md) | Align bot attacks with real players, in waves | Real bots | - |
| [33](33-human-movement-flaws.md) | Human movement flaws: missed jumps, falls, route variety | Nav v2 | 04 |
| [34](34-swim-navigation.md) | Swim navigation for Aqua Road | Nav v2 | 01,03,04 |
| [35](35-admin-dashboard.md) | Admin dashboard: nav learning map viewer, bots, economy | Ops | 04,15 |
| [36](36-fast-slow-chat-brain.md) | Fast/slow chat brain: GLiNER Decide gates and classifies, Qwen replies | Real bots | 1.3.0 |
| [37](37-natural-conversations.md) | Natural conversations: talk window on demand, era-true replies | Real bots | 36 |
| [38](38-bot-economy-behaviour.md) | Bot economy behaviour: meso plans, FM pricing from real trades, sinks | Real bots / Economy | 09,12,15 (after 1.3.0) |
| [39](39-client-dgvoodoo-wrapper.md) | Client graphics wrapper for virtual displays | Client | 1.3.0 |
| [40](40-player-guide-and-wiki.md) | Player guidebook and server wiki | Content / Docs | 1.3.0, 37 |
| [41](41-world-name-bera.md) | Run the live world as Bera | Server / Client / Ops | - |
| [42](42-live-monitoring-alerts.md) | Live monitoring and alerts | Ops | 35 |
| [43](43-gm-security-audit.md) | GM account safety and command audit (built, branch `gm-safety`) | Security / Ops | - |
| [44](44-release-pipeline.md) | One-command release pipeline | Ops | - |
| [45](45-backups-and-rotation.md) | Back up bot learning data, rotate old backups | Ops | 23 |
| [46](46-discord-bridge.md) | Discord bridge and server status | Community | 42 |
| [47](47-bot-chat-content-review.md) | Review bot chat and megaphone content | Real bots / Chat | - |
| [48](48-live-capacity-soak.md) | Capacity soak with real players and bots | Performance | - |
