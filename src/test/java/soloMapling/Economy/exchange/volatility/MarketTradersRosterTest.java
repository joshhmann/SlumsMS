package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 QA round 3: traders are picked from the bots in the game. On TEST the 16 were picked from all 9,000 roster
 * bots, so about one was online at a time and the item books had no levels.
 */
class MarketTradersRosterTest {

    static List<MarketTraders.Candidate> cands(long from, int n) {
        List<MarketTraders.Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new MarketTraders.Candidate(from + i, i % 2 == 0 ? "KILLER" : "ACHIEVER"));
        }
        return out;
    }

    @Test
    void everyTraderIsOnlineAndTheCountIsFilled() {
        List<MarketTraders.Candidate> online = cands(1_000_000_000L, 40);
        List<MarketTraders.Trader> t = MarketTraders.keepAndFill(List.of(), online, 16);
        assertEquals(16, t.size());
        Set<Long> ids = online.stream().map(MarketTraders.Candidate::id).collect(Collectors.toSet());
        assertTrue(t.stream().allMatch(x -> ids.contains(x.id())));
    }

    @Test
    void tradersStillOnlineKeepTheirPlaceAndArchetypeAndOfflineOnesAreReplaced() {
        List<MarketTraders.Candidate> first = cands(1_000_000_000L, 40);
        List<MarketTraders.Trader> before = MarketTraders.keepAndFill(List.of(), first, 16);
        // half of them log out, new bots log in
        List<MarketTraders.Candidate> now = new ArrayList<>();
        Set<Long> gone = before.subList(0, 8).stream().map(MarketTraders.Trader::id).collect(Collectors.toSet());
        for (MarketTraders.Candidate c : first) {
            if (!gone.contains(c.id())) {
                now.add(c);
            }
        }
        now.addAll(cands(1_000_100_000L, 20));
        List<MarketTraders.Trader> after = MarketTraders.keepAndFill(before, now, 16);
        assertEquals(16, after.size());
        assertEquals(before.subList(8, 16), after.subList(0, 8), "the ones still online keep place and archetype");
        assertTrue(after.stream().noneMatch(x -> gone.contains(x.id())), "nobody offline");
        assertEquals(16, after.stream().map(MarketTraders.Trader::id).distinct().count());
    }

    @Test
    void fewerBotsOnlineThanTradersMeansFewerTradersNotOfflineOnes() {
        List<MarketTraders.Trader> t = MarketTraders.keepAndFill(List.of(), cands(1_000_000_000L, 5), 16);
        assertEquals(5, t.size());
        assertEquals(0, MarketTraders.keepAndFill(t, List.of(), 16).size());
    }
}
