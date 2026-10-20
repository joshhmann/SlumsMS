-- Stop the game first and take a backup. Removes every SoloMapling bot: their rows, roster, memory notes and
-- the items stored under bot character ids (>= 1000000000). Real characters are untouched.
DELETE ie FROM inventoryequipment ie JOIN inventoryitems ii ON ie.inventoryitemid = ii.inventoryitemid
  WHERE ii.characterid >= 1000000000;
DELETE FROM inventoryitems WHERE characterid >= 1000000000;
DROP TABLE IF EXISTS botmemory;
DROP TABLE IF EXISTS botroster;
DROP TABLE IF EXISTS botstore;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='003-solomapling-bots';
