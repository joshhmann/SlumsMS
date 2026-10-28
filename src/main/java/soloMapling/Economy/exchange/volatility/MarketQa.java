package soloMapling.Economy.exchange.volatility;

import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeService;
import soloMapling.Economy.exchange.ExchangeStore.OrderRow;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.6.1 volatility: the check shared by {@code !qa run xc-3} and auto-QA t-xc-3. (1) A secret 2-minute Leaf Rush
 * starts and the Maple Leaf's drop chance reads x the multiplier; it ends and the chance is back. (2) An online trader
 * bot posts a small LEAF bid from its own mesos (below the best bid, so it rests and never fills), the order is on the
 * book as the bot's, then it is cancelled and the mesos are back in the bot. First line PASS / FAIL / SKIP.
 */
public final class MarketQa {

    private MarketQa() {
    }

    public static List<String> check(long now) {
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        MarketEvents ev = MarketEvents.get();
        MarketVolatility mv = MarketVolatility.get();
        Exchange ex = ExchangeService.exchange();
        if (!ev.enabled()) {
            return List.of("SKIP market events are off (-Dslums.market.events=true)");
        }
        if (mv == null || ex == null || !mv.config().traders()) {
            return List.of("SKIP trader bots are off (-Dslums.exchange=true -Dslums.exchange.traders=true)");
        }
        // (1) the event changes the rate and expires
        int base = 500;
        boolean rushWas = ev.isActive(MarketEvents.Kind.LEAF_RUSH, now);
        boolean blightWas = ev.isActive(MarketEvents.Kind.LEAF_BLIGHT, now);
        int before = ev.leafChance(base, now);
        if (!rushWas && !blightWas) {
            ev.start(MarketEvents.Kind.LEAF_RUSH, 2, false, now);
            int during = ev.leafChance(base, now);
            ev.end(MarketEvents.Kind.LEAF_RUSH, now);
            int after = ev.leafChance(base, now);
            int want = (int) Math.round(base * ev.config().leafRushMultiplier());
            out.add("Leaf Rush (secret, 2 min): leaf chance " + before + " -> " + during + " -> " + after
                    + " per million (want " + want + " during)");
            if (before != base || during != want || after != base) {
                bad.add("the Leaf Rush did not change the leaf chance as it should");
            }
        } else {
            out.add("a Rush or Blight is already running (chance " + before + "): event part not re-staged");
        }
        // (2) a trader bot posts an order from its own mesos
        MarketTraders.Roster roster = mv.gameRoster(now);
        String leaf = "LEAF";
        if (!ex.open(leaf)) {
            return List.of("SKIP the LEAF ticker is closed (-Dslums.exchange.tickers)");
        }
        var bids = ex.depth(leaf, Side.BUY, 1);
        long price = Math.max(1_000, bids.isEmpty() ? 1_000 : TraderBrain.tidy(bids.get(0).price() / 2));
        boolean posted = false;
        int online = 0;
        for (MarketTraders.Trader t : roster.traders()) {
            LiveWallet w = roster.walletOf(t);
            if (w == null) {
                continue;
            }
            online++;
            long meso0 = w.have(Asset.MESO);
            if (meso0 < price * 2) {
                continue;
            }
            Exchange.Response r = ex.placeBot(new Exchange.Place("qa-xc3-" + t.id() + "-" + now, w.account(),
                    w.character(), Side.BUY, price, 1, leaf), w);
            if (!r.ok()) {
                out.add("trader " + t.id() + " could not post: " + r.code() + " " + r.message());
                continue;
            }
            long id = ((Number) r.data().get("orderId")).longValue();
            boolean onBook = ex.openBotOrders(w.account(), leaf).stream().map(OrderRow::id).anyMatch(x -> x == id);
            long held = meso0 - w.have(Asset.MESO);
            Exchange.Response c = ex.cancelBot("qa-xc3-cancel-" + id, w, id);
            long back = w.have(Asset.MESO);
            out.add("trader " + t.id() + " (" + t.archetype() + ") bid " + price + " for 1 leaf: order " + id
                    + (onBook ? " on the book" : " NOT on the book") + ", " + held + " mesos held from its own wallet; "
                    + "cancel " + c.code() + ", mesos " + meso0 + " -> " + back);
            if (!onBook || held <= 0 || !c.ok() || back != meso0) {
                bad.add("the trader's order did not post, hold or come back cleanly");
            }
            posted = true;
            break;
        }
        if (!posted) {
            bad.add("no trader bot posted (" + roster.traders().size() + " traders, " + online + " online)");
        }
        out.add(0, bad.isEmpty() ? "PASS market event and trader bot order" : "FAIL " + String.join("; ", bad));
        return out;
    }

