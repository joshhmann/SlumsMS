# 177: Reconcile promised features against implementation and acceptance evidence

Owner request, 2026-11-01: take the wheel checking the codebase for promised/ticketed features and bugs that were
not finished or never implemented. This authorizes an audit, not automatic fixes. Server/QA first pass completed
against `release-1.6.3` **d4c24d11626912d43bfa4c1a0ee9d33d9195dc3d**.

Report: [promise audit and coverage ledger](../qa/audits/2026-11-01-promises.md).

## First-pass result

- Two newly identified defect candidates have their own tickets, pending coordinator + submitter triage:
  [178: sibling-only upgrade handover](178-sibling-only-upgrade-handover.md) and
  [179: client readiness events lost between waits](179-qa-worker-loses-batched-ready-events.md).
- Partial implementation and proof gaps are linked to existing tickets rather than filed as duplicate defects:
  99/162 companion sharing, 158 questing, 159 errands/books, 156 silent NPCs, 157 map probes and 166/171 client QA.
- Code for core alt follow/combat/loot, Maker, party support, map routing and Temple progression exists. A stale
  ticket status or a missing audit receipt does not establish that these were never implemented.
- No runtime code changed. No tickets marked fixed or admitted to a release by this audit.

## Triage handoff

Coordinator and submitter review 178/179 and assign official bug ids, severity, closer and release disposition.
Reconcile the report's existing-ticket gaps with newer book/TEST evidence before deciding further work. Do not
require all known limitations to be implemented just to close this audit, and do not broaden 1.6.3 automatically.

## Coverage and continuation

This first pass follows server/QA call paths for tickets 13, 60, 99, 112, 156-159, 161-162, 166-167, 171 and m02,
plus map routing. It is not a whole-repository or all-version certification. Website/launcher/native client
implementation, historical backlog beyond these paths, runtime switch state and full client acceptance remain
outside this pass. Follow-up legs should be separately bounded and retain the same promise -> caller -> effect ->
check -> evidence ledger, deduplicating findings against the current release book.

Verification: 73 QA-runner tests pass, 20 QA-script tests pass, 11 map-routing Python tests run (one skipped).
The signal-loss reproducer independently demonstrates ticket 179 despite the green runner suite. No Java suite,
remote operations, TEST staging, game client or live checks were run. Exact limits and commands are in the report.
