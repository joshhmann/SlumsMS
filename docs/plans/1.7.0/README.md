# SlumsMS 1.7.0: final architecture and execution plan

Signed off for planning on Nov 5 2026. **No 1.7.0 implementation starts until 1.6.4 is released and verified on
live.** The released commit becomes the baseline, tagged `v1.6.4`.

| Doc | What it is |
|---|---|
| [A-architecture-rfc.md](A-architecture-rfc.md) | ActionAuthority mapped onto the existing bot runtime, attempts and trees, resources, proofs, policies, capabilities, memory |
| [B-dependency-map.md](B-dependency-map.md) | W0-W6, critical path, what runs in parallel, interface owners |
| [C-ticket-plan.md](C-ticket-plan.md) | P0 / P1 / P2 / deferred tickets with evidence, acceptance, negative tests, close proof |
| [D-qa-capability-spec.md](D-qa-capability-spec.md) | QA tiers on existing tools, impact selection, golden paths, capability inventory, AMPR and rates |
| [E-release-readiness.md](E-release-readiness.md) | baseline rule, gates G0-G5 with proof, obligations, exclusions, rollback |
| [F-decision-record.md](F-decision-record.md) | 20 decisions, rejected alternatives, risks, blocking questions (none) |

Strategy and corrections: [../1.7.0-stabilization.md](../1.7.0-stabilization.md).

## New confirmed facts from this pass (code, `e52e19c6e`)

- The action contract mostly exists:
  - `LifeActivityQueue`: one waiting + one active per bot, idempotent finish, close cancels;
  - `LifeAcceptance`: admission statuses;
  - `LifeExecutor`: adapter;
  - `LifeOutcomes`: terminal bus.
- `TrainingBot.enterPhase(GRIND)` marks the active activity COMPLETED without proof (`:575-579`), so HELP_FRIEND
  "succeeds" on arrival.
- `LifeLearning` lowers an activity's like on any failure, logout included (`:276-285`, `LifeRuntime.java:706`).
- Active activities have no deadline, only waiting requests do (30 s).
- Town EXP is wider than HELP_FRIEND: the mob level -1 -> 1 coercion sits at 11 `setTrainTarget` call sites in
  TrainingBot, so the fix goes in `setTrainTarget` and the abstract-EXP gate (`:2141`).
- Owned alts run their own 200 ms tick, outside the life queue.
- `LifeMemory` resets on an unknown save version (`:84-93`), so the format must not be bumped.
- Per-check QA timing already exists (`AutoQaReport.java:139`).
- "49 blocked maps" are deliberate rows in `map-routing.tsv`, not breakage.

## Determination

**ARCHITECTURE READY: 1.7.0 implementation can begin once 1.6.4 is released and verified.**

### First three implementation tickets

| # | Ticket | Depends on | Exact proof to close |
|---|---|---|---|
| 1 | **P1-A1 Outcome reasons + honest learning** | `v1.6.4` baseline | (a) L1: GRIND entry no longer fires a terminal; (b) L1 table test: every `Reason` routes to the right memory kind, and DIED/LOGOUT/SHUTDOWN/PREEMPTED change no like; (c) L1: `close()` ends INTERRUPTED(LOGOUT), not CANCELLED; (d) each test fails on the baseline and passes after; (e) TEST: `bot-life.json` shows outcomes by reason for 1 h with zero likes moved by interruptions |
| 2 | **P1-A2 Ledger + HELP_FRIEND slice** | P1-A1 | (a) L1 tree test for HELP_FRIEND (one decision -> root -> TRAVEL, ASSIST; all terminal; one `LifeOutcomes`); (b) architecture test: no `finish` outside `action/`; (c) TEST auto-QA `bl-hf-ok` PASS with the attempt ids in evidence; (d) `bl-hf-town` PASS: 0 abstract EXP over 3 unobserved accrual ticks, REJECTED(TARGET_MAP_NO_MOBS), HELPER like unchanged, world-knowledge row with dependency keys; (e) 24 h TEST soak: zero RUNNING-past-deadline and zero duplicate terminals for HELP_FRIEND |
| 3 | **P1-Q1 Client QA runner keeps batched events** | `v1.6.4` baseline (parallel to 1-2) | (a) `test_qarunner.py` case with two signals in one batch: both waits succeed, and an unrelated event survives for a later wait (fails on baseline, passes after); (b) one Windows-VM client run on the release client build that previously hit b286 completes with both signals recorded |

The P0 safety batch (C, P0-1..P0-8) runs alongside from day one. It isn't on the critical path, but G1 blocks the
release until every P0 item is closed.
