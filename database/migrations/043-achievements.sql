-- SlumsMS 1.6.1 (ticket 93, achievements and medals): durable progress per character and goal. One table, no foreign
-- key: a bot has no characters row (its id is 1,000,000,000 + its botstore id) and earns the same goals. state is
-- 0 progress, 1 earned (medal owed), 2 granted (medal in the bag). A goal's medal is handed over once: the row goes to
-- 1 first, then the item, then 2. An older jar ignores the table. Stop the game and take a backup first, as for every
-- migration here. Applying it twice changes nothing (ledger id '043-achievements').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS character_achievements (
  charid      INT         NOT NULL,
  achievement VARCHAR(40) NOT NULL,
  progress    INT         NOT NULL DEFAULT 0,
  state       TINYINT     NOT NULL DEFAULT 0,
  earned_at   BIGINT      NOT NULL DEFAULT 0,
  granted_at  BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (charid, achievement),
  KEY character_achievements_recent (state, earned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('043-achievements');
