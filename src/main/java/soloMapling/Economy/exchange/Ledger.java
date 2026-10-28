package soloMapling.Economy.exchange;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ticket 113: every NX and meso the exchange touches moves as a {@link Posting} from one {@link Bucket} to another, and
 * every posting becomes one append-only row of {@code exchange_ledger}. The exchange's own balances (escrow, payout,
 * house) are the ledger summed per bucket; {@link Exchange#reconcile} checks the stored balances against that sum.
 * <pre>
 *   WALLET   the player's own money: NX = accounts.nxCredit (id = account id), MESO = characters.meso (id = character id),
 *            an item asset = that character's inventory (id = character id; only ever through the live character).
 *            Touched only while the whole account is offline (checked under a row lock in the same transaction).
 *   ESCROW   what an open order holds (id = order id): NX (or the ticker's item) for a sell, mesos for a buy.
 *   PAYOUT   settled money waiting to reach a wallet (NX: id = account id; MESO and items: id = character id).
 *   HOUSE    a regular's float (id = regular id); see {@link Regulars}. Funded only from a real (staff) wallet.
 *   FEES     mesos taken as fees (id 0); a meso sink that stays on the books.
 *   FEEHOLD  a buy order's reserve for the buyer's own fee (id = order id, mesos only): taken at place with the escrow,
 *            paid into FEES fill by fill, and what is left goes back with the escrow when the order closes.
 *   BOT      a trader bot's own bags and mesos (id = the bot's character id; 1.6.1 volatility): like WALLET, outside
 *            the exchange's books (not summed, no database row). Bots live in memory and save through BotStore, so
 *            the exchange only ever moves what {@code BotTraderWallet} took out of (or gives into) the bot itself.
 * </pre>
 */
public final class Ledger {

    private Ledger() {
    }

    /**
     * What a bucket holds. NX and MESO as before; the rest are the item tickers (owner 2026-10-28): each is one item id,
     * counted in whole items. An item WALLET is that character's inventory (id = character id) and moves only through
     * the live character in game ({@link LiveWallet}); item PAYOUT is kept per character (the buyer's).
     */
    public enum Asset {
        NX(0), MESO(0), LEAF(4001126), CHAOS(2049100), WS(2340000), ELIXIR(2000005);

        private final int itemId;

        Asset(int itemId) {
            this.itemId = itemId;
        }

        /** The item id of an item asset; 0 for NX and MESO. */
        public int itemId() {
            return itemId;
        }

        public boolean item() {
            return itemId > 0;
        }

        /** A payout of this asset is kept per character (mesos, items) rather than per account (NX). */
        public boolean perCharacter() {
            return this != NX;
        }
    }

    public enum Kind {
        WALLET, ESCROW, PAYOUT, HOUSE, FEES, FEEHOLD, BOT;

        /** Outside the exchange's books: a player's wallet or a trader bot's own holdings. */
        public boolean external() {
            return this == WALLET || this == BOT;
        }
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

        /** A trader bot's own holdings (outside the books; see {@link Kind#BOT}). */
        public static Bucket bot(Asset a, long characterId) {
            return new Bucket(Kind.BOT, characterId, a);
        }

        public static Bucket feeHold(long orderId) {
            return new Bucket(Kind.FEEHOLD, orderId, Asset.MESO);
        }

        public static Bucket fees() {
            return new Bucket(Kind.FEES, 0, Asset.MESO);
        }

        @Override
        public String toString() {
            return kind + ":" + asset + ":" + id;
        }
    }

    /** Why a posting happened. REVERSE: a staff reversal of one fill (a compensating posting; nothing is edited). */
    public enum Why {
        PLACE, FILL, RELEASE, COLLECT, FUND, REVERSE
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
            if (!p.from().kind().external()) {
                out.merge(p.from(), -p.amount(), Long::sum);
            }
            if (!p.to().kind().external()) {
                out.merge(p.to(), p.amount(), Long::sum);
            }
        }
        out.values().removeIf(v -> v == 0);
        return out;
    }
}
