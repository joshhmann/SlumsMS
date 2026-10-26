-- Stop the game first and take a backup. Removes what 035 added: the stored trade ratings. The server keeps working
-- (an older jar never reads them; this jar keeps the counts in memory and retries). Applying it twice changes nothing.
DROP TABLE IF EXISTS traderatings;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='035-trade-ratings';
