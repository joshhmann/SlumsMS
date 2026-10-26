package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeLearning;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.SocialGate.Act;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.0 S9: hard limits sit outside learning. A bot learned all the way to Killer, saturated trends and live fads leave
 * ContentGuard (PG-13, game-only insults, never-AI), SocialGate's tiers and the scam caps exactly as they were; and
 * nothing in the learning code even names them.
 */
class LearningHardLimitsTest {
    static final int PLAYER = 4242;
    static final int BOT = BotStore.BOT_ID_BASE + 101;
    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);

    static final List<String> LINES = List.of("nice ks noob", "ur gear is trash lol", "im an ai language model",
            "as an AI i cant", "gg", "selling stuff cheap at fm", "you are an idiot irl");

    @BeforeEach
    void setUp() {
        SocialGate.useForTest(new SocialGateStore.Memory(), Runnable::run, clock::get);
        SocialGate.useRomanceOnForTest(id -> false);
        SocialGate.useBusyForTest(p -> false);
        LifeLearning.resetForTest(clock::get);
        TrendIndex.resetForTest(ZoneOffset.UTC);
        Fads.useForTest(new FadsTest.Fake(), new Random(1));
        LifeMemoryStore.useConnectionSourceForTest(() -> { throw new java.sql.SQLException("no db in tests"); });
    }

    @AfterEach
    void tearDown() {
        SocialGate.useForTest(null, null, null);
        SocialGate.useRomanceOnForTest(null);
        SocialGate.useBusyForTest(null);
        LifeLearning.resetForTest(null);
        TrendIndex.resetForTest(null);
        Fads.useForTest(null, null);
        System.clearProperty("solomapling.fads.noFile");
        LifeMemoryStore.useConnectionSourceForTest(null);
    }

    record Limits(List<String> guard, List<Boolean> ai, List<Boolean> gate, long scamMin, double scamShare) {
    }

    static Limits limits() {
        return new Limits(LINES.stream().map(l -> String.valueOf(ContentGuard.problem(l))).toList(),
                LINES.stream().map(ContentGuard::aiTalk).toList(),
                java.util.Arrays.stream(Act.values()).map(a -> SocialGate.rules(BOT, PLAYER, a).ok()).toList(),
                SocialHooks.scamMin(), SocialHooks.scamShare());
    }

    @Test
    void learnedValuesCannotTouchTheHardLimits() {
        Limits before = limits();
        // learn as hard as the code allows: a maximally plastic bot KS'd for 200 days, trends and fads saturated
        var memory = LifeMemoryStore.of(BOT);
        memory.stageSeed(new SocialProfile(-.9, .9), 1);
        for (int day = 0; day < 200; day++) {
            for (int i = 0; i < 20; i++) LifeLearning.stageKs(BOT, clock.get() + day * 86_400_000L + i);
        }
        assertTrue(memory.social().rivalPull() > .8, "the bot is as Killer as it gets");
        for (int p = 1; p <= 50; p++) TrendIndex.observeHunt(p, 30, 104040000, 1, clock.get());
        Fads.startForTest(Fads.Kind.PHRASE, 1, 0, BOT, clock.get());
        assertEquals(before, limits(), "ContentGuard, SocialGate tiers and scam caps are unchanged");
        assertFalse(SocialGate.rules(BOT, PLAYER, Act.TAUNT).ok(), "a fresh player still gets no spice from a Killer");
        assertFalse(SocialGate.rules(BOT, PLAYER, Act.FLIRT).ok(), "nor romance");
    }

    @Test
    void theLearningCodeNeverNamesAHardLimit() throws Exception {
        for (String file : List.of("src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java",
                "src/main/java/soloMapling/ArtificialPlayer/TrendIndex.java",
                "src/main/java/soloMapling/ArtificialPlayer/Fads.java")) {
            String code = Files.readString(Path.of(file));
            // the class comments say they stay outside; the code itself never calls or sets them
            String body = code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            for (String limit : List.of("SocialGate", "scamMin", "scamShare", "ContentGuard", "setProperty(\"solomapling.social",
                    "romance", "BotBrainSystem")) {
                assertFalse(body.contains(limit), file + " names " + limit);
            }
        }
    }
}
