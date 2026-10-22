package soloMapling.Gachapon;

import java.util.concurrent.atomic.AtomicLong;

/** 1.5.0: gachapon counters since the server started, for {@code !botyield} and {@code !botwealth}. */
public final class GachaStats {

    static final AtomicLong[] playerWins = new AtomicLong[GachaPools.Tier.values().length];
    static final AtomicLong[] botWins = new AtomicLong[GachaPools.Tier.values().length];
    static final AtomicLong botTickets = new AtomicLong();
    static final AtomicLong botTicketMesos = new AtomicLong();

    static {
        for (int i = 0; i < playerWins.length; i++) {
            playerWins[i] = new AtomicLong();
            botWins[i] = new AtomicLong();
        }
    }

    private GachaStats() {
    }

    static void won(boolean bot, GachaPools.Tier tier) {
        (bot ? botWins : playerWins)[tier.ordinal()].incrementAndGet();
    }

    static void botTicket(long mesos) {
        botTickets.incrementAndGet();
        botTicketMesos.addAndGet(mesos);
    }

    public static long botTicketMesos() {
        return botTicketMesos.get();
    }

    /** "players 40 rolls (28/10/2/0), bots 12 tickets for 12,000,000 mesos (9/3/0/0)" */
    public static String line() {
        return String.format("gachapon: players %d rolls (%s), bots %d tickets for %,d mesos (%s) [common/uncommon/rare/jackpot]; %s",
                sum(playerWins), split(playerWins), botTickets.get(), botTicketMesos.get(), split(botWins),
                GachaAnnouncer.get().status());
    }

    private static long sum(AtomicLong[] a) {
        long s = 0;
        for (AtomicLong x : a) {
            s += x.get();
        }
        return s;
    }

    private static String split(AtomicLong[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : "/").append(a[i].get());
        }
        return sb.toString();
    }
}
