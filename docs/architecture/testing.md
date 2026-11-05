# Tests and QA: where they live

Cross-cutting map for the `## Tests and QA` sections of every subsystem doc. Three layers, none of which replaces
playing the game.

## Layer 1 — unit and integration tests (`mvn -q -o test`)

1340 `*Test.java` classes under `src/test/java` on this branch. Full suite takes roughly 10 minutes (AGENTS.md).

| Area | Classes | Notes |
|---|---|---|
| `soloMapling/ArtificialPlayer` | 528 | the bots: `BotLifeSystem/` alone has ~28 (`Life*Test`, `ModeSwitch*Test`), plus one or more per bot subsystem |
| `server/*` | 114 | maps, life, cashshop, reports, FreeMarket, itemPool, worldevents, menus |
| `scripting/*` | 100 | npc, portal, event, reactor script behaviour |
| `net/server` | 75 (+3 in `net/`: `net/packet`, `PacketProcessorBoundsTest`, `InnerPortalRegistrationTest`) | packet handlers, coordinators, tasks |
| `soloMapling/mechanics` | 94 | one or more per content row solver (`Gq*Solver`, `Cwkpq*Solver`, `Kpq*`, …) |
| `soloMapling/Economy` | 70 | exchange, market, volatility, stats |
| `soloMapling/autoqa` | 62 | the QA harness itself (probes are tested) |
| `soloMapling/qa` | 55 | the `!qa` scenarios, exercised headlessly by their own tests |
| others | ~170 | `companions` 21, `site` 17, `server/maps` 32, `client/command` 31, `worldevents` 11, `achievements` 8, … |

Rules that matter when reading a test (AGENTS.md, "Build and test"):

- A test that reads WZ must call `soloMapling.WzTestData.assumePresent("Item.wz")` so GitHub CI (no `wz/`) skips it.
- To assert a class exists without running its static init, use `Class.forName(name, false, loader)`.
- Timing tests need generous margins; CI machines are slow.

Notable pinned behaviours worth knowing about when a subsystem doc says "untested":

- src/test/java/net/InnerPortalRegistrationTest.java — `USE_INNER_PORTAL` must resolve to **no** handler (ticket 164).
- `QaCommandGatingTest` / `QaRegistryTest` — `!qa` is only registered with `-Dslums.qa=true` or
  `-Dslums.attackcapture=true`, which is why live (neither flag) cannot run it (docs/ops/qa-suite.md).

## Layer 2 — auto-QA (`!autoqa`, nightly 10:00 UTC)

- Model: `soloMapling/autoqa/Check.java:14-31` — `Check(id, title, Kind, why, players, nightly, minutes, holds, steps,
  expects)`. `Kind` = `AUTO` (verdict server-side) / `HALF` (logic here, the look needs eyes) / `MANUAL` (never run).
  `Status` = `PASS` / `FAIL` / `SKIP`; SKIP never fails a check.
- `holds`: `WORLD` means the check owns server-wide state (rates, event clock, roster, exports) and runs alone,
  otherwise `map:<id>`, `pq:<name>`, `ch:<n>`. This is the lane mechanism.
- Catalog: `soloMapling/autoqa/AutoQaCatalog.java` — **197 checks** (167 `auto`, 19 `half`, 11 `manual`), all with
  `t-*` page ids (the 1.6.4 conf quotes "190 checks", docs/release/1.6.4.conf:108; the catalog is the count that
  matters). Probes live in `Probes160`, `Probes162`, `Probes164`, `Probes164Social`, `ProbesGear`, `ProbesMaker`,
  `FamilyProbes`, judges in `LlmJudge`, `BotWatchJudge`, `ChatQuality`, `SocialTraceJudge`.
- Pretend players: `SyntheticPlayer`/`SyntheticPlayers` — real players to bots, never counted as connected
  (AGENTS.md). Bots are `Character`s with `BotClient`, so a check that needs a bot uses `AutoQaWorld`/`GameAutoQaWorld`.
- Driver and outputs: `AutoQaRunner`, `AutoQaRunQueue`, `AutoQaScheduler`, `AutoQaService`, `AutoQaReport`,
  `AutoQaRerun`, `AutoQaRelease`; results in `Server/data/autoqa/latest.json`, health sweeps in
  `Server/data/autoqa/health-latest.json` (`HealthSweep`, `SweepMath`), run logs in `Server/logs/qa/<id>-<UTC>.log`.
- Runner entry outside a client: `tools/ops/autoqa-run smoke|all|<id>` (picked up within 30 s).

Other `-D`-gated layers that a doc should not confuse with `!qa`:

| Layer | Flag | Where |
|---|---|---|
| `!qa run <id>` scenario staging, one at a time, 15-min default timeout | `-Dslums.qa=true` (or `-Dslums.attackcapture=true`) | `soloMapling/qa` (62 files), docs/ops/qa-suite.md |
| auto-QA runner | `-Dslums.autoqa=true` | `soloMapling/autoqa` |
| client visual QA | client MCP worker on Silvia (`tools/ops/client-mcp`) | docs/qa/QA.md |

## What is not covered, structurally

- **Live is not testable by these layers.** Live runs neither `-Dslums.qa` nor `-Dslums.autoqa`
  (docs/release/1.6.4.conf:271), so every `t-*` check describes TEST behaviour only.
- **Client and launcher have their own suites** in their own repos (`/root/work/client/src-164-data/tests`,
  `/root/work/launcher-165-server/test/*.test.mjs`), and neither runs in this repo's `mvn test`.
- **The website's Python tools** have their own tests (`tools/site/test_*.py`, `python3 -m unittest discover -s
  tools/release -p 'test_*.py'` for the release tools).
- **No cross-system smoke** ties a bot action to a player-visible effect end to end; the closest thing is an auto-QA
  check that stages both sides (`SyntheticPlayers`) and reads the server's own state.
- **Coverage of the gap between decision and effect** is thin by construction: see
  [bots/decision-core.md](bots/decision-core.md) `Known issues` — no test asserts that a selected activity is ever
  accepted at a realistic rate.
