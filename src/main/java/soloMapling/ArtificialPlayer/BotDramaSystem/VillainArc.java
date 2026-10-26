package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 S7: the villain arc and its redemption ("that's the difference between a hero and a hero becoming a villain",
 * owner, Bartle as a chart). Slice S9's learning emits a QUADRANT_CROSSED life event when a bot's chart point settles
 * in another quadrant; this is the hook it calls ({@link QuadrantCrossed}, {@link #LISTENER}):
 * <pre>
 *   LifeLearning.onCrossing(e -&gt; VillainArc.LISTENER.crossed(e.botId(), e.crossing().from(), e.crossing().to(),
 *           e.seed(), System.currentTimeMillis()));
 * </pre>
 * <ul>
 *   <li><b>Into the Killer quadrant (turned):</b> for {@link #ARC_MS} (7 days) the bot starts more beef
 *   ({@link #boost} +{@link #TURNED_BOOST} on every Beef roll), its friends notice - news for their threads ("he
 *   changed man", {@link DramaNews}) and, when one stands with it on a watched map, a line to its face - and one old
 *   friend may drift (a spat on their bond, half the time).</li>
 *   <li><b>Out of it (redeemed):</b> the bot starts less beef for 7 days ({@link #REDEEMED_BOOST}), friends say it is
 *   chill now.</li>
 * </ul>
 * Crossings between the other three quadrants change nothing here. Memory only (S9 saves the crossing itself).
 */
public final class VillainArc {

    /** S9's QUADRANT_CROSSED, as S7 hears it. */
    @FunctionalInterface
    public interface QuadrantCrossed {
        void crossed(int botId, SocialProfile.Type from, SocialProfile.Type to, SocialProfile.Type seed, long at);
    }

    /** What S9 calls. */
    public static final QuadrantCrossed LISTENER = VillainArc::crossed;

    static final long ARC_MS = TimeUnit.DAYS.toMillis(7);
    static final double TURNED_BOOST = 0.3;
    static final double REDEEMED_BOOST = -0.2;

    /** bot id -> {boost, until}. */
    private static final Map<Integer, double[]> arcs = new ConcurrentHashMap<>();

    private VillainArc() {
    }

    /** A bot's chart point crossed into another quadrant. */
    public static void crossed(int botId, SocialProfile.Type from, SocialProfile.Type to, SocialProfile.Type seed, long at) {
        if (!BeefKit.enabled() || from == null || to == null || from == to) {
            return;
        }
        boolean turned = to == SocialProfile.Type.KILLER;
        boolean redeemed = from == SocialProfile.Type.KILLER;
        if (!turned && !redeemed) {
            return;
        }
        arcs.put(botId, new double[]{turned ? TURNED_BOOST : REDEEMED_BOOST, at + ARC_MS});
        if (arcs.size() > 4_000) {
            arcs.values().removeIf(v -> v[1] < at);
        }
        Character bot = BeefKit.online(botId);
        String name = bot != null ? bot.getName() : soloMapling.ArtificialPlayer.BotBuddies.nameOf(botId);
        DramaNews.add(turned ? DramaNews.Kind.TURNED : DramaNews.Kind.REDEEMED, botId, name, from + "->" + to, at);
        BeefKit.qaAny("VILLAIN " + (turned ? "TURNED " : "REDEEMED ") + name + ": " + from + " -> " + to
                + (seed == null ? "" : " (seed " + seed + ")"));
        List<Integer> friends = BotBonds.friendsOf(botId);
        if (turned && !friends.isEmpty() && ThreadLocalRandom.current().nextBoolean()) {
            int drifting = friends.get(ThreadLocalRandom.current().nextInt(friends.size()));
            BotBonds.record(drifting, botId, BondEvent.SPAT, at, false); // an old friend drifts a little
        }
        if (bot != null && bot.getMap() != null && DramaKit.realPlayerOnMap(bot)) {
            soloMapling.server.ExecutorServiceManager.runAsync(() -> notice(bot, friends, turned)); // S9's thread: O(1)
        }
    }

    /** A friend standing with the bot on a watched map says something about it. */
    static void notice(Character bot, List<Integer> friends, boolean turned) {
        for (int f : friends) {
            Character friend = BeefKit.online(f);
            if (friend != null && friend.getMapId() == bot.getMapId() && friend.getMap() == bot.getMap()) {
                String line = DramaKit.say(friend, turned ? "VillainNotice" : "RedeemedNotice",
                        Map.of("OTHER", bot.getName()));
                BeefKit.qa(bot.getMapId(), "VILLAIN NOTICE " + friend.getName() + " -> " + bot.getName() + ": " + line);
                return;
            }
        }
    }

    /** The bot's Beef roll bonus now: +0.3 after it turned, -0.2 after redemption, 0 otherwise. */
    public static double boost(int botId, long now) {
        double[] a = arcs.get(botId);
        if (a == null) {
            return 0;
        }
        if (now >= a[1]) {
            arcs.remove(botId);
            return 0;
        }
        return a[0];
    }

    static void resetForTest() {
        arcs.clear();
    }
}
