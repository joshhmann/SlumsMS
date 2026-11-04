-- Stop the game first and take a backup. Removes what 062 added: the ten axis and stage columns of botbond and
-- botaffinity. Scores, kinds and hostile counts stay, so the server goes on with the older one-number relationships
-- (the axes are lost; they are derived from the score again at the next load). Applying it twice changes nothing.
SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botbond' AND COLUMN_NAME = 'HasAxes');
SET @ddl = IF(@present, 'ALTER TABLE botbond DROP COLUMN HasAxes, DROP COLUMN Stage, DROP COLUMN Obligation, DROP COLUMN Resentment, DROP COLUMN Rivalry, DROP COLUMN Attraction, DROP COLUMN Respect, DROP COLUMN Affection, DROP COLUMN Trust, DROP COLUMN Familiarity', 'DO 0');
PREPARE drop_botbond_axes FROM @ddl;
EXECUTE drop_botbond_axes;
DEALLOCATE PREPARE drop_botbond_axes;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botaffinity' AND COLUMN_NAME = 'HasAxes');
SET @ddl = IF(@present, 'ALTER TABLE botaffinity DROP COLUMN HasAxes, DROP COLUMN Stage, DROP COLUMN Obligation, DROP COLUMN Resentment, DROP COLUMN Rivalry, DROP COLUMN Attraction, DROP COLUMN Respect, DROP COLUMN Affection, DROP COLUMN Trust, DROP COLUMN Familiarity', 'DO 0');
PREPARE drop_botaffinity_axes FROM @ddl;
EXECUTE drop_botaffinity_axes;
DEALLOCATE PREPARE drop_botaffinity_axes;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='062-bot-relationship-axes';
