-- PREPARED ONLY: never run against a live server with players connected.
-- Take a fresh backup, stop the game service, and verify destination map IDs are unused.
-- MyISAM tables are present: SQL transactions cannot roll back all these tables.
-- If application fails partway, restore the database backup before retrying.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='001-preserve-arcane-for-classic-fm');
UPDATE `characters` SET `map`=`map`+83910000 WHERE @apply AND `map` BETWEEN 910000000 AND 910000022;
UPDATE `medalmaps` SET `mapid`=`mapid`+83910000 WHERE @apply AND `mapid` BETWEEN 910000000 AND 910000022;
UPDATE `playernpcs` SET `map`=`map`+83910000 WHERE @apply AND `map` BETWEEN 910000000 AND 910000022;
UPDATE `playernpcs_field` SET `map`=`map`+83910000 WHERE @apply AND `map` BETWEEN 910000000 AND 910000022;
UPDATE `plife` SET `map`=`map`+83910000 WHERE @apply AND `map` BETWEEN 910000000 AND 910000022;
UPDATE `savedlocations` SET `map`=`map`+83910000 WHERE @apply AND `map` BETWEEN 910000000 AND 910000022;
UPDATE `trocklocations` SET `mapid`=`mapid`+83910000 WHERE @apply AND `mapid` BETWEEN 910000000 AND 910000022;
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('001-preserve-arcane-for-classic-fm');
