package soloMapling.Economy.board;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 1.6.0 site write features (owner, 2026-10-26): the Buy/Sell board and price alerts the website collects. The site
 * writes {@code posts.json} and {@code alerts.json}; the server checks them, answers with bots and notes, and writes
 * {@code board.json}, {@code rejected.json}, {@code removed.json} and {@code alerts-fired.json}. Nothing here moves an
 * item or a meso: a player still trades in the Free Market as usual. The file contract is "Site board contract" in
 * docs/design/track-d-plan.md. These are the shared shapes.
 */
public final class Board {

    private Board() {
    }

    /** WTB: the poster wants to buy; WTS: the poster wants to sell. */
    public enum Kind {
        WTB, WTS;

        static Kind parse(Object o) {
            if (o == null) {
                return null;
            }
            try {
                return valueOf(String.valueOf(o).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** An alert fires when the cheapest FM listing is at or below ({@code BELOW}) or at or above ({@code ABOVE}) its price. */
    public enum Dir {
        BELOW, ABOVE;

        static Dir parse(Object o) {
            if (o == null) {
                return null;
            }
            try {
                return valueOf(String.valueOf(o).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * One open post. {@code price} is mesos per unit. {@code account} is the game account id (0 for a bot's post);
     * {@code characterId} is the poster's character id once the rules have found it (0 before). Times are epoch
     * milliseconds.
     */
    public record Post(String id, int account, String character, int characterId, Kind kind, int itemId, int qty,
                       long price, long createdAt, long expiresAt, boolean bot) {

        public long total() {
            return price * (long) qty;
        }

        Post withCharacter(Owner owner) {
            return new Post(id, account, owner.name(), owner.characterId(), kind, itemId, qty, price, createdAt,
                    expiresAt, bot);
        }

        Post withExpiry(long at) {
            return new Post(id, account, character, characterId, kind, itemId, qty, price, createdAt, at, bot);
        }
    }

    /** One price alert. {@code armedAt} (epoch ms, 0 when the site did not send it) is when the site last armed it. */
    public record Alert(String id, int account, int itemId, Dir dir, long price, boolean armed, long armedAt) {
    }

    /** One account's watchlist and alerts, as the site sent them. */
    public record AccountAlerts(int account, List<Integer> watchlist, List<Alert> alerts) {
    }

    /** Who owns a character: its id, its account and its name as the game spells it. */
    public record Owner(int characterId, int account, String name) {
    }

    /** A post the server would not show, and why (the site closes it or tells the poster). */
    public record Rejection(String id, String reason) {
    }

    static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }
}
