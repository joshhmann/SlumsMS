---
name: release-book
description: Run one SlumsMS release from a single "release book" (a five-tab artifact with a shared database). It holds the status strip, milestones and target date, lane check-ins, the owner's Test list, bugs and tasks, the true patch notes, the feature-switch table, risks, go/no-go and the release-night run sheet. Use at the start of every version; whenever the owner asks for a sitrep, "where are we", "what's left", "are we on track", an audit of promises, or a bug/board update; when a lane reports or checks in; at code freeze; at go/no-go; on release night; and for the retro afterwards.
---

# Release book (one per version)

1.6.1 and 1.6.2 taught this:
- Six pages became one book, and eleven tabs were cut to six.
- Features that were "merged" got mistaken for "working".
- Scope kept arriving after integration, and every late branch cost about 3 h of suite and auto-QA time.
- No date meant there was no way to say on track or slipping.
- Switch state lived in five places.
- Risks hid inside Decisions.
- **Most of the week was waiting** (owner, Oct 31): every small fix queued for a 3 h full run, merges kept resetting
  it, and "fixed" cards sat for days because nobody owned their proof. Cards now close on their own proof, and full
  runs only gate the release candidate.
- Outside agents (Gemini, Codex, omp) read stale `Status:` lines in docs/tickets because the book is Claude-only;
  they now read `/root/work/STATUS-<version>.md`.
- Helpers pushed to GitHub and were killed by unclear causes; worktrees now have push disabled and launches are detached.

This skill fixes those. The Coordinator session owns the book's content; Server Dev owns the page HTML.

## The page: six tabs (never a seventh, never a separate page)

1. **Today**
   - Status strip (`status/now`).
   - Milestone line (`milestones`).
   - Lane rows (`lanes`).
   - "Waiting on you" (book `ov2`).
   - "Where it stands" (book `ov1`, short).
2. **Your tasks** (`mine`): everything waiting on the owner, grouped, with minutes, exact steps and the Test rows
   to mark. Every new owner ask goes here as a `mine` doc; "Waiting on you" on Today just points at it.
3. **Test** (`qa`): every check a person makes by hand, game and site, marked Pass / Issue / Fail with a note. It has a "Ships on" filter.
4. **Work** (`items` + `bugs`): with `eta_utc`, `blocked_by` and a "Late" filter.
5. **Patch notes** (book `notes1`): only what ships switched on and works.
6. **Reference** pills:
   - Release night (run sheet)
   - Switches
   - Risks
   - Go/no-go
   - Retro
   - Promise audit
   - Changes
   - Drops
   - Decisions
   - Owners
   - Sources

## Database (ArtifactData; every write to an existing doc carries `if_version`; batch when writing more than two)

