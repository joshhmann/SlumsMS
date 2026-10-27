---
name: release-readiness
description: Audit a SlumsMS release before it ships - missing live hotfixes, features built but switched off (in code, on TEST, or in the release conf), features with no auto-QA check, and live-only settings the owner must confirm. Use before staging or a release-night dry run, when the owner asks "are we ready", "is everything enabled", "did we test it", and after a batch of helper or external-agent work lands.
---

# Release readiness

Two near-misses in 1.6.0 shaped this: 1.5.3's x5 job fees never reached the release branch, and the headline
features (bot brain, fishing, companions) were built "off by default" and never switched on for live. Both are
invisible in a green test suite, so check them on purpose.

## 1. Hotfixes from live are in
`tools/release/hotfix-parity <live-branch>` (e.g. 156-guard; the branch live runs). It must say ok. A hand port or a
version-bump-only commit goes in `tools/release/hotfix-ported.txt` only after you checked the change is really present.

## 2. Unmerged work
List branches with commits not in the release, by patch content (`git cherry -v HEAD <branch>`). Ported ones show
as `+` too; check the content exists before calling something missing. Include branches by Codex/DeepSeek/Gemini.

## 3. Switch audit (the big one)
Delegate to a read-only helper for anything larger than a patch. For every player-visible or ops-relevant feature in
the release (derive the list from `git log <split>..HEAD`, not memory), record:
`feature | switch(es) + code default (file:line) | TEST state | release/live state | owner wants | auto-QA id + last
result | verdict OK / OFF-ON-LIVE / OFF-ON-TEST / UNTESTED / UNKNOWN`.
- Switch kinds: JVM properties (`Boolean.getBoolean`, `System.getProperty` with slums./solomapling./slumsms.), config
  fields (`ServerConfig.java`), yaml toggles (`EconomyConfig.getBool`), migrations, offline jobs/timers.
- TEST truth is the running process, not the script: read `/proc/<pid>/cmdline` and remember config loads at boot.
- Live truth: `docs/release/<v>.conf` plus earlier confs and deploy-steps; the rest is unknown until the owner checks.

## 4. Live-only unknowns: give the owner one command
Agents can't read live. Hand the owner a `!` command that prints names and values only, e.g.:
```
! ssh root@192.168.0.164 "systemctl cat slumsms | grep -oE -- '-D[A-Za-z0-9_.-]+(=[^ ]*)?' | grep -viE 'key|secret|pass|token' | sort; grep -nE '^\s*(KEY1|KEY2|...):' '/opt/mapleroot/MapleRoot Full Repack/Server/config.yaml'"
```
Then reconcile: a SERVER_SET value that matches live is harmless; a UNIT_EDITS sed that changes nothing aborts the
deploy's pre-check, so drop flags live already has.

## 5. Fix and record
Release conf edits (SERVER_SET for config keys, UNIT_EDITS for -D flags; text values may hold letters, digits and
`:,._/-`), `python3 -m pytest -q tools/release/test_release_config.py`, TEST flags/config to match (restart), QA checks
for UNTESTED items, ops steps in `docs/release/<v>-deploy-steps.md`, unbuilt items moved out of the roadmap. Then the
version notes and patch-notes draft say exactly what ships switched on.

Report to the owner: a short table of what was wrong and fixed, what needs their go or their live check, and what
moved to the next version.
