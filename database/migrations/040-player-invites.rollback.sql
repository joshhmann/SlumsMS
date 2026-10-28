-- Stop the game first and take a backup. Removes what 040 added: who made each code (character, self-serve flag) and
-- who invited each account. Codes and accounts stay; player-made codes then look like GM codes and the weekly limit
-- is lost with the column. Applying it twice changes nothing.
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'invite_codes' AND INDEX_NAME = 'invite_codes_maker'),
  'ALTER TABLE invite_codes DROP INDEX invite_codes_maker', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'invite_codes' AND COLUMN_NAME = 'self_serve'),
  'ALTER TABLE invite_codes DROP COLUMN self_serve', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'invite_codes' AND COLUMN_NAME = 'created_by_char'),
  'ALTER TABLE invite_codes DROP COLUMN created_by_char', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'invited_by_account'),
  'ALTER TABLE accounts DROP COLUMN invited_by_account', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'invited_by_char'),
  'ALTER TABLE accounts DROP COLUMN invited_by_char', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='040-player-invites';
