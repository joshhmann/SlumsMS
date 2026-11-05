# D. QA and Capability Verification Specification

Status: final for sign-off, Nov 5 2026. Reuse first: every item names the existing tool it extends.

## 1. Measured baseline [CONFIRMED, TEST, Nov 5 2026]

| Run | Checks | Wall time | Source |
|---|---|---|---|
| Full release auto-QA | 197 | 2 h 22 min (10:08-12:31 UTC) | `data/autoqa/latest.json` runs[].started/finished |
| Targeted rerun | 16 | 20 min | same |
| Single check (t-xc-5) | 1 | 2.5 min | same |
| Unit suite `mvn test` | ~12,140 tests | not yet timed per class | release-1.6.4 full suite |

Already recorded, so it doesn't need building: **per-check `seconds`** (`AutoQaReport.java:139`) and per-run
`wallSeconds` / `checkSeconds` (`:288-293`). The 6 h figure is the social soak window and is not QA.

## 2. Tiers (existing tools mapped)

| Tier | Tool that exists | Runs on | 1.7.0 change |
|---|---|---|---|
| L0 static | `mvn -q -o compile`, arch-style tests (e.g. `OwnedAltSwitchLineTest`) | build box | add: opcode dispatch vs enum cross-check (HOLES server/network-and-packets); `finish` not called outside `action/` (RFC 2.2); WZ/script reference scanner (section 5) |
| L1 unit/scenario | JUnit, `FakeQaWorld`, `QaScenarioStagingTest`, ledger tests | build box | ledger/adapter/attempt-tree tests; outcome-to-memory routing tests |
| L2 headless on real JVM | server auto-QA probes (`soloMapling/autoqa`, about 199 check ids), `!qa` suite (`soloMapling/qa`) | TEST | new checks `bl-hf-ok`, `bl-hf-town`, `bl-tree`; per-check class + capability id in results |
| L3 integration | `tools/ops/deploy-test` + full auto-QA + `test-status` health | TEST | deploy readiness fix (P0-3); results tied to commit + capability context |
| L4 certification | Windows client runner (`tools/qa-runner`, Windows test VM), release rehearsal (`tools/release/rehearse-host.sh` -> `REHEARSAL.md`), soak | TEST + VM | runner event fix (P1-Q1); client QA module on the release client line (HOLES #9) |

No new framework is introduced.

## 3. Change-impact selection

A checked-in table, `tools/qa/impact.tsv`, has the columns `path-glob  capability-ids  junit-classes
autoqa-check-ids`.
- A small script, `tools/qa/select <base> <head>`, prints the JUnit classes and the `autoqa-run` list.
- **Unknown paths broaden:** any changed file with no row runs the full L1 plus the smoke set (the release golden
  paths, section 4). Nothing is silently omitted.
- It's seeded by hand for the high-risk packages (BotLifeSystem, TrainingBot, companions, economy/exchange,
  persistence, handlers) from the existing probe groups. It grows when a ticket adds a test: the ticket must add its
  row (ticket rule, C).
- `autoqa-run` already accepts a comma list, so nothing changes in the runner.

**Selection stays honest:** each run records the selection inputs (base, head, rows matched, unknown paths) in the
run's results, so a reviewer sees why a check was skipped.

## 4. Golden paths (release smoke set)

Real progression, not logins. Each one is an auto-QA scenario (L2/L3) unless marked VM (L4). Owner may swap items at
kickoff; this list is the default.

| # | Path | Proof |
|---|---|---|
| GP1 | New account via website -> launcher -> client gate -> first map -> logout/login (VM) | character persisted at the same map/level |
| GP2 | Beginner to first job advance (Henesys line) | job id changed, skill point granted, saved |
| GP3 | A common quest chain: accept -> kill/loot -> credit -> turn in -> restart -> state kept | quest COMPLETED after restart, reward in bag |
| GP4 | Grind with potions: buy -> grind -> drink -> sell drops | mesos and item deltas reconcile |
| GP5 | Kerning PQ with an owned alt in the party | PQ clear event, both credited, alt still with owner |
| GP6 | Trade between two players: items + mesos -> relog both | both inventories reconcile |
| GP7 | Exchange item sell/buy under the daily cap | order filled, mesos and cap counters match |
| GP8 | Hired merchant open -> sale -> close -> Fredrick retrieval | no lost items or mesos |
| GP9 | Bot HELP_FRIEND (positive + town negative, RFC 2.8) | ledger tree + memory assertions |
| GP10 | Bot restock trip: travel -> shop -> return -> grind | attempt tree SUCCEEDED with purchase proof |
| GP11 | A boss entry with party (Zakum lane) | entry admitted, fight state reached, exit clean |
| GP12 | Character death -> respawn -> EXP penalty -> save | penalty applied once, saved |

## 5. Capability inventory

### Domains and seed capabilities

