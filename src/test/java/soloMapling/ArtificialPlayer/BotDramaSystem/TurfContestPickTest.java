package soloMapling.ArtificialPlayer.BotDramaSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** rc6 auto-QA triage (t-gw-1): a turf fight gave up on one random held map that no grinder fitted. */
class TurfContestPickTest {

    static final Map<Integer, Integer> MOB_LEVEL = Map.of(105090311, 90, 104040000, 10);

    static int[] pick(List<Integer> maps, List<int[]> grinders) {
        return GuildWars.pickContest(maps, m -> MOB_LEVEL.getOrDefault(m, -1), grinders);
    }

    @Test
    void aSecondHeldMapWithAFitGrinderIsContested() {
        List<int[]> grinders = List.of(new int[]{15, 100000000}, new int[]{20, 104040000});
        assertArrayEquals(new int[]{1, 0}, pick(List.of(105090311, 104040000), grinders));
    }

    @Test
    void aGrinderAlreadyOnTheMapDoesNotCount() {
        assertNull(pick(List.of(104040000), List.<int[]>of(new int[]{12, 104040000})));
    }

    @Test
    void nobodyFitsAnyMap() {
        assertNull(pick(List.of(105090311), List.<int[]>of(new int[]{12, 100000000})));
    }
}
