-- Undoes 058: drops the desk's state table. The desk's money stays where it is (the HOUSE -100 buckets in
-- exchange_escrow, with their ledger rows): switching the desk off leaves it there and `!exchange desk unfund` moves it
-- back to the house account's wallet first. Dropping the table loses R and the history: with the desk switched back on,
-- R restarts from the official VWAP (or 5,000,000), not from where it was. Applying it twice changes nothing.
DROP TABLE IF EXISTS exchange_desk;

DELETE FROM mapleroot_integration_migrations WHERE migration_id = '058-nx-desk';
