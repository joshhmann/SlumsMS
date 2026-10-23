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
import soloMapling.qa.QaLog;
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
            if (out != null && ContentGuard.ok(out)) {
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

    /**
     * A whisper from a bot to a player (the stock whisper window). Held (dropped) while the player is busy - an event
     * or boss map, a trade or shop, an NPC dialog, AFK - like every unsolicited bot whisper (rc3b PlayerBusy hold-off).
     */
    static void whisper(Character bot, Character player, String line) {
        if (player != null && soloMapling.ArtificialPlayer.PlayerBusy.isBusy(player)) {
            return;
        }
        reply(bot, player, line);
    }

    /** A bot's whisper answering something the player just did (never held: the player is waiting for it). */
    static void reply(Character bot, Character player, String line) {
        if (bot == null || player == null || line == null) {
            return;
        }
        player.sendPacket(PacketCreator.getWhisperReceive(bot.getName(), Math.max(0, channelOf(bot) - 1), false, line));
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
     * Brings a bot to the player for a scene: the player's channel, then their map next to them (as a player would
     * with a return scroll and a walk). {@code grind}: it fights there (training, held on the map); else it stands and
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
                at = soloMapling.ArtificialPlayer.StageSpots.near(map, player.getPosition() == null ? at
                        : player.getPosition());
            }
            if (bot.getMapId() != map.getId()) {
                bot.changeMap(map, at);
            }
            if (grind) {
                soloMapling.worldevents.WorldEventSteer.hold(bot.getId(), map.getId());
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.TRAINING_BOT);
            } else if (CharacterStorage.getBotById(bot.getId()) instanceof TrainingBot) {
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.SOCIAL_BOT);
            }
            return bot.getMapId() == map.getId();
        } catch (RuntimeException e) {
            log.debug("Could not bring {} to {}", bot.getName(), player.getName(), e);
            return false;
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
