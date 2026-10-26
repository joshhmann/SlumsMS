package soloMapling.autoqa;

import client.Character;
import net.server.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 (owner 2026-10-26): the social trace's observers. Bot threads only start on a map where a real player stands,
 * and the owner is at work - so up to four pretend players ({@link SyntheticPlayers#OBSERVER_NAMES}, accounts
 * {@code autoqa3..autoqa6}, plain level-30 characters) stand in the social maps, one per channel 1-3 (the fourth back
 * on channel 1), and move on to the next town or the FM every {@link #ROTATE_MIN} minutes. They never chat, trade or
 * accept anything; they only stand there. The site and the economy's player stats leave them out by their
 * {@code autoqa} names, as they do auto-QA's own pretend players.
 */
final class SocialObservers {

    static final int MAX = SyntheticPlayers.OBSERVER_NAMES.length;
    static final int ROTATE_MIN = 20;
    /** Henesys, the FM entrance, Kerning, Ellinia, Perion, Ludibrium, Orbis, Sleepywood. */
    static final int[] MAPS = {100000000, 910000000, 103000000, 101000000, 102000000, 220000000, 200000000, 105040300};

    /** slot -> {map index, channel}. */
    private static final Map<Integer, int[]> where = new TreeMap<>();
    private static long nextRotateAt;

    private SocialObservers() {
    }

    /** The channel of the i-th observer: 1, 2, 3, 1 (channel 1 when that channel is not up). */
    static int channelFor(int i, int channels) {
        int ch = 1 + i % 3;
        return ch <= Math.max(1, channels) ? ch : 1;
    }

    /** Where the i-th observer starts: spread over the list so no two start on the same map. */
    static int firstMapIndex(int i) {
        return (i * 2) % MAPS.length;
    }

    /** Logs in the first {@code n} observers that are not online and puts each on its map. */
    static synchronized void ensure(int n, long now) {
        int channels = channelCount();
        for (int i = 0; i < Math.min(n, MAX); i++) {
            int slot = SyntheticPlayers.FIRST_OBSERVER_SLOT + i;
            SyntheticPlayer p = SyntheticPlayers.get(slot);
            if (p != null && p.isOnline()) {
                continue;
            }
            int[] w = where.computeIfAbsent(slot, s -> new int[]{firstMapIndex(s - SyntheticPlayers.FIRST_OBSERVER_SLOT),
                    channelFor(s - SyntheticPlayers.FIRST_OBSERVER_SLOT, channels)});
            try {
                p = SyntheticPlayers.login(slot, w[1]);
                warp(p, MAPS[w[0]]);
            } catch (RuntimeException e) {
                AutoQa.log.warn("Social trace: observer slot {} could not log in: {}", slot, e.toString());
            }
        }
        if (nextRotateAt == 0) {
            nextRotateAt = now + TimeUnit.MINUTES.toMillis(ROTATE_MIN);
        }
    }

    /** Every {@link #ROTATE_MIN} minutes each observer moves to the next map; one that dropped out logs back in. */
    static synchronized void tick(long now) {
        if (where.isEmpty()) {
            return;
        }
        ensure(where.size(), now);
        if (now < nextRotateAt) {
            return;
        }
        nextRotateAt = now + TimeUnit.MINUTES.toMillis(ROTATE_MIN);
        for (Map.Entry<Integer, int[]> e : where.entrySet()) {
            e.getValue()[0] = (e.getValue()[0] + 1) % MAPS.length;
            SyntheticPlayer p = SyntheticPlayers.get(e.getKey());
            if (p != null && p.isOnline()) {
                warp(p, MAPS[e.getValue()[0]]);
            }
        }
    }

    static synchronized void stopAll() {
        where.clear();
        nextRotateAt = 0;
        SyntheticPlayers.logoutObservers();
    }

    /** "AutoQaThree ch1 100000000, ..." or "none". */
    static synchronized String describe() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : where.entrySet()) {
            SyntheticPlayer p = SyntheticPlayers.get(e.getKey());
            Character c = p == null ? null : p.character();
            out.add(c == null ? SyntheticPlayers.nameOf(e.getKey()) + " offline"
                    : c.getName() + " ch" + e.getValue()[1] + " " + c.getMapId());
        }
        return out.isEmpty() ? "none" : String.join(", ", out);
    }

    private static void warp(SyntheticPlayer p, int mapId) {
        Character c = p.character();
        if (c != null && c.getMapId() != mapId) {
            c.changeMap(mapId);
        }
    }

    private static int channelCount() {
        try {
            return Server.getInstance().getChannelsFromWorld(Server.getInstance().getHomeWorldId()).size();
        } catch (RuntimeException e) {
            return 1;
        }
    }
}
