-- Stop the game first and take a backup. Removes the six gachapon machines 011 placed (only those rows: same world,
-- map and NPC id). Applying it twice changes nothing.
START TRANSACTION;
DELETE FROM plife WHERE type = 'n' AND (map, life) IN ((101000000, 9100101), (102000000, 9100102),
    (103000000, 9100103), (105040300, 9100104), (800000000, 9100105), (600000000, 9100109)) AND world IN (0, 1);
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '011-gachapon-machines';
COMMIT;
