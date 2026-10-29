---
name: release-book
description: Build and keep one "release book" per SlumsMS version - a single Claude Doc with where the version stands, the true player patch notes, the promise audit, bug squashing, who owns what, the owner's decisions and the release-night checklist - plus the db-backed squash board (board items and bugs, each with a notes box for the owner). Use at the start of every version, whenever the owner asks for a sitrep, "one doc", "where are we", "what's left before live", an audit of promises, or a bug/board update, and again on release night. Also use when lanes report in, so the book and board stay current.
---

# Release book (one per version)

1.6.1 taught this: notes, an audit, a sitrep, two boards and a click-through list lived on six pages, the notes
over-promised, and features "merged" were mistaken for "working". One book plus one live board fixes that.

## The two artifacts

1. **Squash board** (live tracker, an HTML artifact with the `db` capability). Copy the 1.6.1 one
   (https://claude.ai/artifact/MyhfTPAtE1oAvDmpz6FTcG, scratchpad file `squash-board.html`) for a new version.
   - Collection `items` (board): `{lane, ord, kind: fix|verify|decide|process, status: open|doing|check|done,
     title, done (the "done when" test), note}`. doc_id `s01` server, `c01` client, `l01` launcher, `w01` website,
     `m01` chat model, `o01` ops, `p01` process.
   - Collection `bugs`: `{num, lane, sev: S1-S4, status: new|triaged|fixing|fixed|wontfix, title, repro,
     found_by, found_at (UTC), fix, note}`; doc_id `b` + 3 digits. S1 breaks live or loses data, S2 a feature
     doesn't work, S3 wrong with a workaround, S4 polish.
   - Every card has a **My notes** box; the owner's text is in `owner_note` (+ `owner_note_at`). Read those notes
     whenever you update the board and act on them.
   - Lane sessions file their own bugs with ArtifactData: list or query the highest `num` right before writing
     (concurrent filers collide), then `set` the next id. Updates always carry `if_version`.
2. **Release book** (a Claude Doc, one tab). Sections, in this order:
   1. **Where it stands**: a table per piece (server, client, launcher, website, chat model) with live vs
      TEST/RC version and time, the QA run tally, and the release gate as a `- [ ]` checklist.
   2. **Patch notes (players)**: only what ships switched on and works; nothing that is off for live.
   3. **Promise audit**: feature, promise (the notes' words), state, proof so far (auto-QA id, real-client
      screenshot), still needs.
   4. **Bug squashing**: counts per lane and status, the open list, a link to the board.
   5. **Who owns what**: lane, owner session (ListAgents names), what it owns, what it needs from the owner.
   6. **Owner decisions**: date, decision, newest first. Mirror them into memory (`decisions-<date>.md`).
   7. **Release night**: numbered steps in deploy order, then the rollback per piece.
   8. **Sources**: older pages it replaces and the repo files (docs/release/<v>-notes.md, <v>.conf).

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

- Start of a version: copy the board, seed items from the roadmap and the notes, create the book.
- After each merge batch, TEST deploy or lane report: update the board (statuses, notes, new bugs) and the book's
  sections 1, 3 and 4. Read the owner's notes boxes first.
- Before release night: rerun the notes audit, a full auto-QA run and the QA-client pass; fill section 7.
- After the release: mark the book released, carry open bugs to the next version's board.

Related skills: `release-readiness` (switches, hotfix parity, QA coverage), `notes-review` (site release page),
`deploy-test`, `qa-triage`.