| Domain | Seed capability ids | Extracted from (existing where possible) |
|---|---|---|
| Navigation | `nav.portal.edge`, `nav.route.town_to_map`, `nav.foothold.walkable` | WZ portals; `map-routing.tsv` (`MapRouting.java:47-49`: 63 route rows, **49 deliberately blocked rows**, not breakage); foothold health check in `test-status` |
| Map loading | `map.load`, `map.life.spawn` | `MapFactory.loadLifeRaw` (`:134-149`); boot load over all map ids |
| NPC interaction | `npc.template.present`, `npc.script.present` | WZ NPC list vs `scripts/npc/*` |
| Quest progression | `quest.start`, `quest.objective.credit`, `quest.turnin` | WZ Quest.wz (Check/Act), `scripts/quest/*` |
| Combat | `combat.mob.template`, `combat.mob.level_known` | WZ Mob.wz vs map life (HOLES server/wz-data: 270 mob ids without templates; re-derive); `MapMobIndex` |
| Inventory/economy | `economy.shop.listing`, `economy.item.exists`, `economy.exchange.catalog` | shop tables vs Item.wz; exchange catalog |
| Persistence | `persist.char.save`, `persist.merchant.close`, `persist.transaction` | fault-injection L1 tests (P0 tickets) |
| Social | `social.party.invite`, `social.guild.invite`, `social.trade` | auto-QA social checks (rv/gu/rm/pb ids) |
| Scripting | `script.event.load`, `script.portal.present` | script engine load at boot; `scripting/field/*` reachability (HOLES server/scripting) |
| WZ references | `wz.ref.item`, `wz.ref.map`, `wz.ref.mob` | reference scanner (new, L0: one Java test reading the existing providers) |
| Client/protocol | `client.opcode.handled`, `client.build.min` | opcode enum vs dispatch table; `MIN_CLIENT_BUILD` vs launcher manifest |

### Scanner output

One TSV row per finding: `capability  consumer  state  class  evidence`. Consumers are quests, maps, NPCs and
activities. Fanout per capability is a count of consumers. The scanner is one L0 test class plus a report
writer, not a service.

### Failure classes (in every QA result and scanner row)

`ENGINE`, `CONTENT`, `BOT`, `CLIENT_PROTOCOL`, `TEST_INFRA`, `CONFIG_DEPENDENT`.

**Root-cause grouping:** failures with the same capability id and class are reported as one row with an affected
consumer count. The raw per-scenario failures stay in the run file for reproduction. An **infrastructure skip** is
reported as `TEST_INFRA`, never as a normal skip (HOLES server/qa-and-autoqa).

## 6. Bot observability: rates and AMPR

### Definitions

Windows: rolling **1 h** and **24 h**, per world.

- **Eligible bot:** online at least 50% of the window.
- **Started attempt:** ADMITTED and RUNNING.

| Metric | Numerator | Denominator |
|---|---|---|
| Admission rate | admitted roots | proposed roots |
| Rejection rate (+ reason histogram) | rejected roots | proposed roots |
| Completion rate | SUCCEEDED roots | started roots |
| Terminal-within-deadline | roots terminal by `deadlineAt` | started roots |
| Cancellation rate (by cause: safety / preempt / owner) | INTERRUPTED roots | started roots |
| Timeout rate | TIMED_OUT roots | started roots |
| Repeated-failure rate | bots with the same (activity, reason, target) failing 3+ times in 1 h | eligible bots |
| Time to first meaningful progress | per bot, login -> first AMPR event; report p50/p95 | eligible bots that logged in during the window |
| **AMPR** | eligible bots with at least 1 meaningful-progress event | eligible bots |
| Meaningful events per online hour | count of AMPR events | bot online hours |

### Meaningful progress events

Each needs a postcondition, and duplicates within the window are dropped.

- **Training:** level up; job advance; at least 2% of the level's EXP from valid kills in one attempt; AP or SP
  spent.
- **Quest:** a quest started or completed; an objective credited.
- **Exploration:** first visit to a map in this bot's lifetime; arrival at a *planned* goal destination.
- **Commerce:** net worth (mesos + valued items) up through a sale, purchase or trade; a better gear piece equipped
  (gear score up).
- **Social:** new bond or friend; a party formed with a member staying 10+ min; PQ clear; a gift or invite accepted
  by its target.
- **Long-term:** a `LifeGoals` step softened or completed.

### Excluded from AMPR

- Chat lines, emotes, chairs, idle wander, BREAK/FISH dwell, SHOW_OFF without an audience reaction.
- Re-entering the same map within 30 min.
- Repeated identical decisions.
- Anything produced by a REJECTED, INTERRUPTED or legacy-adapter attempt.

### Thresholds

None until a one-week baseline after the slice ships on TEST. Gate G2 uses the rates, not AMPR, in 1.7.0. AMPR
is observability for 1.8.

### Where they appear

`data/bot-life.json`, the `test-status` "bot life" line, and the dashboard.

## 7. Evidence and false-green rules

- Every result carries: run id, commit, environment, capability context (RFC 3.1), actor ids, attempt ids, check
  id, class, timings, and the selection record.
- Missing evidence is FAIL or TEST_INFRA, never PASS.
- A skipped check needs a reason row.
- A check may not be weakened in the same change that fixes the code it tests: a reviewer reads the diff.
- The client runner records events per alias in a durable pending queue; two signals in one batch is a regression
  test (P1-Q1).
- Flaky handling: a check that passes on rerun is reported as FLAKY with both results, not as PASS. Two FLAKY runs in
  a release cycle open a ticket.
