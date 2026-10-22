package soloMapling.Economy;

import client.inventory.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 1.5.0 rc3, owner decision 3: one trade window between a real player and a bot, as a state machine over two small
 * ports so it is testable without a server ({@link BotDirectTrade} drives it with the real window).
 * <ul>
 *   <li><b>The player sells</b> ({@link Mode#BOT_BUYS}): the player puts items in; the bot prices them
 *   ({@link PriceBook#fairValue}), refuses what {@link BotBuying} refuses, and posts an opening offer inside its band
 *   ({@link PriceBand}). Mesos posted in a v83 window can only go up, so the bot only raises. It confirms only after
 *   the player locked, and only if the items in the window are the ones it priced and its posted mesos are inside the
 *   band for them (a swap after the offer is refused).</li>
 *   <li><b>The player buys</b> ({@link Mode#BOT_SELLS}): the bot put the item it quoted in chat in the window and asks
 *   its price; it confirms once the player locked with at least that many mesos.</li>
 *   <li><b>Haggling</b>: each chat line is read by {@link HaggleBrain} (rules, then the Decide model) into a move; the
 *   price the bot answers is {@link PriceBand#respond}'s, clamped to the band in code.</li>
 * </ul>
 */
public final class TradeSession {

    public enum Mode { BOT_BUYS, BOT_SELLS }

    public enum State { OPEN, CONFIRMED, CANCELLED }

    /** The trade window as the bot sees it. */
    public interface Window {
        List<Item> partnerItems();

        int partnerMeso();

        boolean partnerLocked();

        /** Mesos the bot has posted. */
        int postedMeso();

        /** Adds mesos to the bot's posted offer (real mesos leave its wallet); false when it cannot. */
        boolean postMore(int delta);

        void say(String fact, String scripted, String mustMention);

        void confirm();

        void cancel();
    }

    /** What the bot's world allows: money, caps, how much it likes the player. */
    public interface Wallet {
        /** Mesos the bot may spend on this trade in all (its purse above its reserve). */
        long spendable();

        /** Reserves this much more of the daily caps (world budget, seller cap); false = over a cap. */
        boolean reserve(long more);

        /** Gives back what was reserved and not paid. */
        void release(long amount);

        boolean friend();
    }

    private final Mode mode;
    private final Window window;
    private final Wallet wallet;
    private final ToLongFunction<Item> fair;
    private final List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());

    private State state = State.OPEN;
    private PriceBand.Band band;
    private long price;          // the bot's standing price (its offer when buying, its ask when selling)
    private int rounds;
    private long reserved;
    private String signature = "";
    private boolean refusedItems;

    public TradeSession(Mode mode, Window window, Wallet wallet, ToLongFunction<Item> fair) {
        this.mode = mode;
        this.window = window;
        this.wallet = wallet;
        this.fair = fair;
    }

    /** The bot is selling {@code item} (already in the window): its opening ask. */
    public void startSelling(Item item, long quotedPrice) {
        long f = Math.max(1, fair.applyAsLong(item)) * Math.max(1, item.getQuantity());
        band = PriceBand.of(PriceBand.Side.BOT_SELLS, f, wallet.friend());
        price = quotedPrice > 0 ? PriceBand.clamp(band, quotedPrice) : PriceBand.opening(band);
        note("SELL ask " + price + " band " + band.low() + ".." + band.high());
        window.say("Tell them the item is in the window and the price is " + PriceBook.shorthand(price)
                        + "; they put the mesos in and lock.",
                "there u go, " + PriceBook.shorthand(price) + " and its yours", PriceBook.shorthand(price));
    }

    /** One poll of the window. */
    public void tick() {
        if (state != State.OPEN) {
            return;
        }
        if (mode == Mode.BOT_BUYS) {
            tickBuying();
        } else {
            tickSelling();
        }
    }

    private void tickBuying() {
        List<Item> items = window.partnerItems();
        String sig = signatureOf(items);
        if (!sig.equals(signature)) {
            signature = sig;
            refusedItems = false;
            if (items.isEmpty()) {
                band = null;
                return;
            }
            long total = 0;
            for (Item it : items) {
                String why = BotBuying.whyNotBuyFromPlayer(it);
                long v = fair.applyAsLong(it);
                if (why != null || v <= 0) {
                    refusedItems = true;
                    note("REFUSE item " + it.getItemId() + ": " + (why != null ? why : "no price"));
                    window.say("Say you do not want that item (" + (why != null ? why : "you cannot price it") + ").",
                            why != null && why.contains("average") ? "nah thats just average gear, only buying good stuff"
                                    : "nah not buying that", null);
                    return;
                }
                total += v * Math.max(1, it.getQuantity());
            }
            PriceBand.Band b = PriceBand.of(PriceBand.Side.BOT_BUYS, total, wallet.friend());
            if (window.postedMeso() > b.high()) {
                note("CANCEL posted " + window.postedMeso() + " over the new band " + b.high() + " (items swapped)");
                window.say("Say the items changed and that is not worth what you put up, so you close the trade.",
                        "u swapped it, not worth that. closing", null);
                cancel();
                return;
            }
            band = b;
            long open = Math.max(window.postedMeso(), PriceBand.opening(band));
            if (!raiseTo(open)) {
                return;
            }
            note("BUY offer " + price + " band " + band.low() + ".." + band.high() + " for " + sig);
            window.say("Offer " + PriceBook.shorthand(price) + " for what they put in; they can lock if ok.",
                    "i can do " + PriceBook.shorthand(price) + " for that", PriceBook.shorthand(price));
            return;
        }
        if (band != null && !refusedItems && window.partnerLocked() && window.postedMeso() > 0
                && window.postedMeso() <= band.high()) {
            note("CONFIRM paying " + window.postedMeso() + " for " + signature);
            state = State.CONFIRMED;
            window.confirm();
        } else if (window.partnerLocked() && (band == null || refusedItems)) {
            note("CANCEL player locked without anything the bot will buy");
            window.say("Say there is nothing in the window you will buy.", "nothing there i want, sry", null);
            cancel();
        }
    }

    private void tickSelling() {
        if (!window.partnerLocked()) {
            return;
        }
        if (window.partnerMeso() >= price) {
            note("CONFIRM selling for " + window.partnerMeso() + " (ask " + price + ")");
            state = State.CONFIRMED;
            window.confirm();
        } else {
            note("CANCEL locked with " + window.partnerMeso() + " < ask " + price);
            window.say("Say they only put in " + PriceBook.shorthand(window.partnerMeso()) + " but the price is "
                    + PriceBook.shorthand(price) + ", so you close it.", "thats short, its "
                    + PriceBook.shorthand(price) + ". closing", PriceBook.shorthand(price));
            cancel();
        }
    }

    /** A chat line from the player in the window (or in chat about this trade). */
    public void onChat(HaggleBrain.Read read) {
        if (state != State.OPEN || band == null || read == null) {
            if (read != null && band == null && mode == Mode.BOT_BUYS && state == State.OPEN) {
                window.say("Tell them to put the item in the window first.", "put it in the window first", null);
            }
            return;
        }
        switch (read.move()) {
            case OFFER -> haggle(read.price());
            case ACCEPT -> window.say("Say ok, they can lock it in.", "kk lock it", null);
            case ASK_PRICE -> window.say("Say your price is " + PriceBook.shorthand(price) + ".",
                    mode == Mode.BOT_BUYS ? "i give " + PriceBook.shorthand(price) : PriceBook.shorthand(price) + " for it",
                    PriceBook.shorthand(price));
            case REFUSE -> window.say("Say no worries.", "np", null);
            case OTHER -> {
            }
        }
    }

    private void haggle(long theirs) {
        PriceBand.Decision d = PriceBand.respond(band, price, theirs, rounds);
        rounds++;
        note("HAGGLE theirs " + theirs + " -> " + d.kind() + " " + d.price() + " (band " + band.low() + ".." + band.high()
                + ", round " + rounds + ")");
        long target = d.price();
        if (mode == Mode.BOT_BUYS) {
            if (!raiseTo(Math.max(price, target))) {
                return;
            }
        } else {
            price = target;
        }
        String p = PriceBook.shorthand(price);
        switch (d.kind()) {
            case ACCEPT -> window.say("Agree to " + p + "; they can lock.", "ok " + p + " deal", p);
            case COUNTER -> window.say("Counter with " + p + ".", "meet me at " + p + "?", p);
            case FINAL -> window.say("Say " + p + " is your last price.", p + " is my max, cant do better",
                    p);
        }
    }

    /** Raises the bot's posted offer to {@code target} (inside the band), reserving the caps for the difference. */
    private boolean raiseTo(long target) {
        long t = Math.min(target, band.high());
        long more = t - window.postedMeso();
        if (more <= 0) {
            price = window.postedMeso();
            return true;
        }
        if (window.postedMeso() + more > wallet.spendable()) {
            note("REFUSE cannot afford " + t + " (spendable " + wallet.spendable() + ")");
            window.say("Say you cannot afford that right now.", "cant afford that rn sry", null);
            price = window.postedMeso();
            return false;
        }
        if (!wallet.reserve(more)) {
            note("REFUSE daily cap reached for " + more);
            window.say("Say you are done buying for today.", "im done buying stuff for today, sry", null);
            price = window.postedMeso();
            return false;
        }
        if (!window.postMore((int) more)) {
            wallet.release(more);
            price = window.postedMeso();
            return false;
        }
        reserved += more;
        price = window.postedMeso();
        return true;
    }

    private void cancel() {
        state = State.CANCELLED;
        window.cancel();
    }

    /** The window closed without the trade going through: give back the caps reserved. */
    public void closedUnsuccessful() {
        if (reserved > 0) {
            wallet.release(reserved);
            reserved = 0;
        }
        if (state == State.OPEN) {
            state = State.CANCELLED;
        }
    }

    static String signatureOf(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            sb.append(it.getItemId()).append('x').append(it.getQuantity()).append('@')
                    .append(System.identityHashCode(it)).append(';');
        }
        return sb.toString();
    }

    private void note(String s) {
        log.add(s);
    }

    public List<String> log() {
        return log;
    }

    public State state() {
        return state;
    }

    public Mode mode() {
        return mode;
    }

    public long price() {
        return price;
    }

    public PriceBand.Band band() {
        return band;
    }
}
