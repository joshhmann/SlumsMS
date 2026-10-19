-- Stop the game first. Discards every account's check-in streak.
DROP TABLE IF EXISTS daily_checkin;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='004-daily-checkin';
