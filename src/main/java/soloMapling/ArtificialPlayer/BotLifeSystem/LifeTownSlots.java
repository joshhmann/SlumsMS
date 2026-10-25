package soloMapling.ArtificialPlayer.BotLifeSystem;

import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMapCap;
import java.util.*;

/** Counts travellers as well as arrivals so simultaneous town visits cannot all claim the last free slot. */
public final class LifeTownSlots {
    private LifeTownSlots() {}
    private static final Map<Integer, Long> byBot = new HashMap<>();
    private static final Map<Long, Integer> incoming = new HashMap<>();
    private static long key(MapleMap map) { return ((long) map.getChannelId() << 32) | (map.getId() & 0xffffffffL); }
    public static synchronized boolean reserve(int botId, MapleMap map) {
        long key = key(map);
        if (Objects.equals(byBot.get(botId), key)) return true;
        int cap = BotMapCap.cap(map.getId());
        if (!BotMapCap.hasRoom(map.getBotCount() + incoming.getOrDefault(key, 0), cap)) return false;
        release(botId);
        byBot.put(botId, key); incoming.merge(key, 1, Integer::sum);
        return true;
    }
    public static synchronized void release(int botId) {
        Long key = byBot.remove(botId);
        if (key != null) incoming.computeIfPresent(key, (k, count) -> count <= 1 ? null : count - 1);
    }
    public static synchronized int size() { return byBot.size(); }
}
