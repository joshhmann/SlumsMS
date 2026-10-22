package soloMapling.Economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceBandTest {

    @BeforeEach
    void setUp() {
        EconomyConfig.useForTest(Map.of());
    }

    @AfterEach
    void tearDown() {
        EconomyConfig.useForTest(null);
    }

    @Test
    void theBandsFromTheOwnersNumbers() {
        PriceBand.Band buy = PriceBand.of(PriceBand.Side.BOT_BUYS, 1_000_000, false);
        assertEquals(700_000, buy.low());
        assertEquals(950_000, buy.high());
        PriceBand.Band sell = PriceBand.of(PriceBand.Side.BOT_SELLS, 1_000_000, false);
        assertEquals(950_000, sell.low());
        assertEquals(1_250_000, sell.high());
        PriceBand.Band friendBuy = PriceBand.of(PriceBand.Side.BOT_BUYS, 1_000_000, true);
        assertEquals(1_000_000, friendBuy.high(), "a friend gets 5% more, never past fair value");
        assertEquals(900_000, PriceBand.of(PriceBand.Side.BOT_SELLS, 1_000_000, true).low());
    }

    @Test
    void noNumberAPlayerTypesMovesABotOutsideItsBand() {
        Random rng = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            long fair = 1_000 + (long) (rng.nextDouble() * 200_000_000);
            boolean friend = rng.nextBoolean();
            for (PriceBand.Side side : PriceBand.Side.values()) {
                PriceBand.Band b = PriceBand.of(side, fair, friend);
                long current = PriceBand.opening(b);
                assertTrue(b.contains(current), "opening inside");
                for (int round = 0; round < 6; round++) {
                    long theirs = rng.nextInt(4) == 0 ? 1 : (long) (rng.nextDouble() * fair * 3);
                    PriceBand.Decision d = PriceBand.respond(b, current, theirs, round);
                    if (side == PriceBand.Side.BOT_BUYS) {
                        assertTrue(d.price() <= b.high(), "a bot never pays over its band: " + d + " " + b);
                    } else {
                        assertTrue(d.price() >= b.low(), "a bot never sells under its band: " + d + " " + b);
                        assertTrue(d.price() <= b.high(), "nor asks over it: " + d + " " + b);
                    }
                    current = side == PriceBand.Side.BOT_BUYS ? Math.max(current, d.price()) : d.price();
                }
            }
        }
    }

    @Test
    void haggleMovesTowardThePlayerThenStopsAtTheEdge() {
        PriceBand.Band sell = PriceBand.of(PriceBand.Side.BOT_SELLS, 1_000_000, false);
        long ask = PriceBand.opening(sell);
        assertEquals(1_190_000, ask);
        PriceBand.Decision d = PriceBand.respond(sell, ask, 1_000_000, 0);
        assertEquals(PriceBand.Kind.COUNTER, d.kind());
        assertTrue(d.price() < ask && d.price() > 1_000_000, d.toString());
        PriceBand.Decision low = PriceBand.respond(sell, d.price(), 1, 0);
        assertTrue(low.price() >= 950_000, "1 meso gets a counter, never a sale: " + low);
        assertEquals(PriceBand.Kind.FINAL, PriceBand.respond(sell, d.price(), 1, 3).kind(), "last round: its floor");
        assertEquals(new PriceBand.Decision(PriceBand.Kind.ACCEPT, 1_190_000),
                PriceBand.respond(sell, ask, 2_000_000_000L, 0), "overpaying gets the bot's own price");
        PriceBand.Band buy = PriceBand.of(PriceBand.Side.BOT_BUYS, 1_000_000, false);
        assertEquals(new PriceBand.Decision(PriceBand.Kind.FINAL, 950_000),
                PriceBand.respond(buy, 900_000, 50_000_000, 3));
    }

    @Test
    void pricesAreWrittenLikeAPlayerWould() {
        assertEquals(1_230_000, PriceBand.nice(1_234_567, true));
        assertEquals(1_240_000, PriceBand.nice(1_234_567, false));
        assertEquals(999, PriceBand.nice(999, true));
    }
}
