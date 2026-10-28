-- Stop the game first and take a backup. Undoes 041: bots stop being family juniors (their family_character and
-- family_entitlement rows are deleted, with any other row whose character no longer exists, so the keys can come
-- back), then the foreign keys recorded in family_041_fks are put back as they were. A player whose junior was a bot
-- keeps their family and reputation; the pedigree just loses the bot. Applying it twice changes nothing.
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

DELETE FROM family_character WHERE cid NOT IN (SELECT id FROM characters);
DELETE FROM family_entitlement WHERE charid NOT IN (SELECT id FROM characters)
   AND EXISTS (SELECT 1 FROM family_041_fks WHERE table_name = 'family_entitlement');

SET @ddl = (SELECT CONCAT('ALTER TABLE family_character ADD CONSTRAINT `', f.constraint_name, '` FOREIGN KEY (`',
                          f.column_name, '`) REFERENCES `', f.ref_table, '` (`', f.ref_column, '`) ON DELETE ',
                          f.delete_rule)
              FROM family_041_fks f
             WHERE f.table_name = 'family_character'
               AND NOT EXISTS (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k
                                WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'family_character'
                                  AND k.COLUMN_NAME = f.column_name AND k.REFERENCED_TABLE_NAME = f.ref_table)
             LIMIT 1);
SET @ddl = IFNULL(@ddl, 'DO 0');
PREPARE add_fk FROM @ddl;
EXECUTE add_fk;
DEALLOCATE PREPARE add_fk;

SET @ddl = (SELECT CONCAT('ALTER TABLE family_entitlement ADD CONSTRAINT `', f.constraint_name, '` FOREIGN KEY (`',
                          f.column_name, '`) REFERENCES `', f.ref_table, '` (`', f.ref_column, '`) ON DELETE ',
                          f.delete_rule)
              FROM family_041_fks f
             WHERE f.table_name = 'family_entitlement'
               AND NOT EXISTS (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k
                                WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'family_entitlement'
                                  AND k.COLUMN_NAME = f.column_name AND k.REFERENCED_TABLE_NAME = f.ref_table)
             LIMIT 1);
SET @ddl = IFNULL(@ddl, 'DO 0');
PREPARE add_fk FROM @ddl;
EXECUTE add_fk;
DEALLOCATE PREPARE add_fk;

DELETE FROM mapleroot_integration_migrations WHERE migration_id = '041-family-bot-juniors';
