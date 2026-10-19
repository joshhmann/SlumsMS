-- Stop the game before applying. Dedicated classic-FM return preserves Arcane's return route.
ALTER TABLE savedlocations MODIFY locationtype ENUM('FREE_MARKET','WORLDTOUR','FLORINA','INTRO','SUNDAY_MARKET','MIRROR','EVENT','BOSSPQ','HAPPYVILLE','DEVELOPER','MONSTER_CARNIVAL','CLASSIC_FREE_MARKET') NOT NULL;
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('002-classic-fm-return');
