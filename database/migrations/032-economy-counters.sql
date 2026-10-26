-- SlumsMS 1.6.0 (Track D, owner 2026-10-26): the day's economy counters survive a restart. Adds one table; no
-- existing table, column or row changes, so an older jar runs fine on a migrated database (it just ignores it). Stop
-- the game and take a backup first, as for every migration here. Applying it twice changes nothing (ledger id
-- '032-economy-counters'). Without it the server still runs: the counters then live in memory only and a restart
-- loses the day's totals (as before).
--
-- economycounters: one row per UTC day and counter (CounterKey: mob_drops_human, mob_drops_bot, npc_sell,
-- quest_reward, npc_buy, job_master_fee, npc_fee, upkeep, fm_tax, trade, fm_sale, maple_leaf_gained,
-- maple_leaf_spent, nx_earned, nx_spent). Amount is the day's total so far (mesos, leaves or NX), UpdatedAt the last
-- write (epoch ms). The server writes about fifteen rows every five minutes and at shutdown, reads today's rows at
-- boot, and deletes rows older than 90 days at the nightly snapshot. No character ids or names.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS economycounters
(
    Day        DATE        NOT NULL,
    CounterKey VARCHAR(32) NOT NULL,
    Amount     BIGINT      NOT NULL DEFAULT 0,
    UpdatedAt  BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (Day, CounterKey)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('032-economy-counters');
