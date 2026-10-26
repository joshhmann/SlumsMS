package soloMapling.Economy.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The owner's board rules (2026-10-26), checked on every post the site sends: the account exists and owns the
 * character (a real character, not a bot), the item exists and can be traded, qty 1-32,767, price 1-2,147,483,647 and
 * qty x price within one trade's mesos (2,147,483,647), at most
 * {@link #MAX_OPEN} open posts per account (the oldest count), and a post lives at most {@link #LIFE_MS} (3 days)
 * after it was made, whatever expiry the site sent. GMs post like anyone; a post a GM removed stays off. Pure.
 */
public final class BoardRules {

    public static final int MAX_OPEN = 5;
    public static final long LIFE_MS = TimeUnit.DAYS.toMillis(3);
    /** The site's own limits (qty 1-32,767, price 1-2,147,483,647), checked again here: rejected.json is the authority. */
    public static final int MAX_QTY = Short.MAX_VALUE;
    /** A whole post must fit in one trade window's mesos. */
    public static final long MAX_TOTAL = Integer.MAX_VALUE;
    /** A post made this far in the future (clock skew allowed) is refused. */
    static final long FUTURE_SLACK_MS = TimeUnit.MINUTES.toMillis(5);
    static final int BOT_ID_BASE = 1_000_000_000;

    private BoardRules() {
    }

    /** What the rules need from the game: accounts, characters and items. Called off the game thread. */
    public interface Lookup {
        /** The character with this name (any case), or null when there is none. */
        Board.Owner owner(String character);

        boolean accountExists(int account);

        boolean itemExists(int itemId);

        boolean tradeable(int itemId);

        boolean equip(int itemId);
    }

    public record Result(List<Board.Post> open, List<Board.Rejection> rejected) {
    }

    /** When a post closes: the earlier of the site's expiry and 3 days after it was made. */
    public static long expiry(Board.Post p) {
        long cap = p.createdAt() + LIFE_MS;
        return p.expiresAt() > 0 ? Math.min(p.expiresAt(), cap) : cap;
    }

    public static Result validate(List<Board.Post> posts, Lookup lookup, Set<String> removed, long now) {
        List<Board.Rejection> rejected = new ArrayList<>();
        List<Board.Post> ok = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Board.Post p : posts) {
            if (!seen.add(p.id())) {
                rejected.add(new Board.Rejection(p.id(), "duplicate id"));
                continue;
            }
            String why;
            Board.Owner owner = null;
            try {
                why = removed.contains(p.id()) ? "removed by a GM" : null;
                if (why == null) {
                    if (p.account() <= 0 || !lookup.accountExists(p.account())) {
                        why = "no such account";
                    } else if ((owner = lookup.owner(p.character())) == null) {
                        why = "no such character";
                    } else if (owner.account() != p.account()) {
                        why = "character is not on this account";
                    } else if (owner.characterId() >= BOT_ID_BASE) {
                        why = "no such character";
                    }
                }
                if (why == null) {
                    why = itemProblem(p, lookup);
                }
            } catch (RuntimeException e) {
                // the database is busy: the caller keeps the last good board and tries again
                throw new IllegalStateException("board lookup failed: " + e.getMessage(), e);
            }
            if (why == null && p.createdAt() > now + FUTURE_SLACK_MS) {
                why = "created in the future";
            }
            if (why == null && expiry(p) <= now) {
                why = "expired";
            }
            if (why != null) {
                rejected.add(new Board.Rejection(p.id(), why));
                continue;
            }
            ok.add(p.withCharacter(owner).withExpiry(expiry(p)));
        }
        // at most MAX_OPEN per account: the oldest stay, newer ones are refused
        ok.sort(Comparator.comparingLong(Board.Post::createdAt).thenComparing(Board.Post::id));
        Map<Integer, Integer> perAccount = new LinkedHashMap<>();
        List<Board.Post> open = new ArrayList<>();
        for (Board.Post p : ok) {
            int n = perAccount.merge(p.account(), 1, Integer::sum);
            if (n > MAX_OPEN) {
                rejected.add(new Board.Rejection(p.id(), "over " + MAX_OPEN + " open posts"));
            } else {
                open.add(p);
            }
        }
        return new Result(open, rejected);
    }

    private static String itemProblem(Board.Post p, Lookup lookup) {
        if (p.itemId() <= 0 || !lookup.itemExists(p.itemId())) {
            return "no such item";
        }
        if (!lookup.tradeable(p.itemId())) {
            return "item can't be traded";
        }
        if (p.qty() < 1 || p.qty() > MAX_QTY) {
            return "qty must be 1 to " + MAX_QTY;
        }
        if (p.price() <= 0) {
            return "price must be above 0";
        }
        if (p.price() > MAX_TOTAL) {
            return "price must be at most " + MAX_TOTAL;
        }
        if (p.total() > MAX_TOTAL) {
            return "qty x price over " + MAX_TOTAL + " mesos (one trade's limit)";
        }
        return null;
    }
}
