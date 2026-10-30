package soloMapling.ArtificialPlayer.BotQuestSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ticket 158 item 5: the Temple of Time mainline as data (quests 3500 to 3520: Memory Lane, the Road of Regrets, the
 * Road to Oblivion and their keepers). Ideas from nutnnut/Cosmic 77e0c5bc9 ({@code BotTempleProgressionManager},
 * AGPL-3.0): the chain, its placements and the job sub-quest that unlocks 3507. Ours: the lanes as lists of maps to
 * disperse over, the lane-exit portals, and every special step run through the quest's own script (upstream forces
 * the sub-quests and 3514 with {@code forceStart}/{@code forceComplete}; we never do).
 *
 * <p>Every number here is checked against the server's own data by {@code TempleRouteTest}: Quest.wz Check.img (NPCs,
 * levels, kill targets, scripts), Map.wz (the NPCs' maps, which maps spawn each lane monster) and
 * {@code scripts/portal/timeQuest.js} (which quest opens which lane exit, run for real against a fake portal manager).
 */
public final class TempleRoute {

    private TempleRoute() {
    }

    /** The Temple Keeper (2140000) stands in the Temple of Time hub. */
    public static final int TEMPLE_KEEPER = 2140000, HUB = 270000000;
    public static final int MEMORY_KEEPER = 2140001, MEMORY_MAP = 270010111;
    public static final int SORCERER = 2140002, SORCERER_MAP = 270020211;
    public static final int RECORD_KEEPER = 2140003, RECORD_MAP = 270030411;

    /** 3507 (Memory Keeper) completes only after the job's sub-quest at its first instructor (scripts/quest/3507.js). */
    public static final int Q_MEMORY_KEEPER = 3507;
    /** 3514 (the Sorcerer who sells emotions): his start script charges this much for potion 2022337. */
    public static final int Q_SORCERER = 3514;
    public static final int SORCERER_POTION = 2022337;
    public static final int SORCERER_FEE = 1_000_000;

    /**
     * One mainline quest: who gives it where, who takes it back where, the kills it needs, the lane maps those
     * monsters spawn on that a bot can reach while the quest is open (a lane monster that also spawns past the
     * quest's own exit, like 8200004 on Memory Lane 5, is only huntable on the earlier map), and whether its start
     * or end runs a script. One lane map per quest in v83's Temple, so the dispersal is over channels.
     */
    public record Step(int questId, int lvmin, int startNpc, int startMap, int endNpc, int endMap,
                       Map<Integer, Integer> mobs, List<Integer> lanes, boolean startScript, boolean endScript) {
        public Step {
            mobs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mobs));
            lanes = List.copyOf(lanes);
        }

        /** A quest with no kills (talk to one NPC, then another). */
        public boolean talk() {
            return mobs.isEmpty();
        }
    }

    /** The job's sub-quest that unlocks 3507: a yes at its first instructor (the quest's start script). */
    public record SubQuest(int questId, int npc, int map) {
    }

    private static Step talk(int q, int lv, int startNpc, int startMap, int endNpc, int endMap) {
        return new Step(q, lv, startNpc, startMap, endNpc, endMap, Map.of(), List.of(), false, false);
    }

    private static Step hunt(int q, int lv, int mob, int count, Integer... lanes) {
        return new Step(q, lv, TEMPLE_KEEPER, HUB, TEMPLE_KEEPER, HUB, Map.of(mob, count), List.of(lanes), false, false);
    }

    /** The mainline in order; each quest needs the one before it (Quest.wz Check.img). */
    public static final List<Step> MAINLINE = List.of(
            talk(3500, 90, TEMPLE_KEEPER, HUB, TEMPLE_KEEPER, HUB),
            hunt(3501, 91, 8200001, 999, 270010100),
            hunt(3502, 94, 8200002, 999, 270010200),
            hunt(3503, 98, 8200003, 999, 270010300),
            hunt(3504, 101, 8200004, 999, 270010400),
            hunt(3505, 103, 8220004, 1, 270010500),
            talk(3506, 103, TEMPLE_KEEPER, HUB, MEMORY_KEEPER, MEMORY_MAP),
            new Step(Q_MEMORY_KEEPER, 103, MEMORY_KEEPER, MEMORY_MAP, MEMORY_KEEPER, MEMORY_MAP, Map.of(), List.of(),
                    false, true),
            hunt(3508, 106, 8200005, 999, 270020100),
            hunt(3509, 109, 8200006, 999, 270020200),
            hunt(3510, 113, 8200007, 999, 270020300),
            hunt(3511, 116, 8200008, 999, 270020400),
            hunt(3512, 120, 8220005, 1, 270020500),
            talk(3513, 120, TEMPLE_KEEPER, HUB, SORCERER, SORCERER_MAP),
            new Step(Q_SORCERER, 120, SORCERER, SORCERER_MAP, SORCERER, SORCERER_MAP, Map.of(), List.of(), true, true),
            hunt(3515, 121, 8200009, 999, 270030100),
            hunt(3516, 124, 8200010, 999, 270030200),
            hunt(3517, 128, 8200011, 999, 270030300),
            hunt(3518, 131, 8200012, 999, 270030400),
            hunt(3519, 135, 8220006, 1, 270030500),
            talk(3520, 135, TEMPLE_KEEPER, HUB, RECORD_KEEPER, RECORD_MAP));

    /** The mainline's position of a quest, or -1. */
    public static int indexOf(int questId) {
        for (int i = 0; i < MAINLINE.size(); i++) {
            if (MAINLINE.get(i).questId() == questId) {
                return i;
            }
        }
        return -1;
    }

    /** The first-instructor sub-quests (Quest.wz Check.img npc + job; Map.wz for the instructor's map). */
    public static final List<SubQuest> SUB_QUESTS = List.of(
            new SubQuest(3523, 1022000, 102000003),   // warrior: Dances with Balrog, Perion
            new SubQuest(3524, 1032001, 101000003),   // magician: Grendel the Really Old, Ellinia
            new SubQuest(3525, 1012100, 100000201),   // bowman: Athena Pierce, Henesys
            new SubQuest(3526, 1052001, 103000003),   // thief: Dark Lord, Kerning City
            new SubQuest(3527, 1090000, 120000101),   // pirate: Kyrin, Nautilus
            new SubQuest(3529, 1101002, 130000000),   // Cygnus Knights: Ereve
            new SubQuest(3539, 1201000, 140000000));  // Aran: Rien

    /** The sub-quest for a job (by its branch, as the quests' job lists split them). */
    public static SubQuest subQuestFor(int jobId) {
        if (jobId >= 2000) {
            return SUB_QUESTS.get(6);
        }
        if (jobId >= 1000) {
            return SUB_QUESTS.get(5);
        }
        int branch = jobId / 100;
        return switch (branch) {
            case 1 -> SUB_QUESTS.get(0);
            case 2 -> SUB_QUESTS.get(1);
            case 3 -> SUB_QUESTS.get(2);
            case 4 -> SUB_QUESTS.get(3);
            case 5 -> SUB_QUESTS.get(4);
            default -> SUB_QUESTS.get(0);
        };
    }

    public static boolean isSubQuest(int questId) {
        for (SubQuest s : SUB_QUESTS) {
            if (s.questId() == questId) {
                return true;
            }
        }
        return false;
    }

    /**
     * A lane's exit (the {@code in00} portal, {@code timeQuest.js}): from {@code fromMap} it lets through to
     * {@code toMap} (portal {@code out00}) whoever completed {@code quest}; everyone else is sent back to the safe lane.
     */
    public record LaneExit(int fromMap, int toMap, int quest) {
    }

    /** Every lane exit the mainline uses, as {@code scripts/portal/timeQuest.js} decides them. */
    public static final List<LaneExit> EXITS = List.of(
            new LaneExit(270010100, 270010110, 3501),
            new LaneExit(270010200, 270010210, 3502),
            new LaneExit(270010300, 270010310, 3503),
            new LaneExit(270010400, 270010410, 3504),
            new LaneExit(270010500, 270020000, 3507),
            new LaneExit(270020100, 270020110, 3508),
            new LaneExit(270020200, 270020210, 3509),
            new LaneExit(270020300, 270020310, 3510),
            new LaneExit(270020400, 270020410, 3511),
            new LaneExit(270020500, 270030000, 3514),
            new LaneExit(270030100, 270030110, 3515),
            new LaneExit(270030200, 270030210, 3516),
            new LaneExit(270030300, 270030310, 3517),
            new LaneExit(270030400, 270030410, 3518),
            new LaneExit(270030500, 270040000, 3519));

    /**
     * The mainline quest a bot must have completed before it may be on this Temple map. The corridor runs in map-id
     * order (Memory Lane 2700100xx, the Road of Regrets 2700200xx, the Road to Oblivion 2700300xx, then 270040000), so
     * a map needs the quest of the last lane exit that leads to it or to a map before it. 0 for the hub, Memory Lane
     * 1 and anything outside the corridor.
     */
    public static int questToEnter(int mapId) {
        if (mapId < 270010000 || mapId > 270040000) {
            return 0;
        }
        int quest = 0;
        for (LaneExit e : EXITS) {
            if (e.toMap() <= mapId) {
                quest = e.quest(); // EXITS run in corridor order: the last one wins
            }
        }
        return quest;
    }

    /** All the lane maps of the mainline (for !temple and the tests). */
    public static List<Integer> laneMaps() {
        List<Integer> out = new ArrayList<>();
        for (Step s : MAINLINE) {
            for (int m : s.lanes()) {
                if (!out.contains(m)) {
                    out.add(m);
                }
            }
        }
        return out;
    }
}
