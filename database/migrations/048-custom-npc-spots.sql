-- SlumsMS 1.6.1 (owner 2026-10-28): move custom NPCs whose sprite a map object drew over to open ground nearby.
-- Slums-chan in Henesys (042) stood behind hay bales and a fence; Cherry in Henesys (046) behind the tree house fence;
-- eight more penguins (046) sat behind front-layer objects or deco. Found by scanning every custom NPC spot against the
-- map's obj/tile layers above the foothold's layer (tools/data-align/lazy-penguins/penguins.tsv holds the new spots).
-- Unchanged (nothing drawn over them): Slums-chan in Base of Roots, and the penguins of Lith Harbor, Sleepywood,
-- Nautilus, Orbis, Leafre, Showa. Each new spot is on a real foothold, 60+ px from portals, 90+ px from NPCs;
-- SlumsChanNpcMigrationTest and LazyPenguinsTest check it against the WZ. A plife change needs a game restart to show.
--
-- Updates the rows migrations 042 and 046 made (worlds 0 and 1); a row that is not at its old spot is left alone. Stop
-- the game and take a backup first, as for every migration here. Applying it twice changes nothing (ledger id
-- '048-custom-npc-spots', and the update only matches the old position).
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '048-custom-npc-spots');

START TRANSACTION;
UPDATE plife p
JOIN (
  SELECT 100000000 AS map, 9990000 AS life, 1215 AS ox, 334 AS oy, 102 AS ofh, 1040 AS nx, 334 AS ny, 91 AS nfh   -- Slums-chan, Henesys
  UNION ALL SELECT 209000000, 2985, 1306, -94, 79, 1305, 154, 80   -- Christmas, Happyville
  UNION ALL SELECT 100000000, 2986, 573, 11, 60, 102, 34, 38   -- Cherry, Henesys
  UNION ALL SELECT 101000000, 2987, -65, -305, 408, 285, -693, 638   -- Lemon, Ellinia
  UNION ALL SELECT 102000000, 2988, 1526, 589, 186, 1540, 589, 186   -- Sunny, Perion
  UNION ALL SELECT 103000000, 2989, 496, -135, 275, 470, -54, 257   -- Cocoa, Kerning City
  UNION ALL SELECT 220000000, 2994, -132, -705, 251, -353, -678, 62   -- Teal, Ludibrium
  UNION ALL SELECT 230000000, 2995, 1757, 469, 72, 1325, 460, 26   -- Clown, Aquarium
  UNION ALL SELECT 250000000, 2997, -1492, -488, 315, -1645, 51, 307   -- Berry, Mu Lung
  UNION ALL SELECT 600000000, 2999, 2655, 364, 465, 2001, 392, 441   -- Classic, New Leaf City
) m ON p.map = m.map AND p.life = m.life AND p.x = m.ox AND p.y = m.oy AND p.fh = m.ofh
SET p.x = m.nx, p.y = m.ny, p.cy = m.ny, p.fh = m.nfh, p.rx0 = m.nx + 50, p.rx1 = m.nx - 50
WHERE @apply AND p.world IN (0, 1) AND p.type = 'n';
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('048-custom-npc-spots');
COMMIT;
