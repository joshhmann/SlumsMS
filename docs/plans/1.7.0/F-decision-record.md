# F. 1.7.0 Architecture Decision Record

Date: 2026-11-05. Status: **accepted and LOCKED by the owner** (material deviations need evidence and owner approval) (owner adopted the systems-first plan; this record closes the open design
questions). Context: `docs/plans/1.7.0-stabilization.md` and Codex's two reviews.

## Decisions

| # | Decision | Why | Rejected alternative |
|---|---|---|---|
| D1 | ActionAuthority = `LifeActivityQueue` grown into a per-actor ledger + `ActivityAdapter` + `LifeOutcomes`, not a new service | the queue, acceptance boundary and outcome bus already exist (A section 1); a new layer would be a second brain | a central ActionAuthority service/thread: global contention at 3,000 bots, duplicate state |
| D2 | One `transition` method; `finish` becomes package-private; an architecture test bans other callers | silent ends are today's main defect (GRIND auto-complete) | convention only: already failed once |
| D3 | Every attempt has its own id, with `parentId` + `rootId`; at most one running child per parent; parents claim only their continuous spine, children claim per step (revised after review) | traceable trees without a scheduler; long quests/PQs don't block unrelated work | parallel siblings; whole-tree upfront claims (blocks BAG/DIALOG for a whole PQ) |
| D4 | Parent succeeds only on its own proof | "all children succeeded" can still be no progress | aggregate child success |
| D5 | Nothing resumes mid-attempt; goals persist in `LifeMemory`, a new root carries `resumesRootId` | resumable executors would need serialized executor state across restarts | checkpointed attempts |
| D6 | Five actor resources (BODY, HANDS, BAG, DIALOG, PARTY); conflict = claim intersection; per-actor atomic admission; cross-actor work uses the handshake protocol (txId, both-side deadlines, idempotent answers, one outstanding handshake per actor, lower-id tie-break, TARGET_GONE on disappearance) | no lock deadlock (per-actor locks) and no logical wait cycles (one outstanding handshake + deadlines) | four coarse domains; cross-actor locks; relying on per-actor locks alone (doesn't prevent logical waits) |
| D7 | Priority SAFETY > OWNER > COMMITMENT > AUTONOMOUS; transaction steps non-preemptible up to 10 s, then COMPENSATING; `compensate` bounded (2 s per tick) with DONE/PENDING/FAILED; claims held while effects are uncertain; operation journal + boot reconcile | owner commands must win; trades must not tear; a shutdown must not leave items half-moved | synchronous unbounded compensation; releasing claims on shutdown regardless |
| D8 | Bots and alts share the ledger, adapters and proofs; they differ by `Policy` (AUTONOMOUS vs OWNER_DIRECTED); alts get no personality drift | house rule "one implementation", and an alt must never leave its owner on a whim | a separate alt executor (duplicates mechanics) |
| D9 | Learning routes by reason: interruptions teach nothing; structural/feasibility reasons go to world knowledge; only SUCCEEDED and bot-owned execution failures go to experience; social memory only from social events | an engine defect must not become a personality trait | outcome-only boolean learning (today) |
| D10 | World knowledge = an extra key in `LifeMemory` v2, **no version bump**; a 1.6.4 save drops it by design; proven by a 1.7.0 -> 1.6.4 -> 1.7.0 round trip | the 1.6.4 reader resets unknown versions (`LifeMemory.java:84-93`) and its writer rebuilds the map (`:734-742`); world knowledge is rebuildable, the rest must survive | v3 format; a new table |
| D11 | Capability verdicts are context-bound (server SHA, environment, content rev, config fingerprint, client build); mismatch reads UNVERIFIED; verification is never availability: availability and actor feasibility are checked at every admission and never downgrade a verdict | a verdict from one config must never vouch for another, and a passing test doesn't mean the NPC is spawned right now | global true/false flags; treating VERIFIED as runtime truth |
| D12 | Registry declarations are checked in; verdicts are a boot-time snapshot in `data/capabilities.json`; no DB table | reproducible from QA results and scanners; no migration | a DB table + admin UI |
| D13 | Probing after repair: at most 1 probe per capability per world per 10 min, only when a bot proposes it anyway; failure doubles backoff (cap 24 h) | wakes bots after fixes without probe storms | time-only expiry; no probing |
| D14 | First slice: HELP_FRIEND, positive and negative scenarios separate; the town-EXP fix is made at its root (`setTrainTarget` + the abstract-EXP gate, 11 call sites), with HELP_FRIEND as its first proof | it carries a confirmed bug (town EXP) and a confirmed false success (GRIND auto-complete) | GRIND first (too wide), TRAVEL first (no learning angle) |
| D15 | QA: extend existing tiers; impact table + `select` script; unknown paths broaden | the 197-check run and per-check timing already exist | a new harness or CI system |
| D16 | No full-certification time target in 1.7.0; optimise from measured per-check timings | soak and restore drills can't be cut to hit a number | the 30-60 min target |
| D17 | Ticket statuses stay `triaged / ontest / check / fixed`, with evidence fields | the team is one owner plus agents | a 9-state lifecycle |
| D18 | Game-content manifest authenticity and rollback protection are **open** (P1-L2) until signed and verified | only the launcher's self-update is signed; content is hash-checked only (`manifest.rs:154-339`) | treating self-update signing as content trust |
| D19 | `-Dslums.action.authority` switch; legacy adapter keeps unmigrated activities on today's behaviour, with learning ignoring legacy failures | migrate one family at a time without a big-bang | migrate all activities at once |
| D20 | No 1.7.0 code merges before 1.6.4 is released and verified; baseline is the `v1.6.4` tag | owner, Nov 5: 1.6.4 is the priority | starting in parallel on release-1.6.4 |
| D22 | Compensation is non-blocking: small in-memory slices per tick, I/O off-tick, 2 s is a cross-tick deadline (owner, at lock) | the shared game loop must never stall for settlement | spending up to 2 s on the tick |
| D21 | G2 must show coverage: share of attempts and bot-hours on migrated adapters, and legacy families' failure rates vs baseline; a worse legacy family blocks unless owner-excepted and listed | closes the loophole between the 99.9% rate and the legacy adapter | excluding legacy silently |

## Remaining risks

| Risk | Mitigation |
|---|---|
| TrainingBot has many implicit exits; the slice may miss some | the architecture test (D2) plus the 24 h soak counter "RUNNING past deadline" |
| Legacy adapter hides failures during migration | legacy outcomes are tagged and counted; G2 uses only migrated families for the 99.9% rate, and lists the rest |
| Reason-routing mistakes skew learning | L1 table-driven test over every Reason x memory kind |
| Scanner noise (thousands of WZ findings) | root-cause grouping by capability; only golden-path consumers block G3 |
| Content manifest signing touches the publish flow on 164 | done on the RC channel first; live publish only on the owner's go |
| Helpers editing core interfaces | single W1 owner for `action/` (B, concurrency rule) |

## Blocking questions

None. Non-blocking owner choices (defaults stand if not answered):
- golden-path list swaps (D section 4);
- the cash-shop gift decision (n16, P2-5).
