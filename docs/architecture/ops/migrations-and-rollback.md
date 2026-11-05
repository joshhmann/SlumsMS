# Migrations and rollback

## Purpose

Every schema or seed-data change ships as an idempotent pair: `database/migrations/NNN-name.sql` plus a matching
`NNN-name.rollback.sql` (AGENTS.md:117-119). Each forward file records itself in one ledger table, so the release
tooling can answer "what does this database already have" without guessing, and each rollback removes exactly its own
ledger row. This page documents the 61 pairs on this branch, the two bookkeeping tables they share, how a release conf
selects a subset, how `deploy.sh` applies it and how `rollback.sh` undoes it.

## Entry points

| Command | Arguments | What it does with migrations |
|---|---|---|
| `tools/release/gen-deploy` | `<conf> <build dir> <out dir> <version>` | validates the conf's `MIGRATIONS` against the repo, writes `deploy.sh` and `rollback.sh` (tools/release/gen-deploy:29-33, :96-100) |
| `tools/release/stage` | `<version> [--conf FILE] [--replace] [--dry-run]` | calls `gen-deploy`, and copies **every** repo migration into `staging/migrations/` (tools/release/stage:56) |
| `tools/release/rehearse` | `<version>` | ssh's to the release host and runs `rehearse-host.sh`, which deploys and then rolls back on a scratch copy (tools/release/rehearse-host.sh:212-227) |
| `tools/release/release` | `<version>` | runs `deploy.sh --phase stop-backup`, publish, `--phase apply-start`, `--phase smoke`; on failure it stops the unit, restores the manifest and runs `rollback.sh` (tools/release/release:186-205) |
| `bash .../v<ver>/deploy.sh` | `[--dry-run] [--phase stop-backup\|apply-start\|smoke\|restart-old]` | pre-checks, backup, apply, start, smoke (tools/release/templates/deploy.body.sh:23-27) |
| `bash .../v<ver>/rollback.sh` | `[--dry-run]` | undoes only the migrations this deploy ran, restores the old runtime, starts (tools/release/templates/rollback.body.sh:48) |
| `tools/ops/deploy-test` | `[--feature] [--skip-build] [--dry-run] [--since <commit>] [--no-migrations]` | applies new `.sql` files to the TEST schema; **no rollback path at all** (tools/ops/deploy-test:27-39, :105-111) |

## How it works

1. **Conf lists file stems.** `docs/release/1.6.4.conf:166` is
   `MIGRATIONS=(059-normal-gear-through-200 … 065-lkc-quest-drops)`; `docs/release/1.6.3.conf:81` is
   `MIGRATIONS=(058-nx-desk)`. `LEDGER_BEFORE` is the ledger live must show before the deploy (docs/release/1.6.4.conf:182,
   54 ids ending `058-nx-desk`).
2. **gen-deploy enforces the pair.** For each entry it requires `$f.sql` *and* `$f.rollback.sql`
   (tools/release/gen-deploy:30), reads the ledger id out of the forward file (`ledger_id()`,
   tools/release/lib.sh:21-23, which understands both `VALUES ('id')` and `SELECT 'id' FROM DUAL`), and requires the
   rollback to name that id: `grep -q "$LEDGER_COL *= *'$id'"` (tools/release/gen-deploy:32). `LEDGER_BEFORE=__auto__`
   computes every repo id not in `MIGRATIONS` and not in `LEDGER_NOT_LIVE` (tools/release/gen-deploy:35-44).
3. **Backup, then apply.** `deploy.sh` stops the unit (deploy.body.sh:187-192), dumps the DB with
   `mysqldump --lock-tables --routines --triggers` plus a `gzip -t` / `Dump completed` check
   (deploy.body.sh:195-196), tars the runtime and writes `ledger-before.txt` (deploy.body.sh:205), then touches
   `.stop-backup.done` (deploy.body.sh:207).
