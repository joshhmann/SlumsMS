package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 (owner: the trader bots are too few to keep a book): a share of the bots in the game are traders
 * ({@code -Dslums.exchange.traders.pct}, 25..50), all four Bartle types take part, and the old fixed count is what
 * happens with no share (off by default: 1.6.1's 16 traders).
 */
class MarketTradersShareTest {

    static final String[] TYPES = {"KILLER", "ACHIEVER", "EXPLORER", "SOCIALIZER"};

    static List<MarketTraders.Candidate> cands(long from, int n) {
        List<MarketTraders.Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new MarketTraders.Candidate(from + i, TYPES[i % TYPES.length]));
        }
        return out;
    }

    static MarketConfig share(int pct, int count) {
        return MarketConfig.allOn().withTraders(count, pct, false);
    }

    @Test
    void aShareOfTheBotsInTheGameAreTraders() {
        List<MarketTraders.Candidate> online = cands(1_000_000_000L, 1_000);
        MarketConfig cfg = share(35, MarketConfig.MAX_TRADERS);
        assertEquals(350, MarketTraders.target(online, cfg));
        List<MarketTraders.Trader> t = MarketTraders.keepAndFill(List.of(), online, MarketTraders.target(online, cfg),
                true);
        assertEquals(350, t.size());
        Set<Long> ids = online.stream().map(MarketTraders.Candidate::id).collect(Collectors.toSet());
        assertTrue(t.stream().allMatch(x -> ids.contains(x.id())), "every trader is a bot in the game");
    }

    @Test
    void theShareFollowsTheRosterButTheCountIsAHardCap() {
        MarketConfig pct = share(35, MarketConfig.MAX_TRADERS);
        assertEquals(14, MarketTraders.target(cands(1, 40), pct));
        assertEquals(0, MarketTraders.target(List.of(), pct), "nobody online, nobody trades");
        assertEquals(1, MarketTraders.target(cands(1, 2), pct), "a tiny roster still gives one trader");
        assertEquals(50, MarketTraders.target(cands(1, 1_000), share(35, 50)), "the count caps the share");
        List<MarketTraders.Candidate> thousand = cands(1, 1_000);
        assertEquals(350, MarketTraders.keepAndFill(List.of(), thousand, MarketTraders.target(thousand, pct), true)
                .size(), "35% of 1,000 bots trade");
        assertEquals(1_000, MarketTraders.keepAndFill(List.of(), thousand, 1_000, true).size(),
                "the count alone can name them all");
    }

    @Test
    void fiftyPercentOfThreeThousandBots() {
        List<MarketTraders.Candidate> online = cands(1_000_000_000L, 3_000);
        assertEquals(1_050, MarketTraders.target(online, share(35, MarketConfig.MAX_TRADERS)));
        assertEquals(1_500, MarketTraders.target(online, share(50, MarketConfig.MAX_TRADERS)));
        assertEquals(750, MarketTraders.target(online, share(25, MarketConfig.MAX_TRADERS)));
    }

    @Test
    void offMeansTheFixedCountOf161() {
        MarketConfig off = share(0, 16);
        List<MarketTraders.Candidate> online = cands(1_000_000_000L, 3_000);
        assertEquals(16, MarketTraders.target(online, off), "no share: 1.6.1's fixed count");
        Map<Long, String> type = online.stream().collect(Collectors.toMap(MarketTraders.Candidate::id,
                MarketTraders.Candidate::bartle));
        List<MarketTraders.Trader> t = MarketTraders.keepAndFill(List.of(), online, 16);
        assertEquals(16, t.size());
        assertTrue(t.stream().allMatch(x -> "KILLER".equals(type.get(x.id()))
                || "ACHIEVER".equals(type.get(x.id()))), "1.6.1 kept the roster to killers and achievers");
    }

    @Test
    void theShareIsClampedTo25Through50() {
        assertEquals(25, share(1, 16).tradersPct());
        assertEquals(25, share(24, 16).tradersPct());
        assertEquals(35, share(35, 16).tradersPct());
        assertEquals(50, share(80, 16).tradersPct());
        assertEquals(0, share(-5, 16).tradersPct(), "0 and less stay off");
    }

    @Test
    void everyBartleTypeMakesTraders() {
        List<MarketTraders.Candidate> online = new ArrayList<>();
        online.add(new MarketTraders.Candidate(1_000_000_001L, "KILLER"));
        online.add(new MarketTraders.Candidate(1_000_000_002L, "ACHIEVER"));
        online.add(new MarketTraders.Candidate(1_000_000_003L, "EXPLORER"));
        online.add(new MarketTraders.Candidate(1_000_000_004L, "SOCIALIZER"));
        online.add(new MarketTraders.Candidate(1_000_000_005L, ""));
        List<MarketTraders.Trader> all = MarketTraders.keepAndFill(List.of(), online, 5, true);
        assertEquals(online.stream().map(MarketTraders.Candidate::id).collect(Collectors.toSet()),
                all.stream().map(MarketTraders.Trader::id).collect(Collectors.toSet()),
                "every bot in the game trades when the share covers all of them");
        List<MarketTraders.Trader> fixed = MarketTraders.keepAndFill(List.of(), online, 5);
        assertEquals(Set.of(1_000_000_001L, 1_000_000_002L),
                fixed.stream().map(MarketTraders.Trader::id).collect(Collectors.toSet()),
                "without the share, explorers and socialisers stay out, as in 1.6.1");
    }

    @Test
    void everyArchetypeShowsUpOnABigRoster() {
        MarketConfig cfg = share(25, MarketConfig.MAX_TRADERS);
        List<MarketTraders.Candidate> online = cands(1, 3_000);
        int n = MarketTraders.target(online, cfg);
        List<MarketTraders.Trader> t = MarketTraders.keepAndFill(List.of(), online, n, true);
        assertEquals(n, t.size());
        assertEquals(Set.of(TraderBrain.Archetype.MOMENTUM, TraderBrain.Archetype.PANIC,
                        TraderBrain.Archetype.WHALE, TraderBrain.Archetype.VALUE, TraderBrain.Archetype.MAKER),
                t.stream().map(MarketTraders.Trader::archetype).collect(Collectors.toSet()),
                "explorers and socialisers quote too, not only the herd");
        // 25% of 3,000 bots acting every 4..12 minutes: more than one trader act a second, all day
        assertTrue(n / (8.0 * 60.0) > 1.0, "trader acts per second at the lowest share: " + n / 480.0);
    }

    @Test
    void defaultsAreOffTodayLike161() {
        System.clearProperty(MarketConfig.TRADERS_PCT);
        System.clearProperty(MarketConfig.TRADERS_COUNT);
        System.clearProperty(MarketConfig.TRADERS_NX);
        try {
            MarketConfig d = MarketConfig.fromSystem();
            assertEquals(0, d.tradersPct(), "a build started without the flags keeps 1.6.1's traders");
            assertFalse(d.tradersNx(), "and trades item tickers only");
            assertFalse(MarketConfig.tradersNxOn());
            assertEquals(16, MarketTraders.target(cands(1, 3_000), d), "the fixed count of 16");
            System.setProperty(MarketConfig.TRADERS_PCT, "35");
            MarketConfig on = MarketConfig.fromSystem();
            assertEquals(35, on.tradersPct());
            assertEquals(MarketConfig.MAX_TRADERS, on.traderCount(), "no count named: the share decides");
            assertEquals(1_050, MarketTraders.target(cands(1, 3_000), on));
            System.setProperty(MarketConfig.TRADERS_COUNT, "20");
            assertEquals(20, MarketTraders.target(cands(1, 3_000), MarketConfig.fromSystem()),
                    "a count still caps the share");
        } finally {
            System.clearProperty(MarketConfig.TRADERS_PCT);
            System.clearProperty(MarketConfig.TRADERS_COUNT);
        }
    }
}
