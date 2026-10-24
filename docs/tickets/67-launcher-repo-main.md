# Ticket 67: put the Tauri launcher on the launcher repo's main, with release tags

Status: open, 2026-10-24. Small, no live impact. Repo: github.com/joshhmann/slumsms-launcher.

## Why

The launcher players run (Tauri 1.5.2, live since 2026-10-24) is only on branch `launcher-ui-compact` (`573d7a2`).
`main` (`48decf8`) is 27 commits behind and still describes the old Electron launcher, and the newest tag is
`v1.3.0`. Anyone cloning the repo gets the wrong launcher, and there is no tag to rebuild a shipped version from.

## Do

1. Check `origin/main` is an ancestor of `origin/launcher-ui-compact` (it was on 2026-10-24) and fast-forward `main`
   to `573d7a2`. No force push. If `main` moved since, merge instead and list what came in.
2. Tag the shipped builds (annotated tags, pushed):
   - `v1.5.1` → `cbad0ea` (Tauri 1.5.1, live 2026-10-24 08:06 UTC until 1.5.2)
   - `v1.5.2` → `573d7a2` (Tauri 1.5.2, live 2026-10-24; exe sha256 `9719282c…5158`)
   - `handover-1.5.0` → `fa1cabe` (the Electron hand-over build on the Live `launcher.json`)
   Check each tag rebuilds to the version it names (`--version`); hashes won't match byte-for-byte (unsigned
   local build), which is fine.
3. README on `main`: Tauri is the launcher; how to build it on Windows (Silvia, `Z:\MSv83\SlumsMS-dev`), that signing
   happens on 187 only (`/root/.config/slumsms-launcher/tauri-signing.key`, never copied), the channels (Live, Test,
   RC), where publishing is documented (`docs/release/RUNBOOK.md` step 2.6 in the server repo), and the friends build
   (`bake-tauri-key.ps1`, run by the owner only). The Electron code stays as `launcher-handover` until it's retired.
4. Make `launcher-ui-compact` → `main` the default for new launcher work: update the server repo's `AGENTS.md`,
   `docs/AGENT-PROMPTS.md` (prompt 5) and `Z:\MSv83\SlumsMS-dev\AGENTS.md` to say "branch from `main`".
5. Don't delete old branches; list the stale ones (`access-key`, `feat/maplestory-theme`, `self-update`, ...) in the
   ticket for the owner to decide.

## Rules

No keys in the repo or tags (the friends build's key is never committed; `tauri/launcher.config.json` keeps
`accessKey` empty). Nothing here publishes a launcher; the Live and Test feeds are untouched.

## Done when

`main` = the shipped Tauri source, tags `v1.5.1`, `v1.5.2`, `handover-1.5.0` exist, the README describes the Tauri
launcher, and the docs point new work at `main`.
