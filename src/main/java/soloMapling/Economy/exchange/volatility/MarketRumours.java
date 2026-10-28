package soloMapling.Economy.exchange.volatility;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 1.6.1 volatility: market rumours (behind {@code -Dslums.market.rumours}). Now and then a bot says something about a
 * ticker in map chat or a megaphone: some true, read from the exchange (a strong 24 h move, a whale-sized order on
 * the book, an event even when it is secret: that is how secrets leak), some made up ({@code fakePct}). Lines come from
 * {@code MarketRumourDialogue.yaml}. At most one rumour per {@code gapMinutes} world-wide, so chat is never spammed.
 * Trader bots that hear one may act on it ({@code actPct}): its pressure fades over two hours. Pure apart from the
 * line bank; the runtime ({@link MarketVolatility}) picks the speaker and says it.
 */
public final class MarketRumours {

    public static final String PACK = "MarketRumourDialogue.yaml";
    public static final String ROOT = "MarketRumour";
    /** How long a heard rumour still sways traders. */
    static final long HEARD_MS = 2 * 3_600_000L;
    /** A 24 h move this big (either way) is a true Rise / Crash rumour. */
    static final double BIG_MOVE = 0.08;

    public enum Claim {
        RISE("Rise", 1), CRASH("Crash", -1), WHALE_BUY("WhaleBuy", 1), WHALE_SELL("WhaleSell", -1),
        DROUGHT("Drought", 1), FLOOD("Flood", -1), ROOTY("Rooty", 1);

        public final String node;
        public final int direction;

        Claim(String node, int direction) {
            this.node = node;
            this.direction = direction;
        }

        /** Claims that only make sense for leaves (the events are leaf-only). */
        boolean leafOnly() {
            return this == DROUGHT || this == FLOOD || this == ROOTY;
        }
    }

    /** What is really going on with a ticker (what a true rumour may say). */
    public record Facts(String ticker, double move24h, boolean whaleBuy, boolean whaleSell,
                        List<MarketEvents.Kind> events) {
        List<Claim> trueClaims() {
            List<Claim> out = new ArrayList<>();
            if (move24h >= BIG_MOVE) {
                out.add(Claim.RISE);
            } else if (move24h <= -BIG_MOVE) {
                out.add(Claim.CRASH);
            }
            if (whaleBuy) {
                out.add(Claim.WHALE_BUY);
            }
            if (whaleSell) {
                out.add(Claim.WHALE_SELL);
            }
            for (MarketEvents.Kind k : events) {
                out.add(switch (k) {
                    case LEAF_RUSH -> Claim.FLOOD;
                    case LEAF_BLIGHT -> Claim.DROUGHT;
                    case ROOTY_BONUS -> Claim.ROOTY;
                });
            }
            return out;
        }
    }

    /** One rumour said. {@code fake}: made up (it may still happen to be right). */
    public record Rumour(String ticker, Claim claim, boolean fake, long at, String line) {
        /** Its pull on the price, fading linearly to 0 over {@link #HEARD_MS}. */
        double pressure(long now) {
            long age = now - at;
            return age < 0 || age >= HEARD_MS ? 0 : claim.direction * (1 - (double) age / HEARD_MS);
        }
    }

    /** The line bank: a claim's lines (with {ITEM} unfilled). */
    public interface Lines {
        List<String> of(Claim c);
    }

    private final Deque<Rumour> heard = new ArrayDeque<>();
    private long last = Long.MIN_VALUE / 2;
    private final Lines lines;

    public MarketRumours(Lines lines) {
        this.lines = lines;
    }

    /** The YAML line bank. */
    public static Lines yaml() {
        return c -> {
            try {
                var con = soloMapling.ArtificialPlayer.BotDialogueHandler.getDialogueCon(PACK, ROOT, c.node);
                return con == null || con.getDialogue() == null ? List.of() : con.getDialogue();
            } catch (RuntimeException e) {
                return List.of();
            }
        };
    }

    /** The plain words for a ticker's item in chat. */
    public static String itemWords(String ticker) {
        return switch (ticker) {
            case "LEAF" -> "leaves";
            case "CHAOS" -> "chaos scrolls";
            case "WS" -> "white scrolls";
            case "ELIXIR" -> "elixirs";
            default -> "nx";
        };
    }

    /** Is a rumour allowed now (the world-wide gap since the last one)? */
    public synchronized boolean due(long now, long gapMs) {
        return now - last >= gapMs;
    }

    /**
     * Makes one rumour about {@code f}'s ticker, or null when it is not {@link #due} yet. With probability
     * {@code fakePct} (or when nothing true is worth saying, also then) it is made up: a random claim that is not
     * among the true ones. The caller says {@link Rumour#line} and then calls {@link #said}.
     */
    public synchronized Rumour make(Facts f, long now, long gapMs, int fakePct, Random rng) {
        if (!due(now, gapMs)) {
            return null;
        }
        List<Claim> truth = f.trueClaims();
        boolean fake = truth.isEmpty() || rng.nextInt(100) < fakePct;
        Claim c;
        if (fake) {
            List<Claim> pool = new ArrayList<>();
            for (Claim x : Claim.values()) {
                if (!truth.contains(x) && (!x.leafOnly() || "LEAF".equals(f.ticker()))) {
                    pool.add(x);
                }
            }
            if (pool.isEmpty()) {
                return null;
            }
            c = pool.get(rng.nextInt(pool.size()));
        } else {
            c = truth.get(rng.nextInt(truth.size()));
        }
        String line = line(c, f.ticker(), rng);
        return line == null ? null : new Rumour(f.ticker(), c, fake, now, line);
    }

    /** A filled line for a claim, or null when the bank has none. */
    String line(Claim c, String ticker, Random rng) {
        List<String> ls = lines.of(c);
        if (ls == null || ls.isEmpty()) {
            return null;
        }
        String l = ls.get(rng.nextInt(ls.size()));
        return l.replace("{ITEM}", itemWords(ticker));
    }

    /** Records that a rumour went out (starts the gap; traders may now have heard it). */
    public synchronized void said(Rumour r) {
        last = r.at();
        heard.addLast(r);
        while (heard.size() > 50) {
            heard.removeFirst();
        }
    }

    /** The rumours still swaying traders. */
    public synchronized List<Rumour> recent(long now) {
        heard.removeIf(r -> now - r.at() >= HEARD_MS);
        return new ArrayList<>(heard);
    }

    /**
     * The pressure a trader feels on a ticker from rumours: each recent one counts with probability {@code actPct}
     * (decided once per trader and rumour, so a trader does not flip-flop on the same rumour), fading with age.
     */
    public synchronized double pressure(String ticker, long now, int actPct, long traderSeed) {
        double p = 0;
        for (Rumour r : recent(now)) {
            if (r.ticker().equals(ticker) && Math.floorMod(java.util.Objects.hash(traderSeed, r.at(), r.claim()), 100)
                    < actPct) {
                p += r.pressure(now);
            }
        }
        return p;
    }

    /** Test seam: a fixed line bank. */
    static Lines fixed(Map<Claim, List<String>> m) {
        return c -> m.getOrDefault(c, List.of());
    }
}
