package soloMapling.Economy.board;

import soloMapling.Economy.MegaBuyDesk;
import soloMapling.Economy.PriceBand;
import soloMapling.Economy.PriceBook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Bots answer player posts (owner, 2026-10-26: "bots that own the item, or have the mesos, answer with one offer").
 * The megaphone buy flow's rules, reused: {@link MegaBuyDesk#plan} prices a bot selling (its sell band, the player's
 * price taken when the band allows, nothing when the player is far below), {@link PriceBand}'s buy band prices a bot
 * buying, and a {@link MegaBuyDesk.Limits} keeps the per-account, server-wide and per-bot rests. On top: one offer per
 * post per bot and {@link BoardState#OFFER_CAP} per post. Pure: {@link SiteBoard} sends what this decides.
 */
public final class BoardOffers {

    private BoardOffers() {
    }

    /** A bot that could buy from a WTS poster. {@code unitValue}: what it pays at most per unit (fair, capped). */
    public record Buyer(int botId, String botName, int channel, long spendable, long unitValue) {
    }

    /**
     * One offer to send. {@code botSells}: the bot sells (a WTB post) and {@code stock} is what it sells; else the bot
     * buys. {@code price}: the whole price in mesos; {@code qty}: units.
     */
    public record Offer(Board.Post post, int botId, String botName, int channel, boolean botSells, long price, int qty,
                        int rounds, MegaBuyDesk.Stock stock) {
    }

    /** The megaphone flow's "far below" share: a player under this much of the bot's floor gets no answer. */
    static double far() {
        return Double.parseDouble(System.getProperty("solomapling.megaBuyFarBelow", "0.6"));
    }

    /**
     * The next offer for this post, or null: a bot that has not offered yet, inside the limits, with a price the post
     * is not far from. Records the offer in {@code state} and the limits.
     */
    public static Offer next(Board.Post post, BoardWorld world, BoardState state, MegaBuyDesk.Limits limits) {
        if (post.bot() || state.offers(post.id()) >= BoardState.OFFER_CAP) {
            return null;
        }
        long now = world.now();
        Random rnd = new Random((long) (world.roll() * Long.MAX_VALUE));
        if (post.kind() == Board.Kind.WTB) {
            List<MegaBuyDesk.Stock> sellers = new ArrayList<>();
            for (MegaBuyDesk.Stock s : world.sellers(post.itemId(), post.characterId())) {
                if (s.itemId() == post.itemId() && s.qty() >= post.qty() && !state.offered(post.id(), s.botId())) {
                    sellers.add(s);
                }
            }
            Collections.shuffle(sellers, rnd);
            // the closest stack first: a bot with exactly what was asked for beats one with a huge pile
            sellers.sort((a, b) -> Integer.compare(a.qty() - post.qty(), b.qty() - post.qty()));
            for (MegaBuyDesk.Stock s : sellers) {
                MegaBuyDesk.Plan plan = MegaBuyDesk.plan(s, post.price() * (long) s.qty(), false);
                if (plan == null || !limits.tryTake(post.account(), s.botId(), now)) {
                    continue;
                }
                if (!state.takeOffer(post.id(), s.botId(), now)) {
                    continue;
                }
                return new Offer(post, s.botId(), s.botName(), s.channel(), true, plan.price(), s.qty(), plan.rounds(),
                        s);
            }
            return null;
        }
        List<Buyer> buyers = new ArrayList<>();
        for (Buyer b : world.buyers(post.itemId(), post.qty(), post.characterId())) {
            if (b.unitValue() > 0 && !state.offered(post.id(), b.botId())) {
                buyers.add(b);
            }
        }
        Collections.shuffle(buyers, rnd);
        for (Buyer b : buyers) {
            Long price = buyPrice(b.unitValue() * (long) post.qty(), post.total());
            if (price == null || price > b.spendable() || !limits.tryTake(post.account(), b.botId(), now)) {
                continue;
            }
            if (!state.takeOffer(post.id(), b.botId(), now)) {
                continue;
            }
            return new Offer(post, b.botId(), b.botName(), b.channel(), false, price, post.qty(),
                    price < post.total() ? 1 : 0, null);
        }
        return null;
    }

    /**
     * What a bot pays for a post asking {@code asking} for goods worth {@code value} to it: the asking price when its
     * buy band allows, its top price when the ask is above it but close, null when the ask is far above (the same
     * "far" share as the megaphone flow, the other way round).
     */
    static Long buyPrice(long value, long asking) {
        PriceBand.Band band = PriceBand.of(PriceBand.Side.BOT_BUYS, Math.max(1, value), false);
        if (asking <= band.high()) {
            return Math.max(1, asking);
        }
        if (asking * far() > band.high()) {
            return null;
        }
        return band.high();
    }

    // ---- the words ----

    private static final List<String> SELL_WHISPER = List.of(
            "saw ur board post, i have %1$s. %2$s? %3$s, trade me",
            "hey i got %1$s for ur wtb, %2$s. %3$s, trade me",
            "re ur board post: %1$s, i can do %2$s. %3$s");
    private static final List<String> SELL_NOTE = List.of(
            "saw ur board post. i have %1$s, %2$s. whisper me when ur on",
            "re ur wtb on the board: got %1$s for %2$s. find me in the fm");
    private static final List<String> BUY_WHISPER = List.of(
            "saw ur board post, ill buy ur %1$s for %2$s. %3$s, trade me",
            "re ur wts: ill take %1$s for %2$s. %3$s");
    private static final List<String> BUY_NOTE = List.of(
            "saw ur board post. ill buy ur %1$s for %2$s, whisper me when ur on",
            "re ur wts on the board: %2$s for %1$s? find me in the fm");

    /** The line the bot sends: a whisper when the poster is on ({@code where}: how to find the bot), else a note. */
    public static String line(Offer o, String itemName, boolean whisper, String where, BoardWorld world) {
        List<String> pool = o.botSells() ? (whisper ? SELL_WHISPER : SELL_NOTE) : (whisper ? BUY_WHISPER : BUY_NOTE);
        String name = (itemName == null || itemName.isBlank() ? "that item" : itemName.toLowerCase(Locale.ROOT))
                + (o.qty() > 1 ? " x" + o.qty() : "");
        String w = where == null || where.isBlank() ? "im in the fm" : where;
        return String.format(Locale.ROOT, pool.get(world.between(0, pool.size() - 1)), name,
                PriceBook.shorthand(o.price()), w);
    }
}
