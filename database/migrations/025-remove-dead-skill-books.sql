-- SlumsMS 1.5.2 (jobs and skills audit, owner decision): stop dropping 19 mastery books whose skill MapleRoot deleted.
--
-- MapleRoot removed eleven 3rd/4th-job skills from Skill.wz (Divine Charge BW 1221004, Heaven's Hammer 1221011,
-- Big Bang 2121001, Taunt 4121003, Ninja Ambush 4121004, Assassinate 4221001, Super Transformation 5121003,
-- Demolition 5121004, Snatch 5121005, Barrage 5121007, Hypnotize 5221009) but left their mastery books in Item.wz and
-- in the drop tables. Using one of those books does nothing, so the drop is dead weight in a mob's drop list.
-- Items: 2290019, 2290020, 2290021, 2290026, 2290027, 2290080, 2290081, 2290082, 2290083, 2290092, 2290093, 2290101, 2290102, 2290103, 2290104, 2290105, 2290106, 2290107, 2290123.
-- This deletes every drop_data row (mob drops) and every reactordrops row (reactor drops) for those item ids; on TEST
-- that is 82 and 10 rows. No other drop, item or skill changes. The books stay in Item.wz and in players' inventories.
--
-- Stop the game and take a backup first, as for every migration here. Drops are cached when a mob or reactor is first
-- loaded, so a running server needs a restart anyway. Before deleting, the exact rows (with their ids) are copied into
-- migration_025_removed_drop_data and migration_025_removed_reactordrops, which 025-remove-dead-skill-books.rollback.sql
-- re-inserts from; those two tables are kept on purpose. Applying it twice changes nothing (ledger id
-- '025-remove-dead-skill-books'). drop_data is MyISAM, so its delete is not transactional: the copy runs first.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS migration_025_removed_drop_data LIKE drop_data;
CREATE TABLE IF NOT EXISTS migration_025_removed_reactordrops LIKE reactordrops;

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '025-remove-dead-skill-books');

INSERT IGNORE INTO migration_025_removed_drop_data
SELECT * FROM drop_data WHERE @apply AND itemid IN (2290019, 2290020, 2290021, 2290026, 2290027, 2290080, 2290081, 2290082, 2290083, 2290092, 2290093, 2290101, 2290102, 2290103, 2290104, 2290105, 2290106, 2290107, 2290123);
INSERT IGNORE INTO migration_025_removed_reactordrops
SELECT * FROM reactordrops WHERE @apply AND itemid IN (2290019, 2290020, 2290021, 2290026, 2290027, 2290080, 2290081, 2290082, 2290083, 2290092, 2290093, 2290101, 2290102, 2290103, 2290104, 2290105, 2290106, 2290107, 2290123);

DELETE FROM drop_data WHERE @apply AND itemid IN (2290019, 2290020, 2290021, 2290026, 2290027, 2290080, 2290081, 2290082, 2290083, 2290092, 2290093, 2290101, 2290102, 2290103, 2290104, 2290105, 2290106, 2290107, 2290123);
DELETE FROM reactordrops WHERE @apply AND itemid IN (2290019, 2290020, 2290021, 2290026, 2290027, 2290080, 2290081, 2290082, 2290083, 2290092, 2290093, 2290101, 2290102, 2290103, 2290104, 2290105, 2290106, 2290107, 2290123);

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('025-remove-dead-skill-books');
