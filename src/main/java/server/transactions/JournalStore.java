package server.transactions;

import java.sql.SQLException;
import java.util.List;

/**
 * The database side of the operation journal for buyback (P0-1, RFC 2.9). The seam lets the all-or-nothing logic and
 * the boot reconcile be tested with fault injection and no live DB; {@link JdbcJournalStore} is the real one.
 */
public interface JournalStore {

    /** A journal row that is still STARTED. */
    record OpenRow(long id, String kind, int actorId, long refId) {
    }

    /**
     * One DB transaction: set {@code transactions.buyback_used = 1} only if it was 0, and write a STARTED BUYBACK
     * journal row. Returns the journal id, or -1 when the transaction was already bought back (nothing changed).
     * Throws when the database failed (nothing changed).
     */
    long claimBuyback(int playerId, int transactionId, String deltasJson) throws SQLException;

    /**
     * One DB transaction: set {@code buyback_used = 0} and mark the journal row ROLLED_BACK. Fails (and changes
     * nothing) if the row is no longer STARTED.
     */
    void releaseBuyback(long journalId, int transactionId, String note) throws SQLException;

    /**
     * One DB transaction: write a STARTED journal row of {@code kind} (merchant close or buy, P0-2) and nothing else.
     * Returns the journal id. Throws when the database failed (nothing changed).
     */
    long startRow(String kind, int actorId, long refId, String deltasJson) throws SQLException;

    /**
     * One DB transaction: mark the STARTED row ROLLED_BACK. For MERCHANT_CLOSE it also clears the actor's HasMerchant
     * flag (their merchant is gone, so the flag must not stay set). Fails (and changes nothing) if the row is no
     * longer STARTED.
     */
    void rollBackRow(long journalId, String kind, int actorId, String note) throws SQLException;

    /** Every row whose state is STARTED. */
    List<OpenRow> openRows() throws SQLException;
}
