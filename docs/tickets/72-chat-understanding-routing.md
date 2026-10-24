# Ticket 72: Player requests and chat routing

Target: 1.6.0. Status: Ready: reproduce benchmark rule errors, then fix negation, quotation and past tense.

Read AGENTS.md, docs/HANDOFF.md, docs/ROADMAP.md and docs/release/RUNBOOK.md first.
Sprint tracking: docs/release/1.6.0-sprint.md. This ticket expands an already approved roadmap item; it does not
authorize Live, shared TEST replacement, rewards or decisions reserved to the owner.

## Plan

Preserve the owner routing order: name, facing, clear reply, closest in hearing bubble, nobody. First stop refusals and quoted/past requests turning into actions or false scam accusations. Rules and model admission must share any new semantic guard so a model cannot reintroduce a rejected request. Existing economy/romance restrictions and drama preferences remain. The agent on 17 owns model experiments, not game-server changes.

## Expected files

ConvoSense and ChatTriage request guards; historical and new regression tests; routing evaluation only after inspecting candidate logs; QA stages for real request results.

## Tests and completion

Fail-before/pass-after paired positive/negative examples, shorthand, tense, quoted speakers, fair trade/scam warnings, question versus request and safe confidence fallback. Preserve legacy corpora and routing order. Full suite plus isolated feature auto-QA.

Run the appropriate full suite, stage automated checks on the isolated 1.6.0 TEST copy when applicable, and
record what was actually checked. Keep flags safe/off by default. Real-client results remain pending until
observed. A plan or disabled framework is not a completed player feature. Commit and push; no release merge
without the owner request.

## Owner choices

No new model, threshold, reward, romance or routing-priority decision is implied. Ask only if a genuine gameplay ambiguity remains.
