-- Stop the game first. Only the new classic-FM return bookmarks are discarded.
DELETE FROM savedlocations WHERE locationtype='CLASSIC_FREE_MARKET';
ALTER TABLE savedlocations MODIFY locationtype ENUM('FREE_MARKET','WORLDTOUR','FLORINA','INTRO','SUNDAY_MARKET','MIRROR','EVENT','BOSSPQ','HAPPYVILLE','DEVELOPER','MONSTER_CARNIVAL') NOT NULL;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='002-classic-fm-return';
