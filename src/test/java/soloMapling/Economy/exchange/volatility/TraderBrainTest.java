package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.volatility.TraderBrain.Action;
import soloMapling.Economy.exchange.volatility.TraderBrain.Archetype;
import soloMapling.Economy.exchange.volatility.TraderBrain.Cancel;
import soloMapling.Economy.exchange.volatility.TraderBrain.Holdings;
import soloMapling.Economy.exchange.volatility.TraderBrain.Open;
import soloMapling.Economy.exchange.volatility.TraderBrain.Place;
import soloMapling.Economy.exchange.volatility.TraderBrain.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 volatility: each archetype's behaviour, and the guard that only stops bugs (never volatility). */
class TraderBrainTest {

    static final long NOW = 1_800_000_000_000L;

    static View view(long ref, long bid, long ask, long hourAgo, long high2h, double pressure) {
        return new View("LEAF", ref, bid, ask, hourAgo, high2h, 550_000, pressure, 1, 1_000_000_000L, 10_000, 100);
    }

    static Holdings holds(long mesos, long items, Open... open) {
        return new Holdings(mesos, items, List.of(open), 20, 10);
    }

    static List<Place> places(List<Action> as) {
        List<Place> out = new ArrayList<>();
        for (Action a : as) {
            if (a instanceof Place p) {
                out.add(p);
            }
        }
        return out;
    }

    /** Runs a turn with many seeds and collects every place. */
    static List<Place> many(Archetype a, View v, Holdings h) {
        List<Place> out = new ArrayList<>();
        for (int s = 0; s < 200; s++) {
            out.addAll(places(TraderBrain.decide(a, v, h, NOW, new Random(s))));
        }
        return out;
    }

    @Test
    void momentumBuysARisingTickerAboveTheAskAndSellsAFallingOneUnderTheBid() {
        List<Place> up = many(Archetype.MOMENTUM, view(700_000, 690_000, 710_000, 600_000, 700_000, 0),
                holds(50_000_000, 20));
        assertFalse(up.isEmpty());
        assertTrue(up.stream().allMatch(p -> p.side() == Side.BUY && p.price() > 710_000), up.toString());
        List<Place> down = many(Archetype.MOMENTUM, view(500_000, 495_000, 505_000, 600_000, 600_000, 0),
                holds(50_000_000, 20));
        assertFalse(down.isEmpty());
        assertTrue(down.stream().allMatch(p -> p.side() == Side.SELL && p.price() < 495_000), down.toString());
    }

    @Test
    void aKnownEventOrRumourTipsMomentumOnAFlatMarket() {
        List<Place> blight = many(Archetype.MOMENTUM, view(600_000, 595_000, 605_000, 600_000, 600_000, 1.0),
                holds(50_000_000, 20));
        assertTrue(blight.stream().anyMatch(p -> p.side() == Side.BUY && p.price() > 605_000), "Blight: buy");
        List<Place> rush = many(Archetype.MOMENTUM, view(600_000, 595_000, 605_000, 600_000, 600_000, -1.0),
                holds(50_000_000, 20));
        assertTrue(rush.stream().anyMatch(p -> p.side() == Side.SELL && p.price() < 595_000), "Rush: sell");
    }

    @Test
    void thePanicSellerDumpsMostOfItsStackAfterASharpDrop() {
        List<Place> ps = many(Archetype.PANIC, view(500_000, 490_000, 510_000, 600_000, 620_000, 0),
                holds(10_000_000, 100));
        assertFalse(ps.isEmpty());
        assertTrue(ps.stream().allMatch(p -> p.side() == Side.SELL && p.qty() >= 60 && p.price() < 490_000), ps.toString());
        // calm market under fair value: it only restocks, never dumps
        List<Place> calm = many(Archetype.PANIC, view(560_000, 555_000, 565_000, 560_000, 565_000, 0),
                holds(10_000_000, 100));
        assertTrue(calm.stream().allMatch(p -> p.side() == Side.BUY));
    }

