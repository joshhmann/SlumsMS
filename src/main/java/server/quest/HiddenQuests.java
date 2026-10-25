package server.quest;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Quests SlumsMS will not let anyone start, because they can never be finished on this server (1.5.2 owner
 * decision, quest sweep). The client still lists them from its own Quest.wz; the server refuses the start, so a
 * player never begins a dead end. Nothing else changes: a player who already has one of these in progress keeps
 * it, can still complete it if they can, and can forfeit it from the quest log.
 *
 * <p>Two groups, both described in docs/tickets/82-finish-mapleroot-quest-chains.md:
 * <ul>
 *   <li>skill-book quests whose reward skill MapleRoot removed from Skill.wz (the player would do the whole quest
 *       for a skill that does not exist);</li>
 *   <li>MapleRoot's own unfinished chains (Von Leon, Henesys Ruins): steps that need an item nothing drops, an NPC
 *       that stands on no map, or that only follow such a step. Steps that work are not here.</li>
 * </ul>
 * To bring a quest back, remove it here once its content is finished.
 */
public final class HiddenQuests {

    /** Applies to every job. */
    private static final Set<Integer> ALL_JOBS = Collections.emptySet();

    private static final Map<Integer, Set<Integer>> HIDDEN = new TreeMap<>();

    static {
        // --- skill-book quests for skills MapleRoot removed ---
        hide(ALL_JOBS, 6141);                       // The Night Warrior: Ninja Ambush 4121004/4221004
        hide(Set.of(122), 6150, 6151, 6152, 6153);  // Sayram chain: Paladin's Heaven's Hammer 1221011 (Hero/DK keep it)
        hide(ALL_JOBS, 6200, 6201, 6202);           // Shadower's Assassinate 4221001
        hide(ALL_JOBS, 6330);                       // Kyrin's test: Super Transformation 5121003
        hide(ALL_JOBS, 6340);                       // Presence of Mind: Snatch 5121005
        hide(ALL_JOBS, 6350);                       // Body of Steel: Demolition 5121004
        hide(ALL_JOBS, 6410);                       // Free the Monsters: Hypnotize 5221009
        hide(ALL_JOBS, 6029, 6030, 6031, 6032, 6033, 6034); // Meren's Maker chain: the Maker skill 1007 is gone

        // --- MapleRoot's Von Leon chain (3163 and 3172 work on their own and stay) ---
        hide(ALL_JOBS, 3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171);
        hide(ALL_JOBS, 3173, 3174, 3175, 3176, 3177, 3181, 3182);

        // --- MapleRoot's Henesys Ruins quests that need an item nothing gives (or follow one that does) ---
        hide(ALL_JOBS, 31110, 31111, 31114, 31115, 31116, 31117, 31118, 31122, 31123);
        hide(ALL_JOBS, 31132, 31133, 31134, 31135, 31141, 31142, 31143);
        hide(ALL_JOBS, 31158, 31159, 31160);
    }

    private HiddenQuests() {
    }

    private static void hide(Set<Integer> jobs, int... questIds) {
        for (int id : questIds) {
            HIDDEN.put(id, jobs);
        }
    }

    /** True when a character of {@code jobId} may not start {@code questId}. */
    public static boolean isHidden(int questId, int jobId) {
        Set<Integer> jobs = HIDDEN.get(questId);
        return jobs != null && (jobs.isEmpty() || jobs.contains(jobId));
    }

    /** Every quest id that is hidden for at least one job, ascending. */
    public static Set<Integer> ids() {
        return Collections.unmodifiableSet(HIDDEN.keySet());
    }
}
