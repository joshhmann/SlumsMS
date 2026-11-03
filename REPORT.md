# 164-all-merge - hand-back

Branch `164-all-merge` in `/root/work/164-all`, built from `release-1.6.4` @ `6d18f961e` and merging `164-bossrows`
(`b258879dd`, which already carries the release tip), `164-opq` (`90e40c1fd`, then the catch-up `d92443ce2`),
`164-run-chatter` (`05b7db0c4`), `164-altinvite` (`3b546e834`), `164-ex2` (`0dd9dfc83`) and `164-pqpolish` (`d30a22702`).
`164-bossfight`, `164-ex2qa` and `164-bossproof` were already ancestors, so they were declared redundant rather than
merged. Head `1a574b84b`, 116 commits over the release tip. Nothing pushed, nothing deployed.

The one tree now carries all 46 mechanics rows (29 boss + 17 party quest), their solvers, the one PQ lobby, the
run-chatter line and the alt-invite fix. Two lanes' spellings of the same stage facts were folded into one
(`hitReactors`, `spawnWaitSeconds`), and the dead `killTarget` field was not resurrected. Full detail, every conflict,
the row inventory, the switch table and what is still unproven: `REPORT-merged.md`. Per lane: `REPORT-bossfight.md`,
`REPORT-bossrows.md`, `REPORT-opq.md`, `REPORT-ex2.md`, `REPORT-altinvite.md`, `REPORT-pqpolish.md`.

Full suite, every switch unset: `Tests run: 8763, Failures: 0, Errors: 0, Skipped: 138` - `BUILD SUCCESS`, `EXIT=0`,
1843 s. The boss branch alone ran 8575, so nothing was dropped. Row data, the row simulation tests and the WZ
row-integrity test are green on this head: 46 rows, 78 maps, 127 mob ids, 10 reactor ids, 1 door item.

No test failed before a fix on this branch: the only breakage was a compile error from the wider `Stage` record.

Economy: none - no mesos, drop, exp or shop rate changed; the rows only let bots play content that already existed.

`tools/ops/helper-checkout`: every check passes except the `Co-Authored-By` trailer on the merged lanes' own commits.
`release-1.6.4`'s tip carries a trailer, so the convention exists and this branch's own commits carry it; the merged
lanes' commits stay as they are, because rewriting them would invalidate every hash their reports cite.
