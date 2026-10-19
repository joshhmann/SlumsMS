-- PREPARED ONLY: never run against a live server with players connected.
-- Take a fresh backup, stop the game service, and verify destination map IDs are unused.
-- MyISAM tables are present: SQL transactions cannot roll back all these tables.
-- If application fails partway, restore the database backup before retrying.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
-- This rollback relocates all users/locations on the preserved maps back to their original IDs,
-- including any created after migration. It requires restoring the matching old client/server maps.
SET @apply = EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='001-preserve-arcane-for-classic-fm');
UPDATE `characters` SET `map`=`map`-83910000 WHERE @apply AND `map` BETWEEN 993910000 AND 993910022;
UPDATE `medalmaps` SET `mapid`=`mapid`-83910000 WHERE @apply AND `mapid` BETWEEN 993910000 AND 993910022;
UPDATE `playernpcs` SET `map`=`map`-83910000 WHERE @apply AND `map` BETWEEN 993910000 AND 993910022;
UPDATE `playernpcs_field` SET `map`=`map`-83910000 WHERE @apply AND `map` BETWEEN 993910000 AND 993910022;
UPDATE `plife` SET `map`=`map`-83910000 WHERE @apply AND `map` BETWEEN 993910000 AND 993910022;
UPDATE `savedlocations` SET `map`=`map`-83910000 WHERE @apply AND `map` BETWEEN 993910000 AND 993910022;
UPDATE `trocklocations` SET `mapid`=`mapid`-83910000 WHERE @apply AND `mapid` BETWEEN 993910000 AND 993910022;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='001-preserve-arcane-for-classic-fm';