4. **The apply loop.** For each `MIGRATION_FILES` entry (deploy.body.sh:214-222): skip when
   `in_ledger "$id"` is 1 (:217); `touch "$B/.migration-$id.attempted"` **before** the SQL (:218); run the file and on
   failure `down` (:219); re-read the ledger and abort if the file did not record itself (:220). After the loop the
   whole ledger must equal `LEDGER_AFTER` (the sorted union of `LEDGER_BEFORE` and `MIGRATION_IDS`,
   deploy.body.sh:72-73, guard :224); the smoke phase re-checks it (:287). Re-apply is a no-op at two levels:
   `deploy.sh` skips any id already in the ledger (:217, "already in the ledger - skipped"), and the SQL itself is
   guarded so a direct `mysql < file` also changes nothing (the `@apply` gate or `IF NOT EXISTS`).
5. **The ledger read.** `ledger()` is one SELECT with `group_concat_max_len=1048576`
   (deploy.body.sh:69, rollback.body.sh:44) — a plain `GROUP_CONCAT` truncates at 1024 characters, which is what
   commit 704a6a4e0 fixed.
6. **Rollback picks a todo list.** Walking `MIGRATION_IDS` backwards (rollback.body.sh:57-69): a marker
   `.migration-$id.attempted` adds the index to `todo`; an id in the ledger *without* a marker is either
   in `LEDGER_BEFORE` ("already applied before this deploy - not rolled back", :65) or `die "… not guessing - check by
   hand or import $B/mapleroot.sql.gz"` (:66).
7. **Rollback runs, then verifies.** Each rollback SQL runs inside `if ! sql < …` (:88) and a failure prints "server
   stays DOWN" plus the import command (:89-92); the id must then be gone from the ledger (:94). The final ledger
   compare only **warns** (:98). Then the runtime is restored path by path with per-path markers (:100-131), the jar
   hash is verified (`sha256sum -c old-jar.sha256`, :134) and the unit starts (:142-152).
8. **Double rollback.** `rollback.sh` never deletes the `.migration-<id>.attempted` markers (`grep -n "rm " ` over
   rollback.body.sh finds only `rm -rf "$restore"` at :106), so a second run rebuilds the same `todo` list and re-executes
   the same rollback SQL, which is safe only because every rollback is idempotent (`DROP TABLE IF EXISTS`,
   `@applied` gates, `DELETE … WHERE migration_id=…`). The runtime restore is guarded per path by
   `.rollback-<path>.saved/restored` (:114-131), so it does not move the new files twice.

## Data

| Path | What it holds |
|---|---|
| `database/migrations/` | 123 entries: 61 forward `.sql`, 61 `.rollback.sql`, `map-columns.json`. Numbers 001-016, 020-032, 034-065; 017-019 were never used (docs/release/1.5.0.conf:13) and 033 never existed (docs/release/1.6.2.conf:118) |
| `mapleroot_integration_migrations` | the ledger: `migration_id varchar(100) PRIMARY KEY, applied_at timestamp` — created by 60 of the 61 forward files, e.g. database/migrations/001-preserve-arcane.sql:5-8 |
| `mapleroot_integration_migration_rows` | row journal `(migration_id, table_name, row_id)` so a rollback deletes only rows that script inserted; created by 013, 021, 024, 026, 027, 034, 042, 046, 064, 065 (database/migrations/065-lkc-quest-drops.sql:18-23) |
| per-migration backups | `stamps_039_accounts/coins`, `stamps_052_*`, `migration_025_removed_drop_data/reactordrops`, `family_041_fks`, `cards_043_*`, `bb_drops_050_new/rows`, `starter_chair_054_rows`, `boss_drops_055_new/rows`, `normal_gear_059_new/rows`, `royal_materials_060_*`, `normal_bridge_061_rows/targets`, `lkc_quest_drops_065_rows` |
| `database/migrations/map-columns.json` | `{table: map column}` for 7 tables (characters→map … trocklocations→mapid); nothing in the repo reads it — only docs/architecture/server/persistence.md:149 names it |
| `$D/backup/` on the host | `mapleroot.sql.gz` (mysqldump, table-locked), `server-runtime.tgz`, `config.yaml`, `slumsms.service`, `old-jar.sha256`, `ledger-before.txt`, markers `.stop-backup.done`, `.apply-start.started`, `.apply-start.done`, `.start-ts`, `.migration-<id>.attempted`, `.rollback-<path>.saved/restored` |
| receipts | `DEPLOY-RECEIPT.md` / `ROLLBACK-RECEIPT.md`; the rehearsal keeps `state-before/after*.tsv` and `rollback.log` (tools/release/rehearse-host.sh:222-229) |

