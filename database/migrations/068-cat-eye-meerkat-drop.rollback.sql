-- Stop the game first and take a backup. Removes only the Meerkat row 068 added, matched on its exact values so a
-- Meerkat row with different values is left alone. The Scorpion 2110301 row stays. Reload drops afterward.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

START TRANSACTION;
DELETE FROM drop_data
 WHERE dropperid = 2100108 AND itemid = 4031568
   AND minimum_quantity = 1 AND maximum_quantity = 1 AND questid = 3911 AND chance = 80000;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '068-cat-eye-meerkat-drop';
COMMIT;
