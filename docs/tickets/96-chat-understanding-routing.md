# Ticket 96: Player requests and chat routing

Target: 1.6.0. Status: Semantic request guard implemented; weighted routing evaluation remains open.

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

## Sprint evidence

Commits `c2215a4f2` and `d1664e81c` add shared clause filtering for refusals, quotes, reported/past speech,
scam warnings and fair price talk. ChatTriage, ConvoSense and the early buff shortcut all use the guard.
Positive follow/hold/buff requests and mixed clauses remain actionable. Paired regressions failed before the
changes and pass after. QA `cv-10`, auto-QA `t-cv-9`, replays refusals/warnings plus a positive follow request.
The full suite passes: 4,622 tests, zero failures/errors, 38 skips.

The model-host agent's 70-case fresh-v1 set has hash
`c473651dc07aedbe272a9ebd1f07e3940315e15ec31d4ad8e2decbc1240374ea`.
Replaying saved answers (no new inference) changed intent accuracy from rules 70.0%, GLiNER combined 60.0%,
gliclass combined 67.1% to rules 94.3%, GLiNER 91.4%, gliclass 92.9%. New wrong intents versus rules: 2 and 1;
new false abuse flags: 5 and 4. These cases are now a development regression set, not an unseen promotion test.
Results are under ignored `dist/fresh-final-{rules,gliner,gliclass}`. No endpoint, threshold or model changed.
Keep the current model while gathering another unseen set. Weighted routing, labelled RouteLog evaluation and
close-call selection are not implemented; the owner's existing routing priority remains.
