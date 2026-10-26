package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import net.server.channel.Channel;
import server.maps.MapleMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1.6.0 S8, the owner's idea: a bot on a crowded map, or next to a KS war, says "cc" or "too many ppl" and appears on
 * the same map on a quieter channel, like a player changing channel.
 *
 * <p>The move is {@code BotChannels.moveToChannel} (the companion channel follow since 1.5.0 rc3): same map, same
 * spot, the bot leaves its old channel's storage and joins the new one, so the director's per-channel counts, the map
 * bot counters and the per-map cap all follow it. Never mid-trade, mid-party (any party), inside an event instance
 * (a PQ or a boss), dead, or while the brain holds a commitment; never onto a map at its cap; at most once per
 * {@link #COOLDOWN_MS} per bot.
 */
public final class LifeChannelMove {
    private LifeChannelMove() {}

    /** One channel change per bot per this long. */
    public static final long COOLDOWN_MS = 30 * 60_000L;
    /** A map with no cap counts as crowded against this many bots. */
    static final int UNCAPPED_CROWD = 40;
    /** The target must hold at most this share of the bots on this map here (it must be clearly quieter). */
    static final double QUIETER = .6;

    static final List<String> CROWD_LINES = List.of("cc", "too many ppl", "cc too crowded", "ugh cc",
            "way too many ppl here, cc", "cc lol");
    static final List<String> KS_LINES = List.of("cc", "ks fest, cc", "not dealing with this ks, cc", "cc lol");

    private static final Map<Integer, Long> lastMove = new ConcurrentHashMap<>();

    // ── pure ──

    /** Why the bot may not change channel, or null when it may. */
    public static String blocked(boolean trading, boolean inParty, boolean inInstance, boolean commitment,
                                 boolean dead, boolean coolingDown) {
        if (trading) return "mid-trade";
        if (inParty) return "in a party";
        if (inInstance) return "in a PQ or boss instance";
        if (commitment) return "holding a commitment";
        if (dead) return "dead";
        if (coolingDown) return "changed channel recently";
        return null;
    }

    /** The map's load against its cap: 1 = at the cap. A cap of 0 (none) is judged against {@link #UNCAPPED_CROWD}. */
    public static double crowd(int bots, int cap) {
        return Math.max(0, bots) / (double) (cap > 0 ? cap : UNCAPPED_CROWD);
    }

    /**
     * The quieter channel (1-based) for a bot on channel {@code current}: the one with room and the fewest bots on
     * this map, holding at most {@link #QUIETER} of the bots here; -1 when there is none.
     * {@code botsOnMap[i]} is channel i+1's count of bots on the same map.
     */
    public static int quieter(int current, int[] botsOnMap, int cap) {
        int here = current - 1 >= 0 && current - 1 < botsOnMap.length ? botsOnMap[current - 1] : 0;
        int best = -1;
        for (int i = 0; i < botsOnMap.length; i++) {
            if (i == current - 1 || !soloMapling.ArtificialPlayer.BotMapCap.hasRoom(botsOnMap[i], cap)) continue;
            if (botsOnMap[i] > here * QUIETER) continue;
            if (best < 0 || botsOnMap[i] < botsOnMap[best]) best = i;
        }
        return best < 0 ? -1 : best + 1;
    }

    // ── live ──

    /** This bot's map load (bots against the map's cap). */
    public static double crowdOf(Character chr) {
        MapleMap map = chr == null ? null : chr.getMap();
        if (map == null) return 0;
        return crowd(map.getBotCount(), soloMapling.ArtificialPlayer.BotMapCap.cap(map.getId()));
    }

    public static boolean ksWarHere(Character chr) {
        try {
            return chr != null && soloMapling.ArtificialPlayer.BotDramaSystem.BotRivalry.ksWarOn(chr.getMap());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Why this bot can't change channel now (null: it can), the brain's commitment state given. */
    public static String why(Character chr, boolean commitment, long now) {
        if (chr == null || chr.getMap() == null || chr.getClient() == null) return "not on a map";
        return blocked(chr.getTrade() != null || chr.getPlayerShop() != null || chr.getMiniGame() != null,
                chr.getParty() != null, chr.getEventInstance() != null, commitment,
                soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.isDead(chr.getId()),
                now - lastMove.getOrDefault(chr.getId(), Long.MIN_VALUE / 2) < COOLDOWN_MS);
    }

    /** The quieter channel for this bot's map now, or -1. Reads each channel's loaded instance of the map only. */
    public static int target(Character chr) {
        MapleMap here = chr == null ? null : chr.getMap();
        if (here == null) return -1;
        List<Channel> channels = soloMapling.ArtificialPlayer.BotChannels.channels();
        int[] counts = new int[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            var factory = channels.get(i).getMapFactory();
            // A channel whose instance isn't loaded holds no bots there; moving loads it, as a player's cc does.
            counts[i] = factory.isMapLoaded(here.getId()) ? factory.getMap(here.getId()).getBotCount() : 0;
        }
        return quieter(here.getChannelId(), counts, soloMapling.ArtificialPlayer.BotMapCap.cap(here.getId()));
    }

    /** The line it says before it goes. */
    public static String line(boolean ksWar) {
        List<String> lines = ksWar ? KS_LINES : CROWD_LINES;
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    /**
     * Says the line (only where a real player can see it) and moves the bot to {@code channel}, the same map. Call on
     * the bot's own update. True when it moved.
     */
    public static boolean move(Character chr, int channel, boolean ksWar, long now) {
        if (chr == null || channel <= 0) return false;
        if (soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(chr.getMapId())) {
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat(chr, line(ksWar));
        }
        int from = chr.getMap().getChannelId();
        int map = chr.getMapId();
        if (!soloMapling.ArtificialPlayer.BotChannels.moveToChannel(chr, channel)) return false;
        lastMove.put(chr.getId(), now);
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS, "LIFE CC bot=" + chr.getName() + " map=" + map
                    + " ch" + from + "->ch" + channel + (ksWar ? " ksWar" : " crowded"));
        }
        return true;
    }

    /** Test/QA seam: forget the cooldowns. */
    public static void resetForTest() {
        lastMove.clear();
    }

    /** QA: clear one bot's cooldown so a staged move can run. */
    public static void forgetCooldown(int charId) {
        lastMove.remove(charId);
    }
}
