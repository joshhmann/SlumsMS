-- Stop the game first and take a backup. Removes what 037 added: the password reset requests (passwords already reset
-- stay). The server keeps working (!resetpw says requests aren't available, /reset/complete answers UNAVAILABLE).
-- Applying it twice changes nothing.
DROP TABLE IF EXISTS password_resets;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='037-password-resets';
