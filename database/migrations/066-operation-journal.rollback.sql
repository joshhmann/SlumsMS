-- Stop the game first and take a backup. Drops the journal table, so rows still STARTED lose their boot reconcile:
-- check `SELECT * FROM operation_journal WHERE state = 'STARTED'` and settle those by hand before running this.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

DROP TABLE IF EXISTS operation_journal;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '066-operation-journal';
