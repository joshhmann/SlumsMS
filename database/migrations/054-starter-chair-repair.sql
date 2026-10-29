-- SlumsMS 1.6.1: repair starter chairs lost to the 1.6.0.2 HUD tip. 1.6.0.2 to 1.6.0.4 saved the one-time Edit-UI tip
-- (GuideMenu.UI_TIP_AREA) under area-info key 29151 with info 'uitip=1.6.0.2', but 29151 is StarterChairs.GIVEN_AREA
-- (info 'chairs=1.6.0'), and StarterChairs only looks at whether a 29151 row exists. At login StarterChairs runs first
-- and the tip later, so a character only lost out when the chairs were held back (fewer than 2 free Setup slots): the
-- tip then wrote 29151 the same login and the chairs were never given. 1.6.1 moved the tip to 29153.
--
-- Rule: an area_info row (area 29151, info exactly 'uitip=1.6.0.2') of a player character (id < 1,000,000,000; bots
-- never get the tip or starter chairs) is repaired when that character owns neither the Relaxer (3010000) nor the
-- Fishing Chair (3011000) anywhere: own items, hired merchant, wedding gifts (inventoryitems type 1/6/8), Duey
-- packages to it (type 9), the account's storage (type 2, shared by the account's characters), the Adventurer's Bag
-- (infinitystorage) or MTS listings. Repair = the row moves to 29153 (the tip's key since 1.6.1, so the tip is not shown
-- again), or is deleted if the character already has a 29153 row. With 29151 empty, StarterChairs gives both chairs at
-- the next login (or tells them to free 2 Setup slots). Characters with the tip mark who do own a chair are left alone.
--
-- Every touched row is kept in starter_chair_054_rows (charid, the old row, and moved/deleted) for the rollback and
-- audit. Stop the game first (it rewrites area_info on save). Applying it twice changes nothing
-- (ledger id '054-starter-chair-repair').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='054-starter-chair-repair');

CREATE TABLE IF NOT EXISTS starter_chair_054_rows (
  area_info_id INT          NOT NULL PRIMARY KEY,
  charid       INT          NOT NULL,
  area         INT          NOT NULL,
  info         VARCHAR(200) NOT NULL,
  action       VARCHAR(8)   NOT NULL,
  KEY idx_charid (charid)
) ENGINE=InnoDB;

INSERT INTO starter_chair_054_rows (area_info_id, charid, area, info, action)
  SELECT a.id, a.charid, a.area, a.info,
         IF(EXISTS (SELECT 1 FROM area_info t WHERE t.charid = a.charid AND t.area = 29153), 'deleted', 'moved')
  FROM area_info a
  JOIN characters c ON c.id = a.charid
  WHERE @apply AND a.area = 29151 AND a.info = 'uitip=1.6.0.2' AND a.charid < 1000000000
    AND NOT EXISTS (SELECT 1 FROM inventoryitems i
                    WHERE i.itemid IN (3010000, 3011000)
                      AND ((i.type IN (1, 6, 8) AND i.characterid = a.charid)
                        OR (i.type = 2 AND i.accountid = c.accountid)
                        OR (i.type = 9 AND i.characterid IN (SELECT d.PackageId FROM dueypackages d
                                                             WHERE d.ReceiverId = a.charid))))
    AND NOT EXISTS (SELECT 1 FROM infinitystorage b WHERE b.charid = a.charid AND b.itemid IN (3010000, 3011000))
    AND NOT EXISTS (SELECT 1 FROM mts_items m WHERE m.seller = a.charid AND m.itemid IN (3010000, 3011000));

UPDATE area_info a JOIN starter_chair_054_rows l ON l.area_info_id = a.id AND l.action = 'moved'
  SET a.area = 29153
  WHERE @apply AND a.area = 29151;
DELETE a FROM area_info a JOIN starter_chair_054_rows l ON l.area_info_id = a.id AND l.action = 'deleted'
  WHERE @apply AND a.area = 29151;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) SELECT '054-starter-chair-repair' FROM DUAL WHERE @apply;
