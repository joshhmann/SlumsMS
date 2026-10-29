-- SlumsMS 1.6.1 (ticket 53, promise audit 2026-10-28): the Family EXP / drop perks a character is running (My Drop
-- Rate / EXP 1.5x and 2x, My Party Drop Rate / EXP 2x, Family Bonding) survive a server restart. One row per
-- character: the EXP and drop multipliers (0 = none) and when each ends (epoch ms). The game writes a row when a perk is
-- given, deletes it when the buffs are cleared, and at start deletes ended rows and reads the rest back. No foreign key:
-- a bot never gets a buff, but an alt or a deleted character's row is just ignored once it has ended. An older jar
-- ignores the table, and the game works without it (buffs then end at a restart, as in 1.6.0). Applying it twice
-- changes nothing (ledger id '053-family-buffs').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS family_buffs (
  charid    INT    NOT NULL PRIMARY KEY,
  exp_rate  DOUBLE NOT NULL DEFAULT 0,
  exp_ends  BIGINT NOT NULL DEFAULT 0,
  drop_rate DOUBLE NOT NULL DEFAULT 0,
  drop_ends BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('053-family-buffs');
