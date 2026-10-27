package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 social trace: a beef thread between rivals may escalate - rarely, once per pair, capped, bots only. */
class BotBotEscalationTest {

    static final int A = BotStore.BOT_ID_BASE + 731, B = BotStore.BOT_ID_BASE + 732, C = BotStore.BOT_ID_BASE + 733;
    static final int D = BotStore.BOT_ID_BASE + 734;

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final Map<Integer, Double> killer = new HashMap<>();
    final Map<Integer, Character> online = new HashMap<>();

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get, id -> new double[]{.25, .25},
                (id, p) -> { }, online::get);
        BeefKit.useForTest(clock::get, id -> killer.getOrDefault(id, 0.1), id -> 0.25, online::get);
        DramaKit.resetForTest();
        VillainArc.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        BeefKit.useForTest(null, null, null, null);
        DramaKit.resetForTest();
        VillainArc.resetForTest();
    }

    Character bot(int id, String name) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName(name);
        c.setLevel(40);
        online.put(id, c);
        return c;
    }

    static Random fixed(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    void theChanceIsSmallAndAKillerOrANemesisRaisesItWithinACap() {
        bot(A, "Amy");
        bot(B, "Bob");
        BotBonds.stage(A, B, AffinityState.RIVAL);
        killer.put(A, 0.0);
        double calm = BotBotRivalry.escalateChance(A, B, clock.get());
        killer.put(A, 1.0);
        double killerChance = BotBotRivalry.escalateChance(A, B, clock.get());
        assertEquals(0.08, calm, 1e-9);
        assertTrue(killerChance > calm && killerChance <= 0.35, "killer " + killerChance);
        BotBonds.stage(A, B, AffinityState.NEMESIS);
        assertTrue(BotBotRivalry.escalateChance(A, B, clock.get()) >= killerChance);
        assertTrue(BotBotRivalry.escalateChance(A, B, clock.get()) <= 0.35);
    }

    @Test
    void onlyRivalsEscalateOncePerPairAndAFewAnHourWorldWide() {
        Character a = bot(A, "Amy"), b = bot(B, "Bob"), c = bot(C, "Cid");
        long now = clock.get();
        assertFalse(BotBotRivalry.decideEscalation(a, b, now, fixed(0)).go(), "no beef, no escalation");
        BotBonds.stage(A, B, AffinityState.RIVAL);
        BotBotRivalry.Escalation no = BotBotRivalry.decideEscalation(a, b, now, fixed(0.99));
        assertFalse(no.go());
        assertTrue(no.why().startsWith("the roll said no"), no.why());
        BotBotRivalry.Escalation yes = BotBotRivalry.decideEscalation(a, b, now, fixed(0));
        assertTrue(yes.go(), yes.why());
        assertTrue(yes.delayMs() >= BotBotRivalry.ESCALATE_DELAY_MIN_MS
                && yes.delayMs() <= BotBotRivalry.ESCALATE_DELAY_MAX_MS, "a few minutes later: " + yes.delayMs());
        assertTrue(BotBotRivalry.decideEscalation(b, a, now, fixed(0)).why().startsWith("pair cooldown"),
                "either order is the same pair");
        assertTrue(BotBotRivalry.decideEscalation(a, b, now + BotBotRivalry.ESCALATE_PAIR_GAP_MS, fixed(0)).go(),
                "after the pair gap again");

        // world cap: this hour holds one (Amy -> Bob again); two more pairs fill it, the next is refused
        long later = now + BotBotRivalry.ESCALATE_PAIR_GAP_MS + 1;
        BotBonds.stage(A, C, AffinityState.RIVAL);
        assertTrue(BotBotRivalry.decideEscalation(a, c, later, fixed(0)).go());
        BotBonds.stage(B, C, AffinityState.RIVAL);
        assertTrue(BotBotRivalry.decideEscalation(b, c, later, fixed(0)).go());
        Character d = bot(D, "Dee");
        BotBonds.stage(D, C, AffinityState.RIVAL);
        BotBotRivalry.Escalation capped = BotBotRivalry.decideEscalation(d, c, later, fixed(0));
        assertFalse(capped.go());
        assertTrue(capped.why().startsWith("world cap"), capped.why());
    }

    @Test
    void aGoneOrMaplessRivalIsNoEscalationAndTheCallerHearsWhy() {
        Character a = bot(A, "Amy"), b = bot(B, "Bob");
        BotBonds.stage(A, B, AffinityState.RIVAL);
        assertEquals("one of the two is gone", BotBotRivalry.escalateBlocked(null, b));
        assertEquals("one of the two is gone", BotBotRivalry.escalateBlocked(a, b), "no map: not in the world");
        List<String> heard = new ArrayList<>();
        BotBotRivalry.escalate(A, B, (ok, why) -> heard.add(ok + " " + why));
        assertEquals(List.of("false one of the two is gone"), heard, "not online as live bots in a unit test");
    }

    @Test
    void theEscalationLinesAreSafeAndNameOnlyTheOtherBot() {
        for (String node : List.of("BeefOpen", "BeefOpenNemesis", "BeefReply")) {
            for (int i = 0; i < 20; i++) {
                String line = DramaKit.pick(node, Map.of("OTHER", "Bob", "COUNT", "2"));
                assertNotNull(line, node);
                assertNull(ContentGuard.chatProblem(line), line);
                assertFalse(line.contains("{"), line);
            }
        }
    }
}
