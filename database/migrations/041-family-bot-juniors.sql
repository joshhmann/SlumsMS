-- SlumsMS 1.6.1 (ticket 53, owner 2026-10-28): the Family system is on, and roster bots may be players' juniors.
-- A bot has no characters row (its id is 1,000,000,000 + its botstore BotId), so the stock foreign keys from
-- family_character.cid (and family_entitlement.charid, if this database has one) to characters(id) refuse a bot's row.
-- This drops those keys and keeps what they were in family_041_fks for the rollback. Deleting a character still
-- removes its family_character row: Character.deleteCharFromDB does that itself.
-- An older jar is unaffected (it never writes a bot's row). Applying it twice changes nothing (ledger id
-- '041-family-bot-juniors').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS family_041_fks (
  table_name      varchar(64) NOT NULL,
  constraint_name varchar(64) NOT NULL,
  column_name     varchar(64) NOT NULL,
  ref_table       varchar(64) NOT NULL,
  ref_column      varchar(64) NOT NULL,
  delete_rule     varchar(16) NOT NULL,
  PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB;

INSERT IGNORE INTO family_041_fks (table_name, constraint_name, column_name, ref_table, ref_column, delete_rule)
SELECT k.TABLE_NAME, k.CONSTRAINT_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME, r.DELETE_RULE
  FROM information_schema.KEY_COLUMN_USAGE k
  JOIN information_schema.REFERENTIAL_CONSTRAINTS r
    ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND r.TABLE_NAME = k.TABLE_NAME
 WHERE k.TABLE_SCHEMA = DATABASE() AND k.REFERENCED_TABLE_NAME = 'characters'
   AND ((k.TABLE_NAME = 'family_character' AND k.COLUMN_NAME = 'cid')
     OR (k.TABLE_NAME = 'family_entitlement' AND k.COLUMN_NAME = 'charid'));

SET @fk = (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'family_character' AND COLUMN_NAME = 'cid'
              AND REFERENCED_TABLE_NAME = 'characters' LIMIT 1);
SET @ddl = IF(@fk IS NULL, 'DO 0', CONCAT('ALTER TABLE family_character DROP FOREIGN KEY `', @fk, '`'));
PREPARE drop_fk FROM @ddl;
EXECUTE drop_fk;
DEALLOCATE PREPARE drop_fk;

SET @fk = (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'family_entitlement' AND COLUMN_NAME = 'charid'
              AND REFERENCED_TABLE_NAME = 'characters' LIMIT 1);
SET @ddl = IF(@fk IS NULL, 'DO 0', CONCAT('ALTER TABLE family_entitlement DROP FOREIGN KEY `', @fk, '`'));
PREPARE drop_fk FROM @ddl;
EXECUTE drop_fk;
DEALLOCATE PREPARE drop_fk;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('041-family-bot-juniors');
