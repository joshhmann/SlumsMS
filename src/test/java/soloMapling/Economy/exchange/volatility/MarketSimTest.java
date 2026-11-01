package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Desk;
import soloMapling.Economy.exchange.DeskConfig;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.ExchangeStore;
import soloMapling.Economy.exchange.ExchangeStore.FillRow;
import soloMapling.Economy.exchange.FakeBotWallet;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.Ledger.Posting;
import soloMapling.Economy.exchange.Ledger.Why;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.NxGuard;
import soloMapling.Economy.exchange.OrderBook;
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

    // ---------------------------------------------------------------- ticket 162: the NX desk

    /** One turnover level for the desk arm: what players trade an hour, and in trades of what size. */
    record DeskArm(String name, long nxPerHour, long perTrade) {
    }

    /** What one desk arm did: R, the caps, the desk's own purse, and what was refused. */
    record DeskRun(String name, int hours, int fills, long volumeNx, int stepCapHours, int dayCapHours, int dryHours,
                   long firstR, long lastR, long minR, long maxR, Map<String, Integer> refused,
                   List<long[]> weeks) {
    }

    /**
     * Ticket 162, the desk arm: the real desk with set B ({@link DeskConfig#defaults()} on) for four weeks of game
     * time, one hourly step an hour, over 34 accounts holding TEST's 1,399,350 NX between them (SELECT 2026-10-31).
     * Each hour they trade {@code nxPerHour} in whole trades at the quote; the buy share falls as R rises over the
     * 5,000,000 the population reckons NX is worth (60% buy at that, 8:1 the other way), so R finds its own level.
     * Nobody tops the desk up, so it can run dry. Returns the R path, how many hours each cap bound, and the purse.
     */
    static DeskRun deskSimulate(String name, long nxPerHour, long perTrade, long seed) throws Exception {
        int hours = 4 * 7 * 24;
        long nxFloat = 1_399_350L;
        int accounts = 34;
        long fair = 5_000_000L;
        MemoryExchangeStore store = new MemoryExchangeStore();
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        for (int i = 0; i < accounts; i++) {
            long acct = 100 + i;
            store.addAccount(acct, nxFloat / accounts + (i < nxFloat % accounts ? 1 : 0));
            store.addCharacter(1_000 + i, acct, 400_000_000L);
        }
        long bank = 99, bankChar = 990;
        store.addAccount(bank, 500_000);
        store.addCharacter(bankChar, bank, 5_000_000_000L);
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig c = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 0,
                d.maxOpen(), d.perDay(), TimeUnit.SECONDS.toMillis(10), d.expiryMs(), 0, d.regularSpreadBps(),
                d.regularNx(), d.rateWindowMs(), 3, d.pollSeconds(), bank, 60_000);
        Exchange ex = new Exchange(store, c, a -> false, a -> null, now::get, Ticker.defaults(c));
        DeskConfig deskCfg = DeskConfig.defaults().on();
        ex.deskConfig(deskCfg);
        assertTrue(ex.recover().isEmpty());
        Desk desk = ex.desk();
        assertEquals(fair, desk.mid(), "R starts at the engine's price: no stored rate, no book to take one from");
        // the house's purse: the NX it bought off the players over 1.6.2, and mesos to buy more with
        long deskNx = 150_000, deskMeso = 500_000_000L;
        store.apply(new ExchangeStore.Batch(9000, now.get(), List.of(
                new Posting(Why.FUND, Bucket.wallet(Asset.NX, bank), Exchange.deskBucket(Asset.NX), deskNx, bank, 0, 0),
                new Posting(Why.FUND, Bucket.wallet(Asset.MESO, bankChar), Exchange.deskBucket(Asset.MESO), deskMeso,
                        bank, 0, 0)), List.of(), List.of()));
        long nx0 = store.totalNx(), meso0 = store.totalMeso();
        Random rng = new Random(seed);
        Map<String, Integer> refused = new TreeMap<>();
        int fills = 0, stepCap = 0, dayCap = 0, dry = 0, seq = 0;
        long volume = 0, minR = Long.MAX_VALUE, maxR = 0, firstR = desk.mid();
        List<long[]> weeks = new ArrayList<>();
        for (int h = 1; h <= hours; h++) {
            long at = now.addAndGet(3_600_000L);
            int n = (int) Math.max(1, Math.round(nxPerHour / (double) perTrade));
            double dev = (desk.mid() - fair) / (double) fair;
            double buyShare = Math.max(0.05, Math.min(0.95, 0.60 - dev * 8));
            for (int i = 0; i < n; i++) {
                boolean buy = rng.nextDouble() < buyShare;
                long acct = 100 + (seq % accounts);
                long nx = perTrade;
                String rid = "s" + h + "-" + i;
                Response pv = ex.preview(new Exchange.Place(rid, acct, 1_000 + (acct - 100),
                        buy ? OrderBook.Side.BUY : OrderBook.Side.SELL, 0, nx, Ticker.NX));
                if (!pv.ok()) {
                    refused.merge(pv.code(), 1, Integer::sum);
                    seq++;
                    continue;
                }
                Response f = ex.confirm("d" + h + "-" + i, acct, (String) pv.data().get("token"));
                seq++;
                if (!f.ok()) {
                    refused.merge(f.code(), 1, Integer::sum);
                    continue;
                }
                fills++;
                volume += nx;
            }
            long bps = ex.tickDesk();
            if (Math.abs(bps) >= deskCfg.maxStepBps()) {
                stepCap++;
            }
            if (Math.abs(desk.movedTodayPct()) >= deskCfg.dayCapPct() - 1e-9) {
                dayCap++;
            }
            long[] inv = ex.deskInventory();
            if (inv[0] < deskCfg.minQty() || inv[1] < 5_000_000L) {
                dry++;
            }
            minR = Math.min(minR, desk.mid());
            maxR = Math.max(maxR, desk.mid());
            if (h % 168 == 0) {
                weeks.add(new long[]{h, desk.mid(), desk.buy(), desk.sell(), inv[0], inv[1], fills, volume});
            }
        }
        assertEquals(nx0, store.totalNx(), "NX conserved");
        assertEquals(meso0, store.totalMeso(), "mesos conserved");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "reconciles: " + Exchange.reconcile(store.load(10)));
        for (var b : store.balances().entrySet()) {
            assertTrue(b.getValue() >= 0, b.getKey() + " negative");
        }
        assertTrue(minR >= NxGuard.NX_LOWEST && maxR <= NxGuard.NX_HIGHEST, "R stayed in the hard window: " + minR
                + " .. " + maxR);
        DeskRun run = new DeskRun(name, hours, fills, volume, stepCap, dayCap, dry, firstR, desk.mid(), minR, maxR,
                refused, weeks);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("SIM DESK %s: %,d NX an hour in %,d-NX trades, %d h, %d fills, %,d NX traded%n"
                        + "SIM DESK %s refused %s%nSIM DESK %s R %,d -> %,d (min %,d max %,d); step cap bound %d h, "
                        + "day cap %d h, desk dry %d h (a cap needs about %,d NX net in an hour: %.1f%% of the float)%n",
                name, nxPerHour, perTrade, hours, fills, volume, name, refused, name, firstR, desk.mid(), minR, maxR,
                stepCap, dayCap, dry, (long) (nxFloat * (double) deskCfg.maxStepBps() / deskCfg.driftBpsPerPct() / 100),
                nxFloat * (double) deskCfg.maxStepBps() / deskCfg.driftBpsPerPct() / 100 / nxFloat * 100));
        for (long[] w : weeks) {
            sb.append(String.format("SIM DESK %s h%d: R %,d buy %,d sell %,d, purse %,d NX / %,d mesos, %,d fills, "
                            + "%,d NX%n", name, w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7]));
        }
        System.out.println(sb);
        return run;
    }

    /**
     * The desk at three turnover levels for four weeks each. What the owner needs from this: at TEST's real turnover
     * (a couple of trades an hour) the drift is a basis point or two an hour, so R is stable and the caps never bind;
     * the caps only bite in a rush, and a rush is also what empties the desk's purse.
     */
    @Test
    void theDeskHoldsItsRateOverFourWeeks() throws Exception {
        DeskRun quiet = deskSimulate("TEST turnover", 2_000, 1_000, 11);
        DeskRun busy = deskSimulate("busy", 20_000, 2_500, 12);
        DeskRun rush = deskSimulate("rush", 200_000, 10_000, 13);
        for (DeskRun r : List.of(quiet, busy, rush)) {
            assertTrue(r.fills() > 100, r.name() + " trades: " + r.fills());
            assertTrue(r.maxR() - r.minR() <= r.firstR() / 50, r.name() + " R stays within 2% over four weeks: "
                    + r.minR() + " .. " + r.maxR());
        }
        assertTrue(rush.stepCapHours() > 0, "a rush is what the hourly step cap is for: " + rush.stepCapHours());
    }
}
