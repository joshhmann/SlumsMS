-- SlumsMS 1.5.0 rc6 bug hunt: an index on botmemory leading with AboutCharacterId. "@botdrama off" purges the gossip
-- about a player with DELETE FROM botmemory WHERE AboutCharacterId = ? ..., and the only key, bot_about (BotId,
-- AboutCharacterId), can't serve that, so each purge scanned the whole table. Adds one secondary index; no row or
-- column changes, so an older jar runs fine on a migrated database. Stop the game and take a backup first, as for every
-- migration here. Applying it twice changes nothing (ledger id '022-botmemory-about-index').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- MySQL has no ADD INDEX IF NOT EXISTS, so the index is added only when it is missing.
SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND INDEX_NAME = 'about');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD INDEX about (AboutCharacterId, Source)', 'DO 0');
PREPARE add_about FROM @ddl;
EXECUTE add_about;
DEALLOCATE PREPARE add_about;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('022-botmemory-about-index');
