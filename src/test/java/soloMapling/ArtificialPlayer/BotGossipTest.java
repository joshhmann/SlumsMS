package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 S4: what bots gossip about is true, and it spreads along bonds. */
class BotGossipTest {

    static final int B = BotStore.BOT_ID_BASE;
    static final int PLAYER = 4242;
    static final Map<Integer, String> NAMES = Map.of(B + 1, "Nayrb", B + 2, "Kimchi", B + 3, "Bob", B + 4, "Tofu",
            PLAYER, "Joshh");
    /** Kimchi (2) and Nayrb (1) are a crew; Bob (3) is a stranger. */
    static final Map<Integer, long[]> GROUPS = new HashMap<>();

    static {
        GROUPS.put(B + 1, new long[]{Reputation.CREW | (B + 1)});
        GROUPS.put(B + 2, new long[]{Reputation.CREW | (B + 1)});
    }

    private final Map<Integer, SocialProfile> points = new HashMap<>();
    private boolean gateOpen;

    @BeforeEach
    void seams() {
        Reputation.useForTest(id -> GROUPS.getOrDefault(id, new long[]{Reputation.SOLO | (id & 0xffffffffL)}), null,
                id -> false);
        BotGossip.useForTest(id -> points.getOrDefault(id, new SocialProfile(0, 0)),
                id -> id == B + 2 ? List.of(B + 1) : id == B + 1 ? List.of(B + 2) : List.of(), NAMES::get,
                (bot, player) -> gateOpen);
        BotWorldGossip.resetForTest();
        BotMemory.useConnectionSourceForTest(() -> {
            throw new java.sql.SQLException("no notes in this test");
        });
    }

    @AfterEach
    void restore() {
        BotMemory.useConnectionSourceForTest(null);
        Reputation.useForTest(null, null, null);
        BotGossip.useForTest(null, null, null, null);
        BotWorldGossip.resetForTest();
    }

