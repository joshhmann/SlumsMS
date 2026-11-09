-- SlumsMS 068 (P0nk/Cosmic bfcadc069): quest 3911 "Making the Fancy Sword" asks for 4 Cat's Eye (4031568), and the
-- item text says it comes from Meowcat. Our drop_data only had it on Scorpion 2110301, so the hint sent players to a
-- mob that could not drop it. This adds the Meerkat (2100108) row upstream ships, quest-only at chance 80000, and
-- leaves the Scorpion row exactly as it is. Applying it twice changes nothing (ledger id
-- '068-cat-eye-meerkat-drop'). A running server reads drop_data on boot or on !reloaddrops.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

START TRANSACTION;
INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
SELECT 2100108, 4031568, 1, 1, 3911, 80000 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = 2100108 AND d.itemid = 4031568);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('068-cat-eye-meerkat-drop');
COMMIT;
