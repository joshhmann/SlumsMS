# E. 1.7.0 Release Readiness Matrix

Status: final for sign-off, Nov 5 2026.

## Baseline

1.7.0 is measured against **the 1.6.4 commit actually running on live after release night**, tagged `v1.6.4`. It
must also be **verified**:
- live boot clean;
- release auto-QA green on that commit;
- no S1 open within 48 h of release.

Until then, no 1.7.0 implementation merges anywhere. Planning documents only.

## Gates

| Gate | Blocks release when | Measurable proof | Exception |
|---|---|---|---|
| **G0 Observability** | QA can lose events, or a failure can't be tied to commit, environment and capability | P1-Q1 regression green; every result carries run id, commit, env, capability context, class; zero PASS without evidence | none |
| **G1 Engine safety** | any open P0 | each P0 `fixed` with a fault-injection test that failed before the fix; GP4, GP6, GP8 green | owner sign-off in the book with a written mitigation, per item; never for item/mesos loss |
| **G2 Bot autonomy** | unbounded loops or starvation | TEST 24 h soak at full population: started-root terminal-within-deadline at least 99.9%; zero RUNNING past deadline; zero illegal transitions or duplicate terminals; repeated-failure rate reported; admission, rejection, cancellation and timeout rates reported (D section 6). A rise in cancellation or rejection vs the slice baseline needs a written explanation | an activity may be switched off with a visible exclusion (CONFIG_DISABLED, listed in notes), never hidden |
| **G2 coverage (no legacy loophole)** | the report doesn't show what the 99.9% covers | the readiness report shows (a) the share of root attempts and of bot-hours on **migrated, verified** adapters; (b) per legacy family: attempt count, failure rate, and that rate vs its 1.6.4 baseline. A legacy family whose failure rate is worse than baseline blocks the release | per legacy family, owner sign-off in the book, listed in the notes |
| **G3 Content** | a golden path fails, or a fatal missing reference sits on a golden path | GP1-GP12 PASS on the RC commit; scanner shows zero fatal references on golden-path consumers | content not on a golden path may be excluded with routing (portal blocked in `map-routing.tsv`) and listed |

**Cooperative evidence rule (amendment 11, owner 6 Nov), applies to G3 and to every boss or party-quest golden path (GP5, GP9, GP11):** real-client evidence of entry, objective participation by every participant type in the run (human, owned alt, regular), a confirmed completion flag, legitimate rewards, instance cleanup, and persistence after relog. Solver tests on fake stage views, unit tests or bots merely entering a map do not count.
| **G4 Cross-system** | a launchable incompatible combination exists, or new-player flow breaks | launcher refuses mismatched client, unsigned/old manifest (P1-L1, P1-L2); GP1 on the VM | none for the new-player flow |
| **G5 Recovery** | unrehearsed rollback, unproven backup, failed restore | `REHEARSAL.md` **Result: PASS** bound to staged hashes incl. WZ + extra steps (P1-R1); restore drill receipt from the cycle; live backup timers green (P0-8) | none |

**No false greens:**
- A FLAKY check counts as not passed for a gate until it passes twice in a row on the RC commit.
- A skipped golden path is a FAIL.
- A gate result must name its evidence file.

## P0/P1 obligations

| ID | Gate | Status at sign-off |
|---|---|---|
| P0-1..P0-8 | G1 / G5 | triaged |
| P1-Q1..Q3 | G0 | triaged |
| P1-A1..A6 | G2 | triaged |
| P1-C1, P1-E1, P1-E2 | G3 / G1 | triaged |
| P1-L1, P1-L2 | G4 | triaged; **P1-L2 stays open until implemented and verified: self-update signing is not content authentication** |
| P1-R1 | G5 | triaged |

## Explicit exclusions

The deferred list in C: new PQs and fights, classes, cosmetic expansion, chat-model upgrades, AMPR thresholds.

## Rollout and rollback

Unchanged process: the 1.7.0 conf (`docs/release/1.7.0.conf`), rehearsal on the rehearsal host, then the release
night playbook.
- New switches: `-Dslums.action.authority` (on for 1.7.0) and the capability registry (read-only, always on).
- Rollback: the previous jar plus `rollback.sh` for migrations.
- The `LifeMemory` world data is an extra key in the v2 format, not a new version: a 1.6.4 jar would reset a v3 row
  (`LifeMemory.java:84-93`). A 1.6.4 save drops the key (world knowledge is rebuilt); seed, delta, goals and
  events survive. Proven by the 1.7.0 -> 1.6.4 -> 1.7.0 round-trip test (P1-A6). *(Amended by D10, 6 Nov: the round trip is
  new release -> previous live release -> new release, run at 1.6.8 and again at 1.7.0.)*
- Open operation-journal rows are reconciled at boot before logins (RFC 2.9), on either version.
- Every live step needs the owner's explicit go.

## Go / no-go

The owner decides from a single page: the gate table filled with evidence links, open exceptions, and the
exclusion list.
