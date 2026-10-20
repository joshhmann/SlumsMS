# Real bots and navigation v2 — spec

Written 2026-10-19 from the owner's answers. Builds on `persistence-on-v120` (the living roster, bot store, LOD
offscreen simulation, chat memory) after v1.2.0 is live. Not a rewrite.

## Goal

Every bot is a real character that keeps and uses what it owns. Each bot:
- stores its skills, AP/SP and job;
- drinks its potions and uses up its ammo;
- shops for supplies with its own mesos;
- equips, scrolls and trades its gear.

Its gear and wealth come from playing. Bots also move better: they use mobility skills, take better routes and
travel between continents. Players can bring their own alts along as companions.

## Settled

| Question | Decision |
| --- | --- |
| Which bots are real | **Hybrid by LOD.** Every bot owns real stats and items. Off-screen bots spend and earn them through an honest cheap simulation (potions, ammo and loot accounted for); near players they play in full. |
| Economy | **Shared with players, with guardrails.** Every bot meso and item goes through the ledger. **Per-map yield caps**: bots share each map's real spawn yield with players. Plus a daily meso cap per bot. |
| Alts as bots | **Yes, as companions.** A player's alt can join them as a party bot. It is a real `characters` row saved like any player and can be logged into normally later. It is a separate identity kind, outside the roster, the director and the 1e9 id range. |
| Combat | **Real stats, server formula.** Damage comes from the bot's stats, gear, skills and buffs through a server-side formula (after nutnnut's `CombatFormulaProvider`), shown through the existing attack adapter. MapleRoot's player attack handlers and `PacketCreator` stay untouched. |
| Item use | Potions and ammo; buying at NPC shops; gear upgrades (optimizer, scrolling, selling junk); selling and trading (NPCs, FM, merchants, players). |
| Scrolling / gacha | **Real outcomes**, using items the bot owns: success, fail and destroy. |
| Progression | AP/SP by class build, plus job advancements. Quests come later. |
| Class coverage | Stock classes first (Explorers, Cygnus, Aran) up to level 200. MapleRoot custom classes, rebirths and 200+ come later. |
| Companion control | Chat commands (cosmic_agents parser: name, prefix or slot targeting) and the NPC-window menus. Companions take commands **only within your party**. |
| Navigation | Port nutnnut's graph v61→v66 (it has no learning of its own) (teleport/flash-jump and windowed edges, fork-walk fix, portal route buckets). Add per-class mobility skills and cross-map travel (boats, taxis, ferries). Keep the learning from player jumps on top, and add continuous learning without players: a nav lab, a nightly deep bake and a stuck-hotspot report. |
| Order | Navigation first, then the real tier, then companions, then economy features. |

## Sources and credit (AGPL-3.0)

- nutnnut/Cosmic (GreenCatMS): nav graph v66, the grind advisor and the scroll valuer.
- Kiwiism/cosmic_agents: the command parser, the gear optimizer and shop resupply.

Port capabilities one at a time behind our own gateways (BotStore, BotClient, BotTickService, the governor). Never
adopt a whole runtime. Record every port in `docs/PROVENANCE.md`.

## Tickets (draft)

### Navigation v2
1. **Nav graph v66:** port the v66 deltas into GCMoveSystem. Bump the graph version so caches rebuild. Run regression tests on the existing maps.
2. **Mobility skills:** teleport and flash-jump edges, used when the bot has the skill. Per-class tables for stock classes.
3. **Cross-map world graph:** boats, taxis and ferries. Offscreen travel through the coarse planner.
4. **Learning on v66:** NavEdgeLearning and NavObservedEdges carried over to the new edge types.
4a. **Nav lab (continuous learning, no players needed).** A low-priority background job runs the real physics engine on:
    - failure hotspots;
    - maps no player has visited;
    - jump and drop candidates the graph builder skipped.

    Proven edges join the learned overlay. Edges that keep failing are penalised, exactly like player-learned and
    failure-learned ones. Bounded by the governor; it pauses under load.
4b. **Nightly deep bake.** While the server is quiet, a dense search of every map for jumps, drops and mobility-skill
    moves (Teleport, Flash Jump), denser than the live builder can afford. Results become versioned overlays the
    graph picks up at the next load.
4c. **Stuck-hotspot report.** A daily list of where bots got stuck: map, position, the failed edge and how often.
    It feeds the nav lab, and humans or agents use it to fix the cases physics cannot solve (scripted portals,
    one-way platforms) in the graph builder.

### Real tier
5. **Persisted real stats:** botstore gains skills, AP/SP and job progress (migration). Existing bots are back-filled from level and job with the class build.
6. **AP/SP builds and job advancement:** per stock class.

   *Done for 05/06 (branch `real-stats`).* Note, 2026-10-19: the builds now follow MapleRoot's own skill set, not
   stock v83 - MapleRoot removed the boosters and the axe/blunt/pole-arm forms, renamed several stock ids and added
   custom 4th-job skills and a Cygnus 4th job (catalog: `mapleroot-skills.md`, from `tools/skill-catalog`). Bots get
   the Cygnus 4th job at 120 and the NPC 2084000 package at their 4th job (paid when the bot holds 10m mesos, else
   left unpaid for ticket 15), attack with the custom skills and only cast the buffs they hold. The 5th tier (x13),
   Super Beginner and Synth Master stay unbuilt (rebirth- and item-gated).
7. **Real combat formula:** server-side damage from stats, gear, skills and buffs, behind the attack adapter. Offscreen LOD uses the same numbers averaged.
8. **Potions and ammo:** real consumption, observed and offscreen; running out changes behaviour.
9. **NPC shop resupply:** walk to town and buy with own mesos (needs ticket 3).
10. **Gear optimizer:** equip the best owned gear, chaining stat requirements (cosmic_agents, ported behind a gateway).
11. **Scrolling and gacha:** real outcomes, valuing which scroll to use and when.
12. **Selling and trading:** NPC sell, FM listing, player and bot trades through the existing value-safe trade path.

### Companions
13. **Owned alt-bots:** a new identity kind (real `characters` row, BotClient, `saveCharToDB` allowed, outside the roster and director). Commands `@bot <alt>` / `@botme` equivalents.
14. **Command parser:** port cosmic_agents' parser into BotMenus and FollowerBot; party-only.

### Economy
15. **Per-map yield caps and a daily meso cap:** extend `OffscreenRewardBudget` to observed play. Ledger every bot meso and item; add a GM read-out.

## Risks

- Performance: the full real tier must stay behind the LOD and governor. Both reference forks stall at about 60–200 fully simulated bots.
- Byte-identical guarantees: no changes to `PacketCreator` or `AbstractDealDamageHandler` (client DLL).
- MapleRoot data: the custom classes (5th tier, 700/710) and level 255 are out of scope at first; MapleRoot's custom skills for the regular classes are built (see ticket 06). Builds must refuse unknown classes rather than guess.
