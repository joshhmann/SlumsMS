-- Stop the game first and take a backup. Removes what 036 added: the invite codes (accounts made with them stay).
-- The server keeps working (!invite and site sign-up then answer that codes aren't available). Applying it twice
-- changes nothing.
DROP TABLE IF EXISTS invite_codes;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='036-invite-codes';
