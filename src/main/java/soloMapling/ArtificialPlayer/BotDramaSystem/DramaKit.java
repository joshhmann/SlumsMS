package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingUtilities;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * Shared pieces of the 1.5.0 bot drama package: its lines ({@code DramaDialogue.yaml}, root {@code Drama}), per-key
 * cooldowns and world-wide hourly caps, the bots a drama scene holds (the director never logs them out mid-scene),
 * moving a bot to a player for a scene and back, and the QA log.
 *
 * <p>Every line a bot says here passes {@link ContentGuard} (the scripted packs are checked by a test too).
 */
public final class DramaKit {

    private static final Logger log = LoggerFactory.getLogger(DramaKit.class);

    static final String PACK = "DramaDialogue.yaml";
    static final String ROOT = "Drama";

    private static final Map<String, Long> nextAt = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Long>> hourly = new ConcurrentHashMap<>();
    /** Bot id -> why it is held by a drama scene (a wedding, a KS war, a revenge follow). */
    private static final Map<Integer, String> busy = new ConcurrentHashMap<>();

    private DramaKit() {
    }

    // ── settings ──

    static double prop(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty("solomapling.drama." + key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static long minutes(String key, double fallback) {
        return (long) (prop(key, fallback) * TimeUnit.MINUTES.toMillis(1));
    }

    static long hours(String key, double fallback) {
        return (long) (prop(key, fallback) * TimeUnit.HOURS.toMillis(1));
    }

    static long days(String key, double fallback) {
        return (long) (prop(key, fallback) * TimeUnit.DAYS.toMillis(1));
    }

    /** The whole drama package: {@code -Dsolomapling.drama=false} turns every part off. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.drama", "true"));
    }

    // ── cooldowns and caps ──

    /** True (and the gap starts) when {@code key} is not cooling down. */
    static boolean take(String key, long now, long gapMs) {
        Long next = nextAt.get(key);
        if (next != null && now < next) {
            return false;
        }
        nextAt.put(key, now + gapMs);
        if (nextAt.size() > 20_000) {
            nextAt.values().removeIf(t -> t < now);
        }
        return true;
    }

    /** Whether {@code key} is free now (no gap running), without taking it. */
    static boolean free(String key, long now) {
        Long next = nextAt.get(key);
        return next == null || now >= next;
    }

    static void release(String key) {
        nextAt.remove(key);
    }

    /** True (and one is counted) when fewer than {@code cap} of {@code kind} happened world-wide in the last hour. */
    static boolean hourly(String kind, int cap, long now) {
        Deque<Long> recent = hourly.computeIfAbsent(kind, k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > TimeUnit.HOURS.toMillis(1)) {
                recent.pollFirst();
            }
            if (recent.size() >= cap) {
                return false;
            }
            recent.addLast(now);
            return true;
        }
    }

    // ── scene holds ──

    /** The bot is in a drama scene: the population director leaves it in the world until {@link #unbusy}. */
    static void busy(int botCharId, String why) {
        busy.put(botCharId, why);
    }

    static void unbusy(int botCharId) {
        busy.remove(botCharId);
    }

    /** Whether a drama scene holds this bot now ({@code BotPopulationDirector.mayLogOut} asks). */
    public static boolean inScene(int botCharId) {
        return !busy.isEmpty() && busy.containsKey(botCharId);
    }

    static String sceneOf(int botCharId) {
        return busy.get(botCharId);
    }

    // ── lines ──

    /** A random line of the node with every {TOKEN} filled and safe to say; null when none fits. */
    static String pick(String node, Map<String, String> tokens) {
        return pick(node, tokens, l -> true);
    }

    /** {@link #pick}, only among the lines {@code ok} accepts. */
    static String pick(String node, Map<String, String> tokens, java.util.function.Predicate<String> ok) {
        BotDialogueHandler.DialogueConstructor dialog;
        try {
            dialog = BotDialogueHandler.getDialogueCon(PACK, ROOT, node);
        } catch (RuntimeException e) {
            return null;
        }
        if (dialog == null || dialog.getDialogue() == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(dialog.getDialogue());
        Collections.shuffle(lines);
        for (String line : lines) {
            String out = fill(line, tokens);
            if (out != null && ContentGuard.ok(out) && ok.test(out)) {
                return out;
            }
        }
        return null;
    }

    static String fill(String line, Map<String, String> tokens) {
        String out = line;
        for (Map.Entry<String, String> t : tokens.entrySet()) {
            if (t.getValue() != null && !t.getValue().isBlank()) {
                out = out.replace("{" + t.getKey() + "}", t.getValue());
            }
        }
        return out.matches(".*\\{[A-Z_]+}.*") ? null : out;
    }

    /** The bot says a line of the node in map chat; the line, or null when none fit. */
    static String say(Character bot, String node, Map<String, String> tokens) {
        String line = pick(node, tokens);
        if (line != null && bot != null && bot.getMap() != null) {
            BotSpeak(bot, line);
        }
        return line;
    }

    /** The bot says a line of the node aimed at this player: never one it said to them lately (1.6.0 S5). */
    static String sayTo(Character bot, Character player, String node, Map<String, String> tokens) {
        if (player == null || bot == null) {
            return say(bot, node, tokens);
        }
        String line = pick(node, tokens, l -> soloMapling.ArtificialPlayer.SocialGate.fresh(bot.getId(), player.getId(), l));
        if (line != null && bot.getMap() != null) {
            BotSpeak(bot, line);
        }
        return line;
    }

    /**
     * A whisper from a bot to a player (the stock whisper window), unprompted. Held (dropped) while the player is busy -
     * an event or boss map, a trade or shop, an NPC dialog, AFK - like every unsolicited bot whisper (rc3b PlayerBusy
     * hold-off); 1.6.0 S5: through the social gate, so {@code @quiet bots} holds it too, and never the same line twice.
     * True when it was sent.
     */
    static boolean whisper(Character bot, Character player, String line) {
        if (player == null || line == null
                || !soloMapling.ArtificialPlayer.SocialGate.allow(bot, player, soloMapling.ArtificialPlayer.SocialGate.Act.WHISPER)
                || !soloMapling.ArtificialPlayer.SocialGate.freshLine(bot, player, line)) {
            return false;
        }
        reply(bot, player, line);
        return true;
    }

    /** A bot's whisper answering something the player just did (never held: the player is waiting for it). */
    static void reply(Character bot, Character player, String line) {
        if (bot == null || player == null || line == null) {
            return;
        }
        player.sendPacket(PacketCreator.getWhisperReceive(bot.getName(), Math.max(0, channelOf(bot) - 1), false, line));
        soloMapling.ArtificialPlayer.BotConvo.SayDo.said(bot, player, line, "drama-whisper");
    }

    // ── who is where ──

    static Character liveBot(int charId) {
        BotSM sm = CharacterStorage.getBotById(charId);
        return sm == null ? null : sm.getChr();
    }

    static Character onlinePlayer(int charId) {
        try {
            net.server.world.World w = SoloMaplingUtilities.world();
            Character c = w == null ? null : w.getPlayerStorage().getCharacterById(charId);
            return c == null || BotHelpers.isBot(c) || !c.isLoggedinWorld() ? null : c;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<Character> realPlayers() {
        List<Character> out = new ArrayList<>();
        try {
            net.server.world.World w = SoloMaplingUtilities.world();
            if (w == null) {
                return out;
            }
            for (Character c : w.getPlayerStorage().getAllCharacters()) {
                if (!BotHelpers.isBot(c) && c.isLoggedinWorld() && BotHelpers.isVisibleRealPlayer(c)) {
                    out.add(c);
                }
            }
        } catch (RuntimeException e) {
            log.debug("players", e);
        }
        return out;
    }

    static int channelOf(Character chr) {
        try {
            return chr.getClient().getChannel();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static String mapName(Character chr) {
        try {
            MapleMap m = chr.getMap();
            String name = m == null ? null : m.getMapName();
            return name == null || name.isBlank() ? "here" : name.toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return "here";
        }
    }

    /**
     * A roster or on-demand bot doing its own thing that a scene may take for a while: training or social, no player
     * commitment (no trade, no party with a real player, not following anyone), in no event and in no other scene.
     */
    static boolean freeForScene(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return false;
        }
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        return (sm instanceof TrainingBot || sm instanceof SocialBot) && BotPopulationDirector.mayLogOut(sm)
                && bot.getEventInstance() == null && !inScene(bot.getId()) && !withRealPlayer(bot);
    }

    /**
     * As {@link #freeForScene(Character)} for a scene with {@code player} itself: chatting with that player, or
     * being in a party with them, does not hold the bot (1.5.0 rc6, QA t-rm-1: the partner was mid-chat with the
     * player it dates - it had just said yes - so it never followed them to the next map).
     */
    static boolean freeForSceneWith(Character bot, Character player) {
        if (player == null) {
            return freeForScene(bot);
        }
        if (bot == null || bot.getMap() == null) {
            return false;
        }
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        return (sm instanceof TrainingBot || sm instanceof SocialBot)
                && BotPopulationDirector.mayLogOut(sm, player.getId()) && bot.getEventInstance() == null
                && !inScene(bot.getId()) && !withRealPlayerOtherThan(bot, player.getId());
    }

    static boolean withRealPlayerOtherThan(Character bot, int playerId) {
        Party p = bot.getParty();
        if (p == null) {
            return false;
        }
        for (PartyCharacter m : p.getMembers()) {
            if (m != null && !BotHelpers.isArtificial(m.getId()) && m.getId() != playerId) {
                return true;
            }
        }
        return false;
    }

    static boolean withRealPlayer(Character bot) {
        Party p = bot.getParty();
        if (p == null) {
            return false;
        }
        for (PartyCharacter m : p.getMembers()) {
            if (m != null && !BotHelpers.isArtificial(m.getId())) {
                return true;
            }
        }
        return false;
    }

    static boolean realPlayerOnMap(Character chr) {
        try {
            for (Character c : chr.getMap().getAllPlayers()) {
                if (!BotHelpers.isBot(c) && BotHelpers.isVisibleRealPlayer(c)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /** Where a bot was before a scene took it: its map and spot, and whether it was a training bot. */
    record Home(int mapId, int x, int y, boolean training) {
    }

    static Home homeOf(Character bot) {
        Point p = bot.getPosition();
        return new Home(bot.getMapId(), p == null ? 0 : p.x, p == null ? 0 : p.y,
                CharacterStorage.getBotById(bot.getId()) instanceof TrainingBot);
    }

    /**
     * Brings a bot to the player for a scene: the player's channel, then their map, entering at a portal away from
     * the player and walking up to them ({@link #arrive}; as a player would with a return scroll and a walk). {@code grind}: it fights there (training, held on the map); else it stands and
     * talks (social). True when it arrived.
     */
    static boolean bringTo(Character bot, Character player, int offsetX, boolean grind) {
        try {
            int ch = channelOf(player);
            if (channelOf(bot) != ch && !BotChannels.moveToChannel(bot, ch)) {
                return false;
            }
            MapleMap map = BotChannels.map(ch, player.getMapId());
            if (map == null) {
                return false;
            }
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(bot);
            soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.standUp(bot); // rc6: no chair along
            Point at = player.getPosition() == null ? new Point(0, 0) : new Point(player.getPosition());
            at.translate(offsetX, 0);
            if (offsetX != 0) { // rc6 (QA t-pb-1): either side near the player, on the ground, clear of others
                Point staged = soloMapling.ArtificialPlayer.StageSpots.near(map, player.getPosition() == null ? at
                        : player.getPosition());
                // Only when the spot is on the player's own ledge: a staged spot one platform up still put a KS war
                // helper 300 px above the player, outside the reach it takes mobs in (auto-QA t-rv-2).
                if (staged != null && Math.abs(staged.y - at.y) <= 30) {
                    at = staged;
                }
            }
            int mapId = map.getId();
            Point entry = null;
            if (bot.getMapId() != mapId) {
                // 1.5.1 (owner QA: "KS-war bots still appear out of thin air"): a bot coming from another map enters
                // like a player would, at one of the map's portals away from the player, and walks up from there.
                // changeMap straight to the spot beside the player made it materialise next to them.
                entry = enter(bot, map, player.getPosition());
                if (entry == null) {
                    return false;
                }
            }
            // The type change comes before any walk: convertBotType stops the old FSM, and a training bot's stop
            // disables its movement, which ended a walk begun before it (a grinding helper stood where it entered).
            if (grind) {
                soloMapling.worldevents.WorldEventSteer.hold(bot.getId(), mapId);
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.TRAINING_BOT);
            } else if (CharacterStorage.getBotById(bot.getId()) instanceof TrainingBot) {
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.SOCIAL_BOT);
            }
            if (entry != null) {
                walkIn(bot, entry, at, mapId, null);
            } else if (grind && (bot.getPosition() == null
                    || Math.abs(bot.getPosition().x - at.x) > 120 || Math.abs(bot.getPosition().y - at.y) > 120)) {
                // A bot already on the player's map keeps its own spot, so a guildmate training on another ledge of
                // the same map never came to the rally and fought its own mobs (auto-QA t-rv-2: 0 of 3 helpers ever
                // took a mob from the player). It walks there, never warps: a changeMap on the map the player is
                // watching made the bot vanish and reappear beside them (owner review of the KS rally).
                Point from = bot.getPosition() == null ? null : new Point(bot.getPosition());
                Point dest = at;
                walkTo(bot, dest, mapId, () -> qa(QaLog.Topic.RIVALRY, mapId, "RALLY WALK " + bot.getName()
                        + " from " + from + " to " + bot.getPosition() + (bot.getPosition() != null
                        && Math.abs(bot.getPosition().x - dest.x) <= 40 && Math.abs(bot.getPosition().y - dest.y) <= 40
                        ? " arrived" : " stopped short of " + dest)));
            }
            return bot.getMapId() == map.getId();
        } catch (RuntimeException e) {
            log.debug("Could not bring {} to {}", bot.getName(), player.getName(), e);
            return false;
        }
    }

    /** How long a same-map approach walks before it stops and lets the bot fight from where it is. */
    private static final long WALK_TO_MS = 4_500L;

    /** How long a bot that just entered the map walks toward the player before it stays where it got to. */
    static final long ARRIVE_WALK_MS = 15_000L;

    /** The closest (px) to the player a bot coming from another map may enter it, when the map has such a portal. */
    static final int ARRIVAL_MIN_GAP = 200;

    /**
     * A bot on another map comes to {@code at} on {@code map} as a player would: it enters at a portal of the map
     * ({@link #entryPoint}) and walks the rest ({@value #ARRIVE_WALK_MS} ms at most, then it stays where it got to;
     * it never warps the last bit). {@code done} runs once when the walk ends. False when the map has no entry point
     * (nothing grounded to enter at): the bot does not come rather than appear beside the player.
     */
    static boolean arrive(Character bot, MapleMap map, Point at, Point player, Runnable done) {
        Point entry = enter(bot, map, player == null ? at : player);
        if (entry == null) {
            return false;
        }
        walkIn(bot, entry, at, map.getId(), done);
        return true;
    }

    /** The warp half of {@link #arrive}: onto {@code map} at its entry point for a player at {@code player}. */
    static Point enter(Character bot, MapleMap map, Point player) {
        Point entry = entryPoint(map, player);
        if (entry == null) {
            return null;
        }
        bot.changeMap(map, entry);
        return bot.getMapId() == map.getId() ? entry : null;
    }

    /** The walk half of {@link #arrive}: from where it entered toward {@code at}, bounded by the arrival cap. */
    private static void walkIn(Character bot, Point entry, Point at, int mapId, Runnable done) {
        walkTo(bot, at, mapId, ARRIVE_WALK_MS, () -> {
            qa(QaLog.Topic.RIVALRY, mapId, "ARRIVE WALK " + bot.getName() + " entered at " + entry + ", walked to "
                    + bot.getPosition() + (bot.getPosition() != null && Math.abs(bot.getPosition().x - at.x) <= 40
                    && Math.abs(bot.getPosition().y - at.y) <= 40 ? " (arrived)" : " (stopped short of " + at + ")"));
            if (done != null) {
                done.run();
            }
        });
    }

    /**
     * Where a bot coming from another map enters {@code map}: the grounded visible portal or spawn point
     * ({@link #entryCandidates}) closest to the player that is at least {@link #ARRIVAL_MIN_GAP} px from them, else
     * the farthest one; null when the map has none.
     */
    static Point entryPoint(MapleMap map, Point player) {
        if (map == null) {
            return null;
        }
        return chooseEntry(entryCandidates(map), player, ARRIVAL_MIN_GAP);
    }

    /** Portal types a player is seen entering at: spawn points (0), visible portals (2), visible scripted ones (7). */
    static boolean entryType(int type) {
        return type == 0 || type == 2 || type == 7;
    }

    /** The ground under every entry portal of the map (any portal when none of them is an entry type). */
    static List<Point> entryCandidates(MapleMap map) {
        List<Point> out = new ArrayList<>();
        List<Point> any = new ArrayList<>();
        try {
            for (server.maps.Portal portal : map.getPortals()) {
                Point p = portal == null ? null : portal.getPosition();
                Point ground = p == null ? null
                        : soloMapling.ArtificialPlayer.BotMovementSystem.BotGround.landing(map, p);
                if (ground == null) {
                    continue;
                }
                (entryType(portal.getType()) ? out : any).add(ground);
            }
        } catch (RuntimeException e) {
            log.debug("No portals on {}", map.getId(), e);
        }
        return out.isEmpty() ? any : out;
    }

    /**
     * The entry spot for a player at {@code player}: of the spots at least {@code minGap} px away, the one the
     * shortest walk from them (height counts triple: a portal on the player's own floor beats one on a ledge above);
     * when every spot is closer than that, the farthest one. Null when there are none.
     */
    static Point chooseEntry(List<Point> spots, Point player, int minGap) {
        if (spots == null || spots.isEmpty()) {
            return null;
        }
        if (player == null) {
            return new Point(spots.get(0));
        }
        Point best = null;
        int bestWalk = Integer.MAX_VALUE;
        Point farthest = null;
        double farthestDist = -1;
        for (Point s : spots) {
            if (s == null) {
                continue;
            }
            double d = s.distance(player);
            if (d > farthestDist) {
                farthestDist = d;
                farthest = s;
            }
            int walk = Math.abs(s.x - player.x) + 3 * Math.abs(s.y - player.y);
            if (d >= minGap && walk < bestWalk) {
                bestWalk = walk;
                best = s;
            }
        }
        Point pick = best != null ? best : farthest;
        return pick == null ? null : new Point(pick);
    }

    /**
     * A bot already on the scene's map walks to {@code at} on its feet; it never warps, because the player it is
     * coming to can see the map. The walk is bounded ({@value #WALK_TO_MS} ms) and ends with
     * {@link GCMovement#stop(Character)} on the scheduler, so a bot whose path is wedged is still free to fight, and
     * the caller never blocks. The bot itself finished {@code bringTo} as before: held on the map and, when it grinds,
     * converted to a training bot that takes the player's mobs from where the walk left it (the KS pick drifts to them).
     */
    private static void walkTo(Character bot, Point at, int mapId) {
        walkTo(bot, at, mapId, null);
    }

    /** As {@link #walkTo(Character, Point, int)}; {@code done} runs once, on arrival or when the walk is stopped. */
    static void walkTo(Character bot, Point at, int mapId, Runnable done) {
        walkTo(bot, at, mapId, WALK_TO_MS, done);
    }

    /** As {@link #walkTo(Character, Point, int, Runnable)}, stopped after {@code capMs}. */
    static void walkTo(Character bot, Point at, int mapId, long capMs, Runnable done) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (done != null && finished.compareAndSet(false, true)) {
                done.run();
            }
        };
        try {
            GCMovement.move(bot, at.x, at.y, finish);
        } catch (RuntimeException e) {
            log.debug("Could not walk {} to {},{}", bot.getName(), at.x, at.y, e);
        }
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                if (!finished.get() && bot.getMapId() == mapId) {
                    GCMovement.stop(bot);
                }
            } catch (RuntimeException e) {
                log.debug("Could not end {}'s walk", bot.getName(), e);
            }
            try {
                finish.run();
            } catch (RuntimeException e) {
                log.debug("Could not finish {}'s walk", bot.getName(), e);
            }
        }), capMs, TimeUnit.MILLISECONDS);
    }

    /** Says exactly this line in map chat. */
    static void speak(Character bot, String line) {
        if (line != null && bot != null && bot.getMap() != null) {
            BotSpeak(bot, line);
        }
    }

    /**
     * A bot done with a scene goes back to what it did, where it stands (no warp): a training bot fights here when
     * the map has mobs, else it hangs around.
     */
    static void settle(Character bot, Home home) {
        if (bot == null || home == null) {
            return;
        }
        try {
            BotSM sm = CharacterStorage.getBotById(bot.getId());
            boolean mobs = soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex.level(bot.getMapId()) >= 0;
            if (home.training() && mobs && !(sm instanceof TrainingBot)) {
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.TRAINING_BOT);
            }
        } catch (RuntimeException e) {
            log.debug("Could not settle {}", bot.getName(), e);
        }
    }

