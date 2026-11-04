-- SlumsMS 1.6.4 (ticket 181 stage 2, relationship axes): every directed relationship a bot holds (botbond: bot to bot;
-- botaffinity: bot to player) gets eight axes and a stage, so the server can tell "trusts but dislikes" from "admires
-- but is rivals" instead of one -100..100 score. Adds ten columns to each of the two tables, all with defaults, so every
-- existing row stays valid and an older jar runs fine on a migrated database (it never names them). Stop the game and
-- take a backup first, as for every migration here. Applying it twice changes nothing (ledger id
-- '059-bot-relationship-axes'). Without it the server still runs: the stores ask once whether the columns exist, and
-- without them read and write the older columns only, deriving the axes from the score at every load.
--
-- Familiarity, Respect, Attraction, Rivalry, Resentment, Trust, Affection, Obligation: the eight axes as of UpdatedAt
--               (Familiarity, Attraction, Rivalry, Resentment 0..1; Trust, Affection, Respect, Obligation -1..1; the
--               server fades each by its own half-life when it reads the row).
-- Stage:        0 STRANGER, 1 ACQUAINTANCE, 2 FRIEND, 3 CLOSE_FRIEND, 4 RIVAL, 5 ROMANTIC, 6 ESTRANGED (the server needs the
--               stage it was in last to apply its enter / leave thresholds; the numbers never change, new stages append).
-- HasAxes:      0 on every row that existed before this migration: the server derives that row's axes and stage from its
--               Score, Kind and Hostile at load and writes them back as 1 the next time the row changes.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- MySQL has no ADD COLUMN IF NOT EXISTS, so each table is altered (all ten columns in one atomic statement) only when
-- it exists and its last column, HasAxes, is missing.
SET @missing = EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botbond')
               AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botbond' AND COLUMN_NAME = 'HasAxes');
SET @ddl = IF(@missing, 'ALTER TABLE botbond ADD COLUMN Familiarity FLOAT NOT NULL DEFAULT 0, ADD COLUMN Trust FLOAT NOT NULL DEFAULT 0, ADD COLUMN Affection FLOAT NOT NULL DEFAULT 0, ADD COLUMN Respect FLOAT NOT NULL DEFAULT 0, ADD COLUMN Attraction FLOAT NOT NULL DEFAULT 0, ADD COLUMN Rivalry FLOAT NOT NULL DEFAULT 0, ADD COLUMN Resentment FLOAT NOT NULL DEFAULT 0, ADD COLUMN Obligation FLOAT NOT NULL DEFAULT 0, ADD COLUMN Stage TINYINT UNSIGNED NOT NULL DEFAULT 0, ADD COLUMN HasAxes TINYINT UNSIGNED NOT NULL DEFAULT 0', 'DO 0');
PREPARE add_botbond_axes FROM @ddl;
EXECUTE add_botbond_axes;
DEALLOCATE PREPARE add_botbond_axes;

SET @missing = EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botaffinity')
               AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botaffinity' AND COLUMN_NAME = 'HasAxes');
SET @ddl = IF(@missing, 'ALTER TABLE botaffinity ADD COLUMN Familiarity FLOAT NOT NULL DEFAULT 0, ADD COLUMN Trust FLOAT NOT NULL DEFAULT 0, ADD COLUMN Affection FLOAT NOT NULL DEFAULT 0, ADD COLUMN Respect FLOAT NOT NULL DEFAULT 0, ADD COLUMN Attraction FLOAT NOT NULL DEFAULT 0, ADD COLUMN Rivalry FLOAT NOT NULL DEFAULT 0, ADD COLUMN Resentment FLOAT NOT NULL DEFAULT 0, ADD COLUMN Obligation FLOAT NOT NULL DEFAULT 0, ADD COLUMN Stage TINYINT UNSIGNED NOT NULL DEFAULT 0, ADD COLUMN HasAxes TINYINT UNSIGNED NOT NULL DEFAULT 0', 'DO 0');
PREPARE add_botaffinity_axes FROM @ddl;
EXECUTE add_botaffinity_axes;
DEALLOCATE PREPARE add_botaffinity_axes;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('059-bot-relationship-axes');
