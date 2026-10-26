package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.autoqa.FootholdCheck.Pose.CLIMBING;
import static soloMapling.autoqa.FootholdCheck.Pose.COARSE;
import static soloMapling.autoqa.FootholdCheck.Pose.GROUNDED;

/*
 * rc6 personal space: the health sweep's "overlap" check (two grounded idle characters with |dx| < 12 and |dy| < 5 on
 * one map instance) and the town clump threshold (3 within 20 px).
 */
class OverlapCheckTest {

    static final int HENESYS = 100000000;
    static final List<FootholdCheck.Seg> GROUND = List.of(new FootholdCheck.Seg(-3000, 274, 3000, 274));

    static SweepMath.Body body(int id, int x, int y, String key) {
        return new SweepMath.Body(id, 1, HENESYS, x, y, key);
    }

    /** A town bot the movement driver does not run (recorded path), idle and grounded. */
    static HealthSweep.BotSample town(int id, int map, int x, int y, FootholdCheck.Pose pose, boolean idle, String key) {
        return new HealthSweep.BotSample(id, "Bot" + id, 1, map, x, y, pose, false, false, null, false, idle, key, true,
                false);
    }

    static HealthSweep.BotSample player(int id, int x, int y) {
        return player(id, HENESYS, x, y);
    }

    static HealthSweep.BotSample player(int id, int map, int x, int y) {
        return new HealthSweep.BotSample(id, "Player" + id, 1, map, x, y, GROUNDED, false, false, null, false, true,
                null, true, true);
    }

