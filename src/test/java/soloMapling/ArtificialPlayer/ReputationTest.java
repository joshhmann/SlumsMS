package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 S4: reputation as per-group aggregates, spread along bonds. */
class ReputationTest {

    static final int B = BotStore.BOT_ID_BASE;
    /** Bots 1 and 2 are a crew; 3 and 4 a crew; 5 is alone; 1 and 5 share guild 7. */
    static final Map<Integer, long[]> GROUPS = new HashMap<>();
    static final int PLAYER = 4242, OPTED_OUT = 4343;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    static {
        long crewA = Reputation.CREW | (B + 1), crewB = Reputation.CREW | (B + 3), guild = Reputation.GUILD | 7;
        GROUPS.put(B + 1, new long[]{guild, crewA});
        GROUPS.put(B + 2, new long[]{crewA});
        GROUPS.put(B + 3, new long[]{crewB});
        GROUPS.put(B + 4, new long[]{crewB});
        GROUPS.put(B + 5, new long[]{guild, Reputation.SOLO | (B + 5)});
    }

    @BeforeEach
    void seams() {
        Reputation.useForTest(id -> GROUPS.getOrDefault(id, new long[]{Reputation.SOLO | (id & 0xffffffffL)}), clock::get,
                id -> id == OPTED_OUT);
    }

    @AfterEach
    void restore() {
        Reputation.useForTest(null, null, null);
    }

    @Test
    void aKsCountsForEveryGroupOfTheBotThatSawIt() {
        Reputation.onAffinity(B + 1, PLAYER, AffinityEvent.KS, clock.get());
        assertEquals(1.0, Reputation.known(B + 2, PLAYER).count(Reputation.Mark.KS), 1e-9, "crew mate knows");
        assertEquals(1.0, Reputation.known(B + 5, PLAYER).count(Reputation.Mark.KS), 1e-9, "guild mate knows");
        assertFalse(Reputation.known(B + 3, PLAYER).any(), "another crew does not");
        assertEquals(B + 1, Reputation.known(B + 2, PLAYER).victim(Reputation.Mark.KS));
        assertEquals(0, Reputation.known(B + 2, PLAYER).hops());
        assertEquals(2, Reputation.groupsOf(PLAYER), "aggregates per group, not notes per listener");
        assertEquals(List.of(PLAYER), Reputation.subjectsKnownBy(B + 2));
    }

    @Test
    void theMarksComeFromScamsKsGiftsFavorsAndPqsOnly() {
        assertEquals(Reputation.Mark.SCAM, Reputation.Mark.of(AffinityEvent.SCAM));
        assertEquals(Reputation.Mark.FAVOR, Reputation.Mark.of(AffinityEvent.FAVOR));
        assertNull(Reputation.Mark.of(AffinityEvent.HEARSAY_BAD), "gossip never counts as seeing it");
        assertNull(Reputation.Mark.of(AffinityEvent.BUFF));
        assertEquals(Reputation.Mark.KS, Reputation.Mark.of(BondEvent.KS));
        assertEquals(Reputation.Mark.PQ, Reputation.Mark.of(BondEvent.PQ));
        assertNull(Reputation.Mark.of(BondEvent.NEAR));
        assertEquals(Reputation.Mark.PQ, Reputation.Mark.of(BotMemory.Kind.BOSS));
        Reputation.onAffinity(B + 1, PLAYER, AffinityEvent.BUFF, clock.get());
        assertFalse(Reputation.known(B + 1, PLAYER).any());
    }

