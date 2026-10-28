package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.ExchangeStore.FillRow;
import soloMapling.Economy.exchange.FakeBotWallet;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.Ticker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 volatility, the offline simulation: the real exchange (memory store, real limits and fees) with 16 trader
 * bots for 72 simulated hours, their off-screen leaf finds at the real global chance, random events plus a forced
 * Leaf Rush (hour 24) and Leaf Blight (hour 48), and a rumour every ~20 minutes. Prints the LEAF price path and
 * checks that nothing was minted: mesos and leaves across the bots and the exchange are exactly conserved (the
 * fees sit in FEES), the books and the ledger reconcile.
 */
class MarketSimTest {

    @AfterEach
    void tearDown() {
        MarketEvents.useForTest(null);
        MarketTraders.clearForTest();
    }

    record Result(long fills, long min, long max, long first, long last, double maxHourMove, Map<Long, Long> closes,
                  Map<String, Integer> refused, long placed) {
    }

    static Result simulate(long seed, int hours, int traders, boolean print) throws Exception {
        MemoryExchangeStore store = new MemoryExchangeStore();
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig c = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                d.maxOpen(), d.perDay(), TimeUnit.SECONDS.toMillis(10), d.expiryMs(), 0, d.regularSpreadBps(),
                d.regularNx(), d.rateWindowMs(), 3, d.pollSeconds(), 0, 60_000);
        Exchange ex = new Exchange(store, c, a -> false, a -> null, now::get, Ticker.defaults(c));
        assertTrue(ex.recover().isEmpty());
        Random rng = new Random(seed);
        List<MarketTraders.Candidate> cands = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String b = switch (i % 10) {
                case 0, 1, 2 -> "ACHIEVER";
                case 3 -> "KILLER";
                case 4, 5 -> "EXPLORER";
                default -> "SOCIALIZER";
            };
            cands.add(new MarketTraders.Candidate(1_000_000_100L + i, b));
        }
        List<MarketTraders.Trader> list = MarketTraders.select(cands, traders);
        Map<Long, FakeBotWallet> wallets = new HashMap<>();
        for (MarketTraders.Trader t : list) {
            FakeBotWallet w = new FakeBotWallet(t.id(), 30_000_000L + rng.nextInt(120_000_000))
                    .with(Asset.LEAF, rng.nextInt(60)).with(Asset.CHAOS, rng.nextInt(3)).with(Asset.ELIXIR,
                            rng.nextInt(200));
            wallets.put(t.id(), w);
        }
        MarketTraders.setTraderIds(wallets.keySet());
        MarketTraders.Roster roster = new MarketTraders.Roster() {
            public List<MarketTraders.Trader> traders() {
                return list;
            }

            public LiveWallet walletOf(MarketTraders.Trader t) {
                return wallets.get(t.id());
            }
        };
        MarketConfig cfg = MarketConfig.allOn();
        MarketEvents events = MarketEvents.forTest(cfg, now::get, s -> { });
        MarketEvents.useForTest(events);
        Map<MarketRumours.Claim, List<String>> lines = new java.util.EnumMap<>(MarketRumours.Claim.class);
        for (MarketRumours.Claim cl : MarketRumours.Claim.values()) {
            lines.put(cl, List.of(cl.node));
        }
        MarketRumours rumours = new MarketRumours(MarketRumours.fixed(lines));
        MarketTraders tr = new MarketTraders(new Random(seed + 1));
        long meso0 = store.totalMeso() + wallets.values().stream().mapToLong(w -> w.mesos).sum();
        long leaf0 = store.total(Asset.LEAF) + wallets.values().stream().mapToLong(w -> w.have(Asset.LEAF)).sum();
        long found = 0;
        long tick = 15_000, start = now.get();
        for (long t = 0; t < hours * 3_600_000L; t += tick) {
            long at = now.addAndGet(tick);
            if (t == 24 * 3_600_000L) {
                events.start(MarketEvents.Kind.LEAF_RUSH, 0, true, at);
            }
            if (t == 48 * 3_600_000L) {
                events.start(MarketEvents.Kind.LEAF_BLIGHT, 0, false, at);
            }
            events.maybeRandom(at, tick, rng);
            // off-screen hunting: every trader hunts about half the time
            for (FakeBotWallet w : wallets.values()) {
                if (rng.nextBoolean()) {
                    int n = MarketTraders.offscreenLeaves(w.id, tick * 2, MarketVolatility.KILLS_PER_MIN,
                            events.leafChance(500, at), 1.5);
                    if (n > 0) {
                        w.give(Asset.LEAF, n);
                        found += n;
                    }
                }
            }
            tr.tick(ex, at, roster, cfg, events, rumours);
            ex.expire();
            if (rumours.due(at, cfg.rumourGapMs()) && rng.nextInt(40) == 0) {
                Ticker leaf = ex.ticker("LEAF");
                MarketRumours.Rumour r = rumours.make(MarketVolatility.facts(ex, leaf, at, events), at,
                        cfg.rumourGapMs(), cfg.fakePct(), rng);
                if (r != null) {
                    rumours.said(r);
                }
            }
        }
        for (FakeBotWallet w : wallets.values()) {
            ex.collectBot(w);
        }
        // conservation: every meso and leaf is in a bot or the exchange (fees in FEES)
        long meso1 = store.totalMeso() + wallets.values().stream().mapToLong(w -> w.mesos).sum();
        assertEquals(meso0, meso1, "mesos conserved");
        long leafBots = wallets.values().stream().mapToLong(w -> w.have(Asset.LEAF)).sum();
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "reconciles");
        for (var e : store.balances().entrySet()) {
            assertTrue(e.getValue() >= 0, e.getKey() + " negative");
        }
        assertEquals(leaf0 + found, store.total(Asset.LEAF) + leafBots, "leaves conserved (plus what the hunts found)");
        List<FillRow> fills = new ArrayList<>(store.fills());
        fills.removeIf(f -> !"LEAF".equals(f.ticker()));
        fills.sort(java.util.Comparator.comparingLong(FillRow::at));
        long min = Long.MAX_VALUE, max = 0;
        TreeMap<Long, Long> closes = new TreeMap<>();
        for (FillRow f : fills) {
            min = Math.min(min, f.price());
            max = Math.max(max, f.price());
            closes.put((f.at() - start) / 3_600_000L, f.price());
        }
        double maxMove = 0;
        Long prev = null;
        for (long h = 0; h < hours; h++) {
            Long cl = closes.get(h);
            if (cl == null) {
                continue;
            }
            if (prev != null) {
                maxMove = Math.max(maxMove, Math.abs(cl - prev) / (double) prev);
            }
            prev = cl;
        }
        Result res = new Result(fills.size(), fills.isEmpty() ? 0 : min, max, fills.isEmpty() ? 0 : fills.get(0).price(),
                fills.isEmpty() ? 0 : fills.get(fills.size() - 1).price(), maxMove, closes, tr.refusedByCode(),
                tr.placedCount());
        if (print) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("SIM seed %d: %d h, %d traders, %d LEAF fills, %d orders placed, leaves found %d, "
                            + "fees %,d%n", seed, hours, traders, res.fills(), res.placed(), found,
                    store.balance(Bucket.fees())));
            sb.append(String.format("SIM LEAF first %,d last %,d min %,d max %,d (max/min %.2fx), biggest hour-to-hour "
                    + "move %.1f%%%n", res.first(), res.last(), res.min(), res.max(), res.min() == 0 ? 0
                    : (double) res.max() / res.min(), res.maxHourMove() * 100));
            sb.append("SIM refused " + res.refused() + "\nSIM hourly closes:");
            for (var e : closes.entrySet()) {
                sb.append(e.getKey() % 6 == 0 ? "\nSIM  h" + e.getKey() + ":" : "").append(String.format(" %,d", e.getValue()));
            }
            System.out.println(sb);
        }
        return res;
    }

    @Test
    void tradersMoveTheLeafPriceAndMintNothing() throws Exception {
        Result r = simulate(7, 72, 16, true);
        assertTrue(r.fills() > 50, "the traders trade: " + r.fills());
        assertTrue(r.max() > r.min(), "the price moves");
    }

    @Test
    void otherSeedsConserveToo() throws Exception {
        for (long s = 1; s <= 3; s++) {
            simulate(s * 101, 48, 16, true);
        }
    }
}
