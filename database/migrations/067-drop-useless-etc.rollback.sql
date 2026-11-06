-- Stop the game first and take a backup. Puts back the drop rows 067 removed (exactly as they were); reload drops afterward.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS useless_etc_067_rows (
  dropperid int NOT NULL, itemid int NOT NULL, minimum_quantity int NOT NULL, maximum_quantity int NOT NULL,
  questid int NOT NULL, chance int NOT NULL, PRIMARY KEY (dropperid, itemid)
) ENGINE=InnoDB;

START TRANSACTION;
INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
SELECT r.dropperid, r.itemid, r.minimum_quantity, r.maximum_quantity, r.questid, r.chance
  FROM useless_etc_067_rows r
 WHERE NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = r.dropperid AND d.itemid = r.itemid);
DELETE FROM useless_etc_067_rows;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '067-drop-useless-etc';
COMMIT;
