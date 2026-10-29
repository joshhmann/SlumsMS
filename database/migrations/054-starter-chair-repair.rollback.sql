-- Undoes 054 for exactly the characters in starter_chair_054_rows. The game rewrites area_info (new row ids) when a
-- character is saved, so rows are matched by character, not by id: the tip mark moved to 29153 is removed again, and
-- the 29151 'uitip=1.6.0.2' mark is put back where 29151 is still empty. A character who has since got the chairs
-- (29151 'chairs=1.6.0') keeps that mark and the chairs: rolling back takes no items away. Stop the game first.
-- Applying it twice changes nothing.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starter_chair_054_rows (
  area_info_id INT          NOT NULL PRIMARY KEY,
  charid       INT          NOT NULL,
  area         INT          NOT NULL,
  info         VARCHAR(200) NOT NULL,
  action       VARCHAR(8)   NOT NULL,
  KEY idx_charid (charid)
) ENGINE=InnoDB;

DELETE a FROM area_info a JOIN starter_chair_054_rows l ON l.charid = a.charid AND l.action = 'moved'
  WHERE a.area = 29153 AND a.info = l.info;

INSERT INTO area_info (charid, area, info)
  SELECT l.charid, l.area, l.info FROM starter_chair_054_rows l
  WHERE NOT EXISTS (SELECT 1 FROM area_info t WHERE t.charid = l.charid AND t.area = l.area);

DROP TABLE IF EXISTS starter_chair_054_rows;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '054-starter-chair-repair';
