# SlumsMS sessions (owner, 2026-10-27)

One Claude session per area; each owns its repos/branches and hands work to the others with SendMessage.

| Session | Owns | Kickoff |
|---|---|---|
| solomapling-a7 (coordinator) | server code, release-* branches (only it merges), TEST, release night, version notes | - |
| slumsms-site-0b | website, site repo (site-160-theme), TEST site stack | - |
| src-15 | client (slums.dll, client builds) | - |
| launcher | Tauri launcher, publisher, patch feed, signing, Defender | [launcher.md](launcher.md) |
| chat-model | pair generator, .17 runs, training, scoring | [chat-model.md](chat-model.md) |

Shared rules: AGENTS.md (hard rules; "Done means switched on and noted"); live needs the owner's go; no secrets;
never `git stash`; the version notes are the source of truth, and each session updates them for what it owns.
To start one: open a new Claude Code session in /root/work/SlumsMS-160-release and paste the kickoff file's text.
