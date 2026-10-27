package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 social trace: a GOSSIP thread's listener keeps the story, word for word, and passes it on to a third bot. */
class BotGossipStoriesTest {

    static final int B = BotStore.BOT_ID_BASE;
    static final int PLAYER = 4242;
    static final int SHY = 4343;
    static final Map<Integer, String> NAMES = Map.of(B + 1, "Nayrb", B + 2, "Kimchi", B + 3, "Bob", B + 4, "Tofu",
            B + 5, "Mochi", PLAYER, "Joshh", SHY, "Shyguy");
    static final long NOW = 1_793_000_000_000L;

    private final Set<Integer> optedOut = new HashSet<>();

    @BeforeEach
    void seams() {
        Reputation.useForTest(id -> new long[]{Reputation.SOLO | (id & 0xffffffffL)}, null, id -> false);
        BotGossip.useForTest(id -> new SocialProfile(0, 0), id -> List.of(), NAMES::get, (bot, player) -> false);
        BotGossip.clearStoriesForTest(optedOut::contains);
        BotWorldGossip.resetForTest();
    }

    @AfterEach
    void restore() {
        Reputation.useForTest(null, null, null);
        BotGossip.useForTest(null, null, null, null);
        BotGossip.clearStoriesForTest(null);
        BotWorldGossip.resetForTest();
    }

    static Character chr(int id) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName(NAMES.get(id));
        c.setLevel(35);
        return c;
    }

    static BotGossip.Fact ks() {
        return new BotGossip.Fact("ks", "Joshh ksed Nayrb", "did u hear Joshh ksed Nayrb", List.of("Joshh", "Nayrb"),
                PLAYER, true);
    }

    @Test
    void theListenerKeepsTheStoryWordForWordAndPassesItToAThirdBot() {
        BotGossip.Heard kept = BotGossip.keep(B + 3, B + 2, "Kimchi", ks(), NOW);
        assertNotNull(kept);
        assertEquals(ks(), kept.fact(), "the same fact: kind, about, opener, names, subject");
        assertEquals(1, kept.hops());
        assertEquals("Kimchi", kept.fromName());

        // Bob tells Tofu later: the same story, its people only, said as hearsay
        BotGossip.Fact told = BotGossip.factFor(chr(B + 3), chr(B + 4), List.of(), NOW + 60_000, new Random(1));
        assertEquals("Joshh ksed Nayrb", told.about());
        assertEquals(List.of("Joshh", "Nayrb"), told.names());
        assertEquals(PLAYER, told.subjectId());
        assertTrue(told.opener().contains("Joshh ksed Nayrb"), told.opener());
        assertFalse(told.opener().contains("me"), "never told as if it happened to the teller: " + told.opener());
        assertEquals(Set.of("Bob", "Tofu", "Joshh", "Nayrb"), BotGossip.allowedNames(told, "Bob", "Tofu"));

        // never back to the bot that told it
        BotGossip.Fact back = BotGossip.factFor(chr(B + 3), chr(B + 2), List.of(), NOW + 60_000, new Random(1));
        assertFalse("Joshh ksed Nayrb".equals(back.about()), "told back to its teller: " + back.about());
        // never to the bot it is about
        BotGossip.Fact toVictim = BotGossip.factFor(chr(B + 3), chr(B + 1), List.of(), NOW + 60_000, new Random(1));
        assertFalse("Joshh ksed Nayrb".equals(toVictim.about()), "told to the one it is about");
    }

    @Test
    void eachRetellingIsAHopDeeperAndStopsAtTheHopLimit() {
        assertEquals(1, BotGossip.keep(B + 3, B + 2, "Kimchi", ks(), NOW).hops());
        assertEquals(2, BotGossip.keep(B + 4, B + 3, "Bob", ks(), NOW).hops());
        assertNull(BotGossip.keep(B + 5, B + 4, "Tofu", ks(), NOW), "past BotMemory.MAX_HOPS");
        assertNull(BotGossip.keep(B + 3, B + 4, "Tofu", ks(), NOW), "it knew this one already");
    }

    @Test
    void smallTalkPrivateMattersAndOptedOutPlayersAreNotKept() {
        assertNull(BotGossip.keep(B + 3, B + 2, "Kimchi", new BotGossip.Fact("talk", "prices in the FM", null,
                List.of(), 0, false), NOW), "small talk");
        assertNull(BotGossip.keep(B + 3, B + 2, "Kimchi", new BotGossip.Fact("spot", "Nayrb trains at Ant Tunnel", null,
                List.of("Nayrb"), B + 1, false), NOW), "where a bot trains is no story");
        assertNull(BotGossip.keep(B + 3, B + 2, "Kimchi", new BotGossip.Fact("note", "Joshh said he hates his job",
                null, List.of("Joshh"), PLAYER, false), NOW), "never a chat note");
        optedOut.add(SHY);
        assertNull(BotGossip.keep(B + 3, B + 2, "Kimchi", new BotGossip.Fact("scam", "Shyguy scammed people on a trade",
                "heard Shyguy scams ppl on trades", List.of("Shyguy"), SHY, true), NOW), "@botdrama off");
        assertNull(BotGossip.keep(B + 3, B + 2, "Kimchi", new BotGossip.Fact("beef", "Bob has beef with Tofu", null,
                List.of("Bob", "Tofu"), B + 3, true), NOW), "a story about the listener itself");
        assertNotNull(BotGossip.keep(B + 3, B + 2, "Kimchi", new BotGossip.Fact("news", "Nayrb killed Zakum", "omg Nayrb killed zakum",
                List.of("Nayrb"), B + 1, false), NOW), "public news is fine");
    }

    @Test
    void aStoryAboutAPlayerWhoLaterOptsOutIsNeverRetold() {
        BotGossip.keep(B + 3, B + 2, "Kimchi", ks(), NOW);
        optedOut.add(PLAYER);
        BotGossip.Fact told = BotGossip.factFor(chr(B + 3), chr(B + 4), List.of(), NOW + 60_000, new Random(1));
        assertFalse("Joshh ksed Nayrb".equals(told.about()));
    }

    @Test
    void aBadStoryIsNotRetoldInFrontOfThePlayerItIsAbout() {
        BotGossip.keep(B + 3, B + 2, "Kimchi", ks(), NOW);
        Character joshh = Character.getDefault(new BotClient(0, 0));
        joshh.setID(PLAYER);
        joshh.setName("Joshh");
        BotGossip.Fact told = BotGossip.factFor(chr(B + 3), chr(B + 4), List.of(joshh), NOW + 60_000, new Random(1));
        assertFalse("Joshh ksed Nayrb".equals(told.about()), "the in-front gate is closed in this test");
    }

    @Test
    void storageIsBoundedPerBotAndStoriesFade() {
        for (int i = 0; i < BotGossip.STORIES_PER_BOT + 3; i++) {
            BotGossip.keep(B + 3, B + 2, "Kimchi", new BotGossip.Fact("news", "Nayrb got item " + i, "x", List.of("Nayrb"),
                    B + 1, false), NOW + i);
        }
        List<BotGossip.Heard> mine = BotGossip.heard(B + 3, NOW + 10);
        assertEquals(BotGossip.STORIES_PER_BOT, mine.size());
        assertEquals("Nayrb got item " + (BotGossip.STORIES_PER_BOT + 2), mine.getLast().fact().about(), "the newest stay");
        assertTrue(BotGossip.heard(B + 3, NOW + BotGossip.STORY_TTL_MS + 100).isEmpty(), "gone after the TTL");
    }
}
