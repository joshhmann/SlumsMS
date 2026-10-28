package soloMapling.qa;

import client.Character;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeQa;
import soloMapling.Economy.exchange.ExchangeService;
import soloMapling.Economy.exchange.GameLiveWallet;
import soloMapling.Economy.exchange.Ledger;
import soloMapling.Economy.exchange.LiveWallet;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.6.1, ticket 113 round 2: xc-1, the NX exchange in game. Stages a test regular, funded from the GM's own mesos
 * (nothing minted), bidding for 100 NX; the GM sells into it with {@code @nx sell} + {@code @nx confirm} and sees the
 * mesos arrive at once, then rests a buy and cancels it. {@code !qa do check} prints what moved. Stop takes the test
 * regular out and pays its house back to the GM. The auto-QA check t-xc-1 runs the same steps.
 */
final class ExchangeScenarios {
    private ExchangeScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new InGame(), new Leaf());
    }

    /**
     * xc-2 (tickers, owner 2026-10-28): the LEAF book. The test regular (funded from the GM's mesos) bids for 10 Maple
     * Leaves; the GM sells 10 into it ({@code @nx leaf sell}), rests another 10 and cancels them. Stop takes the regular
     * out and its 10 leaves come back. The auto-QA check t-xc-2 runs the same steps.
     */
    static final class Leaf implements QaScenario {
        private long leaves0, meso0;

        public String id() { return "xc-2"; }
        public String title() { return "Exchange LEAF ticker: @nx leaf sell escrows leaves, fill pays mesos, cancel returns leaves"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 15; }
        public Set<String> claims(Character gm, String[] args) { return Set.of("nx-exchange-test-regular"); }

        public List<String> stage(QaContext ctx) {
            Exchange ex = ExchangeService.exchange();
            if (ex == null) {
                throw new QaRefusal("The exchange is off on this server (-Dslums.exchange=true).");
            }
            if (!ex.open(ExchangeQa.ITEM_TICKER)) {
                throw new QaRefusal("The LEAF ticker is closed (-Dslums.exchange.tickers).");
            }
            LiveWallet w = GameLiveWallet.of(ctx.gm());
            if (w == null) {
                throw new QaRefusal("Run this from your own logged-in character.");
            }
            Ledger.Asset leaf = Ledger.Asset.LEAF;
            leaves0 = w.have(leaf);
            meso0 = w.have(Ledger.Asset.MESO);
            if (leaves0 < 2 * ExchangeQa.ITEMS) {
                throw new QaRefusal("You need at least " + 2 * ExchangeQa.ITEMS + " tradeable Maple Leaves (you have "
                        + leaves0 + "; !item 4001126 20).");
            }
            ExchangeQa.StagedItem st;
            try {
                st = ExchangeQa.stageItem(ex, w, ExchangeQa.ITEM_TICKER, ExchangeQa.ITEMS);
            } catch (IllegalStateException e) {
                throw new QaRefusal(e.getMessage());
            }
            ctx.log("XC2 START leaves=" + leaves0 + " meso=" + meso0 + " bid=" + st.bidPrice() + " order="
                    + st.bidOrder() + " funded=" + st.funded());
            ctx.onStop("take the test regular out and give its leaves and mesos back", () -> {
                LiveWallet back = GameLiveWallet.of(ctx.gmNow());
                Exchange e = ExchangeService.exchange();
                if (e == null || back == null) {
                    ctx.run().note("CLEANUP", "GM offline or exchange gone: the test regular stays until the next xc run");
                    return;
                }
                ctx.run().note("CLEANUP", "XC2 test regular out: " + ExchangeQa.teardown(e, back));
            });
            var t = ex.ticker(ExchangeQa.ITEM_TICKER);
            return List.of(
                    "A test regular (paid from your mesos: " + st.funded() + ") bids " + st.bidPrice()
                            + " mesos per leaf for 10 Maple Leaves.",
                    "1) @nx leaf sell 10 " + st.bidPrice() + "   then   @nx confirm   -> 10 leaves leave your bags, it "
                            + "fills, " + ExchangeQa.sellerGets(ex.config(), t, 10, st.bidPrice()) + " mesos arrive.",
                    "2) wait 10 s, @nx leaf sell 10 " + t.maxPrice() + "   then   @nx confirm   -> it rests and takes "
                            + "10 more leaves.",
                    "3) @nx orders, then @nx cancel <that order id>   -> the 10 leaves come straight back.",
                    "!qa do check shows what moved. !qa stop takes the regular out: its 10 leaves come back, so you end "
                            + "with the same leaves and exactly two 1% fees fewer mesos.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equals(name)) {
                throw new QaRefusal("xc-2 steps: check");
            }
            LiveWallet w = GameLiveWallet.of(ctx.gmNow());
            Exchange ex = ExchangeService.exchange();
            if (w == null || ex == null) {
                throw new QaRefusal("Log in and keep the exchange on.");
            }
            String line = "XC2 CHECK leaves " + leaves0 + " -> " + w.have(Ledger.Asset.LEAF) + ", meso " + meso0
                    + " -> " + w.have(Ledger.Asset.MESO) + ", open orders " + ex.openIdsOf(w.account()).size()
                    + (ex.halted() ? ", HALTED " + ex.haltReason() : "");
            ctx.run().note("XC2", line);
            return List.of(line);
        }
    }

    static final class InGame implements QaScenario {
        private long nx0, meso0;

        public String id() { return "xc-1"; }
        public String title() { return "NX exchange in game: @nx sell into a test regular, payout lands online, cancel refunds"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 15; }
        public Set<String> claims(Character gm, String[] args) { return Set.of("nx-exchange-test-regular"); }

        public List<String> stage(QaContext ctx) {
            Exchange ex = ExchangeService.exchange();
            if (ex == null) {
                throw new QaRefusal("The NX exchange is off on this server (-Dslums.exchange=true).");
            }
            LiveWallet w = GameLiveWallet.of(ctx.gm());
            if (w == null) {
                throw new QaRefusal("Run this from your own logged-in character.");
            }
            nx0 = w.have(Ledger.Asset.NX);
            meso0 = w.have(Ledger.Asset.MESO);
            if (nx0 < ExchangeQa.NX) {
                throw new QaRefusal("You need at least " + ExchangeQa.NX + " NX credit to sell (you have " + nx0 + ").");
            }
            ExchangeQa.Staged st;
            try {
                st = ExchangeQa.stage(ex, w);
            } catch (IllegalStateException e) {
                throw new QaRefusal(e.getMessage());
            }
            ctx.log("XC1 START nx=" + nx0 + " meso=" + meso0 + " bid=" + st.bidPrice() + " order=" + st.bidOrder()
                    + " funded=" + st.funded());
            ctx.onStop("take the test regular out and pay its house back", () -> {
                Character now = ctx.gmNow();
                LiveWallet back = GameLiveWallet.of(now);
                Exchange e = ExchangeService.exchange();
                if (e == null || back == null) {
                    ctx.run().note("CLEANUP", "GM offline or exchange gone: the test regular stays until the next xc-1");
                    return;
                }
                Map<String, Object> td = ExchangeQa.teardown(e, back);
                ctx.run().note("CLEANUP", "XC1 test regular out: " + td);
            });
            long cfgMin = ex.config().minPrice();
            return List.of(
                    "A test regular (paid from your mesos: " + st.funded() + ") bids " + st.bidPrice()
                            + " mesos per 1,000 NX for 100 NX.",
                    "1) @nx sell 100 " + st.bidPrice() + "   then   @nx confirm   -> it fills; "
                            + ExchangeQa.sellerGets(ex.config(), 100, st.bidPrice())
                            + " mesos arrive at once (no logout), 100 NX leave.",
                    "2) wait 10 s, @nx buy 100 " + cfgMin + "   then   @nx confirm   -> it rests and holds "
                            + ex.config().buyTotal(100, cfgMin) + " mesos.",
                    "3) @nx orders, then @nx cancel <that order id>   -> the mesos come straight back.",
                    "!qa do check shows what moved. !qa stop takes the regular out: you end with the same NX and "
                            + "exactly two 1% fees fewer mesos.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equals(name)) {
                throw new QaRefusal("xc-1 steps: check");
            }
            LiveWallet w = GameLiveWallet.of(ctx.gmNow());
            Exchange ex = ExchangeService.exchange();
            if (w == null || ex == null) {
                throw new QaRefusal("Log in and keep the exchange on.");
            }
            long nx = w.have(Ledger.Asset.NX), meso = w.have(Ledger.Asset.MESO);
            String line = "XC1 CHECK nx " + nx0 + " -> " + nx + ", meso " + meso0 + " -> " + meso + ", open orders "
                    + ex.openIdsOf(w.account()).size() + (ex.halted() ? ", HALTED " + ex.haltReason() : "");
            ctx.run().note("XC1", line);
            return List.of(line);
        }
    }
}
