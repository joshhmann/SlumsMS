package soloMapling.Economy.exchange.volatility;

import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeStore.FillRow;
import soloMapling.Economy.exchange.ExchangeStore.OrderRow;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.OrderBook;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ticker;
import soloMapling.Economy.exchange.TickerHistory;
import soloMapling.Economy.exchange.volatility.TraderBrain.Archetype;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.1 volatility: the trader bots (behind {@code -Dslums.exchange.traders}). A few roster bots (Killers and
 * Achievers by their Bartle seed) are market personalities with a {@link Archetype} each. Every min..max minutes (per
 * bot jitter) an online trader collects what the exchange owes it, picks a ticker (LEAF most often), reads the book,
 * its fills, the events it knows of and the rumours it heard, and acts through {@link Exchange#placeBot} with its own
 * leaves and mesos under every player limit (open orders, the day's count, the 10 s cooldown: a second order in one
 * turn waits for a later tick; the fee). A bot without the leaves does not sell; nothing is ever granted. Trader bots
 * also find Maple Leaves while they hunt off screen, at the real global drop chance ({@link #offscreenLeaves}).
 */
public final class MarketTraders {

    /** One trader: its bot's character id, its archetype and a seed for its own dice. */
    public record Trader(long id, Archetype archetype, long seed) {
    }

    /** Who trades, and their wallets while they are in the game (the game's roster, or a simulation's). */
    public interface Roster {
        List<Trader> traders();

        /** The trader's wallet if it is in the game right now, else null. */
        LiveWallet walletOf(Trader t);

        /** The trader's guild id (0: none), for the guild news it is part of. */
        default int guildOf(Trader t) {
            return 0;
        }
    }

    /** A ticker's price when it has never traded (until players set one): the trader's first anchor. */
    static final Map<String, Long> FALLBACK = Map.of("LEAF", 600_000L, "CHAOS", 20_000_000L, "WS", 10_000_000L,
            "ELIXIR", 6_000L, Ticker.NX, 5_000_000L);
    /**
     * 1.6.2: the band the owner set for bots on NX (2,500..10,000 mesos per NX = 2,500,000..10,000,000 per 1,000,
     * the same 50%/200% of NX's 5,000,000 start price that {@link soloMapling.Economy.exchange.NxGuard} gives players), inside the player range
     * ({@link ExchangeConfig#minPrice()}..{@link ExchangeConfig#maxPrice()}). A bot never quotes outside it.
     */
    static final long BOT_NX_MIN = 2_500_000L, BOT_NX_MAX = 10_000_000L;
    /** The NPC shops buy Maple Leaves at this: value traders anchor LEAF's fair value near it. */
    static final long LEAF_SHOP = 500_000L;

    private static final Set<Long> traderIds = ConcurrentHashMap.newKeySet();

    private final Map<Long, Long> nextAct = new HashMap<>();
    private final Map<Long, Deque<Pending>> pending = new HashMap<>();
    private final Random rng;
    private long seq;
    /** Actions taken (orders placed, cancels) and refused, for {@code !market traders}. */
    private long placed, cancelled, refused;
    private final Deque<String> journal = new ArrayDeque<>();
    private final Map<String, Integer> refusedBy = new java.util.TreeMap<>();

    private record Pending(String ticker, TraderBrain.Place place) {
    }

    /** The fair values the traders anchor to (null: the 7-day median fallback), and the guild news. */
    private FairValue fairValues;
    private MarketNews news;

    public MarketTraders(Random rng) {
        this.rng = rng;
    }

    public synchronized void use(FairValue fair, MarketNews news) {
        this.fairValues = fair;
        this.news = news;
    }

    /** Is this bot (character id) a trader right now (the off-screen leaf finds)? */
    public static boolean isTrader(long characterId) {
        return traderIds.contains(characterId);
    }

    static void setTraderIds(java.util.Collection<Long> ids) {
        traderIds.clear();
        traderIds.addAll(ids);
    }

    // ---------------------------------------------------------------- selection

    /** A roster bot that could trade: its character id and its leading Bartle type ("ACHIEVER", "KILLER", ...). */
    public record Candidate(long id, String bartle) {
    }

    /**
     * The traders out of the roster: Killers and Achievers only, in a fixed shuffled order (by id hash, so the same bots
     * every boot), the first {@code count}. Killers take the herd's roles (momentum, panic, whale), Achievers the
     * patient ones (value, maker, some momentum), in a fixed cycle so every archetype shows up.
     */
    public static List<Trader> select(List<Candidate> roster, int count) {
        return select(roster, count, false);
    }

    /**
     * 1.6.2 (owner: more trader bots): with {@code allTypes} every Bartle type trades, so a share of the bots in the
     * game is enough to fill the books (Killers and Achievers alone were about 40% of the roster). Explorers get the
     * patient roles (value, momentum, maker), Socialisers the quoting ones (maker, panic, value), an unknown type the
     * maker; the archetype cycle per type is unchanged, so the same bots keep the same style every boot.
     */
    public static List<Trader> select(List<Candidate> roster, int count, boolean allTypes) {
        List<Candidate> pool = new ArrayList<>();
        for (Candidate c : roster) {
            if (allTypes || "KILLER".equals(c.bartle()) || "ACHIEVER".equals(c.bartle())) {
                pool.add(c);
            }
        }
        pool.sort(java.util.Comparator.comparingLong(c -> mix(c.id())));
        java.util.Map<String, Integer> next = new HashMap<>();
        List<Trader> out = new ArrayList<>();
        for (Candidate c : pool) {
            if (out.size() >= count) {
                break;
            }
            Archetype[] cycle = CYCLE.getOrDefault(c.bartle(), CYCLE.get(""));
            int i = next.merge(c.bartle() == null ? "" : c.bartle(), 1, Integer::sum) - 1;
            out.add(new Trader(c.id(), cycle[i % cycle.length], mix(c.id() * 31 + 7)));
        }
        return out;
    }

    /** Each Bartle type's archetype cycle (a bot's style follows its type, and repeats in a fixed order). */
    private static final Map<String, Archetype[]> CYCLE = Map.of(
            "KILLER", new Archetype[]{Archetype.MOMENTUM, Archetype.PANIC, Archetype.WHALE, Archetype.MOMENTUM,
                    Archetype.PANIC},
            "ACHIEVER", new Archetype[]{Archetype.VALUE, Archetype.MAKER, Archetype.MOMENTUM, Archetype.MAKER,
                    Archetype.VALUE},
            "EXPLORER", new Archetype[]{Archetype.VALUE, Archetype.MOMENTUM, Archetype.MAKER},
            "SOCIALIZER", new Archetype[]{Archetype.MAKER, Archetype.PANIC, Archetype.VALUE},
            "", new Archetype[]{Archetype.MAKER, Archetype.VALUE});

    /**
     * 1.6.2: how many traders the roster should have now. A share ({@code -Dslums.exchange.traders.pct}) of the bots
     * in the game, under the count as a hard cap; without a share, the count itself (the 1.6.1 behaviour: 16).
     */
    public static int target(List<Candidate> online, MarketConfig cfg) {
        if (cfg.tradersPct() <= 0) {
            return cfg.traderCount();
        }
        int cap = cfg.traderCount() > 0 ? cfg.traderCount() : MarketConfig.MAX_TRADERS;
        if (online.isEmpty()) {
            return 0; // nobody in the game, nobody trades
        }
        long want = Math.max(1, Math.round(online.size() * cfg.tradersPct() / 100.0));
        return (int) Math.min(cap, want);
    }

    /**
     * 1.6.1 QA round 3: the traders among the bots that are in the game now. On TEST the 16 were picked from all 9,000
     * roster bots, so about one was ever online and the item books stayed empty. Traders from {@code previous} that
     * are still among {@code online} keep their place and archetype (a trader's style does not churn); the free
     * places are filled by {@link #select} from the other online candidates.
     */
    public static List<Trader> keepAndFill(List<Trader> previous, List<Candidate> online, int count) {
        return keepAndFill(previous, online, count, false);
    }

    /** As {@link #keepAndFill(List, List, int)}, with {@code allTypes} as {@link #select(List, int, boolean)}. */
    public static List<Trader> keepAndFill(List<Trader> previous, List<Candidate> online, int count,
                                           boolean allTypes) {
        java.util.Set<Long> here = new java.util.HashSet<>();
        for (Candidate c : online) {
            here.add(c.id());
        }
        List<Trader> out = new ArrayList<>();
        java.util.Set<Long> kept = new java.util.HashSet<>();
        for (Trader t : previous == null ? List.<Trader>of() : previous) {
            if (out.size() < count && here.contains(t.id()) && kept.add(t.id())) {
                out.add(t);
            }
        }
        List<Candidate> rest = new ArrayList<>();
        for (Candidate c : online) {
            if (!kept.contains(c.id())) {
                rest.add(c);
            }
        }
        out.addAll(select(rest, count - out.size(), allTypes));
        return out;
    }

    static long mix(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    // ---------------------------------------------------------------- the tick

    /**
     * One pass: each trader whose turn has come and who is in the game collects its payouts and acts; a trader with
     * a waiting second order places it once the cooldown allows. Returns the actions taken.
     */
    public synchronized int tick(Exchange ex, long now, Roster roster, MarketConfig cfg, MarketEvents events,
                                 MarketRumours rumours) {
        if (ex == null || ex.halted()) {
            return 0;
        }
        int done = 0;
        for (Trader t : roster.traders()) {
            Long next = nextAct.get(t.id());
            if (next == null) {
                // first sight: spread the first turns over one interval so traders do not all act at once
                nextAct.put(t.id(), now + (long) (rng.nextDouble() * cfg.maxActMs()));
                continue;
            }
            LiveWallet w = roster.walletOf(t);
            if (w == null) {
                pending.remove(t.id());
                continue;
            }
            if (now < next) {
                Deque<Pending> q = pending.get(t.id());
                if (q != null && !q.isEmpty()) {
                    Pending p = q.peekFirst();
                    Exchange.Response r = place(ex, t, w, p.ticker(), p.place(), now);
                    if (!"COOLDOWN".equals(r.code())) {
                        q.pollFirst();
                    }
                    done += r.ok() ? 1 : 0;
                }
                continue;
            }
            nextAct.put(t.id(), now + cfg.minActMs() + (long) (rng.nextDouble() * (cfg.maxActMs() - cfg.minActMs())));
            ex.collectBot(w);
            done += act(ex, t, w, now, cfg, events, rumours, roster.guildOf(t));
        }
        return done;
    }

    public synchronized int act(Exchange ex, Trader t, LiveWallet w, long now, MarketConfig cfg, MarketEvents events,
                                MarketRumours rumours) {
        return act(ex, t, w, now, cfg, events, rumours, 0);
    }

    /**
     * One trader's turn now (also {@code !market act} and the QA checks). A trader whose guild is in the news (a war,
     * a boss-run prep, a result) acts on that first, on the ticker it concerns ({@link TraderBrain#newsActions}); the
     * rest trade by archetype, reading the news like everyone. Returns the actions taken.
     */
    public synchronized int act(Exchange ex, Trader t, LiveWallet w, long now, MarketConfig cfg, MarketEvents events,
                                MarketRumours rumours, int guild) {
        List<Ticker> items = new ArrayList<>();
        for (Ticker k : ex.tickers()) {
            if (k.item()) {
                items.add(k);
            }
        }
        Ticker nx = cfg.tradersNx() ? ex.ticker(Ticker.NX) : null;
        if (nx != null && ex.open(nx.id())) {
            items.add(nx); // 1.6.2: bots may buy NX with mesos and sell the NX they hold (off unless -D...traders.nx)
        }
        if (items.isEmpty()) {
            return 0;
        }
        Ticker tk = items.stream().filter(k -> "LEAF".equals(k.id())).findFirst().orElse(null);
        if (tk == null || rng.nextInt(100) >= 70) {
            tk = items.get(rng.nextInt(items.size()));
        }
        // 1.6.2: trade what you hold. A bot holding a scroll (CHAOS, WS) offers that scroll this turn: until now the
        // dice alone decided, so the 27 CHAOS a bot already held never reached the book and no bot ever quoted one.
        Ticker held = heldScroll(items, w);
        if (held != null) {
            tk = held;
        } else if (nx != null && ex.botNx(w.account()) > 0 && rng.nextInt(100) < 70) {
            tk = nx; // NX a bot bought and has not sold: offer it back
        }
        MarketNews.Role role = news == null ? MarketNews.Role.NONE : news.role(guild, now);
        if (role != MarketNews.Role.NONE && rng.nextInt(100) < 80) {
            Ticker forRole = ex.ticker(newsTicker(role, w));
            if (forRole != null && ex.open(forRole.id())) {
                tk = forRole;
            } else {
                role = MarketNews.Role.NONE;
            }
        } else {
            role = MarketNews.Role.NONE;
        }
        double pressure = news == null ? 0 : news.pressure(tk.id(), now);
        if (events != null) {
            pressure += events.pressure(tk.id(), now, true);
        }
        if (rumours != null) {
            pressure += rumours.pressure(tk.id(), now, cfg.actOnRumourPct(), t.seed());
        }
        TraderBrain.View v = view(ex, tk, now, pressure, fairValues == null ? null : fairValues.fair(tk.id()));
        List<TraderBrain.Open> open = new ArrayList<>();
        for (OrderRow r : ex.openBotOrders(w.account(), tk.id())) {
            open.add(new TraderBrain.Open(r.id(), r.side(), r.price(), r.nxLeft(), r.placedAt()));
        }
        int left = Math.max(0, tk.perDay() - ex.placedToday(w.account(), tk.id()));
        long heldNow = tk.item() ? w.have(tk.asset()) : ex.botNx(w.account());
        TraderBrain.Holdings h = new TraderBrain.Holdings(w.have(soloMapling.Economy.exchange.Ledger.Asset.MESO),
                heldNow, open, left, tk.maxOpen());
        List<TraderBrain.Action> acts = role == MarketNews.Role.NONE ? List.of() : TraderBrain.newsActions(role, v, h, rng);
        if (acts.isEmpty()) {
            acts = TraderBrain.decide(t.archetype(), v, h, now, rng);
        }
        int done = 0;
        Deque<Pending> q = new ArrayDeque<>();
        boolean first = true;
        for (TraderBrain.Action a : acts) {
            if (a instanceof TraderBrain.Cancel c) {
                if (ex.cancelBot("tb-" + t.id() + "-c" + c.orderId(), w, c.orderId()).ok()) {
                    cancelled++;
                    done++;
                }
            } else if (a instanceof TraderBrain.Place p) {
                if (first) {
                    Exchange.Response r = place(ex, t, w, tk.id(), p, now);
                    done += r != null && r.ok() ? 1 : 0;
                    first = false;
                } else {
                    q.addLast(new Pending(tk.id(), p));
                }
            }
        }
        pending.put(t.id(), q);
        return done;
    }

    private Exchange.Response place(Exchange ex, Trader t, LiveWallet w, String ticker, TraderBrain.Place p,
                                    long now) {
        String rid = "tb-" + t.id() + "-" + now + "-" + (++seq);
        Exchange.Response r = ex.placeBot(new Exchange.Place(rid, w.account(), w.character(), p.side(), p.price(),
                p.qty(), ticker), w);
        if (r.ok()) {
            placed++;
            note(String.format("%s %s %s %,d %s @ %,d -> %s", t.id(), t.archetype(), p.side(), p.qty(), ticker,
                    p.price(), r.data().get("status")));
        } else {
            refused++;
            refusedBy.merge(r.code(), 1, Integer::sum);
            note(t.id() + " " + t.archetype() + " " + p.side() + " " + ticker + " refused: " + r.code());
        }
        return r;
    }

    private void note(String line) {
        journal.addLast(line);
        while (journal.size() > 30) {
            journal.removeFirst();
        }
    }

    /** The ticker a guild-news role trades: elixirs to stock up, leaves for winners, what a loser or clearer holds. */
    static String newsTicker(MarketNews.Role role, LiveWallet w) {
        return switch (role) {
            case WINNER -> "LEAF";
            case LOSER -> w.have(soloMapling.Economy.exchange.Ledger.Asset.LEAF) > 0 ? "LEAF" : "ELIXIR";
            case CLEARED -> w.have(soloMapling.Economy.exchange.Ledger.Asset.CHAOS) > 0 ? "CHAOS" : "WS";
            default -> "ELIXIR";
        };
    }

    public static TraderBrain.View view(Exchange ex, Ticker tk, long now, double pressure) {
        return view(ex, tk, now, pressure, null);
    }

    /**
     * What a trader sees of a ticker right now (see {@link TraderBrain.View}); {@code fairValue} is the ticker's
     * {@link FairValue} (null: the 7-day median, LEAF leaning on the shop's 500k). A ticker that never traded starts
     * from its fair value.
     * <p>
     * 1.6.1 exchange fix (TEST 2026-10-29): the bots only see market information inside the {@link TraderBrain#BAND}
     * around the ticker's anchor (its fair value, else its starting price). Fills a regular took part in (the QA
     * checks' test regular; the official rate leaves them out too) and fills, bids and asks outside the band are left
     * out. Before, the QA check's two 10-leaf fills at 1,000 mesos became every trader's "last", the guard (10x of the
     * reference) then dropped the value and maker quotes near 500,000, and the LEAF book locked in at 1,000 to 1,110.
     */
    public static TraderBrain.View view(Exchange ex, Ticker tk, long now, double pressure, Long fairValue) {
        long anchor = fairValue != null && fairValue > 0 ? fairValue : FALLBACK.getOrDefault(tk.id(), 0L);
        long[] band = TraderBrain.band(anchor);
        java.util.function.LongPredicate in = p -> band[1] <= 0 || (p >= band[0] && p <= band[1]);
        long bid = 0, ask = 0;
        for (OrderBook.Level l : ex.depth(tk.id(), Side.BUY, BOOK_LEVELS)) {
            if (in.test(l.price())) {
                bid = l.price();
                break;
            }
        }
        for (OrderBook.Level l : ex.depth(tk.id(), Side.SELL, BOOK_LEVELS)) {
            if (in.test(l.price())) {
                ask = l.price();
                break;
            }
        }
        List<FillRow> trades = new ArrayList<>();
        for (FillRow f : ex.lastTrades(tk.id(), 500)) {
            if (!f.buyerRegular() && !f.sellerRegular() && in.test(f.price())) {
                trades.add(f);
            }
        }
        long last = trades.isEmpty() ? 0 : trades.get(0).price();
        long hourAgo = 0, high2h = 0;
        for (FillRow f : trades) {
            if (hourAgo == 0 && f.at() <= now - TickerHistory.HOUR_MS) {
                hourAgo = f.price();
            }
            if (f.at() > now - 2 * TickerHistory.HOUR_MS) {
                high2h = Math.max(high2h, f.price());
            }
        }
        long fallback = fairValue != null && fairValue > 0 ? fairValue : FALLBACK.getOrDefault(tk.id(), 1_000L);
        long ref = last > 0 ? last : bid > 0 && ask > 0 ? (bid + ask) / 2 : fallback;
        if (hourAgo == 0 && !trades.isEmpty()) {
            hourAgo = trades.get(trades.size() - 1).price(); // younger than an hour: from the oldest we have
        }
        long fair = fairValue != null && fairValue > 0 ? fairValue : fair(ex, tk, now, fallback, in);
        long lo = tk.minPrice(), hi = tk.maxPrice();
        if (Ticker.NX.equals(tk.id())) { // the bots' band is narrower than the players' range (owner 2026-10-28)
            lo = Math.max(lo, BOT_NX_MIN);
            hi = Math.min(hi, BOT_NX_MAX);
        }
        return new TraderBrain.View(tk.id(), ref, bid, ask, hourAgo, high2h, fair, pressure, lo, hi, tk.maxQty(),
                ex.config().feeBps(), band[0], band[1], tk.per(), tk.lot(), tk.minQty());
    }

    /** The scroll tickers trader bots trade, in the order off-screen finds are rolled (1.6.2). */
    static final java.util.List<String> SCROLL_IDS = java.util.List.of("CHAOS", "WS");

    /** The scroll tickers trader bots trade (1.6.2); anything else they hold is still taken by the dice. */
    private static final java.util.Set<String> SCROLLS = java.util.Set.copyOf(SCROLL_IDS);

    /**
     * The scroll this bot holds most of, among the open item tickers; null when it holds none (the dice pick, then).
     * Leaves and potions are left to the dice on purpose: almost every trader holds some, and preferring them would
     * only concentrate the whole market on LEAF.
     */
    static Ticker heldScroll(List<Ticker> open, LiveWallet w) {
        Ticker best = null;
        long most = 0;
        for (Ticker k : open) {
            if (!k.item() || !SCROLLS.contains(k.id())) {
                continue;
            }
            long have = w.have(k.asset());
            if (have > most) {
                most = have;
                best = k;
            }
        }
        return best;
    }

    /** How deep a trader looks for the best in-band bid and ask. */
    static final int BOOK_LEVELS = 50;

    static long fair(Exchange ex, Ticker tk, long now, long fallback) {
        return fair(ex, tk, now, fallback, p -> true);
    }

    /** Fair value: the median hourly close of the last 7 days (else the fallback); LEAF leans on the shop's 500k. */
    static long fair(Exchange ex, Ticker tk, long now, long fallback, java.util.function.LongPredicate inBand) {
        List<Long> closes = new ArrayList<>();
        for (TickerHistory.Candle c : ex.candles(tk.id())) {
            if (c.hour() > now - 7 * TickerHistory.DAY_MS && inBand.test(c.close())) {
                closes.add(c.close());
            }
        }
        long median = fallback;
        if (!closes.isEmpty()) {
            closes.sort(Long::compare);
            median = closes.get(closes.size() / 2);
        }
        if ("LEAF".equals(tk.id())) {
            return (LEAF_SHOP * 11 / 10 + median) / 2;
        }
        return median;
    }

    /** Status lines for {@code !market traders}. */
    public synchronized List<String> status(Roster roster, Exchange ex) {
        List<String> out = new ArrayList<>();
        int online = 0;
        for (Trader t : roster.traders()) {
            LiveWallet w = roster.walletOf(t);
            online += w != null ? 1 : 0;
            out.add(String.format("  %d %s %s%s", t.id(), t.archetype(), w == null ? "offline"
                    : String.format("%,d mesos, %,d leaves", w.have(soloMapling.Economy.exchange.Ledger.Asset.MESO),
                    w.have(soloMapling.Economy.exchange.Ledger.Asset.LEAF)),
                    ex == null ? "" : ", open " + ex.openIdsOf(t.id()).size()));
        }
        out.add(0, "Traders: " + roster.traders().size() + " (" + online + " online); orders placed " + placed
                + ", cancelled " + cancelled + ", refused " + refused + (refusedBy.isEmpty() ? "" : " " + refusedBy));
        out.add("Last actions:");
        journal.forEach(j -> out.add("  " + j));
        return out;
    }

    public synchronized long placedCount() {
        return placed;
    }

    /** Refused orders by the exchange's code (LIMIT_OPEN, COOLDOWN, INSUFFICIENT_FUNDS, ...). */
    public synchronized Map<String, Integer> refusedByCode() {
        return new java.util.TreeMap<>(refusedBy);
    }

    // ---------------------------------------------------------------- off-screen leaf finds

    private static final Map<Long, Double> leafRemainder = new ConcurrentHashMap<>();

    /**
     * How many Maple Leaves a trader bot finds while hunting off screen for {@code elapsedMs}: the bots' off-screen
     * loot leaves out the global drop table, so traders get the leaf's own global chance (out of 1,000,000, under the
     * running Rush or Blight) per kill at {@code killsPerMin}, times the leaf's average stack. Fractions carry over per
     * bot. Pure arithmetic; the caller grants through the bot's yield caps and holdings.
     */
    public static int offscreenLeaves(long botId, long elapsedMs, double killsPerMin, int chance, double avgQty) {
        if (elapsedMs <= 0 || chance <= 0 || killsPerMin <= 0) {
            return 0;
        }
        double owed = killsPerMin * elapsedMs / 60_000.0 * chance / 1_000_000.0 * Math.max(1, avgQty)
                + leafRemainder.getOrDefault(botId, 0.0);
        int n = (int) Math.floor(owed);
        leafRemainder.put(botId, owed - n);
        return n;
    }

    // ---------------------------------------------------------------- off-screen scroll finds

    private static final Map<Long, double[]> scrollOwed = new ConcurrentHashMap<>();

    /**
     * How many of the scroll at {@code slot} ({@link #SCROLL_IDS}) a trader bot finds while hunting off screen for
     * {@code elapsedMs}: {@code perKill} is the summed chance out of 1,000,000 that one of its kills drops it (its
     * map's mobs' real drop_data rows and global drops, the world's rate in), rolled at {@code killsPerMin}, and
     * {@code findRate} is the owner's share of that real chance ({@link MarketConfig#scrollFindRate}: a quarter since
     * 2026-10-31, 1 = the real rate, 0 = none). Fractions carry over per bot and scroll, so a rare drop lands
     * eventually rather than never. The caller grants through the bot's yield caps and holdings.
     */
    public static int offscreenScroll(long botId, int slot, long elapsedMs, double killsPerMin, double perKill,
                                      double findRate) {
        if (elapsedMs <= 0 || perKill <= 0 || killsPerMin <= 0 || findRate <= 0 || slot < 0
                || slot >= SCROLL_IDS.size()) {
            return 0;
        }
        double[] owed = scrollOwed.computeIfAbsent(botId, k -> new double[SCROLL_IDS.size()]);
        owed[slot] += killsPerMin * elapsedMs / 60_000.0 * perKill * findRate / 1_000_000.0;
        int n = (int) Math.floor(owed[slot]);
        owed[slot] -= n;
        return n;
    }

    static void clearForTest() {
        leafRemainder.clear();
        scrollOwed.clear();
        traderIds.clear();
    }
}
