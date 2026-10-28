-- SlumsMS 1.6.1 (owner request via the site session, "most hunted monsters"): the site's mob-kill counter survives a
-- restart. One row per UTC hour and mob id; kills are players and bots together (no split). The game flushes the last
-- two hours every 5 minutes and prunes rows older than 30 days; it reads the last 7 days back at start. Read-only
-- telemetry: nothing else uses the table, an older jar ignores it, and the game works without it (the counter then just
-- starts from zero after a restart). Applying it twice changes nothing (ledger id '047-mob-kills').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS site_mob_kills (
  hour_start BIGINT NOT NULL,
  mob        INT    NOT NULL,
  kills      INT    NOT NULL DEFAULT 0,
  PRIMARY KEY (hour_start, mob)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('047-mob-kills');
