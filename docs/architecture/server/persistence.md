# Persistence

## Purpose

This is everything that turns live game state into MySQL rows and back: the single Hikari pool the whole server shares
(10 connections), the one transaction that saves a character, the hourly autosave, the shutdown save order, the bot
store that writes bots outside that transaction, and the numbered `.sql` migrations with the
`mapleroot_integration_migrations` ledger. Nothing here decides what a character *is*; it decides when state becomes
durable, what is lost when a write fails, and what a rollback of a release actually undoes.

## Entry points

| Entry | What it does | File:line |
|---|---|---|
| `DatabaseConnection.initializeConnectionPool()` | builds the Hikari pool + Jdbi handle factory; `false` aborts boot | src/main/java/tools/DatabaseConnection.java:235 |
| `Server` boot | throws `IllegalStateException("Failed to initiate a connection to the database")` when the pool fails | src/main/java/net/server/Server.java:940 |
| `DatabaseConnection.getConnection()` | the only way server code borrows a pooled connection | src/main/java/tools/DatabaseConnection.java:52 |
| `DatabaseConnection.newSnapshotConnection()` | pool-independent read connection for the roster/store snapshot | src/main/java/tools/DatabaseConnection.java:139 |
| `Character.saveCharToDB()` | async save (through the save service when `USE_AUTOSAVE`) | src/main/java/client/Character.java:9871 |
| `Character.saveCharToDB(boolean notAutosave)` | the synchronous, `synchronized`, single-transaction save | src/main/java/client/Character.java:9889 |
| `Character.saveOwnedAltToDB()` | the owned-alt save that fails closed | src/main/java/client/Character.java:752 |
| `Client.disconnectInternal` (finally block) | per-logout save: cooldowns, debuffs, character | src/main/java/client/Client.java:1130-1132 |
| `CharacterAutosaverTask.run()` | hourly sweep of everyone in the world | src/main/java/net/server/task/CharacterAutosaverTask.java:39 |
| `CharacterSaveService.registerSaveCharacter` | queues a character's save on the world scheduler | src/main/java/net/server/services/task/world/CharacterSaveService.java:40 |
| `Server.shutdownInternal` → `ShutdownSaves.run` | ordered shutdown save (players, then bots, under budgets) | src/main/java/net/server/Server.java:2127, :2142 |
| `PlayerStorage.disconnectAll()` | channel teardown: force-disconnect every character (which saves) | src/main/java/net/server/PlayerStorage.java:100 |
| `World.beginShutdown()` | stops the hourly autosave before the shutdown save runs | src/main/java/net/server/world/World.java:2192 |
| `BotStore.save/saveAll/settleAndSaveAll` | every bot write | src/main/java/soloMapling/ArtificialPlayer/BotStore.java:625, :1174, :1287 |
| `Storage.saveToDB(Connection)` | storage slice inside the character transaction | src/main/java/server/Storage.java:134 |
| `NoteDao.save` | the only Jdbi DAO write | src/main/java/database/note/NoteDao.java:14 |
| `database/migrations/*.sql` | schema + data migrations, one forward and one rollback each | database/migrations/ |

## How it works

