-- SoloMapling real bots (ticket 09): bots are given a one-time starter supply of potions and ammo on the 1.3.0
-- upgrade. Before ticket 08 bots used supplies they never held; stored bots hold none, so each one (and each newly
-- minted bot) is given what a player of its level carries, once. Adds one nullable column to botstore; no existing
-- row or value is changed. Safe to apply with the game stopped (take a fresh backup first, as for every migration
-- here). Applying it twice changes nothing.
--
-- StockedAt: NULL until the bot has been given its starter supplies, then the epoch-ms it was. The server sets it in the
-- same transaction that writes those items, so a bot is stocked exactly once. Without this migration the server runs
-- as before and gives no starter supplies (nothing could record them, so they would be given every boot).
-- To give every bot its stash again (for example after restoring items from a backup), set StockedAt back to NULL.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- MySQL has no ADD COLUMN IF NOT EXISTS, so the column is added only when it is missing.
SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botstore' AND COLUMN_NAME = 'StockedAt');
SET @ddl = IF(@missing, 'ALTER TABLE botstore ADD COLUMN StockedAt BIGINT NULL', 'DO 0');
PREPARE add_stocked FROM @ddl;
EXECUTE add_stocked;
DEALLOCATE PREPARE add_stocked;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('007-bot-starter-stash');
