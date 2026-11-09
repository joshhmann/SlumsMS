-- Stop the game first and take a backup. Removes only the plife rows 071 recorded (the recruiter 3003970 in
-- 271040000 and the Guwaru summoner 3003903 in 271040100), never by NPC id, so a hand-placed NPC stays.
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
DELETE FROM plife WHERE id IN (
  SELECT row_id FROM mapleroot_integration_migration_rows
  WHERE migration_id = '071-cygnus-npcs' AND table_name = 'plife'
);
DELETE FROM mapleroot_integration_migration_rows WHERE migration_id = '071-cygnus-npcs';
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '071-cygnus-npcs';
COMMIT;
