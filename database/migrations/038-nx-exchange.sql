-- SlumsMS 1.6.1 (ticket 113, owner 2026-10-27): the NX <-> meso exchange, an order book "like forex". Adds four
-- tables; no existing table, column or row changes, so an older jar runs fine on a migrated database (it ignores
-- them). The exchange itself stays off unless the server runs with -Dslums.exchange=true. Stop the game and take a
-- backup first, as for every migration here. Applying it twice changes nothing (ledger id '038-nx-exchange').
--
-- exchange_orders: every order, open and closed. Price is mesos per 1,000 NX; NX in whole units. regular_id > 0 marks
--   a regular (then account_id/character_id are 0). status OPEN, FILLED, CANCELLED, EXPIRED, SELF_TRADE or HALTED.
--   Times are epoch ms. request_id: the site's id for the place request (a repeat returns the same order).
-- exchange_fills: every fill (append only). mesos = floor(nx * price / 1000), paid by the buyer, got by the seller.
-- exchange_escrow: every balance the exchange holds, one row per (kind, owner, asset): ESCROW (owner = order id),
--   PAYOUT (owner = account id for NX, character id for mesos; account_id is the owning account), HOUSE (owner =
--   regular id) and FEES. Never negative. Always equal to the ledger summed per bucket (checked at every start).
-- exchange_ledger: every movement of NX or mesos (append only: the server only INSERTs). WALLET legs are the
--   player's accounts.nxCredit (id = account id) or characters.meso (id = character id).
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS exchange_orders
(
    id           BIGINT      NOT NULL,
    request_id   VARCHAR(64) NULL,
    account_id   INT         NOT NULL DEFAULT 0,
    character_id INT         NOT NULL DEFAULT 0,
    regular_id   INT         NOT NULL DEFAULT 0,
    side         VARCHAR(4)  NOT NULL,
    price        BIGINT      NOT NULL,
    nx_total     BIGINT      NOT NULL,
    nx_left      BIGINT      NOT NULL,
    mesos_held   BIGINT      NOT NULL DEFAULT 0,
    placed_at    BIGINT      NOT NULL,
    expires_at   BIGINT      NOT NULL DEFAULT 0,
    seq          BIGINT      NOT NULL,
    status       VARCHAR(16) NOT NULL,
    closed_at    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY request_id (request_id),
    KEY account (account_id, id),
    KEY status (status, seq)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS exchange_fills
(
    id             BIGINT     NOT NULL,
    at             BIGINT     NOT NULL,
    buy_order      BIGINT     NOT NULL,
    sell_order     BIGINT     NOT NULL,
    nx             BIGINT     NOT NULL,
    price          BIGINT     NOT NULL,
    mesos          BIGINT     NOT NULL,
    buyer_regular  TINYINT(1) NOT NULL DEFAULT 0,
    seller_regular TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY at (at),
    KEY buy_order (buy_order),
    KEY sell_order (sell_order)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS exchange_escrow
(
    kind       VARCHAR(8) NOT NULL,
    owner_id   BIGINT     NOT NULL,
    asset      VARCHAR(4) NOT NULL,
    account_id INT        NOT NULL DEFAULT 0,
    amount     BIGINT     NOT NULL DEFAULT 0,
    updated_at BIGINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, owner_id, asset),
    KEY account (account_id),
    CONSTRAINT exchange_escrow_not_negative CHECK (amount >= 0)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS exchange_ledger
(
    id         BIGINT     NOT NULL AUTO_INCREMENT,
    batch_id   BIGINT     NOT NULL,
    at         BIGINT     NOT NULL,
    why        VARCHAR(8) NOT NULL,
    from_kind  VARCHAR(8) NOT NULL,
    from_id    BIGINT     NOT NULL,
    to_kind    VARCHAR(8) NOT NULL,
    to_id      BIGINT     NOT NULL,
    asset      VARCHAR(4) NOT NULL,
    amount     BIGINT     NOT NULL,
    account_id INT        NOT NULL DEFAULT 0,
    order_id   BIGINT     NOT NULL DEFAULT 0,
    fill_id    BIGINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY batch (batch_id),
    KEY order_id (order_id),
    KEY account (account_id)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('038-nx-exchange');
