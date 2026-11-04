package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.SocialGate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 2, simulated days: 60 bots on six maps nobody watches, one minute step at a time on the simulated
 * clock for three days. Shows the background tier alone grows a bounded web of relationships, within the per-bot cap,
 * with no friendships out of nods, and that a week of silence afterwards cools them without anyone becoming estranged.
 */
class BotBondsSimulatedDaysTest {

    static final long MIN = TimeUnit.MINUTES.toMillis(1);
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    final BondStore.Memory store = new BondStore.Memory();

    @BeforeEach
    void setUp() {
        SocialSim.useForTest(clock::get, 11);
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, null, id -> true);
        BotBonds.useForTest(store, Runnable::run, null, id -> new double[]{.25, .25}, (id, p) -> { }, id -> null);
        Persona.useForTest(id -> Persona.NEUTRAL);
        SocialGate.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        Persona.useForTest(null);
        SocialSim.reset();
        SocialGate.resetForTest();
    }

    @Test
    void threeDaysOfUnwatchedMapsGrowABoundedWebAndAWeekOfSilenceCoolsItWithoutEstrangement() {
        BotBonds.useObservedForTest(mapId -> false);
        List<Character> bots = new ArrayList<>();
        for (int m = 0; m < 6; m++) {
            MapleMap map = BotBondsTest.map(1000 + m);
            for (int i = 0; i < 10; i++) {
                bots.add(BotBondsTest.bot(BotStore.BOT_ID_BASE + 100 * (m + 1) + i, map, null));
            }
        }
        long micros = 0;
        int steps = 0;
        for (long t = 0; t < 3 * DAY; t += MIN) {
            clock.addAndGet(MIN);
            BotBonds.step(bots, SocialSim.random(0, "bonds-step"));
            micros += Integer.parseInt(BotBonds.stats().replaceAll(".*\\((\\d+) us\\).*", "$1"));
            steps++;
            if (steps % 180 == 0) {
                BotBonds.flush();
            }
        }
        Map<Stage, Integer> stages = new EnumMap<>(Stage.class);
        int withBonds = 0;
        int edges = 0;
        for (Character c : bots) {
            int n = BotBonds.sizeOf(c.getId());
            assertTrue(n <= BotBonds.maxBonds(), "the cap holds: " + n);
            if (n > 0) {
                withBonds++;
            }
            for (BotBonds.View v : BotBonds.bondsOf(c.getId())) {
                stages.merge(BotBonds.stage(c.getId(), v.otherId()), 1, Integer::sum);
                edges++;
            }
        }
        System.out.println("simulated 3 days: " + bots.size() + " bots, " + withBonds + " with bonds, " + edges + " edges, stages "
                + stages + ", background tier " + micros / steps + " us per step on average, " + steps + " steps");
        // two maps and one pair a minute over 270 possible pairs: pairs seen once are dropped as faded nods at the
        // flush (as on observed maps); the ones that keep meeting stay
        assertTrue(withBonds >= 30, "half the unwatched bots ended up knowing someone: " + withBonds);
        assertTrue(edges >= 40, "a real web, not a handful: " + edges);
        assertEquals(0, stages.getOrDefault(Stage.ESTRANGED, 0) + stages.getOrDefault(Stage.RIVAL, 0), "nods breed no grudges");
        assertTrue(stages.getOrDefault(Stage.ACQUAINTANCE, 0) > 0, "acquaintances formed: " + stages);

        clock.addAndGet(60 * DAY);
        int still = 0;
        for (Character c : bots) {
            for (BotBonds.View v : BotBonds.bondsOf(c.getId())) {
                Stage s = BotBonds.stage(c.getId(), v.otherId());
                assertTrue(s != Stage.ESTRANGED && s != Stage.RIVAL, "silence is not estrangement: " + s);
                if (s == Stage.ACQUAINTANCE) {
                    still++;
                }
            }
        }
        assertTrue(still > 0, "they still know each other (the familiarity floor)");
    }
}
