---
name: deploy-test
description: Deploy the current SlumsMS checkout to the TEST server (never live) and verify the boot. Use when asked to put a build, branch or fix on the test server.
---

1. Read AGENTS.md sections 1 and 3 if you haven't this session. This deploys to TEST only.
2. Check nobody is mid-test: `tools/ops/test-status`. If auto-QA is running or the owner is playing, ask before
   restarting (a restart stops `!autoqa`).
3. `mvn -q -o test` must be green.
4. `tools/ops/deploy-test --dry-run` and read the list of data files and migrations. New migrations must have a
   `.rollback.sql` and be listed in `docs/release/<version>.conf`.
5. `tools/ops/deploy-test`. It backs up, copies, migrates, restarts and fails on boot errors.
6. If `config.yaml` or `start-test.sh` on the test server need a change, back the file up (`cp -p f f.pre-<what>`)
   and edit by hand; config is strict, so only keys the deployed build knows.
7. Report to the owner: version, commit, boot time in PT and UTC, error count, and what to test.
