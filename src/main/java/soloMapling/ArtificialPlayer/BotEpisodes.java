package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * 1.6.0 slice S3, "what we did together": the episodes a bot writes about a real player without the LLM, from the
 * game's own events. Each is a short note from the bot's side with a {@link BotMemory.Kind} and a weight:
 *
 * <table>
 *   <tr><th>Event</th><th>Note</th><th>Kind</th><th>Weight</th></tr>
 *   <tr><td>PQ clear (SocialHooks)</td><td>did KPQ together</td><td>PQ</td><td>6</td></tr>
 *   <tr><td>boss clear (SocialHooks)</td><td>beat zakum together</td><td>BOSS</td><td>8</td></tr>
 *   <tr><td>party (affinity)</td><td>partied together in ellinia</td><td>PARTY</td><td>3</td></tr>
 *   <tr><td>buff / heal / rescue (affinity)</td><td>buffed me / healed me at the door to zakum / saved me at ...</td>
 *       <td>HELP</td><td>2 / 3 / 5</td></tr>
 *   <tr><td>fame (affinity)</td><td>famed me</td><td>FAME</td><td>4</td></tr>
 *   <tr><td>trade (SocialHooks)</td><td>traded me a claw / bought my claw</td><td>TRADE</td><td>4 / 3</td></tr>
 *   <tr><td>gift, favor, scam (SocialHooks)</td><td>gave me ... / gave me a good deal on ... / scammed me on a ...
 *       trade</td><td>GIFT / FAVOR / SCAM</td><td>6 / 5 / 9</td></tr>
 * </table>
 * Writes go to a background thread (they read and write the database). A PQ clear also nudges the bot a little toward
 * Socializer on Bartle's chart, a scam a little toward Killer (at most once a day each).
 */
public final class BotEpisodes {

    private static final Logger log = LoggerFactory.getLogger(BotEpisodes.class);

    /** One Bartle nudge of each kind per bot per this time: the chart moves with a life, not with each PQ stage. */
    static final long PUSH_GAP_MS = TimeUnit.HOURS.toMillis(24);

    private static volatile Executor writer = soloMapling.server.ExecutorServiceManager::runAsync;
    /** Map id -> a short place name in lower case, or null (a seam: the real one reads String.wz). */
    private static volatile IntFunction<String> places = BotEpisodes::placeName;
    /** (bot character id, push) -> when it last moved the bot (memory only; bounded). */
    private static final Map<Long, Long> lastPush = new ConcurrentHashMap<>();
    /** Where a Bartle nudge goes (a seam for tests: bot character id, push). */
    public interface Pusher {
        void push(int botCharId, SocialProfile.Push push);
    }

    /** 1.6.0 S9: through the learning budget (daily caps, pull-back, crossing check). */
    private static final Pusher LIFE = (botCharId, push) ->
            soloMapling.ArtificialPlayer.BotLifeSystem.LifeLearning.push(botCharId, push, System.currentTimeMillis());
    private static volatile Pusher pusher = LIFE;

    private BotEpisodes() {
    }

    private static boolean bot(int charId) {
        return BotHelpers.isArtificial(charId);
    }

    private static int slot(int botCharId) {
        return botCharId - BotStore.BOT_ID_BASE;
    }

    /** A note to write: what, of which kind, how much it matters. */
    public record Episode(BotMemory.Kind kind, String text, int weight) {
    }

    /**
     * The episode an affinity event leaves (party, buff, heal, rescue, fame), or null for events that leave none here
     * (trades and gifts come from {@link SocialHooks} with the items; KS and other grudges from the feeling notes).
     */
    public static Episode ofAffinity(AffinityEvent event, String place) {
        if (event == null) {
            return null;
        }
        String at = place == null || place.isBlank() ? "" : " " + place;
        return switch (event) {
            case PARTY -> new Episode(BotMemory.Kind.PARTY, "partied together" + (at.isEmpty() ? "" : " in" + at), 3);
            case BUFF -> new Episode(BotMemory.Kind.HELP, "buffed me", 2);
            case HEAL -> new Episode(BotMemory.Kind.HELP, "healed me" + (at.isEmpty() ? "" : " at" + at), 3);
            case RESCUE -> new Episode(BotMemory.Kind.HELP, "saved me from a mob" + (at.isEmpty() ? "" : " at" + at), 5);
            case FAME -> new Episode(BotMemory.Kind.FAME, "famed me", 4);
            default -> null;
        };
    }

