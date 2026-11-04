# 164-all-merge - hand-back

Branch `164-all-merge` in `/root/work/164-all`, built from `release-1.6.4` @ `6d18f961e` and merging thirteen lanes in
eighteen merges: `164-bossrows`, `164-opq`, `164-run-chatter`, `164-altinvite`, `164-ex2`, `164-pqpolish`,
`164-realruns`, `164-seams`, `164-zenumist`, `164-expedpolicy`, `164-amoria`, `164-botmortal` (+ its spawner-seam
follow-up) and `164-tierpay`. **Last merge commit `73fdf507d`** (the code head; `git diff 73fdf507d HEAD -- src/ scripts/`
is empty), 201 commits over the release tip. Nothing pushed, tagged or staged.

The tree carries 57 mechanics rows (29 boss + 28 party quest, 201 stages), their solvers, the PQ lobby, the run-chatter
line, the real-run quota, the four engine seams, Guild Quest and the Zenumist band, the two owner-answered expedition
switches (bot captain, owned alt - both on), mortality with a switch back to immortality, and the HARD tier's reward half
(off). Duplicate spellings folded into one (`hitReactors`, `spawnWaitSeconds`); `killTarget`, `graceMs` and the scalar
`hitReactor` are gone. Detail, every conflict, the row inventory, the switch table and what is unproven:
`REPORT-merged.md`; per lane `REPORT-<lane>.md`.

Full suite, every switch unset: `Tests run: 9128, Failures: 0, Errors: 0, Skipped: 140` - `BUILD SUCCESS`, `EXIT=0`,
33:05 (`/tmp/final9.log`). Row-integrity tests green against the WZ dump: 57 rows, 163 maps, 129 mobs, 14 reactors, one
door item; the reactor-ground net found one shipped defect (Magatia PQ stage 3 named the room next door) and it is fixed.

No test failed before a fix beyond the merge's own breakages and three flakes, each diagnosed: an unseeded draw asserted
as a share (1 run in ~8,500, now measured and asserted as a mechanism), an unseeded spawn draw on a foothold seam (now
injectable, seeded and pinned), and an auto-QA lane test that once reported a result with no step behind it (a
test-harness race, reported not patched). Economy: none - no mesos, drop, exp or shop rate changed.
