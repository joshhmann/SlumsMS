# 164-all-merge - hand-back

Branch `164-all-merge` in `/root/work/164-all`, built from `release-1.6.4` @ `6d18f961e` and merging `164-bossrows`
(`b258879dd`, which already carries the release tip), `164-opq` (`90e40c1fd`, the catch-up `d92443ce2`, then the final
`96484cf9a`), `164-run-chatter` (`05b7db0c4`), `164-altinvite` (`3b546e834`), `164-ex2` (`0dd9dfc83`),
`164-pqpolish` (`d30a22702`), `164-realruns` (`4aaf42e58`) and `164-seams` (`6d01def08` + the follow-up
`6e8011250`). `164-bossfight`, `164-ex2qa` and `164-bossproof` were already ancestors, so they were declared redundant
rather than merged. **Merged head `39276532a`**, 163 commits over the release tip; the suite ran on `4e495c09b`, whose
tree is byte-identical to the head (`git diff 4e495c09b HEAD` is empty). Nothing pushed, nothing deployed.

The one tree now carries all 56 mechanics rows (29 boss + 27 party quest, 195 stages), their solvers, the one PQ lobby,
the run-chatter line, the alt-invite fix, the real-run quota and simulation, and the four engine seams with Guild Quest.
Lanes' duplicate spellings of the same stage facts were folded into one (`hitReactors`, `spawnWaitSeconds`), and the
dead `killTarget` and `graceMs` were not carried. Full detail, every conflict, the row inventory, the switch table and
what is still unproven: `REPORT-merged.md`. Per lane: `REPORT-bossfight.md`, `REPORT-bossrows.md`, `REPORT-opq.md`,
`REPORT-ex2.md`, `REPORT-altinvite.md`, `REPORT-pqpolish.md`.

Full suite, every switch unset: `Tests run: 9068, Failures: 0, Errors: 0, Skipped: 139` - `BUILD SUCCESS`, `EXIT=0`,
2032 s. The largest source lane alone ran 8799, so nothing was dropped. Both row-integrity tests are green against the
WZ dump: 56 rows, 156 map ids, 127 mob ids, 14 reactor ids, 1 door item.

No test failed before a fix on this branch beyond the merge's own breakages: a compile error from the wider `Stage`
record, two test classes that stopped compiling against it, and a row (`zakumpq.yaml`) that the loader itself rejected
with `stage 50: stages must be numbered 1, 2, 3... in order (expected 52)` until it was taken from the side that owns it.

Economy: none - no mesos, drop, exp or shop rate changed; the rows only let bots play content that already existed.

`tools/ops/helper-checkout`: every check passes except the `Co-Authored-By` trailer on the merged lanes' own commits.
`release-1.6.4`'s tip carries a trailer, so the convention exists and this branch's own commits carry it; the merged
lanes' commits stay as they are, because rewriting them would invalidate every hash their reports cite.
