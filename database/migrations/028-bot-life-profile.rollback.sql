-- Stop the game first and take a backup. Removes what 028 added: every bot's saved daily-life profile (Bartle blend,
-- learned drift, goals, life events). An older jar never read this table; it seeds each bot's profile again from its
-- slot, so bots lose only the small drift they picked up since 1.6.0. Applying it twice changes nothing.
DROP TABLE IF EXISTS botlifeprofile;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='028-bot-life-profile';
