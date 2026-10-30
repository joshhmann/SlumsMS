# SlumsMS sessions (owner, 2026-10-27)

One Claude session per area; each owns its repos/branches and hands work to the others with SendMessage.

New here (a Claude session or an outside agent)? Start with **/root/work/START-HERE.md** (outside the repo): the reading
order per agent type and the table of pieces (dev branch, folder, owner, TEST/RC, live).

| Session | Owns | Kickoff |
|---|---|---|
| Server Dev SlumsMS (coordinator) | server code, release-* branches (only it merges), TEST, release night, version notes | - |
| Website Slums | website, site repo (site-160-theme), TEST site stack | [website.md](website.md) |
| SlumsMS Coordinator | the release book, lane coordination, owner checklists; no code | - |
| SlumsMS bug triage | the bugs list, triage and closing proof; no code | - |
| Client Development SlumsMS | client (slums.dll, client builds) | - |
| launcher-tauri-48 (launcher) | Tauri launcher, publisher, patch feed, signing, Defender | [launcher.md](launcher.md) |
| slumsms-160-playerlines-e7 (chat model) | pair generator, .17 runs, training, scoring | [chat-model.md](chat-model.md) |
| CLI agents (Codex, omp, agy) | bounded tasks on a lane, handed out by a lane session | [EXTERNAL-AGENTS.md](EXTERNAL-AGENTS.md) |

Names are the SendMessage addresses (the owner renamed the first three on 2026-10-27).

Shared rules: AGENTS.md (hard rules; "Done means switched on and noted"); live needs the owner's go; no secrets;
never `git stash`; the version notes are the source of truth, and each session updates them for what it owns.
To start one: open a new Claude Code session in /root/work/SlumsMS-162-release and paste the kickoff file's text.
