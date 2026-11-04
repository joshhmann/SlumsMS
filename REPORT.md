# 164-all-merge - hand-back

Branch `164-all-merge` in `/root/work/164-all`, built from `release-1.6.4` @ `6d18f961e` and merging nine lanes:
`164-bossrows` (`b258879dd`, carrying the release tip), `164-opq` (`90e40c1fd` -> `d92443ce2` -> `96484cf9a`),
`164-run-chatter`, `164-altinvite`, `164-ex2`, `164-pqpolish`, `164-realruns`, `164-seams` (`6d01def08`, `6e8011250`) and
`164-zenumist` (`242274259`). **Last merge commit `87205b607`**; the suite ran on it and only `docs/` and `tools/ops`
changed after. Nothing pushed, tagged or staged.

The tree carries 57 mechanics rows (29 boss + 28 party quest, 201 stages), their solvers, the PQ lobby, the run-chatter
line, the real-run quota, the four engine seams, Guild Quest and the Zenumist band. Duplicate spellings folded into one
(`hitReactors`, `spawnWaitSeconds`); `killTarget`, `graceMs` and the scalar `hitReactor` are gone. Detail, the 24 conflict
entries, row inventory, switch table and open items: `REPORT-merged.md`; per lane `REPORT-<lane>.md`.

Full suite, every switch unset: `Tests run: 9071, Failures: 0, Errors: 0, Skipped: 140` - `BUILD SUCCESS`, `EXIT=0`.
Row-integrity tests green against the WZ dump: 57 rows, 163 maps, 129 mobs, 14 reactors, 1 door item; the new
reactor-ground net found one shipped defect (Magatia PQ stage 3 named the room next door) and it is fixed.

No test failed before a fix beyond the merge's own breakages: a compile error from the wider `Stage` record, three test
classes that stopped compiling against it, and `zakumpq.yaml`, which the loader rejected with `stage 50: stages must be
numbered 1, 2, 3... in order (expected 52)` until it was taken from the side that owns it.

Economy: none - no mesos, drop, exp or shop rate changed; the rows only let bots play content that already existed.

`tools/ops/helper-checkout`: every check passes except the `Co-Authored-By` trailer on the 136 merged-lane commits, left
as they are because rewriting them would invalidate the hashes their reports cite. Paperwork is 1.6.4's (owner decision
24): `docs/release/1.6.4-notes.md`, `docs/roadmap/1.6.4.md`, `docs/release/1.6.4.conf`.
