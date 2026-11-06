-- SlumsMS 1.7.0 P0-1 (RFC 2.9): the operation journal. A transactional step (first user: buyback) writes a STARTED row
-- in the same DB transaction as its claim, and flips it to APPLIED inside the character save that carries the effect.
-- Boot reconciles every row still STARTED (OperationJournal.reconcile). deltas is the JSON of the intended change.
-- Applying twice adds nothing; the table is created only if missing.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS operation_journal (
  id         bigint NOT NULL AUTO_INCREMENT,
  kind       varchar(32) NOT NULL,
  actor_id   int NOT NULL,
  ref_id     bigint NOT NULL,
  deltas     text NOT NULL,
  state      enum('STARTED','APPLIED','ROLLED_BACK') NOT NULL,
  note       varchar(255) NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_operation_journal_state (state)
) ENGINE=InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('066-operation-journal');