`database/migrations/` files total 12,525 lines; `050-bb-drop-tables.sql` alone is 8,065 (8,011 `drop_data` rows for 350
mobs). Secrets are not involved beyond the DB password the `SQL_CMD` wrapper reads from the container environment.

## Switches

| Setting | Where | Default |
|---|---|---|
| `MIGRATIONS=(…)` | release conf | empty; stems in apply order (tools/release/templates/release.conf.example:5-7) |
| `LEDGER_BEFORE` | release conf | `__auto__` = every repo id not in `MIGRATIONS`/`LEDGER_NOT_LIVE` (gen-deploy:18, :35-44) |
| `LEDGER_NOT_LIVE=(…)` | release conf | empty; no committed conf sets it |
| `SLUMS_TEST_HOST` | tools/ops env | `192.168.0.111` (tools/ops/deploy-test:8) |
| `PHASE`, `DRY_RUN` | deploy/rollback args | `all`, `0` (deploy.body.sh:23-27) |
| `B`, `LOG`, `RECEIPT`, `SQL_CMD`, `ONLINE_TIMEOUT` | deploy/rollback env | `$D/backup`, `$D/deploy.log`, `$D/DEPLOY-RECEIPT.md`, `docker exec … mysql mapleroot`, `120` |
| `MIGRATE` | `deploy-test --no-migrations` | `1` (tools/ops/deploy-test:27, :34) |

## Invariants

- Every forward migration has a rollback and vice versa — no unpaired stem exists in `database/migrations/`; the
  generator refuses to build without the pair (tools/release/gen-deploy:30) and without the rollback's ledger delete
  (:32).
- Every migration is idempotent: all 61 forward files carry at least one of `CREATE TABLE IF NOT EXISTS`,
  `information_schema` guards, `SET @apply = NOT EXISTS(…)`, `INSERT IGNORE` or `INSERT … SELECT … WHERE NOT EXISTS`
  (AGENTS.md:117-118). 28 files use `information_schema`, 22 use the `@apply` gate.
- The marker precedes the SQL, so an interrupted migration is always visible to the rollback
  (deploy.body.sh:218; asserted by tools/release/test_release_recovery.py:273-276).
- The ledger is the only source of truth about what ran, and the deploy refuses to proceed on any mismatch with the
  conf: deploy.body.sh:151 (before), :224 (after apply), :287 (smoke).
- A migration in the ledger without an `.attempted` marker and outside `LEDGER_BEFORE` is never guessed at
  (rollback.body.sh:66).

**The 61 migrations.** All have a `.rollback.sql`; no pair is missing. "Rollback loses" marks a rollback that destroys
player state by design.

