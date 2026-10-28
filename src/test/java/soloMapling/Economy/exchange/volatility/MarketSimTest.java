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
import java.util.LinkedHashMap;
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
 * bots for 72 simulated hours: their off-screen leaf finds at the real global chance, the world's drop and sink flows
 * feeding the hourly {@link FairValue}, a guild war (Hawks vs Owls, declared at hour 12, Hawks win at hour 36; five
 * traders in each guild), a guild gearing up for Zakum at hour 50 that wipes at hour 52, and rumours. No random events
 * (off by default). Prints the LEAF and ELIXIR paths and checks that nothing was minted.
 */
class MarketSimTest {

    static final int HAWKS = 101, OWLS = 202;

    @AfterEach
    void tearDown() {
        MarketEvents.useForTest(null);
        MarketNews.useForTest(null);
        MarketTraders.clearForTest();
        MarketFlows.get().clear();
    }

    record Range(long fills, long min, long max, long first, long last, double maxHourMove, TreeMap<Long, Long> closes) {
        double ratio() {
            return min == 0 ? 0 : (double) max / min;
        }
    }

    record Result(Range leaf, Range elixir, Map<String, Integer> refused, long placed) {
    }

    static Range range(List<FillRow> all, String ticker, long start, int hours) {
        List<FillRow> fills = new ArrayList<>(all);
        fills.removeIf(f -> !ticker.equals(f.ticker()));
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
        return new Range(fills.size(), fills.isEmpty() ? 0 : min, max, fills.isEmpty() ? 0 : fills.get(0).price(),
                fills.isEmpty() ? 0 : fills.get(fills.size() - 1).price(), maxMove, closes);
    }

