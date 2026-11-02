-- SlumsMS 1.6.4 (ticket 164): give the Henesys Game Park casino NPC its chip shop back.
--
-- NPC 9000055 ("Aramia") is spawned into 100000203 (Henesys Game Park) at (1321, 214) by
-- EnvironmentManager.spawnCasinoNpcs, and that map is reachable on foot (100000000 in01 -> 100000200 in00 -> 100000203
-- out00). The NPC has no script (its WZ script node points at scripts/npc/armi.js, which does not exist) and no shop
-- row, so NPC talk fell through script -> hasShop() -> "NPC 9000055 (9000055) is not coded": a mute prop players click.
--
-- The shop is CasinoChipConfig: shop 9999001 for NPC 9000055, selling the four stamps at their face value (10k, 50k,
-- 250k, 1m mesos). CasinoChipShopMigrationTest keeps this file and CasinoChipConfig.CHIPS identical in shop, NPC, item
-- ids and prices. Selling a chip back pays the same face value (Shop.sell -> CasinoChipConfig.sellValue), so the
-- exchange is lossless mesos <-> chip; the stock chip WZ price (250/350/400/350) is never used.
--
-- Both halves are needed: scripts/npc/9000055.js calls cm.openShopNPC(9999001), and without this row
-- NPCConversationManager.openShopNPC logs "Shop ID: 9999001 is missing from database." and opens GM shop 11000 instead
-- (CasinoNpcShopFallbackTest). This row without that script leaves the NPC mute ("is not coded").
--
-- No existing row changes. Stop the game and take a backup first, as for every migration here. Applying it twice
-- changes nothing (ledger id '058-casino-chip-shop'; the shops row is inserted only when shop 9999001 is free and no
-- shop already serves NPC 9000055, and each item only when that shop does not already sell it). A shop that an
-- operator created by hand before this migration is left alone and stays theirs: only rows this script inserted are
-- recorded in mapleroot_integration_migration_rows and deleted by the rollback.
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '058-casino-chip-shop');

-- The game must be stopped: snapshot existing ids so only this insert is owned.
CREATE TEMPORARY TABLE migration_shops_before (id bigint PRIMARY KEY);
CREATE TEMPORARY TABLE migration_shopitems_before (id bigint PRIMARY KEY);
START TRANSACTION;
INSERT INTO migration_shops_before SELECT shopid FROM shops;
INSERT INTO migration_shopitems_before SELECT shopitemid FROM shopitems;
-- 9999001 is not a GM shop (ShopFactory: 1337 and 9999992-9999999) and Shop.NPC_SHOP_QUERY does not exclude it.
INSERT INTO shops (shopid, npcid)
SELECT 9999001, 9000055 FROM DUAL
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM shops s WHERE s.shopid = 9999001)
  AND NOT EXISTS (SELECT 1 FROM shops s WHERE s.npcid = 9000055);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '058-casino-chip-shop', 'shops', s.shopid FROM shops s
WHERE @apply AND s.shopid = 9999001
  AND NOT EXISTS (SELECT 1 FROM migration_shops_before b WHERE b.id = s.shopid);
INSERT INTO shopitems (shopid, itemid, price, pitch, position)
SELECT r.shopid, r.itemid, r.price, r.pitch, r.position
FROM (
  SELECT 9999001 AS shopid, 4002000 AS itemid, 10000 AS price, 0 AS pitch, 4 AS position
  UNION ALL SELECT 9999001, 4002001, 50000, 0, 3
  UNION ALL SELECT 9999001, 4002002, 250000, 0, 2
  UNION ALL SELECT 9999001, 4002003, 1000000, 0, 1
) r
WHERE @apply
  AND EXISTS (SELECT 1 FROM shops s WHERE s.shopid = r.shopid)
  AND NOT EXISTS (SELECT 1 FROM shopitems i WHERE i.shopid = r.shopid AND i.itemid = r.itemid);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '058-casino-chip-shop', 'shopitems', i.shopitemid FROM shopitems i
WHERE @apply AND i.shopid = 9999001
  AND NOT EXISTS (SELECT 1 FROM migration_shopitems_before b WHERE b.id = i.shopitemid);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('058-casino-chip-shop');
COMMIT;
DROP TEMPORARY TABLE migration_shopitems_before;
DROP TEMPORARY TABLE migration_shops_before;
