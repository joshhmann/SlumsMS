-- Undoes 009: drops the GM audit tables (and their history; dump them first if it should be kept). Only runs while
-- the ledger holds 009; applying it twice changes nothing. The 1.3.1 jar never touches these tables.
SET @applied = EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='009-gm-audit');
SET @sql = IF(@applied, 'DROP TABLE IF EXISTS gm_command_audit, gm_level_changes', 'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='009-gm-audit';
