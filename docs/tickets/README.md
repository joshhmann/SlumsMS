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
| [21](21-world-events.md) | Scripted world events with bot reactions | Later | 16 |
| [22](22-jar-data-resolver.md) | Jar-only data loading | Later | - |
| [23](23-live-backups.md) | Scheduled live backups | Ops | - |
| [24](24-client-cleanup.md) | Retire the junction test clients | Client | - |
