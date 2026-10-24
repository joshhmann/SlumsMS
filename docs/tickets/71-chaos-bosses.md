# Ticket 71: Chaos Zakum and Chaos Horntail

Target: 1.6.0. Status: Planned: compatibility audit before playable content.

Read AGENTS.md, docs/HANDOFF.md, docs/ROADMAP.md and docs/release/RUNBOOK.md first.
Sprint tracking: docs/release/1.6.0-sprint.md. This ticket expands an already approved roadmap item; it does not
authorize Live, shared TEST replacement, rewards or decisions reserved to the owner.

## Plan

Separate Chaos encounters, leaving ordinary bosses unchanged. Audit candidate mob WZ/animations/attacks for v83 compatibility before scripts or rewards. Reuse existing expedition/event machinery and bot guild staging. No unsupported skill/element may be sent to a real client.

## Expected files

Data compatibility report; separate event/NPC scripts and IDs; client/server pack staging if needed; guild boss adapters and QA.

## Tests and completion

Static mob/skill/dependency checks, instance isolation and cleanup, replayed rewards and expedition caps. Real-client tests for every phase and attack; owner clear before calling playable.

Run the appropriate full suite, stage automated checks on the isolated 1.6.0 TEST copy when applicable, and
record what was actually checked. Keep flags safe/off by default. Real-client results remain pending until
observed. A plan or disabled framework is not a completed player feature. Commit and push; no release merge
without the owner request.

## Owner choices

Target party size and difficulty requested. Rewards, entry limits and data source need review after the compatibility audit.