    @Test
    void gossipMovesOneStepAlongABondNeverPastTheTellerOrTwoHops() {
        long now = clock.get();
        for (int i = 0; i < 3; i++) {
            Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.SCAM, now);
        }
        assertEquals(3.0, Reputation.known(B + 2, PLAYER).count(Reputation.Mark.SCAM), 1e-6);
        Random rng = new Random(1);
        assertEquals(PLAYER, Reputation.spread(B + 2, B + 3, 1, now, rng), "crew A tells crew B");
        assertEquals(1.0, Reputation.known(B + 4, PLAYER).count(Reputation.Mark.SCAM), 1e-6, "one step, the whole crew");
        assertEquals(1, Reputation.known(B + 4, PLAYER).hops());
        assertEquals(B + 1, Reputation.known(B + 4, PLAYER).victim(Reputation.Mark.SCAM), "who it was done to travels");
        Reputation.spread(B + 2, B + 3, 1, now, rng);
        Reputation.spread(B + 2, B + 3, 1, now, rng);
        Reputation.spread(B + 2, B + 3, 1, now, rng);
        assertEquals(3.0, Reputation.known(B + 3, PLAYER).count(Reputation.Mark.SCAM), 1e-6, "never past the teller");
        assertEquals(PLAYER, Reputation.spread(B + 3, 9_999 + B, 1, now, rng), "hop 1 still travels");
        assertEquals(2, Reputation.known(9_999 + B, PLAYER).hops());
        assertEquals(0, Reputation.spread(9_999 + B, 8_888 + B, 1, now, rng), "hop 2 goes no further");
        assertEquals(0, Reputation.spread(B + 1, B + 2, 1, now, rng), "a crew mate knows already");
    }

    @Test
    void killersPreferBadNews() {
        long now = clock.get();
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.KS, now);
        Reputation.witnessed(B + 1, 5151, Reputation.Mark.GIFT, now);
        int bad = 0;
        for (int i = 0; i < 400; i++) {
            Reputation.resetForTest();
            Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.KS, now);
            Reputation.witnessed(B + 1, 5151, Reputation.Mark.GIFT, now);
            bad += Reputation.spread(B + 1, B + 3, 3, now, new Random(i)) == PLAYER ? 1 : 0;
        }
        assertTrue(bad > 260, "a grudge bias of 3 passes the KS about 3 times in 4: " + bad);
    }

    @Test
    void grudgesFadeInTwoWeeksKindnessInSix() {
        long now = clock.get();
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.KS, now);
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.GIFT, now);
        clock.addAndGet(Reputation.GRUDGE_HALF_LIFE_MS);
        Reputation.Known k = Reputation.known(B + 2, PLAYER);
        assertEquals(0.5, k.count(Reputation.Mark.KS), 1e-6);
        assertEquals(Math.pow(0.5, 1 / 3.0), k.count(Reputation.Mark.GIFT), 1e-6);
        clock.addAndGet(Reputation.GRUDGE_HALF_LIFE_MS * 3);
        assertEquals(0, Reputation.known(B + 2, PLAYER).count(Reputation.Mark.KS), 1e-9, "faint counts are gone");
    }

    @Test
    void wariOfAKnownKserOrScammer() {
        long now = clock.get();
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.KS, now);
        assertFalse(Reputation.wary(B + 2, PLAYER), "one KS is not a reputation yet");
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.SCAM, now);
        assertTrue(Reputation.wary(B + 2, PLAYER));
        assertFalse(Reputation.wary(B + 3, PLAYER), "a crew that never heard of it");
        for (int i = 0; i < 4; i++) {
            Reputation.witnessed(B + 2, PLAYER, Reputation.Mark.GIFT, now);
        }
        assertFalse(Reputation.wary(B + 2, PLAYER), "kindness outweighs it");
    }

    @Test
    void botDramaOffForgetsAndNeverCounts() {
        long now = clock.get();
        Reputation.witnessed(B + 1, OPTED_OUT, Reputation.Mark.KS, now);
        assertFalse(Reputation.known(B + 2, OPTED_OUT).any());
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.KS, now);
        assertEquals(2, Reputation.forget(PLAYER));
        assertFalse(Reputation.known(B + 2, PLAYER).any());
        assertTrue(Reputation.subjectsKnownBy(B + 2).isEmpty());
    }

    @Test
    void bondsCountBotsAndBondDramaIsKeptForAFewHours() {
        long now = clock.get();
        Reputation.onBond(B + 3, B + 1, BondEvent.KS, now);
        assertEquals(1.0, Reputation.known(B + 4, B + 1).count(Reputation.Mark.KS), 1e-9, "a bot's KS of a bot counts");
        Reputation.onBondState(B + 3, B + 1, AffinityState.RIVAL, BondEvent.KS, now);
        Reputation.onBondState(B + 3, B + 2, AffinityState.NEUTRAL, BondEvent.NEAR, now);
        List<Reputation.Drama> drama = Reputation.dramaAbout(Set.of(B + 3), now + 1000);
        assertEquals(1, drama.size());
        assertEquals(AffinityState.RIVAL, drama.get(0).state());
        assertTrue(Reputation.dramaAbout(Set.of(B + 3), now + Reputation.DRAMA_TTL_MS + 1).isEmpty());
    }

    @Test
    void boundedSubjectsAndGroups() {
        long now = clock.get();
        for (int s = 0; s < Reputation.MAX_SUBJECTS + 50; s++) {
            Reputation.witnessed(B + 1, 10_000 + s, Reputation.Mark.PQ, now);
        }
        assertEquals(Reputation.MAX_SUBJECTS, Reputation.subjectCount());
        assertTrue(Reputation.subjectsKnownBy(B + 1).size() <= 2 * Reputation.MAX_SUBJECTS_PER_GROUP);
        for (int g = 0; g < Reputation.MAX_GROUPS_PER_SUBJECT + 10; g++) {
            Reputation.witnessed(B + 100 + g, PLAYER, Reputation.Mark.KS, now);
        }
        assertEquals(Reputation.MAX_GROUPS_PER_SUBJECT, Reputation.groupsOf(PLAYER));
    }
}
