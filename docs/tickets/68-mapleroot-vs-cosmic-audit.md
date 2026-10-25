# Ticket 68: audit MapleRoot's changes to stock Cosmic for broken v83 features

Status: open, 2026-10-25. Owner OK'd. Next after DeepSeek's current 1.5.1 items. Live untouched; findings ship in
1.5.1 or later.

## Why

Three player-facing bugs reported in the first day of 1.5.0 were all MapleRoot changes to Cosmic, present since our
baseline commit `03d13cb21`, never touched by our own work and never covered by our QA:
- `EventInstanceManager.clearPQ` assumed an expedition: every non-boss PQ/event clear crashed (fixed `039d13164`).
- Face/hair id ranges in `ItemInformationProvider`/`ItemConstants`: salons and face/eye coupons said "no options"
  (fixed `1fe365733`).
- `ENABLE_PIC: false` (Cosmic default true): characters could not be deleted (config, on for 1.5.1).
There are likely more of the same kind.

## Do

1. Diff our tree against stock Cosmic (`/root/work/cosmic-triage/Cosmic`; the old `SlumsMS-cosmicdiff` worktree has
   earlier notes) for `src/main/java` (handlers, scripting, server, client, constants), `scripts/` (npc, quest, event,
   portal, reactor) and `config.yaml` defaults. Ignore our own SlumsMS packages (`soloMapling.*`) and files we
   changed on purpose (check `git log` for each: anything only touched by `03d13cb21` is MapleRoot's).
2. For each MapleRoot change: what it does, whether it can break a stock v83 feature (null assumptions, hard-coded
   id ranges that MapleRoot's own data exceeds, disabled flags, removed handlers), and a verdict: keep, fix, or ask
   the owner (anything that changes rates, economy or content).
3. Fix the clear bugs on `151-<topic>` branches with tests that fail before the fix; list the rest in this ticket.
4. Check config flags that differ from Cosmic's defaults and list them with what each does for players.

## Done when

A table in this ticket (file, change, risk, verdict, fix commit) covering every MapleRoot-only change, fixes merged
into release-1.5.1 with tests, and owner questions listed.
