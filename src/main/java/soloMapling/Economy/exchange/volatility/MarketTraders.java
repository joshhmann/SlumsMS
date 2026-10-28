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
            "ELIXIR", 6_000L);
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
        List<Candidate> pool = new ArrayList<>();
        for (Candidate c : roster) {
            if ("KILLER".equals(c.bartle()) || "ACHIEVER".equals(c.bartle())) {
                pool.add(c);
            }
        }
        pool.sort(java.util.Comparator.comparingLong(c -> mix(c.id())));
        Archetype[] killer = {Archetype.MOMENTUM, Archetype.PANIC, Archetype.WHALE, Archetype.MOMENTUM,
                Archetype.PANIC};
        Archetype[] achiever = {Archetype.VALUE, Archetype.MAKER, Archetype.MOMENTUM, Archetype.MAKER,
                Archetype.VALUE};
        int k = 0, a = 0;
        List<Trader> out = new ArrayList<>();
        for (Candidate c : pool) {
            if (out.size() >= count) {
                break;
            }
            Archetype at = "KILLER".equals(c.bartle()) ? killer[k++ % killer.length] : achiever[a++ % achiever.length];
            out.add(new Trader(c.id(), at, mix(c.id() * 31 + 7)));
        }
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
        if (items.isEmpty()) {
            return 0;
        }
        Ticker tk = items.stream().filter(k -> "LEAF".equals(k.id())).findFirst().orElse(null);
        if (tk == null || rng.nextInt(100) >= 70) {
            tk = items.get(rng.nextInt(items.size()));
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
        TraderBrain.Holdings h = new TraderBrain.Holdings(w.have(soloMapling.Economy.exchange.Ledger.Asset.MESO),
                w.have(tk.asset()), open, left, tk.maxOpen());
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
     */
    public static TraderBrain.View view(Exchange ex, Ticker tk, long now, double pressure, Long fairValue) {
        List<OrderBook.Level> bids = ex.depth(tk.id(), Side.BUY, 1), asks = ex.depth(tk.id(), Side.SELL, 1);
        long bid = bids.isEmpty() ? 0 : bids.get(0).price(), ask = asks.isEmpty() ? 0 : asks.get(0).price();
        List<FillRow> trades = ex.lastTrades(tk.id(), 500);
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
        long fair = fairValue != null && fairValue > 0 ? fairValue : fair(ex, tk, now, fallback);
        return new TraderBrain.View(tk.id(), ref, bid, ask, hourAgo, high2h, fair, pressure, tk.minPrice(),
                tk.maxPrice(), tk.maxQty(), ex.config().feeBps());
    }

    /** Fair value: the median hourly close of the last 7 days (else the fallback); LEAF leans on the shop's 500k. */
    static long fair(Exchange ex, Ticker tk, long now, long fallback) {
        List<Long> closes = new ArrayList<>();
        for (TickerHistory.Candle c : ex.candles(tk.id())) {
            if (c.hour() > now - 7 * TickerHistory.DAY_MS) {
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

    static void clearForTest() {
        leafRemainder.clear();
        traderIds.clear();
    }
}
