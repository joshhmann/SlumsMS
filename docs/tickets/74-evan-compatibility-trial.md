# Ticket 74: GM-only Evan compatibility trial

Target: 1.6.0. Status: Planned research on a separate branch; never ship Evan in 1.6.0.

Read AGENTS.md, docs/HANDOFF.md, docs/ROADMAP.md and docs/release/RUNBOOK.md first.
Sprint tracking: docs/release/1.6.0-sprint.md. This ticket expands an already approved roadmap item; it does not
authorize Live, shared TEST replacement, rewards or decisions reserved to the owner.

## Plan

Test whether a character and dragon can animate together on this v83 client without crashes. Audit data/skill/packet prerequisites and record a bounded go/no-go result. Keep unavailable to ordinary players and omit all trial assets/code from release integration.

## Expected files

Separate server/client research branches and isolated client copy; compatibility inventory and real-client evidence.

## Tests and completion

GM gate, unsupported data/skills fail closed; client creation, dragon motion, attacks and map transitions under observation. Owner decides full Evan versus skill cover for 1.7.0.

Run the appropriate full suite, stage automated checks on the isolated 1.6.0 TEST copy when applicable, and
record what was actually checked. Keep flags safe/off by default. Real-client results remain pending until
observed. A plan or disabled framework is not a completed player feature. Commit and push; no release merge
without the owner request.

## Owner choices

Go/no-go after observed trial, not before. No additional classes or economy changes in scope.
