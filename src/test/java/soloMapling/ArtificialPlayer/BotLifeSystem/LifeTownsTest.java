package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.0 towns: BOT_LIFE_TOWN_WEIGHTS and the town table (near, home, henesys; bands). */
class LifeTownsTest {
    static final long NOW = 1_800_000L * 1000;

    @Test
    void theShippedWeightsAreHenesysOnlyAsBefore() {
        assertArrayEquals(new double[]{0, 0, 100}, LifeTowns.weights(new config.ServerConfig().BOT_LIFE_TOWN_WEIGHTS));
        assertArrayEquals(new double[]{0, 0, 1}, LifeTowns.weights(""));
        assertArrayEquals(new double[]{0, 0, 1}, LifeTowns.weights("near:x"), "a broken spec is today's behaviour");
        assertArrayEquals(new double[]{0, 0, 1}, LifeTowns.weights("mars:5"));
        assertArrayEquals(new double[]{55, 20, 25}, LifeTowns.weights("near:55, home:20 ,henesys:25"));
        for (int bot = 0; bot < 200; bot++) {
            var pick = LifeTowns.pick(LifeTowns.weights("henesys:100"), 101000000, 102000000, bot, NOW);
            assertEquals(LifeRuntime.HOME_TOWN, pick.mapId());
            assertEquals(LifeTowns.Source.HENESYS, pick.source());
        }
    }

    @Test
    void thePickFollowsTheWeightsAndIsStableForHalfAnHour() {
        double[] w = LifeTowns.weights("near:55,home:20,henesys:25");
        Map<LifeTowns.Source, Integer> counts = new EnumMap<>(LifeTowns.Source.class);
        for (int bot = 0; bot < 4000; bot++) {
            var pick = LifeTowns.pick(w, 101000000, 102000000, bot, NOW);
            counts.merge(pick.source(), 1, Integer::sum);
            assertEquals(pick, LifeTowns.pick(w, 101000000, 102000000, bot, NOW + 1_799_999), "same bucket, same town");
        }
        assertEquals(.55, counts.get(LifeTowns.Source.NEAR) / 4000.0, .04);
        assertEquals(.20, counts.get(LifeTowns.Source.HOME) / 4000.0, .04);
        assertEquals(.25, counts.get(LifeTowns.Source.HENESYS) / 4000.0, .04);
        // no near town and no home town: everything renormalises to Henesys
        for (int bot = 0; bot < 100; bot++) {
            assertEquals(LifeTowns.Source.HENESYS, LifeTowns.pick(w, -1, -1, bot, NOW).source());
        }
        // no home: near and Henesys split 55:25
        int near = 0;
        for (int bot = 0; bot < 4000; bot++) if (LifeTowns.pick(w, 101000000, -1, bot, NOW).source() == LifeTowns.Source.NEAR) near++;
        assertEquals(55 / 80.0, near / 4000.0, .04);
    }

    @Test
    void nearAndHomeRespectTheTownsLevelBands() {
        // Ellinia's field returns to Ellinia; standing in Henesys Market is "near" Henesys Market
        assertEquals(101000000, LifeTowns.near(101010000, 101000000, 20));
        assertEquals(100000100, LifeTowns.near(100000100, 100000000, 20));
        assertEquals(-1, LifeTowns.near(104040000, 999999999, 20), "a return map that is no town is no near town");
        // no Lv20 hanging out in El Nath or Leafre, a Lv80 is fine
        assertEquals(-1, LifeTowns.near(211040000, 211000000, 20));
        assertEquals(211000000, LifeTowns.near(211040000, 211000000, 80));
        assertEquals(-1, LifeTowns.home(240000000, 20));
        assertEquals(240000000, LifeTowns.home(240000000, 90));
        assertEquals(-1, LifeTowns.home(105050000, 40), "an Ant Tunnel junction home is no town");
        assertEquals(100000000, LifeTowns.home(100000200, 10), "a home in Henesys's family is Henesys");
        assertTrue(LifeTowns.isTownMap(220000000));
        assertTrue(LifeTowns.isTownMap(910000000), "the Free Market and its rooms are one family");
        assertEquals("Free Market", LifeTowns.townOf(910000022).name());
        assertFalse(LifeTowns.isTownMap(910000023));
    }
}
