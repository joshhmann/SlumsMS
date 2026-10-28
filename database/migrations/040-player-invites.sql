-- SlumsMS 1.6.1 (owner 2026-10-28, ticket 105): players with a level 30+ character make invite codes for friends with
-- @invite. Adds columns only, so an older jar runs fine on a migrated database (it ignores them).
--   invite_codes.created_by_char  the character that made the code (id; 0 for codes made before this or by a GM
--                                 with no character), next to created_by_account/created_by_name from 036.
--   invite_codes.self_serve       1 for a code made with the player command @invite (these count toward the weekly
--                                 limit); 0 for GM codes from !invite, which are unlimited.
--   invite_codes index            (created_by_account, created_at) for the rolling-week count.
--   accounts.invited_by_account / invited_by_char  who invited this account (0 = nobody: login-screen account or
--                                 made before invite codes). For staff only; never shown to players or the site.
-- Existing used codes are copied into accounts.invited_by_account once. Stop the game and take a backup first, as for
-- every migration here. Applying it twice changes nothing (ledger id '040-player-invites'). Needs 036 applied.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='040-player-invites');

SET @ddl = IF(@apply AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'invite_codes' AND COLUMN_NAME = 'created_by_char'),
  'ALTER TABLE invite_codes ADD COLUMN created_by_char INT NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(@apply AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'invite_codes' AND COLUMN_NAME = 'self_serve'),
  'ALTER TABLE invite_codes ADD COLUMN self_serve TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(@apply AND NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'invite_codes' AND INDEX_NAME = 'invite_codes_maker'),
  'ALTER TABLE invite_codes ADD KEY invite_codes_maker (created_by_account, created_at)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(@apply AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'invited_by_account'),
  'ALTER TABLE accounts ADD COLUMN invited_by_account INT NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(@apply AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'invited_by_char'),
  'ALTER TABLE accounts ADD COLUMN invited_by_char INT NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Codes used before this migration: record the maker on the account they created (first run only).
SET @ddl = IF(@apply,
  'UPDATE accounts a JOIN invite_codes i ON i.used_by_account = a.id SET a.invited_by_account = i.created_by_account WHERE i.used_by_account > 0 AND i.created_by_account > 0',
  'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('040-player-invites');