    @Test
    void theWhaleIsRareButWalksTheBook() {
        List<Place> ws = many(Archetype.WHALE, view(600_000, 595_000, 605_000, 580_000, 600_000, 0),
                holds(1_000_000_000L, 0));
        assertTrue(ws.size() > 5 && ws.size() < 60, "about 12% of turns: " + ws.size());
        for (Place p : ws) {
            assertEquals(Side.BUY, p.side());
            assertTrue(p.price() >= 605_000 * 1.10 - 1000, "10%+ through the ask: " + p);
            assertTrue(p.qty() >= 500, "a big order: " + p);
        }
    }

    @Test
    void valueBuysUnderFairAndAsksWellAbove() {
        List<Place> ps = many(Archetype.VALUE, view(480_000, 470_000, 480_000, 500_000, 520_000, 0),
                holds(50_000_000, 30));
        assertTrue(ps.stream().anyMatch(p -> p.side() == Side.BUY && p.price() <= 490_000));
        assertTrue(ps.stream().filter(p -> p.side() == Side.SELL).allMatch(p -> p.price() >= 550_000 * 1.35));
    }

    @Test
    void theMakerQuotesBothSidesAndRepricesStaleQuotes() {
        Open stale = new Open(7, Side.BUY, 400_000, 3, NOW - 25 * 60_000L);
        List<Action> as = TraderBrain.decide(Archetype.MAKER, view(600_000, 590_000, 610_000, 600_000, 600_000, 0),
                holds(50_000_000, 30, stale), NOW, new Random(1));
        assertTrue(as.contains(new Cancel(7)), as.toString());
        List<Place> ps = places(as);
        assertTrue(ps.stream().anyMatch(p -> p.side() == Side.BUY && p.price() < 600_000));
        assertTrue(ps.stream().anyMatch(p -> p.side() == Side.SELL && p.price() > 600_000));
    }

    @Test
    void theGuardStopsBugsNotVolatility() {
        View v = view(600_000, 590_000, 610_000, 600_000, 600_000, 0);
        Holdings h = new Holdings(1_000_000, 5, List.of(), 3, 10);
        List<Action> in = List.of(
                new Place(Side.SELL, 600_000, 4), new Place(Side.SELL, 600_000, 4),   // 8 > 5 held
                new Place(Side.BUY, 0, 1),                                           // price 0
                new Place(Side.BUY, 100_000_000, 1),                                 // >10x the reference
                new Place(Side.BUY, 600_000, 5),                                     // more than 1m mesos
                new Cancel(99));                                                     // not its order
        List<Action> out = TraderBrain.guard(in, v, h);
        long sold = 0, spent = 0;
        int placesN = 0;
        for (Action a : out) {
            assertTrue(a instanceof Place, "a foreign cancel is dropped");
            Place p = (Place) a;
            placesN++;
            assertTrue(p.price() > 0 && p.price() <= 6_000_000, p.toString());
            if (p.side() == Side.SELL) {
                sold += p.qty();
            } else {
                spent += TraderBrain.total(p.qty(), p.price(), 100);
            }
        }
        assertTrue(sold <= 5, "never more than it holds: " + out);
        assertTrue(spent <= 1_000_000, "never more mesos than it has: " + out);
        assertTrue(placesN <= 3, "never more than its day's places left");
        // a 40% crash order is allowed: the guard is not a circuit breaker
        List<Action> crash = TraderBrain.guard(List.of(new Place(Side.SELL, 360_000, 5)), v, h);
        assertEquals(List.of(new Place(Side.SELL, 360_000, 5)), crash);
    }

    @Test
    void openSlotsAreRespected() {
        Open[] ten = new Open[10];
        for (int i = 0; i < 10; i++) {
            ten[i] = new Open(i + 1, Side.SELL, 700_000, 1, NOW);
        }
        List<Action> out = TraderBrain.guard(List.of(new Place(Side.BUY, 600_000, 1)),
                view(600_000, 0, 0, 0, 0, 0), new Holdings(10_000_000, 0, List.of(ten), 20, 10));
        assertTrue(out.isEmpty(), "10 open already");
    }

    @Test
    void pricesAreTidy() {
        assertEquals(601_000, TraderBrain.tidy(600_789));
        assertEquals(6_120, TraderBrain.tidy(6_123));
        assertEquals(999, TraderBrain.tidy(999));
        assertEquals(1, TraderBrain.tidy(0));
    }
}
