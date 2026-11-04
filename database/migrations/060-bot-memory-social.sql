-- SlumsMS 1.6.4 (ticket 271, Stage 3: structured social memory): what a bot remembers gets a subject type (a real
-- player or another bot), a place, how sure the bot is, how it felt, how it came to know and a second character
-- involved, so bots remember each other, recall by relevance and keep to what they saw or were told. Adds six
-- columns to botmemory, all with defaults, so every existing row stays valid and an older jar runs fine on a migrated
-- database (it never names them). Stop the game and take a backup first, as for every migration here. Applying it
-- twice changes nothing (ledger id '060-bot-memory-social'). Without it the server still runs: new notes keep those
-- six fields in memory only and read back with the defaults of their source (it also needs 030's Kind/Weight columns).
--
-- SubjectType:      PLAYER (every older row) or BOT (the note is about another bot; AboutCharacterId is then that bot's
--                   character id, at least 1,000,000,000).
-- Location:         the map id where it happened; NULL = unknown.
-- Confidence:       0..1, how sure the bot is; NULL = the default of the row's Source (DIRECT 0.9, HEARSAY 0.5 / hops).
-- Valence:          -1..1, how it felt (bad to good); its size is how strongly it stuck. 0 for every older row.
-- Provenance:       OBJECTIVE (the game recorded it), WITNESSED, REPORTED (told, always Source HEARSAY) or BELIEF;
--                   NULL = WITNESSED for DIRECT and REPORTED for HEARSAY.
-- OtherParticipant: a second character involved (a player id or a bot's character id); NULL = none.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- MySQL has no ADD COLUMN IF NOT EXISTS, so each column is added only when it is missing.
SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'SubjectType');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN SubjectType VARCHAR(6) NOT NULL DEFAULT ''PLAYER''', 'DO 0');
PREPARE add_subject FROM @ddl;
EXECUTE add_subject;
DEALLOCATE PREPARE add_subject;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Location');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN Location INT NULL DEFAULT NULL', 'DO 0');
PREPARE add_location FROM @ddl;
EXECUTE add_location;
DEALLOCATE PREPARE add_location;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Confidence');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN Confidence FLOAT NULL DEFAULT NULL', 'DO 0');
PREPARE add_confidence FROM @ddl;
EXECUTE add_confidence;
DEALLOCATE PREPARE add_confidence;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Valence');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN Valence FLOAT NOT NULL DEFAULT 0', 'DO 0');
PREPARE add_valence FROM @ddl;
EXECUTE add_valence;
DEALLOCATE PREPARE add_valence;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Provenance');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN Provenance VARCHAR(10) NULL DEFAULT NULL', 'DO 0');
PREPARE add_provenance FROM @ddl;
EXECUTE add_provenance;
DEALLOCATE PREPARE add_provenance;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'OtherParticipant');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN OtherParticipant INT NULL DEFAULT NULL', 'DO 0');
PREPARE add_other FROM @ddl;
EXECUTE add_other;
DEALLOCATE PREPARE add_other;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('060-bot-memory-social');