    static Character chr(int id, String name) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName(name);
        c.setLevel(35);
        return c;
    }

    @Test
    void aKsOnACrewMateIsToldWithTheRealNames() {
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.KS, System.currentTimeMillis());
        BotGossip.Fact f = BotGossip.factFor(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), List.of(), System.currentTimeMillis(),
                new Random(1));
        assertEquals("ks", f.kind());
        assertEquals("Joshh ksed Nayrb", f.about());
        assertEquals(List.of("Joshh", "Nayrb"), f.names());
        assertTrue(f.opener().contains("Joshh") && f.opener().contains("Nayrb"), f.opener());
        assertTrue(f.negative());
        assertEquals(PLAYER, f.subjectId());
    }

    @Test
    void theVictimTellsItAsMe() {
        Reputation.witnessed(B + 2, PLAYER, Reputation.Mark.SCAM, System.currentTimeMillis());
        BotGossip.Fact f = BotGossip.factFor(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), List.of(), System.currentTimeMillis(),
                new Random(1));
        assertEquals("Joshh scammed Kimchi on a trade", f.about());
        assertTrue(f.opener().contains("me"), f.opener());
        assertEquals(List.of("Joshh"), f.names());
    }

    @Test
    void badGossipInFrontOfThePlayerNeedsTheGate() {
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.KS, System.currentTimeMillis());
        List<Character> here = List.of(chr(PLAYER, "Joshh"));
        gateOpen = false;
        BotGossip.Fact f = BotGossip.factFor(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), here, System.currentTimeMillis(),
                new Random(1));
        assertFalse(f.person(), "not to their face: " + f);
        gateOpen = true; // they started it with the crew: SocialGate GOSSIP says yes
        assertEquals("ks", BotGossip.factFor(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), here, System.currentTimeMillis(),
                new Random(1)).kind());
        assertNotNull(BotGossip.factAbout(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), here, PLAYER));
    }

    @Test
    void nothingTrueToTellIsATopicWithoutPeople() {
        BotGossip.Fact f = BotGossip.factFor(chr(B + 3, "Bob"), chr(B + 4, "Tofu"), List.of(), System.currentTimeMillis(),
                new Random(1));
        assertFalse(f.person());
        assertTrue(List.of("maps and hidden spots worth exploring", "leveling and the best drops", "prices in the FM",
                "the new patch").contains(f.about()), f.about());
    }

    @Test
    void bondDramaIsTold() {
        long now = System.currentTimeMillis();
        Reputation.onBondState(B + 1, B + 4, AffinityState.RIVAL, BondEvent.KS, now);
        BotGossip.Fact f = BotGossip.factFor(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), List.of(), now, new Random(1));
        assertEquals("beef", f.kind());
        assertEquals("Nayrb has beef with Tofu", f.about());
        assertEquals(List.of("Nayrb", "Tofu"), f.names());
        Reputation.resetForTest();
        Reputation.onBondState(B + 2, B + 4, AffinityState.RIVAL, BondEvent.KS, now);
        assertEquals("Tofu keeps ksing me smh", BotGossip.factFor(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), List.of(), now,
                new Random(1)).opener());
    }

    @Test
    void worldNewsIsTold() {
        long now = System.currentTimeMillis();
        BotWorldGossip.feedForTest().add(BotWorldGossip.newsForTest(BotWorldGossip.Kind.BOSS, "Joshh", PLAYER, "Zakum",
                null, now));
        BotGossip.Fact f = BotGossip.factFor(chr(B + 3, "Bob"), chr(B + 4, "Tofu"), List.of(), now, new Random(1));
        assertEquals("news", f.kind());
        assertEquals("Joshh killed Zakum", f.about());
        assertTrue(f.opener().contains("Joshh") && f.opener().contains("Zakum"), f.opener());
    }

    @Test
    void killersTellBadNewsAchieversBossKills() {
        long now = System.currentTimeMillis();
        BotWorldGossip.feedForTest().add(BotWorldGossip.newsForTest(BotWorldGossip.Kind.BOSS, "Joshh", PLAYER, "Zakum",
                null, now));
        Reputation.witnessed(B + 2, 5151, Reputation.Mark.KS, now);
        Map<Integer, String> names = new HashMap<>(NAMES);
        names.put(5151, "Ksr");
        BotGossip.useForTest(id -> points.getOrDefault(id, new SocialProfile(0, 0)), id -> List.of(), names::get,
                (bot, player) -> true);
        points.put(B + 2, new SocialProfile(-0.9, 0.9)); // a deep Killer
        int ks = 0;
        for (int i = 0; i < 300; i++) {
            ks += "ks".equals(BotGossip.factFor(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), List.of(), now, new Random(i)).kind()) ? 1 : 0;
        }
        points.put(B + 2, new SocialProfile(0.9, 0.9)); // a deep Achiever
        int ksAchiever = 0;
        for (int i = 0; i < 300; i++) {
            ksAchiever += "ks".equals(BotGossip.factFor(chr(B + 2, "Kimchi"), chr(B + 3, "Bob"), List.of(), now,
                    new Random(i)).kind()) ? 1 : 0;
        }
        assertTrue(ks > ksAchiever + 60, "killer " + ks + " vs achiever " + ksAchiever);
    }

    @Test
    void socializersPassThingsOnMore() {
        assertEquals(0.3, BotGossip.passChance(0.25), 1e-9);
        assertTrue(BotGossip.passChance(0.7) > 0.5);
        assertTrue(BotGossip.passChance(0.02) < 0.2);
    }

    @Test
    void reputationSpreadsAlongABondToAFriendOnAnotherMap() {
        long now = System.currentTimeMillis();
        Reputation.witnessed(B + 2, PLAYER, Reputation.Mark.SCAM, now);
        // Bob (3) is Kimchi's only friend here, in another town; Tofu (4) stands next to Kimchi
        BotGossip.useForTest(id -> new SocialProfile(-0.9, -0.9), id -> id == B + 2 ? List.of(B + 3) : List.of(),
                NAMES::get, (bot, player) -> true);
        Random always = new Random() {
            @Override
            public double nextDouble() {
                return 0;
            }
        };
        BotGossip.step(Map.of(100000000, List.of(chr(B + 2, "Kimchi"), chr(B + 4, "Tofu")),
                101000000, List.of(chr(B + 3, "Bob"))), now, always);
        assertEquals(1.0, Reputation.known(B + 3, PLAYER).count(Reputation.Mark.SCAM), 1e-6, "the friend heard it");
        assertFalse(Reputation.known(B + 4, PLAYER).any(), "the bot standing next to it did not: bonds, not maps");
    }

    @Test
    void trainingTalkNamesNobodyMadeUp() {
        BotGossip.Fact f = BotGossip.trainingFor(chr(B + 3, "Bob"), chr(B + 4, "Tofu"), new Random(3));
        assertFalse(f.person());
        assertTrue(f.about().contains("level"), f.about());
    }
}