    static Result simulate(long seed, int hours, boolean print) throws Exception {
        MemoryExchangeStore store = new MemoryExchangeStore();
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig c = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                d.maxOpen(), d.perDay(), TimeUnit.SECONDS.toMillis(10), d.expiryMs(), 0, d.regularSpreadBps(),
                d.regularNx(), d.rateWindowMs(), 3, d.pollSeconds(), 0, 60_000);
        Exchange ex = new Exchange(store, c, a -> false, a -> null, now::get, Ticker.defaults(c));
        assertTrue(ex.recover().isEmpty());
        Random rng = new Random(seed);
        MarketFlows flows = MarketFlows.get();
        flows.clear();
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
        List<MarketTraders.Trader> list = MarketTraders.select(cands, 16);
        Map<Long, FakeBotWallet> wallets = new HashMap<>();
        Map<Long, Integer> guilds = new HashMap<>();
        int i = 0;
        for (MarketTraders.Trader t : list) {
            FakeBotWallet w = new FakeBotWallet(t.id(), 30_000_000L + rng.nextInt(120_000_000))
                    .with(Asset.LEAF, rng.nextInt(60)).with(Asset.CHAOS, rng.nextInt(3))
                    .with(Asset.ELIXIR, rng.nextInt(300));
            wallets.put(t.id(), w);
            guilds.put(t.id(), i < 5 ? HAWKS : i < 10 ? OWLS : 0);
            i++;
        }
        MarketTraders.setTraderIds(wallets.keySet());
        MarketTraders.Roster roster = new MarketTraders.Roster() {
            public List<MarketTraders.Trader> traders() {
                return list;
            }

            public LiveWallet walletOf(MarketTraders.Trader t) {
                return wallets.get(t.id());
            }

            public int guildOf(MarketTraders.Trader t) {
                return guilds.get(t.id());
            }
        };
        MarketConfig cfg = MarketConfig.allOn();
        MarketEvents events = MarketEvents.forTest(cfg, now::get, s -> { });
        MarketEvents.useForTest(events);
        MarketNews news = MarketNews.forTest(now::get, s -> { }, true);
        Map<MarketRumours.Claim, List<String>> lines = new java.util.EnumMap<>(MarketRumours.Claim.class);
        for (MarketRumours.Claim cl : MarketRumours.Claim.values()) {
            lines.put(cl, List.of(cl.node));
        }
        MarketRumours rumours = new MarketRumours(MarketRumours.fixed(lines));
        FairValue fair = new FairValue(0.5, 0.3);
        FairValue.Sources src = new FairValue.Sources() {
            public long floor(String t) {
                return switch (t) {
                    case "LEAF" -> 500_000;
                    case "ELIXIR" -> 3_000;
                    default -> 100_000;
                };
            }

            public long ceiling(String t, Map<String, Long> fairs) {
                return switch (t) {
                    case "LEAF" -> 1_200_000;
                    case "ELIXIR" -> 12_000;
                    default -> 30_000_000;
                };
            }
        };
        MarketTraders tr = new MarketTraders(new Random(seed + 1));
        tr.use(fair, news);
        long meso0 = store.totalMeso() + wallets.values().stream().mapToLong(w -> w.mesos).sum();
        long leaf0 = store.total(Asset.LEAF) + wallets.values().stream().mapToLong(w -> w.have(Asset.LEAF)).sum();
        long found = 0;
        long tick = 15_000, start = now.get();
        for (long t = 0; t < hours * 3_600_000L; t += tick) {
            long at = now.addAndGet(tick);
            long h = t / 3_600_000L;
            if (t == 12 * 3_600_000L) {
                news.declared(1, HAWKS, "Hawks", OWLS, "Owls", start + 7 * 86_400_000L);
            }
            if (t == 36 * 3_600_000L) {
                news.ended(1, HAWKS);
            }
            if (t == 50 * 3_600_000L) {
                news.prep(OWLS, "Owls", "zakum", "zakum", 6);
            }
            if (t == 52 * 3_600_000L) {
                news.finished(OWLS, false);
            }
            // the world's flows (players): ~400 leaves dropped and ~300 spent at Rooty a day; elixirs 2,000 each way,
            // half again more used while the war or the prep runs
            double perTick = tick / 86_400_000.0;
            boolean busy = h >= 12 && h < 36 || h >= 50 && h < 54;
            flows.add("LEAF", 400 * perTick, 300 * perTick, at);
            flows.add("ELIXIR", 2_000 * perTick, (busy ? 3_000 : 2_000) * perTick, at);
            if (t % 3_600_000L == 0) {
                fair.update(at, flows, src);
            }
            // off-screen hunting: every trader hunts about half the time
            for (FakeBotWallet w : wallets.values()) {
                if (rng.nextBoolean()) {
                    int n = MarketTraders.offscreenLeaves(w.id, tick * 2, MarketVolatility.KILLS_PER_MIN,
                            events.leafChance(500, at), 1.5);
                    if (n > 0) {
                        w.give(Asset.LEAF, n);
                        flows.add("LEAF", n, 0, at);
                        found += n;
                    }
                }
            }
            tr.tick(ex, at, roster, cfg, events, rumours);
            ex.expire();
            if (rumours.due(at, cfg.rumourGapMs()) && rng.nextInt(40) == 0) {
                Ticker tk = ex.ticker(rng.nextBoolean() ? "LEAF" : "ELIXIR");
                MarketRumours.Rumour r = rumours.make(MarketVolatility.facts(ex, tk, at, events, news), at,
                        cfg.rumourGapMs(), cfg.fakePct(), rng);
                if (r != null) {
                    rumours.said(r);
                }
            }
        }
        for (FakeBotWallet w : wallets.values()) {
            ex.collectBot(w);
        }
        long meso1 = store.totalMeso() + wallets.values().stream().mapToLong(w -> w.mesos).sum();
        assertEquals(meso0, meso1, "mesos conserved");
        long leafBots = wallets.values().stream().mapToLong(w -> w.have(Asset.LEAF)).sum();
        assertEquals(leaf0 + found, store.total(Asset.LEAF) + leafBots, "leaves conserved (plus what the hunts found)");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "reconciles");
        for (var e : store.balances().entrySet()) {
            assertTrue(e.getValue() >= 0, e.getKey() + " negative");
        }
        Result res = new Result(range(store.fills(), "LEAF", start, hours), range(store.fills(), "ELIXIR", start, hours),
                tr.refusedByCode(), tr.placedCount());
        if (print) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("SIM seed %d: %d h, %d orders placed, leaves found %d, fees %,d, fair LEAF %,d "
                            + "ELIXIR %,d, refused %s%n", seed, hours, res.placed(), found, store.balance(Bucket.fees()),
                    fair.fair("LEAF"), fair.fair("ELIXIR"), res.refused()));
            Map<String, Range> both = new LinkedHashMap<>();
            both.put("LEAF", res.leaf());
            both.put("ELIXIR", res.elixir());
            for (var e : both.entrySet()) {
                Range r = e.getValue();
                sb.append(String.format("SIM %s %d fills, first %,d last %,d, min %,d max %,d (%.2fx), biggest hour move "
                                + "%.1f%%%nSIM %s closes:", e.getKey(), r.fills(), r.first(), r.last(), r.min(), r.max(),
                        r.ratio(), r.maxHourMove() * 100, e.getKey()));
                for (var cl : r.closes().entrySet()) {
                    sb.append(cl.getKey() % 6 == 0 ? "\nSIM   h" + cl.getKey() + ":" : "")
                            .append(String.format(" %,d", cl.getValue()));
                }
                sb.append('\n');
            }
            System.out.println(sb);
        }
        return res;
    }

    @Test
    void tradersMoveThePricesOnNewsAndMintNothing() throws Exception {
        Result r = simulate(7, 72, true);
        assertTrue(r.leaf().fills() > 30, "LEAF trades: " + r.leaf().fills());
        assertTrue(r.elixir().fills() > 30, "ELIXIR trades: " + r.elixir().fills());
    }

    @Test
    void otherSeedsConserveToo() throws Exception {
        for (long s = 1; s <= 3; s++) {
            simulate(s * 101, 72, true);
        }
    }
}
