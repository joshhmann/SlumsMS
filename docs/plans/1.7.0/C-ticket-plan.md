# C. Prioritized Engineering Ticket Plan

Status: final for sign-off, Nov 5 2026. Tickets go on the 1.7.0 release book with the existing statuses
(`triaged / ontest / check / fixed`) and the evidence fields from the plan (`docs/plans/1.7.0-stabilization.md`, 07).

**Every ticket's close proof includes:**
- a test that fails before the fix and passes after;
- its row in `tools/qa/impact.tsv`;
- the TEST commit and the check result.

Evidence is file:line on `release-1.6.4` @ `e52e19c6e`; re-check on the 1.6.4 released commit at kickoff.
"HOLES" = `docs/architecture/HOLES.md`.

Grouping rule: one ticket per root cause, listing its symptoms. No ticket per symptom.

---

## P0: player safety, item/currency integrity, persistence, deploy/recovery

| ID | W | Title | Evidence | Acceptance | Negative test | Close proof |
|---|---|---|---|---|---|---|
| P0-1 | W3 | Buyback is all-or-nothing | `TransactionService.java:108-123`: flag set before grants; catch can't revert (HOLES #4) | items granted and mesos taken, then flag set, in one DB transaction; or nothing changes | inject a failure after the 1st item: no flag, no items, no mesos change | L1 fault-injection test + L2 buyback check |
| P0-2 | W3 | Merchant close and shop buy are atomic and guarded | HOLES server/handlers-trade-storage-merchants: close is two non-atomic writes and swallows SQL errors; `HiredMerchant.buy` / `PlayerShop.buy` index unguarded | close commits items + mesos to Fredrick in one transaction, errors surface; buy rejects an out-of-range index | SQL failure mid-close -> nothing lost, retry possible; buy with a bad index -> refused, no change | L1 tests + GP8 |
| P0-3 | W3 | `deploy-test` proves readiness before marking | `tools/ops/deploy-test:115-123`: marker written before start; wait loop can't fail; `grep -q` + pipefail | marker written only after "is now online"/"Auto-QA on"; timeout exits non-zero and names the rollback dir | stalled-start fixture: deploy fails, marker unchanged | script test in `tools/ops` + one real TEST deploy |
| P0-4 | W3 | Failed boot exits non-zero | `Server.java:1005` `System.exit(0)` with a "world.ini" message (HOLES #6) | boot failure logs the real exception and exits 1; systemd shows failed | forced boot exception in a test harness -> exit 1 | L1 test on the boot-abort path |
| P0-5 | W3 | Saves report failure honestly | `Character.saveCharToDB` returns void (`:10313-10328`); `SaveAllCommand.java:39-48` says "saved successfully"; cooldowns/pets save outside the character transaction (HOLES server/persistence) | save returns a result; `!saveall` reports "N saved, M failed (names)"; pets/cooldowns join the character transaction | DB failure injected for one character -> reported failed, others saved | L1 fault-injection + GM command test |
| P0-6 | W3 | Incubator doesn't eat items | `UseCashItemHandler.java:771` compares an index to an item id (HOLES #3) | incubator finds the item, consumes both, grants the reward | wrong/absent target -> nothing consumed | L1 test + L2 check |
| P0-7 | W3/W4 | Banned accounts lose site sessions | HOLES #11: ban writes `accounts.banned`; nothing writes `revoked.txt` | a ban (and password reset) revokes existing site sessions within 1 min | banned session cookie -> 401 on the next request | site test on the `site-*` line + TEST check |
| P0-8 | W3 | Backups and restore are proven, not assumed | HOLES ops/backups-and-restore: off-box copy off by default, rotation dry-run by default, timers unmonitored. [RUNTIME: live may differ; nightly 164 -> .122 is configured per ops notes] | live backup + rotation + monthly drill confirmed read-only; timers monitored in `test-status`/monitor | a missed backup raises a monitor alert | restore drill receipt from TEST (G5) |

## P1: bot execution correctness, essential engine capabilities, golden-path breaks, QA reliability

| ID | W | Title | Evidence | Acceptance | Negative test | Close proof |
|---|---|---|---|---|---|---|
| **P1-Q1** | W0 | Client QA runner never loses batched events | `qarunner.py:381-389,476-494` (HOLES #10, b286) | per-alias durable pending queue; waits consume only their event; cursor replay-safe | two signals in one batch: both waits succeed; unrelated event survives | `test_qarunner.py` new case fails before, passes after; one VM run |
| P1-Q2 | W0 | Client QA module ships from the release client line | HOLES #9: QA client built from `client-170-qa-signals`, not the 1.6.4 line | QA signals built from the release client branch; build stamp shows it | runner refuses a client whose stamp lacks the QA module | VM run against the release client build |
| P1-Q3 | W0 | Impact selection + honest skips | D section 3; HOLES server/qa-and-autoqa (infra skip reported as normal skip) | `tools/qa/select` + `impact.tsv` seeded; results carry class, capability, selection record; infra skips are TEST_INFRA | an unknown path runs full L1 + smoke | selection output on 3 real merges |
| **P1-A1** | W1 | Outcome reasons + honest learning | RFC 1 defects 1, 2, 4: GRIND auto-complete (`TrainingBot.java:575-579`); learning ignores the reason (`LifeLearning.java:276-285`); boolean refusals | `Reason` on every terminal; GRIND no longer completes; learning routes by reason (RFC 3.3); logout = INTERRUPTED | logout and unreachable map leave likes unchanged; GRIND entry doesn't fire SUCCEEDED | L1 tests on `LifeRuntime`/`LifeLearning`; TEST bot-life counters by reason |
| **P1-A2** | W1 | Ledger + HELP_FRIEND vertical slice | RFC 2.1-2.8; HOLES #8 town EXP: the -1 -> 1 coercion at 11 `setTrainTarget` call sites (`TrainingBot.java:1085, 1499, 1909-1992, 2392, 2928`) + gate `:2141`; fixed at the root (setTrainTarget + gate) | ledger with attempts/claims/deadline/watchdog; single `transition`; HELP_FRIEND adapter with proof; legacy adapter for the rest | `bl-hf-town`: no EXP, REJECTED(TARGET_MAP_NO_MOBS), personality unchanged | `bl-hf-ok` + `bl-hf-town` PASS on TEST, L1 tree test |
| P1-A3 | W1 | Attempt trees + deadlines for travel, grind, restock, sell, PQ, invites | RFC 2.3, 2.5, 2.7; defect 3 (no active deadline) | real adapters and proofs for these families; `bl-tree` | logout mid-combat ends the tree INTERRUPTED with no orphans | `bl-tree` PASS; zero RUNNING-past-deadline in a 24 h TEST soak |
| P1-A4 | W1 | Owned alts on the shared ledger (OWNER_DIRECTED) | RFC 2.6; `OwnedAltCompanion`/`OwnedAltOrders` own tick today | FOLLOW/FIGHT/orders are OWNER attempts; owner orders preempt | an alt never admits an autonomous attempt off its owner's map | adapter tests under both policies; GP5 |
| P1-A5 | W1 | Town stay has a ceiling; party GRIND passes map admission | HOLES bots/training-bot (`phaseDeadlineMs` re-armed while a player talks); bots/party-and-expeditions (party GRIND skips `BotMapCap.admit`) | attempt deadline caps the stay; party GRIND calls `BotMapCap.admit` | a chatting player can't hold a bot past the cap; a full map refuses the party | L1 tests + L2 check |
| P1-A6 | W1 | World knowledge + reassessment | RFC 3.3-3.4 | `LifeMemory` world section with dependency keys; probes limited to 1 per capability per world per 10 min | a fixed map wakes suppressed bots after a deploy; probe failure doubles backoff; a row written by 1.7.0 loads in the 1.6.4 reader without reset | L1 tests + TEST scenario: suppress, change context, observe probe |
| P1-C1 | W2 | Capability registry + scanners | D section 5 | declarations table, `data/capabilities.json`, L0 reference scanner, fanout report | a verdict from another config/env reads UNVERIFIED | scanner report on the baseline commit; registry unit tests |
| P1-E1 | W2/W6 | One bad life entry doesn't kill a map load | HOLES server/world-and-maps: map life NPE aborts the whole map | a bad spawn is skipped and logged with map + id; the map loads | inject a bad life row -> map loads minus that spawn | L1 test + scanner count drops |
| P1-E2 | W3 | Guild expel and party leader handlers don't crash | HOLES #1 (`GuildOperationHandler.java:232`), #2 (`World.java:1055`) | guarded; the player gets a proper refusal | guildless expel packet; offline leader change | L1 handler tests |
| P1-L1 | W4 | One client build number and the manifest carries `minClientBuild` | HOLES launcher/core-manifest (10604 vs 10740); launcher/publishing (publisher never writes `minClientBuild`) | one build-number scheme; publisher writes `minClientBuild`; launcher refuses a mismatched client | old client + new server -> launcher blocks with a clear message | launcher tests + TEST channel publish |
| P1-L2 | W4 | Game-content manifest authenticity + rollback protection (**open until verified**) | launcher `manifest.rs:154-339` (sha256 only); only self-update is signed (`tauri.conf.json:41`) | content manifest signed by the release key, verified before use; version monotonic per channel | unsigned, tampered or older manifest -> refused | launcher tests + RC channel check |
| P1-R1 | W4 | Release rehearses WZ and extra steps | HOLES ops/release-pipeline: WZ and `extra-steps.sh` never rehearsed | rehearsal runs the same WZ + extra steps as release; receipt binds staged hashes | stale or tampered receipt refused (b323 base exists) | `REHEARSAL.md` with hashes; G5 |

## P2: high-impact content defects and inconsistencies (W6 ranks these by fanout)

| ID | Title | Evidence |
|---|---|---|
| P2-1 | Quest turn-in refusals don't spend the day's budget | HOLES bots/quests-and-progression (`recordRefused` increments `doneToday`) |
| P2-2 | Supply shortage check is pure; one copy of supply needs | HOLES bots/supply (`BotSupplies.shortage()` mutates; drifting copy in `LifeRuntime`) |
| P2-3 | Script engine cache hits | HOLES server/scripting (`"scripts/" + path` vs `path`) |
| P2-4 | Opcode dispatch cross-check (L0) | HOLES server/network-and-packets (177 hand registrations) |
| P2-5 | Cash-shop gifts deliver (**owner decision n16 first**) | HOLES #7: `open(true)` never called; refusal happens before any charge, so nothing is lost |
| P2-6 | `!enableauth` removal works | HOLES #5 (Pair key type) |
| P2-7 | Launcher TEST_SITE points at TEST | HOLES launcher/site-and-account |
| P2-8 | Monitor reads `health-latest.json`; live target version current | HOLES ops/monitoring |
| P2-9 | Reputation rebuild keeps FEELING grudges | HOLES bots/affinity-bonds-gossip-memory |
| P2-10 | Carry-over fixes from 1.6.5: trader scroll selling (b287), token drops (b291), Will/Magnus portals (b274), pet auto-potion cause (b280) | `docs/release/1.6.4-playbook.md` section C |
| P2-W6 | Fanout-ranked content repairs | produced by P1-C1's report; each becomes a P2 ticket naming what it unblocks |

## Deferred to 1.8.x (feature freeze)

Holiday PQ; Elnath PQ stages; Von Leon, Cygnus, Hard Lotus and Balrog Easy fights; Chaos Horntail; open boss/PQ
additions; new classes or PQs; cosmetic expansion; chat-model work beyond fixing contradictions; AMPR thresholds.
A deferred item comes back only if a golden path needs it.

## Not a ticket (already fixed or corrected)

- Release gate (b323, fixed in 1.6.4).
- `@botme` dead on live (wrong: live has it on).
- Chaos Scroll cap (b320, fixed in 1.6.4 at 150M).
