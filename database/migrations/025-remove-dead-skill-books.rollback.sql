-- Stop the game first and take a backup. Puts back exactly the rows 025 deleted (same ids), from the tables it kept.
-- An absent kept table means the forward migration never ran; nothing is guessed and nothing is inserted.
CREATE TABLE IF NOT EXISTS migration_025_removed_drop_data LIKE drop_data;
CREATE TABLE IF NOT EXISTS migration_025_removed_reactordrops LIKE reactordrops;

INSERT IGNORE INTO drop_data SELECT * FROM migration_025_removed_drop_data;
INSERT IGNORE INTO reactordrops SELECT * FROM migration_025_removed_reactordrops;
DROP TABLE migration_025_removed_drop_data;
DROP TABLE migration_025_removed_reactordrops;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '025-remove-dead-skill-books';
