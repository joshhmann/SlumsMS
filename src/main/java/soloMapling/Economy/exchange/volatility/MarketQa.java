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
}
