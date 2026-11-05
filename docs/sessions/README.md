# SlumsMS sessions (owner, 2026-10-27)

One Claude session per area; each owns its repos/branches and hands work to the others with SendMessage.

New here (a Claude session or an outside agent)? Start with **/root/work/START-HERE.md** (outside the repo): the reading
order per agent type and the table of pieces (dev branch, folder, owner, TEST/RC, live).

| Session | Owns | Kickoff |
|---|---|---|
| Server Dev SlumsMS | server code, release-* branches (only it merges), TEST deploys, release night, version notes | - |
| Website Slums | website, site repo (site-160-theme), TEST site stack; the launcher since 2026-11-04 (owner) | [website.md](website.md) |
| SlumsMS Coordinator | the release book, lane coordination, owner checklists, bug triage (qa-triage skill) since 2026-11-01, and tickets, tasks and release cycles since 2026-11-05 (owner); no code | - |
| ~~SlumsMS bug triage~~ | closed ~06:00 UTC 2026-11-01 to save usage; triage moved to the Coordinator | - |
| Client Development SlumsMS | client (slums.dll, client builds) | - |
| ~~launcher-tauri-48 (launcher)~~ | closed ~06:00 UTC 2026-11-01; the launcher moved to Website Slums on 2026-11-04 (kickoff kept for reference) | [launcher.md](launcher.md) |
| slumsms-160-playerlines-e7 (chat model) | pair generator, .17 runs, training, scoring. Training closed; m02's TEST proof and switch-on belong to Server Dev | [chat-model.md](chat-model.md) |
| SlumsMS QA | verification: tools/qa-runner, auto-QA runs on TEST, QA-pass cards, golden paths, soak; 1.7.0 W0 | [qa.md](qa.md) |
| SlumsMS Bots | bot + owned-alt runtime: LifeRuntime, TrainingBot, ActionAuthority; 1.7.0 W1 | [bots.md](bots.md) |
| CLI agents (Codex, omp, agy) | bounded tasks on a lane, handed out by a lane session | [EXTERNAL-AGENTS.md](EXTERNAL-AGENTS.md) |

Names are the SendMessage addresses (the owner renamed the first three on 2026-10-27).

1.7.0 lanes follow the locked plan in `docs/plans/1.7.0/` (owner, Nov 5): no 1.7.0 code merges until 1.6.4 is released and
verified and tagged `v1.6.4`; material deviations from the plan need evidence and the owner's approval.

Shared rules: AGENTS.md (hard rules; "Done means switched on and noted"); live needs the owner's go; no secrets;
never `git stash`; the version notes are the source of truth, and each session updates them for what it owns.
To start one: open a new Claude Code session in the server release worktree named in /root/work/START-HERE.md (now /root/work/SlumsMS-164-release) and paste the kickoff file's text.
