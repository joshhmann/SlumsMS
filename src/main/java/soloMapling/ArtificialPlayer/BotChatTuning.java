package soloMapling.ArtificialPlayer;

import constants.game.GameConstants;
import constants.id.MapId;
import server.maps.MapleMap;
import server.maps.MiniDungeonInfo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Ambient-only rates. Player conversations and scripted action responses keep their own timing. */
public final class BotChatTuning {
    public enum MapType { TOWN, HUNTING, DUNGEON }

    private static final Map<String, Long> lastMapLine = new HashMap<>();
    private static final Map<Integer, ArrayDeque<Long>> worldLines = new HashMap<>();

    private BotChatTuning() { }

    public static boolean isBossMap(int id) {
        // Existing rebirth/dojo/boss-rush predicates, plus arenas from scripts/event/*Battle*.js.
        return MapId.isRB0BossMap(id) || MapId.isRB1BossMap(id) || MapId.isRb2BossMap(id)
                || MapId.MAGNUS_MAP(id) || MapId.LOTUS_MAP(id) || MapId.LOTUS_TRAINING_MAP(id)
                || MapId.isBossRush(id) || GameConstants.isDojoBossArea(id)
                || id == MapId.ORIGIN_OF_CLOCKTOWER || id == MapId.CAVE_OF_PIANUS
                || id >= 280030000 && id <= 280030001
                || id >= 270050100 && id <= 270050300
                || id >= 211070100 && id <= 211070101
                || id >= 105100300 && id <= 105100301 || id >= 105100400 && id <= 105100401
                || id >= 551030200 && id <= 551030202
                || id == 541020800 || id == 541010100 || id == 800040410 || id == 801040100
                || id == 450004750 || id == 450004751 || id == 450007440 || id == 450007441
                || id == 450010100 || id == 450010101 || id == 450012210 || id == 450012211
                || id >= 240070203 && id <= 240070603 && id % 100 == 3
                || id == 922020100 || id == 925010300 || id == 914020000;
    }

    static boolean hasBoss(MapleMap map) {
        return map != null && map.getAllMonsters().stream().anyMatch(m -> m.isBoss());
    }

    public static MapType mapType(MapleMap map) {
        if (map == null) {
            return MapType.HUNTING;
        }
        int id = map.getId();
        if (isBossMap(id) || MiniDungeonInfo.isDungeonMap(id) || map.getEventInstance() != null || map.isEventMap()
                || hasBoss(map)) {
            return MapType.DUNGEON;
        }
        if (map.isTown() || id == MapId.FM_ENTRANCE || id == MapId.MAPLEROOT_HUB
                || GameConstants.isFreeMarketRoom(id)) {
            return MapType.TOWN;
        }
        return MapType.HUNTING;
    }

    public static double multiplier(MapleMap map) {
        return multiplier(mapType(map));
    }

    static double multiplier(MapType type) {
        return switch (type) {
            case TOWN -> number("townMultiplier", 1.5);
            case HUNTING -> number("huntingMultiplier", 0.15);
            case DUNGEON -> number("dungeonMultiplier", 0.01);
        };
    }

    static double number(String key, double fallback) {
        try {
            double value = Double.parseDouble(System.getProperty("solomapling.chat." + key, "" + fallback));
            return Double.isFinite(value) && value >= 0 ? Math.min(10, value) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Final line budget shared by all ambient sources. No queue and no catch-up tokens. */
    public static boolean allowAmbient(client.Character bot) {
        if (bot == null || bot.getMap() == null) {
            return false;
        }
        return takeAmbient(bot.getWorld(), bot.getClient().getChannel(), bot.getMapId(),
                multiplier(bot.getMap()), System.currentTimeMillis());
    }

    static synchronized boolean takeAmbient(int world, int channel, int map, double multiplier, long now) {
        if (multiplier <= 0) {
            return false;
        }
        long baseGap = Math.max(1, Long.getLong("solomapling.chat.ambientMapGapMs", 3_000));
        long gap = Math.max(1, (long) (baseGap / multiplier));
        String key = world + ":" + channel + ":" + map;
        Long last = lastMapLine.get(key);
        if (last != null && now - last < gap) {
            return false;
        }
        ArrayDeque<Long> lines = worldLines.computeIfAbsent(world, k -> new ArrayDeque<>());
        while (!lines.isEmpty() && now - lines.peekFirst() >= 60_000) {
            lines.removeFirst();
        }
        int cap = Math.max(0, Integer.getInteger("solomapling.chat.ambientWorldPerMinute", 60));
        if (lines.size() >= cap) {
            return false;
        }
        lastMapLine.entrySet().removeIf(e -> now - e.getValue() >= 86_400_000);
        lastMapLine.put(key, now);
        lines.addLast(now);
        return true;
    }

    static synchronized void resetForTest() {
        lastMapLine.clear();
        worldLines.clear();
    }
}
