package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeHost;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 1.6.1 (ticket 114, roadmap "Friend crews"): friends who hang out move towns together. A crew is
 * {@link BotBonds#crewOf} (mutual friends, two hops, at most {@link BotBonds#CREW_MAX}); its leader on a channel is
 * the member with the lowest id online there. When the leader's daily-life brain starts a hang-out in a town
 * ({@link #led}), that town is the crew's pick for {@link #PICK_TTL_MS}: the other members' next hang-out goes there
 * ({@link #follow}, read by {@code LifeRuntime.townDestination}), and each is nudged to decide after a small delay
 * (2-6 s, then 3 s more per member) so they trickle in rather than arrive as one block. With no fresh pick, a member
 * standing in a town pulls the others there ({@link BotBonds#crewTownMap}).
 *
 * <p>Never blocks a map: only {@link #maxMovers()} members (4) move with the leader, and each arrival still goes
 * through the bot's own map-cap check ({@code BotMapCap.hasRoom}, {@code LifeTownSlots}) - a full town is simply not
 * chosen. Memory only; one entry per leader. Switch: {@code -Dslums.crews=true}.
 */
public final class BotCrews {

    static final long PICK_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    /** A pick another member made this recently is not replaced by a trip elsewhere (the friends are on their way). */
    static final long HOLD_PICK_MS = TimeUnit.MINUTES.toMillis(2);

    /** A leader's pick. */
    /** A crew's pick: the town, the channel, when, and the member whose trip made it (it follows nobody). */
    record Pick(int mapId, int channel, long at, int by) {
    }

    private static final Map<Integer, Pick> picks = new ConcurrentHashMap<>();
    private static final LongAdder led = new LongAdder();
    private static final LongAdder nudged = new LongAdder();
    private static final LongAdder followed = new LongAdder();

    /** Where a crew mate is (a seam for tests: id -> its channel and map, or null when offline). */
    @FunctionalInterface
    interface Where {
        int[] of(int id);
    }

    static volatile Where where = BotCrews::worldWhere;
    /** How a follower is nudged (a seam for tests): id, delay in ms. */
    static volatile java.util.function.ObjLongConsumer<Integer> nudge = BotCrews::nudgeBot;

    private BotCrews() {
    }

    public static boolean on() {
        return Boolean.getBoolean("slums.crews") && BotBonds.enabled();
    }

    /** How many members move with the leader (the leader included). */
    public static int maxMovers() {
        return Math.max(2, Math.min(BotBonds.CREW_MAX, Integer.getInteger("slums.crews.max", 4)));
    }

    /**
     * A crew member started a hang-out in {@code mapId}: the crew's pick, and its friends among the movers are nudged
     * and handed the trip 2-6 s apart. 1.6.1 b042 ("when one heads to a town, its friends follow"): any member's trip,
     * not only the leader's; the crew is still keyed by its leader (lowest id online here) and still at most
     * {@link #maxMovers()} move. No-op unless {@code bot} is among its crew's movers with someone else online on its
     * channel, or when it is only following the crew's fresh pick to that same town (no echo of the same trip).
     */
    public static void led(Character bot, int mapId) {
        if (!on() || bot == null || bot.getMap() == null || mapId <= 0) {
            return;
        }
        int channel = bot.getMap().getChannelId();
        List<Integer> movers = movers(bot.getId(), channel);
        if (movers.size() < 2 || !movers.contains(bot.getId())) {
            return;
        }
        long now = BotBonds.now();
        Pick fresh = picks.get(movers.get(0));
        if (fresh != null && fresh.mapId() == mapId && fresh.channel() == channel && now - fresh.at() <= PICK_TTL_MS) {
            return; // it is following the crew's own pick there
        }
        if (fresh != null && fresh.by() != bot.getId() && fresh.channel() == channel && now - fresh.at() < HOLD_PICK_MS) {
            return; // the friends just set off elsewhere with another member: no tug of war, this one goes alone
        }
        picks.put(movers.get(0), new Pick(mapId, channel, now, bot.getId()));
        led.increment();
        long delay = 2_000 + ThreadLocalRandom.current().nextInt(4_000);
        for (int i = 0; i < movers.size(); i++) {
            int id = movers.get(i);
            if (id == bot.getId()) {
                continue;
            }
            int[] at = where.of(id);
            if (at != null && at[1] != mapId) {
                nudge.accept(id, delay);
                later.accept(delay, () -> handOff(id, 1)); // 1.6.1 say-do: and the trip itself, after the delay
                nudged.increment();
                delay += 3_000 + ThreadLocalRandom.current().nextInt(2_000);
            }
        }
        if (picks.size() > 4096) {
            picks.values().removeIf(p -> now - p.at() > PICK_TTL_MS);
        }
    }

    /**
     * Where this crew member's next hang-out goes: its leader's fresh pick, else a town a crew mate stands in; -1 when
     * it leads, has no crew online here, is not among the {@link #maxMovers()}, or is already there.
     */
    public static int follow(Character bot, long now) {
        if (!on() || bot == null || bot.getMap() == null) {
            return -1;
        }
        int channel = bot.getMap().getChannelId();
        List<Integer> movers = movers(bot.getId(), channel);
        if (movers.size() < 2 || !movers.contains(bot.getId())) {
            return -1;
        }
        Pick p = picks.get(movers.get(0));
        if (p != null && p.by() == bot.getId() && now - p.at() <= PICK_TTL_MS) {
            return -1; // its own trip made the pick: it follows nobody
        }
        int target = p != null && p.channel() == channel && now - p.at() <= PICK_TTL_MS ? p.mapId() : -1;
        if (target <= 0 && movers.get(0) == bot.getId()) {
            return -1; // no fresh pick: the leader is not pulled by where the others stand
        }
        if (target <= 0) {
            target = BotBonds.crewTownMap(bot.getId(), channel);
        }
        if (target <= 0 || target == bot.getMapId()) {
            return -1;
        }
        followed.increment();
        return target;
    }

    /**
     * The crew members online on this channel who move together: the leader (lowest id) first, then up to
     * {@link #maxMovers()} - 1 others in crew order; {@code self} is always considered.
     */
    static List<Integer> movers(int self, int channel) {
        List<Integer> crew = BotBonds.crewOf(self);
        if (crew.size() < 2) {
            return List.of(self);
        }
        List<Integer> here = new ArrayList<>(crew.size());
        for (int id : crew) {
            int[] at = id == self ? new int[]{channel, 0} : where.of(id);
            if (at != null && at[0] == channel) {
                here.add(id);
            }
        }
        here.sort(Integer::compare);
        int leader = here.get(0);
        List<Integer> out = new ArrayList<>();
        out.add(leader);
        // the leader's own crew order (its closest friends first), so every member sees the same movers
        for (int id : leader == self ? crew : BotBonds.crewOf(leader)) {
            if (out.size() >= maxMovers()) {
                break;
            }
            if (id != leader && here.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static int[] worldWhere(int id) {
        try {
            Character c = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(id);
            return c == null || c.getMap() == null ? null : new int[]{c.getMap().getChannelId(), c.getMapId()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void nudgeBot(int id, long delayMs) {
        BotSM sm = CharacterStorage.getBotById(id);
        if (sm instanceof LifeHost host && host.lifeRuntime() != null) {
            host.lifeRuntime().changed();
            host.nudgeSoon(delayMs);
        }
    }

    /*
     * 1.6.1 say-do: a follower's hang-out in the crew's town is handed to its life queue after its delay (before, it
     * was only nudged to decide, and the brain often kept grinding: auto-QA t-bt-2 saw 1 of 2 followers arrive). A
     * follower held a moment (a line, its own decision) is tried again RETRY_MS later, up to TRIES times; one that
     * cannot go keeps the pull.
     */
    static final int TRIES = 6;
    static final long RETRY_MS = 10_000;
    private static final LongAdder handedOff = new LongAdder();
    private static final LongAdder notHanded = new LongAdder();

    /** When a follower's hand-off runs (a seam for tests: delay in ms, the work). */
    static volatile java.util.function.BiConsumer<Long, Runnable> later =
            (ms, r) -> soloMapling.server.BotTiming.after(ms, r);
    /** How a follower is sent (a seam for tests): id, town, until; true when its trip was handed over. */
    static volatile Mover mover = BotCrews::moveBot;

    @FunctionalInterface
    interface Mover {
        /** {@code null}: the follower is gone (offline, no brain); else whether it was handed the trip. */
        Boolean go(int id, int town, long until, long now);
    }

    /** One try at sending a follower to its crew's pick; again later while it is held. */
    static void handOff(int id, int attempt) {
        int[] at = where.of(id);
        if (at == null) {
            return;
        }
        long now = BotBonds.now();
        int town = pickFollowed(id, at[0], now);
        if (town <= 0 || town == at[1]) {
            return; // no fresh pick any more, or it is there
        }
        Boolean sent = mover.go(id, town, now + PICK_TTL_MS, now);
        if (sent == null) {
            return;
        }
        if (sent) {
            handedOff.increment();
        } else if (attempt < TRIES) {
            later.accept(RETRY_MS, () -> handOff(id, attempt + 1));
        } else {
            notHanded.increment();
        }
    }

    /** The fresh pick of this member's leader on its channel, or -1 (it leads, or there is none). */
    static int pickFollowed(int id, int channel, long now) {
        List<Integer> movers = movers(id, channel);
        if (movers.size() < 2 || !movers.contains(id)) {
            return -1;
        }
        Pick p = picks.get(movers.get(0));
        return p != null && p.by() != id && p.channel() == channel && now - p.at() <= PICK_TTL_MS ? p.mapId() : -1;
    }

    private static Boolean moveBot(int id, int town, long until, long now) {
        BotSM sm = CharacterStorage.getBotById(id);
        if (!(sm instanceof LifeHost host) || host.lifeRuntime() == null) {
            return null;
        }
        return host.lifeRuntime().goTogether(town, until, now);
    }

    /** Counters for bot-life.json and the checks. */
    public static Map<String, Long> stats() {
        return Map.of("led", led.sum(), "nudged", nudged.sum(), "followed", followed.sum(), "picks", (long) picks.size(),
                "handedOff", handedOff.sum(), "notHanded", notHanded.sum());
    }

    /** QA: the leader's current pick for a crew member, or -1. */
    public static int pickFor(int botId, int channel, long now) {
        List<Integer> movers = movers(botId, channel);
        Pick p = picks.get(movers.get(0));
        return p != null && p.channel() == channel && now - p.at() <= PICK_TTL_MS ? p.mapId() : -1;
    }

    static void resetForTest() {
        picks.clear();
        led.reset();
        nudged.reset();
        followed.reset();
        handedOff.reset();
        notHanded.reset();
        where = BotCrews::worldWhere;
        nudge = BotCrews::nudgeBot;
        later = (ms, r) -> soloMapling.server.BotTiming.after(ms, r);
        mover = BotCrews::moveBot;
    }
}
