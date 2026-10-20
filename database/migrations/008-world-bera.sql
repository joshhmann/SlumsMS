-- SlumsMS 1.3.1, ticket 41: the live world becomes Bera (world id 1) instead of Scania (0).
-- Moves every row keyed by world from 0 to 1. Goes live together with the 1.3.1 jar and the config.yaml
-- `worlds:` entry `id: 1`; the old jar cannot read that key, and with this data but world 0 configured every
-- character would be in a world the server does not run.
--
-- Tables keyed by world (information_schema on a copy of live, 2026-10-20): characters, storages, playernpcs,
-- playernpcs_field, plife, worldtransfers (`from`, `to`). Everything else follows its character or account id
-- (items, skills, quests, buddies, guild ranks, merchants, Duey, notes, cash shop). Bots have no characters row:
-- they take the world the server runs (BotClient), and their items are keyed by character id.
--
-- Stop the game and take a backup with table locks first. Applying it twice changes nothing (ledger gate). A storage
-- row that would collide with one the account already has in world 1 is left in world 0 (none on live).
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='008-world-bera');

START TRANSACTION;
UPDATE characters SET world = 1 WHERE @apply AND world = 0;
UPDATE storages s LEFT JOIN storages t ON t.accountid = s.accountid AND t.world = 1
  SET s.world = 1 WHERE @apply AND s.world = 0 AND t.storageid IS NULL;
UPDATE playernpcs SET world = 1 WHERE @apply AND world = 0;
UPDATE playernpcs_field SET world = 1 WHERE @apply AND world = 0;
UPDATE plife SET world = 1 WHERE @apply AND world = 0;
UPDATE worldtransfers SET `from` = 1 WHERE @apply AND `from` = 0;
UPDATE worldtransfers SET `to` = 1 WHERE @apply AND `to` = 0;
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('008-world-bera');
COMMIT;
