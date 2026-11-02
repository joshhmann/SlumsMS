package soloMapling.qa;

import client.Character;
import soloMapling.Economy.exchange.Desk;
import soloMapling.Economy.exchange.DeskConfig;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeQa;
import soloMapling.Economy.exchange.ExchangeService;
import soloMapling.Economy.exchange.GameLiveWallet;
import soloMapling.Economy.exchange.Ledger;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.OrderBook;
import soloMapling.Economy.exchange.Ticker;

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
        return List.of(new InGame(), new Leaf(), new Volatility(), new Scroll(), new DeskQuote());
    }

    /**
     * xc-3 (1.6.1 volatility): a secret 2-minute Leaf Rush changes the Maple Leaf's drop chance and ends; an online
     * trader bot posts a small LEAF bid from its own mesos and cancels it. {@code !qa do check} runs it again. Then
     * watch {@code !market traders}, {@code !market events} and {@code !market event rush} for the real thing.
     */
    static final class Volatility implements QaScenario {
        public String id() { return "xc-3"; }
        public String title() { return "Market volatility: Leaf Rush changes the leaf rate, a trader bot posts an order"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 10; }

        public List<String> stage(QaContext ctx) {
            List<String> r = soloMapling.Economy.exchange.volatility.MarketQa.check(System.currentTimeMillis());
            if (r.get(0).startsWith("SKIP")) {
                throw new QaRefusal(r.get(0).substring(5));
            }
            r.forEach(l -> ctx.log("XC3 " + l));
            List<String> out = new java.util.ArrayList<>(r);
            out.add("Next: !market event rush 10 (announced) and watch the notice; !market traders shows the traders "
                    + "and their last orders; !market act makes one act now; !market rumour says one rumour.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equals(name)) {
                throw new QaRefusal("xc-3 steps: check");
            }
            List<String> r = soloMapling.Economy.exchange.volatility.MarketQa.check(System.currentTimeMillis());
            ctx.run().note("XC3", String.join(" | ", r));
            return r;
        }
    }

    /**
     * xc-5 (162-xtraders, owner 2026-10-31 "waking up chaos and WS"): a trader that holds a Chaos or White Scroll
     * offers that scroll on its turn, and its sell rests, prices inside the band, escrows exactly what it holds and
     * comes back on a cancel. {@code !qa do check} runs it again. With no online holder it refuses and says why (a
     * trader finds scrolls hunting at the real drop chance, so this needs a while).
     */
    static final class Scroll implements QaScenario {
        public String id() { return "xc-5"; }
        public String title() { return "Scroll trading: a trader that holds a Chaos or White Scroll lists it"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 10; }

        public List<String> stage(QaContext ctx) {
            List<String> r = soloMapling.Economy.exchange.volatility.MarketQa.scrollCheck(System.currentTimeMillis());
            if (r.get(0).startsWith("SKIP")) {
                throw new QaRefusal(r.get(0).substring(5));
            }
            r.forEach(l -> ctx.log("XC5 " + l));
            List<String> out = new java.util.ArrayList<>(r);
            out.add("Next: !market traders shows which bots hold a scroll and their last orders; !market act makes one "
                    + "act now; watch the CHAOS/WS books for a bot's ask.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equals(name)) {
                throw new QaRefusal("xc-5 steps: check");
            }
            List<String> r = soloMapling.Economy.exchange.volatility.MarketQa.scrollCheck(System.currentTimeMillis());
            ctx.run().note("XC5", String.join(" | ", r));
            return r;
        }
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
        private boolean desk;
        private long deskBuy0, deskSell0, deskNx0, deskMeso0;

        public String id() { return "xc-1"; }
        public String title() {
            return "NX exchange in game: @nx sell into a test regular (or, with the desk on, buy then sell at the desk's "
                    + "quote), payout lands online, cancel refunds";
        }
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
            desk = ExchangeQa.deskOn(ex);
            if (desk) {
                return stageDesk(ctx, ex, w);
            }
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
            // b264: the resting buy is priced at the effective band minimum (the ticker's raw 1,000 is under the hard
            // window's 2,500 per 1,000 NX and the exchange refuses it with PRICE_BAND)
            long restMin = ExchangeQa.restingBuyPrice(ex);
            return List.of(
                    "A test regular (paid from your mesos: " + st.funded() + ") bids "
                            + soloMapling.Economy.exchange.Exchange.perNx(st.bidPrice()) + " mesos per NX for 100 NX.",
                    "1) @nx sell 100 " + soloMapling.Economy.exchange.ExchangeChat.typedNx(st.bidPrice()) + "   then   @nx confirm   -> it fills; "
                            + ExchangeQa.sellerGets(ex.config(), 100, st.bidPrice())
                            + " mesos arrive at once (no logout), 100 NX leave.",
                    "2) wait 10 s, @nx buy 100 " + soloMapling.Economy.exchange.ExchangeChat.typedNx(restMin) + "   then   @nx confirm   -> it rests and holds "
                            + ex.config().buyTotal(100, restMin) + " mesos.",
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
            long[] inv = ex.deskInventory();
            String line = desk
                    ? "XC1 CHECK desk nx " + nx0 + " -> " + nx + ", meso " + meso0 + " -> " + meso + " (the spread: "
                            + (deskBuy0 - deskSell0) + " mesos per 1,000 NX), the desk " + deskNx0 + " -> " + inv[0]
                            + " NX and " + deskMeso0 + " -> " + inv[1] + " mesos, open orders "
                            + ex.openIdsOf(w.account()).size() + (ex.halted() ? ", HALTED " + ex.haltReason() : "")
                    : "XC1 CHECK nx " + nx0 + " -> " + nx + ", meso " + meso0 + " -> " + meso + ", open orders "
                            + ex.openIdsOf(w.account()).size() + (ex.halted() ? ", HALTED " + ex.haltReason() : "");
            ctx.run().note("XC1", line);
            return List.of(line);
        }

        /**
         * The desk branch of {@link #stage}: the NX book is off under the desk (ticket 162 §4), so nothing rests and
         * there is no regular to take out. The GM buys {@link ExchangeQa#DESK_NX} NX at the desk's sell quote and
         * sells it straight back at its buy quote, so the same NX and (all but the spread) the same mesos come back.
         */
        private List<String> stageDesk(QaContext ctx, Exchange ex, LiveWallet w) {
            Desk deskBook = ex.desk();
            DeskConfig dc = deskBook.config();
            long nx = ExchangeQa.DESK_NX;
            long[] inv = ex.deskInventory();
            long buyMesos, sellMesos;
            try {
                buyMesos = deskBook.mesos(OrderBook.Side.BUY, nx, deskBook.sell());
                sellMesos = deskBook.mesos(OrderBook.Side.SELL, nx, deskBook.buy());
            } catch (ArithmeticException e) {
                throw new QaRefusal(nx + " NX is more than one desk trade can carry: " + e.getMessage());
            }
            if (inv[0] < nx) {
                throw new QaRefusal("The desk has " + inv[0] + " NX to sell, need " + nx
                        + ". Staff: !exchange desk fund nx 50000.");
            }
            if (inv[1] + buyMesos < sellMesos) {
                throw new QaRefusal("The desk has " + inv[1] + " mesos to buy the " + nx + " NX back, need "
                        + sellMesos + ". Staff: !exchange desk fund meso 50000000.");
            }
            long need = buyMesos + dc.fee(buyMesos); // the BUY's cost, fees included
            if (meso0 < need) {
                throw new QaRefusal("You need " + need + " mesos to buy " + nx + " NX at the desk's "
                        + Exchange.perNx(deskBook.sell()) + " per 1,000 (you have " + meso0 + ").");
            }
            deskBuy0 = deskBook.buy();
            deskSell0 = deskBook.sell();
            deskNx0 = inv[0];
            deskMeso0 = inv[1];
            ctx.log("XC1 START desk nx=" + nx0 + " meso=" + meso0 + " buy=" + deskBuy0 + " sell=" + deskSell0
                    + " deskNx=" + deskNx0 + " deskMeso=" + deskMeso0);
            ctx.onStop("note that the desk check leaves nothing resting", () -> {
                Exchange e = ExchangeService.exchange();
                long[] now = e == null ? new long[]{-1, -1} : e.deskInventory();
                ctx.run().note("CLEANUP", "XC1 desk: nothing to take out (the desk fills at once); the desk holds "
                        + now[0] + " NX, " + now[1] + " mesos");
            });
            return List.of(
                    "The NX desk is on, so the NX book is off: nothing rests and there is no test regular. You trade "
                            + nx + " NX twice at the desk's own quote (buy " + Exchange.perNx(deskBuy0)
                            + " / sell " + Exchange.perNx(deskSell0) + " mesos per 1,000 NX, mid "
                            + Exchange.perNx(deskBook.mid()) + ").",
                    "1) @nx buy " + nx + "   then   @nx confirm   -> " + nx + " NX arrive and " + buyMesos
                            + " mesos leave (at once).",
                    "2) @nx sell " + nx + "   then   @nx confirm   -> " + sellMesos + " mesos arrive and the NX leaves.",
                    "!qa do check shows what moved: the same NX, the same desk inventory, and only the spread ("
                            + (deskBuy0 - deskSell0) + " mesos per 1,000 NX) plus "
                            + (dc.fee(buyMesos) + dc.fee(sellMesos)) + " mesos of fees gone.");
        }
    }

    /**
     * xd-1 (1.6.3, ticket 162): the NX desk trades at one quote. The GM previews 1,000 NX on whichever side the desk
     * can pay for and types {@code @nx confirm}; it fills at once and the NX and the mesos move by the quote, not by a
     * book. {@code !qa do check} prints what moved, and stop trades the 1,000 NX back at the new quote, so the only
     * cost is the spread. Needs {@code -Dslums.exchange.desk=true} and a house account with inventory in the desk:
     * {@code !exchange desk fund nx 50000} and {@code !exchange desk fund meso 50000000}.
     */
    static final class DeskQuote implements QaScenario {
        private OrderBook.Side side = OrderBook.Side.BUY;
        private long qty, price, mesos, nx0, meso0, deskNx0, deskMeso0;

        public String id() { return "xd-1"; }
        public String title() { return "NX desk: @nx confirm fills 1,000 NX at the desk's quote; the wallet and the desk's inventory move by it"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public Set<String> claims(Character gm, String[] args) { return Set.of("nx-desk-test"); }

        public List<String> stage(QaContext ctx) {
            Exchange ex = ExchangeService.exchange();
            if (ex == null) {
                throw new QaRefusal("The exchange is off on this server (-Dslums.exchange=true).");
            }
            Desk desk = ex.desk();
            if (desk == null) {
                throw new QaRefusal("The NX desk is off (-Dslums.exchange.desk=true).");
            }
            if (ex.closeReason() != null) {
                throw new QaRefusal("The NX desk is closed: " + ex.closeReason() + ".");
            }
            LiveWallet w = GameLiveWallet.of(ctx.gm());
            if (w == null) {
                throw new QaRefusal("Run this from your own logged-in character.");
            }
            long[] inv = ex.deskInventory();
            qty = 1_000;
            long m = desk.mesos(OrderBook.Side.BUY, qty, desk.sell());
            side = inv[0] >= qty ? OrderBook.Side.BUY : inv[1] >= m ? OrderBook.Side.SELL : null;
            if (side == null) {
                throw new QaRefusal("The NX desk has nothing to trade with (it holds " + inv[0] + " NX and " + inv[1]
                        + " mesos): !exchange desk fund nx 50000 and !exchange desk fund meso 50000000.");
            }
            price = desk.quote(side);
            mesos = desk.mesos(side, qty, price);
            long fee = desk.config().fee(mesos);
            Ledger.Asset pays = side == OrderBook.Side.BUY ? Ledger.Asset.MESO : Ledger.Asset.NX;
            long takes = side == OrderBook.Side.BUY ? mesos + fee : qty;
            if (w.have(pays) < takes) {
                throw new QaRefusal("You need " + takes + " " + (pays == Ledger.Asset.NX ? "NX" : "mesos")
                        + " for " + qty + " NX at the desk's quote (you have " + w.have(pays) + ").");
            }
            nx0 = w.have(Ledger.Asset.NX);
            meso0 = w.have(Ledger.Asset.MESO);
            deskNx0 = inv[0];
            deskMeso0 = inv[1];
            Exchange.Response pv = ex.preview(new Exchange.Place("qa-xd1", w.account(), w.character(), side, 0, qty,
                    Ticker.NX));
            if (!pv.ok()) {
                throw new QaRefusal("the desk's preview: " + pv.code() + " " + pv.message());
            }
            ctx.log("XD1 START side=" + side + " nx=" + qty + " price=" + price + " mid=" + desk.mid() + " mesos="
                    + mesos + " fee=" + fee + " wallet nx=" + nx0 + " meso=" + meso0 + " desk nx=" + deskNx0
                    + " meso=" + deskMeso0);
            ctx.onStop("trade the " + qty + " NX back at the new quote", () -> {
                LiveWallet back = GameLiveWallet.of(ctx.gmNow());
                Exchange e = ExchangeService.exchange();
                if (e == null || back == null || e.desk() == null) {
                    ctx.run().note("CLEANUP", "GM offline or the desk gone: the " + qty + " NX stays traded");
                    return;
                }
                OrderBook.Side undo = side == OrderBook.Side.BUY ? OrderBook.Side.SELL : OrderBook.Side.BUY;
                Exchange.Response p = e.preview(new Exchange.Place("qa-xd1-back", back.account(), back.character(),
                        undo, 0, qty, Ticker.NX));
                Exchange.Response f = p.ok()
                        ? e.confirm("qa-xd1-back-c", back.account(), (String) p.data().get("token")) : p;
                ctx.run().note("CLEANUP", "XD1 back " + undo + " " + qty + " NX: " + (f.ok() ? "done" : f.code())
                        + ", NX now " + back.have(Ledger.Asset.NX) + " (was " + nx0 + "), mesos now "
                        + back.have(Ledger.Asset.MESO) + " (was " + meso0 + "; the spread is the cost)");
            });
            String per = Exchange.perNx(price);
            return List.of(
                    "The desk quotes " + Exchange.perNx(desk.buy()) + " to buy and " + Exchange.perNx(desk.sell())
                            + " to sell (mesos per NX), mid " + Exchange.perNx(desk.mid()) + "; it holds " + deskNx0
                            + " NX. Your preview holds " + qty + " NX at " + per + " mesos per NX.",
                    "1) @nx confirm   -> " + (side == OrderBook.Side.BUY ? "you buy" : "you sell") + " " + qty
                            + " NX at once for " + mesos + " mesos (no order rests, no book).",
                    "2) @nx   -> the desk's quote and what it holds now.",
                    "3) !qa do check   -> what moved in your wallet and in the desk.",
                    "!qa stop trades the " + qty + " NX back at the new quote: you end where you started minus the "
                            + "spread.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equals(name)) {
                throw new QaRefusal("xd-1 steps: check");
            }
            LiveWallet w = GameLiveWallet.of(ctx.gmNow());
            Exchange ex = ExchangeService.exchange();
            if (w == null || ex == null || ex.desk() == null) {
                throw new QaRefusal("Log in and keep the exchange and the desk on.");
            }
            Desk desk = ex.desk();
            long nx = w.have(Ledger.Asset.NX), meso = w.have(Ledger.Asset.MESO);
            long[] inv = ex.deskInventory();
            long fee = desk.config().fee(mesos);
            long wantNx = side == OrderBook.Side.BUY ? qty : -qty;
            long wantMeso = side == OrderBook.Side.BUY ? -(mesos + fee) : mesos - fee;
            String line = "XD1 CHECK " + side + " " + qty + " NX at " + price + ": NX " + nx0 + " -> " + nx + " (want "
                    + wantNx + "), mesos " + meso0 + " -> " + meso + " (want " + wantMeso + "); desk " + deskNx0
                    + " NX/" + deskMeso0 + " mesos -> " + inv[0] + "/" + inv[1] + "; mid " + desk.mid() + "; "
                    + ex.deskLine();
            ctx.run().note(wantNx == nx - nx0 && wantMeso == meso - meso0 ? "XD1 PASS" : "XD1 FAIL", line);
            return List.of(line);
        }
    }
}
