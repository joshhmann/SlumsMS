# Ticket 97: In-game settings window

Target: 1.6.0. Status: Built on client-160-settings; 20 native tests pass; real-game visuals/focus/restart checks pending.

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

## Built sprint candidate

Client branch `client-160-settings` is pushed through documentation commit `360b2e7`; feature code `c8202e1`,
real Windows control test `1bf9ded`. Worktree `/root/work/client/src-160-settings`; own Windows build folder
`Z:\MSv83\SlumsMS-dev\client-160-settings`. MSVC Win32 Release, current features ON, two build jobs.
Ctrl+F10/window menu opens a modeless settings panel; Save preserves unrelated keys and applies after restart.
No fixed v83 patch address, packet, game endpoint or installed client changed.

Native build + **20/20 ctest pass**, including actual Windows controls/INI Save and Cancel in a temporary fixture.
Portable g++17 policy test also passes. Final DLL hash:
`4408a63d7218957bccd6c446f18f494bac4bfc5086310cc54549f137aa35142e`.
Client build stamp override 1.6.0-dev / 10600. Full detail: client repo `docs/160-settings-qa.md`.
No game was started, no installed DLL replaced and no feed published. The client worker is unavailable, and its
8489 endpoint guard must stay in place; do not claim this built candidate is visually cleared for players.
