package soloMapling.ArtificialPlayer.BotCoupleSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotCouplesTest {

    static final int A = BotStore.BOT_ID_BASE + 11;
    static final int B = BotStore.BOT_ID_BASE + 12;
    static final int C = BotStore.BOT_ID_BASE + 13;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final CoupleStore.Memory store = new CoupleStore.Memory();

    @BeforeEach
    void setUp() {
        BotCouples.useForTest(store, id -> null); // nobody online: no behaviour, only the bookkeeping
        BotWeddings.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotCouples.useForTest(null, null);
    }

    private static BotCouples.Candidate cand(int id, int gender, int level, int ch, int map) {
        return new BotCouples.Candidate(id, gender, level, ch, map);
    }

    @Test
    void anyTwoBotsOfSimilarLevelPair() {
        assertTrue(BotCouples.compatible(cand(A, 0, 40, 1, 1), cand(B, 1, 55, 1, 1)));
        assertTrue(BotCouples.compatible(cand(A, 0, 40, 1, 1), cand(B, 0, 40, 1, 1)), "two men");
        assertTrue(BotCouples.compatible(cand(A, 1, 40, 1, 1), cand(B, 1, 45, 1, 1)), "two women");
        assertFalse(BotCouples.compatible(cand(A, 0, 20, 1, 1), cand(B, 1, 45, 1, 1)), "more than 20 levels apart");
        assertFalse(BotCouples.compatible(cand(A, 0, 12, 1, 1), cand(B, 1, 14, 1, 1)), "too young");
    }

    @Test
    void pairsFormOnlyOnTheSameMapAndChannelAndNeverTwice() {
        List<BotCouples.Candidate> online = List.of(cand(A, 0, 40, 1, 100), cand(B, 1, 40, 2, 100),
                cand(C, 1, 40, 1, 200));
        assertNull(BotCouples.pickPair(online, Set.of(), new Random(1)), "nobody shares a map and channel");
        List<BotCouples.Candidate> together = List.of(cand(A, 0, 40, 1, 100), cand(B, 1, 41, 1, 100));
        BotCouples.Candidate[] pair = BotCouples.pickPair(together, Set.of(), new Random(1));
        assertNotNull(pair);
        assertNull(BotCouples.pickPair(together, Set.of(A), new Random(1)), "A is taken");
    }

    @Test
    void aBotIsInOneCoupleAtMostAndStagedCouplesAreNotStored() {
        long now = 1_000_000L;
        BotCouples.Couple c = BotCouples.form(A, B, false, now);
        assertNotNull(c);
        assertEquals(1, store.rows.size());
        assertEquals(B, BotCouples.partnerOf(A));
        assertEquals(A, BotCouples.partnerOf(B));
        assertNull(BotCouples.form(A, C, false, now), "A is taken");
        assertNull(BotCouples.form(C, C, true, now));
        BotCouples.Couple staged = BotCouples.form(C, BotStore.BOT_ID_BASE + 14, true, now);
        assertNotNull(staged);
        assertEquals(1, store.rows.size(), "staged couples live in memory only");
        assertTrue(BotCouples.breakUp(c.id()));
        assertEquals(0, store.rows.size());
        assertEquals(-1, BotCouples.partnerOf(A));
    }

    @Test
    void couplesComeBackFromTheStore() {
        BotCouples.form(A, B, false, 5L);
        BotCouples.useForTest(store, id -> null); // a restart: memory gone, the table stays
        assertEquals(-1, BotCouples.partnerOf(A), "lookups never wait on the database");
        BotCouples.step(0, new Random(1)); // the first minute step reads the table
        assertEquals(B, BotCouples.partnerOf(A));
    }

    @Test
    void readyToMarryAfterDatingLongEnoughAndMarriageIsStored() {
        long now = 10 * DAY;
        BotCouples.Couple c = BotCouples.form(A, B, false, now);
        assertFalse(BotCouples.readyToMarry(c, now + DAY));
        assertTrue(BotCouples.readyToMarry(c, now + 2 * DAY));
        BotCouples.Couple m = BotCouples.marry(c, now + 2 * DAY);
        assertEquals(BotCouples.Stage.MARRIED, store.rows.get(c.id()).stage());
        assertFalse(BotCouples.readyToMarry(m, now + 9 * DAY), "married once");
        assertEquals(BotCouples.LOVESEAT, BotCouples.coupleChair(m, null, null), "a married couple owns the loveseat");
    }

    @Test
    void loveNotesEveryFewDaysAndMatchingHatsSomeDays() {
        BotCouples.Couple c = new BotCouples.Couple(7, 1, 2, BotCouples.Stage.DATING, 0, 0, 0);
        assertFalse(BotCouples.noteDue(c, DAY));
        assertTrue(BotCouples.noteDue(c, 3 * DAY));
        assertFalse(BotCouples.noteDue(c.noted(3 * DAY), 4 * DAY));
        int matching = 0;
        for (int d = 0; d < 400; d++) {
            if (BotCouples.matchingDay(c, d * DAY + 1000)) {
                matching++;
            }
            assertEquals(BotCouples.matchingDay(c, d * DAY + 1000), BotCouples.matchingDay(c, d * DAY + DAY - 1000),
                    "a whole day either way");
        }
        assertTrue(matching > 50 && matching < 160, "about one day in four: " + matching);
    }

    @Test
    void ringsAreWeddingRings() {
        Set<Integer> rings = new HashSet<>();
        for (long id = 1; id < 20; id++) {
            rings.add(BotCouples.ringOf(new BotCouples.Couple(id, 1, 2, BotCouples.Stage.MARRIED, 0, 0, 0)));
        }
        for (int r : rings) {
            assertEquals(11128, r / 100);
        }
    }

    @Test
    void theStepDoesNothingForOfflineCouplesAndFormsNoneWithNobodyOnline() {
        BotCouples.form(A, B, false, 0);
        BotCouples.step(30 * DAY, new Random(3));
        assertEquals(1, BotCouples.all().size());
        assertFalse(BotWeddings.running());
    }

    @Test
    void theWeddingPackHasEveryBeat() {
        for (String node : List.of("Officiant", "Vows", "RingsGiven", "Pronounce", "Guest")) {
            String line = BotCouples.pick("Wedding", node, java.util.Map.of("GROOM", "Kit", "BRIDE", "Kat"));
            assertTrue(line != null && !line.contains("{"), node);
        }
        for (String node : List.of("Mention", "Sit", "Formed")) {
            assertNotNull(BotCouples.pick("Couple", node, java.util.Map.of("PARTNER", "Kat")), node);
        }
    }

    @Test
    void bestFriendsWearFriendshipRingsAndNeverMarry() {
        BotCouples.Couple f = BotCouples.form(A, B, BotCouples.Stage.FRIENDS, false, 0);
        assertEquals(BotCouples.Stage.FRIENDS, f.stage());
        assertEquals(BotCouples.Stage.FRIENDS, store.rows.get(f.id()).stage(), "stored as friends");
        assertEquals(11128, BotCouples.ringOf(f) / 100);
        assertTrue(java.util.Arrays.stream(BotCouples.FRIENDSHIP_RINGS).anyMatch(r -> r == BotCouples.ringOf(f)));
        assertFalse(BotCouples.readyToMarry(f, 100 * DAY));
        assertEquals("best friends do not marry", BotWeddings.start(f, "test"));
        assertNull(BotCouples.form(A, C, false, 0), "one pair per bot");
        assertEquals(0, BotCouples.coupleChair(f, null, null), "no loveseat for friends");
    }

    @Test
    void weddingOutfitsFollowEachOnesGenderAndTheBlessingIsSmall() {
        assertEquals(BotWeddings.TUXEDO, BotWeddings.outfitFor(0));
        assertEquals(BotWeddings.WEDDING_DRESS, BotWeddings.outfitFor(1));
        assertEquals(0, BotWeddings.blessExp(0));
        assertEquals(0, BotWeddings.blessExp(200), "nothing to gain at the cap");
        assertTrue(BotWeddings.blessExp(10) >= 1 && BotWeddings.blessExp(10) <= 20000);
        assertTrue(BotWeddings.blessExp(150) <= 20000, "capped");
        assertTrue(BotWeddings.blessExp(150) >= BotWeddings.blessExp(10), "grows with level");
    }
}
