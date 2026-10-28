-- Undoes 045: deletes the card drops and monstercarddata rows 045 inserted (recorded in cards_043_rows; a pair that
-- was already there before 045 is left alone). Applying it twice changes nothing. A running server needs
-- !reloaddrops for the drops and a restart for monstercarddata.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS cards_043_rows (
  kind      varchar(16) NOT NULL,
  dropperid int NOT NULL,
  itemid    int NOT NULL,
  PRIMARY KEY (kind, dropperid, itemid)
) ENGINE=InnoDB;

DELETE d FROM drop_data d JOIN cards_043_rows r ON r.kind = 'drop' AND r.dropperid = d.dropperid AND r.itemid = d.itemid
 WHERE d.chance = 20000;
DELETE m FROM monstercarddata m JOIN cards_043_rows r ON r.kind = 'card' AND r.itemid = m.cardid AND r.dropperid = m.mobid;
DELETE FROM cards_043_rows;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '045-newer-area-cards';
