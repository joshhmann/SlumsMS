package soloMapling.Economy.exchange;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ticket 113: every NX and meso the exchange touches moves as a {@link Posting} from one {@link Bucket} to another, and
 * every posting becomes one append-only row of {@code exchange_ledger}. The exchange's own balances (escrow, payout,
 * house) are the ledger summed per bucket; {@link Exchange#reconcile} checks the stored balances against that sum.
 * <pre>
 *   WALLET   the player's own money: NX = accounts.nxCredit (id = account id), MESO = characters.meso (id = character id).
 *            Touched only while the whole account is offline (checked under a row lock in the same transaction).
 *   ESCROW   what an open order holds (id = order id): NX for a sell, mesos for a buy. Not spendable.
 *   PAYOUT   settled money waiting to reach a wallet (NX: id = account id; MESO: id = character id).
 *   HOUSE    a regular's float (id = regular id); see {@link Regulars}. Funded only from a real (staff) wallet.
 *   FEES     mesos taken as fees (id 0); a meso sink that stays on the books. 0 while the fee is 0 (the default).
 * </pre>
 */
public final class Ledger {

    private Ledger() {
    }

    public enum Asset {
        NX, MESO
    }

    public enum Kind {
        WALLET, ESCROW, PAYOUT, HOUSE, FEES
    }

    public record Bucket(Kind kind, long id, Asset asset) {
        public static Bucket wallet(Asset a, long id) {
            return new Bucket(Kind.WALLET, id, a);
        }

        public static Bucket escrow(Asset a, long orderId) {
            return new Bucket(Kind.ESCROW, orderId, a);
        }

        public static Bucket payout(Asset a, long id) {
            return new Bucket(Kind.PAYOUT, id, a);
        }

        public static Bucket house(Asset a, long regularId) {
            return new Bucket(Kind.HOUSE, regularId, a);
        }

        public static Bucket fees() {
            return new Bucket(Kind.FEES, 0, Asset.MESO);
        }

        @Override
        public String toString() {
            return kind + ":" + asset + ":" + id;
        }
    }

    /** Why a posting happened. */
    public enum Why {
        PLACE, FILL, RELEASE, COLLECT, FUND
    }

    /**
     * {@code amount} of one asset moves {@code from} -> {@code to} (same asset on both sides). {@code account} is the
     * player account whose wallet a WALLET leg touches (0 otherwise); {@code orderId}/{@code fillId} 0 when none.
     */
    public record Posting(Why why, Bucket from, Bucket to, long amount, long account, long orderId, long fillId) {
        public Posting {
            if (amount <= 0) {
                throw new IllegalArgumentException("posting amount must be positive: " + amount);
            }
            if (from.asset() != to.asset()) {
                throw new IllegalArgumentException("asset mismatch " + from + " -> " + to);
            }
            if (from.equals(to)) {
                throw new IllegalArgumentException("posting to itself " + from);
            }
            if ((from.kind() == Kind.WALLET || to.kind() == Kind.WALLET) && account <= 0) {
                throw new IllegalArgumentException("a wallet leg needs its account");
            }
        }

        public Asset asset() {
            return from.asset();
        }
    }

    /** One stored ledger row. */
    public record Row(long id, long at, long batch, Posting posting) {
    }

    /** Sums postings per non-wallet bucket (in minus out). */
    public static Map<Bucket, Long> balances(List<Posting> postings) {
        Map<Bucket, Long> out = new HashMap<>();
        for (Posting p : postings) {
            if (p.from().kind() != Kind.WALLET) {
                out.merge(p.from(), -p.amount(), Long::sum);
            }
            if (p.to().kind() != Kind.WALLET) {
                out.merge(p.to(), p.amount(), Long::sum);
            }
        }
        out.values().removeIf(v -> v == 0);
        return out;
    }
}
