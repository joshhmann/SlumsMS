package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotGossip;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * 1.6.0 S7: the bots' own drama that their friends talk about - a rage quit after a boom and the sheepish return, a
 * friend who "changed" (crossed into the Killer quadrant, {@link VillainArc}) or turned chill again, a couple's
 * breakup. Memory only: the newest {@link #MAX} items, each told for {@link #TTL_MS}.
 *
 * <p>{@link #factFor} grounds a bot thread ({@code BotThreads.Topic.FRIEND_DRAMA}) in one of these: the teller talks
 * about a friend of its own (its FRIEND bonds or crew), never about the two speakers, and the fact names only that
 * friend - so the name guard has nothing to rewrite.
 */
public final class DramaNews {

    public enum Kind {
        RAGE_QUIT("NewsRageQuit", "rage quit after a boom"),
        BACK("NewsBack", "came back after rage quitting"),
        TURNED("NewsTurned", "changed lately, always ksing now"),
        REDEEMED("NewsRedeemed", "turned chill again"),
        BREAKUP("NewsBreakup", "broke up");

        final String node;
        final String words;

        Kind(String node, String words) {
            this.node = node;
            this.words = words;
        }
    }

    /** One piece of drama: who, what, when ({@code detail}: an item that boomed, an ex's name; may be null). */
    public record Item(Kind kind, int botId, String name, String detail, long at) {
    }

    static final int MAX = 64;
    static final long TTL_MS = TimeUnit.HOURS.toMillis(12);

    private static final Deque<Item> items = new ArrayDeque<>();
    private static volatile IntFunction<Collection<Integer>> circle = DramaNews::circleOf;

    private DramaNews() {
    }

    /** Something happened to this bot that its friends may talk about. */
    public static void add(Kind kind, int botId, String name, String detail, long at) {
        if (name == null || name.isBlank()) {
            return;
        }
        synchronized (items) {
            items.removeIf(i -> i.botId() == botId && i.kind() == kind);
            items.addLast(new Item(kind, botId, name, detail, at));
            while (items.size() > MAX) {
                items.pollFirst();
            }
        }
        BeefKit.qaAny("NEWS " + kind.name().toLowerCase(Locale.ROOT) + " " + name + (detail == null ? "" : " (" + detail + ")"));
    }

    /** The items still told now, newest last. */
    public static List<Item> recent(long now) {
        List<Item> out = new ArrayList<>();
        synchronized (items) {
            for (Item i : items) {
                if (now - i.at() <= TTL_MS) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    /** The newest item about this bot of this kind, or null. */
    public static Item latest(int botId, Kind kind, long now) {
        Item found = null;
        for (Item i : recent(now)) {
            if (i.botId() == botId && i.kind() == kind) {
                found = i;
            }
        }
        return found;
    }

    /**
     * What {@code teller} tells {@code other} about a friend's drama, or null when its circle has none. Fresher news
     * weighs more. Names only the friend it is about.
     */
    public static BotGossip.Fact factFor(Character teller, Character other, long now, Random rng) {
        Collection<Integer> mine = circle.apply(teller.getId());
        List<Item> told = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Item i : recent(now)) {
            if (i.botId() == teller.getId() || i.botId() == other.getId() || !mine.contains(i.botId())) {
                continue;
            }
            told.add(i);
            weights.add(1.0 - 0.7 * Math.min(1, (double) (now - i.at()) / TTL_MS));
        }
        if (told.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double w : weights) {
            sum += w;
        }
        double roll = rng.nextDouble() * sum;
        Item pick = told.getLast();
        for (int k = 0; k < told.size(); k++) {
            roll -= weights.get(k);
            if (roll < 0) {
                pick = told.get(k);
                break;
            }
        }
        return fact(pick);
    }

    /** The thread fact for one item: its opener from the line bank (the friend named as {OTHER}). */
    static BotGossip.Fact fact(Item i) {
        String opener = DramaKit.pick(i.kind().node, Map.of("OTHER", i.name()));
        if (opener == null) {
            opener = i.name() + " " + i.kind().words + " lol";
        }
        boolean negative = i.kind() != Kind.REDEEMED && i.kind() != Kind.BACK;
        return new BotGossip.Fact("drama-" + i.kind().name().toLowerCase(Locale.ROOT), i.name() + " " + i.kind().words,
                opener, List.of(i.name()), i.botId(), negative);
    }

    /** A bot's friends and crew (memory only). */
    private static Collection<Integer> circleOf(int botId) {
        List<Integer> out = new ArrayList<>(BotBonds.friendsOf(botId));
        for (int id : BotBonds.crewOf(botId)) {
            if (id != botId && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    // ── test seams ──

    static void resetForTest(IntFunction<Collection<Integer>> testCircle) {
        synchronized (items) {
            items.clear();
        }
        circle = testCircle != null ? testCircle : DramaNews::circleOf;
    }

    /** Public test seam (BotThreads tests): forget every item and use this circle (null: the real one). */
    public static void useCircleForTest(IntFunction<Collection<Integer>> testCircle) {
        resetForTest(testCircle);
    }
}
