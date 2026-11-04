package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Ticket 271 stage 6a: the memory-only views of the social layer (inspect {@code mind}/{@code bonds}/{@code mode},
 * {@code !botwhy} lines, the dashboard's bond totals). New code; the fail-before for the readers is that none existed.
 */
class SocialMetricsTest {

    /** Ids below the bot base give a memory that never touches a table. Unique per test: the store keeps them. */
    static final int RESTING = 970_001, UPSET = 970_002, NOT_LOADED = 970_003, UPSET_SHAPE = 970_004,
            UPSET_READ = 970_005;
    static final long T = 5_000_000_000L;
    final AtomicLong clock = new AtomicLong(T);

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get,
                id -> new double[]{.25, .25}, (id, p) -> { }, id -> null);
    }

    @AfterEach
    void tearDown() {
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
    }

    private static void upset(int id) {
        LifeMemoryStore.of(id).mind(m -> {
            m.feel(Emotion.ANGER, 0.9, T);
            m.feel(Emotion.PRIDE, 0.4, T);
            m.feel(Emotion.FEAR, 0.005, T);   // too small to list
            m.satisfy(Need.COMPANIONSHIP, 1.0, T);
            return null;
        });
    }

    @Test
    void aMindViewHasTheDocumentedShapeAndRanksEmotionsByHowFarFromRest() {
        upset(UPSET_SHAPE);
        Map<String, Object> mind = SocialMetrics.mindView(UPSET_SHAPE, T);
        assertEquals(List.of("mood", "moodWord", "traits", "emotions", "topEmotions", "needs", "strongestNeed"),
                List.copyOf(mind.keySet()));
        assertEquals(SocialMind.Trait.values().length, ((Map<?, ?>) mind.get("traits")).size());
        assertEquals(Emotion.values().length, ((Map<?, ?>) mind.get("emotions")).size());
        assertEquals(Need.values().length, ((Map<?, ?>) mind.get("needs")).size());
        List<?> top = (List<?>) mind.get("topEmotions");
        assertEquals(2, top.size(), "the nudge of fear is at rest, so it is not listed: " + top);
        Map<?, ?> first = (Map<?, ?>) top.get(0);
        assertEquals("anger", first.get("name"));
        assertTrue(((Number) first.get("value")).doubleValue() > 0.5, String.valueOf(first));
        assertEquals("pride", ((Map<?, ?>) top.get(1)).get("name"));
        Map<?, ?> strongest = (Map<?, ?>) mind.get("strongestNeed");
        assertNotNull(strongest.get("name"));
        assertTrue(((Number) strongest.get("value")).doubleValue() >= 0 && ((Number) strongest.get("value")).doubleValue() <= 1);
        assertTrue(((Number) mind.get("mood")).doubleValue() < 0.2, "angry: not a happy bot");
        Json.write(mind);   // writable
    }

    @Test
    void aRestingMindListsNoEmotionAndTheMoodWordFollowsTheNumber() {
        LifeMemoryStore.of(RESTING);
        Map<String, Object> mind = SocialMetrics.mindView(RESTING, T);
        assertTrue(((List<?>) mind.get("topEmotions")).isEmpty(), String.valueOf(mind.get("topEmotions")));
        assertEquals("neutral", SocialMetrics.moodWord(0));
        assertEquals("happy", SocialMetrics.moodWord(0.7));
        assertEquals("content", SocialMetrics.moodWord(0.2));
        assertEquals("low", SocialMetrics.moodWord(-0.2));
        assertEquals("miserable", SocialMetrics.moodWord(-0.9));
    }

    @Test
    void aBotWhoseMemoryIsNotLoadedHasNoMindAndNothingIsLoadedToAnswer() {
        int before = LifeMemoryStore.size();
        assertNull(SocialMetrics.mindView(NOT_LOADED, T));
        assertNull(SocialMetrics.snapshot(NOT_LOADED, T));
        assertEquals(before, LifeMemoryStore.size(), "reading never builds a memory, let alone reads a table");
        List<String> lines = SocialMetrics.whyLines(NOT_LOADED, null, T);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("Mode other"), lines.toString());
        assertTrue(lines.get(1).startsWith("Mind: not loaded"), lines.toString());
    }

    @Test
    void readingAMindDoesNotChangeItOrMarkItForASave() {
        upset(UPSET_READ);
        var memory = LifeMemoryStore.of(UPSET_READ);
        double before = memory.mind(m -> m.emotion(Emotion.ANGER, T));
        SocialMetrics.mindView(UPSET_READ, T + 3_600_000L);   // an hour later
        SocialMetrics.whyLines(UPSET_READ, null, T + 3_600_000L);
        assertEquals(before, memory.mind(m -> m.emotion(Emotion.ANGER, T)), 1e-12, "the stored value is untouched");
    }

    @Test
    void whyLinesNameTheModeMoodTopTwoEmotionsAndStrongestNeed() {
        upset(UPSET);
        List<String> training = SocialMetrics.whyLines(UPSET, mock(TrainingBot.class), T);
        assertTrue(training.get(0).startsWith("Mode training"), training.toString());
        String mind = training.get(1);
        assertTrue(mind.startsWith("Mind: mood "), mind);
        assertTrue(mind.contains("feels anger ") && mind.contains(", pride 0."), mind);
        assertTrue(mind.contains("; strongest need "), mind);
        assertTrue(SocialMetrics.whyLines(UPSET, mock(SocialBot.class), T).get(0).startsWith("Mode social"));
        assertTrue(SocialMetrics.whyLines(UPSET, mock(TownWandererBot.class), T).get(0).startsWith("Mode town"));
        LifeMemoryStore.of(RESTING);
        assertTrue(SocialMetrics.whyLines(RESTING, null, T).get(1).contains("emotions at rest"));
    }

    @Test
    void theModeIsTheRunningTypeOrNullOutsideTheThree() {
        assertEquals("training", SocialMetrics.mode(mock(TrainingBot.class)));
        assertEquals("social", SocialMetrics.mode(mock(SocialBot.class)));
        assertEquals("town", SocialMetrics.mode(mock(TownWandererBot.class)));
        assertNull(SocialMetrics.mode(mock(BotSM.class)));
    }

    @Test
    void bondsViewListsTheStrongestBondsAndTheDashboardTotalsParseTheBondStatsLine() {
        int a = 1_000_000_501, b = 1_000_000_502;
        assertEquals(0, SocialMetrics.bondsView(a).get("count"));
        BotBonds.stage(a, b, AffinityState.FRIEND);
        Map<String, Object> view = SocialMetrics.bondsView(a);
        assertEquals(List.of("count", "max", "top"), List.copyOf(view.keySet()));
        assertEquals(1, view.get("count"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) view.get("top")).getFirst();
        assertEquals(List.of("otherId", "state", "score", "kind", "hostile"), List.copyOf(row.keySet()));
        assertEquals(b, row.get("otherId"));
        assertEquals("friend", row.get("state"));

        Map<String, Object> totals = SocialMetrics.bondTotals();
        assertEquals(2L, totals.get("bonds"), "stage() makes both directions: " + totals.get("text"));
        assertEquals(2L, totals.get("holders"));
        assertTrue(((String) totals.get("text")).startsWith("bonds 2 held by 2 bots"), "the line the parser reads");
    }
}
