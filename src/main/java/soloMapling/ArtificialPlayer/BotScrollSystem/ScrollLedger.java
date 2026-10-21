package soloMapling.ArtificialPlayer.BotScrollSystem;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ticket 11 "scroll results appear in the ledger": what bots did with scrolls since boot, shown by {@code !botyield}
 * next to ticket 09's NPC shop line (ticket 15's ledger read-out). Every roll is also logged as a {@code [Scroll]}
 * line. Where the mesos went: NPC shops and bot FM shops are sinks (the mesos leave the world), real players'
 * shops are transfers to that player (inside ticket 56A's caps).
 */
public final class ScrollLedger {

    public enum Source { NPC, BOT_SHOP, PLAYER_SHOP }

    static final AtomicLong[] bought = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    static final AtomicLong[] mesos = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    static final AtomicLong success = new AtomicLong();
    static final AtomicLong fail = new AtomicLong();
    static final AtomicLong curse = new AtomicLong();
    static final AtomicLong whiteUsed = new AtomicLong();
    static final AtomicLong destroyedValue = new AtomicLong();
    static final AtomicLong megaphones = new AtomicLong();
    static final AtomicLong worn = new AtomicLong();
    static final AtomicLong listed = new AtomicLong();
    static final AtomicLong listedSold = new AtomicLong();
    static final AtomicLong listedSoldMesos = new AtomicLong();
    static final AtomicLong dropped = new AtomicLong();

    private ScrollLedger() {
    }

    public static void bought(Source source, long price) {
        bought[source.ordinal()].incrementAndGet();
        mesos[source.ordinal()].addAndGet(price);
    }

    static void rolled(BotScrolling.Outcome outcome, boolean white, Integer valueLost) {
        switch (outcome) {
            case SUCCESS -> success.incrementAndGet();
            case FAIL -> fail.incrementAndGet();
            case CURSE -> {
                curse.incrementAndGet();
                if (valueLost != null) {
                    destroyedValue.addAndGet(valueLost);
                }
            }
        }
        if (white) {
            whiteUsed.incrementAndGet();
        }
    }

    public static long rolls() {
        return success.get() + fail.get() + curse.get();
    }

    public static List<String> report() {
        long n = rolls();
        return List.of(
                String.format("Bot scrolling (since boot): bought %,d from NPCs for %,d (sink), %,d from bot shops"
                                + " for %,d (sink), %,d from players for %,d (to players)",
                        bought[0].get(), mesos[0].get(), bought[1].get(), mesos[1].get(), bought[2].get(),
                        mesos[2].get()),
                String.format("  %,d scrolls used: %,d passed, %,d failed, %,d boomed (%s), %,d white scrolls;"
                                + " %,d mesos of gear destroyed; %,d megaphones",
                        n, success.get(), fail.get(), curse.get(),
                        n == 0 ? "-" : String.format("%.1f%% passed", 100.0 * success.get() / n), whiteUsed.get(),
                        destroyedValue.get(), megaphones.get()),
                String.format("  results: %,d put on, %,d listed in FM shops (%,d sold for %,d), %,d junk dropped",
                        worn.get(), listed.get(), listedSold.get(), listedSoldMesos.get(), dropped.get()));
    }

    static void resetForTest() {
        for (int i = 0; i < 3; i++) {
            bought[i].set(0);
            mesos[i].set(0);
        }
        for (AtomicLong a : List.of(success, fail, curse, whiteUsed, destroyedValue, megaphones, worn, listed,
                listedSold, listedSoldMesos, dropped)) {
            a.set(0);
        }
    }
}
