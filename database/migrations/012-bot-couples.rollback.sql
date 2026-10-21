-- Stop the game first and take a backup. Removes what 012 added: every bot couple (and so every bot marriage). Rings
-- the bots wear stay on them as plain items. Applying it twice changes nothing.
DROP TABLE IF EXISTS botcouple;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='012-bot-couples';
