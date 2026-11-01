---
name: qa-triage
description: Turn the owner's QA notes, auto-QA results and player reports into bug cards with a named closer and proof, then close each card on its own proof. Use when the owner says they did QA, asks what auto-QA found, a lane or player reports a bug, at each check-in, and at a freeze or go/no-go.
---

## Find what broke

1. Read the owner's marks and notes: the release book's `qa` collection (Test tab), `docs/release/<version>-qa.md`, or
   chat. Guide: `docs/qa/QA.md`.
2. Read auto-QA: `tools/ops/test-status`, then `Server/data/autoqa/latest.json` on the test server (each check's
   `status`, `commit`, `finished`, `logs`; the run's `release.line`).
3. For each Issue/Fail, open that check's log in `Server/logs/qa/<id>-<UTC>.log` and the journal around it before
   deciding anything. Sort it into one of four:
   - a staging problem (the `!qa` setup)
   - a real bug
   - working as designed but hard to see
   - needs the owner's decision

   Check that a fix commit is in the tested build: `git merge-base --is-ancestor <fix> <TEST commit>`.
4. Player reports (`/var/lib/slums-feedback/reports.jsonl` on 164): never print IP hashes, emails or user agents.

## Triage before any fix (ticket 171)

Who decides: triage runs day-to-day triage on the Coordinator's behalf, and admission disputes go to the Coordinator.
The Coordinator and the submitter judge admission. For a failed automated check, the submitter is the lane that owns
the check. The owner still decides gameplay and economy changes, and on their own reports.

The flow is: `check fails -> evidence captured -> triage -> ticket accepted -> fix assigned -> focused recheck ->
required client pass -> closure`. Gathering evidence can start before triage. Product changes, test or staging
repairs and balance changes wait for an accepted card. Finding a bug doesn't authorize fixing it.

There are two exceptions:
- A live S1 mitigation that only changes config or a flag can go on the owner's go before triage. It is recorded as a
  card afterwards.
- For S4 and other small fixes, an existing card counts as the accepted ticket.

Every triage records six things:
1. **Classification:** a product defect, a test or staging defect, an environment problem, or inconclusive evidence.
   Inconclusive evidence needs an investigation card before any corrective work. Don't guess at a gameplay patch.
2. **The card:** a new card or the canonical existing one, linked to the affected checks and reports. Never file a
   duplicate. One canonical acceptance result can close linked duplicates.
3. **Expected vs observed,** plus the repro, preconditions and evidence.
4. **Severity and disposition:** admitted, deferred with a target and reason, duplicate, or needs investigation.
5. **The decision,** who owns the fix (CLOSER) and who verifies it.
6. **Bounded acceptance:** the focused rechecks and any client evidence required.

New, unrelated findings that turn up during a fix go back to triage.

## File a card (book `bugs`, query the highest `num` first)

Every card gets, at filing:
- sev S1-S4, lane, repro (what, where, which build), and the admission line (below).
- A first `note` line: `CLOSER: <owner|Server Dev|Client lane|Website lane|night step>. PROOF: <what closes it>`.

Status meanings. The STATUS export (`/root/work/STATUS-1.6.x.md`) and outside agents read these:
- `triaged` means UNCLAIMED, free to take. The moment anyone owns it, set `fixing` and name the branch in `fix`.
- `fixing` means owned, with a branch or a named step.
- `fixed` means closed on its proof. `wontfix` means not reproduced, superseded or by design, with the reason. `later` means
  the next version, with an owner and a target.

## Admission after a freeze (ticket 168)

A NEW find enters the current version only if it is:
- security
- data loss
- a crash
- a broken deploy path
- a regression from the candidate
- a broken promise committed for this version

Everything else is `later` with an owner and a target. Cards already on the board keep their place. Link duplicates to one
canonical acceptance result.

## Close a card on its own proof (the full run is only the release gate)

| Card | Closes on |
| --- | --- |
| S4, and tool/QA-process cards | a fail-before unit test plus a reviewed merge |
| S3 | a unit test plus a targeted run (`tools/ops/autoqa-proof` with only the covering check ids), or a hands-on check by the owner or a lane |
| S1/S2 | a targeted run plus the release run (candidate + go/no-go) |

A proof always names:
- the TEST commit;
- the log or `latest.json` entry with its UTC time, or the frame or screenshot path;
- for the owner, their Test-tab mark.

Don't wait for a full ~2.5 h run to close a single card.

Client-visible checks the owner can do go to the owner's Your tasks tab (book collection `mine`, via the Coordinator), with step-by-step
commands. Scripted client frames run on tarkov2 (the default QA machine), not on the owner's desktop.

## Each check-in (15:00 and 03:00 UTC)

- Review every card that has been `fixing` for more than 48 h: still owned? Is the branch moving? Is the closer still right?
- Re-check that no owned card is still `triaged`.
- Send the Coordinator the three lines: Done / Next (ETA UTC) / Blocked.

## Fix flow (lanes)

Group fixes by area and give each group its own worktree and branch from the newest release head (AGENTS.md section 3). New work goes
to omp helpers. Every fix gets a fail-before test. Merge, run the suite, deploy with deploy-test, then close each card
on its proof as above. Update `docs/release/<version>-qa.md` and the patch notes, and tell the owner what to retest, by
check id.
