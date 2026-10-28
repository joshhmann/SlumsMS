-- SlumsMS 1.6.1 (client-session screenshots of all 17 custom NPC spots on TEST, 2026-10-28): five penguins from 046/048
-- were out of sight or out of reach. Moved to ground level on the main walkway, off ledges, roofs and rope tops:
-- Blueberry (Sleepywood), Tangerine (Nautilus), Mint (Showa), Classic (New Leaf City), Lemon (Ellinia).
-- Updates the rows 046/048 left (worlds 0 and 1); a row that is not at the old spot is left alone. Stop the game and take
-- a backup first. Applying it twice changes nothing (ledger id '049-penguin-spots-2'). A plife change needs a restart.
-- LazyPenguinsTest checks each spot against the WZ: real foothold, portals, NPCs, objects in front, reachability.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '049-penguin-spots-2');

START TRANSACTION;
UPDATE plife p
JOIN (
  SELECT 105040300 AS map, 2991 AS life, 793 AS ox, -720 AS oy, 85 AS ofh, 920 AS nx, 255 AS ny, 90 AS nfh   -- Blueberry, Sleepywood
  UNION ALL SELECT 120000000, 2992, 2689, -333, 334, 2850, 91, 696   -- Tangerine, Nautilus
  UNION ALL SELECT 800000000, 2998, 2965, -105, 107, 2905, 95, 106   -- Mint, Showa
  UNION ALL SELECT 600000000, 2999, 2001, 392, 441, 2335, 501, 455   -- Classic, New Leaf City
  UNION ALL SELECT 101000000, 2987, 285, -693, 638, -102, 95, 413   -- Lemon, Ellinia
) m ON p.map = m.map AND p.life = m.life AND p.x = m.ox AND p.y = m.oy AND p.fh = m.ofh
SET p.x = m.nx, p.y = m.ny, p.cy = m.ny, p.fh = m.nfh, p.rx0 = m.nx + 50, p.rx1 = m.nx - 50
WHERE @apply AND p.world IN (0, 1) AND p.type = 'n';
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('049-penguin-spots-2');
COMMIT;
