-- Stop the game first and take a backup. Puts the Mint penguin back on its 049 spot in Mushroom Shrine.
START TRANSACTION;
UPDATE plife p
JOIN (
  SELECT 801000000 AS omap, 2998 AS life, 1560 AS ox, 155 AS oy, 52 AS ofh, 800000000 AS nmap, 2905 AS nx, 95 AS ny, 106 AS nfh   -- Mint, Showa Town -> Mushroom Shrine
) m ON p.map = m.omap AND p.life = m.life AND p.x = m.ox AND p.y = m.oy AND p.fh = m.ofh
SET p.map = m.nmap, p.x = m.nx, p.y = m.ny, p.cy = m.ny, p.fh = m.nfh, p.rx0 = m.nx + 50, p.rx1 = m.nx - 50
WHERE p.world IN (0, 1) AND p.type = 'n';
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '051-penguin-showa';
COMMIT;
