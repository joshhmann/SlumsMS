# 168: Finish 1.6.2 with fixed scope and explicit acceptance gates

Owner direction: 2026-10-31 (UTC and PT). The owner agrees with the release-closure direction and wants to finish
1.6.2, which has grown into a major release rather than an incremental one. This ticket records that direction;
it does not authorize a deployment, remove existing release gates, or declare any bug fixed.

Target: close 1.6.2. Server Dev leads technical closure; the Coordinator maintains the release book and scope;
each lane supplies its evidence; triage reconciles bug closure. Current status remains in the release book
(https://claude.ai/artifact/MyhfTPAtE1oAvDmpz6FTcG, the source of truth) and in `/root/work/STATUS-1.6.2.md`, the
copy for non-Claude agents generated from the book, never edited by hand; not this ticket.

## Problem

Implementation has outpaced acceptance. The October 31 13:00 UTC status export lists 77 unresolved bugs and
56 unfinished work items, including 31 at `check`. These lists overlap and must not be added together. Eighteen
unresolved bug titles mention KPQ. Many fixes are already merged but await integrated or real-client proof.

QA also generates repair work: stale fixtures, occupied channels, ambient bot activity and missing visual
evidence. Scope additions then expand the candidate while existing proof is still incomplete. The objective is
to drain the accepted release work, not to expand the catalog or build a new QA platform before shipping.

## 1. Establish the closure list before starting more work

The Coordinator and lane owners reconcile the current book, git and TEST evidence into one bounded 1.6.2 list.
Use the latest evidence; the snapshot above is context, not a current claim about deployed state.

For every remaining item record:

- Existing bug/work ids, linked to one acceptance obligation when they overlap (for example b005/s10).
- One accountable lane owner and the next action.
- Whether it blocks 1.6.2, with the specific player, security or deployment consequence.
- State: implementing, review, merged, deployed to TEST, awaiting proof, accepted; a blocked reason if applicable.
- A bounded acceptance scenario, automated check ids, any required client/browser check and evidence paths.
- Relevant commit, artifact/build, configuration and runtime data identity.
- Disposition: required for 1.6.2, explicitly deferred with a target, or removed/disabled with the owner's decision.

Do not silently drop previously committed behavior. Reconcile every existing promise, including work outside the
four groups below. A duplicate card links to its canonical acceptance result rather than requiring another run.
Use the existing release book; this does not require a new tracker or state-machine implementation.

## 2. Freeze scope and control exceptions

Pause additional feature starts while the closure list is established and drained. Existing agents hand back
their current work and evidence; no blanket cancellation, destructive branch changes or interruption of running QA.

After the freeze, new discoveries are recorded but enter 1.6.2 only when they are:

1. Security, data loss, crashes or a broken deployment path.
2. A regression introduced by the candidate.
3. A failure of an explicitly committed 1.6.2 acceptance requirement.

Everything else goes to a later release with an owner and target. Severity alone does not determine admission.
The earlier blanket instruction that every board bug goes into 1.6.2 is replaced by this admission rule.

The owner can approve an exception. The Coordinator records why it must ship now, affected systems, required
rechecks, and the schedule consequence or work displaced. A newly found adjacent improvement is not automatic scope.

Two owner exceptions are in force as of 2026-10-31, recorded in the release book's Decisions (dec1 v73): s-xtraders
(trader bots at 25-50%, CHAOS/WS from the bots' own drops including event bosses, NX forex-style market, offline
simulator) and m02 (reply planner). Each ships OFF if not proven by go/no-go. No more tickers until 1.6.3.

No broad new audits, economy redesign, expanded chat-quality goals, infrastructure moves or QA-platform completion
as implicit prerequisites. Existing agreed requirements still need acceptance or an explicit disposition.

## 3. Close integrated behavior in bounded groups

These are coordination groups, not new feature tickets. Start by mapping the existing ids; do not implement all
ideas implied by a group name.

| Group | Required closure focus | Examples to reconcile, not a complete blocker list |
| --- | --- | --- |
| KPQ and alts | A player can complete the committed journey; navigation, passes, stage progression, death/abort and cleanup work together | b082, b153, b204, b225, b234, b239, b241, b243, b244 |
| Exchange | Security guards, accounting, required account/funding setup and game/site activation agree | b005/s10, b174, b206, s08, s09, s32 |
| Client compatibility and visuals | Compatible build gate and artifacts; exact remaining player-visible checks recorded | b140, b166, b205, b233, c04 |
| Release readiness | Config/switches, migrations/runtime data, deployment parity, rehearsal, notes and recovery | b052, b164, b176, b185, o01, s29, s30 |

For each group, name an acceptance owner and list the exact unresolved checks. Avoid broad tasks such as
"improve KPQ" or "watch questing work" without a defined starting state, action and expected outcome.

Client compatibility group detail: the final 1.6.2 client is built at build 10721 or higher via
`-DSLUMS_BUILD_NUMBER_OVERRIDE` (VERSION 1.6.2 alone computes 10720), and `MIN_CLIENT_BUILD` is raised to 10721 in
[1.6.2.conf](../release/1.6.2.conf): rc2-rc5 (10720) crash in Rampart Residences (410000350). rc6 is rc5's code plus
the b238 fix.

## 4. Use QA to finish the candidate

### During development and integration

- Reproduce first; inspect the failed run before changing gameplay code.
- Keep required fail-before/pass-after tests and existing full-suite hand-back/review requirements.
- Use `tools/ops/autoqa-proof` and affected checks for faster runtime feedback between release runs. Targeted runs
  do not replace full release evidence. Broad/shared changes require the broader checks their dependencies warrant.
- Classify failures as product, staging, environment or inconclusive evidence. Every category blocks acceptance
  until resolved or explicitly dispositioned; route each to the responsible owner instead of guessing at a code fix.
- Staging must reserve required channels/maps, identify its actors, reset persistent fixture state and clean up.
  Repeated fixture failures take priority over additional feature starts in that area.
- Stabilize the smallest client script needed for a required check, including capture and cleanup. Do not make
  completion of all ticket 166 automation a 1.6.2 prerequisite. Recorded manual proof remains valid where current
  policy permits it; synthetic-player checks cannot prove a visual effect.
- Validate ticket 167's serial/parallel equivalence before relying on parallel runs. Until proven, use the known
  reliable mode; do not trade trustworthy verdicts for a projected speed-up.

### Frozen candidate gate

Record the candidate's server commit, client/launcher/site artifacts, configuration and data/migration versions.
Protect the TEST window from unrelated deployments, restarts and competing staged scenarios.

1. Required local suites pass under existing lane rules.
2. Run the full normal-clock release catalog on that candidate using the existing release gate: no fast or
   failed-only substitute, no missing checks or unexpected skips. Preserve the allowed-skip policy; changes to it
   require the owner's decision. Old/carried passes do not become fresh evidence.
3. Complete every required real-client/browser check with build identity, time, steps and evidence paths.
   An allowed server-catalog manual skip does not waive a required visual acceptance check.
4. Prove committed switches and compatible runtime data are present on TEST and represented in release staging.
5. Finish the existing deployment rehearsal and rollback checks. Reconcile notes against accepted behavior and
   explicitly document deferrals/disabled behavior.

If a blocker forces a candidate change, record the new identity and affected evidence, use targeted checks while
repairing it, then satisfy the full release gate on the final candidate. Do not repeatedly start full runs on a
branch that is still receiving unrelated changes.

## 5. Completion and reporting

This ticket is satisfied when:

- Every existing 1.6.2 promise has an acceptance result or explicit owner-approved disposition.
- No release blocker remains unowned or unresolved; duplicate cards link to the same evidence.
- The final candidate satisfies the existing automated, visual and rehearsal gates above.
- The Coordinator can present a concrete go/no-go package: candidate identities, proof, known limitations,
  deferred ids, deployment sequence and rollback path. Live still requires the owner's explicit go.

During closure report accepted items, outstanding blockers by group, next proof actions and items added by
exception. Record when work became ready for review, merged, deployed and accepted where available, so waiting
for review/proof is distinguishable from execution time. Use the existing book; no metrics project is required.

## Handoff to Claude

Adopt this as the 1.6.2 closure direction. First reconcile and publish the fixed closure list, then direct lanes
to finish its acceptance obligations. Defer unrelated discoveries under the admission rule. Do not begin a new
feature wave or a broad QA rewrite. The desired result is a proven, reviewable release candidate and an explicit
owner go/no-go decision.

## References

- `/root/work/START-HERE.md` and `/root/work/STATUS-1.6.2.md` (current lane/status entrypoints).
- [QA workflow](../ops/qa-suite.md), including release verdicts and changed-file proof selection.
- [Client QA automation](166-auto-qa-client.md).
- [Parallel auto-QA](167-parallel-autoqa.md).
- [Release runbook](../release/RUNBOOK.md) and [1.6.2 deploy details](../release/1.6.2-deploy-steps.md).
