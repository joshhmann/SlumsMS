package soloMapling.Economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.MegaBuyDesk.Asker;
import soloMapling.Economy.MegaBuyDesk.Outcome;
import soloMapling.Economy.MegaBuyDesk.Source;
import soloMapling.Economy.MegaBuyDesk.Stock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MegaBuyDeskTest {

    static final long FAIR = 4_000_000;
    static final int WHIP = 1372005;
    static final Asker ASKER = new Asker(7, "Buyer", 1, null);

    static final class FakeWorld implements MegaBuyDesk.World {
        long now = 1_000_000;
        final Random rng = new Random(1);
        final List<Stock> stock = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        final List<String> whispers = new ArrayList<>();
        final List<String> quotes = new ArrayList<>();
        boolean online = true;
        boolean has = true;
        boolean friend;

        public long now() { return now; }
        public double roll() { return rng.nextDouble(); }
        public int between(int lo, int hi) { return hi <= lo ? lo : lo + rng.nextInt(hi - lo + 1); }
        public void later(long delayMs, Runnable task) { delays.add(delayMs); queued.add(task); }
        public List<Stock> stock(Asker a) { return stock; }
        public boolean stillHas(Stock s) { return has; }
        public boolean askerOnline(Asker a) { return online; }
        public boolean friend(int botId, int playerId) { return friend; }
        public long fairOf(Stock s) { return FAIR; }
        public boolean whisper(Stock s, Asker a, String line) { whispers.add(s.botName() + ": " + line); return true; }
        public void quote(Stock s, Asker a, long price, int rounds) { quotes.add(s.botName() + " " + price + " r" + rounds); }

        void run() {
            List<Runnable> now = new ArrayList<>(queued);
            queued.clear();
            now.forEach(Runnable::run);
        }
    }

    FakeWorld w;
    MegaBuyDesk desk;

    static Stock bag(int botId, int itemId, String name) {
        return new Stock(Source.BAG, botId, "Bot" + botId, 1, itemId, name, 1, 0, 0, "im at henesys ch1", new Object());
    }

    @BeforeEach
    void setUp() {
        w = new FakeWorld();
        desk = new MegaBuyDesk(w, new MegaBuyDesk.Limits(180_000, 4, 600_000, 8, 900_000));
    }

    @Test
    void noStockMeansNoAnswer() {
        assertEquals(Outcome.NO_STOCK, desk.hear(ASKER, "B> red whip 5m"));
        w.stock.add(bag(1, 1332006, "Ilbis"));
        assertEquals(Outcome.NO_STOCK, desk.hear(ASKER, "B> red whip 5m"), "bots have an ilbis, not a red whip");
        assertTrue(w.queued.isEmpty());
        assertTrue(w.whispers.isEmpty());
    }

    @Test
    void sellMegaphonesAndChatterNeverGetOffers() {
        w.stock.add(bag(1, WHIP, "Red Whip"));
        assertEquals(Outcome.NOT_A_BUY, desk.hear(ASKER, "S> red whip 5m"));
        assertEquals(Outcome.NOT_A_BUY, desk.hear(ASKER, "hello world"));
        assertEquals(Outcome.NOT_A_BUY, desk.hear(ASKER, "lf pt"));
        assertTrue(w.queued.isEmpty());
    }

    @Test
    void ambiguousItemWordsGetNoOffer() {
        w.stock.add(bag(1, 1472030, "Claw for ATT 60%"));
        w.stock.add(bag(2, 1472031, "Claw for ATT 10%"));
        assertEquals(Outcome.NO_ITEM, desk.hear(ASKER, "wtb claw att"));
        assertTrue(w.queued.isEmpty());
    }

    @Test
    void exactlyOneBotAnswersAfterAHumanDelay() {
        for (int i = 1; i <= 5; i++) {
            w.stock.add(bag(i, WHIP, "Red Whip"));
        }
        assertEquals(Outcome.SCHEDULED, desk.hear(ASKER, "B> red whip"));
        assertEquals(1, w.queued.size(), "one bot, not five");
        long d = w.delays.get(0);
        assertTrue(d >= 20_000 && d <= 120_000, "delay " + d);
        assertTrue(w.whispers.isEmpty(), "nothing is said until the delay is up");
        w.run();
        assertEquals(1, w.whispers.size());
        assertEquals(1, w.quotes.size(), "a bag bot remembers its price for the trade flow");
        assertTrue(w.whispers.get(0).contains("red whip"), w.whispers.get(0));
    }

    @Test
    void theClaimGoesToExactlyOneBot() throws Exception {
        for (int round = 0; round < 50; round++) {
            long id = 1000 + round;
            AtomicInteger won = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> ts = new ArrayList<>();
            for (int bot = 1; bot <= 8; bot++) {
                int b = bot;
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException ignored) {
                    }
                    if (desk.claim(id, b)) {
                        won.incrementAndGet();
                    }
                });
                ts.add(t);
                t.start();
            }
            go.countDown();
            for (Thread t : ts) {
                t.join();
            }
            assertEquals(1, won.get());
        }
    }

    @Test
    void thePlayerIsRateLimited() {
        for (int i = 1; i <= 6; i++) {
            w.stock.add(bag(i, WHIP, "Red Whip"));
        }
        assertEquals(Outcome.SCHEDULED, desk.hear(ASKER, "B> red whip"));
        w.now += 60_000;
        assertEquals(Outcome.RATE_LIMITED, desk.hear(ASKER, "B> red whip"), "inside the gap");
        for (int k = 0; k < 3; k++) {
            w.now += 200_000;
            assertEquals(Outcome.SCHEDULED, desk.hear(ASKER, "B> red whip"));
        }
        w.now += 200_000;
        assertEquals(Outcome.RATE_LIMITED, desk.hear(ASKER, "B> red whip"), "4 an hour");
        assertEquals(4, w.queued.size());
    }

    @Test
    void theServerIsRateLimitedAndABotRests() {
        MegaBuyDesk.Limits l = new MegaBuyDesk.Limits(0, 100, 600_000, 3, 900_000);
        for (int i = 1; i <= 10; i++) {
            w.stock.add(bag(i, WHIP, "Red Whip"));
        }
        MegaBuyDesk d = new MegaBuyDesk(w, l);
        for (int p = 1; p <= 3; p++) {
            assertEquals(Outcome.SCHEDULED, d.hear(new Asker(p, "P" + p, 1, null), "wtb red whip"));
        }
        assertEquals(Outcome.RATE_LIMITED, d.hear(new Asker(4, "P4", 1, null), "wtb red whip"), "3 per 10 min server-wide");
        w.now += 601_000;
        assertEquals(Outcome.SCHEDULED, d.hear(new Asker(4, "P4", 1, null), "wtb red whip"));
        // one bot only: it rests 15 min after answering
        w.stock.clear();
        w.stock.add(bag(99, WHIP, "Red Whip"));
        w.now += 601_000;
        assertEquals(Outcome.SCHEDULED, d.hear(new Asker(5, "P5", 1, null), "wtb red whip"));
        w.now += 601_000;
        assertEquals(Outcome.RATE_LIMITED, d.hear(new Asker(6, "P6", 1, null), "wtb red whip"), "bot 99 is resting");
    }

    @Test
    void priceFollowsTheBandAndRespectsTheBuyersNumber() {
        Stock s = bag(1, WHIP, "Red Whip").withFair(FAIR);
        PriceBand.Band band = PriceBand.of(PriceBand.Side.BOT_SELLS, FAIR, false);
        MegaBuyDesk.Plan none = MegaBuyDesk.plan(s, -1, false);
        assertTrue(band.contains(none.price()), "opening price is inside the band");
        // a generous buyer: the bot takes the price (it is inside or above the band)
        MegaBuyDesk.Plan rich = MegaBuyDesk.plan(s, band.high() + 1_000_000, false);
        assertFalse(rich.countered());
        assertTrue(rich.price() >= band.low());
        // a close buyer is countered, never below the band floor
        MegaBuyDesk.Plan close = MegaBuyDesk.plan(s, band.low() - 100_000, false);
        assertNotNull(close);
        assertTrue(close.price() >= band.low(), close.price() + " < " + band.low());
        // far below: no answer
        assertNull(MegaBuyDesk.plan(s, band.low() / 4, false));
        assertNull(MegaBuyDesk.plan(s, 1_000, false));
    }

    @Test
    void aFarBelowBuyerGetsNoOffer() {
        w.stock.add(bag(1, WHIP, "Red Whip"));
        assertEquals(Outcome.FAR_BELOW, desk.hear(ASKER, "B> red whip 100k"));
        assertTrue(w.queued.isEmpty());
        // and it did not use up the player's allowance
        assertEquals(Outcome.SCHEDULED, desk.hear(ASKER, "B> red whip 5m"));
    }

    @Test
    void merchantsAndShopsStateTheirPostedPriceAndNeverNegotiateBelowIt() {
        Stock shop = new Stock(Source.SHOP, 5, "ShopBot", 2, WHIP, "Red Whip", 1, FAIR, 4_500_000,
                "fm room 3 ch2, just buy it from the shop", new Object());
        assertEquals(4_500_000, MegaBuyDesk.plan(shop, -1, false).price());
        assertEquals(4_500_000, MegaBuyDesk.plan(shop, 4_000_000, false).price(), "the shelf price stands");
        assertNull(MegaBuyDesk.plan(shop, 1_000_000, false));
        w.stock.add(shop);
        assertEquals(Outcome.SCHEDULED, desk.hear(ASKER, "lf red whip"));
        w.run();
        assertTrue(w.whispers.get(0).contains("fm room 3 ch2"), w.whispers.get(0));
        assertTrue(w.quotes.isEmpty(), "a shop sells at its shelf, no quote to remember");
    }

    @Test
    void nothingIsSentWhenTheBotLostTheItemOrThePlayerLeft() {
        w.stock.add(bag(1, WHIP, "Red Whip"));
        assertEquals(Outcome.SCHEDULED, desk.hear(ASKER, "B> red whip"));
        w.has = false;
        w.run();
        assertTrue(w.whispers.isEmpty());

        w = new FakeWorld();
        desk = new MegaBuyDesk(w, new MegaBuyDesk.Limits(180_000, 4, 600_000, 8, 900_000));
        w.stock.add(bag(1, WHIP, "Red Whip"));
        assertEquals(Outcome.SCHEDULED, desk.hear(ASKER, "B> red whip"));
        w.online = false;
        w.run();
        assertTrue(w.whispers.isEmpty());
    }

    @Test
    void aQuantityTheBotsLackIsNoStock() {
        w.stock.add(bag(1, WHIP, "Red Whip"));
        assertEquals(Outcome.NO_STOCK, desk.hear(ASKER, "wtb 3 red whip"));
    }

    @Test
    void samePlayerChannelBotsAreTriedFirst() {
        Stock far = new Stock(Source.BAG, 1, "FarBot", 5, WHIP, "Red Whip", 1, 0, 0, "im at ludi ch5", new Object());
        Stock near = new Stock(Source.BAG, 2, "NearBot", 1, WHIP, "Red Whip", 1, 0, 0, "im at henesys ch1", new Object());
        for (int i = 0; i < 20; i++) {
            FakeWorld f = new FakeWorld();
            f.rng.setSeed(i);
            f.stock.add(far);
            f.stock.add(near);
            MegaBuyDesk d = new MegaBuyDesk(f, new MegaBuyDesk.Limits(0, 100, 600_000, 100, 0));
            d.hear(ASKER, "wtb red whip");
            f.run();
            assertTrue(f.whispers.get(0).startsWith("NearBot"), f.whispers.get(0));
        }
    }
}
