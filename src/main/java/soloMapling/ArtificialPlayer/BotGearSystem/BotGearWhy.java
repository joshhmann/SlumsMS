package soloMapling.ArtificialPlayer.BotGearSystem;

import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Piece;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Plan;
import soloMapling.server.DebugTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 157 (idea from nutnnut/Cosmic {@code autoequip debug}, 77e0c5bc9): why a bot last changed its gear - which
 * slots, the old and new item, and the planner's score for the whole loadout before and after (the bot's main attack
 * with its gear, {@link BotGear}) - for {@code !botwhy}. Kept only while the debug tools are on (TEST); a gear change
 * is an event (a loot or a level-up), never a tick. One short string per bot, cleared when the map grows past a cap.
 */
public final class BotGearWhy {

    static final int MAX_BOTS = 5_000;
    private static final Map<Integer, String[]> LAST = new ConcurrentHashMap<>();

    private BotGearWhy() {
    }

    /** After a review changed the bot's gear. */
    static void changed(int botId, Map<Short, Piece> worn, Plan plan, long now) {
        if (!DebugTools.isEnabled()) {
            return;
        }
        List<String> slots = new ArrayList<>();
        for (Map.Entry<Short, Piece> e : plan.wear().entrySet()) {
            Piece before = worn.get(e.getKey());
            if (before != e.getValue()) {
                slots.add("slot " + e.getKey() + " " + (before == null ? "empty" : before.itemId()) + " -> "
                        + e.getValue().itemId());
            }
        }
        for (Map.Entry<Short, Piece> e : worn.entrySet()) {
            if (!plan.wear().containsKey(e.getKey())) {
                slots.add("slot " + e.getKey() + " " + e.getValue().itemId() + " -> off");
            }
        }
        if (LAST.size() >= MAX_BOTS && !LAST.containsKey(botId)) {
            LAST.clear(); // a debug read-out, not a ledger
        }
        LAST.put(botId, new String[]{Long.toString(now), String.join("; ", slots),
                Math.round(plan.before()) + " -> " + Math.round(plan.after())});
    }

    /** The {@code !botwhy} line, or null when the bot changed no gear since the server started (or tools are off). */
    public static String describe(int botId, long now) {
        String[] last = LAST.get(botId);
        if (last == null) {
            return null;
        }
        long ago = Math.max(0, now - Long.parseLong(last[0])) / 1000;
        return "Gear pick " + ago + " s ago: " + (last[1].isEmpty() ? "no slot moved" : last[1])
                + "; loadout score (main attack) " + last[2];
    }

    /** Test seam. */
    static void clearForTest() {
        LAST.clear();
    }
}
