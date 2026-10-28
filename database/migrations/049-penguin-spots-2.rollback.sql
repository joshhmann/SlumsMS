-- Stop the game first and take a backup. Puts the five penguins back on their 046/048 spots.
START TRANSACTION;
UPDATE plife p
JOIN (
  SELECT 105040300 AS map, 2991 AS life, 920 AS ox, 255 AS oy, 90 AS ofh, 793 AS nx, -720 AS ny, 85 AS nfh   -- Blueberry, Sleepywood
  UNION ALL SELECT 120000000, 2992, 2850, 91, 696, 2689, -333, 334   -- Tangerine, Nautilus
  UNION ALL SELECT 800000000, 2998, 2905, 95, 106, 2965, -105, 107   -- Mint, Showa
  UNION ALL SELECT 600000000, 2999, 2335, 501, 455, 2001, 392, 441   -- Classic, New Leaf City
  UNION ALL SELECT 101000000, 2987, -102, 95, 413, 285, -693, 638   -- Lemon, Ellinia
) m ON p.map = m.map AND p.life = m.life AND p.x = m.ox AND p.y = m.oy AND p.fh = m.ofh
SET p.x = m.nx, p.y = m.ny, p.cy = m.ny, p.fh = m.nfh, p.rx0 = m.nx + 50, p.rx1 = m.nx - 50
WHERE p.world IN (0, 1) AND p.type = 'n';
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '049-penguin-spots-2';
COMMIT;
