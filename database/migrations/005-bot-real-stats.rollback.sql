-- Stop the game first and take a backup. Removes what 005 added: the bots' stored skills and their stat columns.
-- Bots keep their identity, level, job and items; on the next boot with 005 applied again their builds are
-- back-filled from level and job. Applying it twice changes nothing.
DROP TABLE IF EXISTS botskills;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botstore' AND COLUMN_NAME = 'BuildLevel');
SET @ddl = IF(@present,
  'ALTER TABLE botstore
     DROP COLUMN StatStr, DROP COLUMN StatDex, DROP COLUMN StatInt, DROP COLUMN StatLuk,
     DROP COLUMN MaxHp, DROP COLUMN MaxMp, DROP COLUMN Hp, DROP COLUMN Mp,
     DROP COLUMN RemainingAp, DROP COLUMN Sp, DROP COLUMN BuildLevel',
  'DO 0');
PREPARE drop_real_stats FROM @ddl;
EXECUTE drop_real_stats;
DEALLOCATE PREPARE drop_real_stats;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='005-bot-real-stats';
