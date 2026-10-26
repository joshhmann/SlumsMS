-- Stop the game first and take a backup. Removes what 029 added: the bot-to-bot bonds. The server keeps working with
-- bonds in memory only (an older jar never reads them). Applying it twice changes nothing.
DROP TABLE IF EXISTS botbond;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='029-bot-bonds';