    /**
     * t-xc-4 / {@code !qa run xc-4} (guild war shock, owner 2026-10-28): on a private news desk (no Cody notice, no
     * banner, nothing else sees it) a war is declared for an online trader's guild; the trader's role reads as at war,
     * ELIXIR's pressure goes up, and its news action is an ELIXIR buy from its own mesos. The check then posts that
     * buy as 1 elixir below the best bid (so it rests and never fills), sees it on the book and cancels it.
     */
    public static List<String> warCheck(long now) {
        List<String> out = new ArrayList<>();
        MarketVolatility mv = MarketVolatility.get();
        Exchange ex = ExchangeService.exchange();
        if (mv == null || ex == null || !mv.config().traders()) {
            return List.of("SKIP trader bots are off (-Dslums.exchange=true -Dslums.exchange.traders=true)");
        }
        if (!ex.open("ELIXIR")) {
            return List.of("SKIP the ELIXIR ticker is closed (-Dslums.exchange.tickers)");
        }
        MarketTraders.Roster roster = mv.gameRoster(now);
        for (MarketTraders.Trader t : roster.traders()) {
            LiveWallet w = roster.walletOf(t);
            if (w == null || w.have(Asset.MESO) < 100_000) {
                continue;
            }
            int guild = roster.guildOf(t) > 0 ? roster.guildOf(t) : 999_999;
            MarketNews desk = MarketNews.forTest(() -> now, s -> { }, false);
            desk.declared(-1, guild, "QaGuildA", guild + 1, "QaGuildB", now + 3_600_000L);
            MarketNews.Role role = desk.role(guild, now);
            double p = desk.pressure("ELIXIR", now);
            var tk = ex.ticker("ELIXIR");
            TraderBrain.View v = MarketTraders.view(ex, tk, now, p, FairValue.of("ELIXIR"));
            TraderBrain.Holdings h = new TraderBrain.Holdings(w.have(Asset.MESO), w.have(Asset.ELIXIR), List.of(),
                    Math.max(0, tk.perDay() - ex.placedToday(w.account(), "ELIXIR")), tk.maxOpen());
            List<TraderBrain.Action> acts = TraderBrain.newsActions(role, v, h, new java.util.Random(now));
            out.add("trader " + t.id() + " (guild " + guild + "): role " + role + ", ELIXIR pressure " + p
                    + ", news action " + acts);
            boolean buys = acts.stream().anyMatch(a -> a instanceof TraderBrain.Place pl && pl.side() == Side.BUY);
            if (role != MarketNews.Role.AT_WAR_FRESH || p <= 0 || !buys) {
                out.add(0, "FAIL the war news did not turn into an elixir buy");
                return out;
            }
            var bids = ex.depth("ELIXIR", Side.BUY, 1);
            long price = Math.max(1, bids.isEmpty() ? Math.min(v.reference() / 2, 1_000) : bids.get(0).price() / 2);
            Exchange.Response r = ex.placeBot(new Exchange.Place("qa-xc4-" + t.id() + "-" + now, w.account(),
                    w.character(), Side.BUY, price, 1, "ELIXIR"), w);
            if (!r.ok()) {
                out.add("could not post: " + r.code() + " " + r.message());
                continue;
            }
            long id = ((Number) r.data().get("orderId")).longValue();
            boolean onBook = ex.openBotOrders(w.account(), "ELIXIR").stream().anyMatch(o -> o.id() == id);
            Exchange.Response c = ex.cancelBot("qa-xc4-cancel-" + id, w, id);
            out.add("posted 1 elixir at " + price + " (order " + id + (onBook ? ", on the book" : ", NOT on the book")
                    + "), cancel " + c.code());
            out.add(0, onBook && c.ok() ? "PASS guild war news drives a trader's elixir buy"
                    : "FAIL the trader's elixir order did not post and cancel cleanly");
            return out;
        }
        out.add(0, "FAIL no online trader with 100,000 mesos (" + roster.traders().size() + " traders)");
        return out;
    }
}
