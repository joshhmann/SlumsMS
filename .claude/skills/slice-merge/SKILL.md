---
name: slice-merge
description: Take one SlumsMS server change from a fresh worktree slice to merged, tested and running on TEST, with the version notes updated. Use for any fix or feature headed into a release-* branch ("fix X", "add Y for 1.6.0", "merge that branch", a helper's finished branch), and whenever you are about to merge into release-* or run deploy-test after a code change.
---

# Slice, merge, test, deploy, note

The release branch is shared by every session and helper, so changes land through short-lived slices and one
coordinator merges. Each step below exists because skipping it bit us.

## 1. Slice
```
git -C /root/work/SlumsMS-150 worktree add /root/work/SlumsMS-<ver>-<topic> -b <ver>-<topic> release-<ver>
cp /root/work/SlumsMS-150/config.yaml /root/work/SlumsMS-<ver>-<topic>/     # tests need it; it is untracked
```
Never `git stash` (all worktrees share one stash list; parallel agents swap each other's changes through it).
For a helper, put that line, the hard rules (AGENTS.md §1) and "don't merge" in its prompt.

## 2. Change + test that fails before and passes after
Targeted run while working: `mvn -q -o test -Dtest='Name*' -Dsurefire.failIfNoSpecifiedTests=false`. Release tooling
in Python: `python3 -m pytest -q tools/release/test_release_config.py`. Commit with the session's trailers.

## 3. Merge (coordinator only)
Before merging, check nothing else is running a suite in the release tree:
`ps -eo args | grep -c "[s]urefire.*SlumsMS-<ver>-release"` must be 0. Then
`git merge -q --no-ff <ver>-<topic> -m "Merge branch '<ver>-<topic>' into release-<ver>"`.

## 4. Full suite, to completion
Run `mvn -q -o test` in the background and trust only the exit code from the task's own completion notice.
Don't edit, merge or build in that tree (or that worktree) while it runs: a build mid-run swaps target/classes and
produced 24 bogus errors once and a false "green" another time. A helper's "suite green" needs its exit code from the
real run; if it ran builds in parallel, rerun.

## 5. Deploy to TEST and check it took
`tools/ops/deploy-test` (it lists changed data files and migrations, restarts, fails on boot errors). Then confirm what
the change needs is actually live in the process, not just in files:
`ssh root@192.168.0.164 'tr "\0" " " < /proc/$(systemctl show -p MainPID --value slums-persistence-test)/cmdline'`
for JVM flags; config.yaml is read only at boot, so a config edit needs this restart too.

## 6. QA
Queue the checks that cover it: `tools/ops/autoqa-run t-xx-1,t-yy-2`; results land in
`/opt/mapleroot-dev/persistence-test/Server/data/autoqa/latest.json`. No check covers it? Add one (or note why not).

## 7. Notes (part of done, not a release-night chore)
Update `docs/roadmap/<ver>.md` (status planned/partial/done, its switches, its check id), the release conf if it needs
a switch for live (`docs/release/<ver>.conf` SERVER_SET / UNIT_EDITS), and the player patch-notes draft if players
will notice. A feature built but switched off on TEST or in the release conf is not done (1.6.0 nearly shipped its
headline brain, fishing and companions switched off).

Report: commits, suite count + exit, TEST commit, QA ids and results, notes updated, what's left for the owner.
