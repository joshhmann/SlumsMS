package server.quest;

import client.SkillFactory;
import client.inventory.MakerService;

import java.util.Arrays;
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
 * <p>Three groups (the first two are described in docs/tickets/82-finish-mapleroot-quest-chains.md):
 * <ul>
 *   <li>skill-book quests whose reward skill MapleRoot removed from Skill.wz (the player would do the whole quest
 *       for a skill that does not exist);</li>
 *   <li>MapleRoot's own unfinished chains (Henesys Ruins; the Von Leon chain came back in 1.6.4): steps that need an item nothing drops, an NPC
 *       that stands on no map, or that only follow such a step. Steps that work are not here.</li>
 *   <li>medal quests that ask for a medal item nothing in the server hands out, and whose acts grant nothing
 *       anyway.</li>
 * </ul>
 * To bring a quest back, remove it here once its content is finished.
 *
 * <p>One group above is not fixed by code but by server data: Moren's Maker chain (6029-6036) teaches a skill that
 * ships as {@code Skill.wz} nodes written by {@code tools/data-align/add_maker_skill.py --apply} (ticket 112).
 * {@link #isHidden(int, int)} therefore also hides the chain while the character's own job family has no Maker
 * skill, and stops hiding it the moment the WZ data is there and the server restarted.
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
        // Moren's Maker chain 6029-6036 is back in 1.6.2 (ticket 112, owner 2026-10-30): Skill.wz has Maker again
        // (tools/data-align/add_maker_skill.py), so the chain teaches Maker 1/2/3. That data is a release step per
        // server, not code, so the chain is kept out of this map and gated in isHidden() on the skill being loaded.

        // --- the Von Leon chain 3163-3177, 3181-3182 is open again (1.6.4, owner Round 7): every item has a quest-only
        // drop (065-lkc-quest-drops), Murt 2160000 has his Npc.wz entry (tools/data-align/add_murt.py) and the Von Leon
        // NPCs and the Rose Garden golem stand on their maps. The fight itself keeps the rebirth gate (2161000.js).

        // --- MapleRoot's Henesys Ruins quests that need an item nothing gives (or follow one that does) ---
        hide(ALL_JOBS, 31110, 31111, 31114, 31115, 31116, 31117, 31118, 31122, 31123);
        hide(ALL_JOBS, 31132, 31133, 31134, 31135, 31141, 31142, 31143);
        hide(ALL_JOBS, 31158, 31159, 31160);

        // --- medal quests whose medal item exists nowhere obtainable (no drop, shop, reactor, Maker, act or script) ---
        // block 1 only asks to talk to the NPC and both act blocks are empty, so even a medal from a GM would grant
        // nothing; refusing the start tells the player instead of failing silently on the item requirement
        hide(ALL_JOBS, 29500);                      // medal 1142003
        hide(ALL_JOBS, 29915);                      // medal 1142064
        hide(ALL_JOBS, 29916, 29917, 29918, 29919, 29920, 29921);   // medals 1142014-1142019
        hide(ALL_JOBS, 29933);                      // medal 1142030
    }

    private HiddenQuests() {
    }

    private static void hide(Set<Integer> jobs, int... questIds) {
        for (int id : questIds) {
            HIDDEN.put(id, jobs);
        }
    }

    /** Moren's Maker chain (ticket 112): 6029 starts it, 6033 and 6036 teach Maker 2 and 3. */
    private static final int[] MAKER_CHAIN = {6029, 6030, 6031, 6032, 6033, 6034, 6035, 6036};

    /**
     * The chain has nowhere to teach while the character's job family has no Maker skill: the 6029 act
     * ({@code SkillAction}) and 6033/6036 ({@code teachSkill}) skip an unknown skill without telling the player,
     * the three lessons still charge 10,000 mesos each, and 6033/6036 then ask for items only Maker can craft. So
     * a server that never ran the WZ tool is a dead end, not a slow start.
     */
    private static boolean morenChainWithoutMaker(int questId, int jobId) {
        return Arrays.binarySearch(MAKER_CHAIN, questId) >= 0
                && SkillFactory.getSkill(MakerService.makerSkillId(jobId)) == null;
    }

    /** True when a character of {@code jobId} may not start {@code questId}. */
    public static boolean isHidden(int questId, int jobId) {
        if (morenChainWithoutMaker(questId, jobId)) {
            return true;
        }
        Set<Integer> jobs = HIDDEN.get(questId);
        return jobs != null && (jobs.isEmpty() || jobs.contains(jobId));
    }

    /**
     * Every quest id this file hides regardless of server data, ascending. The Maker chain is not here: whether it
     * is hidden depends on Skill.wz, see {@link #isHidden(int, int)}.
     */
    public static Set<Integer> ids() {
        return Collections.unmodifiableSet(HIDDEN.keySet());
    }
}
