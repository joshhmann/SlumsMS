---
name: qa-triage
description: Turn the owner's QA notes and auto-QA results into verified causes and fixes. Use when the owner says they did QA, or asks what auto-QA found.
---

1. Read the owner's marks and notes: `docs/release/<version>-qa.md` (PASS/FAIL after each line), what they wrote in
   chat, or (Claude only) the 1.5.0 QA page's db collection `qa`. Guide: `docs/qa/QA.md`.
2. Read auto-QA: `tools/ops/test-status`, then `Server/data/autoqa/latest.json` on the test server.
3. For each Issue/Fail, open that run's log in `Server/logs/qa/<id>-<UTC>.log` and the journal around it before
   deciding anything. Sort each into: staging problem (the `!qa` setup), real bug, working as designed but hard to
   see, or needs the owner's decision.
4. Group fixes by area (e.g. guilds, social, economy, client) and give each group to its own worktree/branch from the
   newest rc (AGENTS.md section 3). Every fix gets a test.
5. Merge into the rc, run the full suite, deploy with the deploy-test skill, update `docs/release/<version>-qa.md` and the patch notes
   (`docs/site/releases/<version>.md`), and tell the owner what to retest, by check id.
