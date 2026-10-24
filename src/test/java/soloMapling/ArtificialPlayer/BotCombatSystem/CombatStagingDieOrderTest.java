package soloMapling.ArtificialPlayer.BotCombatSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc6 t-cb-3 (2026-10-23 full run, 2 of 2): the {@code die} step took the first staged bot, which is the one the
 * {@code lowpots} step had just sent to town - and a bot that left the grind is not death-eligible, so the forced
 * lethal hit did nothing and the check failed "did not die (not grinding yet?)". The step now reaches for a bot that
 * is grinding first.
 */
class CombatStagingDieOrderTest {

    @Test
    void theDeathStepReachesAGrinderBeforeTheBotThatWasJustSentToTown() {
        List<Integer> staged = List.of(101, 102, 103, 104); // staging order: 101 is what lowpots drained
        Set<Integer> grinding = Set.of(102, 103, 104);
        // old rule: BotHelpers.getCharFromChannelStorage(first staged id) - the bot in town, no death
        assertEquals(101, staged.get(0));
        // new rule: the grinders, in staging order, keep every other staged bot as a fallback
        assertEquals(List.of(102, 103, 104, 101), CombatStaging.dieOrder(staged, grinding));
    }

    @Test
    void aStageWhereOnlyTheFirstBotGrindsStillKillsThatOne() {
        List<Integer> staged = List.of(101, 102);
        assertEquals(List.of(101, 102), CombatStaging.dieOrder(staged, Set.of(101)));
    }

    @Test
    void theOrderKeepsEveryStagedBotExactlyOnce() {
        List<Integer> staged = List.of(7, 8, 9, 10);
        List<Integer> order = CombatStaging.dieOrder(staged, Set.of(8, 10));
        assertEquals(4, order.size());
        assertTrue(order.containsAll(staged));
        assertEquals(Set.copyOf(order), Set.copyOf(staged), "no staged bot is dropped and none is added");
    }
}
