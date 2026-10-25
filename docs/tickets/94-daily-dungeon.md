# Ticket 94: Daily dungeon with bot friends

Target: 1.6.0. Status: Planned after fishing and medals.

Read AGENTS.md, docs/HANDOFF.md, docs/ROADMAP.md and docs/release/RUNBOOK.md first.
Sprint tracking: docs/release/1.6.0-sprint.md. This ticket expands an already approved roadmap item; it does not
authorize Live, shared TEST replacement, rewards or decisions reserved to the owner.

## Plan

A once-per-character daily activity with 4–6 rooms on existing maps, a supported party and bot companions. Reuse event instances; scope spawns, rewards and cleanup to that instance. Persist entry/claim boundaries across disconnects/restarts; never use a shared normal map as an instance.

## Expected files

Event script, entry NPC/menu, instance policy and claim storage, bot companion adapter, QA and content validation.

## Tests and completion

Party eligibility and world 1, duplicate entry/claim, Pacific/day boundary policy, disconnect/rejoin, leader departure, timeout and complete cleanup. Real-client room transitions and bot following.

Run the appropriate full suite, stage automated checks on the isolated 1.6.0 TEST copy when applicable, and
record what was actually checked. Keep flags safe/off by default. Real-client results remain pending until
observed. A plan or disabled framework is not a completed player feature. Commit and push; no release merge
without the owner request.

## Owner choices

Exact maps, level bands, reset time, encounter difficulty and chest rewards. Draft concrete choices; no unapproved economy change.