    /**
     * Sends a bot back where it was before the scene, doing what it did. A map hold the scene set is the caller's to
     * release (a staged bot keeps the hold it had).
     */
    static void sendHome(Character bot, Home home) {
        if (bot == null) {
            return;
        }
        try {
            if (home == null) {
                return;
            }
            MapleMap back = BotChannels.map(channelOf(bot), home.mapId());
            if (back != null && bot.getMapId() != home.mapId()) {
                soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(bot);
                soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.standUp(bot);
                bot.changeMap(back, new Point(home.x(), home.y()));
            }
            BotSM sm = CharacterStorage.getBotById(bot.getId());
            if (home.training() && !(sm instanceof TrainingBot)) {
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.TRAINING_BOT);
            } else if (!home.training() && sm instanceof TrainingBot) {
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.SOCIAL_BOT);
            }
        } catch (RuntimeException e) {
            log.debug("Could not send {} home", bot.getName(), e);
        }
    }

    // ── QA ──

    static void qa(QaLog.Topic topic, int mapId, String line) {
        if (QaLog.on(topic)) {
            QaLog.map(topic, mapId, line);
        }
    }

    /** Tests and staging: forget every cooldown, cap and scene hold. */
    static void resetForTest() {
        nextAt.clear();
        hourly.clear();
        busy.clear();
    }

    /** GM: clear the cooldowns whose keys start with this prefix (a forced scene). */
    static void clearCooldowns(String prefix) {
        nextAt.keySet().removeIf(k -> k.startsWith(prefix));
    }

    static Set<String> cooldownKeys() {
        return nextAt.keySet();
    }
}
