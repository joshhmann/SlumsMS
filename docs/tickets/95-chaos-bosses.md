# Ticket 95: Chaos Zakum and Chaos Horntail

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

## First data audit

Owner direction: keep existing Chaos values first, then tune on TEST. No boss data or rewards changed.
Read-only audit found Chaos data on TEST and corresponding core art in the RC client data. Examples:
Zakum 8800100/101/102 body HP 900,000,000 / 1,000,000,000 / 1,500,000,000;
Horntail pre-heads 8810100/101 HP 1,650,000,000, main 8810102 HP 2,147,483,647.
These are the existing TEST data values, not independently verified canonical later-version values.
The local ignored `dist/chaos-audit/mobs.jsonl` records source hashes, HP, elements and skill references.

A follow-up read-only pass across these 22 core Chaos mob files found all 65 distinct referenced skill/level
pairs in TEST MobSkill data, and all 11 directly summoned mob files. Receipt: ignored
`dist/chaos-audit/dependencies.json`. This checks presence only, not recursive summon behavior or client support.

Next: audit linked/summoned behavior and client fields, build separate events and entry checks, then observe
every attack/phase in a real client. Existing enum entries and data do not prove compatibility. No playable
Chaos encounter or new event script was added. Normal bosses remain unchanged.
