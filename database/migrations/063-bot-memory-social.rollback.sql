-- Stop the game first and take a backup. Removes what 063 added: botmemory's SubjectType, Location, Confidence,
-- Valence, Provenance and OtherParticipant columns. The notes themselves stay (a note about another bot stays too,
-- as a plain note keyed by that bot's character id); what the columns held is lost and every note reads with the
-- defaults of its source again. Applying it twice changes nothing.
SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'OtherParticipant');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN OtherParticipant', 'DO 0');
PREPARE drop_other FROM @ddl;
EXECUTE drop_other;
DEALLOCATE PREPARE drop_other;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Provenance');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN Provenance', 'DO 0');
PREPARE drop_provenance FROM @ddl;
EXECUTE drop_provenance;
DEALLOCATE PREPARE drop_provenance;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Valence');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN Valence', 'DO 0');
PREPARE drop_valence FROM @ddl;
EXECUTE drop_valence;
DEALLOCATE PREPARE drop_valence;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Confidence');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN Confidence', 'DO 0');
PREPARE drop_confidence FROM @ddl;
EXECUTE drop_confidence;
DEALLOCATE PREPARE drop_confidence;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Location');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN Location', 'DO 0');
PREPARE drop_location FROM @ddl;
EXECUTE drop_location;
DEALLOCATE PREPARE drop_location;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'SubjectType');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN SubjectType', 'DO 0');
PREPARE drop_subject FROM @ddl;
EXECUTE drop_subject;
DEALLOCATE PREPARE drop_subject;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='063-bot-memory-social';