    /**
     * Called by {@code BotAffinity.record} after an event counted (so the event's own cooldown already applied): the
     * matching episode, if any, is written in the background.
     */
    public static void onAffinity(int botCharId, int playerId, AffinityEvent event, int mapId, long now) {
        if (!bot(botCharId) || bot(playerId) || ofAffinity(event, null) == null) {
            return;
        }
        writer.execute(() -> {
            try {
                Episode e = ofAffinity(event, mapId > 0 ? places.apply(mapId) : null);
                BotMemory.episode(slot(botCharId), playerId, e.kind(), e.text(), e.weight(), "", now);
            } catch (RuntimeException ex) {
                log.debug("Could not write bot {}'s {} episode about {}", botCharId, event, playerId, ex);
            }
        });
    }

    /** The note a PQ or boss clear leaves: "did KPQ together", "beat zakum together". */
    public static Episode ofClear(SocialHooks.Clear clear) {
        return clear.boss() ? new Episode(BotMemory.Kind.BOSS, "beat " + clear.name() + " together", 8)
                : new Episode(BotMemory.Kind.PQ, "did " + clear.name() + " together", 6);
    }

    /** A bot cleared a PQ or a boss with a real player: the episode, and a small push toward Socializer. */
    public static void together(int botCharId, int playerId, SocialHooks.Clear clear, long now) {
        if (!bot(botCharId) || bot(playerId) || clear == null) {
            return;
        }
        Episode e = ofClear(clear);
        Reputation.witnessed(botCharId, playerId, Reputation.Mark.PQ, now); // 1.6.0 S4: "pqs with us a lot"
        writer.execute(() -> {
            try {
                BotMemory.episode(slot(botCharId), playerId, e.kind(), e.text(), e.weight(), "", now);
                nudge(botCharId, SocialProfile.Push.PQ_TOGETHER, now);
            } catch (RuntimeException ex) {
                log.debug("Could not write bot {}'s clear episode about {}", botCharId, playerId, ex);
            }
        });
    }

    /** A finished trade's episode (gift, favor, scam or a plain trade), written in the background. */
    public static void trade(int botCharId, int playerId, Episode e, long now) {
        if (!bot(botCharId) || bot(playerId) || e == null) {
            return;
        }
        writer.execute(() -> {
            try {
                BotMemory.episode(slot(botCharId), playerId, e.kind(), e.text(), e.weight(), "", now);
                if (e.kind() == BotMemory.Kind.SCAM) {
                    nudge(botCharId, SocialProfile.Push.SCAMMED, now); // a grudge hardens a bot a little
                }
            } catch (RuntimeException ex) {
                log.debug("Could not write bot {}'s trade episode about {}", botCharId, playerId, ex);
            }
        });
    }

    /** A small push on Bartle's chart, at most once per {@link #PUSH_GAP_MS} per bot and kind. True when it moved. */
    static boolean nudge(int botCharId, SocialProfile.Push push, long now) {
        long k = ((long) botCharId << 8) | push.ordinal();
        Long last = lastPush.get(k);
        if (last != null && now - last < PUSH_GAP_MS) {
            return false;
        }
        lastPush.put(k, now);
        soloMapling.server.BoundedMaps.prune(lastPush, 8_000, 16_000, at -> now - at >= PUSH_GAP_MS);
        try {
            pusher.push(botCharId, push);
            return true;
        } catch (RuntimeException e) {
            log.debug("Could not push bot {} toward {}", botCharId, push, e);
            return false;
        }
    }

    /** A map's short place name in lower case ("ellinia", "the door to zakum"), or null. */
    static String placeName(int mapId) {
        try {
            String place = server.maps.MapFactory.loadPlaceName(mapId);
            if (place == null || place.isBlank()) {
                place = server.maps.MapFactory.loadStreetName(mapId);
            }
            return place == null || place.isBlank() ? null : place.trim().toLowerCase(Locale.ROOT);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Test seam: where writes run, map names, and where pushes go (nulls restore the real ones); forgets push times. */
    public static void useForTest(Executor testWriter, IntFunction<String> testPlaces, Pusher testPusher) {
        writer = testWriter != null ? testWriter : soloMapling.server.ExecutorServiceManager::runAsync;
        places = testPlaces != null ? testPlaces : BotEpisodes::placeName;
        pusher = testPusher != null ? testPusher : LIFE;
        lastPush.clear();
    }
}
