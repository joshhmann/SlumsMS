-- SlumsMS card b349: ETC drops 050 added that have no use in scripts/ and no NPC price. 050 kept them only because an
-- old forge recipe used them (b349 named Sleepy Grobbler Stalk 4000704, Amaterasu Shoes); the Nov 2 rebirth-forge
-- change (21df468eb) replaced those materials, so they no longer meet the use-or-price rule
-- (docs/economy/bb-drops/RULES.md). The data test found 15 more with the same cause: Grobbler/Boss-tier stalks
-- 4000705-4000712, 4000732, 4000734, 4009221 and the Arcane River stones 4000649-4000651. 050 is already applied and
-- is never edited: this removes exactly the 16 (mob, item) rows listed below.
-- Each removed row is kept whole in useless_etc_067_rows so the rollback puts back exactly what was deleted.
-- Applying twice changes nothing (ledger id '067-drop-useless-etc').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS useless_etc_067_rows (
  dropperid int NOT NULL, itemid int NOT NULL, minimum_quantity int NOT NULL, maximum_quantity int NOT NULL,
  questid int NOT NULL, chance int NOT NULL, PRIMARY KEY (dropperid, itemid)
) ENGINE=InnoDB;

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '067-drop-useless-etc');

CREATE TEMPORARY TABLE useless_etc_067_pairs (dropperid int NOT NULL, itemid int NOT NULL, PRIMARY KEY (dropperid, itemid));
INSERT INTO useless_etc_067_pairs (dropperid, itemid) VALUES
(2400000, 4000704),
(2400001, 4000705),
(2400002, 4000706),
(2400003, 4000707),
(2400005, 4000709),
(2400006, 4000710),
(2400013, 4000711),
(2400014, 4000712),
(2400111, 4000732),
(2400113, 4000734),
(2400202, 4009221),
(2400361, 4000734),
(8610005, 4000649),
(8610006, 4000650),
(8610007, 4000651),
(8610022, 4000649);

START TRANSACTION;
INSERT IGNORE INTO useless_etc_067_rows (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
SELECT d.dropperid, d.itemid, d.minimum_quantity, d.maximum_quantity, d.questid, d.chance
  FROM drop_data d JOIN useless_etc_067_pairs p ON p.dropperid = d.dropperid AND p.itemid = d.itemid
 WHERE @apply;
DELETE d FROM drop_data d JOIN useless_etc_067_rows r ON r.dropperid = d.dropperid AND r.itemid = d.itemid
 WHERE @apply;
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('067-drop-useless-etc');
COMMIT;
DROP TEMPORARY TABLE useless_etc_067_pairs;
