-- Stop the game first and take a backup. Removes what 016 added: every guild war and alliance (scores included).
-- Guilds and their members are untouched. Applying it twice changes nothing.
DROP TABLE IF EXISTS botguildwar;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='016-bot-guild-wars';
