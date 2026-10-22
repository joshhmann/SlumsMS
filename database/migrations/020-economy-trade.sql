-- SlumsMS 1.5.0 rc3 (economy lane): the price board, the world's daily economy meters and friend-bot gifts.
-- Adds three tables; no existing table, column or row changes, so an older jar runs fine on a migrated database (it
-- just ignores them). Stop the game and take a backup first, as for every migration here. Applying it twice changes
-- nothing (ledger id '020-economy-trade'). Without it the server still runs: the board, the meters and the gift
-- timers then live in memory only and start again empty after a restart.
--
-- pricesamples: one row per real sale the price board (@price) learns from: player shop and hired merchant sales,
-- single-item meso trades, bot shop sales, direct trades with bots, and (capped) bot purchases that seed the board
-- while players are few. UnitPrice is mesos per unit as paid; NormPrice the clean-equivalent price for an equip
-- (price / its quality next to a clean one), else UnitPrice. PlayerSide = 1 when a real player bought or sold. At is
-- epoch ms. The server reads the last 7 days at its first price lookup and prunes rows older than 14 days.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS pricesamples
(
    Id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ItemId     INT UNSIGNED     NOT NULL,
    UnitPrice  BIGINT           NOT NULL,
    NormPrice  BIGINT           NOT NULL,
    Quantity   INT              NOT NULL DEFAULT 1,
    Source     VARCHAR(16)      NOT NULL,
    PlayerSide TINYINT UNSIGNED NOT NULL DEFAULT 0,
    At         BIGINT           NOT NULL,
    KEY idx_pricesamples_item_at (ItemId, At),
    KEY idx_pricesamples_at (At)
) ENGINE = InnoDB;

-- economyday: one number per meter per server day (botbuy = mesos bots paid real players against the world's daily
-- budget; gifts / giftmesos; tax = FM shop tax; godlies = bot scroll results past the godly line; botshopsales;
-- sinkshop = the cosmetics merchant). Small: a handful of rows a day.
CREATE TABLE IF NOT EXISTS economyday
(
    Day    DATE        NOT NULL,
    Meter  VARCHAR(32) NOT NULL,
    Amount BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (Day, Meter)
) ENGINE = InnoDB;

-- botgifts: when a bot (BotId = its character id) last gave a player (CharacterId) a gift, so a friend bot gives
-- each player at most one gift a week across restarts.
CREATE TABLE IF NOT EXISTS botgifts
(
    BotId       INT UNSIGNED NOT NULL,
    CharacterId INT UNSIGNED NOT NULL,
    GivenAt     BIGINT       NOT NULL,
    Gift        VARCHAR(64)  NOT NULL DEFAULT '',
    PRIMARY KEY (BotId, CharacterId)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('020-economy-trade');
