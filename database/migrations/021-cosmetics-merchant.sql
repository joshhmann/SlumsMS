-- SlumsMS 1.5.0 rc3 (economy lane, owner decision 6): a meso-sink merchant in the Free Market entrance (910000000):
-- Hanako (NPC 9209006, "Special Item Merchant - My hats are only for people with elegant taste!"), a stock v83 NPC
-- with no script and no shop row, so the client draws her from Npc.wz as for !pnpc. Talking to her opens the
-- cosmetics shop the server builds from economy.yaml (cosmeticShop.items: hair, face, lens, skin and dye coupons,
-- classic cosmetic items and Gachapon Tickets for big mesos); there is no shops/shopitems row, so prices are
-- changed in economy.yaml and !economy reload, not here.
--
-- Spot: the ground floor (y 34, foothold 85 runs x -270..-180) between the Free Market Maple TV (x -71) and the exit
-- portal out00 (x -538): 179 px from the TV, 288 px from the portal (EconomyMigrationTest checks it against the WZ).
-- Rows for world 0 and world 1 (Bera, after 008): a world that does not run never loads its rows.
--
-- No existing row changes. Stop the game and take a backup first, as for every migration here. Applying it twice
-- changes nothing (ledger id '021-cosmetics-merchant', and each row is inserted only when missing).
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '021-cosmetics-merchant');

START TRANSACTION;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, 'n', m.y, 0, m.fh, m.x + 50, m.x - 50, m.x, m.y, 0, -1, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- merchant: map, npc, x, ground y, foothold
  SELECT 910000000 AS map, 9209006 AS life, -250 AS x, 34 AS y, 85 AS fh   -- FM entrance, left of the Maple TV
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('021-cosmetics-merchant');
COMMIT;
