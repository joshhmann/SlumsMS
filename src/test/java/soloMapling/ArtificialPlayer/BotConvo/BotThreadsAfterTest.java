package soloMapling.ArtificialPlayer.BotConvo;

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
import soloMapling.ArtificialPlayer.BotDramaSystem.BeefKit;
import soloMapling.ArtificialPlayer.BotDramaSystem.BotBotRivalry;
import soloMapling.ArtificialPlayer.BotGossip;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 social trace: what a bot thread leaves behind - "we talked", a kept story, an escalation roll. */
class BotThreadsAfterTest {

    final Map<Integer, Character> online = new HashMap<>();
    final List<Integer> talked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, System::currentTimeMillis, id -> true);
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, System::currentTimeMillis,
                id -> new double[]{.25, .25}, (id, p) -> { }, online::get);
        BeefKit.usePullsForTest(id -> 1.0, id -> 0.25);
        BotGossip.clearStoriesForTest(id -> false);
        BotThreads.resetForTest();
        BotThreads.lifeTalked = (id, now) -> talked.add(id);
    }

    @AfterEach
    void tearDown() {
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        BeefKit.usePullsForTest(null, null);
        BotGossip.clearStoriesForTest(null);
        BotThreads.resetForTest();
    }

    Character chr(int slot, String name) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BotStore.BOT_ID_BASE + slot);
        c.setName(name);
        c.setLevel(40);
        online.put(c.getId(), c);
        return c;
    }

    static BotThreads.Live live(Character a, Character b, BotThreads.Topic topic, BotGossip.Fact fact, boolean said) {
        BotThreads.Live l = new BotThreads.Live(1, 100000000, List.of(a.getId(), b.getId()), List.of(a.getName(),
                b.getName()), topic, fact == null ? null : fact.about(), fact, 0, a.getId());
        l.lastLineAt = said ? 1 : 0;
        return l;
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
    void everyThreadThatSaidSomethingIsATalkForBothOncePerPairCooldown() {
        Character a = chr(901, "Amy"), b = chr(902, "Bob");
        long now = 1_793_000_000_000L;
        BotThreads.finished(live(a, b, BotThreads.Topic.TRADE, null, false), a, b, now, new Random(1));
        assertTrue(talked.isEmpty(), "nothing said, nothing remembered");
        BotThreads.finished(live(a, b, BotThreads.Topic.LFG, null, true), a, b, now, new Random(1));
        assertEquals(List.of(a.getId(), b.getId()), talked);
        BotThreads.finished(live(b, a, BotThreads.Topic.TRAINING, null, true), b, a, now + 60_000, new Random(1));
        assertEquals(2, talked.size(), "the pair's talk cooldown holds (either order)");
        BotThreads.finished(live(a, b, BotThreads.Topic.GUILD_BEEF, null, true), a, b,
                now + BotThreads.TALK_PAIR_GAP_MS + 1, new Random(1));
        assertEquals(4, talked.size(), "after the gap they talked again");
    }

    @Test
    void aGossipThreadsListenerKeepsTheStory() {
        Character a = chr(903, "Amy"), b = chr(904, "Bob");
        BotGossip.Fact fact = new BotGossip.Fact("beef", "Cid has beef with Dee", "Cid and Dee have beef lol",
                List.of("Cid", "Dee"), BotStore.BOT_ID_BASE + 905, true);
        long now = System.currentTimeMillis();
        BotThreads.finished(live(a, b, BotThreads.Topic.GOSSIP, fact, true), a, b, now, new Random(1));
        List<BotGossip.Heard> heard = BotGossip.heard(b.getId(), now);
        assertEquals(1, heard.size());
        assertEquals(fact, heard.get(0).fact());
        assertEquals(a.getId(), heard.get(0).fromId());
        assertTrue(BotGossip.heard(a.getId(), now).isEmpty(), "the teller keeps nothing new");

        Character c = chr(906, "Eve"), d = chr(907, "Fay");
        BotThreads.finished(live(c, d, BotThreads.Topic.GOSSIP, fact, false), c, d, now, new Random(1));
        assertTrue(BotGossip.heard(d.getId(), now).isEmpty(), "a thread that said nothing passes nothing on");
    }

    @Test
    void aRivalryThreadRollsTheEscalationOnceAndOnlyBetweenRivals() {
        Character a = chr(908, "Amy"), b = chr(909, "Bob");
        long now = System.currentTimeMillis();
        List<String> escalated = new ArrayList<>();
        BotThreads.escalator = (s, t, done) -> escalated.add(s + "->" + t);
        BotThreads.finished(live(a, b, BotThreads.Topic.RIVALRY, null, true), a, b, now, fixed(0.99));
        assertTrue(BotBotRivalry.decideEscalation(a, b, now, fixed(0)).why().contains("no beef"), "not rivals");

        BotBonds.stage(a.getId(), b.getId(), AffinityState.RIVAL);
        BotThreads.finished(live(a, b, BotThreads.Topic.RIVALRY, null, true), a, b, now, fixed(0.99));
        BotBotRivalry.Escalation e = BotBotRivalry.decideEscalation(a, b, now, fixed(0.99));
        assertFalse(e.go());
        assertTrue(e.why().startsWith("the roll said no"), "the roll said no, no cooldown taken: " + e.why());

        BotThreads.finished(live(a, b, BotThreads.Topic.RIVALRY, null, true), a, b, now, fixed(0));
        e = BotBotRivalry.decideEscalation(a, b, now, fixed(0));
        assertFalse(e.go());
        assertTrue(e.why().startsWith("pair cooldown"), "the thread's yes took the pair's cooldown: " + e.why());
    }
}
