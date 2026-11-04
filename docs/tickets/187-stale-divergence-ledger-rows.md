# Ticket 187: three divergence-ledger rows now describe code that no longer exists, and only the owner can retire them

**Status:** owner confirmation required, docs-only, 1.6.4. Filed 2026-11-02 09:04 UTC (01:04 PST) from the 1.6.4
content hunt. A ledger row is a decision record, not a code fact, so this ticket does not change the rows: it lists
what the code now does and asks the owner to confirm the wording. Line numbers as of `release-1.6.4` tip
`c0e0a2cd7`; ledger lines are the same on `163-integration`.

`docs/design/divergence-ledger.md` opens by saying it is "one living list of every place our server differs from stock
v83 (stock Cosmic), who changed it, and what was decided". Three rows are now older than the code.

## NP-007 (ledger line 130) — should read `keep / on`, not `undecided`

> `| NP-007 | Family system off (`USE_FAMILY_SYSTEM` false; stock on) | MapleRoot | undecided | Ticket 68 owner question #4: deliberate? | ticket 68 |`

What is true now:

- The subsystem is complete and running: `USE_FAMILY_SYSTEM=true` has been in `SERVER_SET` in
  `docs/release/1.6.3.conf`, so live runs the family system on, and 1.6.2's migrations 041/053 land the bot juniors and
  buffs that need it.
- The four tuning defaults that used to mute it even when it was on landed as `6cea00b4e` ("merge 164-familycfg: give
  the family rates their real defaults"): `FAMILY_REP_PER_KILL = 4`, `FAMILY_REP_PER_BOSS_KILL = 20`,
  `FAMILY_REP_PER_LEVELUP = 200`, `FAMILY_MAX_GENERATIONS = 1000` at `ServerConfig.java:486-489`, with the comment that
  0 "pays no reputation at all and turns the generation cap into a wall that refuses every join"
  (`FamilyAddHandler.java:71`, `AcceptFamilyHandler.java:105`).
- The field itself still has no inline initialiser (`ServerConfig.java:289` on `163-integration`), so a config without
  the key still ships the system off — that is a configuration fact, not a decision.

Proposed row: `NP-007 | Family system on (config default stays false; every release conf sets USE_FAMILY_SYSTEM=true; the four rates default non-zero since 6cea00b4e) | SlumsMS | keep / on | 164-familycfg; 1.6.3 SERVER_SET | 164 content hunt`.

## SK-033 (ledger line 82) — already handled by migration 025

> `| SK-033 | 19 mastery books for removed skills still drop (82 mob rows + 10 reactor rows) and do nothing | MapleRoot | undecided | Rec: remove from drops. Decisions #7 | audit #1 |`

The recommendation was carried out: `database/migrations/025-remove-dead-skill-books.sql:1-13` documents the fix — the
19 books, the 11 deleted skills, the 82 mob drop rows and 10 reactor rows are deleted, with the rows copied to
`migration_025_removed_drop_data` / `migration_025_removed_reactordrops` and a matching `.rollback.sql`. `025` is in
`LEDGER_BEFORE` of the 1.6.3 conf, so live already has it.

Proposed row: `SK-033 | 19 mastery books for removed skills: drop rows deleted (82 mob + 10 reactor) by migration 025, rows kept in migration_025_removed_* | SlumsMS | fixed | 025-remove-dead-skill-books.sql | 164 content hunt`.

## MP-003 (ledger line 142) — half of it is gone

> `| MP-003 | Expedition maps (Zakum 211070100/101): any USE item is applied to every character on the map; `inExpedition` / `potionCount` flags leak on death, GM warp, script warp | MapleRoot | undecided | Rec: group potions are a design choice; the flag leak is a bug to fix wherever flags reset | `UseItemHandler:55`, `ChangeMapHandler:146` |`

What is true now:

- `inExpedition` does not appear anywhere in `src/main/java`: the flag the row names no longer exists.
- The map-wide apply is now gated on one item. `net/server/channel/handlers/UseItemHandler.java:99-106` applies a use
  item to the whole map only when `toUse.getItemId() == ItemId.HAPPY_BIRTHDAY`; everything else applies to the user
  (`ii.getItemEffect(...).applyTo(chr)`), and the comment below it records the owner's 2026-10-25 decision that the
  expedition-potion leak is gone.
- Group potions therefore remain a deliberate feature for the birthday cake only, which is what the row's own
  recommendation ("group potions are a design choice") wanted.

Proposed row: `MP-003 | Birthday cake (HAPPY_BIRTHDAY) applies map-wide; every other USE item applies to its user. The old inExpedition/potionCount leak is gone with the flag | SlumsMS | keep (group apply narrowed to one item) | UseItemHandler.java:99-106 | 164 content hunt`.

## Acceptance

- The owner confirms or rewrites the three proposed rows; nothing is edited before that.
- When edited, the rows keep their ids (other rows and reports cite them) and each row's decision column says what is
  now true, not what was proposed.
- No code change in this ticket.

## For the release book

- **Card:** Docs | three divergence-ledger rows describe code that has changed (family system on, dead skill books deleted by migration 025, `inExpedition` no longer exists) | S4 | lane Server | closer: the owner confirms the proposed wording and the three rows are rewritten in `docs/design/divergence-ledger.md`.
- **Patch notes (draft, player words):** staff only, no line.
- **Confign line:** none.
- **Test-tab row:** none.
