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
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotDramaSystem.BeefKit;
import soloMapling.ArtificialPlayer.BotDramaSystem.DramaNews;
import soloMapling.ArtificialPlayer.BotGossip;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 S7 "Beef": rival bots' beef threads and friends' drama threads, grounded and name-guarded. */
class BotThreadsBeefTest {

    final Map<Integer, Character> online = new HashMap<>();
    final Map<Integer, Double> killer = new HashMap<>();

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, System::currentTimeMillis, id -> true);
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, System::currentTimeMillis,
                id -> new double[]{.25, .25}, (id, p) -> { }, online::get);
        BeefKit.usePullsForTest(id -> killer.getOrDefault(id, 0.1), id -> 0.25);
        DramaNews.useCircleForTest(id -> List.of());
        NameGuard.useForTest(name -> false, null);
    }

    @AfterEach
    void tearDown() {
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        BeefKit.usePullsForTest(null, null);
        DramaNews.useCircleForTest(null);
        NameGuard.useForTest(null, null);
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

    static Random fixed(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    void rivalsHaveBeefAndAKillerStartsItMoreOften() {
        Character a = chr(811, "Amy"), b = chr(812, "Bob");
        long now = System.currentTimeMillis();
        assertNull(BotThreads.beefOrDrama(a, b, now, fixed(0)), "no bond: the usual topics");
        BotBonds.stage(a.getId(), b.getId(), AffinityState.RIVAL);
        assertEquals(BotThreads.Topic.RIVALRY, BotThreads.beefOrDrama(a, b, now, fixed(0)));
        killer.put(a.getId(), 0.0);
        killer.put(b.getId(), 0.0);
        int calm = 0, killers = 0;
        for (int i = 0; i < 2000; i++) {
            calm += BotThreads.beefOrDrama(a, b, now, new Random(i)) == BotThreads.Topic.RIVALRY ? 1 : 0;
        }
        killer.put(a.getId(), 1.0);
        killer.put(b.getId(), 1.0);
        for (int i = 0; i < 2000; i++) {
            killers += BotThreads.beefOrDrama(a, b, now, new Random(i)) == BotThreads.Topic.RIVALRY ? 1 : 0;
        }
        assertTrue(killers > calm * 2, "killers " + killers + " vs calm " + calm);
    }

    @Test
    void aBeefThreadIsTrashTalkBetweenTheTwoAndTheGuardHasNothingToCatch() {
        Character a = chr(813, "Amy"), b = chr(814, "Bob");
        BotBonds.stage(a.getId(), b.getId(), AffinityState.RIVAL);
        BotGossip.Fact fact = BotThreads.ground(BotThreads.Topic.RIVALRY, a, b, List.of(), System.currentTimeMillis(),
                new Random(1));
        assertNotNull(fact);
        assertEquals("rivalry", fact.kind());
        List<String[]> lines = BotThreads.fallback(BotThreads.Topic.RIVALRY, a, b, fact, new Random(1));
        assertEquals(2, lines.size());
        assertEquals("Amy", lines.get(0)[0]);
        assertEquals("Bob", lines.get(1)[0]);
        for (String[] l : lines) {
            assertNull(ContentGuard.chatProblem(l[1]), l[1]);
        }
        Set<String> allowed = BotGossip.allowedNames(fact, a.getName(), b.getName());
        assertEquals(Set.of("Amy", "Bob"), allowed, "only the two speakers");
        assertEquals(lines.size(), BotThreads.guard(lines, allowed, 100000000).size(), "nothing to rewrite or drop");
        String prompt = BotThreads.threadPrompt(a, b, BotThreads.Topic.RIVALRY, fact.about(), "Henesys", fact);
        assertTrue(prompt.contains("They are rivals in the game") && prompt.contains(fact.about())
                && prompt.contains("name no other player") && prompt.contains("insults only about the game"), prompt);
        assertEquals(BotThreads.Topic.RIVALRY, BotThreads.Topic.parse("rival"));
        assertEquals(BotThreads.Topic.GUILD_BEEF, BotThreads.Topic.parse("beef"), "the old cv-5 word still means guild beef");
    }

    @Test
    void aFriendsRageQuitBecomesADramaThreadNamingOnlyThatFriend() {
        Character a = chr(815, "Amy"), b = chr(816, "Bob"), c = chr(817, "Cid");
        DramaNews.useCircleForTest(id -> id == a.getId() ? List.of(c.getId()) : List.of());
        long now = System.currentTimeMillis();
        DramaNews.add(DramaNews.Kind.RAGE_QUIT, c.getId(), "Cid", "maple sword", now);
        assertEquals(BotThreads.Topic.FRIEND_DRAMA, BotThreads.beefOrDrama(a, b, now, fixed(0)));
        assertNull(BotThreads.beefOrDrama(b, a, now, fixed(0)), "Bob's circle has no news");
        BotGossip.Fact fact = BotThreads.ground(BotThreads.Topic.FRIEND_DRAMA, a, b, List.of(), now, new Random(1));
        assertEquals(List.of("Cid"), fact.names());
        List<String[]> lines = BotThreads.fallback(BotThreads.Topic.FRIEND_DRAMA, a, b, fact, new Random(1));
        assertTrue(lines.get(0)[1].contains("Cid"), lines.get(0)[1]);
    }
}
