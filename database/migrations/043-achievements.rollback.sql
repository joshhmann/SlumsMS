-- Stop the game first and take a backup. Removes the achievement progress of every character, bots included. Medals
-- already in bags stay (they are ordinary items). Applying it twice changes nothing.
DROP TABLE IF EXISTS character_achievements;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '043-achievements';
