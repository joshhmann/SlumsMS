-- Stop the game first and take a backup. Delete only ids recorded by this migration.
-- An absent ownership table means the forward insert never ran; never guess by NPC/location.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migration_rows (
  migration_id varchar(100) NOT NULL,
  table_name varchar(64) NOT NULL,
  row_id bigint NOT NULL,
  PRIMARY KEY (migration_id, table_name, row_id)
) ENGINE=InnoDB;

START TRANSACTION;
DELETE FROM plife WHERE id IN (
  SELECT row_id FROM mapleroot_integration_migration_rows
  WHERE migration_id = '046-lazy-penguins' AND table_name = 'plife'
);
DELETE FROM mapleroot_integration_migration_rows WHERE migration_id = '046-lazy-penguins';
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '046-lazy-penguins';
COMMIT;
