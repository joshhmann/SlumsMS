package soloMapling.ArtificialPlayer.BotGuildSystem;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * t-gu-5 (TEST d4f103b0e): the check skipped with "1 free roster members online". gu-5 now picks the bot guild needing
 * the fewest offline roster members logged in for a fast run (2+ at a boss's level), and logs those in.
 */
class GuildBossRunQaCrewTest {

    private static List<int[]> offline(int[]... rows) {
        return Arrays.asList(rows);
    }

    @Test
    void enoughOnlineNeedsNoLogins() {
        assertEquals(List.of(), GuildBossRuns.crewToStage(new int[]{35, 41}, offline(), 2, null));
    }

    @Test
    void oneOnlineLogsInTheHighestOfflineMember() {
        // the TEST case: one free member online; the level-60 offline member makes a GPQ/Zakum pair with it
        assertEquals(List.of(1002),
                GuildBossRuns.crewToStage(new int[]{55}, offline(new int[]{1001, 12}, new int[]{1002, 60}), 2, null));
    }

    @Test
    void lowMembersAreAddedUntilABossFits() {
        assertEquals(List.of(1001, 1002), GuildBossRuns.crewToStage(new int[]{10},
                offline(new int[]{1001, 33}, new int[]{1002, 31}, new int[]{1003, 8}), 2, null));
    }

    @Test
    void aNamedBossCountsOnlyMembersAtItsLevel() {
        GuildRules.Boss zakum = GuildRules.boss("zakum");
        assertEquals(List.of(1002), GuildBossRuns.crewToStage(new int[]{52, 40},
                offline(new int[]{1001, 45}, new int[]{1002, 70}), 2, zakum));
        assertNull(GuildBossRuns.crewToStage(new int[]{52}, offline(new int[]{1001, 45}), 2, zakum),
                "no second member at 50+: not this guild");
    }

    @Test
    void noGuildCanRunWhenNobodyIsHighEnough() {
        assertNull(GuildBossRuns.crewToStage(new int[]{}, offline(new int[]{1001, 20}, new int[]{1002, 25}), 2, null));
    }

    @Test
    void theGuildNeedingFewestLoginsIsPicked() {
        assertEquals(1, GuildBossRuns.fewestLogins(Arrays.asList(List.of(1, 2), List.of(), null)));
        assertEquals(0, GuildBossRuns.fewestLogins(Arrays.asList(List.of(7), null, List.of(8, 9))));
        assertEquals(-1, GuildBossRuns.fewestLogins(Arrays.asList(null, null)));
    }
}