| # | Name | What it changes | Rollback loses |
|---|---|---|---|
| 001 | preserve-arcane | adds 83,910,000 to 7 map columns for 910000000-910000022; header "PREPARED ONLY", MyISAM so no transactional undo (001-preserve-arcane.sql:1-16) | — |
| 002 | classic-fm-return | `savedlocations` ENUM gains `CLASSIC_FREE_MARKET` | new bookmarks |
| 003 | solomapling-bots | new tables `botmemory`, `botroster`, `botstore` | bot inventory rows (characterid ≥ 1e9) + the tables |
| 004 | daily-checkin | `daily_checkin` | every check-in streak |
| 005 | bot-real-stats | botstore columns + `botskills` | — |
| 006 | bot-yield-caps | `botyield` | daily yield counters |
| 007 | bot-starter-stash | one botstore column | — |
| 008 | world-bera | world 0→1 across characters, storages, playnpc/plife, worldtransfers, in a transaction (:19-29) | Bera-era characters moved back |
| 009 | gm-audit | `gm_command_audit`, `gm_level_changes` | GM audit history |
| 010 | color-prism | `character_look_tints`, `equip_tints` (FK cascade) | tints |
| 011 | bot-affinity | `botaffinity`, `botdrama` | affinity + opt-outs |
| 012 | bot-couples | `botcouple` | couples |
| 013 | gachapon-machines | 6 `plife` NPC rows + row journal | — |
| 014 | bot-guilds | `botguild`, `botguildmember` | bot guilds |
| 015 | bot-romance | `botromance`, `botromanceopt` | romances |
| 016 | bot-guild-wars | `botguildwar` | wars |
| 020 | economy-trade | `pricesamples`, `economyday`, `botgifts` | samples/counters |
| 021 | cosmetics-merchant | `plife` Hanako 9209006 at 910000000 | — |
| 022 | botmemory-about-index | index `about` on botmemory | — |
| 023 | character-lookup-indexes | 10 secondary indexes (queststatus…drop_data); header cites InnoDB deadlock storms, "60 before, 0 after" (:1-12) | — |
| 024 | restore-stock-shop-items | `shopitems` rows for 3 empty + 9 incomplete shops | — |
| 025 | remove-dead-skill-books | copies then deletes 19 mastery-book `drop_data`/`reactordrops` rows | nothing it did not copy |
| 026 | dalair-henesys | `plife` Dalair 9000066 | — |
| 027 | nimble-free-market | `plife` Nimble 9977779 | — |
| 028 | bot-life-profile | `botlifeprofile` | profiles |
| 029 | bot-bonds | `botbond` | bonds |
| 030 | bot-memory-kinds | 4 botmemory columns | — |
| 031 | social-gate | `botsocialopt`, `botstartedit` | player choices |
| 032 | economy-counters | `economycounters` | counters |
| 034 | site-board-npc | `plife` 1092017 (FM entrance board) | — |
| 035 | trade-ratings | `traderatings` | ratings |
| 036 | invite-codes | `invite_codes` | codes |
| 037 | password-resets | `password_resets` | pending requests |
| 038 | nx-exchange | `exchange_escrow/orders/fills/ledger` | **players' escrowed NX/mesos** (header: cancel and collect first) |
| 039 | slums-stamps | `accounts.stamps`; converts vote points and Billion Coin 4001253 at 6:1, deletes the coins (:18-65) | converted coins (copies kept) |
| 040 | player-invites | columns on `invite_codes`/`accounts`, information_schema-guarded (:4, :9) | — |
| 041 | family-bot-juniors | drops two stock FKs so a bot can be a junior | — |
| 042 | slums-chan-npc | `plife` 9990000 (two maps) | — |
| 043 | achievements | `character_achievements` (no FK by design) | all achievement progress |
| 044 | exchange-tickers | ticker columns + 2 indexes on 038's tables; no-op without 038 | — |
| 045 | newer-area-cards | 254 card drops (2%) + `monstercarddata` rows via temp table `cards_043_pairs` (:21-22) | — |
| 046 | lazy-penguins | 15 `plife` NPCs 2985-2999 | — |
| 047 | mob-kills | `site_mob_kills` (hour, mob) | the site's kill counts |
| 048 | custom-npc-spots | `plife` coordinate UPDATEs | — |
| 049 | penguin-spots-2 | `plife` moves for 5 penguins | — |
| 050 | bb-drop-tables | 8,011 `drop_data` rows for 350 mobs; header "DRAFT … do not apply to TEST or live before the owner's go" (:1-2) | — |
| 051 | penguin-showa | moves penguin 2998 800000000→801000000 | — |
| 052 | stamps-gifts-mts | extends 039 into wedding gifts, Adventurer's Bag and `mts_items`/`mts_cart` (:1-22, :58-61) | converted items (copies kept) |
| 053 | family-buffs | `family_buffs` | running perks |
| 054 | starter-chair-repair | moves `area_info` 29151→29153, matched by characterid (:24-40) | — |
| 055 | boss-drop-gaps | 242 `drop_data` rows for 8 boss mobs (055-boss-drop-gaps.sql:5) | — |
| 056 | mob-kills-split | `site_mob_kills.estimated` | — |
| 057 | mob-kills-split-null | makes `estimated` nullable | — |
| 058 | nx-desk | `exchange_desk` (one JSON row: R) | R and the desk history |
| 059 | normal-gear-through-200 | 136 draft drop rows (header: "Pending economy review … Not auto-enabled", :1-6) | — |
| 060 | royal-crafting-materials | 5 first-forge material pairs ("release admission pending", :1-3) | — |
| 061 | normal-gear-bridge | 226 pairs / 113 items; "Apply with game/drop writers stopped" (:3), intent journal (:5) | — |
| 062 | bot-relationship-axes | 10 columns each on `botbond` and `botaffinity`, `HasAxes` guard (:23-37) | axis values |
| 063 | bot-memory-social | 6 botmemory columns | — |
| 064 | casino-chip-shop | `shops`+`shopitems` for NPC 9000055, journaled (:27-70) | — |
| 065 | lkc-quest-drops | quest-only drops, 2 Von Leon NPCs, Rose Garden golem, `plife` rows (:1-23) | — |

