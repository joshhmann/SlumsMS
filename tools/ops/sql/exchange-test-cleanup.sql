-- exchange-test-cleanup.sql: remove the QA checks' test trades from the TEST exchange's price history.
--
-- FOR REVIEW. NOT RUN. TEST ONLY: mapleroot_persist on mapleroot-integration-db (164, port 3307). Never live.
-- Written 2026-10-29 (branch 161-exfix) after the website lane's TEST check (03:03 UTC): NX showed last 1,001,000
-- and change_24h_pct +901, LEAF last 1,000-1,110 against a fair 500,000.
--
-- What is "clearly test": a fill where one side is the QA checks' test regular (ExchangeQa.REGULAR = 9, order
-- request_id 'qa-regular-9-<n>', always buyer_regular = 1). On TEST on 2026-10-29 that is fills 1, 2, 8, 13 (NX: the
-- xc-1 check's 100 NX at 100,000 and 1,001,000) and 3, 4 (LEAF: the xc-2 check's 10 leaves at 1,000). The other side
-- of each is the tester's own order (account 42, request_id 't-<token>').
--
-- What it does: copies those fills into a backup table, then deletes them from exchange_fills. That is all.
--   * exchange_orders, exchange_escrow and exchange_ledger are NOT touched: the ledger is the money trail and the
--     exchange halts at start if balances and ledger sums disagree. The ledger rows keep their fill_id (24 rows for
--     these six fills); a staff reversal of a deleted fill then answers NOT_FOUND, which is what we want.
--   * It never deletes the newest fill (MAX(id)): the next fill id is MAX(id)+1 at start, and reusing a deleted id
--     would collide with the ledger rows that still name it.
--   * The server keeps fills in memory: the history, stats and bot views change only after the next TEST restart
--     (history is rebuilt from exchange_fills at start). Run it with TEST stopped, or restart TEST after.
--   * NOT included: the trader bots' own LEAF fills at 1,080 (fill 9) and 1,110 (fill 16), and their resting bids
--     around 1,000-1,110. Those are real bot-to-bot trades caused by the bug, not test trades; the fixed traders cancel
--     their off-band bids themselves (through the exchange, so the escrow comes back). Optional block at the end.

SELECT DATABASE() INTO @db;
SET @ok = (@db = 'mapleroot_persist');
SELECT IF(@ok, 'database ok: mapleroot_persist', CONCAT('WRONG DATABASE ', IFNULL(@db, 'none'), ': nothing done')) AS guard;

-- 1. what would go (review this output first)
SELECT f.id, f.ticker, FROM_UNIXTIME(f.at / 1000) AS at_utc, f.nx AS qty, f.price, f.mesos,
       b.request_id AS buy_request, s.request_id AS sell_request
FROM exchange_fills f
         JOIN exchange_orders b ON b.id = f.buy_order
         JOIN exchange_orders s ON s.id = f.sell_order
WHERE (b.request_id LIKE 'qa-regular-9-%' OR s.request_id LIKE 'qa-regular-9-%')
  AND f.id < (SELECT m.mx FROM (SELECT MAX(id) AS mx FROM exchange_fills) m)
ORDER BY f.id;

-- 2. the backup table (DDL commits on its own, so it comes before the transaction)
SET @sql = IF(@ok, 'CREATE TABLE IF NOT EXISTS exchange_fills_qa_removed_20261029 LIKE exchange_fills', 'DO 0');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

START TRANSACTION;

-- 3. back up
SET @sql = IF(@ok,
    'INSERT IGNORE INTO exchange_fills_qa_removed_20261029
     SELECT f.* FROM exchange_fills f
              JOIN exchange_orders b ON b.id = f.buy_order
              JOIN exchange_orders s ON s.id = f.sell_order
     WHERE (b.request_id LIKE ''qa-regular-9-%'' OR s.request_id LIKE ''qa-regular-9-%'')
       AND f.id < (SELECT m.mx FROM (SELECT MAX(id) AS mx FROM exchange_fills) m)',
    'DO 0');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 4. delete exactly what was backed up
SET @sql = IF(@ok,
    'DELETE f FROM exchange_fills f
              JOIN exchange_fills_qa_removed_20261029 r ON r.id = f.id',
    'DO 0');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

SELECT IF(@ok, (SELECT COUNT(*) FROM exchange_fills_qa_removed_20261029), 0) AS fills_backed_up_and_deleted;
SELECT ticker, COUNT(*) AS fills_left, MIN(price), MAX(price) FROM exchange_fills GROUP BY ticker;

-- Check the numbers above (expect 6 deleted on 2026-10-29: NX 4 -> 0, LEAF 4 -> 2), then COMMIT, else ROLLBACK.
-- COMMIT;
-- ROLLBACK;

-- Undo after a COMMIT (puts the fills back):
--   INSERT IGNORE INTO exchange_fills SELECT * FROM exchange_fills_qa_removed_20261029;

-- Optional, owner's call: also drop the trader bots' bug-priced LEAF fills (9 at 1,080 and 16 at 1,110; check the ids
-- first, and never MAX(id)). Same backup-then-delete shape:
--   INSERT IGNORE INTO exchange_fills_qa_removed_20261029 SELECT * FROM exchange_fills WHERE id IN (9, 16) AND ticker = 'LEAF';
--   DELETE FROM exchange_fills WHERE id IN (9, 16) AND ticker = 'LEAF';
