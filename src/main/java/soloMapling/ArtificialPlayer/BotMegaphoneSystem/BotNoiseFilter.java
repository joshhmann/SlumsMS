package soloMapling.ArtificialPlayer.BotMegaphoneSystem;

import client.Character;
import net.packet.Packet;
import net.server.Server;
import net.server.channel.Channel;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-player noise settings for bot chatter (ticket 47): {@code @quiet megaphones} hides bot megaphones,
 * {@code @quiet bots} hides ambient bot map chat, {@code @quiet off} shows both again. Only packets sent by the bot
 * speech primitives ({@code SocialCommands}, {@code MegaphoneCommands}) pass through here, so real players' chat and
 * megaphones, GM notices and server notices are never filtered.
 * <p>
 * "Ambient" means not aimed at the viewer: a bot's line still reaches a quiet player when the player has talked to that
 * bot in the last {@code -Dsolomapling.quietEngagedSeconds} (default 180), when the bot is in the player's party, or
 * when the line names the player.
 * <p>
 * Settings are kept per character in memory and, since 1.6.0 (S5), stored through {@code SocialGate} (migration 031,
 * {@code botsocialopt}), so they survive a restart too. {@code @quiet bots} also stops unsolicited bot whispers,
 * invites and buddy requests: {@code SocialGate} reads {@link #get} for that.
 */
public final class BotNoiseFilter {

    static final long DEFAULT_ENGAGED_SECONDS = 180;

    public record Setting(boolean megaphones, boolean bots) {
        public static final Setting NONE = new Setting(false, false);

        boolean isNone() {
            return !megaphones && !bots;
        }
    }

    private static final Map<Integer, Setting> settings = new ConcurrentHashMap<>();
    private static final Map<Long, Long> engagedUntil = new ConcurrentHashMap<>();

    private BotNoiseFilter() {
    }

    public static Setting get(int charId) {
        return settings.getOrDefault(charId, Setting.NONE);
    }

    public static void set(int charId, Setting setting) {
        put(charId, setting);
        soloMapling.ArtificialPlayer.SocialGate.quietChanged(charId); // 1.6.0 S5: kept across restarts
    }

    /** The stored setting read back at boot (no write). */
    public static void restore(int charId, Setting setting) {
        put(charId, setting);
    }

    private static void put(int charId, Setting setting) {
        if (setting == null || setting.isNone()) {
            settings.remove(charId);
        } else {
            settings.put(charId, setting);
        }
    }

    public static boolean hidesMegaphones(int viewerId) {
        return get(viewerId).megaphones();
    }

    /** A real player addressed this bot (or the bot asked them something): its replies stay visible for a while. */
    public static void noteEngaged(int playerId, int botId) {
        noteEngaged(playerId, botId, System.currentTimeMillis());
    }

    static void noteEngaged(int playerId, int botId, long now) {
        if (engagedUntil.size() > 10_000) {
            engagedUntil.values().removeIf(until -> until < now);
        }
        engagedUntil.put(pair(playerId, botId), now + TimeUnit.SECONDS.toMillis(engagedSeconds()));
    }

    /**
     * Whether a bot's map-chat line is hidden from this viewer: the viewer turned bot chatter off and the line is not
     * aimed at them.
     */
    static boolean hidesBotChat(int viewerId, String viewerName, int botId, String line, boolean sameParty, long now) {
        if (!get(viewerId).bots()) {
            return false;
        }
        if (sameParty) {
            return false;
        }
        Long until = engagedUntil.get(pair(viewerId, botId));
        if (until != null && until >= now) {
            return false;
        }
        return !namesViewer(line, viewerName);
    }

    static boolean namesViewer(String line, String viewerName) {
        if (line == null || viewerName == null || viewerName.length() < 3) {
            return false;
        }
        return line.toLowerCase(Locale.ROOT).contains(viewerName.toLowerCase(Locale.ROOT));
    }

    /** Map chat from a bot: like {@code map.broadcastMessage(packet)}, minus players who muted bot chatter. */
    public static void broadcastBotChat(Character bot, Packet packet, String line) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        // owner report 2026-10-22: every public bot line (LLM, scripted, ambient, thread, bank) joins the map's log
        soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.record(
                soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.channelOf(map), map.getId(), bot.getId(),
                bot.getName(), line, true, System.currentTimeMillis());
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.BOT_CHAT)) { // !qa gd-1, gh-1
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.BOT_CHAT, map.getId(), bot.getName() + ": " + line);
        }
        if (settings.isEmpty()) {
            map.broadcastMessage(packet);
            return;
        }
        long now = System.currentTimeMillis();
        for (Character viewer : map.getAllPlayers()) {
            if (viewer == bot) {
                continue;
            }
            if (!BotHelpers.isBot(viewer) && hidesBotChat(viewer.getId(), viewer.getName(), bot.getId(), line,
                    sameParty(viewer, bot), now)) {
                continue;
            }
            viewer.sendPacket(packet);
        }
    }

    /** A bot megaphone to the whole world, minus players who muted bot megaphones. */
    public static void broadcastBotMegaphone(int world, Packet packet) {
        if (settings.isEmpty()) {
            Server.getInstance().broadcastMessage(world, packet);
            return;
        }
        for (Channel ch : Server.getInstance().getChannelsFromWorld(world)) {
            for (Character viewer : ch.getPlayerStorage().getAllCharacters()) {
                if (!hidesMegaphones(viewer.getId())) {
                    viewer.sendPacket(packet);
                }
            }
        }
    }

    private static boolean sameParty(Character viewer, Character bot) {
        return viewer.getPartyId() > 0 && viewer.getPartyId() == bot.getPartyId();
    }

    private static long engagedSeconds() {
        return Math.max(0, Long.getLong("solomapling.quietEngagedSeconds", DEFAULT_ENGAGED_SECONDS));
    }

    private static long pair(int playerId, int botId) {
        return ((long) playerId << 32) | (botId & 0xffffffffL);
    }

    /** Test seam (a "restart" in SocialGate's tests). */
    public static void forgetAllForTest() {
        settings.clear();
        engagedUntil.clear();
    }

    /** Test seam. */
    static void resetForTest() {
        settings.clear();
        engagedUntil.clear();
    }
}
