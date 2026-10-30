package soloMapling.ArtificialPlayer.BotQuestSystem;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 158 item 5, lane dispersal: Temple of Time bots must not all stack on one lane map. Each lane quest needs up to
 * 999 kills of one monster that spawns on one or two maps, so hundreds of bots in the 90-135 band would otherwise pile
 * onto the same map of the same channel for hours. A bot picks its lane map and channel at pick time:
 * <ol>
 *   <li>on its own channel, the least busy of the quest's lane maps that is under the lane cap (ties: the map with
 *   more spawns first, then a steady per-bot coin);</li>
 *   <li>else the least busy channel where one is under the cap: the bot's life brain is offered a "cc" there
 *   ({@code TempleOfTime.wantedChannel}, carried out by {@code LifeChannelMove} on the bot's own update);</li>
 *   <li>else nowhere: it grinds elsewhere for a while and asks again (upstream's step-aside, nutnnut/Cosmic
 *   77e0c5bc9 {@code BotTempleProgressionManager.grindLane}).</li>
 * </ol>
 * "Busy" counts the Temple hunters this registry knows on that map and channel, plus the real players there (a player
 * counts double: bots give way to people). Pure choice ({@link #choose}) and a small registry of who hunts where.
 */
public final class TempleLanes {

    private TempleLanes() {
    }

    /** Default most Temple hunters on one lane map of one channel ({@code -Dslums.temple.laneCap}). */
    public static final int DEFAULT_CAP = 3;
    /** A registered hunt is forgotten after this long without being renewed (a bot that logged out). */
    static final long FORGET_MS = 6 * 3_600_000L;

    public static int cap() {
        return Math.max(1, Integer.getInteger("slums.temple.laneCap", DEFAULT_CAP));
    }

    /** Where a bot hunts: a lane map on a channel. */
    public record Choice(int mapId, int channel) {
    }

    /** How busy a lane map is on a channel (hunters plus weighted players). */
    @FunctionalInterface
    public interface Load {
        int on(int channel, int mapId);
    }

    /**
     * The lane map and channel for one bot, or null when every lane map is at the cap on every channel.
     *
     * @param lanes    the quest's lane maps, best (most spawns) first
     * @param channels the world's channel ids
     * @param own      the bot's channel
     */
    public static Choice choose(List<Integer> lanes, List<Integer> channels, int own, Load load, int cap, int botId) {
        if (lanes.isEmpty()) {
            return null;
        }
        Choice best = pick(lanes, own, load, cap, botId);
        if (best != null) {
            return best;
        }
        Choice elsewhere = null;
        int bestLoad = Integer.MAX_VALUE;
        for (int ch : channels) {
            if (ch == own) {
                continue;
            }
            Choice c = pick(lanes, ch, load, cap, botId);
            if (c != null) {
                int l = load.on(ch, c.mapId());
                if (l < bestLoad) {
                    bestLoad = l;
                    elsewhere = c;
                }
            }
        }
        return elsewhere;
    }

    private static Choice pick(List<Integer> lanes, int channel, Load load, int cap, int botId) {
        Choice best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (int i = 0; i < lanes.size(); i++) {
            int map = lanes.get(i);
            int l = load.on(channel, map);
            if (l >= cap) {
                continue;
            }
            // a steady per-bot coin breaks ties between equally quiet maps, the spawn order otherwise
            boolean better = l < bestLoad || l == bestLoad && i > 0 && Math.floorMod(botId * 31 + i, 3) == 0;
            if (better) {
                bestLoad = l;
                best = new Choice(map, channel);
            }
        }
        return best;
    }

    // ── who hunts where ──

    private record Hunt(int channel, int mapId, long since) {
    }

    private static final Map<Integer, Hunt> hunts = new ConcurrentHashMap<>();

    /** The bot hunts this lane map on this channel now (replaces any earlier hunt). */
    public static void register(int botId, int channel, int mapId, long now) {
        hunts.put(botId, new Hunt(channel, mapId, now));
    }

    public static void unregister(int botId) {
        hunts.remove(botId);
    }

    /** Temple hunters registered on a map of a channel, the bot itself left out. */
    public static int hunters(int channel, int mapId, int exceptBotId, long now) {
        int n = 0;
        for (Map.Entry<Integer, Hunt> e : hunts.entrySet()) {
            Hunt h = e.getValue();
            if (now - h.since() > FORGET_MS) {
                hunts.remove(e.getKey(), h);
                continue;
            }
            if (e.getKey() != exceptBotId && h.channel() == channel && h.mapId() == mapId) {
                n++;
            }
        }
        return n;
    }

    /** The map a bot is registered on, or -1. */
    public static int registeredMap(int botId) {
        Hunt h = hunts.get(botId);
        return h == null ? -1 : h.mapId();
    }

    static void resetForTests() {
        hunts.clear();
    }
}