    static Map<String, Object> run(List<HealthSweep.BotSample> bots, List<HealthSweep.BotSample> players) {
        int[] perChannel = {bots.size(), 0, 0, 0, 0};
        HealthSweep.Snapshot s = new HealthSweep.Snapshot(0, bots, bots.size(), 1, perChannel,
                BotChannels.shares("100,0,0,0,0", 5), 0, "test.log", List.of(), null, players);
        return new HealthSweep(HealthSweep.Limits.defaults()).run(s, m -> GROUND);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> check(Map<String, Object> result, String name) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("checks")).get(name);
    }

    @Test
    void theOverlapMathIsUnderTwelveAcrossAndUnderFiveUp() {
        assertEquals(1, SweepMath.overlaps(List.of(body(1, 100, 274, null), body(2, 111, 278, null)), 12, 5).size());
        assertEquals(0, SweepMath.overlaps(List.of(body(1, 100, 274, null), body(2, 112, 274, null)), 12, 5).size(),
                "12 px across is apart");
        assertEquals(0, SweepMath.overlaps(List.of(body(1, 100, 274, null), body(2, 100, 279, null)), 12, 5).size(),
                "5 px up is apart (a slope or a step)");
        assertEquals(1, SweepMath.overlaps(List.of(body(1, 100, 274, null), body(2, 95, 270, null)), 12, 5).size(),
                "either side, either way up");
    }

    @Test
    void onlyTheSameMapInstanceCounts() {
        List<SweepMath.Body> bodies = List.of(
                new SweepMath.Body(1, 1, HENESYS, 100, 274, null),
                new SweepMath.Body(2, 2, HENESYS, 100, 274, null),      // another channel
                new SweepMath.Body(3, 1, 100000100, 100, 274, null));   // another map
        assertEquals(0, SweepMath.overlaps(bodies, 12, 5).size());
    }

    @Test
    void aDeliberatePairIsLeftOutButAStrangerOnTopOfItIsNot() {
        List<SweepMath.Body> bodies = List.of(body(1, 100, 274, "couple:1:2"), body(2, 104, 274, "couple:1:2"),
                body(3, 102, 274, null));
        List<SweepMath.Overlap> o = SweepMath.overlaps(bodies, 12, 5);
        assertEquals(2, o.size(), "the stranger overlaps both, the couple does not count: " + o);
        assertEquals(0, SweepMath.overlaps(List.of(body(1, 100, 274, "talk:7"), body(7, 100, 274, "talk:7")), 12, 5)
                .size());
        assertEquals(1, SweepMath.overlaps(List.of(body(1, 100, 274, "talk:7"), body(2, 100, 274, "talk:8")), 12, 5)
                .size(), "two different conversations are not a pair");
    }

    @Test
    void threeOnOnePixelAreThreePairs() {
        assertEquals(3, SweepMath.overlaps(List.of(body(1, 50, 274, null), body(2, 50, 274, null),
                body(3, 50, 274, null)), 12, 5).size());
    }

    @Test
    void anyOverlapWarnsAndFivePairsFailWithTheWorstMapsListed() {
        List<HealthSweep.BotSample> bots = new ArrayList<>();
        bots.add(town(1, HENESYS, 100, 274, GROUNDED, true, null));
        bots.add(town(2, HENESYS, 100, 274, GROUNDED, true, null));
        // 1.6.0: a real player on each map, far from the pairs, so they count
        List<HealthSweep.BotSample> watchers = List.of(player(40, -2000, 274), player(41, 100000100, -2000, 154));
        Map<String, Object> warn = check(run(bots, watchers), "overlap");
        assertEquals("WARN", warn.get("status"), String.valueOf(warn.get("summary")));
        assertEquals(1, warn.get("pairs"));

        for (int i = 0; i < 4; i++) {   // four more pairs on the market
            bots.add(town(10 + 2 * i, 100000100, 500 + 200 * i, 154, GROUNDED, true, null));
            bots.add(town(11 + 2 * i, 100000100, 503 + 200 * i, 154, GROUNDED, true, null));
        }
        Map<String, Object> r = run(bots, watchers);
        Map<String, Object> fail = check(r, "overlap");
        assertEquals("FAIL", fail.get("status"));
        assertEquals(5, fail.get("pairs"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> worst = (List<Map<String, Object>>) fail.get("worstMaps");
        assertEquals(100000100, worst.get(0).get("map"), "the market has 4 of the 5");
        assertEquals(4, worst.get(0).get("pairs"));
        assertEquals(HENESYS, worst.get(1).get("map"));
        assertTrue(String.valueOf(r.get("summary")).contains("overlap FAIL"));
        assertTrue(Json.isValid(Json.write(r)));
    }

    @Test
    void onlyMapsWithARealPlayerCountAndTheRestIsInfo() {
        // 1.6.0 (owner, 2026-10-26): six pairs on a map nobody is watching, one on Henesys where a player stands
        List<HealthSweep.BotSample> bots = new ArrayList<>();
        bots.add(town(1, HENESYS, 100, 274, GROUNDED, true, null));
        bots.add(town(2, HENESYS, 100, 274, GROUNDED, true, null));
        for (int i = 0; i < 6; i++) {
            bots.add(town(10 + 2 * i, 100000100, 500 + 200 * i, 154, GROUNDED, true, null));
            bots.add(town(11 + 2 * i, 100000100, 503 + 200 * i, 154, GROUNDED, true, null));
        }
        Map<String, Object> c = check(run(bots, List.of(player(40, -2000, 274))), "overlap");
        assertEquals("WARN", c.get("status"), String.valueOf(c.get("summary")));
        assertEquals(1, c.get("pairs"));
        assertEquals(7, c.get("allMapsPairs"));
        assertEquals(5, c.get("failAt"));
        assertTrue(String.valueOf(c.get("summary")).contains("7 pair(s) on all maps, info only"), String.valueOf(c));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> worst = (List<Map<String, Object>>) c.get("worstMaps");
        assertEquals(1, worst.size());
        assertEquals(HENESYS, worst.get(0).get("map"));

        Map<String, Object> nobody = check(run(bots, List.of()), "overlap");
        assertEquals("OK", nobody.get("status"), "no real player anywhere: nothing counts");
        assertTrue(String.valueOf(nobody.get("summary")).contains("7 pair(s) on all maps"));

        // the same map on another channel is another instance: a player on channel 2 does not watch channel 1
        HealthSweep.BotSample elsewhere = new HealthSweep.BotSample(40, "Player40", 2, HENESYS, -2000, 274, GROUNDED,
                false, false, null, false, true, null, true, true);
        assertEquals("OK", check(run(bots, List.of(elsewhere)), "overlap").get("status"));

        // a hidden GM (sampled as not idle) still marks the map as watched, and is never part of a pair
        HealthSweep.BotSample hidden = new HealthSweep.BotSample(41, "Gm41", 1, 100000100, 500, 154, GROUNDED,
                false, false, null, false, false, null, true, true);
        Map<String, Object> gm = check(run(bots, List.of(hidden)), "overlap");
        assertEquals("FAIL", gm.get("status"), String.valueOf(gm.get("summary")));
        assertEquals(6, gm.get("pairs"));
    }

    @Test
    void ropesLaddersCoarseMovesAndWalkersAreLeftOut() {
        List<HealthSweep.BotSample> bots = List.of(
                town(1, HENESYS, 100, 274, CLIMBING, true, null),
                town(2, HENESYS, 100, 274, GROUNDED, true, null),
                town(3, HENESYS, 300, 274, COARSE, true, null),
                town(4, HENESYS, 300, 274, GROUNDED, true, null),
                town(5, HENESYS, 500, 274, GROUNDED, false, null),   // mid recorded walk
                town(6, HENESYS, 500, 274, GROUNDED, true, null));
        Map<String, Object> c = check(run(bots, List.of()), "overlap");
        assertEquals("OK", c.get("status"), String.valueOf(c.get("summary")));
    }

    @Test
    void aBotOnAPlayersPixelCounts() {
        Map<String, Object> c = check(run(List.of(town(1, HENESYS, 100, 274, GROUNDED, true, null)),
                List.of(player(42, 104, 274))), "overlap");
        assertEquals("WARN", c.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) c.get("overlaps");
        assertTrue(String.valueOf(pairs.get(0)).contains("(player)"), String.valueOf(pairs));
    }

    @Test
    void townClumpsOfThreeWithinTwentyPxAreCaughtEvenWithoutPhysics() {
        List<HealthSweep.BotSample> bots = List.of(
                town(1, HENESYS, 100, 274, GROUNDED, true, null),
                town(2, HENESYS, 115, 274, GROUNDED, true, null),
                town(3, HENESYS, 85, 274, GROUNDED, true, null));
        Map<String, Object> c = check(run(bots, List.of()), "clumps");
        assertEquals("WARN", c.get("status"), String.valueOf(c.get("summary")));

        // spaced the way personal space keeps them (25 px): no town clump
        List<HealthSweep.BotSample> spaced = List.of(
                town(1, HENESYS, 100, 274, GROUNDED, true, null),
                town(2, HENESYS, 125, 274, GROUNDED, true, null),
                town(3, HENESYS, 75, 274, GROUNDED, true, null));
        assertEquals("OK", check(run(spaced, List.of()), "clumps").get("status"));
    }

    @Test
    void fieldMapsKeepTheOldClumpRule() {
        List<HealthSweep.BotSample> bots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {   // three driven grinders close together on a field: fine
            bots.add(new HealthSweep.BotSample(i, "G" + i, 1, 104010001, 100 + 10 * i, 274, GROUNDED, false, true, null));
        }
        assertEquals("OK", check(run(bots, List.of()), "clumps").get("status"));
    }
}
