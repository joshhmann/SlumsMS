package soloMapling.Economy.exchange.volatility;

import soloMapling.Economy.exchange.AssetRegistry.Category;
import soloMapling.Economy.exchange.Ticker;
import soloMapling.worldevents.ActiveEvent;
import soloMapling.worldevents.EventTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 270 (owner 2026-11-03: "more events that can weigh in price driving in items"): the world events press on the
 * item books, not only on the guild news. An event is a real change in the game -- an invasion drops what it drops,
 * a boss sighting eats potions, a treasure hunt hands out a spot's item -- so the market reads it the way a trader
 * would: the goods an event gives away get cheaper (supply), the goods it burns get dearer (demand).
 * <ul>
 *   <li>{@code INVASION}, {@code BOSS_SIGHTING}, {@code GIANT_CAKE}: a boss fight burns potions (+0.4 x weight on
 *   {@link Category#USE}).</li>
 *   <li>{@code TREASURE_HUNT}, {@code HOT_SPOT}: more of the map's loot in circulation (-0.3 x weight on
 *   {@link Category#ETC}).</li>
 *   <li>{@code WEDDING}: a party spends food and potions (+0.2 x weight on {@link Category#USE}).</li>
 *   <li>Any event whose spot or reward table names a catalog item at once floods that item (-0.5 x weight x chance),
 *   which is the one line that can move an ETC or a scroll because the server itself is handing it out.</li>
 *   <li>{@code WANDERING_NPC}: the item the quest asks for is handed over (+0.3 x weight, demand); nothing else.</li>
 * </ul>
 * The pressure is pure arithmetic on a {@link Live} list, so it can be pinned in a test; {@link #running(long)} reads
 * the game's events (cached for {@link #CACHE_MS}, the scheduler reads it every few seconds and the roster is large).
 * Like the guild news it only nudges traders ({@link TraderBrain}): the orders they place are what moves the price.
 */
public final class MarketEventPressure {

    private MarketEventPressure() {
    }

    /** One running event, as the market sees it. */
    public record Live(String id, EventTemplate.Kind kind, double weight, List<Integer> itemIds, long endsAt) {
        public Live {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    /** How long the bridge trusts its reading of the game's events. */
    static final long CACHE_MS = 30_000L;

    /** The whole set of running events never moves a ticker more than this (trader pressure is about -2..2 overall). */
    static final double MAX = 1.0;

    private static volatile List<Live> cache = List.of();
    private static volatile long cacheAt = Long.MIN_VALUE / 2;

    /** The running world events as {@link Live} rows; a game without the event system (tests) gives none. */
    public static List<Live> running(long now) {
        if (now - cacheAt < CACHE_MS) {
            return cache;
        }
        List<Live> out = new ArrayList<>();
        try {
            for (ActiveEvent e : soloMapling.worldevents.WorldEvents.getInstance().active()) {
                EventTemplate t = e.template;
                if (t == null) {
                    continue;
                }
                List<Integer> ids = new ArrayList<>();
                for (EventTemplate.Spot s : t.spots()) {
                    if (s.itemId() > 0) {
                        ids.add(s.itemId());
                    }
                }
                for (EventTemplate.Reward r : t.rewards()) {
                    if (r.itemId() > 0 && r.chance() > 0) {
                        ids.add(r.itemId());
                    }
                }
                for (EventTemplate.Reward r : t.participation()) {
                    if (r.itemId() > 0 && r.chance() > 0) {
                        ids.add(r.itemId());
                    }
                }
                out.add(new Live(t.id(), t.kind(), t.weight(), ids, e.endsAt));
            }
        } catch (RuntimeException | LinkageError e) {
            return cache; // no world events in this build: the guild news is all the market knows
        }
        cache = List.copyOf(out);
        cacheAt = now;
        return cache;
    }

    /** Test seam. */
    static void resetForTest() {
        cache = List.of();
        cacheAt = Long.MIN_VALUE / 2;
    }

    /** The pressure of one event on {@code t} now; 0 once it is over. */
    static double pressure(Live e, Ticker t, long now) {
        if (e == null || t == null || (e.endsAt() > 0 && now >= e.endsAt())) {
            return 0;
        }
        double w = Math.max(0, Math.min(2, e.weight()));
        boolean named = t.item() && e.itemIds().contains(t.asset().itemId());
        if (e.kind() == EventTemplate.Kind.WANDERING_NPC) {
            return named ? 0.3 * w : 0; // the quest asks for the item: it is consumed
        }
        if (named) {
            return -0.5 * w; // the server is handing this item out: supply
        }
        Category c = t.category();
        return switch (e.kind()) {
            case INVASION, BOSS_SIGHTING, GIANT_CAKE -> c == Category.USE ? 0.4 * w : 0;
            case TREASURE_HUNT, HOT_SPOT -> c == Category.ETC ? -0.3 * w : 0;
            case WEDDING -> c == Category.USE ? 0.2 * w : 0;
            case WANDERING_NPC -> 0; // handled above: its only lever is the item it names
        };
    }

    /** The pressure of every running event on {@code t}, within +-{@link #MAX}. */
    public static double pressure(List<Live> live, Ticker t, long now) {
        if (live == null || live.isEmpty() || t == null) {
            return 0;
        }
        double p = 0;
        for (Live e : live) {
            p += pressure(e, t, now);
        }
        return Math.max(-MAX, Math.min(MAX, p));
    }
}