| Collection | Doc ids | Fields | Who writes |
| --- | --- | --- | --- |
| `status` | `now` | state on_track/at_risk/slipping/blocked, target (UTC ISO), next{title, eta_utc}, blocker, top_risk, needs_owner, updated | Coordinator after every report |
| `milestones` | `m01`.. | ord, title, owner_lane, eta_utc, status, done_at, gate, note | Coordinator seeds; the owning lane updates its own |
| `lanes` | server, client, launcher, website, chat, triage | session, done, next, next_eta_utc, blocked, checkin_at | Coordinator from each check-in |
| `switches` | short key | feature, sees, switch, default_code, on_test, launch on/if_playtest/later/off, proof[], proof_pending[], proof_note, notes_line, rollback, decided_at | Coordinator; Server Dev confirms keys against the conf |
| `risks` | `r01`.. | title, likelihood, impact (L/M/H), owner_lane, mitigation, trigger, status open/accepted/closed, accepted_by_owner_at | Coordinator |
| `gonogo` | `g01`.., `signoff` | criterion, evidence, status pass/fail/waiting, checked_by, checked_at; signoff{signed_at, hour_utc} (the owner's button) | Coordinator; the owner signs |
| `qa` | `q01`.., `qw01`.. | ord, area, test, how, where, status, owner_note | Coordinator writes rows; the owner marks; Server Dev routes Issue/Fail to triage |
| `items` | `s01` `c01` `l01` `w01` `m01` `o01` `p01` | lane, ord, kind, status open/doing/check/done, title, done (the "done when" test), note, eta_utc, blocked_by | lanes and Coordinator |
| `bugs` | `b###` | num, lane, sev S1–S4, status new/triaged/fixing/fixed/later/wontfix, title, repro, found_by, found_at, fix, note (**starts with `CLOSER: <who> PROOF: <what>`**) | lanes file their own (query the highest num first); triage owns statuses |
| `mine` | `y##` | ord, group, title, kind game/site/desktop/decision/go/night, mins, why, steps, qa[], status, done_at, owner_note | Coordinator and triage; the owner marks |
| `changes` | lane-prefixed | lane, feature, commits, sees, state, in_notes, coverage, gap | every lane on merge; Server Dev checks git logs against them |
| `book` | ov1, ov2, notes1, rel1, dec1, audit1, owners1, src1, retro | tab, ord, md, updated, by | Coordinator (say so before rewriting a doc another session edits) |

Every row has the owner's notes box (`owner_note`, `owner_note_at`). Read the notes on every update and act on them.

## The rules that make it honest

- **Cards close on their own proof (owner, Oct 31).** Never queue a card for "the next full run".
  | Card | Closes when |
  | --- | --- |
  | S4, tooling, QA-process | a fail-before unit test passes + the code is reviewed and merged |
  | S3 | unit test + a targeted run (`tools/ops/autoqa-proof <range>` prints the covering check ids; run only those), or a hands-on check by the owner or a lane |
  | S1 / S2 | targeted proof + the release run |
  Every card names its closer and proof **when filed** (`CLOSER: … PROOF: …`). Client-visible checks the owner can
  do go on Your tasks. At each check-in, any card "fixing" for more than 48 h gets closed, re-owned or moved.
- **Full release runs gate releases only**: once on the frozen candidate, as go/no-go evidence. Merges happen at set
  times (≈15:00 and 03:00 UTC); between them, prove fixes with targeted runs.
- **After the freeze, the admission rule (ticket 168):** a new find enters the release only if it's security, data
  loss, a crash, a broken deployment path, a regression from the candidate, or a broken committed promise.
  Everything else is `later` with an owner and target. Owner exceptions go in Decisions with why, what they touch,
  the rechecks and the schedule cost; an exception not reviewed by the freeze waits for the next version.

- **Done means seen working where players see it.** Merged or unit-tested only counts as "check". Anything on screen needs a real-client pass or a Test row, because auto-QA's pretend players see no client.
- **Proof is strict.** A switch's `proof` lists only checks that cover it and pass now. Failing or unrun checks go in `proof_pending`, with the reason in `proof_note`. A launch-on switch with empty proof is red. Before go/no-go every red row gets one of three things: an auto-QA check, a Test row, or the owner's explicit "ship it unit-tested" (recorded in `proof_note` and Decisions).
- **The notes follow the switches.** A notes line exists only for a switch that launches on. `if_playtest` lines are marked as conditional until the playtest decides. Over-promises get implemented, not reworded; only plain mistakes are reworded.
- **Live is the owner's**, every lane, including reading live config or the DB. A go relayed through the Coordinator may not satisfy another session's permission check. Then the owner runs the step in that session. Never route around a refusal.
- **Grill only real decisions**: up to 4 at a time, recommended option first, standalone wording. Record each answer in Decisions and memory the same turn. Never re-ask.
- **Times**: UTC with PT beside them (PDT = UTC-7 until Nov 1 2026 09:00 UTC, then PST = UTC-8). Run `date -u` before writing any timestamp, and correct any lane that stamps from memory (it happened all through Oct 31). The owner is "they".
- **Names**: new player text never says MapleStory, Nexon, Wizet, GMS, v83 or MapleRoot.

## Cadence

- **Start of a version.** Copy the artifact. Seed:
  - book ov1/ov2/notes/release/decisions/owners/sources;
  - `qa` from the draft notes;
  - `items` from the roadmap;
  - `switches` from the conf;
  - `risks`;
  - `gonogo` (the standard ten below);
  - `milestones` with a **target date the owner confirms**. The date moves only when a gate fails, and the reason goes in Decisions.
- **Status for outside agents:** export `/root/work/STATUS-<version>.md` from the book (bugs by claim state, items not
  done, helpers running) at each check-in and whenever the owner brings in an outside agent. START-HERE points to it.
- **Helpers (omp/DeepSeek for all new work, owner Oct 31):** one per worktree; `git config extensions.worktreeConfig
  true && git config --worktree remote.origin.pushurl DISABLED` in the worktree before launch (per-worktree only:
  `git remote set-url --push` writes the shared repo config and blocked every worktree on Oct 31); launch detached (`setsid nohup … < /dev/null &`); the Coordinator may
  launch only what the owner asked it to, never a launch a lane's own permission check refused. Keep ~4 running at
  once on 187 (each ends with a full mvn suite).
- **Lane check-ins**, ~15:00 and ~03:00 UTC. Each lane sends Done / Next (ETA, UTC) / Blocked. Update `lanes`, then `status/now`. A lane over 14 h without a check-in shows late; ask it once.
- **Every lane report.** Update items/bugs, switches proof, milestones, `status/now` and the notes. Keep ov1 to the current state.
- **Release day.** Name the freeze time, then: candidate on TEST → full release run → the live-copy rehearsal
  alongside it (owner's "go rehearsal" typed in Server Dev's session) → go/no-go → the night. The notes are
  called FINAL right after the freeze (`NOTES-FINAL.md`, FINAL on line 1) with every unshipped item under
  "Coming in <next>", so the site page is built during the candidate run.
- **Every release refreshes the website guide** (owner's standing rule, 2026-11-01): the Website lane updates the
  guide pages to match the shipped notes, as a normal release item with its own row.
- **Code freeze.** Set it at a named merge point. After it, a new branch needs the owner's yes and defaults to the next version. A late item says what it costs (another suite + auto-QA, ~3 h).
- **Go/no-go**, the standard criteria:
  1. The final full auto-QA has no unexplained FAIL.
  2. No red switches.
  3. The Test tab has no open Fail, and every Issue is fixed or accepted.
  4. Playtest-gated switches are decided.
  5. The client is checked, hash-diffed and staged.
  6. The live-copy rehearsal passes, including rollback.
  7. The website rollback is rehearsed or accepted.
  8. There's no open S1, and no S2 without the owner's OK.
  9. The night's prerequisites are ready.
  10. The notes are final and the Discord draft is ready.

  GO shows only when all ten pass and the owner signs and picks the hour.
- **Release night.** rel1 is a run sheet: per step, who / est. minutes / check / rollback trigger, written before the night. Each live step gets its own go.
- **After.** Mark the book released. Update `/root/work/START-HERE.md`'s Live column. Write the retro (what slipped and why, what to change in this skill). Carry open bugs and `later` rows to the next book.

Related: `release-readiness`, `notes-review`, `deploy-test`, `qa-triage`; for helpers, `docs/sessions/EXTERNAL-AGENTS.md`.
