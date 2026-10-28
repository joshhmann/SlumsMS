package soloMapling.Economy.exchange;

import java.util.List;
import java.util.Map;

/**
 * Ticket 113: where the exchange keeps its orders, fills, balances and ledger. {@link #apply} is <b>all or nothing</b>:
 * one database transaction in {@link JdbcExchangeStore}, a copy-and-swap in {@link MemoryExchangeStore}. A failure
 * part way (a lost connection, a crash, a guard that does not hold) leaves the store exactly as it was.
 */
public interface ExchangeStore {

    /** Why a write was refused. Nothing was written. */
    enum Failure {
        /** The account (or one of its characters) is logged in, so its wallet cannot be touched. */
        ONLINE,
        /** The wallet or bucket being debited holds less than the posting. */
        INSUFFICIENT,
        /** The character does not exist or is not on that account. */
        NOT_OWNER,
        /** A request id that was already used for a different order. */
        DUPLICATE,
        /**
         * The live (logged-in) wallet a batch was written for is no longer the live one: the account is changing
         * channel, logging out, or no longer logged in. Nothing was written; the caller gives memory back.
         */
        MOVED,
        /** Anything else: SQL error, lost connection, a simulated crash. */
        ERROR
    }

    final class StoreException extends Exception {
        public final Failure failure;

        public StoreException(Failure failure, String message) {
            super(failure + ": " + message);
            this.failure = failure;
        }

        public StoreException(Failure failure, String message, Throwable cause) {
            super(failure + ": " + message, cause);
            this.failure = failure;
        }
    }

    /** One order row (insert or update by id). {@code status} OPEN or a {@link OrderBook.Reason} name. */
    record OrderRow(long id, String requestId, long account, long character, long regular, OrderBook.Side side,
                    long price, long nxTotal, long nxLeft, long mesosHeld, long placedAt, long expiresAt, long seq,
                    String status, long closedAt) {
        public boolean open() {
            return "OPEN".equals(status);
        }
    }

    /** One fill row. */
    record FillRow(long id, long at, long buyOrder, long sellOrder, long nx, long price, long mesos,
                   boolean buyerRegular, boolean sellerRegular) {
    }

    /**
     * One atomic unit of work. {@code liveAccount} (0 = none) is an account that is logged in and whose live character
     * {@code liveCharacter} the exchange holds locked (its save lock) while this batch is written: that account's wallet
     * legs are written as deltas although it is online, and only while the account is still fully logged in
     * ({@code loggedin = 2}; a channel change or logout in flight refuses with {@link Failure#MOVED}). Its meso legs may
     * only touch {@code liveCharacter}. Every other wallet leg still needs its account logged out.
     */
    record Batch(long id, long at, List<Ledger.Posting> postings, List<OrderRow> orders, List<FillRow> fills,
                 long liveAccount, long liveCharacter) {
        public Batch(long id, long at, List<Ledger.Posting> postings, List<OrderRow> orders, List<FillRow> fills) {
            this(id, at, postings, orders, fills, 0, 0);
        }
    }

    /** What {@link #load} returns at start-up: everything recovery needs. */
    record Loaded(List<OrderRow> openOrders, Map<Ledger.Bucket, Long> balances, Map<Ledger.Bucket, Long> ledgerSums,
                  List<FillRow> recentFills, long maxOrderId, long maxFillId, long maxSeq, long maxBatch) {
    }

    Loaded load(int recentFills) throws StoreException;

    /** Applies a batch atomically, or throws and changes nothing. */
    void apply(Batch batch) throws StoreException;

    /**
     * Moves an account's PAYOUT balances into its wallets (NX into accounts.nxCredit, mesos into each character's
     * meso), in one transaction, only while the account is offline. A wallet is never filled past
     * {@link Integer#MAX_VALUE}; what does not fit stays in PAYOUT. Returns the postings it made (empty when nothing
     * was waiting). Throws {@link Failure#ONLINE} when the account is logged in.
     */
    List<Ledger.Posting> collect(long batchId, long at, long account) throws StoreException;

    /** Accounts with something in PAYOUT. */
    List<Long> accountsWithPayout() throws StoreException;

    /** The order placed with this request id, if any (idempotent places). */
    OrderRow byRequest(String requestId) throws StoreException;

    /** An account's orders (open and recent closed), newest first, at most {@code limit}. */
    List<OrderRow> ordersOf(long account, int limit) throws StoreException;

    /** One stored exchange balance (0 when none). */
    long balance(Ledger.Bucket bucket) throws StoreException;

    /** One order (open or closed), or null. */
    OrderRow order(long id) throws StoreException;

    /** One fill, or null. */
    FillRow fill(long id) throws StoreException;

    /** Every ledger posting made for this fill (FILL rows, and REVERSE rows once a staff reversal ran). */
    List<Ledger.Posting> postingsOfFill(long fillId) throws StoreException;
}
