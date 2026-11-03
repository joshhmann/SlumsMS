# 164-all-merge - hand-back

Branch `164-all-merge` in `/root/work/164-all`, built from `release-1.6.4` @ `6d18f961e` and merging
`164-bossrows` (`b258879dd`, which already carries the release tip), `164-opq` (`90e40c1fd`), `164-run-chatter`
(`05b7db0c4`), `164-altinvite` (`3b546e834`), `164-ex2` (`0dd9dfc83`) and `164-pqpolish` (`d30a22702`) - in that
order. `164-bossfight`, `164-ex2qa` and `164-bossproof` were already ancestors of the first two, so they were declared
redundant rather than merged. Nothing pushed, nothing deployed.

The one tree now carries all 46 mechanics rows (12 base + 27 boss + 7 party quest), their solvers, the one PQ lobby,
the run-chatter line and the alt-invite fix. Two lanes' spellings of the same stage fact were folded into one
(`hitReactors`), and the dead `killTarget` field was not resurrected. Full detail, every conflict, the row inventory,
the switch table and what is still unproven: `REPORT-merged.md`. Per lane: `REPORT-bossfight.md`,
`REPORT-bossrows.md`, `REPORT-opq.md`, `REPORT-ex2.md`, `REPORT-altinvite.md`, `REPORT-pqpolish.md`.

Full suite: `mvn -o test`, all switches unset - see `REPORT-merged.md` "Suite totals" for the exact line; the boss
branch alone ran 8575, so anything below that means something did not merge. Row data, the row simulation tests and
the WZ row-integrity test (46 rows, 78 maps, 127 mobs, 7 reactors, 1 door item) are green.

No test failed before a fix on this branch: the merge's only breakage was a compile error (the `Stage` record's wider
constructor), fixed in the merge commits themselves. The known order-dependent flake
(`BotConversationFlowTest.aRefusedPartyAskIsPlannedFromTheGatesVerdict`) did not appear.

Economy: none - no mesos, drop, exp or shop rate changed; the rows only let bots play content that already existed.

`tools/ops/helper-checkout`: everything passes except the `Co-Authored-By` trailer on the merged lanes' own commits
(92 of them). `release-1.6.4`'s tip does carry a trailer, so the convention exists and this branch's own commits carry
it; the merged lanes' commits cannot be rewritten without invalidating every hash their reports cite.
