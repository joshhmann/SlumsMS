-- Stop the game first and take a backup. Removes what 011 added: every bot's feelings about players and the
-- @botdrama opt-outs. The server keeps working with both in memory only. Applying it twice changes nothing.
DROP TABLE IF EXISTS botaffinity;
DROP TABLE IF EXISTS botdrama;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='011-bot-affinity';
