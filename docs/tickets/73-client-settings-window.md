# Ticket 73: In-game settings window

Target: 1.6.0. Status: Planned on the current client line in its own worktree.

Read AGENTS.md, docs/HANDOFF.md, docs/ROADMAP.md and docs/release/RUNBOOK.md first.
Sprint tracking: docs/release/1.6.0-sprint.md. This ticket expands an already approved roadmap item; it does not
authorize Live, shared TEST replacement, rewards or decisions reserved to the owner.

## Plan

A discoverable settings UI for existing supported config.ini options. Use the established in-client UI/render path, preserve current defaults and validate values. No new gameplay feature or server protocol change hidden in this window. Changes must survive restart and be consistent with launcher options.

## Expected files

Client UI/config source and tests; own Windows build folder; optional discoverability hook; QA checklist.

## Tests and completion

Native config parsing/persistence tests, MSVC build/ctest, real-client UI at supported resolutions and Parsec modes, restart persistence and launcher consistency. Hash-diff DLL/client outputs.

Run the appropriate full suite, stage automated checks on the isolated 1.6.0 TEST copy when applicable, and
record what was actually checked. Keep flags safe/off by default. Real-client results remain pending until
observed. A plan or disabled framework is not a completed player feature. Commit and push; no release merge
without the owner request.

## Owner choices

Final controls and layout should follow existing launcher options. Show a concrete preview for owner review before publishing outside Test/RC.