1. **Boot.** `Server` sets the JVM default time zone first (src/main/java/net/server/Server.java:934-938 — the MySQL
   driver fixes each connection's zone when it opens), then calls `DatabaseConnection.initializeConnectionPool()`
   (src/main/java/net/server/Server.java:940). `initializeConnectionPool` returns early if a `dataSource` already
   exists (src/main/java/tools/DatabaseConnection.java:236), otherwise builds one `HikariDataSource` from
   `getConfig()` and calls `initializeJdbi` (src/main/java/tools/DatabaseConnection.java:235-258). On any exception it
   logs `"Failed to initialize database connection pool. Gave up after {} seconds."` and returns `false`
   (src/main/java/tools/DatabaseConnection.java:249-255), which aborts boot. Immediately after, `UPDATE accounts SET
   loggedin = 0` and `UPDATE characters SET HasMerchant = 0` clear the crash residue of the previous process
   (src/main/java/net/server/Server.java:960-961, :1070, :1076).

2. **Pool shape.** `MAX_POOL_SIZE = 10` (src/main/java/tools/DatabaseConnection.java:33). `getConfig()` sets the JDBC
   URL (`DB_HOST` env var overrides `YamlConfig`, :180-187), user, password, `setInitializationFailTimeout` from
   `INIT_CONNECTION_POOL_TIMEOUT`, `setConnectionTimeout(connectionTimeoutMs())` and
   `setMaximumPoolSize(MAX_POOL_SIZE)` (src/main/java/tools/DatabaseConnection.java:211-227), plus
   `cachePrepStmts=true`, `prepStmtCacheSize=25`, `prepStmtCacheSqlLimit=2048`. A configured timeout ≤ 0 falls back to
   `DEFAULT_CONNECTION_TIMEOUT_MS = 30_000`, and nothing below `MIN_CONNECTION_TIMEOUT_MS = 250` is accepted
   (src/main/java/tools/DatabaseConnection.java:190-208).

3. **Autosave tick.** `World` registers `CharacterAutosaverTask` once an hour (src/main/java/net/server/world/World.java:208).
   `run()` returns immediately when `YamlConfig.config.server.USE_AUTOSAVE` is off
   (src/main/java/net/server/task/CharacterAutosaverTask.java:43) and again when the world is already shutting down
   (:49). It walks `PlayerStorage.getAllCharacters()` (:53), re-checking `isShuttingDown()` per character (:56).
   Logged-in characters go through `chr.saveCharToDB(false)` on the timer thread (:62-63). Characters that are not
   logged in and pass `BotHelpers.isBot` are collected (:64-70) and written on a bot-db thread via `runBotDb`:
   `BotStore.saveAll(bots)`, `BotRoster.recordSessions(bots, now, false)`, `BotYieldCaps.flush()`
   (:73-81). Bots are never logged in, so they never reach the player save at all.

4. **The character transaction.** `saveCharToDB(boolean)` is `synchronized` (src/main/java/client/Character.java:9889),
   returns for roster bots (:9890) and for any character that is not logged in (:9891), and returns early while an owned
   alt is closing but this is not its final save. It then takes one connection (:9902), `setAutoCommit(false)`
   (:9903), `TRANSACTION_READ_UNCOMMITTED` (:9904) and issues, in this order, on that one connection: the big
   `UPDATE characters SET …` (:9907); `monsterbook.saveCards(con, accID)` for non-owned-alt (:9999); the three pets
   `pet.saveToDb()` (:10044); `petignores` delete+insert (:10048, :10053); `keymap` (:10064-10065);
   `quickslotkeymapped` `INSERT … ON DUPLICATE KEY UPDATE` (:10083); `skillmacros` (:10092-10093);
   `ItemFactory.INVENTORY.saveItems(itemsWithType, id, con)` (:10121); `REPLACE INTO skills` (:10128);
   `savedlocations` (:10141-10142); `trocklocations` for regular and VIP (:10155-10170); `buddies` (:10182-10183);
   `area_info` (:10197-10198); `eventstats` (:10210-10211); `queststatus` + `questprogress` + `medalmaps`
   (:10224-10228, skipping `isEmptyQuestPlaceholder`); `FamilyEntry.saveReputation` for the character and, when the
   relative is offline, its seniors (:10265-10283); `character_styles` delete+insert (:10286-10287);
   `cashshop.save(con)` for non-owned-alt (:10301); the storage slice (:10305-10311); `ColorPrismStore.saveLookTints`
   (:10313). Then `con.commit()` (:10315), `ownedAltSaveCommitted = true` for an owned alt (:10316). Any exception
   rolls back and rethrows (:10317-10319); `finally` restores `REPEATABLE_READ` and autocommit
   (:10320-10323).

5. **Storage inside the save.** `Storage.saveToDB(Connection)` is called from inside step 4 and is declared
   `throws SQLException` on purpose — its javadoc says "a failed statement must reach the caller so it rolls back"
   (src/main/java/server/Storage.java:133-134). It writes `UPDATE storages SET slots = ?, meso = ? WHERE storageid = ?`
   (:135) then `ItemFactory.STORAGE.saveItems(itemsWithType, id, con)` (:148). The caller only clears
   `usedStorage` after the call returns (src/main/java/client/Character.java:10305-10311), and the comment there says
   `usedStorage` stays `true` on failure so the next save retries the rows. `usedStorage` is set from
   `StorageProcessor` and `CashOperationHandler` (src/main/java/client/Character.java:173, :6104).

6. **The save cannot report success.** The outermost catch of `saveCharToDB(boolean)` logs `"Error saving chr {},
   level: {}, job: {}"` and returns (src/main/java/client/Character.java:10324-10328) — the comment says it must never
   throw because autosave and `!saveall` loop over every character, and `saveOwnedAltToDB` fails closed on its own. The
   method is `void`. The only caller that can observe failure is `saveOwnedAltToDB`, which throws
   `IllegalStateException("Alt save did not commit")` when `ownedAltSaveCommitted` is still false
   (src/main/java/client/Character.java:752-760).

7. **Async path.** `saveCharToDB()` (no argument) checks `USE_AUTOSAVE` and, when on, hands the work to
   `CharacterSaveService` (`WorldServices.SAVE_CHARACTER`) as a `Runnable` that calls `saveCharToDB(true)`
   (src/main/java/client/Character.java:9871-9885). The service is a base-untouched scheduler
   (src/main/java/net/server/services/task/world/CharacterSaveService.java:40-50). `!saveall` uses this path for every
   character in every world and then prints "All players saved successfully." unconditionally
   (src/main/java/client/command/commands/gm6/SaveAllCommand.java:40-48).

8. **Logout save.** `Client.disconnectInternal` runs its `finally` block over three separate steps: `saveCooldowns()`,
   `cancelAllDebuffs()`, `saveCharToDB(true)`, `logOff()`, then pending name change and `clear()`
   (src/main/java/client/Client.java:1130-1132). `saveCooldowns()` is `synchronized`, opens **its own** connection
   (skipping the body entirely when the cooldown list is empty) and runs `DELETE FROM cooldowns WHERE charid = ?` then
   `INSERT INTO cooldowns (charid, SkillID, StartTime, length)` — outside the character transaction
   (src/main/java/client/Character.java:9625-9643) — followed by the same pattern for `playerdiseases` (:9645-9670);
   both catch `SQLException` and only `printStackTrace`. Characters with `isOwnedAlt()` return from
   `disconnectInternal` before any of this (src/main/java/client/Client.java:1062), so the owned-alt registry owns
   their one final save.

9. **Shutdown save.** `shutdownInternal` first calls `w.beginShutdown()` for every world
   (src/main/java/net/server/Server.java:2134-2136), which cancels the hourly autosave
   (src/main/java/net/server/world/World.java:2192-2194), so autosave and the shutdown save never write the same
   character concurrently. Then `ShutdownSaves.run(Steps)` (:2142) runs the player phase and only afterwards the bot
   phase. The player phase snapshots `connectedClients` and calls `Client::forceDisconnect` on 4 threads under a 60 s
   budget (:2144-2150), logs per-failure `log.error` and per-timeout `log.warn` (:2155, :2162), then saves owned alts
   still out through `OwnedAltService.outForShutdown()` in the same budget (:2165-2177). Each `forceDisconnect` is the
   path in step 8, so a player's final write is one character transaction plus the separate cooldown/disease writes. The
   bot phase is gated on `MapleRootSolo.isEnabled()` (:2181). Only after both does `shutdownBots` finish
   (`BotShutdown.begin()`, `BotTickService.stop()`, then `BotStore.settleAndSaveAll(everyone, leftMs, BOT_WRITERS)` per
   world, `BotRoster.recordSessions`, `BotYieldCaps::flush`, `BotBonds::flushAtShutdown`, :2084-2124) and each
   `World.shutdown()` runs (:2188). At the very end `DatabaseConnection.closeSnapshotConnections()` releases the
   pool-independent snapshot connections (:2229) and, in the non-restart branch, the process exits; the restart branch re-`init()`s (:2243-2253).

10. **Shutdown budgets.** `ShutdownSaves.PLAYER_BUDGET_MS` and `BOT_BUDGET_MS` are 60 s each, overridable with
    `-Dslums.shutdown.playerBudgetMs` / `-Dslums.shutdown.botBudgetMs` (src/main/java/soloMapling/server/ShutdownSaves.java:29,
    :32); `PLAYER_SAVERS = 4`, `BOT_WRITERS = 4`, `BOT_GRACE_MS = 5_000` (:35, :38, :44). `forEachWithin` spawns
    daemon threads, enforces a `System.nanoTime` deadline and returns succeeded/failed/timedOut (:105). The class
    javadoc records the 1.5.0 rehearsal failure that produced this order: the bot sweep waited on the pool per bot, ran
    past systemd's five minutes and the real players' final save came after it (:19). A bot phase that misses its
    budget is abandoned with `"Shutdown: the bot phase was still running after {} ms (budget {} ms); going on without
    it"` (:77).

11. **Bots are not characters.** `BotStore`'s javadoc fixes the save points: first creation, bot removal
    (`saveThenSettle`), and the periodic/shutdown sweeps (`saveAll`, `settleAndSaveAll`) — "Nothing else writes a bot"
    (src/main/java/soloMapling/ArtificialPlayer/BotStore.java:63-64). `save(Character bot)` wraps one bot in
    `writeTransaction` (a bot-only transaction with rollback and a deadlock retry) (:625, :708-727); `saveAll` skips
    characters that are not stored bots (:1185) and logs `"Bot {} ({}) changed while its row was being written, so its
    deltas are kept for the next write"` (:1203); the budgeted sweep returns a `SweepReport(saved, failed, unsaved,
    elapsedMs, journaled)` (:1287) and unsaved bots get their payments into a debit journal
    (`BotHoldings` → `BotStore.recordDebit`, :1088). Bot traffic is capped to a share of the pool:
    `PERMITS = max(CHARACTER_LOAD, MAX_POOL_SIZE - PLAYER_RESERVE)` = 6 of 10
    (src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:81-103), and a refused bot write throws
    `BotDbGate.RefusedException` ("The bots' share of the database pool (6 connections, …)"),
    which extends `SQLException` (:276-281). Shutdown work bypasses the cap through `BotDbGate.unmetered`
    (:148-161).

12. **Migrations and the ledger.** `database/migrations/` holds 61 forward `.sql` files and 61 matching
    `.rollback.sql` files (122 `.sql`, plus `map-columns.json`); the newest are `059-normal-gear-through-200`,
    `060-royal-crafting-materials`, `061-normal-gear-bridge`, `062-bot-relationship-axes`, `063-bot-memory-social`,
    `064-casino-chip-shop`, `065-lkc-quest-drops`. 60 of the 61 forward files begin with `CREATE TABLE IF NOT EXISTS
    mapleroot_integration_migrations (migration_id varchar(100) PRIMARY KEY, applied_at timestamp NOT NULL DEFAULT
    CURRENT_TIMESTAMP) ENGINE=InnoDB` (database/migrations/001-preserve-arcane.sql:5-8); `002-classic-fm-return.sql`
    is the exception, a bare `ALTER TABLE savedlocations` that assumes the table exists
    (database/migrations/002-classic-fm-return.sql:2). 22 files then do the full row gate — `SET @apply = NOT EXISTS
    (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='…')` (:9) and every DML `WHERE @apply` — while
    the other 39 (including 001, whose `@apply` guards only the `UPDATE`s) rely on `CREATE TABLE IF NOT EXISTS`,
    `INSERT IGNORE` and `INSERT … SELECT … WHERE NOT EXISTS`. Every file ends with `INSERT IGNORE INTO
    mapleroot_integration_migrations(migration_id) VALUES ('…')` (:17). The rollback mirrors it: `@apply = EXISTS (...)`
    (database/migrations/001-preserve-arcane.rollback.sql:11) where the forward used the gate, reverse DML otherwise, then
    `DELETE FROM mapleroot_integration_migrations WHERE migration_id=…` (:19). `065-lkc-quest-drops.sql` adds a second
    bookkeeping table,
    `mapleroot_integration_migration_rows(migration_id, table_name, row_id)`, so its rollback deletes only rows that
    script inserted (database/migrations/065-lkc-quest-drops.sql:18-23); `064` uses the same table
    (database/migrations/064-casino-chip-shop.sql:27, :48). The header of `001` states the operating rule: "PREPARED ONLY:
    never run against a live server with players connected … MyISAM tables are present: SQL transactions cannot roll
    back all these tables … restore the database backup before retrying" (database/migrations/001-preserve-arcane.sql:1-4).

13. **Release wiring.** `tools/release/gen-deploy` builds the deploy from the conf's `MIGRATIONS` array and dies if a
    forward file, a rollback file, or the rollback's ledger delete is missing (tools/release/gen-deploy:28-35). The
    deploy body skips ids already in the ledger, touches `.migration-$id.attempted` **before** running the forward SQL
    and dies if the migration did not record itself (tools/release/templates/deploy.body.sh:217-220). The rollback body
    builds its todo list only from ids with an `.attempted` marker; an id in the ledger without a marker is left alone
    when it is in `LEDGER_BEFORE`, otherwise the rollback refuses: `"not guessing - check by hand or import
    $B/mapleroot.sql.gz"` (tools/release/templates/rollback.body.sh:56-69, :88-98).

## Data

Writer per table (only real writers, not readers):

| Table | Writer | When |
|---|---|---|
| `characters` | `Character.saveCharToDB(boolean)` | hourly autosave, logout, shutdown, `!saveall` |
| `characters` (`guildid`,`guildrank`,`allianceRank`) | `Character.saveGuildStatus()` src/main/java/client/Character.java:9672 | guild/alliance change (own connection, swallow) |
| `characters` (`HasMerchant`) | `Character.setHasMerchant`, `Server.setAllMerchantsInactive`, `HiredMerchant` | merchant open/close, boot |
| `characters` (`MerchantMesos`) | `Character.addMerchantMesos` / `setMerchantMeso` src/main/java/client/Character.java:10524, :10539 | merchant sale/withdraw |
| `accounts` (`loggedin`,`lastlogin`,`ip`,`pin`,`pic`,`hwid`,`macs`,`tos`,`votepoints`,`characterslots`,`gender`,`rewardpoints`,`language`,`autologin`,`nxCredit`,`maplePoint`,`nxPrepaid`) | `Client`, `Character`, `Server`, `MTSHandler`, `CashShop.save` | login/logout, boot, setting changes, cash shop save |
| `inventoryitems`/`inventoryequipment` | `ItemFactory.INVENTORY.saveItems` (delete-then-insert) src/main/java/client/inventory/ItemFactory.java:199 | character save |
| `storages` (+`inventoryitems` type 2) | `Storage.saveToDB(Connection)` src/main/java/server/Storage.java:134 | character save when `usedStorage` |
| `pets` | `Pet.saveToDb()` / `createPet` src/main/java/client/inventory/Pet.java:133, :154 | character save; pet creation |
| `monsterbook` | `MonsterBook.saveCards` src/main/java/client/MonsterBook.java:215 | character save (not owned alts) |
| `skills`, `skillmacros`, `keymap`, `quickslotkeymapped`, `savedlocations`, `trocklocations`, `buddies`, `area_info`, `eventstats`, `queststatus`, `questprogress`, `medalmaps`, `character_styles`, `petignores` | `Character.saveCharToDB(boolean)` | character save |
| `family_character` | `FamilyEntry.saveReputation` (from `saveCharToDB`) | character save |
| `wishlists` (+ cash inventory) | `CashShop.save(Connection)` src/main/java/server/CashShop.java:511 | character save |
| `cooldowns`, `playerdiseases` | `Character.saveCooldowns()` src/main/java/client/Character.java:9625 | logout (own connection, outside the transaction) |
| `guilds` | `Guild.writeToDB(boolean)` src/main/java/net/server/guild/Guild.java:156 | guild edit / disband |
| `alliance` | `Alliance.saveToDB()` src/main/java/net/server/guild/Alliance.java:229 | alliance operation |
| `rings` | `Ring` src/main/java/client/Ring.java:77, :119 | ring create/delete |
| `fredstorage` (+ merchant `inventoryitems` type 6) | `HiredMerchant.saveItems()` → `FredrickProcessor.insertFredrickLog` src/main/java/server/maps/HiredMerchant.java:761-781 | merchant close |
| `dueypackages` (+ `inventoryitems` type 9) | `DueyProcessor` src/main/java/client/processor/npc/DueyProcessor.java:152-186, :613 | package send/claim/expiry |
| `botstore`, `botskills` | `BotStore.save/saveAll/settleAndSaveAll` | bot create, bot removal, hourly sweep, shutdown |
| `botmemory`, `botaffinity`, `botbonds`, `botguild`, `botguildmember`, `botroster` | `BotMemory`, `BotAffinity*`, `BotGuildSystem.GuildStore`, `BotRoster` | bot lifecycle + sweep; bonds also on a 3-minute flush |
| `economycounters` | `EconomyCounterPersistence` src/main/java/soloMapling/Economy/stats/EconomyCounterPersistence.java:104 | periodic + `EconomySnapshotService.shutdown()` |
| `traderatings` | `TradeRatings.writeDb` src/main/java/soloMapling/site/TradeRatings.java:175 | periodic + shutdown |
| `character_achievements` | `JdbcAchievementStore.saveProgress` src/main/java/soloMapling/achievements/JdbcAchievementStore.java:49 | on achievement progress (synchronous) |
| `infinitystorage` | `AdventurerBag` src/main/java/client/inventory/AdventurerBag.java:417-426 | bag use |
| `notes` | `NoteDao.save` src/main/java/database/note/NoteDao.java:14 | note sent |
| `mapleroot_integration_migrations`, `mapleroot_integration_migration_rows` | the migrations themselves | deploy / rollback |

Not persisted anywhere (in-memory only, zero `getConnection`/`prepareStatement` in the class): `Party`,
`Messenger`, `MiniGame`.

## Switches

| Switch | Where | Default | Live / TEST |
|---|---|---|---|
| `USE_AUTOSAVE` | src/main/java/config/ServerConfig.java:327 (`public boolean USE_AUTOSAVE;` — no code default, the value comes from `config.yaml`); config.example.yaml:232 | example says `true` | live and TEST unknown: `config.yaml` was not read, and docs/release/1.6.4.conf does not mention it, so the released jar carries whatever the file says. Off means the hourly character sweep and the async save service do nothing (src/main/java/net/server/task/CharacterAutosaverTask.java:43, src/main/java/client/Character.java:9873) |
| `SHUTDOWNHOOK` | src/main/java/config/ServerConfig.java:95 (no code default); config.example.yaml:105 | example says `true` | unknown for both; when on, `Server` installs a JVM shutdown hook that runs `shutdownInternal` (src/main/java/net/server/Server.java:930-933) |
| `DB_CONNECTION_TIMEOUT_MS` | src/main/java/config/ServerConfig.java:18 | `30000` | live: left at the code default (docs/release/1.6.4.conf:149-151 notes `=10000` "makes a wedged pool" fail loudly); TEST unknown |
| `INIT_CONNECTION_POOL_TIMEOUT` | src/main/java/config/ServerConfig.java:13; config.example.yaml:53 | `90` s in the example | unknown; feeds `setInitializationFailTimeout` (src/main/java/tools/DatabaseConnection.java:219-220) |
| `DB_HOST` / `DB_USER` / `DB_PASS` / `DB_URL_FORMAT` | src/main/java/config/ServerConfig.java:9-12 | example: `jdbc:mysql://%s:3306/slumsms`, `127.0.0.1`, `slumsms` | `DB_HOST` env var overrides the yaml (src/main/java/tools/DatabaseConnection.java:180-187); values not inspected |
| `-Dslums.shutdown.playerBudgetMs` | src/main/java/soloMapling/server/ShutdownSaves.java:29 | `60000` | not set in the 1.6.4 conf → default |
| `-Dslums.shutdown.botBudgetMs` | src/main/java/soloMapling/server/ShutdownSaves.java:32 | `60000` | same |
| `-Dslums.db.snapshotConnectTimeoutMs` | src/main/java/tools/DatabaseConnection.java:46 | `10000` | unset → default |
| `-Dslums.db.snapshotSocketTimeoutMs` | src/main/java/tools/DatabaseConnection.java:48 | `30000` | unset → default |
| `-Dsolomapling.enabled` (+ `template-id`) | src/main/java/soloMapling/server/MapleRootSolo.java:17 | off | gates the whole bot write path and the shutdown bot phase (src/main/java/net/server/Server.java:2181) |
| `MIGRATIONS` / `LEDGER_BEFORE` | docs/release/1.6.4.conf:166, :182 | 059…065 for 1.6.4; ledger through `058-nx-desk` | release-tooling only, not read by the server |

## Invariants

- A character is saved in exactly one transaction on one connection; every character-scoped table in step 4 rides that
  commit (src/main/java/client/Character.java:9902-10319).
- A failed storage write aborts the whole character save. `Storage.saveToDB` declares `throws SQLException`, the caller
  does not clear `usedStorage` on failure, and the next save retries the storage rows
  (src/main/java/server/Storage.java:133-134; src/main/java/client/Character.java:10305-10311).
- A save never propagates an exception to its caller; callers must treat "returned" as "attempted"
  (src/main/java/client/Character.java:10324-10328).
- Bots are never written through `saveCharToDB` — `saveCharToDB` returns for roster bots
  (src/main/java/client/Character.java:9890) and all bot state goes through `BotStore`.
- Bot writes hold at most `PERMITS` = 6 of the 10 pool connections; shutdown and reconciliation use `unmetered`
  (src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:92, :148).
- The hourly autosave cannot run during a shutdown save: `beginShutdown` cancels the schedule before
  `ShutdownSaves.run` starts (src/main/java/net/server/world/World.java:2192-2194;
  src/main/java/net/server/Server.java:2134-2142).
- The player phase of a shutdown completes (or times out) before any bot work begins
  (src/main/java/soloMapling/server/ShutdownSaves.java:59-77).
- Every migration records itself in the ledger as its last statement, so a partial run leaves no ledger entry and the
  deploy/rollback tools stop on it instead of continuing
  (database/migrations/001-preserve-arcane.sql:17; tools/release/templates/deploy.body.sh:220).
- A migration is idempotent by one of two idioms: **row-gated** (22 of the 61 forward files put
  `SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='…')` up front and write
  gates its DML on `WHERE @apply`, database/migrations/001-preserve-arcane.sql:9-16) or **statement-guarded** (the
  other 39 use `CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE` / `INSERT … SELECT … WHERE NOT EXISTS`, as in
  database/migrations/059-normal-gear-through-200.sql:162-165 and
  database/migrations/060-royal-crafting-materials.sql:27-30). Only the row-gated files skip their DML entirely on a
  re-run; the statement-guarded ones re-execute and rely on each statement to be a no-op.
- Every forward migration has a `.rollback.sql` that deletes the same ledger row; the deploy generator refuses to build
  without it (tools/release/gen-deploy:28-35).

## Origin

| Piece | Class | Evidence |
|---|---|---|
| `src/main/java/database/` | modified (shrunk) | base has `DatabaseMigrations.java` (Liquibase, `db/changelog-root.xml`); SlumsMS has only `DaoException.java` + `note/`, and no reference to `DatabaseMigrations` survives. `DaoException.java` is byte-identical to base. |
| `DatabaseConnection.java` | modified | differs from `/root/workspace/cosmicms/src/main/java/tools/DatabaseConnection.java` (base sets `setMaximumPoolSize(10)` at :65 and has no snapshot connections, no timeout helpers); oldest commit `03d13cb21` (2026-10-18 baseline), then `4d125f57e`, `a3d8a6ebf` (snapshot exporter). |
| `Character.java` | modified | oldest `03d13cb21`, 88 commits; save path touched by `1916a37f9`/`4d7e78747` (storage), `4b845fe5f`. |
| `Storage.java` | modified | `03d13cb21` + 5 commits including `1916a37f9` "Storage saves fail loudly so a failed save no longer wipes storage"; base:132 has the swallowing `saveToDB`. |
| `CharacterAutosaverTask.java` | modified | base file exists; `1512c08a6` (SoloMapling fork merge), `f226794cd`; the shutdown guard is a 1.5.2 port of `nutnnut/Cosmic e61767a42`. |
| `CharacterSaveService.java`, `DaoException.java` | base, unmodified | byte-identical to the cosmicms tree. |
| `BotStore.java`, `BotDbGate.java`, `ShutdownSaves.java` | custom | no base counterpart; `ShutdownSaves.java` has only 2 commits (`4d08a0cba`, `32806f0a3`). |
| `database/migrations/` | custom | base `database/` contains only `docker-db-data` — zero migration files. 61 forward + 61 rollback, 76 commits, starting `1512c08a6`. |
| Release tooling (`tools/release/*`, `docs/release/*.conf`) | custom | not in base. |
| `tools/cosmicdiff/classifications.json` | — | the only persistence-adjacent entry is `DELIBERATE` for `StorageProcessor.storageAction/2` ("Wrapper for MapleRoot's ore storage; the Cosmic body is defaultStorageAction"); no storage/save method is classified `BROKEN` or `UNSURE`. |

## Tests and QA

| Test | Covers | File:line |
|---|---|---|
| `StorageSaveFailureRollbackTest` | a failing storage insert throws and the inventory delete rolls back (the b294 fail-before test) | src/test/java/server/StorageSaveFailureRollbackTest.java:39 |
| `StorageMissingSlotTest` | empty storage has no slot; a slot past the items is no slot | src/test/java/server/StorageMissingSlotTest.java:26, :33 |
| `ShutdownSavesTest` | players saved before bot work; bot phase overrun does not hold the shutdown; a hanging player save is reported | src/test/java/soloMapling/server/ShutdownSavesTest.java:24, :50, :78, :102 |
| `AutosaveDuringShutdownTest` | a shutting-down world is not autosaved; a running world still is | src/test/java/net/server/task/AutosaveDuringShutdownTest.java:56, :65 |
| `PlayerStorageDisconnectAllTest` | one throwing disconnect does not strand the rest | src/test/java/net/server/PlayerStorageDisconnectAllTest.java:23 |
| `BotShutdownTest` | the bot shutdown gate closes once and runs every hook exactly once; a late hook runs immediately | src/test/java/soloMapling/server/BotShutdownTest.java:29, :48 |
| Migration tests | `LkcQuestDropsMigrationTest`, `CasinoChipShopMigrationTest`, `CharacterLookupIndexMigrationTest` parse the SQL only; the `*MigrationIT` classes (`CasinoChipShop`, `FamilyBuffs`, `FamilyBotJuniors`, `NormalGearBridge`, `NewerAreaCards`, `BossDropGaps`, `BbDropTables`, `StarterChairRepair`) run against a scratch schema only when a JDBC URL is supplied | src/test/java/database/ |

Auto-QA: `t-vb-7` "Storage, Duey and a trade between two players" exercises the storage store/take-out and Duey claim
paths that end in these saves (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:825-831); `t-oa-1` covers an owned
alt being saved on dismiss (:1231); `t-ng-10` is the only migration row in
src/main/resources/soloMapling/autoqa-coverage.tsv:223 (`061-normal-gear-bridge`). `!qa` scenario `np-3` closes a
merchant "in place of the restart" (src/main/java/soloMapling/qa/QaScenarios.java:688). Bot life profile round trip is
src/main/java/soloMapling/qa/BotLifeScenarios.java:173, which says itself that it proves the database round trip "on this server, not a full restart".

Explicitly **not** covered: no test restarts the process or asserts a full save→reload cycle of a `Character`; no test
covers `Guild.writeToDB`, `Alliance.saveToDB`, `saveCooldowns`, `playerdiseases`, `Pet.saveToDb`,
`HiredMerchant.saveItems` + `insertFredrickLog` atomicity, `DueyProcessor` delete variants, or the shutdown of a real
pool. No test asserts that `!saveall` did anything.

## Known issues

| Id | Issue | Evidence |
|---|---|---|
| **b294** | `Storage.saveToDB` swallows `SQLException` inside the caller's transaction, so inventory is deleted and storage never rewritten — silent storage data loss. | Card: /root/work/STATUS-1.6.4.md:44, docs/tickets/182-server-code-audit-1-6-4.md:55. Base code shows the defect: `/root/workspace/cosmicms/src/main/java/server/Storage.java:132` catches and prints. **Fixed on this branch**: `public void saveToDB(Connection con) throws SQLException` with no catch (src/main/java/server/Storage.java:133-134), guarded by the b294 fail-before test (src/test/java/server/StorageSaveFailureRollbackTest.java:39). The card is still open in STATUS because the fix has not been through a release; the remote client path is not exercised by that unit test. |
| **b288** | The release rollback undoes every migration listed in the conf, including ones a previous release applied. | Card: /root/work/STATUS-1.6.4.md:84. The conf now lists only 1.6.4's own set (docs/release/1.6.4.conf:166) and the rollback body filters by `.attempted` markers plus `LEDGER_BEFORE` (tools/release/templates/rollback.body.sh:56-69). Rehearsal recorded as PASS, "rollback undoes only 059-065" (docs/release/1.6.4-playbook.md:19). Residual risk is the `LEDGER_BEFORE` list itself: a bad entry there silently protects a migration that should have been rolled back (:65) or aborts the rollback (:66). |
| `NEW-FINDING` | `Guild.writeToDB` catches `SQLException` and only prints, so a failed guild save is invisible to the caller — the same swallow b294 fixed on the storage side. | src/main/java/net/server/guild/Guild.java:198 `catch (SQLException se) { se.printStackTrace(); }` (base has it too). |
| `NEW-FINDING` | `Character.saveCharToDB(boolean)` returns `void` and swallows every error, so `!saveall` reports "All players saved successfully." without proof. | src/main/java/client/Character.java:10324-10328; src/main/java/client/command/commands/gm6/SaveAllCommand.java:40-48. |
| `NEW-FINDING` | The logout writes are split across connections: `saveCooldowns` (cooldowns + playerdiseases) and `Pet.saveToDb` each take their own connection and swallow errors, so a successful character commit does not imply the cooldowns/pets were written. | src/main/java/client/Character.java:9625-9670; src/main/java/client/inventory/Pet.java:133-148. |
| `NEW-FINDING` | `002-classic-fm-return.sql` is the one forward migration that does not create the ledger table, so it cannot be applied to a database where 001 has not run (or the ledger was rebuilt): its `INSERT IGNORE` fails and the deploy tool then stops on "did not record itself in the ledger". | database/migrations/002-classic-fm-return.sql:2-3 vs. the `CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations` in every other forward file; tools/release/templates/deploy.body.sh:220. |
| `NEW-FINDING` | `PlayerStorage.disconnectAll` logs a throwing disconnect and then still clears the registry, so a character whose teardown failed leaves the world with no record that it was not saved — nothing retries it. | src/main/java/net/server/PlayerStorage.java:113-131. |
| `NEW-FINDING` | Merchant close is two non-atomic writes on two connections: the items, then `fredstorage`. A crash between them leaves items saved with no Fredrick log, or vice versa. | src/main/java/server/maps/HiredMerchant.java:761-781. |

## State

**working** for the paths with evidence: the pool builds and aborts boot on failure (src/main/java/net/server/Server.java:940),
the character save commits one transaction with rollback evidenced by
src/test/java/server/StorageSaveFailureRollbackTest.java:39, the autosave/shutdown ordering is unit-tested
(src/test/java/soloMapling/server/ShutdownSavesTest.java:24-102; src/test/java/net/server/task/AutosaveDuringShutdownTest.java:56-65),
and the migration/rollback tooling was rehearsed end to end (docs/release/1.6.4-playbook.md:19).
**partial** for the swallow-class writers listed as NEW-FINDING (guild, cooldowns, diseases, pets, merchant close) — they
write but cannot report failure. **unknown** for live and TEST runtime behaviour: the live `config.yaml` and TEST's
`start-test.sh` were not read, and nothing here restarts a real server.

## Open questions

- What `USE_AUTOSAVE`, `SHUTDOWNHOOK` and `DB_CONNECTION_TIMEOUT_MS` actually are on live and on TEST. The 1.6.4 conf only
  states live leaves the timeout at the code default; `config.yaml` was out of scope and TEST's start script is on
  another host.
- Whether the b294 fix (`1916a37f9`) has ever run against a real MySQL under load. Only the in-memory
  `FakeTransaction` test covers it; the card is still listed open in STATUS.
- Whether any of the swallow sites in the NEW-FINDING rows has a real player-visible incident. Nothing in STATUS, the
  tickets, or the PQ report mentions one; they were found by reading code.
- Whether `!saveall`'s async path can still be running when the shutdown save starts. `beginShutdown` cancels the hourly
  task but the save service's queued entries are not drained by it, and no test covers that overlap.
- Whether the 10-connection pool is adequate at the configured bot population (peak 3500 per
  docs/release/1.6.4.conf:201+). `BotDbGate` caps the bots at 6, leaving 4 for players and everything else; no soak
  evidence for that ratio was found in the repo.
