-- Stop the game first and take a backup. Removes only the drop_data row 072 recorded (its row id in
-- mapleroot_integration_migration_rows), never by item id, so a Thunder row someone added by hand stays.
-- drop_data is MyISAM: the delete is not transactional, the ledger deletes are. Reload drops afterward
-- (!reloaddrops) or restart.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS mapleroot_integration_migration_rows (
  migration_id varchar(100) NOT NULL,
  table_name varchar(64) NOT NULL,
  row_id bigint NOT NULL,
  PRIMARY KEY (migration_id, table_name, row_id)
) ENGINE=InnoDB;

START TRANSACTION;
DELETE FROM drop_data WHERE id IN (
  SELECT row_id FROM mapleroot_integration_migration_rows
  WHERE migration_id = '072-thunder-stone-drop' AND table_name = 'drop_data'
);
DELETE FROM mapleroot_integration_migration_rows WHERE migration_id = '072-thunder-stone-drop';
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '072-thunder-stone-drop';
COMMIT;
