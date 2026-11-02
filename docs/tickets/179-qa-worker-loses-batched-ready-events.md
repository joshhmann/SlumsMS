# 179: QA client worker loses readiness events between consecutive waits

State: **new reproduced finding, pending formal triage**. Coordinator + submitter assign official bug id,
severity, release disposition, owner and closer before fixing. Suggested impact: S4 QA tooling, false timeouts.
Source inspected: `d4c24d11626912d43bfa4c1a0ee9d33d9195dc3d`.

## Promise and defect

[166](166-auto-qa-client.md) requires reliable readiness-signal waits so timing races do not masquerade as game
failures. [171](171-1.6.3-incremental-qa-and-formal-triage.md) depends on trustworthy scripted client checks.

`tools/qa-runner/qarunner.py`:

- `SilviaWorker.events` (line 363) advances the alias cursor to the end of the entire returned event batch and
  stores the batch in `seen`.
- `wait_signal` (line 443) searches only that new batch and returns its first matching event.
- A later wait does not consume unmatched events already saved in `seen`. If two distinct readiness events
  arrived together, the second can be recorded but unavailable to its own wait.

## Local reproduction (no client, network or game changes)

Run `python3 docs/qa/audits/reproduce_worker_signal_loss.py` from the audit checkout. It imports the actual worker,
supplies one event batch containing `worldselect.ready` and `channels.ready`, then an empty stream. The first wait
succeeds, the second times out, while `seen` still contains both events. The script asserts this discrepancy and
prints a compact result. It succeeds when the defect reproduces; it is an audit reproducer, not a passing-product
regression test. It should be replaced/adapted to the intended assertion during the accepted fix.

Existing QA-runner suite: 73 tests pass. Its batch-reading test waits only for one event and therefore does not
cover sequential waits for both events in a batch. Reproduction evidence is also retained at
`/root/work/agent-runs/server/s-promise-audit/qa-signal-reproduction.json`.

## Acceptance after triage

- Fail-before test uses sequential waits for both events returned in one batch; both resolve once after the fix.
- Preserve per-run/client isolation, ordering, timeout and closed-pipe handling. Explicitly define how waits arm
  relative to an action so retaining an old signal cannot falsely prove a new action succeeded.
- Verify a stale event from an earlier action does not satisfy a later map/login transition.
- Run the worker tests and a real scripted login/transition with batched events recorded. Name the closer and
  evidence required under the current severity-based closure rules.

No implementation or operational change made. Broad runner capabilities remain ticket 166; this isolates one
specific failure mechanism. Reconcile against the current book before adding a duplicate. See [177](177-promised-feature-code-audit.md).
