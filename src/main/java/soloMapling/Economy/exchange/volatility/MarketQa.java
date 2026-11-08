package soloMapling.Economy.exchange.volatility;

import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeService;
import soloMapling.Economy.exchange.ExchangeStore.OrderRow;
import soloMapling.Economy.exchange.AssetRegistry.Asset;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ticker;

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

    /** t-xc-4: the mesos a trader holds for the check (its news buy is 12-22% of them, so a few elixirs' worth). */
    static final long WAR_MESOS = 100_000;

    /**
     * t-xc-4 / {@code !qa run xc-4} (guild war shock, owner 2026-10-28): on a private news desk (no Cody notice, no
     * banner, nothing else sees it) a war is declared for an online trader's guild; the trader's role reads as at war,
     * ELIXIR's pressure goes up, and its news action is an ELIXIR buy from its own mesos. The check then posts that
     * buy as 1 elixir below the best bid (so it rests and never fills), sees it on the book and cancels it.
     */
    public static List<String> warCheck(long now) {
        MarketVolatility mv = MarketVolatility.get();
        Exchange ex = ExchangeService.exchange();
        if (mv == null || ex == null || !mv.config().traders()) {
            return List.of("SKIP trader bots are off (-Dslums.exchange=true -Dslums.exchange.traders=true)");
        }
        return warCheck(ex, mv.gameRoster(now), now, ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * The check itself, self-sufficient (1.6.1 QA round 3): it takes an online trader that is not inside the 10 s
     * order cooldown (one that is waits it out once), and a trader short of {@link #WAR_MESOS} is topped up for the
     * check from QA and the top-up is taken back exactly at the end (nothing minted is left behind).
     */
    static List<String> warCheck(Exchange ex, MarketTraders.Roster roster, long now, java.util.function.LongConsumer sleep) {
        List<String> out = new ArrayList<>();
        if (!ex.open("ELIXIR")) {
            return List.of("SKIP the ELIXIR ticker is closed (-Dslums.exchange.tickers)");
        }
        long cooldown = ex.config().cooldownMs();
        record Cand(MarketTraders.Trader t, LiveWallet w, long last, long mesos) {
        }
        List<Cand> cands = new ArrayList<>();
        for (MarketTraders.Trader t : roster.traders()) {
            LiveWallet w = roster.walletOf(t);
            if (w == null || !w.locked(w::usable)) {
                continue;
            }
            long last = 0;
            try {
                for (OrderRow o : ex.myOrders(w.account(), 5)) {
                    last = Math.max(last, o.placedAt());
                }
            } catch (Exception e) {
                // unknown: treat as ready, a COOLDOWN answer is handled below
            }
            cands.add(new Cand(t, w, last, w.have(Asset.MESO)));
        }
        out.add(rosterLine(roster));
        if (cands.isEmpty()) {
            out.add(0, "FAIL no online trader (" + roster.traders().size() + " traders)");
            return out;
        }
        // ready (outside the cooldown) first, then the richest (the least top-up)
        cands.sort(java.util.Comparator.comparing((Cand c) -> now - c.last() < cooldown)
                .thenComparing(Cand::mesos, java.util.Comparator.reverseOrder()));
        for (int i = 0; i < Math.min(3, cands.size()); i++) {
            Cand c = cands.get(i);
            String r = warTry(ex, roster, c.t(), c.w(), now, out);
            if (!"COOLDOWN".equals(r)) {
                return out;
            }
        }
        // every one tried was inside its cooldown (the traders act on their own): wait it out once and retry the first
        out.add("all tried traders were in the " + cooldown / 1000 + " s order cooldown: waiting it out once");
        sleep.accept(cooldown + 1_000);
        Cand c = cands.get(0);
        String r = warTry(ex, roster, c.t(), c.w(), now + cooldown + 1_000, out);
        if ("COOLDOWN".equals(r)) {
            out.add(0, "FAIL the traders stayed in the order cooldown");
        }
        return out;
    }

    /** Evidence: every trader's archetype and, when online, what it holds (why the books are thin or full). */
    static String rosterLine(MarketTraders.Roster roster) {
        StringBuilder sb = new StringBuilder();
        int online = 0;
        for (MarketTraders.Trader t : roster.traders()) {
            LiveWallet w = roster.walletOf(t);
            sb.append(sb.length() == 0 ? "" : "; ").append(t.id()).append(' ').append(t.archetype());
            if (w == null) {
                sb.append(" offline");
                continue;
            }
            online++;
            sb.append(String.format(" %,d mesos, leaf %d chaos %d ws %d elixir %d", w.have(Asset.MESO),
                    w.have(Asset.LEAF), w.have(Asset.CHAOS), w.have(Asset.WS), w.have(Asset.ELIXIR)));
        }
        return "traders " + roster.traders().size() + ", online " + online + ": " + sb;
    }

    /**
     * t-xc-5 / {@code !qa run xc-5} (162-xtraders, owner 2026-10-31: "waking up chaos and WS"): a trader that holds a
     * Chaos or White Scroll offers that scroll on the next turn instead of rolling the LEAF dice - until 1.6.2 the dice
     * alone decided, so no bot ever quoted one of the 27 Chaos Scrolls bots already held and the ticker had no fills.
     * The check reads every online holder's pick ({@link MarketTraders#heldItem}), then places and cancels one SELL
     * of the scroll through the exchange exactly as the trader would (its own holdings, the price band, 1% fee), and
     * sees the scroll come back. Nothing is minted: with no holder online it SKIPs and says why.
     */
    public static List<String> scrollCheck(long now) {
        MarketVolatility mv = MarketVolatility.get();
        Exchange ex = ExchangeService.exchange();
        if (mv == null || ex == null || !mv.config().traders()) {
            return List.of("SKIP trader bots are off (-Dslums.exchange=true -Dslums.exchange.traders=true)");
        }
        return scrollCheck(ex, mv.gameRoster(now), now);
    }

    /** The check itself, against a roster and a clock (tests pass a fixed clock). First line PASS / FAIL / SKIP. */
    static List<String> scrollCheck(Exchange ex, MarketTraders.Roster roster, long now) {
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        List<Ticker> scrolls = new ArrayList<>();
        for (Ticker k : ex.tickers()) {
            if (k.item() && ex.open(k.id()) && MarketTraders.SCROLL_IDS.contains(k.id())) {
                scrolls.add(k);
            }
        }
        if (scrolls.isEmpty()) {
            return List.of("SKIP no scroll ticker is open (-Dslums.exchange.tickers)");
        }
        record Cand(MarketTraders.Trader t, LiveWallet w, Ticker k) {
        }
        List<Cand> holders = new ArrayList<>();
        int online = 0;
        for (MarketTraders.Trader t : roster.traders()) {
            LiveWallet w = roster.walletOf(t);
            if (w == null) {
                continue;
            }
            online++;
            for (Ticker k : scrolls) {
                if (w.have(k.asset()) > 0) {
                    holders.add(new Cand(t, w, k));
                }
            }
        }
        if (online == 0) {
            return List.of("SKIP no trader is online (" + roster.traders().size() + " traders)");
        }
        String noHolder = holders.isEmpty() ? "no online trader holds a Chaos or White Scroll yet (a trader finds them "
                + "by hunting at the real drop chance, about 2.2 per trader-hour)" : null;
        // a trader holding both scrolls offers the one it holds most of (heldItem): judge each trader once, on that
        // pick, which must be a scroll it really holds with no other held scroll outnumbering it
        List<Cand> picks = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Cand h0 : holders) {
            if (!seen.add(h0.t.id())) {
                continue;
            }
            Ticker pick = MarketTraders.heldItem(scrolls, h0.w());
            StringBuilder held = new StringBuilder();
            long most = 0;
            for (Ticker k : scrolls) {
                long n = h0.w().have(k.asset());
                most = Math.max(most, n);
                if (n > 0) {
                    held.append(held.length() > 0 ? " and " : "").append(n).append(' ').append(k.id());
                }
            }
            out.add("trader " + h0.t.id() + " (" + h0.t.archetype() + ") holds " + held + "; its pick is "
                    + (pick == null ? "none" : pick.id()));
            if (pick == null) {
                bad.add("trader " + h0.t.id() + " held a scroll and picked no scroll at all");
            } else if (h0.w().have(pick.asset()) < most) {
                bad.add("trader " + h0.t.id() + " picked " + pick.id() + " though it holds more of another scroll");
            } else {
                picks.add(new Cand(h0.t, h0.w(), pick));
            }
        }
        // a real order through the exchange, from a holder's own holdings: the first SELL its own decision makes. An
        // archetype that never sells a held scroll (MOMENTUM waits for a trend), a trader inside its order cooldown (the
        // other checks and the traders' own turns order too) or one whose account is at the daily item-sell ceiling
        // (b375: no sell of its own can post today) is passed over for the next holder.
        boolean judged = false;
        int cooling = 0, capped = 0;
        for (Cand c : picks) {
            TraderBrain.Place sell = firstSell(ex, c.t, c.w, c.k, now);
            if (sell == null) {
                continue;
            }
            String st = judgeSell(ex, c.t, c.w, c.k, sell, now, out, bad);
            if ("COOLDOWN".equals(st)) {
                cooling++;
                continue;
            }
            if ("ITEMS_CAP".equals(st)) {
                capped++;
                continue;
            }
            judged = true;
            break;
        }
        // organic first, staging as the fallback (owner 2026-11-04): nothing organic could be judged, so one online
        // trader whose archetype sells a held scroll is handed ONE scroll for the check, through the wallet's own
        // give/take (the path t-xc-4's mesos top-up uses; the order itself still goes through the exchange), and the
        // scroll is taken back at the end whatever happened.
        if (!judged && bad.isEmpty()) {
            if (noHolder != null) {
                out.add(noHolder + ": staging a scroll");
            } else {
                out.add("no organic holder could be judged (" + picks.size() + " holder(s)"
                        + (cooling > 0 ? ", " + cooling + " in the order cooldown" : "")
                        + (capped > 0 ? (cooling > 0 ? ", " : ", ") + capped + " at the daily item-sell cap" : "")
                        + (cooling == 0 && capped == 0 ? ", none whose archetype sells it" : "")
                        + "): staging a scroll");
            }
            int coolStaged = 0, capStaged = 0;
            staging:
            for (MarketTraders.Trader t : roster.traders()) {
                LiveWallet w = roster.walletOf(t);
                if (w == null || !Boolean.TRUE.equals(w.locked(w::usable))) {
                    continue;
                }
                for (Ticker k : scrolls) {
                    String st = stageOne(ex, t, w, k, scrolls, now, out, bad);
                    if ("COOLDOWN".equals(st)) {
                        coolStaged++;
                        continue staging;
                    }
                    if ("ITEMS_CAP".equals(st)) {
                        capStaged++;
                        continue staging;
                    }
                    if ("DONE".equals(st)) {
                        judged = true;
                        break staging;
                    }
                }
            }
            if (!judged && bad.isEmpty()) {
                String blocked = coolStaged > 0 && capStaged > 0
                        ? "inside the order cooldown or at the daily item-sell cap"
                        : coolStaged > 0 ? "inside the order cooldown"
                        : capStaged > 0 ? "at the daily item-sell cap" : null;
                out.add(0, blocked != null
                        ? "SKIP every trader whose archetype sells a scroll was " + blocked + "; run this again"
                        : "SKIP no online trader's archetype sells a scroll on a flat market (" + online
                        + " online); run this again");
                return out;
            }
        }
        if (!judged && bad.isEmpty()) {
            bad.add("no trader could be judged");
        }
        out.add(0, bad.isEmpty() ? "PASS a scroll holder lists its scroll" : "FAIL " + String.join("; ", bad));
        return out;
    }

    /** Hands the trader 1 scroll, runs the sell if its archetype makes one, takes the scroll back. Null/COOLDOWN/DONE. */
    private static String stageOne(Exchange ex, MarketTraders.Trader t, LiveWallet w, Ticker k, List<Ticker> scrolls,
                                   long now, List<String> out, List<String> bad) {
        long before = w.have(k.asset());
        if (!Boolean.TRUE.equals(w.locked(() -> w.give(k.asset(), 1)))) {
            return null;
        }
        String result = null;
        try {
            if (w.have(k.asset()) != before + 1 || MarketTraders.heldItem(scrolls, w) != k) {
                return null;
            }
            TraderBrain.Place sell = firstSell(ex, t, w, k, now);
            if (sell == null) {
                return null;
            }
            out.add("staged 1 " + k.id() + " in trader " + t.id() + " (" + t.archetype() + ") for the check (taken back "
                    + "at the end)");
            result = judgeSell(ex, t, w, k, sell, now, out, bad);
            return result;
        } finally {
            boolean took = false;
            try {
                took = Boolean.TRUE.equals(w.locked(() -> w.take(k.asset(), 1) && w.saveNow()));
            } catch (RuntimeException e) {
                // reported below
            }
            if (!took) {
                bad.add("the staged " + k.id() + " could not be taken back from trader " + t.id());
                out.add("could not take back the staged " + k.id() + " (trader at " + w.have(k.asset()) + ")");
            } else {
                out.add("took back the staged " + k.id() + " (trader at " + w.have(k.asset()) + ")");
            }
        }
    }

    /**
     * Places the sell through the exchange and judges post, hold, band and cancel. "COOLDOWN", "ITEMS_CAP" or "DONE".
     */
    private static String judgeSell(Exchange ex, MarketTraders.Trader t, LiveWallet w, Ticker tk,
                                    TraderBrain.Place sell, long now, List<String> out, List<String> bad) {
        long have0 = w.have(tk.asset()), meso0 = w.have(Asset.MESO);
        Exchange.Response r = ex.placeBot(new Exchange.Place("qa-xc5-" + t.id() + "-" + now, w.account(),
                w.character(), Side.SELL, sell.price(), sell.qty(), tk.id()), w);
        if ("COOLDOWN".equals(r.code())) {
            out.add("trader " + t.id() + " is inside the order cooldown: trying the next one");
            return "COOLDOWN";
        }
        if ("ITEMS_CAP".equals(r.code())) {
            // b375: the holder's account already has its day's item-sell ceiling in filled or resting orders, so no
            // sell of its own can post today. That is the holder's state, not a fault of the sell being checked: pass
            // it over like the cooldown instead of failing on the refusal.
            out.add("trader " + t.id() + " is at the daily item-sell cap (" + r.message() + "): trying the next one");
            return "ITEMS_CAP";
        }
        if (!r.ok()) {
            out.add("the sell of " + sell.qty() + " " + tk.id() + " at " + sell.price() + " was refused: "
                    + r.code() + " " + r.message());
            bad.add("the trader's " + tk.id() + " sell was refused: " + r.code());
            return "DONE";
        }
        long id = ((Number) r.data().get("orderId")).longValue();
        boolean onBook = ex.openBotOrders(w.account(), tk.id()).stream().anyMatch(o -> o.id() == id);
        long held = have0 - w.have(tk.asset());
        boolean inBand = sell.price() >= tk.minPrice() && sell.price() <= tk.maxPrice();
        Exchange.Response can = ex.cancelBot("qa-xc5-cancel-" + id, w, id);
        long back = w.have(tk.asset());
        out.add("sold " + sell.qty() + " " + tk.id() + " at " + sell.price() + " (order " + id
                + (onBook ? ", on the book" : ", NOT on the book") + (inBand ? "" : ", OUTSIDE the band "
                + tk.minPrice() + ".." + tk.maxPrice()) + "), " + held + " escrowed from its own holdings; "
                + "cancel " + can.code() + ", " + tk.id() + " " + have0 + " -> " + back + ", mesos "
                + meso0 + " -> " + w.have(Asset.MESO));
        if (!onBook || held != sell.qty() || !inBand || !can.ok() || back != have0) {
            bad.add("the trader's " + tk.id() + " order did not post, hold, price in band and cancel cleanly");
        }
        return "DONE";
    }

    /** The first SELL a trader's own decision makes for a scroll it holds over a handful of seeds, or null. */
    private static TraderBrain.Place firstSell(Exchange ex, MarketTraders.Trader t, LiveWallet w, Ticker tk, long now) {
        TraderBrain.View v = MarketTraders.view(ex, tk, now, 0, FairValue.of(tk.id()));
        List<TraderBrain.Open> open = new ArrayList<>();
        for (OrderRow r : ex.openBotOrders(w.account(), tk.id())) {
            open.add(new TraderBrain.Open(r.id(), r.side(), r.price(), r.nxLeft(), r.placedAt()));
        }
        TraderBrain.Holdings h = new TraderBrain.Holdings(w.have(Asset.MESO), w.have(tk.asset()), open,
                Math.max(0, tk.perDay() - ex.placedToday(w.account(), tk.id())), tk.maxOpen());
        for (int seed = 1; seed <= 20; seed++) {
            for (TraderBrain.Action a : TraderBrain.decide(t.archetype(), v, h, now, new java.util.Random(seed))) {
                if (a instanceof TraderBrain.Place p && p.side() == Side.SELL) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * One trader's go: tops it up to {@link #WAR_MESOS} if short, runs the war news, posts and cancels the bid, and
     * takes the top-up back. Adds the verdict as line 0 and returns null, or returns "COOLDOWN" (nothing added at 0).
     */
    private static String warTry(Exchange ex, MarketTraders.Roster roster, MarketTraders.Trader t, LiveWallet w,
                                 long now, List<String> out) {
        long meso0 = w.have(Asset.MESO);
        long top = Math.max(0, WAR_MESOS - meso0);
        if (top > 0) {
            if (!w.locked(() -> w.give(Asset.MESO, top))) {
                out.add(0, "FAIL could not top up trader " + t.id() + " with " + top + " mesos");
                return null;
            }
            out.add("trader " + t.id() + " had " + meso0 + " mesos: topped up " + top + " for the check (taken back at the end)");
        }
        String verdict;
        boolean cooldown = false;
        try {
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
                verdict = "FAIL the war news did not turn into an elixir buy";
            } else {
                var bids = ex.depth("ELIXIR", Side.BUY, 1);
                long price = Math.max(1, bids.isEmpty() ? Math.min(v.reference() / 2, 1_000) : bids.get(0).price() / 2);
                long before = w.have(Asset.MESO);
                Exchange.Response r = ex.placeBot(new Exchange.Place("qa-xc4-" + t.id() + "-" + now, w.account(),
                        w.character(), Side.BUY, price, 1, "ELIXIR"), w);
                if (!r.ok()) {
                    out.add("trader " + t.id() + " could not post: " + r.code() + " " + r.message());
                    cooldown = "COOLDOWN".equals(r.code());
                    verdict = "FAIL the trader's elixir bid was refused: " + r.code();
                } else {
                    long id = ((Number) r.data().get("orderId")).longValue();
                    boolean onBook = ex.openBotOrders(w.account(), "ELIXIR").stream().anyMatch(o -> o.id() == id);
                    Exchange.Response c = ex.cancelBot("qa-xc4-cancel-" + id, w, id);
                    long back = w.have(Asset.MESO);
                    out.add("posted 1 elixir at " + price + " (order " + id + (onBook ? ", on the book" : ", NOT on the book")
                            + "), cancel " + c.code() + ", mesos " + before + " -> " + back);
                    verdict = onBook && c.ok() && back == before ? "PASS guild war news drives a trader's elixir buy"
                            : "FAIL the trader's elixir order did not post, cancel and refund cleanly";
                }
            }
        } finally {
            if (top > 0) {
                boolean took = w.locked(() -> w.take(Asset.MESO, top) && w.saveNow());
                out.add(took ? "took back the " + top + " topped-up mesos (trader at " + w.have(Asset.MESO) + ")"
                        : "could not take back the " + top + " topped-up mesos");
                if (!took) {
                    out.add(0, "FAIL the " + top + " QA mesos could not be taken back from trader " + t.id());
                    return null;
                }
            }
        }
        if (cooldown) {
            return "COOLDOWN";
        }
        out.add(0, verdict);
        return null;
    }
}