## Origin

| Path | First commit |
|---|---|
| `database/migrations/001-preserve-arcane.sql` | 03d13cb21 (this repo's baseline — the migration set predates the branch) |
| `tools/release/templates/rollback.body.sh`, `deploy.body.sh`, `gen-deploy`, `stage` | 9d437b372 (2026-10-20, "Script the release pipeline … ticket 44") |
| `tools/ops/deploy-test` | 383b0df55 |
| `tools/release/test_release_recovery.py` | 34baa5202 |
| `docs/release/1.6.4.conf` | e87e3d61d |
| `database/migrations/065-lkc-quest-drops.sql` | 069240c8a |
| `database/migrations/062-bot-relationship-axes.sql` | f03304844 (renumbered from 059 in a226edfdf) |
| `database/migrations/064-casino-chip-shop.sql` | fc1fb5da7 (trial renumber 059→064 in 618b9ce2a) |

## Tests and QA

| Layer | Path | Coverage |
|---|---|---|
| apply-twice / rollback-twice, real MySQL | 8 `*MigrationIT.java` in `src/test/java/database/` (BbDropTables, BossDropGaps, CasinoChipShop, FamilyBotJuniors, FamilyBuffs, NewerAreaCards, NormalGearBridge, StarterChairRepair; 759 lines) | gated by `Assumptions.assumeTrue(URL != null …)`, e.g. CasinoChipShopMigrationIT.java:44; loops at NormalGearBridgeMigrationIT.java:54/:60 and CasinoChipShopMigrationIT.java:49-51 |
| parse-only | 3 `*MigrationTest.java` (CharacterLookupIndexMigrationTest.java:19-24 asserts all 10 indexes are guarded, dropped and the ledger kept) + 2 `*DataTest.java` | file text only, no DB |
| release-script regression | `tools/release/test_release_recovery.py:205-276`, `tools/release/test_release_config.py:323-345` | rollback todo list, refuse-to-guess, marker-before-SQL, `ledger_id` on both INSERT forms |
| rehearsal | `rehearse-host.sh:107-118` catch-up, `:212-229` deploy then rollback then diff player rows | the only end-to-end proof the ledger and the rollback agree on a copy of live |
| manual | AGENTS.md:119 "Test apply-twice and rollback-twice on a throwaway MySQL 8.0 container" | recorded but not scripted: docs/release/1.5.0.conf:15, docs/release/1.5.2-deploy-steps.md:65, docs/release/1.6.4-gear-progression.md:107 |

**Not covered:** `mvn test` never runs the `*IT` classes — pom.xml:252-258 configures surefire only, and surefire's
default includes do not match `*MigrationIT`, so they run only when named explicitly. `tools/ops/deploy-test` has no
rollback step at all. No automation checks that the remaining ~50 migrations apply twice on a disposable container.

## Known issues

| Id | Issue |
|---|---|
| **b288** | `/root/work/STATUS-1.6.4.md:84` — "the rollback undoes every migration listed in the conf, even ones a previous release applied". **What the code does now:** the rollback no longer walks the conf's list blindly. It walks `MIGRATION_IDS` backwards and rolls back only ids whose `.migration-<id>.attempted` marker exists (`touch`ed by the deploy *before* the forward SQL, deploy.body.sh:218); an id in the ledger with no marker is skipped when it is in `LEDGER_BEFORE` and otherwise aborts with "not guessing" (rollback.body.sh:56-69). Fixed twice: the conf itself first (9493a47d8 dropped 1.6.2's migrations from 1.6.3's list, docs/release/1.6.3.conf:81), then the script (1bc04a574, plus tools/release/test_release_recovery.py:237-271). The residual risk is `LEDGER_BEFORE` itself: if a hand-edited list omits an id the live DB already has, the rollback refuses rather than proceeding. |
| NEW-FINDING | The rollback never removes the `.migration-<id>.attempted` markers (no `rm` in rollback.body.sh), so a second `rollback.sh` after a successful one re-runs the same rollback SQL. It is harmless only because every rollback is idempotent; nothing enforces that on a third-party edit. |
| NEW-FINDING | `rollback.body.sh:98` only warns when the ledger differs from `LEDGER_BEFORE` — the unit still starts and players log in. The deploy path `die`s on the same mismatch (deploy.body.sh:151, :224). |
| NEW-FINDING | `001-preserve-arcane.sql` writes ledger id `001-preserve-arcane-for-classic-fm`, not its file stem, and `tools/ops/deploy-test:108-110` builds the id as `basename "$m" .sql`. On TEST, 001 is therefore never seen as applied and is re-run every deploy (a no-op thanks to `@apply`, but the seven UPDATEs and the INSERT still execute). |
| NEW-FINDING | `062`/`063`/`023` issue `ALTER TABLE … ADD INDEX`/`ADD COLUMN` with no `ALGORITHM=INPLACE, LOCK=NONE`; `grep -l "ALGORITHM\|LOCK="` over `database/migrations/` returns nothing. Lock duration is unverified — see Open questions. |
| NEW-FINDING | 40 of the 61 headers say "stop the game first". The release path stops at deploy.body.sh:187-192 before applying at :212; `tools/ops/deploy-test` stops the unit at :94 and only then dumps and applies (:105-111), so the ordering holds there too — but it keeps no `.attempted` markers and has no rollback, so a failed migration on TEST leaves the schema half-applied with only `db-before.sql.gz` to restore. |
| 002 | `database/migrations/002-classic-fm-return.sql:1-3` is the one forward file without `CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations`, so it cannot run before 001 — already logged at docs/architecture/HOLES.md:308 and docs/architecture/server/persistence.md:306. |
| L18 | `/root/work/STATUS-1.6.4.md:109` — "One migration list, ticket numbers and one release conf", state `doing`. The trial renumber of the casino migration (059→064) is still marked trial-only in docs/release/1.6.4.conf:175-177. |

Destructive rollbacks to read before running: 003, 004, 009, 038, 043, 047, 058 (each header says what is lost).
Large-table lock risk: 050 (8,011 rows into MyISAM `drop_data`), 061 (226 pairs, header demands writers stopped), 052
(rewrites `inventoryitems`/`infinitystorage`/`mts_items` for every account), 039, 045 (254 mobs), 055, 025 (its delete
runs outside a transaction — "drop_data is MyISAM, so its delete is not transactional",
database/migrations/025-remove-dead-skill-books.sql:15).

## State

**Working.** 61/61 pairs are present and stem-matched; all 61 forward files are guarded and all 61 rollbacks delete their
own ledger row (verified by grepping each pair); `gen-deploy` refuses an unpaired or ledger-less entry; `deploy.sh`
guards the ledger at three points; `rollback.sh` refuses rather than guesses and is covered by two regression tests plus
one full rehearsal. Weak spots are TEST-only (`deploy-test` has no rollback and a stem-based ledger check) and
documentation-only (the throwaway-MySQL step in AGENTS.md:119 is manual).

## Open questions

- `docs/release/1.6.4-playbook.md:19` says the rehearsal's rollback "undoes only 059-065 (b288)", while
  `docs/release/1.6.4.conf:164-166` puts 058 in `LEDGER_BEFORE` and lists 059-065 in `MIGRATIONS`. Both can be true (the
  rollback undoes the seven it applied), but the playbook wording suggests the list, not the markers, is what decides.
  Not resolvable from the files alone.
- Whether 064-casino-chip-shop is permanently 064 or reverts to 059: docs/release/1.6.4.conf:175-177 calls the renumber
  "TRIAL ONLY" and defers to Server Dev; `/root/work/STATUS-1.6.4.md:7` names "migrations 059-064".
- Whether `ALTER TABLE` on `inventoryitems`/`drop_data` (023) or the 8,065-line `050` is safe with the game up: no
  `ALGORITHM=`/`LOCK=` clause and no measured lock time anywhere in the repo.
- `/root/work/STATUS-1.6.4.md:84`'s note is truncated in the file itself ("rel1 step P3b checks MIGRATIONS = the…"), so
  the acceptance criterion it refers to cannot be read; the commit 1bc04a574 message is the fullest account available.
