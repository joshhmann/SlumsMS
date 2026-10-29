---
name: release-book
description: Build and keep one "release book" per SlumsMS version - a single tabbed artifact with where the version stands, the true player patch notes, the promise audit, bug squashing, who owns what, the owner's decisions and the release-night checklist - including the live board, bugs and the owner's TEST click-through, each card with a notes box for the owner. Use at the start of every version, whenever the owner asks for a sitrep, "one doc", "where are we", "what's left before live", an audit of promises, or a bug/board update, and again on release night. Also use when lanes report in, so the book and board stay current.
---

# Release book (one per version)

1.6.1 taught this: notes, an audit, a sitrep, two boards and a click-through list lived on six pages, the notes
over-promised, and features "merged" were mistaken for "working". One book plus one live board fixes that.

## One artifact per version

The book is ONE HTML artifact with the `db` capability (1.6.1: https://claude.ai/artifact/MyhfTPAtE1oAvDmpz6FTcG,
scratchpad file `squash-board.html`; copy it for a new version and change the title). It holds every other page as a
tab: Overview, Patch notes, Promise audit, Board, Bugs, Click-through, Owners, Decisions, Release night, Sources.
Deep links: the artifact URL plus `#board`, `#bugs`, `#click`, etc. Never make separate pages for these again.

Its database (write with ArtifactData; every update carries `if_version`):
- `book`: the written tabs, one doc per section `{tab, ord, md, updated, by}`. `tab` is overview | notes | audit |
  owners | decisions | release | sources | click (intro). `md` is GitHub markdown (tables, `- [ ]` lists); the page
  renders it. Update a section by rewriting its `md` and `updated` (UTC + PT). No republish needed.
- `items`: board cards `{lane, ord, kind: fix|verify|decide|process, status: open|doing|check|done, title, done (the
  "done when" test), note}`; doc_id `s01` server, `c01` client, `l01` launcher, `w01` website, `m01` chat model,
  `o01` ops, `p01` process.
- `bugs`: `{num, lane, sev: S1-S4, status: new|triaged|fixing|fixed|wontfix, title, repro, found_by, found_at (UTC),
  fix, note}`; doc_id `b` + 3 digits. S1 breaks live or loses data, S2 a feature doesn't work, S3 wrong with a
  workaround, S4 polish. Lane sessions file their own: query the highest `num` right before writing, then `set`.
- `stops`: the owner's TEST click-through `{ord, title, url, mins, checks[], done}`.
- Every card, section and stop has a **My notes** box: the owner's text is `owner_note` (+ `owner_note_at`). Read
  them on every update and act on them.

What each tab must say:
1. **Overview**: a table per piece (server, client, launcher, website, chat model) with live vs TEST/RC version and
   time; the QA run tally; the release gate as `- [ ]`; a "Waiting on you" list.
2. **Patch notes**: only what ships switched on and works; nothing that is off for live.
3. **Promise audit**: feature, promise (the notes' words), state, proof so far (auto-QA id, real-client
   screenshot), still needs.
4. **Owners**: lane, owner session (ListAgents names), what it owns, what it needs from the owner.
5. **Decisions**: date, decision, newest first. Mirror them into memory (`decisions-<date>.md`).
6. **Release night**: numbered steps in deploy order, then the rollback per piece.
7. **Sources**: older pages it replaces and the repo files (docs/release/<v>-notes.md, <v>.conf).

## Change inventory (nothing ships undocumented)

1.6.1 shipped a whole Slums Market overhaul on the website that no page mentioned, so nobody tested it. The fix:
every change in every lane is a row before the release, not just the features someone remembers.

- Keep a `changes` collection in the book (and a Changes tab): `{lane, feature, commits, sees: player|staff|owner,
  state: live|ships|test-only|off, in_notes: yes|no|n/a, coverage: <auto-QA id / unit class / client screenshot /
  site test / click-through stop / NONE>, gap}`.
- Rebuild it from the git logs of all four repos (server, site, client on Silvia, launcher) since the last release
  tag, grouped into features: at the start of release prep, after every merge batch, and on release night.
- Release gate: no row may be player-visible + ships + coverage NONE. Each such row becomes a click-through stop, a
  QA-client step or an auto-QA check, or the owner explicitly accepts it untested.
- Every lane session adds its own rows when it merges something (same rule as filing bugs). The server session
  checks the git logs against the rows, because sessions forget.

## Rules that make it honest

- **Done means seen working where players see it.** Merged or unit-tested is "fixing"/"check". Server features
  that touch the screen (NPC menus, portals, tooltips, warps) need a QA-client pass on Silvia, not only auto-QA:
  auto-QA uses synthetic players inside the server and sees no client.
- **Audit the notes against the code every version**: a helper reads every player line and lists claims the code
  doesn't fully do (file:line). The owner's rule: over-promises get implemented, not reworded; only plain mistakes
  (a wrong colour, a wrong item name) are reworded. Check item names against the client's String data.
- **Grill the owner only on real decisions**, in batches of up to 4 with a recommended option first
  (AskUserQuestion); record each answer in section 6 and in memory, and never re-ask.
- **Live always needs the owner's go**, every lane. Launcher signing needs the go in the launcher session itself.
- Times in UTC with PT next to them. Never mention MapleStory/Nexon/Wizet/v83/MapleRoot in player text.

## Cadence

- Start of a version: copy the artifact, seed `book`, `items` and `stops` from the roadmap and the notes.
- After each merge batch, TEST deploy or lane report: update `items`/`bugs` and the Overview and Promise audit
  sections. Read the owner's notes boxes first.
- Before release night: rerun the notes audit, a full auto-QA run and the QA-client pass; fill Release night.
- After the release: mark the book released, carry open bugs to the next version's board.

Related skills: `release-readiness` (switches, hotfix parity, QA coverage), `notes-review` (site release page),
`deploy-test`, `qa-triage`.
